package id.ojolmitra.driver.voice

import id.ojolmitra.driver.model.Severity
import id.ojolmitra.driver.model.TrafficType
import id.ojolmitra.driver.model.capitalizeFirst
import id.ojolmitra.driver.voice.VoiceCommand.AcceptOrder
import id.ojolmitra.driver.voice.VoiceCommand.RejectOrder

/**
 * Parser perintah suara berbasis aturan untuk Bahasa Indonesia sehari-hari
 * (termasuk slang ojol: "gas", "sikat", "udah sampe", "gak dulu").
 *
 * Murni Kotlin common -> perilakunya identik di Android & iOS dan mudah di-unit-test.
 */
object VoiceCommandParser {

    /** Satu kata: teks asli (untuk isi chat) dan bentuk kanoniknya (untuk pencocokan). */
    private data class Word(val original: String, val canon: String)

    fun parse(raw: String, context: ParseContext = ParseContext()): VoiceCommand {
        val words = stripLeading(tokenize(raw))
        if (words.isEmpty()) return VoiceCommand.Unknown(raw)

        parseChat(words)?.let { return it }

        val core = stripTrailing(words)
        val c = core.map { it.canon }
        val s = " " + c.joinToString(" ") + " "
        fun has(phrase: String) = s.contains(" $phrase ")
        fun hasAny(phrases: Collection<String>) = phrases.any { has(it) }

        if (hasAny(HELP)) return VoiceCommand.Help

        if (has("autobid") || has("auto bid") || has("otomatis")) {
            val off = hasAny(listOf("matikan", "nonaktif", "off", "tidak", "stop", "jangan"))
            return VoiceCommand.SetAutobid(enabled = !off)
        }

        val thanks = hasAny(THANKS)
        val decision = acceptOrReject(c, context, thanks)
        val traffic = detectTraffic(core)
        val hasReportTrigger = hasAny(REPORT_TRIGGERS)
        if (traffic != null && (hasReportTrigger || decision == null)) return traffic
        if (traffic == null && (has("lapor") || has("report"))) return VoiceCommand.AskTrafficReport

        if (hasAny(OFFLINE)) return VoiceCommand.GoOffline
        if (hasAny(ONLINE)) return VoiceCommand.GoOnline

        // Saat ada order masuk, "oke"/"ya"/"gas" paling mungkin berarti terima/tolak.
        if (context.hasOffer && decision != null) return decision
        tripCommand(::has, ::hasAny)?.let { return it }
        if (decision != null) return decision
        if (thanks) return VoiceCommand.Thanks

        return when {
            hasAny(CALL) -> VoiceCommand.CallCustomer
            hasAny(READ_MESSAGE) -> VoiceCommand.ReadLastMessage
            hasAny(OPEN_CHAT) -> VoiceCommand.OpenChat
            hasAny(NAVIGATION) -> VoiceCommand.OpenNavigation
            hasAny(REPEAT) -> VoiceCommand.RepeatOrder
            hasAny(EARNINGS) -> VoiceCommand.Earnings
            hasAny(OPEN_TRAFFIC) -> VoiceCommand.OpenTrafficReports
            hasAny(CANCEL) -> VoiceCommand.Cancel
            else -> VoiceCommand.Unknown(raw)
        }
    }

    // ---------------------------------------------------------------- tokenizing

    private fun tokenize(raw: String): List<Word> =
        raw.trim().split(Regex("\\s+")).mapNotNull { piece ->
            val basic = piece.lowercase().filter { it.isLetterOrDigit() }
            if (basic.isEmpty()) null else Word(piece, canonical(basic))
        }

    private fun canonical(token: String): String {
        ALIASES[token]?.let { return it }
        for (suffix in listOf("nya", "lah", "kah")) {
            if (token.length > suffix.length + 2 && token.endsWith(suffix)) {
                val root = token.dropLast(suffix.length)
                val mapped = ALIASES[root] ?: root
                if (mapped in KNOWN_ROOTS) return mapped
            }
        }
        return token
    }

    private fun stripLeading(words: List<Word>): List<Word> {
        var start = 0
        while (start < words.size - 1 && (words[start].canon in LEADING_FILLERS || words[start].canon == "oke")) start++
        return words.drop(start)
    }

    private fun stripTrailing(words: List<Word>): List<Word> {
        var end = words.size
        while (end > 1 && words[end - 1].canon in TRAILING_FILLERS) end--
        return words.take(end)
    }

