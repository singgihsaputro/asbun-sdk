package id.ojolmitra.driver

import id.asbun.voice.KeywordMatch
import id.asbun.voice.KeywordMatcher
import id.asbun.voice.VoiceContext
import id.ojolmitra.driver.voice.OjolMitraVoice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The prototype's parser cases, now answered by the SDK's keyword matcher reading
 * [OjolMitraVoice.manifest]. Values come back as spoken; the app tidies them afterwards.
 */
class OjolMitraManifestTest {
    private val matcher = KeywordMatcher(OjolMitraVoice.manifest, OjolMitraVoice.LOCALE)
    private val minScore = OjolMitraVoice.config().resolution.keywordMinScore

    private fun context(screen: String) = VoiceContext(
        screen,
        mapOf("dictating" to (screen == OjolMitraVoice.SCREEN_DICTATION), "orderId" to "OM-1", "tripId" to "OM-1", "customer" to "Budi"),
    )

    private val home = context(OjolMitraVoice.SCREEN_HOME)
    private val offer = context(OjolMitraVoice.SCREEN_OFFER)
    private val trip = context(OjolMitraVoice.SCREEN_TRIP)
    private val dictation = context(OjolMitraVoice.SCREEN_DICTATION)
    private val heatmap = context(OjolMitraVoice.SCREEN_HEATMAP)

    /** The command the SDK would run (score above the threshold), or null. */
    private fun match(text: String, ctx: VoiceContext = home): KeywordMatch? =
        matcher.match(text, ctx)?.takeIf { it.score >= minScore }

    private fun command(text: String, ctx: VoiceContext = home): String? = match(text, ctx)?.command?.id

    private fun assertCommand(expected: String, text: String, ctx: VoiceContext = home) = assertEquals(expected, command(text, ctx), text)

    // ------------------------------------------------------------ accept / reject order

    @Test
    fun acceptOrderPhrases() {
        listOf("Terima", "terima order", "oke ambil ordernya", "gas", "gaskeun", "sikat bang", "halo mitra, terima order", "Terima ya pak", "oke gas terima")
            .forEach { assertCommand("order.accept", it, offer) }
    }

    @Test
    fun weakAcceptOnlyWhenOfferIsShown() {
        listOf("oke", "ya", "siap").forEach { assertCommand("order.accept", it, offer) }
        assertNull(command("ya", home))
        assertNull(command("terima", trip))
        assertNull(command("apa ya tadi", offer), "a filler inside an unclear sentence must not accept")
    }

    @Test
    fun rejectOrderPhrases() {
        listOf("tolak", "tolak aja", "skip", "lewati", "nggak dulu", "gak", "jangan ambil", "tidak mau", "jangan diambil")
            .forEach { assertCommand("order.reject", it, offer) }
    }

    @Test
    fun thankYouIsNotAccept() {
        assertCommand("app.thanks", "terima kasih", offer)
        assertCommand("app.thanks", "makasih ya")
    }

    // ------------------------------------------------------------ chat

    @Test
    fun chatToCustomerKeepsTheSpokenWords() {
        mapOf(
            "chat saya sudah di depan ya pak" to "saya sudah di depan ya pak",
            "kirim pesan ke penumpang bahwa saya terjebak macet" to "saya terjebak macet",
            "bilang ke customer tunggu 5 menit" to "tunggu 5 menit",
            "tolong chat penumpang saya otw" to "saya otw",
            "balas oke siap" to "oke siap",
            "chat customer posisi di mana kak?" to "posisi di mana kak?",
        ).forEach { (said, message) ->
            val m = assertNotNull(match(said, trip), said)
            assertEquals("chat.send", m.command.id, said)
            assertEquals(message, m.params["message"], said)
        }
    }

    @Test
    fun chatWithoutMessageIsMatchedSoTheSdkAsksForIt() {
        listOf("chat", "kirim pesan ke penumpang").forEach {
            val m = assertNotNull(match(it, trip), it)
            assertEquals("chat.send", m.command.id)
            assertNull(m.params["message"])
        }
        assertEquals("Mau kirim pesan apa ke {context.customer}?", OjolMitraVoice.manifest.command("chat.send")!!.param("message")!!.ask!!.resolve("id-ID", "id-ID"))
    }

    @Test
    fun readOpenAndCall() {
        assertCommand("chat.read", "baca pesan", trip)
        assertCommand("chat.read", "pesan terakhir apa", trip)
        assertCommand("chat.open", "buka chat", trip)
        assertCommand("customer.call", "telpon penumpang", trip)
        assertNull(command("buka chat", home), "chat commands need an active trip")
    }

    @Test
    fun dictationSendsEverythingExceptCancel() {
        val m = assertNotNull(match("terima kasih ya", dictation))
        assertEquals("chat.dictate", m.command.id)
        assertEquals("terima kasih ya", m.params["message"])
        assertCommand("chat.dictate", "lapor macet di depan", dictation)
        assertCommand("chat.dictate.cancel", "batal", dictation)
        assertCommand("chat.dictate.cancel", "gak jadi", dictation)
    }

    // ------------------------------------------------------------ traffic reports

    private fun assertReport(text: String, type: String, severity: String?, place: String?, ctx: VoiceContext = home) {
        val m = assertNotNull(match(text, ctx), text)
        assertEquals("traffic.report", m.command.id, text)
        assertEquals(type, m.params["type"], text)
        assertEquals(severity, m.params["severity"], text)
        assertEquals(place, OjolMitraVoice.spokenPlace(m.params["location"]), text)
    }

