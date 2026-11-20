package id.ojolmitra.driver

import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.core.SimTiming
import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.voice.ListenContext
import id.ojolmitra.driver.voice.VoiceEngine
import id.ojolmitra.driver.voice.VoiceEngineListener
import id.ojolmitra.driver.voice.VoiceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Engine palsu: TTS selesai seketika, dan test bisa "mengucapkan" sesuatu. */
private class FakeVoiceEngine : VoiceEngine {
    override val isRecognitionAvailable = true
    override var listener: VoiceEngineListener? = null
    val spoken = mutableListOf<String>()
    var listening = false
        private set
    var listenCount = 0
        private set

    override fun startListening(languageTag: String) {
        listening = true
        listenCount++
        listener?.onListeningStarted()
    }

    override fun stopListening() {}
    override fun cancelListening() {
        listening = false
    }

    override fun speak(text: String, utteranceId: String, languageTag: String) {
        spoken += text
        listener?.onSpeakingStarted(utteranceId)
        listener?.onSpeakingDone(utteranceId)
    }

    override fun stopSpeaking() {}
    override fun shutdown() {}

    fun say(text: String) {
        check(listening) { "Asisten tidak sedang mendengarkan" }
        listening = false
        listener?.onFinalResult(listOf(text))
    }

    fun silence() {
        check(listening)
        listening = false
        listener?.onListeningError(VoiceError.NO_MATCH)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DriverControllerTest {

    private val timing = SimTiming(offerDelayMinMs = 1_000, offerDelayMaxMs = 1_000)

    private fun TestScope.newController(engine: FakeVoiceEngine, scope: CoroutineScope = backgroundScope) =
        DriverController(engine, scope, Random(3), timing, clock = { 1_763_000_000_000L }, ambientSimulation = false)

    private fun TestScope.waitForOffer(controller: DriverController) {
        advanceTimeBy(1_100)
        runCurrent()
        assertNotNull(controller.state.value.offer, "order belum masuk")
    }

    private fun DriverController.lastResponse(): String = voice.state.value.log.first().response

    /** Tekan mic (kalau belum mendengarkan) lalu ucapkan [text]. */
    private fun DriverController.speakCommand(engine: FakeVoiceEngine, text: String) {
        if (!engine.listening) onMicPressed()
        engine.say(text)
    }

    @Test
    fun fullTripHandsFreeByVoice() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)

        c.setOnline(true)
        assertTrue(c.state.value.isOnline)
        waitForOffer(c)

        // Hands-free: order dibacakan lalu asisten langsung mendengarkan.
        assertTrue(engine.spoken.any { it.startsWith("Order ") && it.contains("Ucapkan terima") })
        assertTrue(engine.listening)
        assertEquals(ListenContext.ORDER_OFFER, c.voice.state.value.context)

        engine.say("oke gas terima")
        val trip = assertNotNull(c.state.value.trip)
        assertNull(c.state.value.offer)
        assertEquals(TripStage.TO_PICKUP, trip.stage)
        assertTrue(c.lastResponse().startsWith("Order diterima"))

        // Chat ke customer lewat suara, customer membalas.
        c.speakCommand(engine, "chat saya sudah dekat ya")
        assertTrue(c.state.value.chat.any { it.fromDriver && it.viaVoice && it.text == "Saya sudah dekat ya" })
        advanceTimeBy(8_000)
        runCurrent()
        assertTrue(c.state.value.chat.any { !it.fromDriver && !it.isSystem }, "customer belum membalas")

        c.speakCommand(engine, "sudah sampai")
        assertEquals(TripStage.AT_PICKUP, c.state.value.trip?.stage)
        c.speakCommand(engine, "mulai perjalanan")
        assertEquals(TripStage.TO_DROPOFF, c.state.value.trip?.stage)

        advanceTimeBy(20_000)
        runCurrent()
        assertTrue(c.state.value.trip?.reachedTarget == true, "driver belum sampai tujuan")

        c.speakCommand(engine, "selesaikan order")
        assertNull(c.state.value.trip)
        val earnings = c.state.value.earnings
        assertEquals(1, earnings.trips)
        assertEquals(trip.order.driverEarning, earnings.today)
        assertNotNull(c.state.value.lastCompleted)
        assertTrue(c.lastResponse().startsWith("Order selesai"))

        // Setelah selesai, order berikutnya datang lagi.
        advanceTimeBy(4_000)
        runCurrent()
        assertNotNull(c.state.value.offer)
    }