    // ---------------------------------------------------------------- chat

    private fun parseChat(words: List<Word>): VoiceCommand? {
        val c = words.map { it.canon }
        val verb = CHAT_VERBS.firstOrNull { startsWith(c, 0, it) } ?: return null
        var k = verb.size
        CHAT_TARGETS.firstOrNull { startsWith(c, k, it) }?.let { k += it.size }
        if (k < c.size && c[k] in CHAT_CONNECTORS) k++

        val rest = words.drop(k)
        if (rest.isEmpty()) return VoiceCommand.AskChatMessage
        // "pesan terakhir", "pesan masuk" -> bukan kirim chat
        if (verb == listOf("pesan") && rest.first().canon in setOf("terakhir", "masuk", "baru", "dari")) return null
        val message = rest.joinToString(" ") { it.original }.trim().trimEnd(',', ' ')
        return VoiceCommand.SendChat(message.capitalizeFirst())
    }

    private fun startsWith(tokens: List<String>, at: Int, phrase: List<String>): Boolean =
        tokens.size >= at + phrase.size && phrase.indices.all { tokens[at + it] == phrase[it] }

    // ---------------------------------------------------------------- order

    private fun acceptOrReject(canon: List<String>, context: ParseContext, thanks: Boolean): VoiceCommand? {
        val tokens = if (thanks) canon.filterNot { it in THANKS_TOKENS } else canon
        if (tokens.any { it in REJECT }) return RejectOrder
        // Kata lemah ("ya", "oke", "mau") hanya dihitung bila ucapannya pendek / diawali kata itu,
        // supaya "apa ya tadi" tidak dianggap menerima order.
        val acceptIndex = tokens.indices.firstOrNull { i ->
            val t = tokens[i]
            t in STRONG_ACCEPT || (context.hasOffer && t in WEAK_ACCEPT && (i == 0 || tokens.size <= 2))
        } ?: -1
        if (acceptIndex >= 0) {
            val negated = tokens.take(acceptIndex).any { it in NEGATIONS }
            return if (negated) RejectOrder else AcceptOrder
        }
        if (context.hasOffer && tokens.isNotEmpty() && tokens.all { it in NEGATIONS || it in TRAILING_FILLERS }) {
            return RejectOrder
        }
        return null
    }

    // ---------------------------------------------------------------- trip

    private fun tripCommand(has: (String) -> Boolean, hasAny: (Collection<String>) -> Boolean): VoiceCommand? {
        val notYet = has("belum") || has("hampir") || has("lagi") || has("mau") || has("akan")
        return when {
            hasAny(COMPLETE) && !has("belum") -> VoiceCommand.CompleteTrip
            hasAny(START_TRIP) && !has("belum") -> VoiceCommand.StartTrip
            hasAny(ARRIVED) && !notYet -> VoiceCommand.Arrived
            hasAny(NEXT_STEP) -> VoiceCommand.NextStep
            else -> null
        }
    }

    // ---------------------------------------------------------------- traffic

    private fun detectTraffic(words: List<Word>): VoiceCommand.ReportTraffic? {
        val c = words.map { it.canon }
        val s = " " + c.joinToString(" ") + " "
        val type = TRAFFIC_KEYWORDS.firstOrNull { (_, keys) -> keys.any { s.contains(" $it ") } }?.first ?: return null

        val severity = when {
            SEVERE.any { s.contains(" $it ") } -> Severity.PARAH
            MILD.any { s.contains(" $it ") } -> Severity.RINGAN
            else -> Severity.SEDANG
        }
        return VoiceCommand.ReportTraffic(type, severity, extractLocation(words))
    }

    private fun extractLocation(words: List<Word>): String? {
        val c = words.map { it.canon }
        var start = c.indexOfFirst { it in LOCATION_PREPOSITIONS || it in LOCATION_NOUNS }
        if (start < 0) {
            // "macet jalan sudirman" (tanpa "di"), tapi bukan "jalan ditutup"/"jalan rusak"
            start = c.indices.firstOrNull { i ->
                c[i] == "jalan" && i + 1 < c.size && c[i + 1] !in setOf("ditutup", "tutup", "rusak", "berlubang", "sekarang")
            } ?: return null
        }
        var locWords = words.drop(start)
        // buang kata depan murni ("di", "daerah", ...) di awal
        while (locWords.size > 1 && locWords.first().canon in DROPPABLE_PREPOSITIONS) locWords = locWords.drop(1)
        // potong saat bertemu kata jenis kejadian / tingkat keparahan / pengisi
        val cut = locWords.indexOfFirst { w ->
            w.canon in STOP_WORDS_IN_LOCATION || TRAFFIC_SINGLE_TOKENS.contains(w.canon)
        }
        if (cut == 0) return null
        if (cut > 0) locWords = locWords.take(cut)
        if (locWords.isEmpty()) return null
        if (locWords.all { it.canon in HERE_WORDS }) return null
        return formatLocation(locWords.map { it.canon })
    }

