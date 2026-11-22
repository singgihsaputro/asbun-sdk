package id.asbun.voice

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The idle wake-word loop: when it runs, when it yields, how it restarts. */
@OptIn(ExperimentalCoroutinesApi::class)
class WakeLoopTest {

    private val wakeConfig = VoiceConfig(wakeWord = WakeWordConfig(listOf("halo mitra")), dispatch = DispatchConfig(listOf("ojolmitra")))

    private class Harness(
        val sdk: VoiceSdk,
        val engine: FakeVoiceEngine,
        val events: MutableList<VoiceEvent>,
        val opened: MutableList<String>,
        val requests: MutableList<LlmRequest>,
    )

    private fun TestScope.setup(config: VoiceConfig = wakeConfig, wakeAvailable: Boolean = true, withLlm: Boolean = false): Harness {
        val engine = FakeVoiceEngine().also { it.wakeAvailable = wakeAvailable }
        val opened = mutableListOf<String>()
        val requests = mutableListOf<LlmRequest>()
        val llm = if (withLlm) LlmClient { req -> requests += req; LlmDecision(null) } else null
        val sdk = VoiceSdk(config, TEST_MANIFEST, engine, backgroundScope, linkOpener = { opened += it; true }, llmClient = llm, clock = { 0L })
        val events = mutableListOf<VoiceEvent>()
        sdk.addEventListener { events += it }
        return Harness(sdk, engine, events, opened, requests)
    }

    private fun TestScope.enabled(context: VoiceContext = VoiceContext.EMPTY, withLlm: Boolean = false): Harness =
        setup(withLlm = withLlm).also {
            it.sdk.setContext(context)
            it.sdk.setWakeWord(true)
        }

    @Test
    fun enablingStartsWakeSession() = runTest {
        val h = enabled()
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertTrue(h.sdk.state.value.wakeListening)
        assertEquals(VoicePhase.IDLE, h.sdk.phase)
        assertTrue(h.events.none { it is VoiceEvent.StateChanged }, "events=${h.events}")
    }

    @Test
    fun notConfiguredReportsReason() = runTest {
        val h = setup(config = VoiceConfig())
        h.sdk.setWakeWord(true)
        assertEquals(WakeUnavailableReason.NOT_CONFIGURED, h.sdk.state.value.wakeUnavailable)
        assertEquals(0, h.engine.listenCount)
    }

    @Test
    fun noOnDeviceReportsReason() = runTest {
        val h = setup(wakeAvailable = false)
        h.sdk.setWakeWord(true)
        assertEquals(WakeUnavailableReason.NO_ON_DEVICE_RECOGNITION, h.sdk.state.value.wakeUnavailable)
        assertEquals(0, h.engine.listenCount)
    }

    @Test
    fun chatterRestartsQuietly() = runTest {
        val h = enabled()
        h.engine.say("iya bu sebentar")
        advanceTimeBy(251)
        assertEquals(2, h.engine.listenCount)
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertTrue(h.engine.spoken.isEmpty(), "spoken=${h.engine.spoken}")
        assertTrue(h.events.none { it is VoiceEvent.Partial || it is VoiceEvent.Final || it is VoiceEvent.NoMatch }, "events=${h.events}")
        assertNull(h.sdk.state.value.lastHeard)
    }

    @Test
    fun micTapReplacesWakeSession() = runTest {
        val h = enabled()
        h.sdk.startListening()
        assertEquals(ListenMode.COMMAND, h.engine.lastMode)
        assertEquals(VoicePhase.LISTENING, h.sdk.phase)
        assertFalse(h.sdk.state.value.wakeListening)
    }

    @Test
    fun speakCancelsWakeAndResumesAfter() = runTest {
        val h = enabled()
        h.sdk.speak("Order baru")
        assertEquals(listOf("Order baru"), h.engine.spoken)
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertEquals(2, h.engine.listenCount)
        assertTrue(h.sdk.state.value.wakeListening)
    }

    @Test
    fun cancelResumesWake() = runTest {
        val h = enabled()
        h.sdk.startListening()
        h.sdk.cancel()
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertTrue(h.sdk.state.value.wakeListening)
    }

    @Test
    fun disablingStops() = runTest {
        val h = enabled()
        h.sdk.setWakeWord(false)
        assertFalse(h.engine.listening)
        assertFalse(h.sdk.state.value.wakeListening)
        advanceTimeBy(60_000)
        assertEquals(1, h.engine.listenCount)
    }

    @Test
    fun unavailableStopsLoop() = runTest {
        val h = enabled()
        h.engine.fail(VoiceError.NOT_AVAILABLE)
        advanceTimeBy(60_000)
        assertEquals(1, h.engine.listenCount)
        assertEquals(WakeUnavailableReason.NO_ON_DEVICE_RECOGNITION, h.sdk.state.value.wakeUnavailable)
        assertEquals(1, h.events.count { it is VoiceEvent.Error && it.code == VoiceErrorCode.STT_UNAVAILABLE }, "events=${h.events}")
    }

