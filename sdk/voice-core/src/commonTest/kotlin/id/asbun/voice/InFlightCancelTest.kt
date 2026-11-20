package id.asbun.voice

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Commands still being resolved (LLM call in progress) must die with cancel() / close(). */
@OptIn(ExperimentalCoroutinesApi::class)
class InFlightCancelTest {

    /** LLM that answers only when the test says so. */
    private class SlowLlm : LlmClient {
        val answer = CompletableDeferred<LlmDecision>()
        var asked = 0
        override suspend fun decide(request: LlmRequest): LlmDecision {
            asked++
            return answer.await()
        }
    }

    private val accept = LlmDecision(toolName = "order_accept", confidence = 0.9)

    private fun kotlinx.coroutines.test.TestScope.setup(llm: LlmClient): Triple<VoiceSdk, FakeVoiceEngine, MutableList<String>> {
        val engine = FakeVoiceEngine()
        val opened = mutableListOf<String>()
        val sdk = VoiceSdk(
            VoiceConfig(dispatch = DispatchConfig(listOf("ojolmitra"))),
            TEST_MANIFEST, engine, backgroundScope,
            linkOpener = { opened += it; true }, llmClient = llm, clock = { 0L },
        )
        return Triple(sdk, engine, opened)
    }

    @Test
    fun cancelDuringLlmCallDoesNotRunTheCommand() = runTest {
        val llm = SlowLlm()
        val (sdk, engine, opened) = setup(llm)
        sdk.setContext(OFFER)
        sdk.startListening()
        engine.say("hmm itu aja deh") // no keyword match -> LLM
        assertEquals(1, llm.asked)
        assertEquals(VoicePhase.PROCESSING, sdk.phase)

        sdk.cancel()
        assertEquals(VoicePhase.IDLE, sdk.phase)

        llm.answer.complete(accept) // LLM answers late
        runCurrent()

        assertTrue(opened.isEmpty(), "cancelled command must not open a deeplink, opened=$opened")
        assertTrue(engine.spoken.isEmpty(), "nothing should be spoken after cancel, spoken=${engine.spoken}")
    }

    @Test
    fun lateAnswerMustNotActOnTheNextOrder() = runTest {
        val llm = SlowLlm()
        val (sdk, engine, opened) = setup(llm)
        sdk.setContext(OFFER) // order OM-1
        sdk.startListening()
        engine.say("hmm itu aja deh")

        // What DriverController.presentOffer does: cancel, then show a new order.
        sdk.cancel()
        sdk.setContext(VoiceContext(screen = "order_offer", values = mapOf("orderId" to "OM-2")))

        llm.answer.complete(accept)
        runCurrent()

        assertTrue(opened.isEmpty(), "late answer accepted an order nobody asked for, opened=$opened")
    }

    @Test
    fun closeDuringLlmCallDoesNotSpeakOnAShutDownEngine() = runTest {
        val llm = SlowLlm()
        val (sdk, engine, _) = setup(llm)
        sdk.setContext(OFFER)
        sdk.startListening()
        engine.say("hmm itu aja deh")

        sdk.close()
        assertTrue(engine.isShutDown)

        llm.answer.complete(accept)
        runCurrent()

        assertTrue(engine.spoken.isEmpty(), "spoke on a shut-down engine, spoken=${engine.spoken}")
    }

    /** Finding #7: blocks the "cancel the Job" fix, because the client swallows the cancellation. */
    @Test
    fun httpClientLetsCancellationThrough() = runTest {
        val started = CompletableDeferred<Unit>()
        val http = HttpClient(MockEngine { started.complete(Unit); awaitCancellation() })
        val client = HttpLlmClient(LlmConfig("https://llm.example.com", "m", LlmProtocol.ANTHROPIC_MESSAGES, apiKey = "k"), http)
        val request = LlmRequest("x", "id-ID", OFFER.toJson(), LlmTools.build(TEST_MANIFEST.commands), "sys")

        var thrown: Throwable? = null
        withContext(Dispatchers.Default) {
            val job = launch {
                try {
                    client.decide(request)
                } catch (e: Throwable) {
                    thrown = e
                }
            }
            started.await()
            yield()
            job.cancel()
            job.join()
        }
        assertIs<CancellationException>(thrown, "cancellation was rethrown as ${thrown?.let { it::class.simpleName }}: ${thrown?.message}")
    }
}
