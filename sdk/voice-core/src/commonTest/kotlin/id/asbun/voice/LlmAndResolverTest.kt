package id.asbun.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HttpLlmClientTest {
    private val tools = LlmTools.build(TEST_MANIFEST.commands.filter { it.id == "chat.send" || it.id == "traffic.report" })
    private val request = LlmRequest(
        transcript = "bilang ke penumpang saya kejebak macet",
        locale = "id-ID",
        context = TRIP.toJson(),
        tools = tools,
        systemPrompt = LlmTools.systemPrompt("id-ID", TRIP.toJson()),
    )

    private class Captured {
        var url: String? = null
        var headers: Map<String, String> = emptyMap()
        var body: JsonObject? = null
    }

    private fun client(
        config: LlmConfig,
        captured: Captured = Captured(),
        status: HttpStatusCode = HttpStatusCode.OK,
        delayMs: Long = 0,
        response: String,
    ): HttpLlmClient {
        val engine = MockEngine { req ->
            capture(req, captured)
            if (delayMs > 0) delay(delayMs)
            respondJson(response, status)
        }
        return HttpLlmClient(config, HttpClient(engine))
    }

    private fun capture(req: HttpRequestData, captured: Captured) {
        captured.url = req.url.toString()
        captured.headers = req.headers.entries().associate { it.key.lowercase() to it.value.joinToString(",") }
        captured.body = Json.parseToJsonElement((req.body as TextContent).text).jsonObject
    }

    /** MockEngine answers on a real thread, so the client's timeout must run on real time too. */
    private suspend fun HttpLlmClient.decideReal(request: LlmRequest): LlmDecision =
        withContext(Dispatchers.Default) { decide(request) }

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode): HttpResponseData =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    @Test
    fun anthropicRequestShapeAndToolUseAnswer() = runTest {
        val captured = Captured()
        val config = LlmConfig("https://llm.example.com", "test-model", LlmProtocol.ANTHROPIC_MESSAGES, apiKey = "sk-test")
        val response = """
            {"stop_reason":"tool_use","content":[{"type":"tool_use","id":"t1","name":"chat_send",
             "input":{"message":"saya kejebak macet","confidence":0.93}}]}
        """.trimIndent()
        val decision = client(config, captured, response = response).decideReal(request)

        assertEquals("https://llm.example.com/v1/messages", captured.url)
        assertEquals("sk-test", captured.headers["x-api-key"])
        assertEquals("2026-10-21", captured.headers["anthropic-version"])
        val body = captured.body!!
        assertEquals("test-model", body["model"]?.jsonPrimitive?.content)
        assertEquals("256", body["max_tokens"]?.jsonPrimitive?.content)
        assertEquals("low", body["output_config"]?.jsonObject?.get("effort")?.jsonPrimitive?.content)
        assertFalse(body.containsKey("temperature"))
        val choice = body["tool_choice"]!!.jsonObject
        assertEquals("auto", choice["type"]?.jsonPrimitive?.content)
        assertEquals("true", choice["disable_parallel_tool_use"]?.jsonPrimitive?.content)
        val tool = body["tools"]!!.jsonArray.first().jsonObject
        assertEquals("chat_send", tool["name"]?.jsonPrimitive?.content)
        assertEquals("true", tool["strict"]?.jsonPrimitive?.content)
        val schema = tool["input_schema"]!!.jsonObject
        assertEquals("false", schema["additionalProperties"]?.jsonPrimitive?.content)
        val properties = schema["properties"]!!.jsonObject
        assertTrue("message" in properties && "confidence" in properties)
        assertFalse("tripId" in properties, "context-only params are not offered to the LLM")

        assertEquals("chat_send", decision.toolName)
        assertEquals("saya kejebak macet", decision.arguments["message"]?.jsonPrimitive?.content)
        assertEquals(0.93, decision.confidence)
        assertFalse("confidence" in decision.arguments)
    }

    @Test
    fun anthropicQuestionAndRefusal() = runTest {
        val config = LlmConfig("https://llm.example.com", "m", LlmProtocol.ANTHROPIC_MESSAGES, apiKey = "k")
        val question = client(config, response = """{"stop_reason":"end_turn","content":[{"type":"text","text":"Mau lapor apa?"}]}""").decideReal(request)
        assertNull(question.toolName)
        assertEquals("Mau lapor apa?", question.question)
        val refusal = client(config, response = """{"stop_reason":"refusal","content":[]}""").decideReal(request)
        assertTrue(refusal.refused)
    }

    @Test
    fun openAiCompatibleShapeWithTokenProvider() = runTest {
        val captured = Captured()
        val config = LlmConfig(
            baseUrl = "https://proxy.example.com/v1",
            model = "my-model",
            protocol = LlmProtocol.OPENAI_COMPATIBLE,
            apiKey = "ignored",
            tokenProvider = { "short-lived" },
        )
        val response = """
            {"choices":[{"message":{"role":"assistant","content":null,"tool_calls":[{"id":"c1","type":"function",
             "function":{"name":"traffic_report","arguments":"{\"type\":\"MACET\",\"confidence\":0.8}"}}]}}]}
        """.trimIndent()
        val decision = client(config, captured, response = response).decideReal(request)

        assertEquals("https://proxy.example.com/v1/chat/completions", captured.url)
        assertEquals("Bearer short-lived", captured.headers["authorization"])
        val body = captured.body!!
        assertEquals("auto", body["tool_choice"]?.jsonPrimitive?.content)
        val messages = body["messages"]!!.jsonArray
        assertEquals("system", messages[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals(request.transcript, messages[1].jsonObject["content"]?.jsonPrimitive?.content)
        assertEquals("function", body["tools"]!!.jsonArray.first().jsonObject["type"]?.jsonPrimitive?.content)

        assertEquals("traffic_report", decision.toolName)
        assertEquals("MACET", decision.arguments["type"]?.jsonPrimitive?.content)
        assertEquals(0.8, decision.confidence)
    }

    @Test
    fun customJsonShapeAndCustomAuthHeader() = runTest {
        val captured = Captured()
        val config = LlmConfig("https://backend.example.com/voice", "unused", LlmProtocol.CUSTOM_JSON, apiKey = "abc", authHeader = "X-App-Key")
        val response = """{"commandId":"chat.send","params":{"message":"oke"},"confidence":0.9,"question":null}"""
        val decision = client(config, captured, response = response).decideReal(request)

        assertEquals("https://backend.example.com/voice/resolve", captured.url)
        assertEquals("abc", captured.headers["x-app-key"])
        val commands = captured.body!!["commands"]!!.jsonArray
        assertEquals("chat.send", commands.first().jsonObject["id"]?.jsonPrimitive?.content)
        assertEquals("chat_send", decision.toolName)
        assertEquals(0.9, decision.confidence)
    }

    @Test
    fun errorsBecomeTypedLlmExceptions() = runTest {
        val config = LlmConfig("https://llm.example.com", "m", LlmProtocol.ANTHROPIC_MESSAGES, apiKey = "k", timeoutMs = 300)
        val http = assertFailsWith<LlmException> { client(config, status = HttpStatusCode.InternalServerError, response = "{}").decideReal(request) }
        assertEquals(VoiceErrorCode.LLM_HTTP_ERROR, http.code)
        val invalid = assertFailsWith<LlmException> { client(config, response = "not json").decideReal(request) }
        assertEquals(VoiceErrorCode.LLM_INVALID_RESPONSE, invalid.code)
        val timeout = assertFailsWith<LlmException> { client(config, delayMs = 2_000, response = "{}").decideReal(request) }
        assertEquals(VoiceErrorCode.LLM_TIMEOUT, timeout.code)
    }

    @Test
    fun plainHttpIsRefusedExceptLocalhost() {
        assertFailsWith<IllegalArgumentException> { LlmConfig("http://llm.example.com", "m") }
        LlmConfig("http://localhost:8080", "m")
    }
}

class IntentResolverTest {

    private class FakeLlm(var decision: LlmDecision? = null, var error: LlmException? = null) : LlmClient {
        val requests = mutableListOf<LlmRequest>()
        override suspend fun decide(request: LlmRequest): LlmDecision {
            requests += request
            error?.let { throw it }
            return decision ?: LlmDecision(toolName = null)
        }
    }

    private fun resolver(llm: LlmClient?, mode: ResolutionMode = ResolutionMode.KEYWORD_FIRST, privacy: PrivacyConfig = PrivacyConfig()) =
        IntentResolver(TEST_MANIFEST, VoiceConfig(resolution = ResolutionConfig(mode = mode), privacy = privacy), llm)

    private fun args(vararg pairs: Pair<String, String>) = buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }

    @Test
    fun keywordMatchDoesNotCallTheLlm() = runTest {
        val llm = FakeLlm()
        val r = resolver(llm).resolve(listOf("terima"), OFFER)
        val matched = assertIs<Resolution.Matched>(r)
        assertEquals("order.accept", matched.command.id)
        assertEquals("OM-1", matched.params["orderId"], "filled from context")
        assertEquals(ResolutionSource.KEYWORD, matched.source)
        assertTrue(llm.requests.isEmpty())
    }

    @Test
    fun lowKeywordScoreFallsBackToValidatedLlmAnswer() = runTest {
        val llm = FakeLlm(LlmDecision("traffic_report", args("type" to "macet", "severity" to "PARAH"), confidence = 0.9))
        val r = resolver(llm).resolve(listOf("di depan sana kendaraannya numpuk nggak gerak"), HOME)
        val matched = assertIs<Resolution.Matched>(r)
        assertEquals("traffic.report", matched.command.id)
        assertEquals("MACET", matched.params["type"], "synonym mapped to enum key")
        assertEquals("PARAH", matched.params["severity"])
        assertEquals("Jl. Gatot Subroto", matched.params["location"], "default from context")
        assertEquals(ResolutionSource.LLM, matched.source)
        // Only commands allowed on this screen are offered.
        val offered = llm.requests.single().tools.map { it.commandId }
        assertTrue("order.accept" !in offered && "traffic.report" in offered)
    }

    @Test
    fun llmAnswersAreValidated() = runTest {
        // Tool not allowed on this screen.
        val notAllowed = FakeLlm(LlmDecision("order_accept", confidence = 0.99))
        assertIs<Resolution.NoMatch>(resolver(notAllowed).resolve(listOf("blablabla"), HOME))
        // Low confidence: the LLM's question is kept.
        val unsure = FakeLlm(LlmDecision("traffic_report", args("type" to "MACET"), confidence = 0.3, question = "Maksudnya macet?"))
        assertEquals("Maksudnya macet?", assertIs<Resolution.NoMatch>(resolver(unsure).resolve(listOf("hmm"), HOME)).question)
        // Context-only values never come from the LLM.
        val sneaky = FakeLlm(LlmDecision("order_accept", args("orderId" to "OTHER"), confidence = 0.95))
        val r = assertIs<Resolution.Matched>(resolver(sneaky).resolve(listOf("yuk lah kita jalan"), OFFER))
        assertEquals("OM-1", r.params["orderId"])
    }

    @Test
    fun modesDecideWhoIsAskedFirst() = runTest {
        val llm = FakeLlm(LlmDecision("chat_send", args("message" to "dari llm"), confidence = 0.9))
        assertIs<Resolution.NoMatch>(resolver(llm, ResolutionMode.KEYWORD_ONLY).resolve(listOf("tolong sampaikan saya otw"), TRIP))
        assertTrue(llm.requests.isEmpty())

        val viaLlm = assertIs<Resolution.Matched>(resolver(llm, ResolutionMode.LLM_FIRST).resolve(listOf("chat saya otw"), TRIP))
        assertEquals(ResolutionSource.LLM, viaLlm.source)

        val failing = FakeLlm(error = LlmException(VoiceErrorCode.LLM_TIMEOUT, "slow"))
        val fallback = assertIs<Resolution.Matched>(resolver(failing, ResolutionMode.LLM_FIRST).resolve(listOf("chat saya otw"), TRIP))
        assertEquals(ResolutionSource.KEYWORD, fallback.source)
        val onlyLlm = assertIs<Resolution.NoMatch>(resolver(failing, ResolutionMode.LLM_ONLY).resolve(listOf("chat saya otw"), TRIP))
        assertEquals(VoiceErrorCode.LLM_TIMEOUT, onlyLlm.error?.code)
    }

    @Test
    fun missingRequiredValueAsksItsQuestion() = runTest {
        val r = assertIs<Resolution.NeedsInput>(resolver(null).resolve(listOf("chat"), TRIP))
        assertEquals("message", r.missing.name)
        assertEquals("Mau kirim pesan apa?", r.question)
        assertEquals("T-9", r.params["tripId"])
    }

    @Test
    fun privacySettingsShapeTheLlmRequest() = runTest {
        val llm = FakeLlm()
        val privacy = PrivacyConfig(sendContextToLlm = false, redactor = { it.replace(Regex("\\d{6,}"), "<nomor>") })
        resolver(llm, privacy = privacy).resolve(listOf("telepon saya di 08123456789 ya"), TRIP)
        val sent = llm.requests.single()
        assertEquals("telepon saya di <nomor> ya", sent.transcript)
        assertTrue(sent.context.isEmpty())
    }
}
