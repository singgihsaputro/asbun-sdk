package id.ojolmitra.driver.core

import id.ojolmitra.driver.epochMillis
import id.ojolmitra.driver.formatClock
import id.ojolmitra.driver.map.CityMap
import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.model.ActiveTrip
import id.ojolmitra.driver.model.ChatMessage
import id.ojolmitra.driver.model.CompletedTrip
import id.ojolmitra.driver.model.Order
import id.ojolmitra.driver.model.OrderOffer
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.ServiceType
import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficReport
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.model.capitalizeFirst
import id.ojolmitra.driver.model.spokenKm
import id.ojolmitra.driver.model.spokenRupiah
import id.ojolmitra.driver.model.voiceHint
import id.ojolmitra.driver.sim.CustomerBot
import id.ojolmitra.driver.sim.OrderFactory
import id.ojolmitra.driver.voice.ListenContext
import id.ojolmitra.driver.voice.ParseContext
import id.ojolmitra.driver.voice.Speech
import id.ojolmitra.driver.voice.VoiceAssistant
import id.ojolmitra.driver.voice.VoiceCommand
import id.ojolmitra.driver.voice.VoiceCommandParser
import id.ojolmitra.driver.voice.VoiceEngine
import id.ojolmitra.driver.voice.VoiceError
import id.ojolmitra.driver.voice.VoicePhase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/**
 * "ViewModel" bersama untuk Android & iOS: menjalankan simulasi driver
 * (order masuk, perjalanan, customer chat, laporan lalu lintas) dan
 * mengeksekusi perintah suara.
 *
 * Semua fungsi dipanggil di main thread (scope dari Compose).
 */
