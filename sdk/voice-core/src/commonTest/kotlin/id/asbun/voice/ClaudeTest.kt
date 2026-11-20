package id.asbun.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeTest {
    private val request = LlmRequest(
        transcript = "bilang ke penumpang saya kejebak macet",
        locale = "id-ID",
        context = TRIP.toJson(),
        tools = LlmTools.build(TEST_MANIFEST.commands.filter { it.id == "chat.send" || it.id == "traffic.report" }),
        systemPrompt = LlmTools.systemPrompt("id-ID"),
    )

    private class Reply(val status: HttpStatusCode, val body: String, val headers: List<Pair<String, String>> = emptyList())

    /** Records every request and answers with [replies] in order (the last one repeats). */
    private class FakeClaude(vararg replies: Reply) {
        private val queue = replies.toList()
        val headers = mutableListOf<Map<String, String>>()
        val bodies = mutableListOf<JsonObject>()

        val engine = MockEngine { req ->
            headers += req.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
            bodies += Json.parseToJsonElement((req.body as TextContent).text).jsonObject
            val reply = queue[minOf(bodies.size - 1, queue.lastIndex)]
            val all = reply.headers + (HttpHeaders.ContentType to "application/json")
            respond(reply.body, reply.status, headersOf(*all.map { it.first to listOf(it.second) }.toTypedArray()))
        }

        fun client(config: LlmConfig) = HttpLlmClient(config, HttpClient(engine))
    }

    /** MockEngine answers on a real thread, so timeouts and retry delays run on real time too. */
    private suspend fun HttpLlmClient.decideReal(): LlmDecision = withContext(Dispatchers.Default) { decide(request) }

    private val toolUse = Reply(
        HttpStatusCode.OK,
        """{"model":"m","stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"chat_send",
           "input":{"message":"saya kejebak macet","confidence":0.9}}],"usage":{"input_tokens":20,"output_tokens":30}}""",
    )

    private fun apiError(status: HttpStatusCode, type: String, vararg headers: Pair<String, String>) =
        Reply(status, """{"type":"error","error":{"type":"$type","message":"$type happened"}}""", headers.toList())

    @Test
    fun configDefaultsFollowTheCurrentApi() {
        val config = Claude.config("sk-ant-secret")
        assertEquals(Claude.API_URL, config.baseUrl)
        assertEquals(Claude.DEFAULT_MODEL, config.model)
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, config.protocol)
        assertEquals("low", config.effort)
        assertEquals(LlmConfig.DEFAULT_MAX_TOKENS, config.maxTokens)
        assertEquals(Claude.DEFAULT_TIMEOUT_MS, config.timeoutMs)
        assertTrue(config.refusalFallback && config.promptCaching)
        assertFalse("sk-ant-secret" in config.toString(), "the key is never printed")
        // Models without server-side fallback must not get the parameter.
        assertFalse(Claude.config(apiKey = "k", model = "claude-haiku-5-5").refusalFallback)
    }

    @Test
    fun requestShapeHasFallbackCachingAndNoSampling() = runTest {
        val fake = FakeClaude(toolUse)
        val decision = fake.client(Claude.config("sk-ant-test")).decideReal()
        assertEquals("chat_send", decision.toolName)

        val headers = fake.headers.single()
        assertEquals("sk-ant-test", headers["x-api-key"])
        assertEquals("2026-10-21", headers["anthropic-version"])
        assertEquals("server-side-fallback-2026-07-01", headers["anthropic-beta"])
        val body = fake.bodies.single()
        assertEquals(Claude.DEFAULT_MODEL, body["model"]?.jsonPrimitive?.content)
        assertEquals("default", body["fallbacks"]?.jsonPrimitive?.content)
        assertEquals("low", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("thinking"), "thinking stays on; effort controls it")
        assertFalse(body.containsKey("temperature"))
        val system = body["system"]!!.jsonArray.single().jsonObject
        assertEquals("ephemeral", system["cache_control"]?.jsonObject?.get("type")?.jsonPrimitive?.content)
        assertFalse(system["text"]!!.jsonPrimitive.content.contains("T-9"), "per-screen data stays out of the cached prefix")
        val confidence = body["tools"]!!.jsonArray.first().jsonObject["input_schema"]!!.jsonObject["properties"]!!
            .jsonObject[LlmTools.CONFIDENCE]!!.jsonObject
        assertFalse("minimum" in confidence || "maximum" in confidence, "strict tools reject numeric constraints")
    }

    @Test
    fun extraBetaHeadersAreJoinedNotReplaced() = runTest {
        val fake = FakeClaude(toolUse)
        val config = Claude.config("k").copy(extraHeaders = mapOf("anthropic-beta" to "some-other-beta"))
        fake.client(config).decideReal()
        assertEquals("server-side-fallback-2026-07-01,some-other-beta", fake.headers.single()["anthropic-beta"])
    }

    @Test
    fun fallbackAnswerIsReadAndUsageReported() = runTest {
        val answered = Reply(
            HttpStatusCode.OK,
            """
            {"model":"fallback-model","stop_reason":"tool_use","content":[
              {"type":"fallback","from":{"model":"m"},"to":{"model":"fallback-model"}},
              {"type":"thinking","thinking":"","signature":"s"},
              {"type":"tool_use","id":"t1","name":"traffic_report","input":{"type":"MACET","confidence":0.8}}],
             "usage":{"input_tokens":40,"output_tokens":25,"cache_read_input_tokens":1200,"cache_creation_input_tokens":0,
              "iterations":[{"type":"message"},{"type":"fallback_message"}]}}
            """.trimIndent(),
        )
        val seen = mutableListOf<LlmUsage>()
        val config = Claude.config(apiKey = "k", onUsage = { seen += it; throw IllegalStateException("a broken callback must not matter") })
        val decision = FakeClaude(answered).client(config).decideReal()

        assertEquals("traffic_report", decision.toolName)
        assertEquals("MACET", decision.arguments["type"]?.jsonPrimitive?.content)
        val usage = seen.single()
        assertEquals("fallback-model", usage.model)
        assertTrue(usage.servedByFallback)
        assertEquals(1200, usage.cacheReadInputTokens)
        assertEquals(40, usage.inputTokens)
    }

    @Test
    fun refusalAndMaxTokensAreHandledBeforeReadingContent() = runTest {
        val refusal = Reply(HttpStatusCode.OK, """{"stop_reason":"refusal","stop_details":{"type":"refusal","category":null},"content":[]}""")
        assertTrue(FakeClaude(refusal).client(Claude.config("k")).decideReal().refused)

        val cut = Reply(HttpStatusCode.OK, """{"stop_reason":"max_tokens","content":[{"type":"thinking","thinking":""}]}""")
        val e = assertFailsWith<LlmException> { FakeClaude(cut).client(Claude.config("k")).decideReal() }
        assertEquals(VoiceErrorCode.LLM_INVALID_RESPONSE, e.code)
        assertTrue(e.message!!.contains("max_tokens"))
    }

    @Test
    fun apiErrorsCarryStatusAndTypeAndAreNotRetried() = runTest {
        val fake = FakeClaude(apiError(HttpStatusCode.Unauthorized, "authentication_error"))
        val e = assertFailsWith<LlmException> { fake.client(Claude.config("bad-key")).decideReal() }
        assertEquals(VoiceErrorCode.LLM_HTTP_ERROR, e.code)
        assertEquals(401, e.httpStatus)
        assertEquals("authentication_error", e.errorType)
        assertTrue(e.message!!.contains("authentication_error happened"))
        assertEquals(1, fake.bodies.size)
    }

    @Test
    fun overloadIsRetriedOnceWithinTheTimeout() = runTest {
        val recovered = FakeClaude(apiError(HttpStatusCode(529, "Overloaded"), "overloaded_error"), toolUse)
        assertEquals("chat_send", recovered.client(Claude.config("k")).decideReal().toolName)
        assertEquals(2, recovered.bodies.size)

        val stillDown = FakeClaude(apiError(HttpStatusCode(529, "Overloaded"), "overloaded_error"))
        val e = assertFailsWith<LlmException> { stillDown.client(Claude.config("k")).decideReal() }
        assertEquals(529, e.httpStatus)
        assertEquals(2, stillDown.bodies.size, "one retry only")
    }

    @Test
    fun rateLimitRetriesOnlyWhenToldWhen() = runTest {
        val spendCap = FakeClaude(apiError(HttpStatusCode.TooManyRequests, "rate_limit_error"))
        assertFailsWith<LlmException> { spendCap.client(Claude.config("k")).decideReal() }
        assertEquals(1, spendCap.bodies.size, "a 429 without retry-after is not retried")

        val brief = FakeClaude(apiError(HttpStatusCode.TooManyRequests, "rate_limit_error", "retry-after" to "0.1"), toolUse)
        assertEquals("chat_send", brief.client(Claude.config("k")).decideReal().toolName)

        val told = FakeClaude(apiError(HttpStatusCode.ServiceUnavailable, "api_error", "x-should-retry" to "false"))
        assertFailsWith<LlmException> { told.client(Claude.config("k")).decideReal() }
        assertEquals(1, told.bodies.size, "x-should-retry: false wins")
    }

    @Test
    fun missingKeyFailsBeforeAnyRequest() = runTest {
        val fake = FakeClaude(toolUse)
        val e = assertFailsWith<LlmException> { fake.client(Claude.config(model = Claude.DEFAULT_MODEL)).decideReal() }
        assertTrue(e.message!!.contains("API key"))
        assertTrue(fake.bodies.isEmpty())
        // A proxy that holds the key itself needs none from the app.
        assertNotNull(FakeClaude(toolUse).client(Claude.config(baseUrl = "https://proxy.example.com")).decideReal().toolName)
    }

    @Test
    fun claudeBlockInJsonConfig() {
        val config = VoiceConfig.fromJson("""{"locale": "id-ID", "claude": {"effort": "medium"}}""").withLlmApiKey("sk-ant-k")
        val llm = assertNotNull(config.llm)
        assertEquals(LlmProtocol.ANTHROPIC_MESSAGES, llm.protocol)
        assertEquals(Claude.DEFAULT_MODEL, llm.model)
        assertEquals("medium", llm.effort)
        assertEquals("sk-ant-k", llm.apiKey)
        assertTrue(llm.refusalFallback)
        assertNull(VoiceConfig.fromJson("{}").withLlmApiKey("k").llm, "no LLM configured stays on-device")
    }
}
