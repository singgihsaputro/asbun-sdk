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
import android.util.Log
import android.speech.RecognitionSupport
import android.speech.RecognitionSupportCallback
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
        // Android 13+: only there can the app confirm the language is installed offline before listening.
        get() = Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isRecognitionAvailable(appContext)

    private var recognizer: SpeechRecognizer? = null
    /**
     * Second instance of the default recognizer, used for wake sessions with EXTRA_PREFER_OFFLINE
     * ("only use an offline engine"). A separate instance keeps wake callbacks out of command sessions.
     */
    private var wakeRecognizer: SpeechRecognizer? = null
    private var wakeActive = false
    private val activeRecognizer get() = if (wakeActive) wakeRecognizer else recognizer
    /** Active listening session; events outside it are ignored (see the [VoiceEngineListener] contract). */
    private var sessionActive = false
    /** Bumped by every start and cancel; a posted start or support check from an older value is stale. */
    private var startGen = 0
    /** Languages the recognizer reported as installed for offline use (wake mode, Android 13+). */
    private val wakeLanguagesInstalled = mutableSetOf<String>()
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
        // Synchronous: callbacks of the previous session already queued on main must be dropped,
        // and a start superseded before its posted body runs must not run at all.
        sessionActive = false
        val gen = ++startGen
        main.post {
            if (gen != startGen) return@post
            val wake = mode == ListenMode.WAKE
            if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                // Wake sessions never ask: the permission screen pauses the app, which restarts wake -> endless loop.
                if (!wake) requestPermission()
                listener?.onListeningError(VoiceError.PERMISSION_DENIED)
                return@post
            }
            if (if (wake) !isWakeListeningAvailable else !isRecognitionAvailable) {
                listener?.onListeningError(VoiceError.NOT_AVAILABLE)
                return@post
            }
            if (wake && Build.VERSION.SDK_INT >= 33 && languageTag !in wakeLanguagesInstalled) {
                checkWakeLanguage(languageTag, gen)
                return@post
            }
            begin(languageTag, wake)
        }
    }

    /**
     * Ask the recognizer whether [languageTag] is installed for offline use before a wake session:
     * wake audio must never go online, so without a confirmed offline language there is no wake
     * session (NOT_AVAILABLE). Only a positive answer is cached: after the user installs the language,
     * the next wake start sees it.
     */
    @android.annotation.TargetApi(33)
    private fun checkWakeLanguage(languageTag: String, gen: Int) {
        val r = wakeRecognizer ?: createWakeRecognizer().also { wakeRecognizer = it }
        val intent = recognizerIntent(languageTag, wake = true)
        // Some services answer more than once (seen: error 14, then the same result twice), so act
        // once per check: a result wins; an error counts only if no result follows shortly.
        var answered = false
        r.checkRecognitionSupport(intent, appContext.mainExecutor, object : RecognitionSupportCallback {
            override fun onSupportResult(support: RecognitionSupport) {
                if (answered || gen != startGen) return
                answered = true
                if (support.installedOnDeviceLanguages.any { sameLanguage(it, languageTag) }) {
                    wakeLanguagesInstalled += languageTag
                    begin(languageTag, wake = true)
                } else {
                    Log.i(TAG, "$languageTag not installed offline: ${support.installedOnDeviceLanguages}")
                    listener?.onListeningError(VoiceError.NOT_AVAILABLE)
                }
            }

            override fun onError(error: Int) {
                main.postDelayed({
                    if (answered || gen != startGen) return@postDelayed
                    answered = true
                    Log.i(TAG, "Recognition support check failed: error $error")
                    // Can't confirm offline support: don't risk streaming car audio.
                    listener?.onListeningError(VoiceError.NOT_AVAILABLE)
                }, SUPPORT_ERROR_GRACE_MS)
            }
        })
    }

    private fun begin(languageTag: String, wake: Boolean) {
        tts?.stop()
        // Starting either mode cancels the other recognizer first; no events from it after that.
        sessionActive = false
        activeRecognizer?.cancel()
        wakeActive = wake
        val r = if (wake) {
            // A fresh instance per wake session: the service finishes the previous session only when the
            // next one starts, and its late error must not reach (and end) the new session.
            // Destroy the old one a moment later: destroying it right before the new one starts makes
            // the new session fail with ERROR_SERVER_DISCONNECTED. Its callbacks are already ignored.
            wakeRecognizer?.let { old -> main.postDelayed({ old.destroy() }, 1_000) }
            createWakeRecognizer().also { wakeRecognizer = it }
        } else {
            recognizer ?: newRecognizer().also { recognizer = it }
        }
        r.cancel()
        sessionActive = true
        lastPartial = ""
        r.startListening(recognizerIntent(languageTag, wake))
    }

    private fun recognizerIntent(languageTag: String, wake: Boolean): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, appContext.packageName)
            // Commands only: a short silence limit also ends a session that hasn't heard speech yet,
            // which would make wake sessions restart every ~3 s and drop words in the gaps.
            // Int, not Long: recognizers read these with getInt and ignore a Long (default 0).
            if (!wake) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, endOfSpeechMs.toInt())
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (endOfSpeechMs * 3 / 4).toInt())
            }
            if (wake) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }

    private fun createWakeRecognizer(): SpeechRecognizer = newRecognizer()

    private fun newRecognizer(): SpeechRecognizer {
        lateinit var r: SpeechRecognizer
        r = SpeechRecognizer.createSpeechRecognizer(appContext)
        r.setRecognitionListener(listenerFor { r })
        return r
    }

    override fun stopListening() {
        main.post { if (sessionActive) activeRecognizer?.stopListening() }
    }

    override fun cancelListening() {
        // Synchronous (see the VoiceEngineListener contract); only the recognizer call is deferred.
        sessionActive = false
        ++startGen
        main.post { activeRecognizer?.cancel() }
    }

    private fun listenerFor(owner: () -> SpeechRecognizer): RecognitionListener = object : RecognitionListener {
        // Only the recognizer instance of the current session may emit; stale callbacks are dropped.
        private val live get() = sessionActive && activeRecognizer === owner()

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

private const val TAG = "AndroidVoiceEngine"

/** How long a failed support check waits for a late result before reporting NOT_AVAILABLE. */
private const val SUPPORT_ERROR_GRACE_MS = 1_000L

/** "id-ID", "id_ID", "in-ID" and "id" are the same language (Android still uses the legacy "in" code). */
private fun sameLanguage(a: String, b: String): Boolean {
    fun lang(tag: String) = tag.substringBefore('-').substringBefore('_').lowercase().let { if (it == "in") "id" else it }
    return lang(a) == lang(b)
}
