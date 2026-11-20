package id.asbun.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

public enum class LlmProtocol { OPENAI_COMPATIBLE, ANTHROPIC_MESSAGES, CUSTOM_JSON }

public enum class ResolutionMode { KEYWORD_FIRST, LLM_FIRST, KEYWORD_ONLY, LLM_ONLY }

public enum class LogLevel { NONE, ERROR, DEBUG }

/**
 * Connection to the LLM used when keywords cannot decide. For Claude, build it with
 * [Claude.config], which fills in the endpoint, protocol and recommended settings.
 *
 * Production apps should not ship a provider key: point [baseUrl] at your own proxy, or fetch a
 * short-lived token with [tokenProvider]. [apiKey] is meant for development and internal builds.
 */
public data class LlmConfig(
    val baseUrl: String,
    val model: String,
    val protocol: LlmProtocol = LlmProtocol.OPENAI_COMPATIBLE,
    val apiKey: String? = null,
    val tokenProvider: (suspend () -> String)? = null,
    /** "Header: Prefix" or "Header"; null = protocol default (`x-api-key` or `Authorization: Bearer`). */
    val authHeader: String? = null,
    val timeoutMs: Long = 3_000,
    val extraHeaders: Map<String, String> = emptyMap(),
    /**
     * ANTHROPIC_MESSAGES only. A ceiling, not a target: you pay for the tokens used. It must leave
     * room for thinking, which current Claude models always do and which counts toward it.
     */
    val maxTokens: Int = DEFAULT_MAX_TOKENS,
    /** ANTHROPIC_MESSAGES only: sent as `output_config.effort`; null leaves it out. */
    val effort: String? = "low",
    /**
     * ANTHROPIC_MESSAGES only: send `fallbacks: "default"` so a safety decline is retried
     * server-side on another model. Only some models and platforms accept it; see [Claude].
     */
    val refusalFallback: Boolean = false,
    /** ANTHROPIC_MESSAGES only: mark the tools and system prompt with `cache_control`. */
    val promptCaching: Boolean = true,
    /** Called after each answered request with the token counts, e.g. to watch cost and cache hits. */
    val onUsage: ((LlmUsage) -> Unit)? = null,
) {
    init {
        require(baseUrl.startsWith("https://") || isLocalDebugUrl(baseUrl)) {
            "llm.baseUrl must use HTTPS (plain HTTP is allowed only for localhost)"
        }
    }

    override fun toString(): String =
        "LlmConfig(baseUrl=$baseUrl, model=$model, protocol=$protocol, apiKey=${if (apiKey != null) "***" else "null"}, " +
            "tokenProvider=${tokenProvider != null}, timeoutMs=$timeoutMs)"

    public companion object {
        public const val DEFAULT_MAX_TOKENS: Int = 4_096

        private fun isLocalDebugUrl(url: String): Boolean =
            url.startsWith("http://localhost") || url.startsWith("http://127.0.0.1") || url.startsWith("http://10.0.2.2")
    }
}

public data class ResolutionConfig(
    val mode: ResolutionMode = ResolutionMode.KEYWORD_FIRST,
    /** Keyword matches scoring below this go to the LLM (or count as no match). */
    val keywordMinScore: Double = 0.8,
    /** LLM answers below this confidence make the assistant ask again. */
    val llmMinConfidence: Double = 0.6,
)

public data class DialogConfig(
    /** Re-asks after silence or an unclear answer, for prompts and follow-up questions. */
    val maxReprompts: Int = 2,
    /** Passed to platform engines: no speech for this long ends listening. */
    val listenTimeoutMs: Long = 7_000,
    /** Passed to platform engines: silence after the last word that ends a request. */
    val endOfSpeechMs: Long = 1_300,
    /** The same command with the same words inside this window is ignored (assistant echo). */
    val dedupeWindowMs: Long = 1_000,
)

public data class DispatchConfig(
    /** Deeplink schemes the SDK may open, e.g. `listOf("ojolmitra")`. Empty = no deeplink is opened. */
    val allowedSchemes: List<String> = emptyList(),
    /** Optional host allowlist; empty = any host of an allowed scheme. */
    val allowedHosts: List<String> = emptyList(),
)

