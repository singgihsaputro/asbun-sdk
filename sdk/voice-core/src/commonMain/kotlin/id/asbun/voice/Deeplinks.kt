package id.asbun.voice

/** A deeplink split into parts, with percent-decoded path segments and query values. */
public data class ParsedLink(
    val scheme: String,
    val host: String,
    val pathSegments: List<String>,
    val query: Map<String, String>,
)

/** Template filling, allowlist checks and parsing for deeplinks. */
public object Deeplinks {
    private val PLACEHOLDER = Regex("\\{(context\\.)?([A-Za-z0-9_]+)}")

    /**
     * Replaces `{param}` and `{context.key}` in a deeplink template. Values are percent-encoded
     * (everything except RFC 3986 unreserved characters), so they are safe in a path segment and
     * in a query value. Returns null when a placeholder has no value.
     */
    public fun build(template: String, params: Map<String, String>, context: VoiceContext): String? {
        var missing = false
        val out = PLACEHOLDER.replace(template) { m ->
            val value = valueFor(m, params, context)
            if (value.isNullOrEmpty()) {
                missing = true
                ""
            } else {
                percentEncode(value)
            }
        }
        return if (missing) null else out
    }

    /** Fills a spoken template (reply, question). Unknown placeholders become empty. */
    public fun render(template: String, params: Map<String, String>, context: VoiceContext): String =
        PLACEHOLDER.replace(template) { m -> valueFor(m, params, context).orEmpty() }
            .replace(Regex("\\s+"), " ")
            .replace(Regex("\\s+([.,!?:])"), "$1")
            .trim()

    /** True when the link's scheme (and host, if a host list is set) is allowed. */
    public fun isAllowed(url: String, dispatch: DispatchConfig): Boolean {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return false
        val scheme = url.substring(0, schemeEnd)
        if (dispatch.allowedSchemes.none { it.equals(scheme, ignoreCase = true) }) return false
        if (dispatch.allowedHosts.isEmpty()) return true
        val host = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '?' && it != '#' }.substringAfter('@').substringBefore(':')
        return dispatch.allowedHosts.any { it.equals(host, ignoreCase = true) }
    }

    /** Splits `scheme://host/a/b?x=1` into parts; returns null for a string without "://". */
    public fun parse(url: String): ParsedLink? {
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return null
        val scheme = url.substring(0, schemeEnd)
        val rest = url.substring(schemeEnd + 3).substringBefore('#')
        val beforeQuery = rest.substringBefore('?')
        val queryPart = if ('?' in rest) rest.substringAfter('?') else ""
        val host = beforeQuery.substringBefore('/')
        val path = if ('/' in beforeQuery) beforeQuery.substringAfter('/') else ""
        val segments = path.split('/').filter { it.isNotEmpty() }.map { percentDecode(it) }
        val query = queryPart.split('&').filter { it.isNotEmpty() }.associate { pair ->
            percentDecode(pair.substringBefore('=')) to percentDecode(pair.substringAfter('=', ""))
        }
        return ParsedLink(scheme, host, segments, query)
    }

    public fun percentEncode(value: String): String = buildString {
        for (byte in value.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '-' || ch == '.' || ch == '_' || ch == '~') {
                append(ch)
            } else {
                append('%')
                append(HEX[c shr 4])
                append(HEX[c and 0x0F])
            }
        }
    }

    public fun percentDecode(value: String): String {
        val bytes = ArrayList<Byte>(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '%' && i + 2 < value.length) {
                val hi = value[i + 1].digitToIntOrNull(16)
                val lo = value[i + 2].digitToIntOrNull(16)
                if (hi != null && lo != null) {
                    bytes += ((hi shl 4) or lo).toByte()
                    i += 3
                    continue
                }
            }
            if (ch == '+') {
                bytes += ' '.code.toByte()
            } else {
                ch.toString().encodeToByteArray().forEach { bytes += it }
            }
            i++
        }
        return bytes.toByteArray().decodeToString()
    }

    private fun valueFor(m: MatchResult, params: Map<String, String>, context: VoiceContext): String? {
        val isContext = m.groupValues[1].isNotEmpty()
        val key = m.groupValues[2]
        return if (isContext) context[key]?.toString() else params[key] ?: context[key]?.toString()
    }

    private val HEX = "0123456789ABCDEF".toCharArray()
}