    private fun formatLocation(tokens: List<String>): String =
        tokens.mapIndexed { i, t ->
            when {
                t == "jalan" -> "Jl."
                t == "gang" || t == "gg" -> "Gg."
                t.length == 1 -> t.uppercase()
                i > 0 && t in LOWERCASE_WORDS -> t
                else -> t.capitalizeFirst()
            }
        }.joinToString(" ").capitalizeFirst()

    // ---------------------------------------------------------------- vocab

    private val ALIASES = mapOf(
        "udah" to "sudah", "dah" to "sudah", "sdh" to "sudah", "uda" to "sudah", "udh" to "sudah",
        "sampe" to "sampai", "nyampe" to "sampai", "nyampai" to "sampai", "sampek" to "sampai",
        "gak" to "tidak", "ga" to "tidak", "nggak" to "tidak", "enggak" to "tidak", "ngga" to "tidak",
        "engga" to "tidak", "tdk" to "tidak", "ndak" to "tidak", "kagak" to "tidak", "gk" to "tidak", "nda" to "tidak",
        "nope" to "no",
        "ok" to "oke", "okay" to "oke", "okey" to "oke", "oce" to "oke", "okee" to "oke", "okeh" to "oke", "okai" to "oke",
        "iya" to "ya", "yaa" to "ya", "yap" to "ya", "yup" to "ya", "yoi" to "ya", "yes" to "ya", "yo" to "ya",
        "telpon" to "telepon", "telfon" to "telepon", "tlp" to "telepon", "nelpon" to "telepon",
        "telponin" to "telepon", "teleponin" to "telepon", "telepun" to "telepon",
        "jl" to "jalan", "jln" to "jalan",
        "tau" to "tahu",
        "kustomer" to "customer", "costumer" to "customer", "kastemer" to "customer", "custumer" to "customer",
        "pelangan" to "pelanggan", "penumpangnya" to "penumpang", "customernya" to "customer",
        "cet" to "chat", "chatting" to "chat", "ceting" to "chat", "chatin" to "chat", "chatkan" to "chat", "chet" to "chat",
        "bales" to "balas", "balesin" to "balas", "balasin" to "balas", "balaskan" to "balas",
        "bilangin" to "bilang", "kabarin" to "kabari", "kalo" to "kalau",
        "lewatin" to "skip", "lewati" to "skip", "lewatkan" to "skip", "pass" to "skip",
        "gass" to "gas", "gaspol" to "gas", "gaskan" to "gas", "gasken" to "gas", "gaskeun" to "gas", "gasss" to "gas",
        "orderan" to "order", "pesanannya" to "pesanan",
        "selesaikan" to "selesai", "selesein" to "selesai", "kelar" to "selesai", "rampung" to "selesai",
        "laporkan" to "lapor", "laporin" to "lapor", "melaporkan" to "lapor", "melapor" to "lapor",
        "terimain" to "terima", "terimakan" to "terima", "ambilin" to "ambil", "ambilkan" to "ambil",
        "tolakin" to "tolak", "tolakkan" to "tolak",
        "kemacetan" to "macet", "makasi" to "makasih", "trims" to "makasih", "tengkyu" to "makasih",
        "aktifkan" to "aktif", "nyalakan" to "aktif", "hidupkan" to "aktif", "aktipkan" to "aktif",
        "nonaktifkan" to "nonaktif", "navigasikan" to "navigasi", "batalin" to "batal", "batalkan" to "batal",
        "ulangin" to "ulangi", "lanjutkan" to "lanjut", "lanjutin" to "lanjut", "tabrakan" to "tabrakan",
        "berlobang" to "berlubang", "antri" to "antrean", "antrian" to "antrean",
    )

