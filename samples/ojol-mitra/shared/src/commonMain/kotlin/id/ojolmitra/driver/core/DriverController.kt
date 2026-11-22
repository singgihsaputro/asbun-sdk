package id.ojolmitra.driver.core

import id.asbun.voice.CommandCall
import id.asbun.voice.Deeplinks
import id.asbun.voice.HandlerResult
import id.asbun.voice.VoiceContext
import id.asbun.voice.VoiceErrorCode
import id.asbun.voice.VoiceEvent
import id.asbun.voice.VoiceSdk
import id.asbun.voice.WakeUnavailableReason
import id.ojolmitra.driver.epochMillis
import id.ojolmitra.driver.playWakeTone
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
import id.ojolmitra.driver.model.voiceHint
import id.ojolmitra.driver.model.spokenKm
import id.ojolmitra.driver.model.spokenRupiah
import id.ojolmitra.driver.sim.CustomerBot
import id.ojolmitra.driver.sim.OrderFactory
import id.ojolmitra.driver.voice.OjolMitraVoice
import id.ojolmitra.driver.voice.Speech
import id.ojolmitra.driver.voice.VoicePanel
import id.ojolmitra.driver.voice.VoicePhase
import id.ojolmitra.driver.voice.VoiceUiState
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
 * menjalankan perintah suara dari Voice Command SDK.
 *
 * Perintah suara: [OjolMitraVoice] berisi manifest (frasa -> deeplink / handler).
 * Controller ini menerima deeplink lewat [route], menjalankan handler di
 * [registerVoiceHandlers], dan mengirim konteks layar lewat [syncVoiceContext].
 *
 * Semua fungsi dipanggil di main thread (scope dari Compose).
 */
