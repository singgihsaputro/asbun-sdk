package id.ojolmitra.driver.voice

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.get
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import platform.AVFAudio.AVAudioEngine
import platform.AVFAudio.AVAudioPCMBuffer
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryOptionAllowBluetoothA2DP
import platform.AVFAudio.AVAudioSessionCategoryOptionDefaultToSpeaker
import platform.AVFAudio.AVAudioSessionCategoryOptionDuckOthers
import platform.AVFAudio.AVAudioSessionCategoryPlayAndRecord
import platform.AVFAudio.AVAudioSessionModeDefault
import platform.AVFAudio.AVSpeechBoundary
import platform.AVFAudio.AVSpeechSynthesisVoice
import platform.AVFAudio.AVSpeechSynthesizer
import platform.AVFAudio.AVSpeechSynthesizerDelegateProtocol
import platform.AVFAudio.AVSpeechUtterance
import platform.AVFAudio.AVSpeechUtteranceDefaultSpeechRate
import platform.AVFAudio.setActive
import platform.Foundation.NSLocale
import platform.Foundation.localeIdentifier
import platform.Speech.SFSpeechAudioBufferRecognitionRequest
import platform.Speech.SFSpeechRecognitionTask
import platform.Speech.SFSpeechRecognizer
import platform.Speech.SFSpeechRecognizerAuthorizationStatus
import platform.Speech.SFTranscription
import platform.darwin.NSObject
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Implementasi iOS (ditulis di Kotlin/Native):
 * - Speech-to-text: SFSpeechRecognizer + AVAudioEngine (deteksi jeda bicara otomatis)
 * - Text-to-speech: AVSpeechSynthesizer dengan suara id-ID
 */
@OptIn(ExperimentalForeignApi::class)
class IosVoiceEngine : VoiceEngine {

    override var listener: VoiceEngineListener? = null

    private val scope = MainScope()
    private val audioEngine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer? = null
    private var recognizerTag: String? = null
    private var request: SFSpeechAudioBufferRecognitionRequest? = null
    private var task: SFSpeechRecognitionTask? = null

    private var sessionActive = false
    private var sessionId = 0
    private var lastPartial = ""
    private var silenceJob: Job? = null
    private var tapCounter = 0

    private val synthesizer = AVSpeechSynthesizer()
    private val synthesizerDelegate = SynthesizerDelegate(this)
    private val utteranceIds = mutableListOf<Pair<AVSpeechUtterance, String>>()

    init {
        synthesizer.delegate = synthesizerDelegate
    }

    override val isRecognitionAvailable: Boolean
        get() = recognizerFor(VOICE_LANGUAGE)?.isAvailable() == true

    private fun recognizerFor(tag: String): SFSpeechRecognizer? {
        if (recognizer == null || recognizerTag != tag) {
            // initWithLocale: mengembalikan nil untuk bahasa yang tidak didukung -> cek dulu,
            // kalau tidak ada pakai bahasa perangkat.
            val supported = SFSpeechRecognizer.supportedLocales().any {
                (it as? NSLocale)?.localeIdentifier?.replace('_', '-').equals(tag, ignoreCase = true)
            }
            recognizer = if (supported) SFSpeechRecognizer(locale = NSLocale(localeIdentifier = tag)) else SFSpeechRecognizer()
            recognizerTag = tag
        }
        return recognizer
    }

    private fun configureAudioSession() {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(
            AVAudioSessionCategoryPlayAndRecord,
            mode = AVAudioSessionModeDefault,
            options = AVAudioSessionCategoryOptionDefaultToSpeaker or
                AVAudioSessionCategoryOptionDuckOthers or
                AVAudioSessionCategoryOptionAllowBluetoothA2DP,
            error = null,
        )
        session.setActive(true, error = null)
    }

    // ------------------------------------------------------------------ STT

