package id.ojolmitra.driver.voice

import id.asbun.voice.CommandManifest
import id.asbun.voice.DispatchConfig
import id.asbun.voice.LlmConfig
import id.asbun.voice.VoiceConfig
import id.asbun.voice.VoiceMessages
import id.ojolmitra.driver.model.capitalizeFirst

/**
 * How Ojol Mitra uses the Voice Command SDK: the command manifest (phrases -> deeplinks and
 * handlers), the screens it sends as context, and the SDK configuration.
 *
 * Context sent with every utterance (see `DriverController.syncVoiceContext`):
 * - `screen`: [SCREEN_HOME], [SCREEN_OFFER], [SCREEN_TRIP], [SCREEN_CHAT] or [SCREEN_DICTATION]
 * - `dictating`, `online` (booleans), `hint` (what to say next, used in re-prompts)
 * - offer: `orderId`; trip: `tripId`, `customer`, `stage`, `target`, `targetDistance`
 */
object OjolMitraVoice {
    const val LOCALE = "id-ID"
    const val SCHEME = "ojolmitra"

    const val SCREEN_HOME = "home"
    const val SCREEN_OFFER = "order_offer"
    const val SCREEN_TRIP = "trip"
    const val SCREEN_CHAT = "chat"
    /** Chat screen mic: whatever the driver says is the message. */
    const val SCREEN_DICTATION = "dictation"

    val manifest: CommandManifest by lazy { CommandManifest.fromJson(MANIFEST_JSON) }

    /**
     * Pass an [LlmConfig] to let an LLM resolve what the keywords miss, e.g.
     * `Claude.config(apiKey = ...)` for Claude. Without one the app works fully offline on keywords.
     */
    fun config(llm: LlmConfig? = null): VoiceConfig = VoiceConfig(
        locale = LOCALE,
        llm = llm,
        dispatch = DispatchConfig(allowedSchemes = listOf(SCHEME)),
        messages = VoiceMessages.INDONESIAN.copy(
            notUnderstood = "Maaf, perintah belum dikenali. Ucapkan bantuan untuk contoh perintah.",
            reprompt = "Maaf, kurang jelas. {context.hint}",
            noSpeech = "Belum terdengar. {context.hint}",
        ),
    )

    /**
     * Tidies a spoken place for display: "jalan sudirman arah blok m" -> "Jl. Sudirman arah Blok M".
     * Returns null for "here" words ("di sini", "di depan"): the report uses the driver's road.
     */
    fun spokenPlace(text: String?): String? {
        val words = text.orEmpty().split(Regex("\\s+"))
            .map { w -> w.lowercase().filter { it.isLetterOrDigit() } }
            .filter { it.isNotEmpty() }
        if (words.isEmpty() || words.all { it in HERE_WORDS }) return null
        return words.mapIndexed { i, t ->
            when {
                t == "jalan" || t == "jl" || t == "jln" -> "Jl."
                t == "gang" || t == "gg" -> "Gg."
                t.length == 1 -> t.uppercase()
                i > 0 && t in LOWERCASE_WORDS -> t
                else -> t.capitalizeFirst()
            }
        }.joinToString(" ").capitalizeFirst()
    }

    private val HERE_WORDS = setOf(
        "sini", "situ", "sana", "depan", "sekitar", "lokasi", "saya", "ini", "posisi", "sekitaran", "dekat", "aku", "sekarang",
    )
    private val LOWERCASE_WORDS = setOf(
        "arah", "dekat", "depan", "di", "ke", "dan", "sebelum", "setelah", "sesudah", "menuju", "dari", "yang", "samping",
    )
}

/**
 * The command manifest. In a production app this JSON can come from remote config and be
 * swapped at runtime with `VoiceSdk.updateManifest`.
 *
 * Commands that work everywhere carry `"when": {"dictating": false}` so that, while the driver
 * dictates a chat message, "terima kasih" is sent as text instead of being answered.
 */
