package id.asbun.voice

/**
 * Claude (Anthropic Messages API) as the SDK's LLM, with your API key:
 *
 * ```kotlin
 * VoiceConfig(locale = "id-ID", llm = Claude.config(apiKey = "sk-ant-..."))
 * ```
 *
 * The request follows the current API: commands become strict tools, `tool_choice` is `auto`
 * with one call at most, thinking depth is set through `output_config.effort`, the tools and
 * system prompt are prompt-cached, and a safety decline is retried server-side on the model
 * Anthropic recommends (`fallbacks: "default"`) where the model supports it.
 *
 * A key built into an app can be extracted from it. For production, put the key on your own
 * proxy ([baseUrl][config]) or fetch short-lived tokens with `tokenProvider`.
 */
public object Claude {
    public const val API_URL: String = "https://api.anthropic.com"

    /** Claude Opus 5.5. Pass another model id to trade capability for price or speed. */
    public const val DEFAULT_MODEL: String = "claude-opus-5-5"

    /** Routing one sentence to one command needs little thinking; raise it only if accuracy needs it. */
    public const val DEFAULT_EFFORT: String = "low"

    /** Thinking is always on for current models, so allow more than a plain HTTP round trip. */
    public const val DEFAULT_TIMEOUT_MS: Long = 6_000

    /** Models that take the server-side `fallbacks` parameter (others reject it or never fall back). */
    private val REFUSAL_FALLBACK_MODELS = setOf(
        "claude-fable-5", "claude-fable-5-1", "claude-fable-5-2",
        "claude-mythos-5-1", "claude-mythos-5-2",
        "claude-opus-5", "claude-opus-5-5",
        "claude-sonnet-5-5",
    )

    /** For Swift and Java, which cannot use Kotlin default arguments. */
    public fun config(apiKey: String): LlmConfig = config(apiKey = apiKey, model = DEFAULT_MODEL)

    /**
     * @param apiKey your Anthropic API key; or leave it null and pass [tokenProvider].
     * @param model a Claude model id.
     * @param effort `low`, `medium`, `high`, `xhigh` or `max`, sent as `output_config.effort`.
     * @param baseUrl the Claude API, or your proxy that forwards to it.
     * @param refusalFallback send `fallbacks: "default"`. On by default for models that support
     *   it; turn it off when [baseUrl] is a proxy to a platform without it (Bedrock, Vertex AI, Foundry).
     * @param promptCaching mark the tools and system prompt with `cache_control`.
     * @param onUsage called after each answered request, e.g. to watch tokens and cache hits.
     */
    public fun config(
        apiKey: String? = null,
        model: String = DEFAULT_MODEL,
        effort: String = DEFAULT_EFFORT,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        baseUrl: String = API_URL,
        tokenProvider: (suspend () -> String)? = null,
        refusalFallback: Boolean = supportsRefusalFallback(model),
        promptCaching: Boolean = true,
        maxTokens: Int = LlmConfig.DEFAULT_MAX_TOKENS,
        onUsage: ((LlmUsage) -> Unit)? = null,
    ): LlmConfig = LlmConfig(
        baseUrl = baseUrl,
        model = model,
        protocol = LlmProtocol.ANTHROPIC_MESSAGES,
        apiKey = apiKey,
        tokenProvider = tokenProvider,
        timeoutMs = timeoutMs,
        maxTokens = maxTokens,
        effort = effort,
        refusalFallback = refusalFallback,
        promptCaching = promptCaching,
        onUsage = onUsage,
    )

    public fun supportsRefusalFallback(model: String): Boolean = model in REFUSAL_FALLBACK_MODELS
}
