package id.asbun.voice.android

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import id.asbun.voice.ListenMode
import id.asbun.voice.VoiceEngine
import id.asbun.voice.VoiceEngineListener
import id.asbun.voice.VoiceError
import java.util.Locale

/**
 * Android engine: [SpeechRecognizer] for speech-to-text and [TextToSpeech] for spoken replies.
 *
 * @param requestPermission called when RECORD_AUDIO is not granted yet (the Activity asks for it).
 * @param endOfSpeechMs silence after the last word that ends a request (a hint to the recognizer).
 */
public class AndroidVoiceEngine(
    context: Context,
    private val requestPermission: () -> Unit = {},
    private val endOfSpeechMs: Long = 1_300,
) : VoiceEngine {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    override var listener: VoiceEngineListener? = null

    override val isRecognitionAvailable: Boolean
        get() = SpeechRecognizer.isRecognitionAvailable(appContext)

    override val isWakeListeningAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    private var recognizer: SpeechRecognizer? = null
    /** On-device recognizer used for wake sessions (API 31+), created lazily. */
    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeActive = false
    private val activeRecognizer get() = if (wakeActive) wakeRecognizer else recognizer
    /** Active listening session; events outside it are ignored (see the [VoiceEngineListener] contract). */
    private var sessionActive = false
    private var lastPartial: String = ""

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private val pendingSpeech = mutableListOf<Pair<String, String>>()
    private var pendingLanguage: String = "id-ID"

    init {
        tts = TextToSpeech(appContext) { status ->
            main.post {
                ttsReady = status == TextToSpeech.SUCCESS
                if (ttsReady) configureTts()
                val pending = pendingSpeech.toList()
                pendingSpeech.clear()
                pending.forEach { (text, id) -> speak(text, id, pendingLanguage) }
            }
        }
    }

    private fun configureTts() {
        val engine = tts ?: return
        engine.setSpeechRate(1.05f)
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                main.post { listener?.onSpeakingStarted(utteranceId.orEmpty()) }
            }

            override fun onDone(utteranceId: String?) {
                main.post { listener?.onSpeakingDone(utteranceId.orEmpty()) }
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                main.post { listener?.onSpeakingDone(utteranceId.orEmpty()) }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                main.post { listener?.onSpeakingDone(utteranceId.orEmpty()) }
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                main.post { listener?.onSpeakingDone(utteranceId.orEmpty()) }
            }
        })
    }

    // ------------------------------------------------------------------ STT

    override fun startListening(languageTag: String): Unit = start(languageTag, ListenMode.COMMAND)

    override fun startListening(languageTag: String, mode: ListenMode): Unit = start(languageTag, mode)

    private fun start(languageTag: String, mode: ListenMode) {
        main.post {
            val wake = mode == ListenMode.WAKE
            if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                requestPermission()
                listener?.onListeningError(VoiceError.PERMISSION_DENIED)
                return@post
            }
            if (if (wake) !isWakeListeningAvailable else !isRecognitionAvailable) {
                listener?.onListeningError(VoiceError.NOT_AVAILABLE)
                return@post
            }
            tts?.stop()
            // Starting either mode cancels the other recognizer first; no events from it after that.
            sessionActive = false
            activeRecognizer?.cancel()
            wakeActive = wake
            val r = if (wake) {
                wakeRecognizer ?: createWakeRecognizer().also { wakeRecognizer = it }
            } else {
                recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
                    it.setRecognitionListener(listenerFor(false))
                    recognizer = it
                }
            }
            r.cancel()
            sessionActive = true
            lastPartial = ""
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechMs)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechMs * 3 / 4)
                if (wake) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            r.startListening(intent)
        }
    }

    @android.annotation.TargetApi(31)
    private fun createWakeRecognizer(): SpeechRecognizer =
        SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext).also { it.setRecognitionListener(listenerFor(true)) }

    override fun stopListening() {
        main.post { if (sessionActive) activeRecognizer?.stopListening() }
    }

    override fun cancelListening() {
        main.post {
            sessionActive = false
            activeRecognizer?.cancel()
        }
    }

    private fun listenerFor(wake: Boolean): RecognitionListener = object : RecognitionListener {
        // Only the recognizer of the current session may emit; stale callbacks from a cancelled one are dropped.
        private val live get() = sessionActive && wakeActive == wake

        override fun onReadyForSpeech(params: Bundle?) {
            if (live) listener?.onListeningStarted()
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // rmsdB is roughly -2..10 dB -> 0..1
            if (live) listener?.onSoundLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            if (!live) return
            sessionActive = false
            // Sometimes the partial result was right but the engine timed out: use it.
            if ((error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) && lastPartial.isNotBlank()) {
                listener?.onFinalResult(listOf(lastPartial))
                return
            }
            listener?.onListeningError(mapError(error))
        }

        override fun onResults(results: Bundle?) {
            if (!live) return
            sessionActive = false
            val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty().filter { it.isNotBlank() }
            when {
                texts.isNotEmpty() -> listener?.onFinalResult(texts)
                lastPartial.isNotBlank() -> listener?.onFinalResult(listOf(lastPartial))
                else -> listener?.onListeningError(VoiceError.NO_MATCH)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (!live) return
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                lastPartial = text
                listener?.onPartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun mapError(code: Int): VoiceError = when (code) {
        SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> VoiceError.NO_MATCH
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> VoiceError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> VoiceError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_TOO_MANY_REQUESTS -> VoiceError.BUSY
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> VoiceError.NOT_AVAILABLE
        else -> VoiceError.UNKNOWN
    }

    // ------------------------------------------------------------------ TTS

    override fun speak(text: String, utteranceId: String, languageTag: String) {
        main.post {
            val engine = tts
            when {
                engine == null -> listener?.onSpeakingDone(utteranceId)
                !ttsReady -> {
                    pendingLanguage = languageTag
                    pendingSpeech += text to utteranceId
                }
                else -> {
                    applyLanguage(engine, languageTag)
                    val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, Bundle(), utteranceId)
                    if (result != TextToSpeech.SUCCESS) listener?.onSpeakingDone(utteranceId)
                }
            }
        }
    }

    private var ttsLanguage: String? = null

    /** Uses the requested language, or the device language when its voice data is missing. */
    private fun applyLanguage(engine: TextToSpeech, languageTag: String) {
        if (ttsLanguage == languageTag) return
        ttsLanguage = languageTag
        val result = engine.setLanguage(Locale.forLanguageTag(languageTag))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.getDefault())
        }
    }

    override fun stopSpeaking() {
        main.post {
            pendingSpeech.clear()
            tts?.stop()
        }
    }

    override fun shutdown() {
        main.post {
            sessionActive = false
            recognizer?.destroy()
            recognizer = null
            wakeRecognizer?.destroy()
            wakeRecognizer = null
            tts?.shutdown()
            tts = null
        }
    }
}
