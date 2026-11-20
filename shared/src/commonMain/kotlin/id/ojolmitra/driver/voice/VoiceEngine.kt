package id.ojolmitra.driver.voice

/** Bahasa pengenalan suara & TTS. */
const val VOICE_LANGUAGE = "id-ID"

enum class VoiceError(val message: String) {
    NO_MATCH("Suara tidak dikenali. Coba ulangi."),
    PERMISSION_DENIED("Izin mikrofon / pengenalan suara belum diberikan."),
    NOT_AVAILABLE("Pengenalan suara tidak tersedia di perangkat ini."),
    NETWORK("Koneksi untuk pengenalan suara bermasalah."),
    BUSY("Mesin pengenalan suara sedang sibuk."),
    UNKNOWN("Terjadi kesalahan pada pengenalan suara."),
}

/**
 * Event dari engine suara platform. Selalu dipanggil di main thread.
 *
 * Kontrak satu sesi mendengarkan: [onListeningStarted] -> (level/partial)* -> tepat SATU event
 * terminal: [onFinalResult], [onListeningError], atau [onListeningEnded] (berhenti tanpa hasil).
 * Setelah event terminal atau [VoiceEngine.cancelListening], engine tidak boleh mengirim event
 * lagi untuk sesi tersebut.
 */
interface VoiceEngineListener {
    fun onListeningStarted() {}
    /** Level suara mikrofon 0..1 untuk animasi gelombang. */
    fun onSoundLevel(level: Float) {}
    fun onPartialResult(text: String) {}
    /** [alternatives] berisi kandidat transkrip, yang pertama paling mungkin. */
    fun onFinalResult(alternatives: List<String>) {}
    fun onListeningError(error: VoiceError) {}
    fun onListeningEnded() {}
    fun onSpeakingStarted(utteranceId: String) {}
    fun onSpeakingDone(utteranceId: String) {}
}

/**
 * Abstraksi speech-to-text + text-to-speech.
 * - Android: android.speech.SpeechRecognizer + TextToSpeech
 * - iOS: SFSpeechRecognizer + AVAudioEngine + AVSpeechSynthesizer
 */
interface VoiceEngine {
    val isRecognitionAvailable: Boolean
    var listener: VoiceEngineListener?
    fun startListening(languageTag: String = VOICE_LANGUAGE)
    /** Berhenti merekam dan minta hasil final secepatnya. */
    fun stopListening()
    /** Batalkan tanpa hasil. */
    fun cancelListening()
    fun speak(text: String, utteranceId: String, languageTag: String = VOICE_LANGUAGE)
    fun stopSpeaking()
    fun shutdown()
}

/** Engine tanpa suara: dipakai di test & sebagai fallback (perintah bisa diketik). */
class SilentVoiceEngine : VoiceEngine {
    override val isRecognitionAvailable: Boolean = false
    override var listener: VoiceEngineListener? = null
    override fun startListening(languageTag: String) {
        listener?.onListeningError(VoiceError.NOT_AVAILABLE)
        listener?.onListeningEnded()
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