public data class PrivacyConfig(
    /** Off = the LLM receives the transcript and command list only. */
    val sendContextToLlm: Boolean = true,
    /** Masks personal data in the transcript before an LLM call. */
    val redactor: ((String) -> String)? = null,
)

/**
 * Sentences the SDK speaks on its own. They may use `{context.key}` placeholders, e.g.
 * `"Maaf, kurang jelas. {context.hint}"`.
 */
public data class VoiceMessages(
    val notUnderstood: String,
    val reprompt: String,
    val noSpeech: String,
    val cancelled: String,
    val actionFailed: String,
    val yesWords: Set<String>,
    val noWords: Set<String>,
    val cancelWords: Set<String>,
) {
    public companion object {
        public val INDONESIAN: VoiceMessages = VoiceMessages(
            notUnderstood = "Maaf, perintah belum dikenali.",
            reprompt = "Maaf, kurang jelas. Coba ulangi.",
            noSpeech = "Belum terdengar. Coba ulangi.",
            cancelled = "Oke, dibatalkan.",
            actionFailed = "Maaf, perintah tidak bisa dijalankan.",
            yesWords = setOf("ya", "iya", "oke", "ok", "lanjut", "boleh", "betul", "benar", "siap", "yes"),
            noWords = setOf("tidak", "nggak", "gak", "ga", "enggak", "jangan", "batal", "no", "bukan"),
            cancelWords = setOf("batal", "batalkan", "cancel", "stop", "tidak jadi", "gak jadi", "nggak jadi"),
        )

        public val ENGLISH: VoiceMessages = VoiceMessages(
            notUnderstood = "Sorry, I didn't catch a command.",
            reprompt = "Sorry, I didn't get that. Please say it again.",
            noSpeech = "I didn't hear anything. Please try again.",
            cancelled = "Okay, cancelled.",
            actionFailed = "Sorry, that can't be done right now.",
            yesWords = setOf("yes", "yeah", "yep", "ok", "okay", "sure", "continue", "confirm"),
            noWords = setOf("no", "nope", "don't", "dont", "cancel", "stop"),
            cancelWords = setOf("cancel", "stop", "never mind", "nevermind"),
        )

        public fun forLocale(locale: String): VoiceMessages =
            if (locale.substringBefore('-').equals("en", ignoreCase = true)) ENGLISH else INDONESIAN
    }
}

