package id.ojolmitra.driver.voice

import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficType

/** Hasil interpretasi ucapan driver. */
sealed interface VoiceCommand {
    data object AcceptOrder : VoiceCommand
    data object RejectOrder : VoiceCommand
    data object RepeatOrder : VoiceCommand

    /** Kirim chat ke customer, mis. "chat saya sudah di depan". */
    data class SendChat(val message: String) : VoiceCommand
    /** "chat" tanpa isi -> asisten bertanya isi pesannya. */
    data object AskChatMessage : VoiceCommand
    data object OpenChat : VoiceCommand
    data object ReadLastMessage : VoiceCommand
    data object CallCustomer : VoiceCommand

    /** Lapor kondisi lalu lintas, mis. "lapor macet parah di jalan sudirman". */
    data class ReportTraffic(
        val type: TrafficType,
        val severity: Severity,
        /** Lokasi yang diucapkan (sudah dirapikan) atau null = lokasi driver saat ini. */
        val location: String?,
    ) : VoiceCommand
    data object OpenTrafficReports : VoiceCommand
    /** "lapor" tanpa jenis kejadian -> asisten bertanya mau lapor apa. */
    data object AskTrafficReport : VoiceCommand

    data object Arrived : VoiceCommand
    data object StartTrip : VoiceCommand
    data object CompleteTrip : VoiceCommand
    data object NextStep : VoiceCommand
    data object OpenNavigation : VoiceCommand

    data object GoOnline : VoiceCommand
    data object GoOffline : VoiceCommand
    data class SetAutobid(val enabled: Boolean) : VoiceCommand
    data object Earnings : VoiceCommand

    data object Help : VoiceCommand
    data object Cancel : VoiceCommand
    data object Thanks : VoiceCommand
    data class Unknown(val text: String) : VoiceCommand
}

/** Konteks layar saat perintah diucapkan, untuk memecahkan ambiguitas ("oke", "ya"). */
data class ParseContext(
    val hasOffer: Boolean = false,
    val hasTrip: Boolean = false,
)