    @Test
    fun permissionDeniedStopsLoop() = runTest {
        val h = enabled()
        h.engine.fail(VoiceError.PERMISSION_DENIED)
        assertEquals(WakeUnavailableReason.PERMISSION_DENIED, h.sdk.state.value.wakeUnavailable)
        advanceTimeBy(60_000)
        assertEquals(1, h.engine.listenCount)
    }

    @Test
    fun permissionDeniedReportedOnceAcrossReenable() = runTest {
        val h = enabled()
        h.engine.fail(VoiceError.PERMISSION_DENIED)
        h.sdk.setWakeWord(true) // e.g. the app re-enables on every resume
        assertEquals(2, h.engine.listenCount)
        h.engine.fail(VoiceError.PERMISSION_DENIED)
        assertEquals(WakeUnavailableReason.PERMISSION_DENIED, h.sdk.state.value.wakeUnavailable)
        assertEquals(1, h.events.count { it is VoiceEvent.Error && it.code == VoiceErrorCode.PERMISSION_DENIED }, "events=${h.events}")
    }

    @Test
    fun commandPermissionDeniedNotRepeatedByWake() = runTest {
        val h = enabled()
        h.sdk.startListening()
        h.engine.fail(VoiceError.PERMISSION_DENIED)
        if (h.engine.listening) h.engine.fail(VoiceError.PERMISSION_DENIED) // wake retry, if any
        assertEquals(1, h.events.count { it is VoiceEvent.Error && it.code == VoiceErrorCode.PERMISSION_DENIED }, "events=${h.events}")
    }

    @Test
    fun abandonedQuestionDoesNotDisableWake() = runTest {
        val h = enabled(HOME)
        h.engine.say("halo mitra lapor")
        assertEquals(VoicePhase.LISTENING, h.sdk.phase) // asked "Mau lapor apa?"
        h.sdk.cancelListening()
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertTrue(h.sdk.state.value.wakeListening)
        assertNull(h.sdk.state.value.awaiting)
    }

    @Test
    fun networkErrorsBackOff() = runTest {
        val h = enabled()
        h.engine.fail(VoiceError.NETWORK)
        advanceTimeBy(999)
        assertEquals(1, h.engine.listenCount)
        advanceTimeBy(2)
        assertEquals(2, h.engine.listenCount)
        h.engine.fail(VoiceError.NETWORK)
        advanceTimeBy(1_999)
        assertEquals(2, h.engine.listenCount)
        advanceTimeBy(2)
        assertEquals(3, h.engine.listenCount)
    }

    @Test
    fun closeStopsPendingRestart() = runTest {
        val h = enabled()
        h.engine.silence()
        h.sdk.close()
        advanceTimeBy(1_000)
        assertEquals(1, h.engine.listenCount)
    }

    // ------------------------------------------------------------------ acting on the phrase

    @Test
    fun phrasePlusCommandRuns() = runTest {
        val h = enabled(OFFER)
        h.engine.say("Halo Mitra, terima")
        assertEquals(listOf("ojolmitra://order/OM-1/accept"), h.opened)
        val wake = h.events.indexOf(VoiceEvent.WakeDetected(true))
        val final = h.events.indexOf(VoiceEvent.Final(listOf("terima")))
        assertTrue(wake >= 0 && final > wake, "events=${h.events}")
        assertEquals("terima", h.sdk.state.value.lastHeard)
    }

    @Test
    fun phraseAloneStartsCommandSession() = runTest {
        val h = enabled()
        h.engine.say("halo mitra")
        assertTrue(VoiceEvent.WakeDetected(false) in h.events, "events=${h.events}")
        assertEquals(ListenMode.COMMAND, h.engine.lastMode)
        assertEquals(VoicePhase.LISTENING, h.sdk.phase)
        h.sdk.setContext(OFFER)
        h.engine.say("terima")
        assertEquals(listOf("ojolmitra://order/OM-1/accept"), h.opened)
    }

    @Test
    fun phraseAloneThenSilenceReturnsQuietly() = runTest {
        val h = enabled()
        h.engine.say("halo mitra")
        h.engine.silence()
        advanceTimeBy(251)
        assertTrue(h.engine.spoken.isEmpty(), "spoken=${h.engine.spoken}")
        assertTrue(h.events.none { it is VoiceEvent.NoMatch }, "events=${h.events}")
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertTrue(h.sdk.state.value.wakeListening)
    }

    @Test
    fun chatterNeverReachesLlm() = runTest {
        val h = enabled(withLlm = true)
        h.engine.say("iya bu saya kejebak macet")
        assertTrue(h.requests.isEmpty(), "requests=${h.requests}")
    }

    @Test
    fun onlyRemainderReachesLlm() = runTest {
        val h = enabled(HOME, withLlm = true)
        h.engine.say("halo mitra airnya sepinggang")
        assertEquals("airnya sepinggang", h.requests.single().transcript)
    }

    @Test
    fun wakeResumesAfterCommand() = runTest {
        val h = enabled(OFFER)
        h.engine.say("halo mitra terima")
        assertEquals(listOf("Order diterima."), h.engine.spoken)
        assertEquals(ListenMode.WAKE, h.engine.lastMode)
        assertTrue(h.sdk.state.value.wakeListening)
    }
}