/** Everything an app configures; build it in code or load the non-secret parts with [fromJson]. */
public data class VoiceConfig(
    /** BCP 47 tag for speech and matching, e.g. `id-ID`. */
    val locale: String = "id-ID",
    /** Null keeps the SDK fully on-device (keyword matching only). */
    val llm: LlmConfig? = null,
    val resolution: ResolutionConfig = ResolutionConfig(),
    val dialog: DialogConfig = DialogConfig(),
    val ttsEnabled: Boolean = true,
    val dispatch: DispatchConfig = DispatchConfig(),
    val privacy: PrivacyConfig = PrivacyConfig(),
    val logLevel: LogLevel = LogLevel.NONE,
    val messages: VoiceMessages = VoiceMessages.forLocale(locale),
) {
    /** The same config with an API key for its LLM, e.g. after [fromJson], which never reads keys. */
    public fun withLlmApiKey(apiKey: String): VoiceConfig = copy(llm = llm?.copy(apiKey = apiKey))

    /** The same config with a token provider for its LLM (short-lived tokens from your backend). */
    public fun withLlmTokenProvider(tokenProvider: suspend () -> String): VoiceConfig =
        copy(llm = llm?.copy(tokenProvider = tokenProvider))

    public companion object {
        /**
         * Reads the JSON form shown in the spec. Secrets are deliberately not read from JSON:
         * add them in code with [withLlmApiKey] or [withLlmTokenProvider].
         *
         * `"claude": {"model": ..., "effort": ..., "timeoutMs": ..., "baseUrl": ...}` sets up Claude
         * with [Claude.config] defaults; `"llm": {...}` configures any provider field by field.
         */
        public fun fromJson(json: String): VoiceConfig {
            val root = Json.parseToJsonElement(json).jsonObject
            val locale = root.string("locale") ?: "id-ID"
            val claude = (root["claude"] as? JsonObject)?.let { o ->
                val model = o.string("model") ?: Claude.DEFAULT_MODEL
                Claude.config(
                    model = model,
                    effort = o.string("effort") ?: Claude.DEFAULT_EFFORT,
                    timeoutMs = o.long("timeoutMs") ?: Claude.DEFAULT_TIMEOUT_MS,
                    baseUrl = o.string("baseUrl") ?: Claude.API_URL,
                    refusalFallback = o.bool("refusalFallback") ?: Claude.supportsRefusalFallback(model),
                    promptCaching = o.bool("promptCaching") ?: true,
                    maxTokens = o.int("maxTokens") ?: LlmConfig.DEFAULT_MAX_TOKENS,
                )
            }
            val llm = claude ?: (root["llm"] as? JsonObject)?.let { o ->
                LlmConfig(
                    baseUrl = o.string("baseUrl") ?: throw IllegalArgumentException("llm.baseUrl is required"),
                    model = o.string("model") ?: throw IllegalArgumentException("llm.model is required"),
                    protocol = o.string("protocol")?.let { LlmProtocol.valueOf(it) } ?: LlmProtocol.OPENAI_COMPATIBLE,
                    authHeader = o.string("authHeader"),
                    timeoutMs = o.long("timeoutMs") ?: 3_000,
                    extraHeaders = (o["extraHeaders"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap(),
                    maxTokens = o.int("maxTokens") ?: LlmConfig.DEFAULT_MAX_TOKENS,
                    effort = if (o.containsKey("effort")) o.string("effort") else "low",
                    refusalFallback = o.bool("refusalFallback") ?: false,
                    promptCaching = o.bool("promptCaching") ?: true,
                )
            }
            val resolution = (root["resolution"] as? JsonObject)?.let { o ->
                ResolutionConfig(
                    mode = o.string("mode")?.let { ResolutionMode.valueOf(it) } ?: ResolutionMode.KEYWORD_FIRST,
                    keywordMinScore = o.double("keywordMinScore") ?: 0.8,
                    llmMinConfidence = o.double("llmMinConfidence") ?: 0.6,
                )
            } ?: ResolutionConfig()
            val dialog = (root["dialog"] as? JsonObject)?.let { o ->
                DialogConfig(
                    maxReprompts = o.int("maxReprompts") ?: 2,
                    listenTimeoutMs = o.long("listenTimeoutMs") ?: 7_000,
                    endOfSpeechMs = o.long("endOfSpeechMs") ?: 1_300,
                    dedupeWindowMs = o.long("dedupeWindowMs") ?: 1_000,
                )
            } ?: DialogConfig()
            val dispatch = (root["dispatch"] as? JsonObject)?.let { o ->
                DispatchConfig(allowedSchemes = o.stringList("allowedSchemes"), allowedHosts = o.stringList("allowedHosts"))
            } ?: DispatchConfig()
            val privacy = (root["privacy"] as? JsonObject)?.let { o ->
                PrivacyConfig(sendContextToLlm = o.bool("sendContextToLlm") ?: true)
            } ?: PrivacyConfig()
            val tts = (root["tts"] as? JsonObject)?.bool("enabled") ?: true
            val logLevel = (root["logging"] as? JsonObject)?.string("level")?.let { LogLevel.valueOf(it) } ?: LogLevel.NONE
            return VoiceConfig(
                locale = locale,
                llm = llm,
                resolution = resolution,
                dialog = dialog,
                ttsEnabled = tts,
                dispatch = dispatch,
                privacy = privacy,
                logLevel = logLevel,
            )
        }
    }
}

internal fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull
internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
internal fun JsonObject.double(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull
internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
internal fun JsonObject.stringList(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