class DriverController(
    voiceEngine: VoiceEngine,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val timing: SimTiming = SimTiming(),
    private val clock: () -> Long = { epochMillis() },
    private val ambientSimulation: Boolean = true,
) {
    val voice = VoiceAssistant(voiceEngine, scope)

    private val orders = OrderFactory(random)
    private val bot = CustomerBot(random)
    private var idCounter = 0L

    private val _state = MutableStateFlow(DriverUiState(reports = if (ambientSimulation) initialReports() else emptyList()))
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    private val _location = MutableStateFlow(DriverLocation(START_POSITION))
    val location: StateFlow<DriverLocation> = _location.asStateFlow()

    private var dispatchJob: Job? = null
    private var offerTimerJob: Job? = null
    private var moveJob: Job? = null
    private var ambientJob: Job? = null
    private val tripJobs = mutableListOf<Job>()
    private var offerRetries = 0

    private data class CommandResult(val message: String, val success: Boolean, val listenAfter: ListenContext? = null)

    init {
        voice.onResult = ::handleVoiceResult
        voice.onListenFailed = ::handleListenFailed
        if (ambientSimulation) startAmbientReports()
    }

    // =============================================================== UI actions

    fun toggleOnline() = setOnline(!_state.value.isOnline)

    fun setOnline(online: Boolean) = announce(doSetOnline(online))

    fun setAutobid(enabled: Boolean) {
        _state.update { it.copy(autobid = enabled) }
        toast(if (enabled) "Autobid aktif: order diterima otomatis" else "Autobid nonaktif")
    }

    fun setHandsFree(enabled: Boolean) {
        _state.update { it.copy(handsFree = enabled) }
        if (!enabled) voice.stopAll()
        toast(if (enabled) "Mode hands-free aktif" else "Mode hands-free nonaktif")
    }

    fun setReadMessagesAloud(enabled: Boolean) = _state.update { it.copy(readMessagesAloud = enabled) }

    fun selectTab(tab: HomeTab) = _state.update { it.copy(tab = tab) }

    fun acceptOffer() {
        doAccept()?.let(::announce)
    }

    fun rejectOffer() {
        doReject()?.let(::announce)
    }

    /** Tombol geser utama di layar perjalanan. */
    fun advanceTrip() = announce(doNextStep())

    fun openChat() {
        if (_state.value.trip == null) return
        _state.update { it.copy(showChat = true, unreadChat = 0) }
    }

    fun closeChat() = _state.update { it.copy(showChat = false) }

    fun sendChat(text: String) {
        if (text.isNotBlank()) doSendChat(text, viaVoice = false)
    }

    fun callCustomer() {
        val trip = _state.value.trip ?: return
        toast("Menelepon ${trip.order.customer.name}… (simulasi)")
    }

    fun openNavigation() {
        val trip = _state.value.trip ?: return
        toast("Navigasi ke ${targetOf(trip).name} dibuka (simulasi)")
    }

    fun reportTraffic(type: TrafficType, severity: Severity, location: String?) =
        announce(doReportTraffic(type, severity, location?.takeIf { it.isNotBlank() }, viaVoice = false))

    /** Tombol simulasi: langsung datangkan order. */
    fun requestOrderNow(service: ServiceType? = null) {
        val s = _state.value
        if (s.trip != null || s.offer != null) return
        if (!s.isOnline) doSetOnline(true)
        dispatchJob?.cancel()
        presentOffer(orders.create(_location.value.position, service))
    }

    fun dismissCompleted() = _state.update { it.copy(lastCompleted = null) }

    fun consumeSnackbar(id: Long) = _state.update { if (it.snackbar?.id == id) it.copy(snackbar = null) else it }

    /** Tombol mic: mulai/selesai mendengarkan. */
    fun onMicPressed() {
        when (voice.phase) {
            VoicePhase.LISTENING -> voice.finishListening()
            else -> {
                val context = if (_state.value.offer != null) ListenContext.ORDER_OFFER else ListenContext.GENERAL
                voice.listen(context, pinPanel = context == ListenContext.GENERAL)
            }
        }
    }

    /** Mic di layar chat: dikte langsung jadi pesan ke customer. */
    fun startChatDictation() {
        if (voice.phase == VoicePhase.LISTENING) voice.finishListening() else voice.listen(ListenContext.DICTATION)
    }

    /** Perintah yang diketik di panel asisten (simulasi tanpa mikrofon). */
    fun submitTypedCommand(text: String) {
        val context = when {
            voice.state.value.context == ListenContext.AWAITING_CHAT -> ListenContext.AWAITING_CHAT
            _state.value.offer != null -> ListenContext.ORDER_OFFER
            else -> ListenContext.GENERAL
        }
        voice.submitTyped(text, context)
    }

    fun closeVoicePanel() = voice.stopAll()

    fun dispose() {
        dispatchJob?.cancel()
        offerTimerJob?.cancel()
        moveJob?.cancel()
        ambientJob?.cancel()
        cancelTripJobs()
        voice.stopAll()
    }

    // =============================================================== online / offer

    private fun doSetOnline(online: Boolean): String {
        val s = _state.value
        if (!online && s.trip != null) return "Selesaikan order yang sedang berjalan dulu sebelum offline."
        if (online == s.isOnline) return if (online) "Kamu sudah online." else "Kamu sudah offline."
        return if (online) {
            _state.update { it.copy(isOnline = true) }
            startRoaming()
            scheduleNextOffer()
            "Kamu sekarang online. Semangat narik, ${s.profile.firstName}!"
        } else {
            dispatchJob?.cancel()
            offerTimerJob?.cancel()
            moveJob?.cancel()
            voice.cancelListening(ListenContext.ORDER_OFFER)
            _state.update { it.copy(isOnline = false, offer = null) }
            "Kamu sekarang offline. Istirahat yang cukup ya."
        }
    }

    private fun scheduleNextOffer(extraDelayMs: Long = 0) {
        dispatchJob?.cancel()
        val s = _state.value
        if (!s.isOnline || s.offer != null || s.trip != null) return
        dispatchJob = scope.launch {
            delay(extraDelayMs + random.nextLong(timing.offerDelayMinMs, timing.offerDelayMaxMs + 1))
            presentOffer(orders.create(_location.value.position))
        }
    }

    private fun presentOffer(order: Order) {
        val s = _state.value
        if (!s.isOnline || s.offer != null || s.trip != null) return
        moveJob?.cancel() // berhenti keliling saat ada tawaran
        offerRetries = 0
        _state.update {
            it.copy(
                offer = OrderOffer(order, timing.offerSeconds, timing.offerSeconds),
                tab = HomeTab.BERANDA,
                showChat = false,
                lastCompleted = null,
                earnings = it.earnings.copy(offers = it.earnings.offers + 1),
            )
        }
        offerTimerJob?.cancel()
        offerTimerJob = scope.launch {
            for (left in timing.offerSeconds - 1 downTo 0) {
                delay(1_000)
                _state.update { st -> st.offer?.let { st.copy(offer = it.copy(secondsLeft = left)) } ?: st }
            }
            onOfferExpired()
        }
        when {
            s.autobid -> scope.launch {
                delay(timing.autobidDelayMs)
                if (_state.value.offer?.order?.id == order.id) doAccept(source = "Autobid")?.let(::announce)
            }
            s.handsFree -> voice.speak(Speech.offerAnnouncement(order), listenAfter = ListenContext.ORDER_OFFER, interrupt = true)
            else -> voice.speak("Ada order ${order.service.spoken} baru.", interrupt = true)
        }
    }

    private fun doAccept(source: String? = null): String? {
        val offer = _state.value.offer ?: return null
        offerTimerJob?.cancel()
        voice.cancelListening(ListenContext.ORDER_OFFER)
        val order = offer.order
        val intro = ChatMessage(
            id = nextId(),
            fromDriver = false,
            text = "Order ${order.id} diterima. Kamu terhubung dengan ${order.customer.name}.",
            timeLabel = now(),
            isSystem = true,
        )
        _state.update {
            it.copy(
                offer = null,
                trip = ActiveTrip(order, TripStage.TO_PICKUP),
                chat = listOf(intro),
                unreadChat = 0,
                showChat = false,
                earnings = it.earnings.copy(accepted = it.earnings.accepted + 1),
            )
        }
        startLeg(order.pickupRoute)
        bot.openingMessage(order)?.let { scheduleCustomerMessage(it, random.nextLong(4_000, 7_000)) }
        return Speech.accepted(order, source)
    }

    private fun doReject(): String? {
        if (_state.value.offer == null) return null
        offerTimerJob?.cancel()
        voice.cancelListening(ListenContext.ORDER_OFFER)
        _state.update { it.copy(offer = null, earnings = it.earnings.copy(rejected = it.earnings.rejected + 1)) }
        resumeIdle()
        return "Order dilewati. Mencari order lain."
    }

    private fun onOfferExpired() {
        if (_state.value.offer == null) return
        voice.cancelListening(ListenContext.ORDER_OFFER)
        _state.update { it.copy(offer = null, earnings = it.earnings.copy(missed = it.earnings.missed + 1)) }
        toast("Order terlewat")
        announce("Order terlewat.")
        resumeIdle()
    }

    private fun resumeIdle(extraDelayMs: Long = 0) {
        val s = _state.value
        if (!s.isOnline || s.trip != null) return
        startRoaming()
        scheduleNextOffer(extraDelayMs)
    }

    // =============================================================== trip

    private fun targetOf(trip: ActiveTrip) =
        if (trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.AT_PICKUP) trip.order.pickup else trip.order.dropoff

    private fun startLeg(path: List<MapPoint>) {
        val length = CityMap.pathLength(path)
        val speed = maxOf(timing.tripSpeedUnitsPerSec, length / timing.maxLegSeconds)
        moveAlong(path, speed, publishRoute = true) { onReachedTarget() }
    }

    private fun onReachedTarget() {
        val trip = _state.value.trip ?: return
        _state.update { it.copy(trip = trip.copy(reachedTarget = true)) }
        if (!_state.value.handsFree) return
        val text = when (trip.stage) {
            TripStage.TO_PICKUP -> "Kamu sudah dekat ${Speech.place(trip.order.pickup.name)}. Ucapkan sudah sampai untuk konfirmasi."
            TripStage.TO_DROPOFF -> "Kamu sudah sampai di tujuan. Ucapkan sudah sampai."
            else -> return
        }
        voice.speak(text, listenAfter = ListenContext.GENERAL)
    }

    private fun doArrive(): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif."
        val order = trip.order
        return when (trip.stage) {
            TripStage.TO_PICKUP -> {
                moveJob?.cancel()
                placeDriverAt(order.pickup.position)
                _state.update { it.copy(trip = trip.copy(stage = TripStage.AT_PICKUP, reachedTarget = true)) }
                bot.atPickupMessage(order)?.let { scheduleCustomerMessage(it, random.nextLong(3_000, 5_000)) }
                val next = when (order.service) {
                    ServiceType.RIDE -> "Setelah penumpang naik"
                    ServiceType.FOOD -> "Setelah makanan diambil"
                    ServiceType.SEND -> "Setelah paket diambil"
                }
                "Sip, kamu di ${Speech.place(order.pickup.name)}. $next, ucapkan mulai perjalanan."
            }
            TripStage.TO_DROPOFF -> {
                moveJob?.cancel()
                placeDriverAt(order.dropoff.position)
                _state.update { it.copy(trip = trip.copy(stage = TripStage.AT_DROPOFF, reachedTarget = true)) }
                val cash = if (order.payment == PaymentMethod.CASH) "Terima tunai ${spokenRupiah(order.fare)}. " else ""
                "Sudah sampai di ${Speech.place(order.dropoff.name)}. ${cash}Ucapkan selesai untuk menyelesaikan order."
            }
            TripStage.AT_PICKUP, TripStage.AT_DROPOFF -> "Kamu sudah di lokasi. Ucapkan ${trip.stage.voiceHint()}."
        }
    }

    private fun doStartTrip(): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif."
        return when (trip.stage) {
            TripStage.TO_PICKUP -> {
                doArrive()
                doStartTrip()
            }
            TripStage.AT_PICKUP -> {
                _state.update { it.copy(trip = trip.copy(stage = TripStage.TO_DROPOFF, reachedTarget = false)) }
                startLeg(trip.order.tripRoute)
                "Perjalanan dimulai. Antar ke ${Speech.place(trip.order.dropoff.name)}, ${spokenKm(trip.order.tripDistanceKm)}."
            }
            TripStage.TO_DROPOFF -> "Perjalanan sudah berjalan menuju ${Speech.place(trip.order.dropoff.name)}."
            TripStage.AT_DROPOFF -> "Kamu sudah di tujuan. Ucapkan selesai."
        }
    }

    private fun doComplete(): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif."
        return when (trip.stage) {
            TripStage.TO_PICKUP, TripStage.AT_PICKUP -> "Order belum bisa diselesaikan, kamu belum mengantar ke tujuan."
            TripStage.TO_DROPOFF -> {
                doArrive()
                completeTrip()
            }
            TripStage.AT_DROPOFF -> completeTrip()
        }
    }

    private fun doNextStep(): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif."
        return when (trip.stage) {
            TripStage.TO_PICKUP, TripStage.TO_DROPOFF -> doArrive()
            TripStage.AT_PICKUP -> doStartTrip()
            TripStage.AT_DROPOFF -> doComplete()
        }
    }

    private fun completeTrip(): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif."
        val order = trip.order
        val rating = if (random.nextInt(100) < 80) 5 else 4
        val completed = CompletedTrip(order, now(), order.driverEarning, rating)
        val cash = order.payment == PaymentMethod.CASH
        _state.update { s ->
            val e = s.earnings
            s.copy(
                trip = null,
                chat = emptyList(),
                unreadChat = 0,
                showChat = false,
                lastCompleted = completed,
                completedTrips = listOf(completed) + s.completedTrips,
                earnings = e.copy(
                    today = e.today + order.driverEarning,
                    trips = e.trips + 1,
                    completed = e.completed + 1,
                    // Tunai: customer bayar ke driver, potongan aplikasi diambil dari dompet.
                    wallet = e.wallet + if (cash) order.driverEarning - order.fare else order.driverEarning,
                    cashCollected = e.cashCollected + if (cash) order.fare else 0,
                    ratingSum = e.ratingSum + rating,
                    ratingCount = e.ratingCount + 1,
                ),
            )
        }
        cancelTripJobs()
        _location.update { it.copy(remainingRoute = emptyList(), remainingKm = 0.0) }
        resumeIdle(extraDelayMs = 2_500)
        return "Order selesai. Kamu dapat ${spokenRupiah(order.driverEarning)}. " +
            "Total hari ini ${spokenRupiah(_state.value.earnings.today)}."
    }

    // =============================================================== chat

    private fun doSendChat(text: String, viaVoice: Boolean): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif, chat belum bisa dikirim."
        val clean = text.trim()
        if (clean.isEmpty()) return "Pesannya kosong."
        _state.update { it.copy(chat = it.chat + ChatMessage(nextId(), fromDriver = true, text = clean, timeLabel = now(), viaVoice = viaVoice)) }
        scheduleCustomerMessage(
            bot.replyTo(clean, trip.order, trip.stage),
            random.nextLong(timing.customerReplyMinMs, timing.customerReplyMaxMs + 1),
        )
        return "Pesan terkirim ke ${trip.order.customer.firstName}: $clean"
    }

    private fun scheduleCustomerMessage(text: String, delayMs: Long) {
        val orderId = _state.value.trip?.order?.id ?: return
        tripJobs += scope.launch {
            delay(delayMs)
            receiveCustomerMessage(text, orderId)
        }
    }

    private fun receiveCustomerMessage(text: String, orderId: String) {
        val s = _state.value
        val trip = s.trip ?: return
        if (trip.order.id != orderId) return
        _state.update {
            it.copy(
                chat = it.chat + ChatMessage(nextId(), fromDriver = false, text = text, timeLabel = now()),
                unreadChat = if (it.showChat) 0 else it.unreadChat + 1,
            )
        }
        if (s.handsFree && s.readMessagesAloud) {
            voice.speak("Pesan dari ${trip.order.customer.firstName}: ${Speech.stripEmoji(text)}")
        }
    }

    private fun cancelTripJobs() {
        tripJobs.forEach { it.cancel() }
        tripJobs.clear()
    }

    // =============================================================== traffic

    private fun doReportTraffic(type: TrafficType, severity: Severity, spokenLocation: String?, viaVoice: Boolean): String {
        val here = _location.value.position
        val match = spokenLocation?.let { CityMap.matchLocation(it, here) }
        val label = when {
            spokenLocation == null -> CityMap.roadAt(here).name
            match == null -> spokenLocation
            match.isPlace -> match.label
            spokenLocation.split(' ').size > 2 -> spokenLocation // ada detail tambahan, mis. "arah Blok M"
            else -> match.label
        }
        val report = TrafficReport(
            id = nextId(),
            type = type,
            severity = severity,
            locationLabel = label,
            position = match?.position ?: here,
            reporter = "Kamu",
            isMine = true,
            createdAtMillis = clock(),
            viaVoice = viaVoice,
        )
        _state.update { it.copy(reports = (listOf(report) + it.reports).take(MAX_REPORTS)) }
        toast("Laporan ${type.label} terkirim ✓")
        // Simulasi mitra lain ikut mengonfirmasi laporan.
        scope.launch {
            repeat(2 + random.nextInt(5)) {
                delay(random.nextLong(4_000, 11_000))
                _state.update { s ->
                    s.copy(reports = s.reports.map { r -> if (r.id == report.id) r.copy(confirmations = r.confirmations + 1) else r })
                }
            }
        }
        return "Laporan ${type.spoken} ${severity.label.lowercase()} di ${Speech.place(label)} sudah dikirim. " +
            "Terima kasih sudah bantu mitra lain."
    }

    private fun initialReports(): List<TrafficReport> {
        val now = clock()
        return listOf(
            communityReport(TrafficType.MACET, Severity.PARAH, CityMap.roads[9], 0.35f, now - 4 * 60_000),
            communityReport(TrafficType.BANJIR, Severity.SEDANG, CityMap.roads[11], 0.62f, now - 12 * 60_000),
            communityReport(TrafficType.RAZIA, Severity.SEDANG, CityMap.roads[3], 0.2f, now - 7 * 60_000),
            communityReport(TrafficType.JALAN_RUSAK, Severity.RINGAN, CityMap.roads[5], 0.75f, now - 25 * 60_000),
        )
    }

    private fun communityReport(
        type: TrafficType,
        severity: Severity,
        road: id.ojolmitra.driver.map.Road,
        fraction: Float,
        createdAt: Long,
    ): TrafficReport {
        val t = CityMap.MIN + (CityMap.MAX - CityMap.MIN) * fraction
        val pos = if (road.vertical) MapPoint(road.coord, t) else MapPoint(t, road.coord)
        return TrafficReport(
            id = nextId(),
            type = type,
            severity = severity,
            locationLabel = road.name,
            position = pos,
            reporter = "Mitra ${OTHER_DRIVERS[random.nextInt(OTHER_DRIVERS.size)]}",
            isMine = false,
            createdAtMillis = createdAt,
            confirmations = 1 + random.nextInt(14),
        )
    }

    private fun startAmbientReports() {
        ambientJob = scope.launch {
            while (isActive) {
                delay(timing.communityReportEveryMs + random.nextLong(0, 20_000))
                val types = TrafficType.entries
                val type = if (random.nextInt(100) < 45) TrafficType.MACET else types[random.nextInt(types.size)]
                val report = communityReport(
                    type,
                    Severity.entries[random.nextInt(Severity.entries.size)],
                    CityMap.roads[random.nextInt(CityMap.roads.size)],
                    random.nextFloat(),
                    clock(),
                )
                val cutoff = clock() - 40 * 60_000
                _state.update { s ->
                    s.copy(reports = (listOf(report) + s.reports.filter { it.isMine || it.createdAtMillis > cutoff }).take(MAX_REPORTS))
                }
            }
        }
    }

    // =============================================================== movement

    private fun placeDriverAt(point: MapPoint) {
        _location.update { it.copy(position = point, remainingRoute = emptyList(), remainingKm = 0.0) }
    }

    private fun moveAlong(path: List<MapPoint>, speed: Float, publishRoute: Boolean, onArrive: () -> Unit) {
        moveJob?.cancel()
        moveJob = scope.launch {
            if (path.size < 2 || CityMap.pathLength(path) < 1f) {
                delay(500)
                onArrive()
                return@launch
            }
            var segment = 0
            var pos = path.first()
            val dt = timing.tickMs / 1000f
            while (segment < path.size - 1) {
                delay(timing.tickMs)
                var travel = speed * dt
                while (travel > 0f && segment < path.size - 1) {
                    val target = path[segment + 1]
                    val d = pos.distanceTo(target)
                    if (d <= travel) {
                        pos = target
                        travel -= d
                        segment++
                    } else {
                        pos = pos.lerp(target, travel / d)
                        travel = 0f
                    }
                }
                val heading = if (segment < path.size - 1) pos.headingTo(path[segment + 1]) else _location.value.heading
                val remaining = if (publishRoute) listOf(pos) + path.drop(segment + 1) else emptyList()
                _location.value = DriverLocation(
                    position = pos,
                    heading = heading,
                    remainingRoute = remaining,
                    remainingKm = if (publishRoute) CityMap.unitsToKm(CityMap.pathLength(remaining)) else 0.0,
                )
            }
            onArrive()
        }
    }

    /** Keliling santai di sekitar saat online menunggu order. */
    private fun startRoaming() {
        val s = _state.value
        if (!s.isOnline || s.trip != null || s.offer != null) return
        val here = _location.value.position
        val candidates = CityMap.destinations.filter { it.position.distanceTo(here) in 150f..650f }
        val target = if (candidates.isNotEmpty()) candidates[random.nextInt(candidates.size)]
        else CityMap.destinations[random.nextInt(CityMap.destinations.size)]
        moveAlong(CityMap.route(here, target.position), timing.roamSpeedUnitsPerSec, publishRoute = false) { startRoaming() }
    }

    // =============================================================== voice

    private fun handleVoiceResult(alternatives: List<String>, context: ListenContext) {
        val heard = alternatives.first()
        val s = _state.value

        if (context == ListenContext.AWAITING_CHAT || context == ListenContext.DICTATION) {
            if (VoiceCommandParser.parse(heard) == VoiceCommand.Cancel) {
                respond(heard, CommandResult("Oke, pesan dibatalkan.", true))
                return
            }
            val message = doSendChat(heard.capitalizeFirst(), viaVoice = true)
            val spoken = if (context == ListenContext.DICTATION && s.trip != null) "Terkirim." else message
            respond(heard, CommandResult(spoken, s.trip != null))
            return
        }

        val parseContext = ParseContext(hasOffer = s.offer != null, hasTrip = s.trip != null)
        val command = alternatives.asSequence()
            .map { VoiceCommandParser.parse(it, parseContext) }
            .firstOrNull { it !is VoiceCommand.Unknown }
            ?: VoiceCommand.Unknown(heard)
        respond(heard, execute(command))
    }

    private fun handleListenFailed(error: VoiceError, context: ListenContext) {
        when {
            error == VoiceError.PERMISSION_DENIED -> toast("Izinkan akses mikrofon & pengenalan suara di Pengaturan")
            context == ListenContext.ORDER_OFFER && error == VoiceError.NO_MATCH &&
                _state.value.offer != null && offerRetries < 2 -> {
                offerRetries++
                voice.speak("Belum terdengar. Ucapkan terima, atau tolak.", listenAfter = ListenContext.ORDER_OFFER)
            }
        }
    }

    private fun respond(heard: String, result: CommandResult) {
        voice.addLog(heard, result.message, result.success, now())
        voice.speak(result.message, listenAfter = result.listenAfter)
    }

    private fun execute(command: VoiceCommand): CommandResult {
        val s = _state.value
        val trip = s.trip
        fun ok(message: String, listenAfter: ListenContext? = null) = CommandResult(message, true, listenAfter)
        fun fail(message: String, listenAfter: ListenContext? = null) = CommandResult(message, false, listenAfter)
        val noTrip = "Belum ada order aktif."

        return when (command) {
            VoiceCommand.AcceptOrder -> doAccept()?.let { ok(it) } ?: fail("Belum ada order masuk.")
            VoiceCommand.RejectOrder -> doReject()?.let { ok(it) } ?: fail("Tidak ada order yang bisa ditolak.")
            VoiceCommand.RepeatOrder -> when {
                s.offer != null -> ok(Speech.offerAnnouncement(s.offer.order), ListenContext.ORDER_OFFER)
                trip != null -> ok(Speech.tripStatus(trip, _location.value.remainingKm))
                else -> fail("Belum ada order. ${if (s.isOnline) "Tunggu sebentar ya." else "Ucapkan online untuk mulai narik."}")
            }

            is VoiceCommand.SendChat ->
                if (trip == null) fail("$noTrip Chat ke customer bisa dikirim setelah order diterima.")
                else ok(doSendChat(command.message, viaVoice = true))
            VoiceCommand.AskChatMessage ->
                if (trip == null) fail(noTrip)
                else ok("Mau kirim pesan apa ke ${trip.order.customer.firstName}?", ListenContext.AWAITING_CHAT)
            VoiceCommand.OpenChat ->
                if (trip == null) fail(noTrip) else {
                    openChat()
                    ok("Membuka chat dengan ${trip.order.customer.firstName}.")
                }
            VoiceCommand.ReadLastMessage -> {
                val last = s.chat.lastOrNull { !it.fromDriver && !it.isSystem }
                if (last == null) fail("Belum ada pesan dari customer.")
                else {
                    _state.update { it.copy(unreadChat = 0) }
                    ok("Pesan terakhir dari ${trip?.order?.customer?.firstName ?: "customer"}: ${Speech.stripEmoji(last.text)}")
                }
            }
            VoiceCommand.CallCustomer ->
                if (trip == null) fail(noTrip) else {
                    callCustomer()
                    ok("Menelepon ${trip.order.customer.firstName}. Ini simulasi.")
                }

            is VoiceCommand.ReportTraffic -> ok(doReportTraffic(command.type, command.severity, command.location, viaVoice = true))
            VoiceCommand.AskTrafficReport -> ok(
                "Mau lapor apa? Misalnya macet, kecelakaan, banjir, atau jalan ditutup.",
                ListenContext.GENERAL,
            )
            VoiceCommand.OpenTrafficReports -> {
                if (trip == null && s.offer == null) selectTab(HomeTab.LALU_LINTAS)
                val active = s.reports.size
                ok("Ada $active laporan lalu lintas di sekitarmu. Yang terbaru: ${s.reports.firstOrNull()?.let { "${it.type.spoken} di ${Speech.place(it.locationLabel)}" } ?: "belum ada"}.")
            }

            VoiceCommand.Arrived -> if (trip == null) fail(noTrip) else ok(doArrive())
            VoiceCommand.StartTrip -> if (trip == null) fail(noTrip) else ok(doStartTrip())
            VoiceCommand.CompleteTrip -> if (trip == null) fail(noTrip) else {
                val message = doComplete()
                CommandResult(message, _state.value.trip == null)
            }
            VoiceCommand.NextStep -> if (trip == null) fail(noTrip) else ok(doNextStep())
            VoiceCommand.OpenNavigation -> if (trip == null) fail(noTrip) else {
                openNavigation()
                ok("Navigasi ke ${Speech.place(targetOf(trip).name)}, sisa ${spokenKm(_location.value.remainingKm)}.")
            }

            VoiceCommand.GoOnline -> ok(doSetOnline(true))
            VoiceCommand.GoOffline -> {
                val message = doSetOnline(false)
                CommandResult(message, !_state.value.isOnline)
            }
            is VoiceCommand.SetAutobid -> {
                _state.update { it.copy(autobid = command.enabled) }
                ok(if (command.enabled) "Autobid diaktifkan. Order akan diterima otomatis." else "Autobid dimatikan.")
            }
            VoiceCommand.Earnings -> {
                val e = s.earnings
                ok("Pendapatan hari ini ${spokenRupiah(e.today)} dari ${e.trips} trip. Saldo dompet ${spokenRupiah(maxOf(0, e.wallet))}.")
            }

            VoiceCommand.Help -> ok(Speech.help(s.offer != null, trip != null))
            VoiceCommand.Cancel -> ok("Oke.")
            VoiceCommand.Thanks -> ok("Sama-sama! Hati-hati di jalan ya.")
            is VoiceCommand.Unknown ->
                if (s.offer != null && offerRetries < 2) {
                    offerRetries++
                    fail("Maaf, kurang jelas. Ucapkan terima, atau tolak.", ListenContext.ORDER_OFFER)
                } else {
                    fail("Maaf, perintah belum dikenali. Ucapkan bantuan untuk contoh perintah.")
                }
        }
    }

    // =============================================================== helpers

    /** Konfirmasi suara untuk aksi dari tombol (hanya saat mode hands-free). */
    private fun announce(text: String) {
        if (_state.value.handsFree) voice.speak(text)
    }

    private fun toast(text: String) = _state.update { it.copy(snackbar = UiMessage(nextId(), text)) }

    private fun nextId(): Long = ++idCounter

    private fun now(): String = formatClock(clock())

    companion object {
        val START_POSITION = MapPoint(700f, 820f)
        private const val MAX_REPORTS = 20
        private val OTHER_DRIVERS = listOf("Agus", "Joko", "Rudi", "Wawan", "Eko", "Hendra", "Slamet", "Dedi", "Asep", "Yanto")
    }
}