    private val LEADING_FILLERS = setOf(
        "halo", "hai", "hei", "hey", "mitra", "asisten", "tolong", "coba", "please", "eh", "em", "emm", "hmm", "ehm",
    )
    private val TRAILING_FILLERS = setOf(
        "dong", "deh", "aja", "saja", "ya", "yah", "sih", "nih", "pak", "bos", "bang", "kak", "mas", "mbak",
        "sekarang", "please", "yuk", "ayo", "dulu", "segera", "cepat", "ah", "aja deh",
    )

    private val THANKS = listOf("terima kasih", "makasih", "thanks", "thank you", "tengkyu")
    private val THANKS_TOKENS = setOf("terima", "kasih", "makasih", "thanks", "thank", "you")

    private val REJECT = setOf("tolak", "skip", "reject", "decline")
    private val STRONG_ACCEPT = setOf("terima", "ambil", "accept", "gas", "sikat", "angkut", "hajar", "embat", "sabet")
    private val WEAK_ACCEPT = setOf(
        "oke", "siap", "ya", "boleh", "mau", "setuju", "sip", "sipp", "mantap", "lanjut", "yuk", "ayo", "diambil", "diterima",
    )
    private val NEGATIONS = setOf("tidak", "jangan", "no", "bukan", "ogah", "emoh", "males", "malas")

    private val HELP = listOf(
        "bantuan", "help", "perintah apa", "bisa apa", "daftar perintah", "contoh perintah", "cara pakai", "panduan",
    )

    private val ONLINE = listOf(
        "online", "aktif", "mulai narik", "narik", "mulai kerja", "siap narik", "on bid", "onbid", "mulai bid",
    )
    private val OFFLINE = listOf(
        "offline", "nonaktif", "matikan", "istirahat", "berhenti narik", "selesai narik", "stop narik", "off bid",
        "offbid", "udahan", "pulang dulu", "tutup aplikasi",
    )

    private val COMPLETE = listOf("selesai", "finish", "complete", "beres", "tuntas", "done", "order selesai")
    private val START_TRIP = listOf(
        "mulai perjalanan", "mulai antar", "mulai", "berangkat", "sudah naik", "naik", "sudah jemput", "jemput",
        "start", "sudah diambil", "diambil", "sudah ambil", "pickup", "pick up", "antar sekarang", "jalan sekarang",
    )
    private val ARRIVED = listOf(
        "sudah sampai", "sampai", "tiba", "sudah tiba", "arrived", "sudah di lokasi", "sudah di titik", "di lokasi",
    )
    private val NEXT_STEP = listOf("lanjut", "next", "berikutnya", "geser", "konfirmasi")

    private val CALL = listOf("telepon", "call", "hubungi")
    private val READ_MESSAGE = listOf(
        "baca pesan", "baca chat", "bacakan", "pesan terakhir", "apa katanya", "ada pesan", "pesan masuk", "chat terakhir",
    )
    private val OPEN_CHAT = listOf("buka chat", "lihat chat", "buka pesan", "lihat pesan", "inbox", "ruang chat")
    private val NAVIGATION = listOf("navigasi", "arahkan", "rute", "maps", "peta", "petunjuk arah", "navigate", "waze")
    private val REPEAT = listOf(
        "ulangi", "ulang", "detail", "info order", "detail order", "baca order", "order apa", "repeat", "rincian",
    )
    private val EARNINGS = listOf(
        "pendapatan", "penghasilan", "saldo", "dapat berapa", "dapet berapa", "earning", "duit", "gaji", "cuan", "income",
    )
    private val OPEN_TRAFFIC = listOf("laporan", "lalu lintas", "info jalan", "kondisi jalan", "lalin")
    private val CANCEL = listOf("batal", "cancel", "tidak jadi", "stop", "diam", "cukup", "nevermind")

    private val CHAT_VERBS: List<List<String>> = listOf(
        listOf("kirim", "pesan"), listOf("kirim", "chat"), listOf("kirim", "sms"), listOf("kirim", "wa"),
        listOf("kirimi", "pesan"), listOf("send", "message"), listOf("kasih", "tahu"), listOf("beri", "tahu"),
        listOf("chat"), listOf("pesan"), listOf("balas"), listOf("bilang"), listOf("sampaikan"), listOf("beritahu"),
        listOf("kabari"), listOf("tulis"), listOf("ketik"), listOf("tell"), listOf("message"), listOf("sms"),
    ).sortedByDescending { it.size }

