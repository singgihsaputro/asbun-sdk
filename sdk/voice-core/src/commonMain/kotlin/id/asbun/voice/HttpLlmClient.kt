package id.asbun.voice

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
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
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import kotlin.time.TimeSource

/**
 * Raw-HTTP LLM client (Ktor), so it runs on Android and on iOS through Kotlin/Native, where no
 * official LLM SDK exists.
 *
 * - `ANTHROPIC_MESSAGES`: POST {baseUrl}/v1/messages (Claude; see [Claude.config])
 * - `OPENAI_COMPATIBLE`: POST {baseUrl}/chat/completions
 * - `CUSTOM_JSON`: POST {baseUrl}/resolve
 *
 * One retry, inside [LlmConfig.timeoutMs], for overloads, server errors and rate limits that
 * say when to come back; never for a 429 without `retry-after` (a spend cap).
 */
public class HttpLlmClient(
    private val config: LlmConfig,
    private val http: HttpClient = HttpClient(),
) : LlmClient {

    override suspend fun decide(request: LlmRequest): LlmDecision = try {
        withTimeout(config.timeoutMs) {
            val token = config.tokenProvider?.invoke() ?: config.apiKey
            if (token == null && config.baseUrl.trimEnd('/') == Claude.API_URL) {
                throw LlmException(
                    VoiceErrorCode.LLM_HTTP_ERROR,
                    "The Claude API needs an API key or a token provider, e.g. Claude.config(apiKey = ...)",
                )
            }
            when (config.protocol) {
                LlmProtocol.ANTHROPIC_MESSAGES -> anthropic(request, token)
                LlmProtocol.OPENAI_COMPATIBLE -> openAi(request, token)
                LlmProtocol.CUSTOM_JSON -> custom(request, token)
            }
        }
    } catch (e: TimeoutCancellationException) {
        throw LlmException(VoiceErrorCode.LLM_TIMEOUT, "LLM did not answer within ${config.timeoutMs} ms", e)
    }

    // ------------------------------------------------------------------ Anthropic Messages (Claude)

    private suspend fun anthropic(request: LlmRequest, token: String?): LlmDecision {
        val body = buildJsonObject {
            put("model", config.model)
            put("max_tokens", config.maxTokens)
            // Thinking is always on for current models; effort is the control. No temperature:
            // API version 2026-10-21 rejects sampling parameters.
            config.effort?.let { putJsonObject("output_config") { put("effort", it) } }
            if (config.refusalFallback) put("fallbacks", "default")
            // Render order is tools -> system -> messages, so one breakpoint on the system block
            // caches the tools and the system prompt; the per-request text stays after it.
            if (config.promptCaching) {
                putJsonArray("system") {
                    addJsonObject {
                        put("type", "text")
                        put("text", request.systemPrompt)
                        putJsonObject("cache_control") { put("type", "ephemeral") }
                    }
                }
            } else {
                put("system", request.systemPrompt)
            }
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
                    put("content", LlmTools.userMessage(request.transcript, request.context))
                }
            }
        }
        val headers = buildMap {
            put("anthropic-version", ANTHROPIC_VERSION)
            if (config.refusalFallback) put("anthropic-beta", FALLBACK_BETA)
        }
        val started = TimeSource.Monotonic.markNow()
        val json = post("${config.baseUrl.trimEnd('/')}/v1/messages", body, token, defaultHeader = "x-api-key", extra = headers)
        reportAnthropicUsage(json, started.elapsedNow().inWholeMilliseconds)

        // Check stop_reason before reading content: a refusal may come with no content at all.
        when (json.str("stop_reason")) {
            "refusal" -> return LlmDecision(toolName = null, refused = true)
            "max_tokens" -> invalid("Claude stopped at max_tokens (${config.maxTokens}) before answering; raise maxTokens")
        }
        // Content may also hold thinking blocks and, after a fallback, a `fallback` block: skip them.
        val content = json["content"] as? JsonArray ?: invalid("Anthropic response has no content")
        val blocks = content.filterIsInstance<JsonObject>()
        val toolUse = blocks.firstOrNull { it.str("type") == "tool_use" }
        if (toolUse != null) {
            val input = toolUse["input"] as? JsonObject ?: invalid("tool_use block has no input object")
            return decisionFromArguments(toolUse.str("name") ?: invalid("tool_use block has no name"), input)
        }
        val text = blocks.filter { it.str("type") == "text" }.mapNotNull { it.str("text") }.joinToString(" ").trim()
        return LlmDecision(toolName = null, question = text.ifBlank { null })
    }

    private fun reportAnthropicUsage(json: JsonObject, latencyMs: Long) {
        val usage = json["usage"] as? JsonObject ?: return
        // A fallback_message entry means a fallback model ran; it served the answer unless that model refused too.
        val fallbackRan = (usage["iterations"] as? JsonArray)?.any { (it as? JsonObject)?.str("type") == "fallback_message" } == true
        report(
            LlmUsage(
                model = json.str("model"),
                inputTokens = usage.num("input_tokens"),
                outputTokens = usage.num("output_tokens"),
                cacheReadInputTokens = usage.num("cache_read_input_tokens"),
                cacheCreationInputTokens = usage.num("cache_creation_input_tokens"),
                servedByFallback = fallbackRan && json.str("stop_reason") != "refusal",
                latencyMs = latencyMs,
            ),
        )
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
                    put("content", LlmTools.userMessage(request.transcript, request.context))
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
        val started = TimeSource.Monotonic.markNow()
        val json = post("${config.baseUrl.trimEnd('/')}/chat/completions", body, token, defaultHeader = "Authorization: Bearer")
        (json["usage"] as? JsonObject)?.let { usage ->
            report(
                LlmUsage(
                    model = json.str("model"),
                    inputTokens = usage.num("prompt_tokens"),
                    outputTokens = usage.num("completion_tokens"),
                    cacheReadInputTokens = (usage["prompt_tokens_details"] as? JsonObject)?.num("cached_tokens") ?: 0,
                    latencyMs = started.elapsedNow().inWholeMilliseconds,
                ),
            )
        }
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

    // ------------------------------------------------------------------ HTTP

    private suspend fun post(
        url: String,
        body: JsonObject,
        token: String?,
        defaultHeader: String,
        extra: Map<String, String> = emptyMap(),
    ): JsonObject {
        val headers = mergeHeaders(extra, config.extraHeaders)
        var retried = false
        while (true) {
            val response = send(url, body, token, defaultHeader, headers)
            if (response.status.isSuccess()) return parseObject(response.bodyAsText())

            val status = response.status.value
            val retryDelay = if (retried) null else retryDelayMs(response, status)
            if (retryDelay != null) {
                retried = true
                delay(retryDelay)
                continue
            }
            val error = (runCatching { Json.parseToJsonElement(response.bodyAsText()) }.getOrNull() as? JsonObject)
                ?.get("error") as? JsonObject
            val type = error?.str("type")
            val detail = error?.str("message")?.take(MAX_ERROR_MESSAGE)
            val message = buildString {
                append("LLM answered HTTP ").append(status)
                type?.let { append(" (").append(it).append(')') }
                detail?.let { append(": ").append(it) }
            }
            throw LlmException(VoiceErrorCode.LLM_HTTP_ERROR, message, httpStatus = status, errorType = type)
        }
    }

    private suspend fun send(
        url: String,
        body: JsonObject,
        token: String?,
        defaultHeader: String,
        headers: Map<String, String>,
    ): HttpResponse = try {
        http.post(url) {
            contentType(ContentType.Application.Json)
            if (token != null) {
                val spec = config.authHeader ?: defaultHeader
                val name = spec.substringBefore(':').trim()
                val prefix = spec.substringAfter(':', "").trim()
                header(name, if (prefix.isEmpty()) token else "$prefix $token")
            }
            headers.forEach { (k, v) -> header(k, v) }
            setBody(body.toString())
        }
    } catch (e: CancellationException) {
        throw e // includes the timeout
    } catch (e: Exception) {
        throw LlmException(VoiceErrorCode.LLM_HTTP_ERROR, "LLM request failed: ${e.message}", e)
    }

    /** Null = do not retry. Honors `x-should-retry` and `retry-after`; waits at most [MAX_RETRY_DELAY_MS]. */
    private fun retryDelayMs(response: HttpResponse, status: Int): Long? {
        val retryAfterMs = response.headers["retry-after"]?.toDoubleOrNull()?.let { (it * 1000).toLong() }
        val delayMs = when (response.headers["x-should-retry"]?.lowercase()) {
            "false" -> return null
            "true" -> retryAfterMs ?: RETRY_DELAY_MS
            else -> when {
                // A 429 without retry-after is a spend cap: retrying cannot help.
                status == 429 -> retryAfterMs ?: return null
                status == 408 || status == 409 || status >= 500 -> retryAfterMs ?: RETRY_DELAY_MS
                else -> return null
            }
        }
        return delayMs.takeIf { it <= MAX_RETRY_DELAY_MS }
    }

    /** Joins `anthropic-beta` values from both maps instead of letting one replace the other. */
    private fun mergeHeaders(base: Map<String, String>, extra: Map<String, String>): Map<String, String> {
        val merged = LinkedHashMap(base)
        for ((name, value) in extra) {
            val existing = merged.keys.firstOrNull { it.equals(name, ignoreCase = true) }
            merged[existing ?: name] = if (existing != null && name.equals("anthropic-beta", ignoreCase = true)) {
                merged.getValue(existing) + "," + value
            } else {
                value
            }
        }
        return merged
    }

    // ------------------------------------------------------------------ helpers

    private fun report(usage: LlmUsage) {
        val callback = config.onUsage ?: return
        try {
            callback(usage)
        } catch (e: Exception) {
            // A failing usage callback must not cost the user their command.
        }
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

    private fun JsonObject.num(key: String): Long = (this[key] as? JsonPrimitive)?.longOrNull ?: 0

    private companion object {
        const val ANTHROPIC_VERSION = "2026-10-21"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
        const val RETRY_DELAY_MS = 300L
        const val MAX_RETRY_DELAY_MS = 2_000L
        const val MAX_ERROR_MESSAGE = 300
    }
}
