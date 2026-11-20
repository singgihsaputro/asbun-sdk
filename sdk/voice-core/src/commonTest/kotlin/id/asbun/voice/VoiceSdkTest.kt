package id.asbun.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSdkTest {

    private class Harness(scope: CoroutineScope, config: VoiceConfig, llm: LlmClient?) {
        val engine = FakeVoiceEngine()
        var now = 0L
        val opened = mutableListOf<String>()
        val routed = mutableListOf<String>()
        val events = mutableListOf<VoiceEvent>()
        val sdk = VoiceSdk(config, TEST_MANIFEST, engine, scope, linkOpener = { opened += it; true }, llmClient = llm, clock = { now })

        fun route(handled: Boolean = true) = sdk.onDeeplink { routed += it; handled }

        /** Tap the mic and say [text]. */
        fun command(text: String) {
            if (!engine.listening) sdk.startListening()
            engine.say(text)
        }
    }

    private fun TestScope.harness(
        config: VoiceConfig = VoiceConfig(dispatch = DispatchConfig(listOf("ojolmitra"))),
        llm: LlmClient? = null,
    ): Harness {
        val h = Harness(backgroundScope, config, llm)
        // Unconfined: every event lands in the list as soon as it is emitted.
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.sdk.events.collect { h.events += it } }
        return h
    }

    @Test
    fun deeplinkGoesToTheAppRouterAndTheReplyIsSpoken() = runTest {
        val h = harness()
        h.route()
        h.sdk.setContext(OFFER)
        h.command("terima")
        assertEquals(listOf("ojolmitra://order/OM-1/accept"), h.routed)
        assertTrue(h.opened.isEmpty(), "the system opener is only a fallback")
        assertEquals("Order diterima.", h.engine.spoken.last())
        assertEquals(VoicePhase.IDLE, h.sdk.phase)
        val resolved = h.events.filterIsInstance<VoiceEvent.Resolved>().single()
        assertEquals("order.accept", resolved.commandId)
        val action = h.events.filterIsInstance<VoiceEvent.Action>().single()
        assertTrue(action.success)
        assertEquals("ojolmitra://order/OM-1/accept", action.deeplink)
    }

    @Test
    fun systemOpenerIsUsedWhenTheRouterDeclines() = runTest {
        val h = harness()
        h.route(handled = false)
        h.sdk.setContext(TRIP)
        h.command("chat saya sudah di depan")
        assertEquals(listOf("ojolmitra://trip/T-9/chat?text=saya%20sudah%20di%20depan"), h.opened)
        assertEquals("Pesan terkirim.", h.engine.spoken.last())
    }

    @Test
    fun blockedSchemeIsNeverOpened() = runTest {
        val h = harness(config = VoiceConfig()) // no allowed schemes
        h.route()
        h.sdk.setContext(OFFER)
        h.command("terima")
        assertTrue(h.routed.isEmpty() && h.opened.isEmpty())
        assertEquals(VoiceConfig().messages.actionFailed, h.engine.spoken.last())
        assertEquals(VoiceErrorCode.DEEPLINK_BLOCKED, h.events.filterIsInstance<VoiceEvent.Error>().single().code)
        assertFalse(h.events.filterIsInstance<VoiceEvent.Action>().single().success)
    }

    @Test
    fun handlerGetsParamsAndItsReplyCanListenAgain() = runTest {
        val h = harness()
        var call: CommandCall? = null
        h.sdk.registerHandler("reportTraffic") { c ->
            call = c
            HandlerResult.success("Laporan diterima. Ada lagi?", listenAfter = true)
        }
        h.sdk.setContext(HOME)
        h.command("lapor macet parah di jalan sudirman")
        val c = call!!
        assertEquals("traffic.report", c.commandId)
        assertEquals("MACET", c.param("type"))
        assertEquals("PARAH", c.param("severity"))
        assertEquals("jalan sudirman", c.param("location"))
        assertEquals(ResolutionSource.KEYWORD, c.source)
        assertEquals("Laporan diterima. Ada lagi?", h.engine.spoken.last())
        assertTrue(h.engine.listening)
        assertTrue(h.sdk.state.value.prompted)
    }

    @Test
    fun manifestReplyUsesDefaultsAndEnumWords() = runTest {
        val h = harness()
        h.sdk.registerHandler("reportTraffic") { HandlerResult.success() }
        h.sdk.setContext(HOME)
        h.command("ada kecelakaan")
        assertEquals("Laporan kecelakaan di Jl. Gatot Subroto terkirim.", h.engine.spoken.last())
    }

    @Test
    fun missingValueIsAskedThenFilled() = runTest {
        val h = harness()
        h.route()
        h.sdk.setContext(TRIP)
        h.command("kirim pesan ke penumpang")
        assertEquals("Mau kirim pesan apa?", h.engine.spoken.last())
        assertTrue(h.engine.listening)
        assertEquals(Awaiting.Input("chat.send", "message"), h.sdk.state.value.awaiting)

        h.engine.say("saya pakai jaket hijau")
        assertEquals(listOf("ojolmitra://trip/T-9/chat?text=saya%20pakai%20jaket%20hijau"), h.routed)
        assertEquals(null, h.sdk.state.value.awaiting)
    }

    @Test
    fun enumAnswerAndCancelDuringQuestion() = runTest {
        val h = harness()
        val types = mutableListOf<String?>()
        h.sdk.registerHandler("reportTraffic") { types += it.param("type"); HandlerResult.success() }
        h.sdk.setContext(HOME)
        h.command("lapor")
        assertEquals("Mau lapor apa? Macet, kecelakaan, atau banjir?", h.engine.spoken.last())
        assertIs<VoiceEvent.NeedsInput>(h.events.last { it is VoiceEvent.NeedsInput })
        h.engine.say("tabrakan")
        assertEquals(listOf<String?>("KECELAKAAN"), types)

        h.command("lapor")
        h.engine.say("batal")
        assertEquals(VoiceMessages.INDONESIAN.cancelled, h.engine.spoken.last())
        assertEquals(1, types.size)
        assertEquals("traffic.report", h.events.filterIsInstance<VoiceEvent.Cancelled>().single().commandId)
    }

    @Test
    fun confirmationGuardsDestructiveCommands() = runTest {
        val h = harness()
        h.route()
        h.sdk.setContext(OFFER)
        h.command("tolak")
        assertEquals("Yakin tolak order ini?", h.engine.spoken.last())
        assertEquals(Awaiting.Confirmation("order.reject"), h.sdk.state.value.awaiting)
        h.engine.say("tidak")
        assertTrue(h.routed.isEmpty())
        assertEquals(VoiceMessages.INDONESIAN.cancelled, h.engine.spoken.last())

        h.command("tolak")
        h.engine.say("iya")
        assertEquals(listOf("ojolmitra://order/OM-1/reject"), h.routed)
        assertEquals("Order ditolak.", h.engine.spoken.last())
    }

    @Test
    fun promptsAreRepeatedAtMostMaxRepromptsTimes() = runTest {
        val messages = VoiceMessages.INDONESIAN.copy(reprompt = "Maaf, kurang jelas. {context.hint}", noSpeech = "Belum terdengar. {context.hint}")
        val h = harness(config = VoiceConfig(dispatch = DispatchConfig(listOf("ojolmitra")), messages = messages))
        h.sdk.setContext(OFFER.copy(values = OFFER.values + ("hint" to "Ucapkan terima atau tolak.")))
        h.sdk.speak("Order baru masuk.", listenAfter = true)
        assertTrue(h.engine.listening)

        h.engine.say("apa ya tadi")
        assertEquals("Maaf, kurang jelas. Ucapkan terima atau tolak.", h.engine.spoken.last())
        assertTrue(h.engine.listening)
        h.engine.silence()
        assertEquals("Belum terdengar. Ucapkan terima atau tolak.", h.engine.spoken.last())
        assertTrue(h.engine.listening)
        h.engine.say("hmm entahlah")
        assertEquals(VoiceMessages.INDONESIAN.notUnderstood, h.engine.spoken.last())
        assertFalse(h.engine.listening, "gives up after two re-prompts")
    }

    @Test
    fun micCommandThatFailsIsNotRepeated() = runTest {
        val h = harness()
        h.sdk.setContext(HOME)
        h.command("blablabla")
        assertEquals(VoiceMessages.INDONESIAN.notUnderstood, h.engine.spoken.last())
        assertFalse(h.engine.listening)
    }

    @Test
    fun llmResolutionIsMarkedAsSuch() = runTest {
        val llm = LlmClient { LlmDecision("traffic_report", buildJsonObject { put("type", "BANJIR") }, confidence = 0.85) }
        val config = VoiceConfig(dispatch = DispatchConfig(listOf("ojolmitra")))
        val h = harness(config = config, llm = llm)
        var source: ResolutionSource? = null
        h.sdk.registerHandler("reportTraffic") { source = it.source; HandlerResult.success() }
        h.sdk.setContext(HOME)
        h.command("airnya sudah sepinggang di underpass")
        assertEquals(ResolutionSource.LLM, source)
        assertEquals("Laporan banjir di Jl. Gatot Subroto terkirim.", h.engine.spoken.last())
    }

    @Test
    fun ttsCanBeTurnedOff() = runTest {
        val h = harness(config = VoiceConfig(ttsEnabled = false, dispatch = DispatchConfig(listOf("ojolmitra"))))
        h.route()
        h.sdk.setContext(TRIP)
        h.command("chat")
        assertTrue(h.engine.spoken.isEmpty())
        assertEquals("Mau kirim pesan apa?", h.sdk.state.value.lastReply)
        assertTrue(h.engine.listening, "still listens for the answer")
    }

    @Test
    fun sameCommandRepeatedWithinWindowIsIgnored() = runTest {
        val h = harness()
        h.route()
        h.sdk.setContext(OFFER)
        h.command("terima")
        h.command("terima")
        assertEquals(1, h.routed.size)
        h.now = 5_000
        h.command("terima")
        assertEquals(2, h.routed.size)
    }

    @Test
    fun missingHandlerAndUnavailableRecognitionAreReported() = runTest {
        val h = harness()
        h.sdk.setContext(HOME)
        h.command("bantuan") // no "help" handler registered
        assertEquals(VoiceMessages.INDONESIAN.actionFailed, h.engine.spoken.last())
        assertEquals(VoiceErrorCode.HANDLER_FAILED, h.events.filterIsInstance<VoiceEvent.Error>().single().code)

        val silent = VoiceSdk(VoiceConfig(), TEST_MANIFEST, SilentVoiceEngine(), backgroundScope)
        silent.startListening()
        assertEquals(VoiceErrorCode.STT_UNAVAILABLE, silent.state.value.error)
        assertFalse(silent.state.value.recognitionAvailable)
    }

    @Test
    fun eventListenerIsCalledSynchronouslyUntilRemoved() = runTest {
        val h = harness()
        h.route()
        h.sdk.setContext(OFFER)
        val seen = mutableListOf<VoiceEvent>()
        val listener = VoiceEventListener { seen += it }
        h.sdk.addEventListener(listener)
        h.sdk.submitText("terima")
        // No collector or scheduler step needed: the events are already there.
        assertEquals("order.accept", seen.filterIsInstance<VoiceEvent.Resolved>().single().commandId)
        assertTrue(seen.filterIsInstance<VoiceEvent.Action>().single().success)
        assertTrue(seen.indexOfFirst { it is VoiceEvent.Resolved } < seen.indexOfFirst { it is VoiceEvent.Action })

        h.sdk.removeEventListener(listener)
        val count = seen.size
        h.now = 5_000
        h.sdk.submitText("terima")
        assertEquals(count, seen.size)
    }

    @Test
    fun closeReleasesTheEngine() = runTest {
        val h = harness()
        h.sdk.startListening()
        h.sdk.close()
        assertFalse(h.engine.listening)
        assertTrue(h.engine.isShutDown)
        assertEquals(null, h.engine.listener)
    }

    @Test
    fun typedTextWorksWithoutAMicrophone() = runTest {
        val sdk = VoiceSdk(VoiceConfig(dispatch = DispatchConfig(listOf("ojolmitra"))), TEST_MANIFEST, SilentVoiceEngine(), backgroundScope)
        val routed = mutableListOf<String>()
        sdk.onDeeplink { routed += it; true }
        sdk.setContext(OFFER)
        sdk.submitText("terima")
        assertEquals(listOf("ojolmitra://order/OM-1/accept"), routed)
    }
}
