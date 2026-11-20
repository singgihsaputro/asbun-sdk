package id.ojolmitra.driver.voice

import id.ojolmitra.driver.model.ActiveTrip
import id.ojolmitra.driver.model.Order
import id.ojolmitra.driver.model.PaymentMethod
import id.ojolmitra.driver.model.TripStage
import id.ojolmitra.driver.model.spokenKm
import id.ojolmitra.driver.model.spokenRupiah
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

    fun help(hasOffer: Boolean, hasTrip: Boolean): String = when {
        hasOffer -> "Ucapkan terima untuk ambil order, tolak untuk melewati, atau ulangi untuk dengar detail order."
        hasTrip -> "Kamu bisa bilang: sudah sampai, mulai perjalanan, selesai, chat lalu isi pesannya, " +
            "telepon penumpang, atau lapor macet di jalan tertentu."
        else -> "Kamu bisa bilang: online, offline, pendapatan hari ini, lapor macet di jalan Sudirman, " +
            "atau nyalakan autobid. Saat ada order, cukup ucapkan terima atau tolak."
    }
}
