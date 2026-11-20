package id.asbun.voice

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Error codes reported in [VoiceEvent.Error]. */
public enum class VoiceErrorCode {
    PERMISSION_DENIED,
    STT_UNAVAILABLE,
    NO_MATCH,
    LLM_TIMEOUT,
    LLM_HTTP_ERROR,
    LLM_INVALID_RESPONSE,
    DEEPLINK_BLOCKED,
    HANDLER_FAILED,
}

public class LlmException(
    public val code: VoiceErrorCode,
    message: String,
    cause: Throwable? = null,
    /** HTTP status when the endpoint answered with an error, e.g. 401 for a wrong API key. */
    public val httpStatus: Int? = null,
    /** The provider's error type when it sent one, e.g. `authentication_error` or `rate_limit_error`. */
    public val errorType: String? = null,
) : Exception(message, cause)

/** Token use of one LLM request, passed to [LlmConfig.onUsage]. */
public data class LlmUsage(
    /** The model that produced the answer; differs from the configured one after a refusal fallback. */
    val model: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    /** Prompt tokens read from the prompt cache (billed at a fraction of the input price). */
    val cacheReadInputTokens: Long = 0,
    /** Prompt tokens written to the prompt cache. */
    val cacheCreationInputTokens: Long = 0,
    /** True when a fallback model answered after the configured model declined. */
    val servedByFallback: Boolean = false,
    val latencyMs: Long = 0,
)

/** One manifest command offered to the LLM as a function. */
public data class LlmTool(
    val name: String,
    val commandId: String,
    val description: String,
    val inputSchema: JsonObject,
)

public data class LlmRequest(
    val transcript: String,
    val locale: String,
    val context: JsonObject,
    val tools: List<LlmTool>,
    val systemPrompt: String,
)

/** The LLM's answer: one tool call, or a clarifying question, or nothing. */
public data class LlmDecision(
    val toolName: String?,
    val arguments: JsonObject = JsonObject(emptyMap()),
    val confidence: Double? = null,
    val question: String? = null,
    val refused: Boolean = false,
)

/** Implement this to plug in any backend; [HttpLlmClient] covers the three standard protocols. */
public fun interface LlmClient {
    public suspend fun decide(request: LlmRequest): LlmDecision
}

/** Turns manifest commands into LLM tools and builds the system prompt. */
public object LlmTools {
    public const val CONFIDENCE: String = "confidence"

    /** `order.accept` -> `order_accept`: function names allow letters, digits, '_' and '-', up to 64 chars. */
    public fun toolName(commandId: String): String = commandId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(64)

    public fun build(commands: List<Command>): List<LlmTool> =
        commands.map { command ->
            val speechParams = command.params.filter { it.from != ParamSource.CONTEXT }
            val schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    speechParams.forEach { p ->
                        putJsonObject(p.name) {
                            when (p.type) {
                                ParamType.TEXT -> put("type", "string")
                                ParamType.DATE -> {
                                    put("type", "string")
                                    put("description", "ISO date, YYYY-MM-DD")
                                }
                                ParamType.NUMBER -> put("type", "number")
                                ParamType.ENUM -> {
                                    put("type", "string")
                                    putJsonArray("enum") { p.values.keys.forEach { add(it) } }
                                }
                            }
                        }
                    }
                    putJsonObject(CONFIDENCE) {
                        put("type", "number")
                        put("description", "How sure you are that this is the user's intent, from 0 to 1")
                    }
                }
                putJsonArray("required") {
                    speechParams.filter { it.required && it.from == ParamSource.SPEECH }.forEach { add(it.name) }
                    add(CONFIDENCE)
                }
                put("additionalProperties", false)
            }
            LlmTool(toolName(command.id), command.id, command.description, schema)
        }

    /**
     * The same for every request in a locale, so providers can cache it together with the tools.
     * What changes per request (screen data, transcript) goes in [userMessage].
     */
    public fun systemPrompt(locale: String): String =
        """
        You map one spoken request from a mobile app user to exactly one app command.
        Language: $locale. The user message holds the current screen and its data in <context>,
        when the app shares it, and what the user said in <transcript>.
        Call the single matching tool, with parameter values taken from the transcript.
        Set confidence to how sure you are.
        If nothing matches or the request is ambiguous, call no tool and reply with one
        short question in $locale.
        """.trimIndent()

    /** The per-request part: screen data (left out when empty) and the transcript. */
    public fun userMessage(transcript: String, context: JsonObject): String = buildString {
        if (context.isNotEmpty()) append("<context>").append(context.toString()).append("</context>\n")
        append("<transcript>").append(transcript).append("</transcript>")
    }
}
