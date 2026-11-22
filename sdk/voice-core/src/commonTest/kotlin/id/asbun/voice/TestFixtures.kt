package id.asbun.voice

/** Manifest from the spec, plus a confirmed reject command and a help command. */
internal val TEST_MANIFEST_JSON = """
{
  "version": 1,
  "app": "ojolmitra-driver",
  "defaultLocale": "id-ID",
  "aliases": { "id-ID": { "udah": "sudah", "sampe": "sampai", "gak": "tidak", "nggak": "tidak", "laporkan": "lapor", "customer": "penumpang" } },
  "fillers": { "id-ID": ["halo", "tolong", "dong", "ya", "pak", "oke"] },
  "suffixes": { "id-ID": ["nya", "lah"] },
  "commands": [
    {
      "id": "order.accept",
      "description": "Accept the incoming order shown on screen.",
      "phrases": { "id-ID": ["terima", "terima order", "ambil", "gas", "oke", "ya"], "en-US": ["accept", "take it"] },
      "notPhrases": { "id-ID": ["terima kasih"] },
      "when": { "screen": "order_offer" },
      "params": [ { "name": "orderId", "type": "text", "from": "context", "required": true } ],
      "action": { "type": "deeplink", "url": "ojolmitra://order/{orderId}/accept" },
      "reply": { "id-ID": "Order diterima.", "en-US": "Order accepted." }
    },
    {
      "id": "order.reject",
      "description": "Reject the incoming order shown on screen.",
      "phrases": { "id-ID": ["tolak", "jangan ambil", "tidak mau"] },
      "when": { "screen": "order_offer" },
      "params": [ { "name": "orderId", "type": "text", "from": "context", "required": true } ],
      "action": { "type": "deeplink", "url": "ojolmitra://order/{orderId}/reject" },
      "confirm": { "id-ID": "Yakin tolak order ini?" },
      "reply": { "id-ID": "Order ditolak." }
    },
    {
      "id": "chat.send",
      "description": "Send a chat message to the customer of the active trip.",
      "phrases": { "id-ID": ["chat {message}", "kirim pesan ke penumpang {message}", "bilang ke customer {message}"] },
      "when": { "screen": ["trip", "chat"] },
      "params": [
        { "name": "message", "type": "text", "from": "speech", "required": true, "ask": { "id-ID": "Mau kirim pesan apa?" } },
        { "name": "tripId", "type": "text", "from": "context", "required": true }
      ],
      "action": { "type": "deeplink", "url": "ojolmitra://trip/{tripId}/chat?text={message}" },
      "reply": { "id-ID": "Pesan terkirim." }
    },
    {
      "id": "traffic.report",
      "description": "Report a traffic condition (jam, accident, flood) at a place.",
      "phrases": { "id-ID": ["lapor {type} di {location}", "lapor {type}", "ada {type} di {location}", "ada {type}", "{type}"] },
      "params": [
        { "name": "type", "type": "enum", "from": "speech", "required": true,
          "values": { "MACET": ["macet", "padat"], "KECELAKAAN": ["kecelakaan", "tabrakan"], "BANJIR": ["banjir", "genangan"] },
          "ask": { "id-ID": "Mau lapor apa? Macet, kecelakaan, atau banjir?" } },
        { "name": "severity", "type": "enum", "from": "speech", "default": "SEDANG",
          "values": { "PARAH": ["parah", "total", "banget"], "RINGAN": ["ringan", "sedikit"] } },
        { "name": "location", "type": "text", "from": "both", "default": "{context.currentRoad}", "after": ["di", "dekat"] }
      ],
      "action": { "type": "handler", "name": "reportTraffic" },
      "reply": { "id-ID": "Laporan {type} di {location} terkirim." }
    },
    {
      "id": "app.help",
      "description": "List what the user can say.",
      "phrases": ["bantuan", "help"],
      "action": { "type": "handler", "name": "help" },
      "priority": 1
    }
  ]
}
""".trimIndent()

internal val TEST_MANIFEST: CommandManifest by lazy { CommandManifest.fromJson(TEST_MANIFEST_JSON) }

internal val OFFER = VoiceContext(screen = "order_offer", values = mapOf("orderId" to "OM-1"))
internal val TRIP = VoiceContext(screen = "trip", values = mapOf("tripId" to "T-9", "currentRoad" to "Jl. Gatot Subroto"))
internal val HOME = VoiceContext(screen = "home", values = mapOf("currentRoad" to "Jl. Gatot Subroto"))

/** Engine double: speech finishes at once, and tests "say" things while it listens. */
internal class FakeVoiceEngine(override val isRecognitionAvailable: Boolean = true) : VoiceEngine {
    override var listener: VoiceEngineListener? = null
    val spoken = mutableListOf<String>()
    var listening = false
        private set
    var listenCount = 0
        private set

    var wakeAvailable = true
    override val isWakeListeningAvailable: Boolean get() = wakeAvailable
    var lastMode: ListenMode? = null
        private set

    override fun startListening(languageTag: String) = startListening(languageTag, ListenMode.COMMAND)

    override fun startListening(languageTag: String, mode: ListenMode) {
        listening = true
        listenCount++
        lastMode = mode
        listener?.onListeningStarted()
    }

    override fun stopListening() {}

    override fun cancelListening() {
        listening = false
    }

    override fun speak(text: String, utteranceId: String, languageTag: String) {
        spoken += text
        listener?.onSpeakingStarted(utteranceId)
        listener?.onSpeakingDone(utteranceId)
    }

    var isShutDown = false
        private set

    override fun stopSpeaking() {}
    override fun shutdown() {
        isShutDown = true
    }

    fun say(text: String) {
        check(listening) { "not listening" }
        listening = false
        listener?.onFinalResult(listOf(text))
    }

    fun silence() {
        check(listening) { "not listening" }
        listening = false
        listener?.onListeningError(VoiceError.NO_MATCH)
    }

    fun fail(error: VoiceError) {
        check(listening) { "not listening" }
        listening = false
        listener?.onListeningError(error)
    }
}
