package id.ojolmitra.driver.voice

import id.ojolmitra.driver.model.ActiveTrip
import id.ojolmitra.driver.model.Hotspot
import id.ojolmitra.driver.model.HotspotLevel
import id.ojolmitra.driver.model.Order
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.model.capitalizeFirst
import id.ojolmitra.driver.model.spokenClock
import id.ojolmitra.driver.model.spokenDuration
import id.ojolmitra.driver.model.spokenKm
import id.ojolmitra.driver.model.spokenNumber
import id.ojolmitra.driver.model.spokenRupiah
import id.ojolmitra.driver.model.spokenTimeRange
import id.ojolmitra.driver.model.title

/** Kalimat-kalimat yang diucapkan asisten (dirancang enak didengar TTS Bahasa Indonesia). */
object Speech {

    /** "Jl." dibaca "je el" oleh TTS -> ganti jadi "jalan". */
    fun place(label: String): String = label
        .replace("Jl.", "jalan")
        .replace("Jend.", "Jenderal")
        .replace("Letjen", "Letnan Jenderal")
        .replace("Gg.", "gang")
        .replace("RS ", "rumah sakit ")
        .replace("SMA ", "S M A ")
        .replace(" & ", " dan ")

    /** Buang emoji agar TTS tidak membaca nama emoji. */
    fun stripEmoji(text: String): String =
        text.filterNot { it.isSurrogate() || it.category == CharCategory.OTHER_SYMBOL || it == '️' || it == '‍' }
            .replace(Regex("\\s+"), " ").trim()

    fun offerAnnouncement(order: Order): String = buildString {
        append("Order ${order.service.spoken} baru. ")
        append("Jemput di ${place(order.pickup.name)}, ${spokenKm(order.pickupDistanceKm)} dari kamu. ")
        append("Tujuan ${place(order.dropoff.name)}, ${spokenKm(order.tripDistanceKm)}. ")
        append("Pendapatan ${spokenRupiah(order.driverEarning)}, ${order.payment.spoken}. ")
        append("Ucapkan terima, atau tolak.")
    }

    fun accepted(order: Order, source: String?): String {
        val prefix = if (source != null) "$source: order diterima otomatis. " else "Order diterima. "
        return prefix + "Menuju ${place(order.pickup.name)}, ${spokenKm(order.pickupDistanceKm)}."
    }

    fun tripStatus(trip: ActiveTrip, remainingKm: Double): String {
        val order = trip.order
        val target = if (trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.AT_PICKUP) order.pickup else order.dropoff
        val distance = if (trip.stage == TripStage.TO_PICKUP || trip.stage == TripStage.TO_DROPOFF) ", sisa ${spokenKm(remainingKm)}" else ""
        val pay = if (order.payment == PaymentMethod.CASH) " Pembayaran tunai ${spokenRupiah(order.fare)}." else " Pembayaran non tunai."
        return "${trip.stage.title(order.service)}: ${place(target.name)}$distance. Customer ${order.customer.name}.$pay"
    }

    fun help(hasOffer: Boolean, hasTrip: Boolean, onHeatmap: Boolean = false): String = when {
        hasOffer -> "Ucapkan terima untuk ambil order, tolak untuk melewati, atau ulangi untuk dengar detail order."
        hasTrip -> "Kamu bisa bilang: sudah sampai, mulai perjalanan, selesai, chat lalu isi pesannya, " +
            "telepon penumpang, atau lapor macet di jalan tertentu."
        onHeatmap -> "Di halaman area ramai, kamu bisa bilang: detail nomor satu, berikutnya, ulangi, " +
            "atau ada acara apa."
        else -> "Kamu bisa bilang: online, offline, area ramai, ada acara apa, pendapatan hari ini, " +
            "lapor macet di jalan Sudirman, atau nyalakan autobid. Saat ada order, cukup ucapkan terima atau tolak."
    }

    // ------------------------------------------------------------ area ramai (heat map)

    /** Berapa area yang dibacakan sekali jalan; sisanya lewat "berikutnya". */
    const val HOTSPOTS_PER_READ = 3

