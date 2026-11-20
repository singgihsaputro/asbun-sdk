package id.ojolmitra.driver.voice

import id.asbun.voice.Awaiting
import id.asbun.voice.VoiceErrorCode
import id.asbun.voice.VoiceEvent
import id.asbun.voice.VoiceEventListener
import id.asbun.voice.VoiceSdk
import id.asbun.voice.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The SDK's phase, under the name the UI already uses. */
typealias VoicePhase = id.asbun.voice.VoicePhase

/** What the assistant is listening for, for the UI. */
enum class ListenContext {
    /** A free command (mic button). */
    GENERAL,
    /** An order offer is shown: waiting for "terima" / "tolak". */
    ORDER_OFFER,
    /** The SDK asked for the chat message. */
    AWAITING_CHAT,
    /** Dictation from the chat screen. */
    DICTATION,
}

data class VoiceLogEntry(
    val id: Long,
    val heard: String,
    val response: String,
    val success: Boolean,
    val timeLabel: String,
)

/** App-side voice UI state that the SDK does not track. */
data class VoicePanelState(
    /** The assistant panel was opened by the driver (mic button or typing). */
    val pinned: Boolean = false,
    /** The chat screen mic is in use: everything said is the message. */
    val dictating: Boolean = false,
    val log: List<VoiceLogEntry> = emptyList(),
)

/** Everything the voice UI shows: the SDK's [VoiceState] plus [VoicePanelState]. */
data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val context: ListenContext = ListenContext.GENERAL,
    val partialText: String = "",
    val lastHeard: String? = null,
    val lastResponse: String? = null,
    val level: Float = 0f,
    val panelPinned: Boolean = false,
    val error: String? = null,
    val recognitionAvailable: Boolean = true,
    val log: List<VoiceLogEntry> = emptyList(),
) {
    companion object {
        fun of(sdk: VoiceState, panel: VoicePanelState, hasOffer: Boolean): VoiceUiState = VoiceUiState(
            phase = sdk.phase,
            context = when {
                panel.dictating -> ListenContext.DICTATION
                sdk.awaiting == Awaiting.Input("chat.send", "message") -> ListenContext.AWAITING_CHAT
                hasOffer -> ListenContext.ORDER_OFFER
                else -> ListenContext.GENERAL
            },
            partialText = sdk.partialText,
            lastHeard = sdk.lastHeard,
            lastResponse = sdk.lastReply,
            level = sdk.level,
            panelPinned = panel.pinned,
            error = sdk.error?.let { errorText(it, sdk.recognitionAvailable) },
            recognitionAvailable = sdk.recognitionAvailable,
            log = panel.log,
        )

        private fun errorText(code: VoiceErrorCode, recognitionAvailable: Boolean): String = when {
            !recognitionAvailable -> "Pengenalan suara tidak tersedia di perangkat ini. Ketik perintah di bawah untuk simulasi."
            code == VoiceErrorCode.PERMISSION_DENIED -> "Izin mikrofon / pengenalan suara belum diberikan."
            code == VoiceErrorCode.NO_MATCH -> "Suara tidak dikenali. Coba ulangi."
            else -> "Pengenalan suara sedang bermasalah. Coba lagi."
        }
    }
}

/**
 * Keeps the assistant panel, dictation flag and history log in step with SDK events.
 * Events arrive synchronously through [VoiceEventListener], so the log is current as soon
 * as a command has run.
 */
class VoicePanel(
    private val sdk: VoiceSdk,
    private val scope: CoroutineScope,
    private val clockLabel: () -> String,
) : VoiceEventListener {

    private val _state = MutableStateFlow(VoicePanelState())
    val state: StateFlow<VoicePanelState> = _state.asStateFlow()

    /** Called when [VoicePanelState.dictating] changes (the voice context depends on it). */
    var onDictationChanged: () -> Unit = {}

    private var autoClose: Job? = null
    private var logCounter = 0L

    init {
        sdk.addEventListener(this)
    }

    fun pin(pinned: Boolean) {
        autoClose?.cancel()
        _state.update { it.copy(pinned = pinned) }
    }

    fun setDictating(dictating: Boolean) {
        if (_state.value.dictating == dictating) return
        _state.update { it.copy(dictating = dictating) }
        onDictationChanged()
    }

    override fun onEvent(event: VoiceEvent) {
        when (event) {
            is VoiceEvent.StateChanged ->
                if (event.phase == VoicePhase.IDLE) onIdle() else autoClose?.cancel()
            is VoiceEvent.Action -> log(event.heard, event.reply ?: "Selesai.", event.success)
            is VoiceEvent.NeedsInput -> log(event.heard, event.question, true)
            is VoiceEvent.NoMatch -> log(event.heard, event.reply ?: sdk.config.messages.notUnderstood, false)
            is VoiceEvent.Cancelled -> log(event.heard, sdk.config.messages.cancelled, true)
            else -> {}
        }
    }

    private fun onIdle() {
        if (_state.value.dictating && sdk.state.value.awaiting == null) setDictating(false)
        if (!_state.value.pinned) return
        // Close the panel a few seconds after the assistant went quiet.
        autoClose?.cancel()
        autoClose = scope.launch {
            delay(AUTO_CLOSE_MS)
            if (sdk.phase == VoicePhase.IDLE) _state.update { it.copy(pinned = false) }
        }
    }

    private fun log(heard: String, response: String, success: Boolean) {
        val entry = VoiceLogEntry(++logCounter, heard, response, success, clockLabel())
        _state.update { it.copy(log = (listOf(entry) + it.log).take(MAX_LOG)) }
    }

    private companion object {
        const val AUTO_CLOSE_MS = 4_000L
        const val MAX_LOG = 30
    }
}