    @Test
    fun reportTrafficWithPlaceAndSeverity() {
        assertReport("lapor macet parah di jalan sudirman", "MACET", "PARAH", "Jl. Sudirman")
        assertReport("laporkan banjir di Kemang Raya", "BANJIR", null, "Kemang Raya")
        assertReport("jalan ditutup dekat stasiun dukuh atas", "DITUTUP", null, "Stasiun Dukuh Atas", trip)
        assertReport("info razia polisi di gatot subroto", "RAZIA", null, "Gatot Subroto")
        assertReport("macet di sudirman parah banget", "MACET", "PARAH", "Sudirman")
        assertReport("lapor macet di jalan sudirman arah blok m", "MACET", null, "Jl. Sudirman arah Blok M")
    }

    @Test
    fun reportTrafficHereUsesNoPlace() {
        assertReport("ada kecelakaan di depan", "KECELAKAAN", null, null)
        assertReport("lapor macet", "MACET", null, null, offer)
        assertReport("lapor jalan rusak di sini", "JALAN_RUSAK", null, null)
    }

    @Test
    fun cannotPassIsRoadClosedNotReject() {
        assertReport("nggak bisa lewat di jalan casablanca", "DITUTUP", null, "Jl. Casablanca", offer)
    }

    @Test
    fun reportWithoutTypeIsMatchedSoTheSdkAsks() {
        val m = assertNotNull(match("lapor"))
        assertEquals("traffic.report", m.command.id)
        assertNull(m.params["type"])
        assertCommand("traffic.open", "lihat laporan")
    }

    // ------------------------------------------------------------ area ramai (heat map)

    @Test
    fun busyAreaPhrasesWorkEverywhere() {
        listOf(
            "area ramai", "mana yang rame", "di mana yang ramai", "dimana yang lagi rame", "area ramai terdekat dong",
            "ngetem dimana enaknya", "heatmap", "bacakan area ramai", "tolong cari area ramai",
        ).forEach { assertCommand("hotspot.read", it) }
        assertCommand("hotspot.read", "area ramai", trip)
        assertCommand("hotspot.read", "area ramai", heatmap)
    }

    @Test
    fun eventPhrases() {
        listOf("ada acara apa", "acara apa saja", "ada event apa", "ada konser", "acara hari ini")
            .forEach { assertCommand("hotspot.events", it) }
    }

    @Test
    fun detailByNumberOnlyOnTheBusyAreaPage() {
        assertEquals("2", match("detail nomor 2", heatmap)?.params?.get("number"))
        assertEquals("3", match("nomor tiga", heatmap)?.params?.get("number"))
        assertEquals("1", match("yang pertama", heatmap)?.params?.get("number"))
        assertEquals("2", match("detail nomer dua dong", heatmap)?.params?.get("number"))
        val ask = assertNotNull(match("detail", heatmap))
        assertEquals("hotspot.detail", ask.command.id)
        assertNull(ask.params["number"], "no number: the SDK asks which one")
        assertNull(command("detail nomor 2", home))
    }

    @Test
    fun nextAndRepeatFollowTheScreen() {
        listOf("berikutnya", "lanjut", "yang lain", "masih ada lagi").forEach { assertCommand("hotspot.more", it, heatmap) }
        assertCommand("hotspot.repeat", "ulangi", heatmap)
        assertCommand("order.repeat", "ulangi", offer)
        assertCommand("trip.next", "lanjut", trip)
        assertCommand("order.accept", "lanjut", offer)
    }

    @Test
    fun crowdReportIsStillATrafficReport() {
        assertReport("lapor keramaian di gatot subroto", "DEMO", null, "Gatot Subroto")
        assertReport("ada demo di sudirman", "DEMO", null, "Sudirman", heatmap)
    }

    // ------------------------------------------------------------ trip

    @Test
    fun tripProgress() {
        assertCommand("trip.arrived", "sudah sampai", trip)
        assertCommand("trip.arrived", "udah sampe pak", trip)
        assertCommand("trip.start", "penumpang sudah naik", trip)
        assertCommand("trip.start", "makanan sudah diambil", trip)
        assertCommand("trip.start", "mulai perjalanan", trip)
        assertCommand("trip.complete", "selesaikan order", trip)
        assertCommand("trip.next", "lanjut", trip)
        assertCommand("trip.navigate", "buka navigasi", trip)
    }

    @Test
    fun notYetArrivedIsNotArrived() {
        assertNull(command("5 menit lagi sampai", trip))
        assertNull(command("belum sampai", trip))
    }

    // ------------------------------------------------------------ other

    @Test
    fun onlineOfflineAndSettings() {
        assertCommand("driver.online", "mulai narik")
        assertCommand("driver.online", "online sekarang")
        assertCommand("driver.offline", "selesai narik")
        assertCommand("driver.offline", "selesai narik", trip) // not "complete trip"
        assertCommand("driver.offline", "istirahat dulu")
        assertEquals("OFF", match("matikan autobid")?.params?.get("enabled"))
        assertEquals("ON", match("nyalakan autobid")?.params?.get("enabled"))
        assertCommand("autobid.set", "autobid")
        assertCommand("earnings.today", "berapa pendapatan hari ini")
        assertCommand("app.help", "bantuan")
        assertCommand("app.cancel", "batal")
        assertCommand("order.repeat", "ulangi", offer)
    }

    @Test
    fun everyHandlerAndDeeplinkUsesTheAppScheme() {
        OjolMitraVoice.manifest.commands.forEach { command ->
            val action = command.action
            if (action is id.asbun.voice.CommandAction.Deeplink) {
                assertEquals(OjolMitraVoice.SCHEME, action.url.substringBefore("://"), command.id)
            }
        }
    }
}