    override fun startListening(languageTag: String) {
        scope.launch {
            when (SFSpeechRecognizer.authorizationStatus()) {
                SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized ->
                    requestMicrophoneThenBegin(languageTag)
                SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusNotDetermined ->
                    SFSpeechRecognizer.requestAuthorization { status ->
                        scope.launch {
                            if (status == SFSpeechRecognizerAuthorizationStatus.SFSpeechRecognizerAuthorizationStatusAuthorized) {
                                requestMicrophoneThenBegin(languageTag)
                            } else {
                                listener?.onListeningError(VoiceError.PERMISSION_DENIED)
                            }
                        }
                    }
                else -> listener?.onListeningError(VoiceError.PERMISSION_DENIED)
            }
        }
    }

    private fun requestMicrophoneThenBegin(languageTag: String) {
        AVAudioSession.sharedInstance().requestRecordPermission { granted ->
            scope.launch {
                if (granted) begin(languageTag) else listener?.onListeningError(VoiceError.PERMISSION_DENIED)
            }
        }
    }

    private fun begin(languageTag: String) {
        teardownAudio()
        val rec = recognizerFor(languageTag)
        if (rec == null || !rec.isAvailable()) {
            listener?.onListeningError(VoiceError.NOT_AVAILABLE)
            return
        }
        if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        configureAudioSession()

        val req = SFSpeechAudioBufferRecognitionRequest()
        req.shouldReportPartialResults = true
        request = req

        val input = audioEngine.inputNode
        val format = input.outputFormatForBus(0u)
        if (format.sampleRate <= 0.0 || format.channelCount == 0u) {
            listener?.onListeningError(VoiceError.NOT_AVAILABLE)
            return
        }

        val myId = ++sessionId
        input.removeTapOnBus(0u)
        input.installTapOnBus(0u, bufferSize = 1024u, format = format) { buffer, _ ->
            if (buffer != null) {
                req.appendAudioPCMBuffer(buffer)
                if (++tapCounter % 3 == 0) {
                    val level = soundLevel(buffer)
                    scope.launch { if (sessionActive && myId == sessionId) listener?.onSoundLevel(level) }
                }
            }
        }
        audioEngine.prepare()
        if (!audioEngine.startAndReturnError(null)) {
            teardownAudio()
            listener?.onListeningError(VoiceError.UNKNOWN)
            return
        }

        sessionActive = true
        lastPartial = ""
        listener?.onListeningStarted()

        task = rec.recognitionTaskWithRequest(req) { result, error ->
            scope.launch {
                if (!sessionActive || myId != sessionId) return@launch
                if (result != null) {
                    val best = result.bestTranscription.formattedString
                    if (result.isFinal()) {
                        val alternatives = result.transcriptions
                            .mapNotNull { (it as? SFTranscription)?.formattedString }
                            .filter { it.isNotBlank() }
                        finish(alternatives.ifEmpty { listOf(best) })
                        return@launch
                    }
                    if (best.isNotBlank() && best != lastPartial) {
                        lastPartial = best
                        listener?.onPartialResult(best)
                        scheduleEndOfSpeech(myId, 1_300)
                    }
                }
                if (error != null) {
                    if (lastPartial.isNotBlank()) finish(listOf(lastPartial)) else fail(VoiceError.NO_MATCH)
                }
            }
        }
        // Tidak ada suara sama sekali dalam 7 detik -> anggap tidak ada ucapan.
        scheduleEndOfSpeech(myId, 7_000)
    }