    @Test
    fun rejectByVoiceThenNextOfferArrives() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setOnline(true)
        waitForOffer(c)
        engine.say("nggak dulu")
        assertNull(c.state.value.offer)
        assertEquals(1, c.state.value.earnings.rejected)
        waitForOffer(c)
    }

    @Test
    fun unclearAnswerIsRepromptedThenOfferExpires() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setOnline(true)
        waitForOffer(c)

        engine.say("apa ya tadi")
        assertTrue(c.lastResponse().contains("Ucapkan terima"))
        assertNotNull(c.state.value.offer, "ucapan tidak jelas tidak boleh menerima order")
        assertTrue(engine.listening, "asisten harus mendengarkan lagi")

        engine.silence()
        assertTrue(engine.listening, "setelah hening, asisten bertanya lagi")

        advanceTimeBy(timing.offerSeconds * 1_000L + 100)
        runCurrent()
        assertNull(c.state.value.offer)
        assertEquals(1, c.state.value.earnings.missed)
        assertTrue(!engine.listening)
    }

    @Test
    fun askChatThenDictateMessage() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setOnline(true)
        waitForOffer(c)
        c.acceptOffer()

        c.speakCommand(engine, "kirim pesan ke penumpang")
        assertTrue(c.lastResponse().startsWith("Mau kirim pesan apa"))
        assertTrue(engine.listening)
        assertEquals(ListenContext.AWAITING_CHAT, c.voice.state.value.context)

        engine.say("saya pakai jaket hijau")
        assertTrue(c.state.value.chat.any { it.fromDriver && it.text == "Saya pakai jaket hijau" })
    }

    @Test
    fun reportTrafficByVoiceUsesSpokenRoad() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.speakCommand(engine, "lapor macet parah di jalan gatot subroto")

        val report = c.state.value.reports.first()
        assertEquals(TrafficType.MACET, report.type)
        assertTrue(report.isMine && report.viaVoice)
        assertEquals("Jl. Gatot Subroto", report.locationLabel)
        assertEquals(CityMap.roads.first { it.name == "Jl. Gatot Subroto" }.coord, report.position.y)
        assertTrue(c.lastResponse().startsWith("Laporan macet parah di jalan Gatot Subroto"))

        // Tanpa lokasi -> pakai jalan tempat driver berada.
        c.speakCommand(engine, "ada kecelakaan di depan")
        assertEquals(CityMap.roadAt(c.location.value.position).name, c.state.value.reports.first().locationLabel)
    }

    @Test
    fun voiceCommandsNeedingTripFailGracefully() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.speakCommand(engine, "terima")
        assertEquals("Belum ada order masuk.", c.lastResponse())
        c.speakCommand(engine, "chat halo")
        assertTrue(c.lastResponse().startsWith("Belum ada order aktif"))
        assertEquals(false, c.voice.state.value.log.first().success)
    }

    @Test
    fun autobidAcceptsAutomatically() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setAutobid(true)
        c.setOnline(true)
        waitForOffer(c)
        advanceTimeBy(timing.autobidDelayMs + 100)
        runCurrent()
        assertNotNull(c.state.value.trip)
        assertTrue(engine.spoken.any { it.startsWith("Autobid") })
    }

    @Test
    fun cannotGoOfflineDuringTrip() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setOnline(true)
        waitForOffer(c)
        c.acceptOffer()
        c.speakCommand(engine, "offline")
        assertTrue(c.state.value.isOnline)
        assertTrue(c.lastResponse().startsWith("Selesaikan order"))
    }
}
