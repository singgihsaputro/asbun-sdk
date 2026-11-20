package id.asbun.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Raw-HTTP LLM client (Ktor), so it runs on Android and on iOS through Kotlin/Native.
 *
 * - `ANTHROPIC_MESSAGES`: POST {baseUrl}/v1/messages
 * - `OPENAI_COMPATIBLE`: POST {baseUrl}/chat/completions
 * - `CUSTOM_JSON`: POST {baseUrl}/resolve
 */
public class HttpLlmClient(
    private val config: LlmConfig,
    private val http: HttpClient = HttpClient(),
) : LlmClient {

    override suspend fun decide(request: LlmRequest): LlmDecision {
        val token = config.tokenProvider?.invoke() ?: config.apiKey
        return try {
            withTimeout(config.timeoutMs) {
                when (config.protocol) {
                    LlmProtocol.ANTHROPIC_MESSAGES -> anthropic(request, token)
                    LlmProtocol.OPENAI_COMPATIBLE -> openAi(request, token)
                    LlmProtocol.CUSTOM_JSON -> custom(request, token)
                }
            }
        } catch (e: TimeoutCancellationException) {
            throw LlmException(VoiceErrorCode.LLM_TIMEOUT, "LLM did not answer within ${config.timeoutMs} ms", e)
        }
    }

    // ------------------------------------------------------------------ Anthropic Messages

    private suspend fun anthropic(request: LlmRequest, token: String?): LlmDecision {
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            config.effort?.let { putJsonObject("output_config") { put("effort", it) } }
            put("system", request.systemPrompt)
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("strict", true)
                        put("input_schema", tool.inputSchema)
                    }
                }
            }
            // Forced tool_choice (any / tool) is rejected by current models: auto + one call.
            putJsonObject("tool_choice") {
                put("type", "auto")
                put("disable_parallel_tool_use", true)
            }
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "user")
                    put("content", request.transcript)
                }
            }
        }
        val json = post("${config.baseUrl.trimEnd('/')}/v1/messages", body, token, defaultHeader = "x-api-key", extra = mapOf("anthropic-version" to ANTHROPIC_VERSION))
        if (json.str("stop_reason") == "refusal") return LlmDecision(toolName = null, refused = true)
        val content = json["content"] as? JsonArray ?: invalid("Anthropic response has no content")
        val toolUse = content.filterIsInstance<JsonObject>().firstOrNull { it.str("type") == "tool_use" }
        if (toolUse != null) {
            val input = toolUse["input"] as? JsonObject ?: invalid("tool_use block has no input object")
            return decisionFromArguments(toolUse.str("name") ?: invalid("tool_use block has no name"), input)
        }
        val text = content.filterIsInstance<JsonObject>().filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString(" ").trim()
        return LlmDecision(toolName = null, question = text.ifBlank { null })
    }

    // ------------------------------------------------------------------ OpenAI-compatible

    private suspend fun openAi(request: LlmRequest, token: String?): LlmDecision {
        val body = buildJsonObject {
            put("model", config.model)
            putJsonArray("messages") {
                addJsonObject {
                    put("role", "system")
                    put("content", request.systemPrompt)
                }
                addJsonObject {
                    put("role", "user")
                    put("content", request.transcript)
                }
            }
            putJsonArray("tools") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("type", "function")
                        putJsonObject("function") {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        }
                    }
                }
            }
            put("tool_choice", "auto")
        }
        val json = post("${config.baseUrl.trimEnd('/')}/chat/completions", body, token, defaultHeader = "Authorization: Bearer")
        val message = ((json["choices"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("message") as? JsonObject
            ?: invalid("OpenAI-compatible response has no choices[0].message")
        val call = ((message["tool_calls"] as? JsonArray)?.firstOrNull() as? JsonObject)?.get("function") as? JsonObject
        if (call != null) {
            val name = call.str("name") ?: invalid("tool call has no name")
            val args = parseObject(call.str("arguments") ?: "{}")
            return decisionFromArguments(name, args)
        }
        return LlmDecision(toolName = null, question = message.str("content")?.trim()?.ifBlank { null })
    }

    // ------------------------------------------------------------------ custom backend

    private suspend fun custom(request: LlmRequest, token: String?): LlmDecision {
        val body = buildJsonObject {
            put("transcript", request.transcript)
            put("locale", request.locale)
            put("context", request.context)
            putJsonArray("commands") {
                request.tools.forEach { tool ->
                    addJsonObject {
                        put("id", tool.commandId)
                        put("description", tool.description)
                        put("params", tool.inputSchema)
                    }
                }
            }
        }
        val json = post("${config.baseUrl.trimEnd('/')}/resolve", body, token, defaultHeader = "Authorization: Bearer")
        val commandId = json.str("commandId")
        val params = json["params"] as? JsonObject ?: JsonObject(emptyMap())
        val confidence = (json["confidence"] as? JsonPrimitive)?.doubleOrNull
        return LlmDecision(
            toolName = commandId?.let { LlmTools.toolName(it) },
            arguments = params,
            confidence = confidence,
            question = json.str("question"),
        )
    }

    // ------------------------------------------------------------------ helpers

    private suspend fun post(
        url: String,
        body: JsonObject,
        token: String?,
        defaultHeader: String,
        extra: Map<String, String> = emptyMap(),
    ): JsonObject {
        val response = try {
            http.post(url) {
                contentType(ContentType.Application.Json)
                if (token != null) {
                    val spec = config.authHeader ?: defaultHeader
                    val name = spec.substringBefore(':').trim()
                    val prefix = spec.substringAfter(':', "").trim()
                    header(name, if (prefix.isEmpty()) token else "$prefix $token")
                }
                (extra + config.extraHeaders).forEach { (k, v) -> header(k, v) }
                setBody(body.toString())
            }
        } catch (e: TimeoutCancellationException) {
            throw e
        } catch (e: Exception) {
            throw LlmException(VoiceErrorCode.LLM_HTTP_ERROR, "LLM request failed: ${e.message}", e)
        }
        if (!response.status.isSuccess()) {
            throw LlmException(VoiceErrorCode.LLM_HTTP_ERROR, "LLM answered HTTP ${response.status.value}")
        }
        return parseObject(response.bodyAsText())
    }

    private fun decisionFromArguments(name: String, args: JsonObject): LlmDecision {
        val confidence = (args[LlmTools.CONFIDENCE] as? JsonPrimitive)?.doubleOrNull
        return LlmDecision(toolName = name, arguments = JsonObject(args - LlmTools.CONFIDENCE), confidence = confidence)
    }

    private fun parseObject(text: String): JsonObject = try {
        Json.parseToJsonElement(text).jsonObject
    } catch (e: Exception) {
        invalid("LLM response is not a JSON object")
    }

    private fun invalid(message: String): Nothing = throw LlmException(VoiceErrorCode.LLM_INVALID_RESPONSE, message)

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private companion object {
        const val ANTHROPIC_VERSION = "2026-10-21"
    }
}

