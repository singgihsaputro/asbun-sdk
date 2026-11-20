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

public class LlmException(public val code: VoiceErrorCode, message: String, cause: Throwable? = null) : Exception(message, cause)

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

    public fun systemPrompt(locale: String, context: JsonObject): String =
        """
        You map one spoken request from a mobile app user to exactly one app command.
        Language: $locale. Current screen and data: $context.
        Call the single matching tool, with parameter values taken from the transcript.
        Set confidence to how sure you are.
        If nothing matches or the request is ambiguous, call no tool and reply with one
        short question in $locale.
        """.trimIndent()
}