    private val CHAT_TARGETS: List<List<String>> = listOf(
        "penumpang", "customer", "pelanggan", "konsumen", "pemesan", "user", "dia", "bapak", "ibu", "kakak",
    ).flatMap { listOf(listOf("ke", it), listOf("kepada", it), listOf(it)) }.sortedByDescending { it.size }

    private val CHAT_CONNECTORS = setOf("bahwa", "kalau", "isi", "isinya", "yaitu", "yakni", "pesannya")

    private val REPORT_TRIGGERS = listOf("lapor", "report", "info", "ada", "laporan", "awas", "hati hati")

    private val TRAFFIC_KEYWORDS: List<Pair<TrafficType, List<String>>> = listOf(
        TrafficType.KECELAKAAN to listOf(
            "kecelakaan", "tabrakan", "laka", "accident", "nabrak", "ketabrak", "tertabrak", "senggolan", "kecelakaannya",
        ),
        TrafficType.BANJIR to listOf("banjir", "genangan", "tergenang", "flood"),
        TrafficType.DITUTUP to listOf(
            "jalan ditutup", "ditutup", "penutupan", "jalan tutup", "tidak bisa lewat", "tidak bisa dilewati",
            "road closed", "dialihkan", "pengalihan",
        ),
        TrafficType.RAZIA to listOf("razia", "polisi", "operasi zebra", "operasi", "tilang", "cegatan", "police", "polantas"),
        TrafficType.JALAN_RUSAK to listOf(
            "jalan rusak", "rusak", "berlubang", "lubang", "perbaikan jalan", "perbaikan", "galian", "pothole", "longsor",
        ),
        TrafficType.DEMO to listOf("demo", "demonstrasi", "unjuk rasa", "keramaian", "massa", "pawai", "konvoi"),
        TrafficType.MACET to listOf(
            "macet", "padat", "merayap", "padat merayap", "antrean", "tersendat", "traffic", "stuck", "crowded",
        ),
    )
    private val TRAFFIC_SINGLE_TOKENS: Set<String> =
        TRAFFIC_KEYWORDS.flatMap { it.second }.filter { ' ' !in it }.toSet() - setOf("lubang", "operasi")

    private val SEVERE = listOf("parah", "total", "banget", "sekali", "berat", "sangat", "mampet", "gila", "panjang")
    private val MILD = listOf("ringan", "sedikit", "dikit", "lumayan", "agak", "lancar")

    private val LOCATION_PREPOSITIONS = setOf(
        "di", "dekat", "depan", "sekitar", "arah", "menuju", "sebelum", "setelah", "sesudah", "daerah", "kawasan",
        "near", "at", "dari",
    )
    private val LOCATION_NOUNS = setOf("perempatan", "pertigaan", "simpang", "bundaran", "flyover", "tol", "lampu", "gerbang")
    private val DROPPABLE_PREPOSITIONS = setOf("di", "daerah", "kawasan", "at", "sekitar", "dari")
    private val STOP_WORDS_IN_LOCATION = setOf(
        "parah", "total", "banget", "sekali", "berat", "sangat", "ringan", "sedikit", "dikit", "lumayan", "agak",
        "ya", "nih", "sekarang", "pak", "bos", "dong", "deh", "sih", "tolong", "hati", "awas",
    )
    private val HERE_WORDS = setOf(
        "sini", "situ", "sana", "depan", "sekitar", "lokasi", "saya", "ini", "posisi", "sekitaran", "dekat", "aku", "sekarang",
    )
    private val LOWERCASE_WORDS = setOf(
        "arah", "dekat", "depan", "di", "ke", "dan", "sebelum", "setelah", "sesudah", "menuju", "dari", "yang", "samping",
    )

    private val KNOWN_ROOTS: Set<String> = buildSet {
        addAll(ALIASES.values)
        addAll(STRONG_ACCEPT); addAll(REJECT); addAll(WEAK_ACCEPT)
        (COMPLETE + START_TRIP + ARRIVED + CALL + EARNINGS + ONLINE + OFFLINE).forEach { addAll(it.split(' ')) }
        TRAFFIC_KEYWORDS.forEach { (_, keys) -> keys.forEach { addAll(it.split(' ')) } }
        addAll(listOf("lapor", "order", "pesan", "chat", "penumpang", "customer", "jalan"))
    }
}
