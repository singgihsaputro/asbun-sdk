package id.ojolmitra.driver

import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.voice.ParseContext
import id.ojolmitra.driver.voice.VoiceCommand
import id.ojolmitra.driver.voice.VoiceCommandParser
import kotlin.test.Test
import kotlin.test.assertEquals

class VoiceCommandParserTest {
    private val offer = ParseContext(hasOffer = true)
    private val trip = ParseContext(hasTrip = true)
    private val idle = ParseContext()

    private fun parse(text: String, ctx: ParseContext = idle) = VoiceCommandParser.parse(text, ctx)

    // ------------------------------------------------------------ terima / tolak order

    @Test
    fun acceptOrderPhrases() {
        listOf("Terima", "terima order", "oke ambil ordernya", "gas", "gaskeun", "sikat bang", "halo mitra, terima order", "Terima ya pak")
            .forEach { assertEquals(VoiceCommand.AcceptOrder, parse(it, offer), it) }
    }

    @Test
    fun weakAcceptOnlyWhenOfferExists() {
        assertEquals(VoiceCommand.AcceptOrder, parse("oke", offer))
        assertEquals(VoiceCommand.AcceptOrder, parse("ya", offer))
        assertEquals(VoiceCommand.AcceptOrder, parse("siap", offer))
        assertEquals(VoiceCommand.Unknown("ya"), parse("ya", idle))
    }

    @Test
    fun rejectOrderPhrases() {
        listOf("tolak", "tolak aja", "skip", "lewati", "nggak dulu", "gak", "jangan ambil", "tidak mau", "jangan diambil")
            .forEach { assertEquals(VoiceCommand.RejectOrder, parse(it, offer), it) }
    }

    @Test
    fun thankYouIsNotAccept() {
        assertEquals(VoiceCommand.Thanks, parse("terima kasih", offer))
        assertEquals(VoiceCommand.Thanks, parse("makasih ya", idle))
    }

    // ------------------------------------------------------------ chat

    @Test
    fun chatToCustomer() {
        assertEquals(VoiceCommand.SendChat("Saya sudah di depan ya pak"), parse("chat saya sudah di depan ya pak", trip))
        assertEquals(
            VoiceCommand.SendChat("Saya terjebak macet"),
            parse("kirim pesan ke penumpang bahwa saya terjebak macet", trip),
        )
        assertEquals(VoiceCommand.SendChat("Tunggu 5 menit"), parse("bilang ke customer tunggu 5 menit", trip))
        assertEquals(VoiceCommand.SendChat("Saya otw"), parse("tolong chat penumpang saya otw", trip))
        assertEquals(VoiceCommand.SendChat("Oke siap"), parse("balas oke siap", trip))
        assertEquals(VoiceCommand.SendChat("Posisi di mana kak?"), parse("chat customer posisi di mana kak?", trip))
    }

    @Test
    fun chatWithoutMessageAsksForIt() {
        assertEquals(VoiceCommand.AskChatMessage, parse("chat", trip))
        assertEquals(VoiceCommand.AskChatMessage, parse("kirim pesan ke penumpang", trip))
    }

    @Test
    fun readAndOpenChat() {
        assertEquals(VoiceCommand.ReadLastMessage, parse("baca pesan", trip))
        assertEquals(VoiceCommand.ReadLastMessage, parse("pesan terakhir apa", trip))
        assertEquals(VoiceCommand.OpenChat, parse("buka chat", trip))
        assertEquals(VoiceCommand.CallCustomer, parse("telpon penumpang", trip))
    }

    // ------------------------------------------------------------ lapor lalu lintas

    @Test
    fun reportTrafficWithLocationAndSeverity() {
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.MACET, Severity.PARAH, "Jl. Sudirman"),
            parse("lapor macet parah di jalan sudirman"),
        )
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.BANJIR, Severity.SEDANG, "Kemang Raya"),
            parse("laporkan banjir di Kemang Raya"),
        )
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.DITUTUP, Severity.SEDANG, "Dekat Stasiun Dukuh Atas"),
            parse("jalan ditutup dekat stasiun dukuh atas", trip),
        )
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.RAZIA, Severity.SEDANG, "Gatot Subroto"),
            parse("info razia polisi di gatot subroto"),
        )
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.MACET, Severity.PARAH, "Sudirman"),
            parse("macet di sudirman parah banget"),
        )
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.MACET, Severity.SEDANG, "Jl. Sudirman arah Blok M"),
            parse("lapor macet di jalan sudirman arah blok m"),
        )
    }

    @Test
    fun reportTrafficAtCurrentLocation() {
        assertEquals(VoiceCommand.ReportTraffic(TrafficType.KECELAKAAN, Severity.SEDANG, null), parse("ada kecelakaan di depan"))
        assertEquals(VoiceCommand.ReportTraffic(TrafficType.MACET, Severity.SEDANG, null), parse("lapor macet", offer))
        assertEquals(VoiceCommand.ReportTraffic(TrafficType.JALAN_RUSAK, Severity.SEDANG, null), parse("lapor jalan rusak di sini"))
    }

    @Test
    fun cannotPassIsRoadClosedNotReject() {
        assertEquals(
            VoiceCommand.ReportTraffic(TrafficType.DITUTUP, Severity.SEDANG, "Jl. Casablanca"),
            parse("nggak bisa lewat di jalan casablanca", offer),
        )
    }

    @Test
    fun reportWithoutTypeAsks() {
        assertEquals(VoiceCommand.AskTrafficReport, parse("lapor"))
        assertEquals(VoiceCommand.OpenTrafficReports, parse("lihat laporan"))
    }

    // ------------------------------------------------------------ perjalanan

    @Test
    fun tripProgress() {
        assertEquals(VoiceCommand.Arrived, parse("sudah sampai", trip))
        assertEquals(VoiceCommand.Arrived, parse("udah sampe pak", trip))
        assertEquals(VoiceCommand.StartTrip, parse("penumpang sudah naik", trip))
        assertEquals(VoiceCommand.StartTrip, parse("makanan sudah diambil", trip))
        assertEquals(VoiceCommand.StartTrip, parse("mulai perjalanan", trip))
        assertEquals(VoiceCommand.CompleteTrip, parse("selesaikan order", trip))
        assertEquals(VoiceCommand.NextStep, parse("lanjut", trip))
        assertEquals(VoiceCommand.OpenNavigation, parse("buka navigasi", trip))
    }

    @Test
    fun notYetArrivedIsNotArrived() {
        assertEquals(VoiceCommand.Unknown("5 menit lagi sampai"), parse("5 menit lagi sampai", trip))
    }

    // ------------------------------------------------------------ lainnya

    @Test
    fun onlineOfflineAndSettings() {
        assertEquals(VoiceCommand.GoOnline, parse("mulai narik"))
        assertEquals(VoiceCommand.GoOnline, parse("online sekarang"))
        assertEquals(VoiceCommand.GoOffline, parse("selesai narik"))
        assertEquals(VoiceCommand.GoOffline, parse("istirahat dulu"))
        assertEquals(VoiceCommand.SetAutobid(false), parse("matikan autobid"))
        assertEquals(VoiceCommand.SetAutobid(true), parse("nyalakan autobid"))
        assertEquals(VoiceCommand.Earnings, parse("berapa pendapatan hari ini"))
        assertEquals(VoiceCommand.Help, parse("bantuan"))
        assertEquals(VoiceCommand.Cancel, parse("batal"))
        assertEquals(VoiceCommand.RepeatOrder, parse("ulangi", offer))
    }
}