class DriverController(
    /** Dibuat dengan `VoiceSdkAndroid.create(...)` / `VoiceSdkIos.create(...)`, atau `VoiceSdk(...)` di test. */
    val voice: VoiceSdk,
    private val scope: CoroutineScope,
    private val random: Random = Random.Default,
    private val timing: SimTiming = SimTiming(),
    private val clock: () -> Long = { epochMillis() },
    private val ambientSimulation: Boolean = true,
) {
    private val orders = OrderFactory(random)
    private val bot = CustomerBot(random)
    private var idCounter = 0L

    private val _state = MutableStateFlow(DriverUiState(reports = if (ambientSimulation) initialReports() else emptyList()))
    val state: StateFlow<DriverUiState> = _state.asStateFlow()

    private val _location = MutableStateFlow(DriverLocation(START_POSITION))
    val location: StateFlow<DriverLocation> = _location.asStateFlow()

    private var foreground = true
    /** The "Halo Mitra unavailable" warning is shown once per app run. */
    private var wakeWarned = false
    private var dispatchJob: Job? = null
    private var offerTimerJob: Job? = null
    private var moveJob: Job? = null
    private var ambientJob: Job? = null
    private val tripJobs = mutableListOf<Job>()

    /** Panel asisten, mode dikte, dan riwayat perintah. */
    val voicePanel = VoicePanel(voice, scope, ::now)

    init {
        voice.onDeeplink(::route)
        registerVoiceHandlers()
        scope.launch {
            voice.state.collect {
                if (!wakeWarned && it.wakeUnavailable == WakeUnavailableReason.NO_ON_DEVICE_RECOGNITION) {
                    wakeWarned = true
                    toast(OjolMitraVoice.WAKE_UNAVAILABLE_WARNING)
                }
            }
        }
        voice.addEventListener { event ->
            if (event is VoiceEvent.Error && event.code == VoiceErrorCode.PERMISSION_DENIED) {
                toast("Izinkan akses mikrofon & pengenalan suara di Pengaturan")
            }
            if (event is VoiceEvent.WakeDetected) {
                if (_state.value.offer == null) voicePanel.pin(true)
                if (!event.commandFollows) playWakeTone()
            }
        }
        voicePanel.onDictationChanged = ::syncVoiceContext
        syncVoiceContext()
        if (ambientSimulation) startAmbientReports()
    }

    /** Status suara lengkap untuk UI dan test. */
    fun voiceUiState(): VoiceUiState = VoiceUiState.of(voice.state.value, voicePanel.state.value, _state.value.offer != null)

    // =============================================================== UI actions

    fun toggleOnline() = setOnline(!_state.value.isOnline)

    fun setOnline(online: Boolean) = announce(doSetOnline(online))

    fun setAutobid(enabled: Boolean) {
        mutate { it.copy(autobid = enabled) }
        toast(if (enabled) "Autobid aktif: order diterima otomatis" else "Autobid nonaktif")
    }

    fun setHandsFree(enabled: Boolean) {
        mutate { it.copy(handsFree = enabled) }
        if (!enabled) voice.cancel()
        toast(if (enabled) "Mode hands-free aktif" else "Mode hands-free nonaktif")
    }

    fun setWakeWord(enabled: Boolean) {
        mutate { it.copy(wakeWord = enabled) }
        syncWakeWord()
    }

    /** Kata panggil hanya aktif saat aplikasi terlihat; mic tidak dipegang di latar belakang. */
    fun setAppForeground(foreground: Boolean) {
        this.foreground = foreground
        syncWakeWord()
    }

    private fun syncWakeWord() {
        val s = _state.value
        voice.setWakeWord(s.wakeWord && s.isOnline && foreground)
    }

    fun setReadMessagesAloud(enabled: Boolean) = mutate { it.copy(readMessagesAloud = enabled) }

    fun selectTab(tab: HomeTab) = mutate { it.copy(tab = tab) }

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
        mutate { it.copy(showChat = true, unreadChat = 0) }
    }

    fun closeChat() = mutate { it.copy(showChat = false) }

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

    fun dismissCompleted() = mutate { it.copy(lastCompleted = null) }

    fun consumeSnackbar(id: Long) = mutate { if (it.snackbar?.id == id) it.copy(snackbar = null) else it }

    /** Tombol mic: mulai/selesai mendengarkan. */
    fun onMicPressed() {
        if (voice.phase == VoicePhase.LISTENING) {
            voice.stopListening()
            return
        }
        if (_state.value.offer == null) voicePanel.pin(true)
        voice.startListening()
    }

    /** Mic di layar chat: dikte langsung jadi pesan ke customer (manifest: `chat.dictate`). */
    fun startChatDictation() {
        if (voice.phase == VoicePhase.LISTENING) {
            voice.stopListening()
            return
        }
        if (_state.value.trip == null) return
        voicePanel.setDictating(true)
        voice.startListening()
        if (voice.phase != VoicePhase.LISTENING) voicePanel.setDictating(false) // mis. tidak ada pengenalan suara
    }

    /** Perintah yang diketik di panel asisten (simulasi tanpa mikrofon). */
    fun submitTypedCommand(text: String) {
        voicePanel.pin(true)
        voice.submitText(text)
    }

    fun closeVoicePanel() {
        voice.cancel()
        voicePanel.setDictating(false)
        voicePanel.pin(false)
    }

    fun dispose() {
        dispatchJob?.cancel()
        offerTimerJob?.cancel()
        moveJob?.cancel()
        ambientJob?.cancel()
        cancelTripJobs()
        voice.close() // juga melepas engine suara (SpeechRecognizer / AVAudioEngine)
    }

    // =============================================================== online / offer

    private fun doSetOnline(online: Boolean): String {
        val s = _state.value
        if (!online && s.trip != null) return "Selesaikan order yang sedang berjalan dulu sebelum offline."
        if (online == s.isOnline) return if (online) "Kamu sudah online." else "Kamu sudah offline."
        return if (online) {
            mutate { it.copy(isOnline = true) }
            syncWakeWord()
            startRoaming()
            scheduleNextOffer()
            "Kamu sekarang online. Semangat narik, ${s.profile.firstName}!"
        } else {
            dispatchJob?.cancel()
            offerTimerJob?.cancel()
            moveJob?.cancel()
            if (s.offer != null) voice.cancelListening()
            mutate { it.copy(isOnline = false, offer = null) }
            syncWakeWord()
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
        // Pertanyaan lama (mis. "Mau lapor apa?") tidak boleh menelan jawaban "terima".
        if (voice.state.value.awaiting != null) voice.cancel()
        mutate {
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
                mutate { st -> st.offer?.let { st.copy(offer = it.copy(secondsLeft = left)) } ?: st }
            }
            onOfferExpired()
        }
        when {
            s.autobid -> scope.launch {
                delay(timing.autobidDelayMs)
                if (_state.value.offer?.order?.id == order.id) doAccept(source = "Autobid")?.let(::announce)
            }
            s.handsFree -> voice.speak(Speech.offerAnnouncement(order), listenAfter = true, interrupt = true)
            else -> voice.speak("Ada order ${order.service.spoken} baru.", interrupt = true)
        }
    }

    private fun doAccept(source: String? = null): String? {
        val offer = _state.value.offer ?: return null
        offerTimerJob?.cancel()
        voice.cancelListening()
        val order = offer.order
        val intro = ChatMessage(
            id = nextId(),
            fromDriver = false,
            text = "Order ${order.id} diterima. Kamu terhubung dengan ${order.customer.name}.",
            timeLabel = now(),
            isSystem = true,
        )
        mutate {
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
        voice.cancelListening()
        mutate { it.copy(offer = null, earnings = it.earnings.copy(rejected = it.earnings.rejected + 1)) }
        resumeIdle()
        return "Order dilewati. Mencari order lain."
    }

    private fun onOfferExpired() {
        if (_state.value.offer == null) return
        voice.cancelListening()
        mutate { it.copy(offer = null, earnings = it.earnings.copy(missed = it.earnings.missed + 1)) }
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
        mutate { it.copy(trip = trip.copy(reachedTarget = true)) }
        if (!_state.value.handsFree) return
        val text = when (trip.stage) {
            TripStage.TO_PICKUP -> "Kamu sudah dekat ${Speech.place(trip.order.pickup.name)}. Ucapkan sudah sampai untuk konfirmasi."
            TripStage.TO_DROPOFF -> "Kamu sudah sampai di tujuan. Ucapkan sudah sampai."
            else -> return
        }
        voice.speak(text, listenAfter = true)
    }

    private fun doArrive(): String {
        val trip = _state.value.trip ?: return "Belum ada order aktif."
        val order = trip.order
        return when (trip.stage) {
            TripStage.TO_PICKUP -> {
                moveJob?.cancel()
                placeDriverAt(order.pickup.position)
                mutate { it.copy(trip = trip.copy(stage = TripStage.AT_PICKUP, reachedTarget = true)) }
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
                mutate { it.copy(trip = trip.copy(stage = TripStage.AT_DROPOFF, reachedTarget = true)) }
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
                mutate { it.copy(trip = trip.copy(stage = TripStage.TO_DROPOFF, reachedTarget = false)) }
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
        mutate { s ->
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
        mutate { it.copy(chat = it.chat + ChatMessage(nextId(), fromDriver = true, text = clean, timeLabel = now(), viaVoice = viaVoice)) }
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
        mutate {
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
        mutate { it.copy(reports = (listOf(report) + it.reports).take(MAX_REPORTS)) }
        toast("Laporan ${type.label} terkirim ✓")
        // Simulasi mitra lain ikut mengonfirmasi laporan.
        scope.launch {
            repeat(2 + random.nextInt(5)) {
                delay(random.nextLong(4_000, 11_000))
                mutate { s ->
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
                mutate { s ->
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

    // =============================================================== voice (Voice Command SDK)

    /**
     * Deeplinks from the manifest's `action.url`, e.g. `ojolmitra://order/OM-1/accept`.
     * Returns false for a stale link (offer gone, another trip): the SDK then says it failed.
     */
    private fun route(url: String): Boolean {
        val link = Deeplinks.parse(url) ?: return false
        if (link.scheme != OjolMitraVoice.SCHEME) return false
        val id = link.pathSegments.getOrNull(0) ?: return false
        val action = link.pathSegments.drop(1).joinToString("/")
        val s = _state.value
        return when (link.host) {
            "order" -> when {
                s.offer?.order?.id != id -> false
                action == "accept" -> doAccept() != null
                action == "reject" -> doReject() != null
                else -> false
            }
            "trip" -> when {
                s.trip?.order?.id != id -> false
                action == "chat" -> {
                    openChat()
                    true
                }
                action == "chat/send" -> {
                    val text = link.query["text"]?.trim().orEmpty()
                    if (text.isNotEmpty()) doSendChat(text.capitalizeFirst(), viaVoice = true)
                    text.isNotEmpty()
                }
                action == "call" -> {
                    callCustomer()
                    true
                }
                else -> false
            }
            else -> false
        }
    }

    /** Handlers named in the manifest (`"action": {"type": "handler", "name": ...}`). */
    private fun registerVoiceHandlers() {
        fun handler(name: String, block: (CommandCall) -> HandlerResult) = voice.registerHandler(name) { block(it) }

        // The manifest's `reply` is the whole answer (thanks, cancel, dictation cancel).
        handler("reply") { HandlerResult.success() }

        handler("repeatOrder") {
            val s = _state.value
            val trip = s.trip
            when {
                s.offer != null -> HandlerResult.success(Speech.offerAnnouncement(s.offer.order), listenAfter = true)
                trip != null -> HandlerResult.success(Speech.tripStatus(trip, _location.value.remainingKm))
                else -> HandlerResult.failure(
                    "Belum ada order. ${if (s.isOnline) "Tunggu sebentar ya." else "Ucapkan online untuk mulai narik."}",
                )
            }
        }
        handler("readMessage") {
            val s = _state.value
            val last = s.chat.lastOrNull { !it.fromDriver && !it.isSystem }
            if (last == null) {
                HandlerResult.failure("Belum ada pesan dari customer.")
            } else {
                mutate { it.copy(unreadChat = 0) }
                val from = s.trip?.order?.customer?.firstName ?: "customer"
                HandlerResult.success("Pesan terakhir dari $from: ${Speech.stripEmoji(last.text)}")
            }
        }
        handler("arrive") { tripStep(::doArrive) }
        handler("startTrip") { tripStep(::doStartTrip) }
        handler("nextStep") { tripStep(::doNextStep) }
        handler("completeTrip") {
            if (_state.value.trip == null) {
                HandlerResult.failure(NO_TRIP)
            } else {
                val message = doComplete()
                HandlerResult(success = _state.value.trip == null, reply = message)
            }
        }
        handler("navigate") {
            val trip = _state.value.trip
            if (trip == null) {
                HandlerResult.failure(NO_TRIP)
            } else {
                openNavigation()
                HandlerResult.success("Navigasi ke ${Speech.place(targetOf(trip).name)}, sisa ${spokenKm(_location.value.remainingKm)}.")
            }
        }
        handler("reportTraffic") { call ->
            val type = TrafficType.entries.firstOrNull { it.name == call.param("type") }
            val severity = Severity.entries.firstOrNull { it.name == call.param("severity") } ?: Severity.SEDANG
            if (type == null) {
                HandlerResult.failure()
            } else {
                val place = OjolMitraVoice.spokenPlace(call.param("location"))
                HandlerResult.success(doReportTraffic(type, severity, place, viaVoice = true))
            }
        }
        handler("openTraffic") {
            val s = _state.value
            if (s.trip == null && s.offer == null) selectTab(HomeTab.LALU_LINTAS)
            val latest = s.reports.firstOrNull()?.let { "${it.type.spoken} di ${Speech.place(it.locationLabel)}" } ?: "belum ada"
            HandlerResult.success("Ada ${s.reports.size} laporan lalu lintas di sekitarmu. Yang terbaru: $latest.")
        }
        handler("goOnline") { HandlerResult.success(doSetOnline(true)) }
        handler("goOffline") {
            val message = doSetOnline(false)
            HandlerResult(success = !_state.value.isOnline, reply = message)
        }
        handler("setAutobid") { call ->
            val enabled = call.param("enabled") != "OFF"
            mutate { it.copy(autobid = enabled) }
            HandlerResult.success(if (enabled) "Autobid diaktifkan. Order akan diterima otomatis." else "Autobid dimatikan.")
        }
        handler("earnings") {
            val e = _state.value.earnings
            HandlerResult.success(
                "Pendapatan hari ini ${spokenRupiah(e.today)} dari ${e.trips} trip. Saldo dompet ${spokenRupiah(maxOf(0, e.wallet))}.",
            )
        }
        handler("help") {
            val s = _state.value
            HandlerResult.success(Speech.help(s.offer != null, s.trip != null))
        }
    }

    private fun tripStep(step: () -> String): HandlerResult =
        if (_state.value.trip == null) HandlerResult.failure(NO_TRIP) else HandlerResult.success(step())

    /**
     * Tells the SDK what is on screen. The `screen` and `dictating` values decide which manifest
     * commands are active (`when`); the others fill `{context.*}` in deeplinks and replies.
     */
    private fun syncVoiceContext() {
        val s = _state.value
        val offer = s.offer
        val trip = s.trip
        val dictating = voicePanel.state.value.dictating && trip != null
        val screen = when {
            dictating -> OjolMitraVoice.SCREEN_DICTATION
            offer != null -> OjolMitraVoice.SCREEN_OFFER
            trip != null && s.showChat -> OjolMitraVoice.SCREEN_CHAT
            trip != null -> OjolMitraVoice.SCREEN_TRIP
            else -> OjolMitraVoice.SCREEN_HOME
        }
        val values = buildMap<String, Any?> {
            put("dictating", dictating)
            put("online", s.isOnline)
            put(
                "hint",
                when {
                    dictating -> "Ucapkan pesanmu, atau batal."
                    offer != null -> "Ucapkan terima, atau tolak."
                    trip != null -> "Ucapkan ${trip.stage.voiceHint()}."
                    else -> "Coba ulangi."
                },
            )
            if (offer != null) put("orderId", offer.order.id)
            if (trip != null) {
                val order = trip.order
                val toPickup = trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.AT_PICKUP
                put("tripId", order.id)
                put("customer", order.customer.firstName)
                put("stage", trip.stage.name)
                put("target", Speech.place(targetOf(trip).name))
                put("targetDistance", spokenKm(if (toPickup) order.pickupDistanceKm else order.tripDistanceKm))
            }
        }
        val context = VoiceContext(screen, values)
        if (context != voice.currentContext) voice.setContext(context)
    }

    /** Updates the state and keeps the voice context in step with it. */
    private inline fun mutate(transform: (DriverUiState) -> DriverUiState) {
        _state.update(transform)
        syncVoiceContext()
    }

    // =============================================================== helpers

    /** Konfirmasi suara untuk aksi dari tombol (hanya saat mode hands-free). */
    private fun announce(text: String) {
        if (_state.value.handsFree) voice.speak(text)
    }

    private fun toast(text: String) = mutate { it.copy(snackbar = UiMessage(nextId(), text)) }

    private fun nextId(): Long = ++idCounter

    private fun now(): String = formatClock(clock())

    companion object {
        val START_POSITION = MapPoint(700f, 820f)
        private const val MAX_REPORTS = 20
        private const val NO_TRIP = "Belum ada order aktif."
        private val OTHER_DRIVERS = listOf("Agus", "Joko", "Rudi", "Wawan", "Eko", "Hendra", "Slamet", "Dedi", "Asep", "Yanto")
    }
}
