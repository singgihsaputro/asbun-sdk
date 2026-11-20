package id.ojolmitra.driver.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class VoicePhase { IDLE, LISTENING, PROCESSING, SPEAKING }

/** Untuk apa asisten sedang mendengarkan. */
enum class ListenContext {
    /** Perintah bebas (tombol mic). */
    GENERAL,
    /** Ada order masuk: tunggu "terima" / "tolak". */
    ORDER_OFFER,
    /** Asisten menanyakan isi chat. */
    AWAITING_CHAT,
    /** Dikte langsung dari layar chat. */
    DICTATION,
}

data class VoiceLogEntry(
    val id: Long,
    val heard: String,
    val response: String,
    val success: Boolean,
    val timeLabel: String,
)

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val context: ListenContext = ListenContext.GENERAL,
    val partialText: String = "",
    val lastHeard: String? = null,
    val lastResponse: String? = null,
    val level: Float = 0f,
    /** Panel asisten dibuka manual oleh driver (tombol mic). */
    val panelPinned: Boolean = false,
    val error: String? = null,
    val recognitionAvailable: Boolean = true,
    val log: List<VoiceLogEntry> = emptyList(),
)

/**
 * Orkestrasi engine suara: dengar -> proses -> bicara -> (opsional) dengar lagi.
 * Semua callback berjalan di main thread sehingga aman memodifikasi state.
 */
