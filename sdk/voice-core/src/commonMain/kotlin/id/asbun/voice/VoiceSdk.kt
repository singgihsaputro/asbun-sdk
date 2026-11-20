package id.asbun.voice

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.time.TimeSource

public enum class VoicePhase { IDLE, LISTENING, PROCESSING, SPEAKING }

/** What the SDK is waiting for after asking a question. */
public sealed interface Awaiting {
    public data class Input(val commandId: String, val param: String) : Awaiting
    public data class Confirmation(val commandId: String) : Awaiting
}

public data class VoiceState(
    val phase: VoicePhase = VoicePhase.IDLE,
    val partialText: String = "",
    val lastHeard: String? = null,
    val lastReply: String? = null,
    val level: Float = 0f,
    val error: VoiceErrorCode? = null,
    val recognitionAvailable: Boolean = true,
    val awaiting: Awaiting? = null,
    /** True while listening for an answer to a question (from the app or the SDK). */
    val prompted: Boolean = false,
)

public sealed interface VoiceEvent {
    public data class StateChanged(val phase: VoicePhase) : VoiceEvent
    public data class SoundLevel(val level: Float) : VoiceEvent
    public data class Partial(val text: String) : VoiceEvent
    public data class Final(val alternatives: List<String>) : VoiceEvent
    public data class Resolved(
        val heard: String,
        val commandId: String,
        val params: Map<String, String>,
        val source: ResolutionSource,
        val confidence: Double,
    ) : VoiceEvent
    public data class NeedsInput(val heard: String, val commandId: String, val param: String, val question: String) : VoiceEvent
    public data class NoMatch(val heard: String, val reply: String?) : VoiceEvent
    public data class Cancelled(val heard: String, val commandId: String?) : VoiceEvent
    public data class Action(
        val heard: String,
        val commandId: String,
        val params: Map<String, String>,
        val success: Boolean,
        val reply: String?,
        val source: ResolutionSource,
        val deeplink: String? = null,
    ) : VoiceEvent
    public data class Error(val code: VoiceErrorCode, val message: String) : VoiceEvent
}

/** Receives every [VoiceEvent] synchronously, on the main thread, as it happens. Handy from Swift. */
public fun interface VoiceEventListener {
    public fun onEvent(event: VoiceEvent)
}

/** Passed to a [CommandHandler]. */
public data class CommandCall(
    val commandId: String,
    val params: Map<String, String>,
    val context: VoiceContext,
    val heard: String,
    val source: ResolutionSource,
    val confidence: Double,
) {
    public fun param(name: String): String? = params[name]
}

/** A handler's outcome. [reply] replaces the manifest's reply; [listenAfter] listens for an answer. */
public data class HandlerResult(
    val success: Boolean = true,
    val reply: String? = null,
    val listenAfter: Boolean = false,
) {
    public companion object {
        public fun success(reply: String? = null, listenAfter: Boolean = false): HandlerResult = HandlerResult(true, reply, listenAfter)
        public fun failure(reply: String? = null): HandlerResult = HandlerResult(false, reply)
    }
}

/** Implements a `handler` action. */
public fun interface CommandHandler {
    public suspend fun handle(call: CommandCall): HandlerResult
}

/** Routes an allowed in-app deeplink; return true when handled. */
public fun interface DeeplinkHandler {
    public fun handle(url: String): Boolean
}

/** Opens a deeplink with the operating system (Intent / UIApplication); return true when opened. */
public fun interface LinkOpener {
    public fun open(url: String): Boolean
}

/**
 * The Voice Command SDK: listens through a [VoiceEngine], resolves speech to a manifest
 * command (keywords first, LLM fallback), asks for missing values or confirmation, then opens
 * the command's deeplink or calls its handler and speaks the reply.
 *
 * Call it from the main thread. Platform helpers create it with the right engine:
 * `VoiceSdkAndroid.create(...)` and `VoiceSdkIos.create(...)`.
 */