    /**
     * Daftar singkat area ramai mulai dari indeks [from], mis.
     * "Ada 8 area ramai dalam 3 jam ke depan. Yang terdekat: Nomor 1, Stasiun Dukuh Atas, ...".
     */
    fun hotspotList(spots: List<Hotspot>, from: Int = 0, count: Int = HOTSPOTS_PER_READ): String {
        if (spots.isEmpty()) return "Belum ada area ramai di sekitarmu dalam 3 jam ke depan."
        if (from >= spots.size) return "Itu semua area ramai di sekitarmu."
        val end = minOf(spots.size, from + count)
        return buildString {
            if (from == 0) append("Ada ${spots.size} area ramai di sekitarmu. Yang terdekat: ")
            else append("Berikutnya: ")
            for (i in from until end) append(hotspotBrief(spots[i], i + 1)).append(' ')
            append(
                if (end < spots.size) "Ucapkan berikutnya untuk area lain, atau detail nomor ${from + 1} untuk info lengkap."
                else "Itu semua. Ucapkan detail dan nomornya untuk info lengkap.",
            )
        }
    }

    /** "Nomor 1, Stasiun Dukuh Atas, 1,2 kilometer. Sekarang sampai jam setengah 8 malam, sekitar 400 orang, 18 driver, jam pulang kantor." */
    fun hotspotBrief(spot: Hotspot, number: Int): String =
        "Nomor $number, ${place(spot.name)}, ${spokenKm(spot.distanceKm)}. " +
            "${hotspotWhen(spot).capitalizeFirst()}, sekitar ${spokenNumber(spot.people)} orang, ${spot.drivers} driver, " +
            "${hotspotWhy(spot)}."

    /** Semua info satu area, untuk "detail nomor 2" atau tombol speaker di kartu. */
    fun hotspotDetail(spot: Hotspot, number: Int): String = buildString {
        append("Nomor $number, ${place(spot.name)}, dekat ${place(spot.area)}, ${spokenKm(spot.distanceKm)}, sekitar ${spot.etaMinutes} menit dari kamu. ")
        spot.event?.let { e ->
            append("Ada ${place(e.name)} ${spokenTimeRange(e.startMinute, e.endMinute)}, sekitar ${spokenNumber(e.attendees)} pengunjung. ")
            append("Ramai cari ojek ${hotspotWhen(spot)}. ")
        } ?: append("${spot.level.spoken.capitalizeFirst()} karena ${spot.reason.spoken}, ${hotspotWhen(spot)}. ")
        append("Perkiraan ${spokenNumber(spot.people)} calon penumpang, ${spot.drivers} driver di sekitar sana, ")
        append("sekitar ${spot.peoplePerDriver} orang per driver. ")
        append(
            when (spot.level) {
                HotspotLevel.SANGAT_RAMAI -> "Peluang order tinggi, driver masih sedikit."
                HotspotLevel.RAMAI -> "Peluang order bagus."
                HotspotLevel.LUMAYAN -> "Lumayan ramai, tapi driver di sana sudah cukup banyak."
            },
        )
    }

    /** Acara di sekitar (konser, bazar, nobar, resepsi) dan kapan pengunjungnya butuh ojek. */
    fun hotspotEvents(spots: List<Hotspot>): String {
        val events = spots.filter { it.event != null }
        if (events.isEmpty()) {
            val busiest = spots.maxByOrNull { it.peoplePerDriver }
                ?: return "Belum ada acara besar dalam 3 jam ke depan."
            return "Belum ada acara besar dalam 3 jam ke depan. Area paling ramai: ${place(busiest.name)}, ${hotspotWhen(busiest)}."
        }
        return buildString {
            append(if (events.size == 1) "Ada 1 acara di sekitarmu. " else "Ada ${events.size} acara di sekitarmu. ")
            events.forEach { spot ->
                val e = spot.event!!
                append("${place(e.name)} di ${place(spot.name)}, ${spokenKm(spot.distanceKm)}, ${spokenTimeRange(e.startMinute, e.endMinute)}, ")
                append("sekitar ${spokenNumber(e.attendees)} pengunjung. ")
                append("Ramai cari ojek ${hotspotWhen(spot)}, sekitar ${spokenNumber(spot.people)} orang, ${spot.drivers} driver. ")
            }
            append("Ucapkan area ramai untuk daftar lengkap.")
        }
    }

    /** "sekarang sampai jam 7 malam" atau "jam setengah 10 sampai jam 11 malam, 40 menit lagi". */
    fun hotspotWhen(spot: Hotspot): String =
        if (spot.ongoing) "sekarang sampai ${spokenClock(spot.endMinute)}"
        else "${spokenTimeRange(spot.startMinute, spot.endMinute)}, ${spokenDuration(spot.startsInMinutes)} lagi"

    private fun hotspotWhy(spot: Hotspot): String =
        spot.event?.let { "karena ada ${place(it.name)}" } ?: "karena ${spot.reason.spoken}"
}
