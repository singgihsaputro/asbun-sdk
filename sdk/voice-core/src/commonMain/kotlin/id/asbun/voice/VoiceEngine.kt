package id.asbun.voice

/** Low-level speech errors reported by a [VoiceEngine]. */
public enum class VoiceError {
    NO_MATCH,
    PERMISSION_DENIED,
    NOT_AVAILABLE,
    NETWORK,
    BUSY,
    UNKNOWN,
}

/** [WAKE]: a background session listening for the wake phrase; audio must never leave the phone (offline recognition only). */
public enum class ListenMode { COMMAND, WAKE }

/**
 * Events from a platform speech engine. Always delivered on the main thread.
 *
 * Contract for one listening session: [onListeningStarted] -> (level/partial)* -> exactly ONE
 * terminal event: [onFinalResult], [onListeningError] or [onListeningEnded] (stopped without a
 * result). After a terminal event, or as soon as [VoiceEngine.cancelListening] or
 * [VoiceEngine.startListening] returns, the engine must not send further events for the previous
 * session: it invalidates that session synchronously, even when the platform work is deferred
 * (callbacks already queued on the main thread are dropped).
 */
public interface VoiceEngineListener {
    public fun onListeningStarted() {}

    /** Microphone level from 0 to 1, for animations. */
    public fun onSoundLevel(level: Float) {}

    public fun onPartialResult(text: String) {}

    /** [alternatives] holds transcript candidates, most likely first. */
    public fun onFinalResult(alternatives: List<String>) {}

    public fun onListeningError(error: VoiceError) {}

    public fun onListeningEnded() {}

    public fun onSpeakingStarted(utteranceId: String) {}

    public fun onSpeakingDone(utteranceId: String) {}
}

/**
 * Speech-to-text and text-to-speech for one platform.
 * Implementations: `AndroidVoiceEngine` (voice-android) and `IosVoiceEngine` (voice-ios).
 */
public interface VoiceEngine {
    public val isRecognitionAvailable: Boolean
    public var listener: VoiceEngineListener?
    public fun startListening(languageTag: String)

    /** True when [ListenMode.WAKE] sessions can run offline (audio never leaves the phone). */
    public val isWakeListeningAvailable: Boolean get() = false

    public fun startListening(languageTag: String, mode: ListenMode) {
        startListening(languageTag)
    }

    /** Stop recording and deliver the final result as soon as possible. */
    public fun stopListening()

    /** Stop without a result. Invalidates the session synchronously: no events for it after this returns. */
    public fun cancelListening()
    public fun speak(text: String, utteranceId: String, languageTag: String)
    public fun stopSpeaking()
    public fun shutdown()
}

/** Engine without audio: recognition unavailable, speech finishes immediately. For tests and typed input. */
public class SilentVoiceEngine : VoiceEngine {
    override val isRecognitionAvailable: Boolean = false
    override var listener: VoiceEngineListener? = null

    override fun startListening(languageTag: String) {
        listener?.onListeningError(VoiceError.NOT_AVAILABLE)
    }

    override fun stopListening() {}
    override fun cancelListening() {}

    override fun speak(text: String, utteranceId: String, languageTag: String) {
        listener?.onSpeakingStarted(utteranceId)
        listener?.onSpeakingDone(utteranceId)
    }

    override fun stopSpeaking() {}
    override fun shutdown() {}
}