public class VoiceSdk(
    config: VoiceConfig,
    manifest: CommandManifest,
    private val engine: VoiceEngine,
    private val scope: CoroutineScope,
    private val linkOpener: LinkOpener? = null,
    llmClient: LlmClient? = config.llm?.let { HttpLlmClient(it) },
    private val clock: () -> Long = monotonicMillis(),
) {
    public val config: VoiceConfig = config
    private val llm = llmClient
    private var manifest = manifest
    private var resolver = IntentResolver(manifest, config, llmClient)
    private var context = VoiceContext.EMPTY

    private val _state = MutableStateFlow(VoiceState(recognitionAvailable = engine.isRecognitionAvailable))
    public val state: StateFlow<VoiceState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    public val events: SharedFlow<VoiceEvent> = _events.asSharedFlow()

    private val handlers = mutableMapOf<String, CommandHandler>()
    private var deeplinkHandler: DeeplinkHandler? = null
    private val eventListeners = mutableListOf<VoiceEventListener>()

    private data class Utterance(val text: String, val listenAfter: Boolean)

    private val queue = ArrayDeque<Utterance>()
    private var currentUtteranceId: String? = null
    private var listenAfterSpeech = false
    private var utteranceCounter = 0
    private var listeningPrompted = false
    private var reprompts = 0
    private var pendingInput: Resolution.NeedsInput? = null
    private var pendingConfirm: Resolution.Matched? = null
    private var lastAction: Triple<String, String, Long>? = null
    private var processJob: Job? = null

    private val listener = object : VoiceEngineListener {
        override fun onListeningStarted() {
            if (phase != VoicePhase.LISTENING) update { it.copy(phase = VoicePhase.LISTENING) }
        }

        override fun onSoundLevel(level: Float) {
            if (phase != VoicePhase.LISTENING) return
            val clamped = level.coerceIn(0f, 1f)
            update { it.copy(level = clamped) }
            emit(VoiceEvent.SoundLevel(clamped))
        }

        override fun onPartialResult(text: String) {
            if (phase != VoicePhase.LISTENING) return
            update { it.copy(partialText = text) }
            emit(VoiceEvent.Partial(text))
        }

        override fun onFinalResult(alternatives: List<String>) {
            if (phase != VoicePhase.LISTENING) return
            val cleaned = alternatives.map { it.trim() }.filter { it.isNotEmpty() }
            if (cleaned.isEmpty()) onListeningError(VoiceError.NO_MATCH) else process(cleaned, listeningPrompted)
        }

        override fun onListeningError(error: VoiceError) {
            if (phase == VoicePhase.LISTENING) handleListenError(error)
        }

        override fun onListeningEnded() {
            if (phase == VoicePhase.LISTENING) {
                update { it.copy(phase = VoicePhase.IDLE, level = 0f) }
                drainQueueOrIdle()
            }
        }

        override fun onSpeakingDone(utteranceId: String) {
            if (utteranceId != currentUtteranceId) return
            currentUtteranceId = null
            val next = listenAfterSpeech
            listenAfterSpeech = false
            update { it.copy(phase = VoicePhase.IDLE) }
            when {
                // Listening for the answer comes first; queued speech plays after that session.
                next -> listen(prompted = true)
                queue.isNotEmpty() -> speakNow(queue.removeFirst())
                else -> goIdle()
            }
        }
    }

    init {
        engine.listener = listener
    }

    public val phase: VoicePhase get() = _state.value.phase
    public val currentContext: VoiceContext get() = context
    public val currentManifest: CommandManifest get() = manifest
    public val keywordMatcher: KeywordMatcher get() = resolver.matcher

    // ================================================================== public API

    /** Swap commands at runtime, e.g. after a remote config fetch. Clears any open question. */
    public fun updateManifest(manifest: CommandManifest) {
        processJob?.cancel()
        this.manifest = manifest
        resolver = IntentResolver(manifest, config, llm)
        pendingInput = null
        pendingConfirm = null
        updateAwaiting()
    }

    /** Current screen and values; call whenever they change. */
    public fun setContext(context: VoiceContext) {
        this.context = context
    }

    public fun onDeeplink(handler: DeeplinkHandler?) {
        deeplinkHandler = handler
    }

    /** Same events as [events], but delivered synchronously (no coroutine collector needed). */
    public fun addEventListener(listener: VoiceEventListener) {
        eventListeners += listener
    }

    public fun removeEventListener(listener: VoiceEventListener) {
        eventListeners -= listener
    }

    public fun registerHandler(name: String, handler: CommandHandler) {
        handlers[name] = handler
    }

    public fun unregisterHandler(name: String) {
        handlers.remove(name)
    }

    /** Mic button: listen for one command (an open question is still answered). */
    public fun startListening() {
        processJob?.cancel()
        reprompts = 0
        listen(prompted = false)
    }

    /** Ask the engine for the final result now. */
    public fun stopListening() {
        if (phase == VoicePhase.LISTENING) engine.stopListening()
    }

    /** Stop listening without a result, and drop a pending "listen after speaking". */
    public fun cancelListening() {
        listenAfterSpeech = false
        if (phase == VoicePhase.LISTENING) {
            engine.cancelListening()
            update { it.copy(phase = VoicePhase.IDLE, partialText = "", level = 0f) }
            drainQueueOrIdle()
        }
    }

    /** Stop everything: speech, listening, queued replies and open questions. */
    public fun cancel() {
        processJob?.cancel()
        queue.clear()
        listenAfterSpeech = false
        currentUtteranceId = null
        pendingInput = null
        pendingConfirm = null
        engine.cancelListening()
        engine.stopSpeaking()
        update { it.copy(phase = VoicePhase.IDLE, partialText = "", level = 0f, error = null, awaiting = null, prompted = false) }
    }

    /**
     * Speak [text]. While listening or speaking it is queued, unless [interrupt].
     * [listenAfter] = then listen for an answer (hands-free prompts).
     */
    public fun speak(text: String, listenAfter: Boolean = false, interrupt: Boolean = false) {
        if (listenAfter) reprompts = 0
        enqueueSpeech(text, listenAfter, interrupt)
    }

    /** Resolve typed text as if spoken: emulators, accessibility, tests. */
    public fun submitText(text: String) {
        if (text.isBlank()) return
        if (phase == VoicePhase.LISTENING) engine.cancelListening()
        process(listOf(text.trim()), prompted = false)
    }

    /** Stop everything and release the engine (recognizer, audio session, TTS). The SDK is unusable afterwards. */
    public fun close() {
        cancel()
        eventListeners.clear()
        engine.listener = null
        engine.shutdown()
    }

    // ================================================================== speech

    private fun enqueueSpeech(text: String, listenAfter: Boolean, interrupt: Boolean) {
        if (!config.ttsEnabled) {
            update { it.copy(lastReply = text) }
            if (listenAfter) listen(prompted = true) else if (phase == VoicePhase.PROCESSING) goIdle()
            return
        }
        if (interrupt) queue.clear()
        val busy = phase == VoicePhase.LISTENING || (phase == VoicePhase.SPEAKING && currentUtteranceId != null)
        if (!interrupt && busy) {
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
        update { it.copy(phase = VoicePhase.SPEAKING, lastReply = utterance.text, level = 0f) }
        engine.speak(utterance.text, id, config.locale)
    }

    private fun listen(prompted: Boolean) {
        if (!engine.isRecognitionAvailable) {
            update { it.copy(phase = VoicePhase.IDLE, error = VoiceErrorCode.STT_UNAVAILABLE, recognitionAvailable = false) }
            emit(VoiceEvent.Error(VoiceErrorCode.STT_UNAVAILABLE, "Speech recognition is not available on this device"))
            return
        }
        if (phase == VoicePhase.SPEAKING) {
            queue.clear()
            listenAfterSpeech = false
            currentUtteranceId = null
            engine.stopSpeaking()
        }
        if (phase == VoicePhase.LISTENING) engine.cancelListening()
        listeningPrompted = prompted
        update { it.copy(phase = VoicePhase.LISTENING, partialText = "", error = null, level = 0f, prompted = prompted) }
        engine.startListening(config.locale)
    }

    private fun ask(text: String) = enqueueSpeech(text, listenAfter = true, interrupt = false)

    private fun say(text: String) = enqueueSpeech(text, listenAfter = false, interrupt = false)

    private fun drainQueueOrIdle() {
        if (queue.isNotEmpty()) speakNow(queue.removeFirst()) else goIdle()
    }

    private fun goIdle() {
        update { it.copy(phase = VoicePhase.IDLE, level = 0f) }
    }

    // ================================================================== recognition

    private fun handleListenError(error: VoiceError) {
        val code = when (error) {
            VoiceError.NO_MATCH -> VoiceErrorCode.NO_MATCH
            VoiceError.PERMISSION_DENIED -> VoiceErrorCode.PERMISSION_DENIED
            else -> VoiceErrorCode.STT_UNAVAILABLE
        }
        update {
            it.copy(
                phase = VoicePhase.IDLE,
                level = 0f,
                error = code,
                recognitionAvailable = if (error == VoiceError.NOT_AVAILABLE) false else it.recognitionAvailable,
            )
        }
        if (error == VoiceError.NO_MATCH && listeningPrompted && reprompts < config.dialog.maxReprompts) {
            reprompts++
            ask(render(config.messages.noSpeech))
            return
        }
        if (listeningPrompted) {
            pendingInput = null
            pendingConfirm = null
            updateAwaiting()
        }
        emit(VoiceEvent.Error(code, "Speech recognition error: $error"))
        drainQueueOrIdle()
    }

    private fun process(alternatives: List<String>, prompted: Boolean) {
        processJob?.cancel() // a new utterance replaces one still being resolved
        val heard = alternatives.first()
        update { it.copy(phase = VoicePhase.PROCESSING, partialText = heard, lastHeard = heard, level = 0f, error = null) }
        emit(VoiceEvent.Final(alternatives))
        // UNDISPATCHED: keyword-only flows finish synchronously; an LLM call resumes later.
        processJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            val confirm = pendingConfirm
            val input = pendingInput
            when {
                confirm != null -> answerConfirmation(heard, confirm)
                input != null -> answerInput(alternatives, input)
                else -> handle(resolver.resolve(alternatives, context), prompted)
            }
            if (phase == VoicePhase.PROCESSING) goIdle()
        }
    }

    private suspend fun handle(resolution: Resolution, prompted: Boolean) {
        when (resolution) {
            is Resolution.Matched -> {
                emit(
                    VoiceEvent.Resolved(resolution.heard, resolution.command.id, resolution.params, resolution.source, resolution.confidence),
                )
                val confirm = resolution.command.confirm?.resolve(config.locale, manifest.defaultLocale)
                if (confirm != null) {
                    pendingConfirm = resolution
                    updateAwaiting()
                    ask(render(confirm, displayParams(resolution.command, resolution.params)))
                } else {
                    execute(resolution)
                }
            }
            is Resolution.NeedsInput -> {
                pendingInput = resolution
                updateAwaiting()
                emit(VoiceEvent.NeedsInput(resolution.heard, resolution.command.id, resolution.missing.name, resolution.question))
                ask(resolution.question)
            }
            is Resolution.NoMatch -> {
                resolution.error?.let {
                    log(LogLevel.ERROR, "LLM error: ${it.message}")
                    emit(VoiceEvent.Error(it.code, it.message ?: it.code.name))
                }
                val reply = when {
                    prompted && reprompts < config.dialog.maxReprompts -> {
                        reprompts++
                        (resolution.question ?: render(config.messages.reprompt)).also { ask(it) }
                    }
                    resolution.question != null -> resolution.question.also { ask(it) }
                    else -> {
                        reprompts = 0
                        render(config.messages.notUnderstood).also { say(it) }
                    }
                }
                emit(VoiceEvent.NoMatch(resolution.heard, reply))
            }
        }
    }

    private suspend fun answerConfirmation(heard: String, matched: Resolution.Matched) {
        val said = wordsOf(heard)
        val messages = config.messages
        when {
            containsAny(said, messages.noWords) || containsAny(said, messages.cancelWords) -> {
                pendingConfirm = null
                updateAwaiting()
                say(render(messages.cancelled))
                emit(VoiceEvent.Cancelled(heard, matched.command.id))
            }
            containsAny(said, messages.yesWords) -> execute(matched)
            reprompts < config.dialog.maxReprompts -> {
                reprompts++
                val confirm = matched.command.confirm?.resolve(config.locale, manifest.defaultLocale).orEmpty()
                ask(render(confirm, displayParams(matched.command, matched.params)))
            }
            else -> {
                pendingConfirm = null
                updateAwaiting()
                say(render(messages.cancelled))
                emit(VoiceEvent.Cancelled(heard, matched.command.id))
            }
        }
    }

    private suspend fun answerInput(alternatives: List<String>, pending: Resolution.NeedsInput) {
        val heard = alternatives.first()
        if (isOnlyCancel(heard)) {
            pendingInput = null
            updateAwaiting()
            say(render(config.messages.cancelled))
            emit(VoiceEvent.Cancelled(heard, pending.command.id))
            return
        }
        val value = when (pending.missing.type) {
            ParamType.TEXT, ParamType.DATE -> heard
            ParamType.ENUM -> alternatives.firstNotNullOfOrNull { resolver.matcher.enumValueIn(it, pending.missing) }
            ParamType.NUMBER -> heard.split(Regex("\\s+")).firstOrNull { w -> w.isNotEmpty() && w.all { it.isDigit() } }
        }
        if (value == null) {
            if (reprompts < config.dialog.maxReprompts) {
                reprompts++
                ask(pending.question)
            } else {
                pendingInput = null
                updateAwaiting()
                val reply = render(config.messages.notUnderstood)
                say(reply)
                emit(VoiceEvent.NoMatch(heard, reply))
            }
            return
        }
        pendingInput = null
        updateAwaiting()
        val next = resolver.complete(
            heard = heard,
            command = pending.command,
            speechParams = pending.params + (pending.missing.name to value),
            context = context,
            source = pending.source,
            confidence = pending.confidence,
        )
        handle(next, prompted = true)
    }

    // ================================================================== dispatch

    private suspend fun execute(m: Resolution.Matched) {
        reprompts = 0
        pendingInput = null
        pendingConfirm = null
        updateAwaiting()
        val now = clock()
        lastAction?.let { (id, heard, at) ->
            if (id == m.command.id && heard.equals(m.heard, ignoreCase = true) && now - at < config.dialog.dedupeWindowMs) {
                log(LogLevel.DEBUG, "Ignored repeat of ${m.command.id}")
                return
            }
        }
        lastAction = Triple(m.command.id, m.heard, now)

        when (val action = m.command.action) {
            is CommandAction.Deeplink -> {
                val url = Deeplinks.build(action.url, m.params, context)
                if (url == null || !Deeplinks.isAllowed(url, config.dispatch)) {
                    emit(VoiceEvent.Error(VoiceErrorCode.DEEPLINK_BLOCKED, "Deeplink not allowed or incomplete: ${url ?: action.url}"))
                    val reply = render(config.messages.actionFailed)
                    emit(VoiceEvent.Action(m.heard, m.command.id, m.params, false, reply, m.source, url))
                    say(reply)
                    return
                }
                val handled = deeplinkHandler?.handle(url) == true || linkOpener?.open(url) == true
                val reply = if (handled) replyFor(m) else render(config.messages.actionFailed)
                emit(VoiceEvent.Action(m.heard, m.command.id, m.params, handled, reply, m.source, url))
                reply?.let { say(it) }
            }
            is CommandAction.Handler -> {
                val handler = handlers[action.name]
                if (handler == null) {
                    emit(VoiceEvent.Error(VoiceErrorCode.HANDLER_FAILED, "No handler registered for '${action.name}'"))
                    val reply = render(config.messages.actionFailed)
                    emit(VoiceEvent.Action(m.heard, m.command.id, m.params, false, reply, m.source))
                    say(reply)
                    return
                }
                val call = CommandCall(m.command.id, m.params, context, m.heard, m.source, m.confidence)
                val result = try {
                    handler.handle(call)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emit(VoiceEvent.Error(VoiceErrorCode.HANDLER_FAILED, e.message ?: "Handler '${action.name}' failed"))
                    HandlerResult.failure()
                }
                val reply = result.reply ?: if (result.success) replyFor(m) else render(config.messages.actionFailed)
                emit(VoiceEvent.Action(m.heard, m.command.id, m.params, result.success, reply, m.source))
                when {
                    reply != null -> enqueueSpeech(reply, result.listenAfter, interrupt = false)
                    result.listenAfter -> listen(prompted = true)
                }
            }
        }
    }

    private fun replyFor(m: Resolution.Matched): String? =
        m.command.reply?.resolve(config.locale, manifest.defaultLocale)?.let { render(it, displayParams(m.command, m.params)) }

    // ================================================================== helpers

    private fun displayParams(command: Command, params: Map<String, String>): Map<String, String> =
        params.mapValues { (name, value) -> command.param(name)?.let { resolver.matcher.displayValue(it, value) } ?: value }

    private fun render(template: String, params: Map<String, String> = emptyMap()): String = Deeplinks.render(template, params, context)

    private fun wordsOf(text: String): List<String> {
        val raw = text.lowercase().split(Regex("\\s+")).map { w -> w.filter { it.isLetterOrDigit() || it == '\'' } }.filter { it.isNotEmpty() }
        return raw + resolver.matcher.canonicalWords(text)
    }

    private fun containsAny(words: List<String>, phrases: Set<String>): Boolean {
        val joined = " " + words.joinToString(" ") + " "
        return phrases.any { joined.contains(" ${it.lowercase()} ") }
    }

    private fun isOnlyCancel(text: String): Boolean {
        val raw = text.lowercase().split(Regex("\\s+")).map { w -> w.filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }.joinToString(" ")
        return raw in config.messages.cancelWords
    }

    private fun updateAwaiting() {
        val awaiting = pendingConfirm?.let { Awaiting.Confirmation(it.command.id) }
            ?: pendingInput?.let { Awaiting.Input(it.command.id, it.missing.name) }
        update { it.copy(awaiting = awaiting) }
    }

    private fun update(transform: (VoiceState) -> VoiceState) {
        val before = _state.value.phase
        _state.update(transform)
        val after = _state.value.phase
        if (after != before) emit(VoiceEvent.StateChanged(after))
    }

    private fun emit(event: VoiceEvent) {
        _events.tryEmit(event)
        eventListeners.toList().forEach { it.onEvent(event) }
    }

    private fun log(level: LogLevel, message: String) {
        if (config.logLevel != LogLevel.NONE && level.ordinal <= config.logLevel.ordinal) println("[VoiceSdk] $message")
    }

    public companion object {
        /** Same as the constructor; platform modules add helpers that pick the engine for you. */
        public fun create(
            config: VoiceConfig,
            manifest: CommandManifest,
            engine: VoiceEngine,
            scope: CoroutineScope,
            linkOpener: LinkOpener? = null,
            llmClient: LlmClient? = config.llm?.let { HttpLlmClient(it) },
        ): VoiceSdk = VoiceSdk(config, manifest, engine, scope, linkOpener, llmClient)
    }
}

private fun monotonicMillis(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
