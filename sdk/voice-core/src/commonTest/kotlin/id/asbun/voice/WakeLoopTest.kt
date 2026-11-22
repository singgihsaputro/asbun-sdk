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

    private class Harness(val sdk: VoiceSdk, val engine: FakeVoiceEngine, val events: MutableList<VoiceEvent>)

    private fun TestScope.setup(config: VoiceConfig = wakeConfig, wakeAvailable: Boolean = true): Harness {
        val engine = FakeVoiceEngine().also { it.wakeAvailable = wakeAvailable }
        val sdk = VoiceSdk(config, TEST_MANIFEST, engine, backgroundScope, linkOpener = { true }, llmClient = null, clock = { 0L })
        val events = mutableListOf<VoiceEvent>()
        sdk.addEventListener { events += it }
        return Harness(sdk, engine, events)
    }

    private fun TestScope.enabled(): Harness = setup().also { it.sdk.setWakeWord(true) }

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
}
