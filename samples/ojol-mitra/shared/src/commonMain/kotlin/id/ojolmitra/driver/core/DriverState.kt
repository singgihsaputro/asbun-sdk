package id.ojolmitra.driver.core

import id.ojolmitra.driver.map.MapPoint
import id.ojolmitra.driver.model.ActiveTrip
import id.ojolmitra.driver.model.ChatMessage
import id.ojolmitra.driver.model.CompletedTrip
import id.ojolmitra.driver.model.DriverProfile
import id.ojolmitra.driver.model.Earnings
import id.ojolmitra.driver.model.OrderOffer
import id.ojolmitra.driver.model.TrafficReport

enum class HomeTab(val label: String) {
    BERANDA("Beranda"),
    PENDAPATAN("Pendapatan"),
    LALU_LINTAS("Lalu Lintas"),
    SUARA("Asisten Suara"),
}

data class UiMessage(val id: Long, val text: String)

/** Posisi driver dipisah dari state utama karena berubah ~25x per detik (hanya peta yang perlu redraw). */
data class DriverLocation(
    val position: MapPoint,
    val heading: Float = -90f,
    /** Sisa rute navigasi (kosong saat tidak ada perjalanan). */
    val remainingRoute: List<MapPoint> = emptyList(),
    val remainingKm: Double = 0.0,
)

data class DriverUiState(
    val profile: DriverProfile = DriverProfile(),
    val isOnline: Boolean = false,
    val autobid: Boolean = false,
    val handsFree: Boolean = true,
    val readMessagesAloud: Boolean = true,
    val tab: HomeTab = HomeTab.BERANDA,
    val showChat: Boolean = false,
    val offer: OrderOffer? = null,
    val trip: ActiveTrip? = null,
    val chat: List<ChatMessage> = emptyList(),
    val unreadChat: Int = 0,
    val reports: List<TrafficReport> = emptyList(),
    val earnings: Earnings = Earnings(),
    val completedTrips: List<CompletedTrip> = emptyList(),
    /** Ringkasan order yang baru selesai (ditampilkan sebagai dialog). */
    val lastCompleted: CompletedTrip? = null,
    val snackbar: UiMessage? = null,
)

/** Parameter waktu simulasi (dibuat cepat supaya demo enak). */
data class SimTiming(
    val offerDelayMinMs: Long = 4_000,
    val offerDelayMaxMs: Long = 9_000,
    val offerSeconds: Int = 25,
    val tickMs: Long = 40,
    val tripSpeedUnitsPerSec: Float = 40f,
    /** Satu leg perjalanan maksimal ± sekian detik. */
    val maxLegSeconds: Float = 18f,
    val roamSpeedUnitsPerSec: Float = 9f,
    val customerReplyMinMs: Long = 2_500,
    val customerReplyMaxMs: Long = 4_500,
    val communityReportEveryMs: Long = 45_000,
    val autobidDelayMs: Long = 1_500,
)