    /** iOS tidak otomatis berhenti saat driver diam -> kita deteksi jeda lalu minta hasil final. */
    private fun scheduleEndOfSpeech(id: Int, afterMs: Long) {
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(afterMs)
            if (!sessionActive || id != sessionId) return@launch
            requestFinalResult(id)
        }
    }

    private fun requestFinalResult(id: Int) {
        request?.endAudio()
        if (audioEngine.isRunning()) audioEngine.stop()
        audioEngine.inputNode.removeTapOnBus(0u)
        // Cadangan bila recognizer tidak mengirim hasil final.
        silenceJob?.cancel()
        silenceJob = scope.launch {
            delay(1_500)
            if (!sessionActive || id != sessionId) return@launch
            if (lastPartial.isNotBlank()) finish(listOf(lastPartial)) else fail(VoiceError.NO_MATCH)
        }
    }

    private fun finish(alternatives: List<String>) {
        if (!sessionActive) return
        sessionActive = false
        teardownAudio()
        listener?.onFinalResult(alternatives)
    }

    private fun fail(error: VoiceError) {
        if (!sessionActive) return
        sessionActive = false
        teardownAudio()
        listener?.onListeningError(error)
    }

    private fun teardownAudio() {
        silenceJob?.cancel()
        silenceJob = null
        if (audioEngine.isRunning()) audioEngine.stop()
        audioEngine.inputNode.removeTapOnBus(0u)
        request?.endAudio()
        task?.cancel()
        request = null
        task = null
    }

    override fun stopListening() {
        scope.launch { if (sessionActive) requestFinalResult(sessionId) }
    }

    override fun cancelListening() {
        scope.launch {
            sessionActive = false
            sessionId++
            teardownAudio()
        }
    }

    private fun soundLevel(buffer: AVAudioPCMBuffer): Float {
        val channels = buffer.floatChannelData ?: return 0f
        val samples = channels[0] ?: return 0f
        val frames = buffer.frameLength.toInt()
        if (frames <= 0) return 0f
        var sum = 0.0
        var count = 0
        var i = 0
        while (i < frames) {
            val s = samples[i].toDouble()
            sum += s * s
            count++
            i += 4
        }
        val rms = sqrt(sum / count)
        val db = 20.0 * log10(rms + 1e-7) // senyap ~ -60 dB, bicara ~ -30..-10 dB
        return ((db + 55.0) / 45.0).coerceIn(0.0, 1.0).toFloat()
    }

    // ------------------------------------------------------------------ TTS

    override fun speak(text: String, utteranceId: String, languageTag: String) {
        scope.launch {
            if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
            val utterance = AVSpeechUtterance.speechUtteranceWithString(text)
            utterance.voice = AVSpeechSynthesisVoice.voiceWithLanguage(languageTag)
            utterance.rate = AVSpeechUtteranceDefaultSpeechRate
            configureAudioSession() // keluar lewat speaker walau kategori PlayAndRecord
            utteranceIds += utterance to utteranceId
            synthesizer.speakUtterance(utterance)
        }
    }

    override fun stopSpeaking() {
        scope.launch {
            if (synthesizer.isSpeaking()) synthesizer.stopSpeakingAtBoundary(AVSpeechBoundary.AVSpeechBoundaryImmediate)
        }
    }

    override fun shutdown() {
        cancelListening()
        stopSpeaking()
    }

    internal fun utteranceStarted(utterance: AVSpeechUtterance) {
        val id = utteranceIds.firstOrNull { it.first === utterance }?.second ?: return
        scope.launch { listener?.onSpeakingStarted(id) }
    }

    internal fun utteranceFinished(utterance: AVSpeechUtterance) {
        val entry = utteranceIds.firstOrNull { it.first === utterance } ?: return
        utteranceIds.remove(entry)
        scope.launch { listener?.onSpeakingDone(entry.second) }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class SynthesizerDelegate(private val owner: IosVoiceEngine) : NSObject(), AVSpeechSynthesizerDelegateProtocol {
    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didStartSpeechUtterance: AVSpeechUtterance) {
        owner.utteranceStarted(didStartSpeechUtterance)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didFinishSpeechUtterance: AVSpeechUtterance) {
        owner.utteranceFinished(didFinishSpeechUtterance)
    }

    @ObjCSignatureOverride
    override fun speechSynthesizer(synthesizer: AVSpeechSynthesizer, didCancelSpeechUtterance: AVSpeechUtterance) {
        owner.utteranceFinished(didCancelSpeechUtterance)
    }
}
