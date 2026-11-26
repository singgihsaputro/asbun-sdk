package id.ojolmitra.driver

import id.ojolmitra.driver.core.DriverController
import id.ojolmitra.driver.core.HomeTab
import id.ojolmitra.driver.core.SimTiming
import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.model.TripStage
import id.asbun.voice.VoiceEngine
import id.asbun.voice.VoiceEngineListener
import id.asbun.voice.VoiceError
import id.asbun.voice.VoiceSdk
import id.ojolmitra.driver.voice.ListenContext
import id.ojolmitra.driver.voice.OjolMitraVoice
import id.ojolmitra.driver.voice.Speech
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
        DriverController(
            VoiceSdk(OjolMitraVoice.config(), OjolMitraVoice.manifest, engine, scope),
            scope,
            Random(3),
            timing,
            clock = { 1_763_000_000_000L },
            ambientSimulation = false,
            timeOfDay = { 17 * 60 + 40 },
        )

    private fun TestScope.waitForOffer(controller: DriverController) {
        advanceTimeBy(1_100)
        runCurrent()
        assertNotNull(controller.state.value.offer, "order belum masuk")
    }

    private fun DriverController.lastResponse(): String = voicePanel.state.value.log.first().response

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
        assertEquals(ListenContext.ORDER_OFFER, c.voiceUiState().context)

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
        assertEquals(ListenContext.AWAITING_CHAT, c.voiceUiState().context)

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
    fun orderAndTripCommandsOnlyWorkOnTheirScreen() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        val notUnderstood = OjolMitraVoice.config().messages.notUnderstood
        c.speakCommand(engine, "terima")
        assertEquals(notUnderstood, c.lastResponse())
        c.speakCommand(engine, "chat halo")
        assertEquals(notUnderstood, c.lastResponse())
        assertEquals(false, c.voicePanel.state.value.log.first().success)
        assertTrue(!engine.listening, "a mic command that failed is not repeated")
    }

    @Test
    fun voiceContextFollowsTheScreen() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        assertEquals(OjolMitraVoice.SCREEN_HOME, c.voice.currentContext.screen)
        c.setOnline(true)
        waitForOffer(c)
        val offer = assertNotNull(c.state.value.offer)
        assertEquals(OjolMitraVoice.SCREEN_OFFER, c.voice.currentContext.screen)
        assertEquals(offer.order.id, c.voice.currentContext["orderId"])
        c.acceptOffer()
        assertEquals(OjolMitraVoice.SCREEN_TRIP, c.voice.currentContext.screen)
        assertEquals(offer.order.customer.firstName, c.voice.currentContext["customer"])
        c.openChat()
        assertEquals(OjolMitraVoice.SCREEN_CHAT, c.voice.currentContext.screen)
    }

    @Test
    fun chatScreenDictationSendsTheWholeSentence() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setOnline(true)
        waitForOffer(c)
        c.acceptOffer()
        c.openChat()

        c.startChatDictation()
        assertEquals(ListenContext.DICTATION, c.voiceUiState().context)
        assertEquals(OjolMitraVoice.SCREEN_DICTATION, c.voice.currentContext.screen)
        engine.say("terima kasih ya")
        assertTrue(c.state.value.chat.any { it.fromDriver && it.viaVoice && it.text == "Terima kasih ya" })
        assertEquals("Terkirim.", c.lastResponse())
        assertEquals(ListenContext.GENERAL, c.voiceUiState().context, "dictation ends after one message")
        assertEquals(OjolMitraVoice.SCREEN_CHAT, c.voice.currentContext.screen)

        val sent = c.state.value.chat.count { it.fromDriver }
        c.startChatDictation()
        engine.say("batal")
        assertEquals(sent, c.state.value.chat.count { it.fromDriver })
        assertEquals("Oke, pesan dibatalkan.", c.lastResponse())
    }

    @Test
    fun reportWithoutTypeAsksThenReports() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.speakCommand(engine, "lapor")
        assertTrue(c.lastResponse().startsWith("Mau lapor apa"))
        assertTrue(engine.listening)
        engine.say("banjir")
        assertEquals(TrafficType.BANJIR, c.state.value.reports.first().type)
        assertTrue(c.lastResponse().startsWith("Laporan banjir sedang"))
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
    fun busyAreaTabIsReadAloudInHandsFree() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.selectTab(HomeTab.AREA_RAMAI)
        val spots = c.state.value.hotspots
        assertTrue(spots.size >= 4)
        assertEquals(17 * 60 + 40, c.state.value.hotspotsAtMinute)
        assertEquals(OjolMitraVoice.SCREEN_HEATMAP, c.voice.currentContext.screen)
        assertEquals(Speech.hotspotList(spots), engine.spoken.last())

        c.readHotspotAloud(1) // tombol speaker di kartu nomor 2
        assertEquals(Speech.hotspotDetail(spots[1], 2), engine.spoken.last())

        c.selectTab(HomeTab.PENDAPATAN)
        assertEquals(OjolMitraVoice.SCREEN_HOME, c.voice.currentContext.screen)
        val said = engine.spoken.size
        c.selectTab(HomeTab.AREA_RAMAI)
        c.selectTab(HomeTab.AREA_RAMAI) // tab yang sama: tidak dibacakan dua kali
        assertEquals(said + 1, engine.spoken.size)
    }

    @Test
    fun busyAreaTabStaysQuietWithoutHandsFree() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setHandsFree(false)
        c.selectTab(HomeTab.AREA_RAMAI)
        assertTrue(c.state.value.hotspots.isNotEmpty())
        assertTrue(engine.spoken.isEmpty())
        c.readHotspotsAloud()
        assertTrue(engine.spoken.single().startsWith("Ada "))
    }

    @Test
    fun busyAreasByVoiceWithNextDetailAndEvents() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.speakCommand(engine, "mana yang rame")
        assertEquals(HomeTab.AREA_RAMAI, c.state.value.tab)
        val spots = c.state.value.hotspots
        assertEquals(Speech.hotspotList(spots), c.lastResponse())

        c.speakCommand(engine, "berikutnya")
        assertTrue(c.lastResponse().startsWith("Berikutnya: Nomor 4, "), c.lastResponse())

        c.speakCommand(engine, "detail nomor 2")
        assertEquals(Speech.hotspotDetail(spots[1], 2), c.lastResponse())

        c.speakCommand(engine, "ada acara apa")
        assertEquals(Speech.hotspotEvents(c.state.value.hotspots), c.lastResponse())

        c.speakCommand(engine, "bantuan")
        assertTrue(c.lastResponse().startsWith("Di halaman area ramai"), c.lastResponse())
    }

    @Test
    fun busyAreasDuringATripAnswerWithoutLeavingTheTrip() = runTest {
        val engine = FakeVoiceEngine()
        val c = newController(engine)
        c.setOnline(true)
        waitForOffer(c)
        c.acceptOffer()
        c.speakCommand(engine, "area ramai")
        assertNotNull(c.state.value.trip)
        assertEquals(OjolMitraVoice.SCREEN_TRIP, c.voice.currentContext.screen)
        assertTrue(c.lastResponse().startsWith("Ada "), c.lastResponse())
        assertTrue(c.voicePanel.state.value.log.first().success)
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
