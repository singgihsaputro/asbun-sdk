package id.ojolmitra.driver.model

import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.map.Place

enum class ServiceType(val label: String, val spoken: String) {
    RIDE("Ride", "Ride"),
    FOOD("Food", "Food"),
    SEND("Send", "Send"),
}

enum class PaymentMethod(val label: String, val spoken: String) {
    CASH("Tunai", "tunai"),
    WALLET("Dompet Digital", "non tunai"),
}

data class Customer(
    val name: String,
    val rating: Double,
) {
    val firstName: String get() = name.substringBefore(' ')
    val initials: String
        get() = name.split(' ').filter { it.isNotBlank() }.take(2).joinToString("") { it.first().uppercase() }
}

data class Order(
    val id: String,
    val service: ServiceType,
    val customer: Customer,
    val pickup: Place,
    val dropoff: Place,
    /** Rute dari posisi driver ke titik jemput. */
    val pickupRoute: List<MapPoint>,
    /** Rute dari titik jemput ke tujuan. */
    val tripRoute: List<MapPoint>,
    val pickupDistanceKm: Double,
    val tripDistanceKm: Double,
    /** Tarif yang dibayar customer. */
    val fare: Int,
    /** Pendapatan bersih driver (setelah potongan aplikasi), sudah termasuk bonus. */
    val driverEarning: Int,
    val bonus: Int,
    val payment: PaymentMethod,
    val note: String?,
    /** Item pesanan (Food) atau isi paket (Send). */
    val items: List<String> = emptyList(),
) {
    val estimatedTripMinutes: Int get() = etaMinutes(tripDistanceKm)
}

/** Estimasi waktu tempuh dunia nyata (rata-rata 22 km/jam di Jakarta). */
fun etaMinutes(distanceKm: Double): Int = maxOf(1, kotlin.math.ceil(distanceKm / 22.0 * 60.0).toInt())

data class OrderOffer(
    val order: Order,
    val secondsLeft: Int,
    val totalSeconds: Int,
)

enum class TripStage {
    TO_PICKUP,
    AT_PICKUP,
    TO_DROPOFF,
    AT_DROPOFF,
}

data class ActiveTrip(
    val order: Order,
    val stage: TripStage,
    /** True ketika simulasi pergerakan sudah sampai target stage saat ini. */
    val reachedTarget: Boolean = false,
)

/** Judul tahap perjalanan, disesuaikan dengan jenis layanan. */
fun TripStage.title(service: ServiceType): String = when (this) {
    TripStage.TO_PICKUP -> when (service) {
        ServiceType.RIDE -> "Menuju titik jemput"
        ServiceType.FOOD -> "Menuju resto"
        ServiceType.SEND -> "Menuju lokasi pengambilan"
    }
    TripStage.AT_PICKUP -> when (service) {
        ServiceType.RIDE -> "Tunggu penumpang"
        ServiceType.FOOD -> "Pesan & ambil makanan"
        ServiceType.SEND -> "Ambil paket"
    }
    TripStage.TO_DROPOFF -> when (service) {
        ServiceType.RIDE -> "Antar penumpang"
        ServiceType.FOOD -> "Antar makanan"
        ServiceType.SEND -> "Antar paket"
    }
    TripStage.AT_DROPOFF -> "Sampai di tujuan"
}

/** Label tombol geser untuk menyelesaikan tahap saat ini. */
fun TripStage.actionLabel(service: ServiceType, payment: PaymentMethod): String = when (this) {
    TripStage.TO_PICKUP -> when (service) {
        ServiceType.RIDE -> "Sampai di titik jemput"
        ServiceType.FOOD -> "Sampai di resto"
        ServiceType.SEND -> "Sampai di lokasi pengambilan"
    }
    TripStage.AT_PICKUP -> when (service) {
        ServiceType.RIDE -> "Mulai perjalanan"
        ServiceType.FOOD -> "Makanan sudah diambil"
        ServiceType.SEND -> "Paket sudah diambil"
    }
    TripStage.TO_DROPOFF -> "Sampai di tujuan"
    TripStage.AT_DROPOFF -> if (payment == PaymentMethod.CASH) "Tunai diterima, selesaikan" else "Selesaikan order"
}

/** Contoh ucapan suara untuk tahap saat ini. */
fun TripStage.voiceHint(): String = when (this) {
    TripStage.TO_PICKUP -> "sudah sampai"
    TripStage.AT_PICKUP -> "mulai perjalanan"
    TripStage.TO_DROPOFF -> "sudah sampai"
    TripStage.AT_DROPOFF -> "selesai"
}

data class ChatMessage(
    val id: Long,
    val fromDriver: Boolean,
    val text: String,
    val timeLabel: String,
    val viaVoice: Boolean = false,
    val isSystem: Boolean = false,
)

enum class TrafficType(val label: String, val emoji: String, val spoken: String) {
    MACET("Macet", "🚗", "macet"),
    KECELAKAAN("Kecelakaan", "💥", "kecelakaan"),
    BANJIR("Banjir", "🌊", "banjir"),
    DITUTUP("Jalan Ditutup", "⛔", "jalan ditutup"),
    RAZIA("Razia", "👮", "razia"),
    JALAN_RUSAK("Jalan Rusak", "🚧", "jalan rusak"),
    DEMO("Keramaian/Demo", "📢", "keramaian"),
}

enum class Severity(val label: String) {
    RINGAN("Ringan"),
    SEDANG("Sedang"),
    PARAH("Parah"),
}

data class TrafficReport(
    val id: Long,
    val type: TrafficType,
    val severity: Severity,
    val locationLabel: String,
    val position: MapPoint,
    val reporter: String,
    val isMine: Boolean,
    val createdAtMillis: Long,
    val confirmations: Int = 0,
    val viaVoice: Boolean = false,
)

data class CompletedTrip(
    val order: Order,
    val timeLabel: String,
    val earning: Int,
    val customerRating: Int,
)

/** Statistik performa & keuangan driver. */
data class Earnings(
    val today: Int = 0,
    val trips: Int = 0,
    val wallet: Int = 152_500,
    val cashCollected: Int = 0,
    val offers: Int = 0,
    val accepted: Int = 0,
    val rejected: Int = 0,
    val missed: Int = 0,
    val completed: Int = 0,
    val ratingSum: Int = 0,
    val ratingCount: Int = 0,
) {
    /** Persentase penerimaan; diawali riwayat 38/40 agar terlihat realistis. */
    val acceptanceRate: Int get() = ((BASE_ACCEPTED + accepted) * 100) / (BASE_OFFERS + offers)
    val completionRate: Int get() = if (accepted == 0) 100 else minOf(100, (BASE_ACCEPTED + completed) * 100 / (BASE_ACCEPTED + accepted))
    val rating: Double get() = (BASE_RATING_SUM + ratingSum).toDouble() / (BASE_RATING_COUNT + ratingCount)

    companion object {
        private const val BASE_OFFERS = 40
        private const val BASE_ACCEPTED = 38
        private const val BASE_RATING_SUM = 4_905
        private const val BASE_RATING_COUNT = 1_000
    }
}

data class DriverProfile(
    val name: String = "Budi Santoso",
    val vehicle: String = "Honda Beat",
    val plate: String = "B 4821 KJT",
) {
    val firstName: String get() = name.substringBefore(' ')
    val initials: String get() = name.split(' ').take(2).joinToString("") { it.first().uppercase() }
}