class VoiceAssistant(
    private val engine: VoiceEngine,
    private val scope: CoroutineScope,
) : VoiceEngineListener {

    private val _state = MutableStateFlow(VoiceUiState(recognitionAvailable = engine.isRecognitionAvailable))
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    /** Dipanggil saat ada transkrip final. */
    var onResult: (alternatives: List<String>, context: ListenContext) -> Unit = { _, _ -> }

    /** Dipanggil saat mendengarkan gagal (mis. tidak ada suara). */
    var onListenFailed: (error: VoiceError, context: ListenContext) -> Unit = { _, _ -> }

    private data class Utterance(val text: String, val listenAfter: ListenContext?)

    private val queue = ArrayDeque<Utterance>()
    private var currentUtteranceId: String? = null
    private var listenAfterSpeech: ListenContext? = null
    private var utteranceCounter = 0
    private var logCounter = 0L
    private var autoClose: Job? = null

    init {
        engine.listener = this
    }

    val phase: VoicePhase get() = _state.value.phase

    /** Mulai mendengarkan. Kalau sedang bicara, bicara dihentikan (barge-in). */
    fun listen(context: ListenContext = ListenContext.GENERAL, pinPanel: Boolean = false) {
        autoClose?.cancel()
        if (!engine.isRecognitionAvailable) {
            _state.update {
                it.copy(
                    phase = VoicePhase.IDLE,
                    context = context,
                    panelPinned = pinPanel || it.panelPinned,
                    error = VoiceError.NOT_AVAILABLE.message + " Ketik perintah di bawah untuk simulasi.",
                    recognitionAvailable = false,
                )
            }
            return
        }
        if (phase == VoicePhase.SPEAKING) {
            queue.clear()
            listenAfterSpeech = null
            currentUtteranceId = null
            engine.stopSpeaking()
        }
        if (phase == VoicePhase.LISTENING) engine.cancelListening()
        _state.update {
            it.copy(
                phase = VoicePhase.LISTENING,
                context = context,
                partialText = "",
                error = null,
                level = 0f,
                panelPinned = pinPanel || it.panelPinned,
            )
        }
        engine.startListening(VOICE_LANGUAGE)
    }

    /**
     * Ucapkan [text]. Jika sedang mendengarkan, ucapan diantrikan sampai selesai.
     * [listenAfter] = langsung dengar lagi setelah selesai bicara (mode hands-free).
     */
    fun speak(text: String, listenAfter: ListenContext? = null, interrupt: Boolean = false) {
        autoClose?.cancel()
        if (interrupt) queue.clear()
        if (!interrupt && (phase == VoicePhase.LISTENING || (phase == VoicePhase.SPEAKING && currentUtteranceId != null))) {
            queue.addLast(Utterance(text, listenAfter))
            return
        }
        if (phase == VoicePhase.LISTENING) engine.cancelListening()
        speakNow(Utterance(text, listenAfter))
    }

    private fun speakNow(utterance: Utterance) {
        val id = "utt-${++utteranceCounter}"
        currentUtteranceId = id
        listenAfterSpeech = utterance.listenAfter
        _state.update { it.copy(phase = VoicePhase.SPEAKING, lastResponse = utterance.text, level = 0f) }
        engine.speak(utterance.text, id, VOICE_LANGUAGE)
    }

    /** Hentikan semua aktivitas suara dan tutup panel. */
    fun stopAll() {
        autoClose?.cancel()
        queue.clear()
        listenAfterSpeech = null
        currentUtteranceId = null
        engine.cancelListening()
        engine.stopSpeaking()
        _state.update { it.copy(phase = VoicePhase.IDLE, panelPinned = false, partialText = "", level = 0f, error = null) }
    }

    /** Batalkan hanya sesi mendengarkan (mis. order sudah diterima lewat tombol). */
    fun cancelListening(context: ListenContext) {
        if (phase == VoicePhase.LISTENING && _state.value.context == context) {
            engine.cancelListening()
            _state.update { it.copy(phase = VoicePhase.IDLE, partialText = "", level = 0f) }
        }
        if (listenAfterSpeech == context) listenAfterSpeech = null
    }

    /** Selesai bicara sekarang (dipakai tombol "Selesai bicara"). */
    fun finishListening() {
        if (phase == VoicePhase.LISTENING) engine.stopListening()
    }

    /** Perintah yang diketik (fallback untuk emulator/simulator tanpa mikrofon). */
    fun submitTyped(text: String, context: ListenContext = _state.value.context) {
        if (text.isBlank()) return
        autoClose?.cancel()
        if (phase == VoicePhase.LISTENING) engine.cancelListening()
        _state.update { it.copy(phase = VoicePhase.PROCESSING, partialText = text, lastHeard = text, error = null, panelPinned = true) }
        onResult(listOf(text), context)
        if (phase == VoicePhase.PROCESSING) goIdle()
    }

    fun pinPanel(pinned: Boolean) {
        autoClose?.cancel()
        _state.update { it.copy(panelPinned = pinned, error = if (pinned) it.error else null) }
    }

    fun addLog(heard: String, response: String, success: Boolean, timeLabel: String) {
        _state.update {
            it.copy(log = (listOf(VoiceLogEntry(++logCounter, heard, response, success, timeLabel)) + it.log).take(30))
        }
    }

    // ------------------------------------------------------------ engine callbacks

    override fun onListeningStarted() {
        if (phase != VoicePhase.LISTENING) _state.update { it.copy(phase = VoicePhase.LISTENING) }
    }

    override fun onSoundLevel(level: Float) {
        if (phase == VoicePhase.LISTENING) _state.update { it.copy(level = level.coerceIn(0f, 1f)) }
    }

    override fun onPartialResult(text: String) {
        if (phase == VoicePhase.LISTENING) _state.update { it.copy(partialText = text) }
    }

    override fun onFinalResult(alternatives: List<String>) {
        if (phase != VoicePhase.LISTENING) return
        val cleaned = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.isEmpty()) {
            onListeningError(VoiceError.NO_MATCH)
            return
        }
        val context = _state.value.context
        _state.update { it.copy(phase = VoicePhase.PROCESSING, partialText = cleaned.first(), lastHeard = cleaned.first(), level = 0f) }
        onResult(cleaned, context)
        if (phase == VoicePhase.PROCESSING) goIdle()
    }

    override fun onListeningError(error: VoiceError) {
        if (phase != VoicePhase.LISTENING) return
        val context = _state.value.context
        _state.update {
            it.copy(
                phase = VoicePhase.IDLE,
                level = 0f,
                error = error.message,
                recognitionAvailable = if (error == VoiceError.NOT_AVAILABLE) false else it.recognitionAvailable,
            )
        }
        onListenFailed(error, context)
        if (phase == VoicePhase.IDLE) drainQueueOrIdle()
    }

    override fun onListeningEnded() {
        if (phase == VoicePhase.LISTENING) {
            _state.update { it.copy(phase = VoicePhase.IDLE, level = 0f) }
            drainQueueOrIdle()
        }
    }

    override fun onSpeakingStarted(utteranceId: String) {}

    override fun onSpeakingDone(utteranceId: String) {
        if (utteranceId != currentUtteranceId) return
        currentUtteranceId = null
        val next = listenAfterSpeech
        listenAfterSpeech = null
        _state.update { it.copy(phase = VoicePhase.IDLE) }
        when {
            // Dengar lagi didahulukan; antrean ucapan diputar setelah sesi dengar selesai.
            next != null -> listen(next)
            queue.isNotEmpty() -> speakNow(queue.removeFirst())
            else -> goIdle()
        }
    }

    private fun drainQueueOrIdle() {
        if (queue.isNotEmpty()) speakNow(queue.removeFirst()) else goIdle()
    }

    private fun goIdle() {
        _state.update { it.copy(phase = VoicePhase.IDLE, level = 0f) }
        if (_state.value.panelPinned) {
            autoClose?.cancel()
            autoClose = scope.launch {
                delay(4_000)
                if (phase == VoicePhase.IDLE) _state.update { it.copy(panelPinned = false, error = null) }
            }
        }
    }
}