internal const val MANIFEST_JSON = """
{
  "version": 1,
  "app": "ojolmitra-driver",
  "defaultLocale": "id-ID",
  "aliases": {
    "id-ID": {
      "udah": "sudah", "dah": "sudah", "sdh": "sudah", "uda": "sudah", "udh": "sudah",
      "sampe": "sampai", "nyampe": "sampai", "nyampai": "sampai", "sampek": "sampai",
      "gak": "tidak", "ga": "tidak", "nggak": "tidak", "enggak": "tidak", "ngga": "tidak", "engga": "tidak",
      "tdk": "tidak", "ndak": "tidak", "kagak": "tidak", "gk": "tidak", "nda": "tidak",
      "ok": "oke", "okay": "oke", "okey": "oke", "oce": "oke", "okee": "oke", "okeh": "oke",
      "iya": "ya", "yaa": "ya", "yap": "ya", "yup": "ya", "yoi": "ya", "yes": "ya",
      "telpon": "telepon", "telfon": "telepon", "tlp": "telepon", "nelpon": "telepon", "telponin": "telepon", "teleponin": "telepon",
      "jl": "jalan", "jln": "jalan", "tau": "tahu", "dapet": "dapat",
      "customer": "penumpang", "kustomer": "penumpang", "costumer": "penumpang", "kastemer": "penumpang",
      "pelanggan": "penumpang", "pelangan": "penumpang", "konsumen": "penumpang", "pemesan": "penumpang",
      "cet": "chat", "chatting": "chat", "ceting": "chat", "chatin": "chat", "chet": "chat",
      "bales": "balas", "balesin": "balas", "balasin": "balas", "bilangin": "bilang", "kabarin": "kabari", "kalo": "kalau",
      "lewatin": "skip", "lewati": "skip", "lewatkan": "skip", "pass": "skip",
      "gass": "gas", "gaspol": "gas", "gaskan": "gas", "gasken": "gas", "gaskeun": "gas",
      "orderan": "order", "selesaikan": "selesai", "selesein": "selesai", "kelar": "selesai", "rampung": "selesai",
      "laporkan": "lapor", "laporin": "lapor", "melaporkan": "lapor", "melapor": "lapor",
      "terimain": "terima", "ambilin": "ambil", "ambilkan": "ambil", "tolakin": "tolak",
      "kemacetan": "macet", "makasi": "makasih", "trims": "makasih", "tengkyu": "makasih",
      "aktifkan": "aktif", "nyalakan": "aktif", "hidupkan": "aktif", "aktipkan": "aktif", "nonaktifkan": "nonaktif",
      "navigasikan": "navigasi", "batalin": "batal", "batalkan": "batal", "ulangin": "ulangi",
      "lanjutkan": "lanjut", "lanjutin": "lanjut", "berlobang": "berlubang", "antri": "antrean", "antrian": "antrean",
      "hatihati": "awas"
    }
  },
  "fillers": {
    "id-ID": ["halo", "hai", "hei", "hey", "mitra", "asisten", "tolong", "coba", "please", "eh", "em", "hmm", "oke",
              "dong", "deh", "aja", "saja", "ya", "yah", "sih", "nih", "pak", "bos", "bang", "kak", "mas", "mbak",
              "sekarang", "yuk", "ayo", "dulu", "segera", "cepat"]
  },
  "suffixes": { "id-ID": ["nya", "lah", "kah"] },
  "commands": [
    {
      "id": "order.accept",
      "description": "Accept the incoming order offer shown on screen.",
      "phrases": ["terima", "terima order", "ambil", "ambil order", "saya ambil", "accept", "gas", "sikat", "angkut",
                  "hajar", "embat", "sabet", "oke", "siap", "ya", "boleh", "mau", "setuju", "sip", "mantap", "lanjut",
                  "yuk", "ayo", "diambil", "diterima"],
      "notPhrases": ["terima kasih", "tidak", "jangan", "bukan"],
      "when": { "screen": "order_offer" },
      "params": [ { "name": "orderId", "type": "text", "from": "context", "required": true } ],
      "action": { "type": "deeplink", "url": "ojolmitra://order/{orderId}/accept" },
      "reply": "Order diterima. Menuju {context.target}, {context.targetDistance}."
    },
    {
      "id": "order.reject",
      "description": "Reject (skip) the incoming order offer shown on screen.",
      "phrases": ["tolak", "tolak order", "skip", "reject", "decline", "tidak", "tidak mau", "tidak ambil", "jangan",
                  "jangan ambil", "jangan diambil", "bukan", "ogah", "males", "malas", "lain kali"],
      "when": { "screen": "order_offer" },
      "params": [ { "name": "orderId", "type": "text", "from": "context", "required": true } ],
      "action": { "type": "deeplink", "url": "ojolmitra://order/{orderId}/reject" },
      "reply": "Order dilewati. Mencari order lain."
    },
    {
      "id": "order.repeat",
      "description": "Read the current order offer or trip status aloud again.",
      "phrases": ["ulangi", "ulang", "detail", "detail order", "info order", "baca order", "order apa", "repeat",
                  "rincian", "status order", "ulangi order"],
      "when": { "screen": ["order_offer", "trip", "chat"] },
      "action": { "type": "handler", "name": "repeatOrder" }
    },
    {
      "id": "chat.send",
      "description": "Send a chat message to the customer of the active trip.",
      "phrases": ["chat {message}", "chat penumpang {message}", "chat ke penumpang {message}",
                  "kirim pesan {message}", "kirim pesan ke penumpang {message}", "kirim pesan bahwa {message}",
                  "kirim pesan ke penumpang bahwa {message}", "kirim pesan ke penumpang kalau {message}",
                  "kirim chat {message}", "kirim chat ke penumpang {message}", "kirimi pesan {message}",
                  "bilang {message}", "bilang ke penumpang {message}", "bilang kalau {message}",
                  "bilang ke penumpang kalau {message}", "balas {message}", "balas penumpang {message}",
                  "kasih tahu penumpang {message}", "kasih tahu penumpang kalau {message}",
                  "beri tahu penumpang {message}", "beritahu penumpang {message}", "kabari penumpang {message}",
                  "sampaikan ke penumpang {message}", "tulis pesan {message}", "send message {message}"],
      "when": { "screen": ["trip", "chat"] },
      "params": [
        { "name": "message", "type": "text", "from": "speech", "required": true,
          "ask": "Mau kirim pesan apa ke {context.customer}?" },
        { "name": "tripId", "type": "text", "from": "context", "required": true }
      ],
      "action": { "type": "deeplink", "url": "ojolmitra://trip/{tripId}/chat/send?text={message}" },
      "reply": "Pesan terkirim ke {context.customer}: {message}"
    },
    {
      "id": "chat.open",
      "description": "Open the chat screen with the customer.",
      "phrases": ["buka chat", "lihat chat", "buka pesan", "lihat pesan", "inbox", "ruang chat", "buka obrolan"],
      "when": { "screen": ["trip", "chat"] },
      "params": [ { "name": "tripId", "type": "text", "from": "context", "required": true } ],
      "action": { "type": "deeplink", "url": "ojolmitra://trip/{tripId}/chat" },
      "reply": "Membuka chat dengan {context.customer}."
    },
    {
      "id": "chat.read",
      "description": "Read the customer's last chat message aloud.",
      "phrases": ["baca pesan", "baca chat", "bacakan", "bacakan pesan", "bacakan chat", "pesan terakhir",
                  "pesan terakhir apa", "apa pesan terakhir", "baca pesan terakhir", "chat terakhir", "apa katanya",
                  "ada pesan", "ada pesan baru", "pesan masuk"],
      "when": { "screen": ["trip", "chat"] },
      "action": { "type": "handler", "name": "readMessage" }
    },
    {
      "id": "customer.call",
      "description": "Call the customer of the active trip.",
      "phrases": ["telepon", "telepon penumpang", "call", "call penumpang", "hubungi", "hubungi penumpang"],
      "when": { "screen": ["trip", "chat"] },
      "params": [ { "name": "tripId", "type": "text", "from": "context", "required": true } ],
      "action": { "type": "deeplink", "url": "ojolmitra://trip/{tripId}/call" },
      "reply": "Menelepon {context.customer}. Ini simulasi."
    },
    {
      "id": "trip.arrived",
      "description": "Confirm arrival at the pickup point or the destination.",
      "phrases": ["sampai", "sudah sampai", "saya sudah sampai", "tiba", "sudah tiba", "arrived", "di lokasi",
                  "sudah di lokasi", "saya sudah di lokasi", "sudah di titik"],
      "notPhrases": ["belum", "hampir"],
      "when": { "screen": ["trip", "chat"] },
      "action": { "type": "handler", "name": "arrive" }
    },
    {
      "id": "trip.start",
      "description": "Start the trip after the passenger got on or the item was picked up.",
      "phrases": ["mulai", "mulai perjalanan", "mulai antar", "berangkat", "antar", "naik", "sudah naik",
                  "penumpang sudah naik", "jemput", "sudah jemput", "start", "diambil", "sudah diambil", "sudah ambil",
                  "makanan sudah diambil", "paket sudah diambil", "pesanan sudah diambil", "pickup", "pick up"],
      "notPhrases": ["belum"],
      "when": { "screen": ["trip", "chat"] },
      "action": { "type": "handler", "name": "startTrip" }
    },
    {
      "id": "trip.complete",
      "description": "Complete the order at the destination.",
      "phrases": ["selesai", "sudah selesai", "selesai order", "order selesai", "selesai antar", "finish", "complete",
                  "beres", "sudah beres", "tuntas", "done"],
      "notPhrases": ["belum"],
      "when": { "screen": ["trip", "chat"] },
      "action": { "type": "handler", "name": "completeTrip" }
    },
    {
      "id": "trip.next",
      "description": "Do the next trip step (arrive, start or complete).",
      "phrases": ["lanjut", "next", "berikutnya", "langkah berikutnya", "geser", "konfirmasi"],
      "when": { "screen": ["trip", "chat"] },
      "action": { "type": "handler", "name": "nextStep" }
    },
    {
      "id": "trip.navigate",
      "description": "Open navigation to the current target.",
      "phrases": ["navigasi", "buka navigasi", "arahkan", "rute", "maps", "buka maps", "peta", "buka peta",
                  "petunjuk arah", "navigate", "waze"],
      "when": { "screen": ["trip", "chat"] },
      "action": { "type": "handler", "name": "navigate" }
    },
    {
      "id": "traffic.report",
      "description": "Report a traffic condition (jam, accident, flood, closed road, police check, damaged road, crowd) at a place or at the driver's position.",
      "phrases": ["{type}", "lapor {type}", "lapor ada {type}", "laporan {type}", "ada {type}", "info {type}",
                  "awas {type}", "awas ada {type}", "hati hati {type}", "hati hati ada {type}"],
      "when": { "dictating": false },
      "params": [
        { "name": "type", "type": "enum", "from": "speech", "required": true,
          "values": {
            "MACET": ["macet", "padat", "merayap", "padat merayap", "antrean", "tersendat"],
            "KECELAKAAN": ["kecelakaan", "tabrakan", "laka", "accident", "nabrak", "ketabrak", "tertabrak", "senggolan"],
            "BANJIR": ["banjir", "genangan", "tergenang", "flood"],
            "DITUTUP": ["jalan ditutup", "ditutup", "penutupan jalan", "penutupan", "jalan tutup", "tidak bisa lewat",
                        "tidak bisa dilewati", "road closed", "dialihkan", "pengalihan arus", "pengalihan"],
            "RAZIA": ["razia", "razia polisi", "polisi", "operasi zebra", "tilang", "cegatan", "polantas"],
            "JALAN_RUSAK": ["jalan rusak", "rusak", "jalan berlubang", "berlubang", "lubang", "perbaikan jalan", "galian", "longsor"],
            "DEMO": ["demo", "demonstrasi", "unjuk rasa", "keramaian", "massa", "pawai", "konvoi"]
          },
          "ask": "Mau lapor apa? Misalnya macet, kecelakaan, banjir, atau jalan ditutup." },
        { "name": "severity", "type": "enum", "from": "speech", "default": "SEDANG",
          "values": {
            "PARAH": ["parah", "total", "banget", "sekali", "berat", "sangat", "mampet", "gila", "panjang"],
            "RINGAN": ["ringan", "sedikit", "dikit", "lumayan", "agak", "lancar"],
            "SEDANG": ["sedang"]
          } },
        { "name": "location", "type": "text", "from": "speech",
          "after": ["di", "dekat", "depan", "sekitar", "arah", "menuju", "sebelum", "setelah", "sesudah", "daerah",
                    "kawasan", "dari", "jalan"] }
      ],
      "action": { "type": "handler", "name": "reportTraffic" }
    },
    {
      "id": "traffic.open",
      "description": "Show and summarize the traffic reports nearby.",
      "phrases": ["laporan", "lihat laporan", "buka laporan", "laporan lalu lintas", "lalu lintas", "info lalu lintas",
                  "info jalan", "kondisi jalan", "lalin", "info lalin"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "openTraffic" }
    },
    {
      "id": "driver.online",
      "description": "Go online to receive orders.",
      "phrases": ["online", "aktif", "narik", "mulai narik", "siap narik", "mulai kerja", "on bid", "onbid", "mulai bid", "go online"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "goOnline" }
    },
    {
      "id": "driver.offline",
      "description": "Go offline and stop receiving orders.",
      "phrases": ["offline", "nonaktif", "istirahat", "berhenti narik", "selesai narik", "stop narik", "off bid",
                  "offbid", "udahan", "pulang", "tutup aplikasi", "go offline"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "goOffline" }
    },
    {
      "id": "autobid.set",
      "description": "Turn automatic order acceptance (autobid) on or off.",
      "phrases": ["autobid", "auto bid", "{enabled} autobid", "{enabled} auto bid", "autobid {enabled}", "auto bid {enabled}"],
      "when": { "dictating": false },
      "params": [
        { "name": "enabled", "type": "enum", "from": "speech", "default": "ON",
          "values": {
            "ON": ["aktif", "on", "nyala", "hidup", "pakai"],
            "OFF": ["matikan", "mati", "nonaktif", "off", "stop", "jangan", "tidak", "tanpa", "hentikan"]
          } }
      ],
      "action": { "type": "handler", "name": "setAutobid" }
    },
    {
      "id": "earnings.today",
      "description": "Tell today's earnings and wallet balance.",
      "phrases": ["pendapatan", "pendapatan hari ini", "berapa pendapatan", "berapa pendapatan hari ini", "penghasilan",
                  "penghasilan hari ini", "saldo", "cek saldo", "dapat berapa", "dapat berapa hari ini", "earning",
                  "duit", "gaji", "cuan", "cuan hari ini", "income"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "earnings" }
    },
    {
      "id": "app.help",
      "description": "Explain what the driver can say on this screen.",
      "phrases": ["bantuan", "help", "perintah apa", "bisa apa", "daftar perintah", "contoh perintah", "cara pakai", "panduan"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "help" },
      "priority": 1
    },
    {
      "id": "app.cancel",
      "description": "Never mind; stop.",
      "phrases": ["batal", "cancel", "tidak jadi", "stop", "diam", "cukup", "sudah cukup", "nevermind"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "reply" },
      "reply": "Oke."
    },
    {
      "id": "app.thanks",
      "description": "The driver says thank you.",
      "phrases": ["terima kasih", "terima kasih banyak", "makasih", "makasih banyak", "thanks", "thank you"],
      "when": { "dictating": false },
      "action": { "type": "handler", "name": "reply" },
      "reply": "Sama-sama! Hati-hati di jalan ya."
    },
    {
      "id": "chat.dictate",
      "description": "Dictation on the chat screen: the whole utterance is the message to the customer.",
      "phrases": ["{message}"],
      "when": { "screen": "dictation" },
      "params": [
        { "name": "message", "type": "text", "from": "speech", "required": true },
        { "name": "tripId", "type": "text", "from": "context", "required": true }
      ],
      "action": { "type": "deeplink", "url": "ojolmitra://trip/{tripId}/chat/send?text={message}" },
      "reply": "Terkirim."
    },
    {
      "id": "chat.dictate.cancel",
      "description": "Cancel the dictated chat message.",
      "phrases": ["batal", "cancel", "tidak jadi", "batal kirim", "jangan kirim"],
      "when": { "screen": "dictation" },
      "action": { "type": "handler", "name": "reply" },
      "reply": "Oke, pesan dibatalkan."
    }
  ]
}
"""
