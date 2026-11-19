package com.kidslauncher.mdm.map

/**
 * A web link split into its parts by hand - pure JVM, so the rules run in unit tests, and lenient where
 * `java.net.URI` throws (an unencoded `æ` or `|` in a pasted link). [host] is lowercase; [path] is as written ("" for
 * none); [query] the raw query without `?`. Anything without `scheme://host` is no link.
 */
data class LinkUrl(
    val scheme: String,
    val host: String,
    val hasUserInfo: Boolean,
    val hasPort: Boolean,
    val path: String,
    val query: String?,
) {
    /** The query's pairs in order, form-decoded (`+` a space, `%XX` as UTF-8). */
    val params: List<Pair<String, String>> by lazy {
        query.orEmpty().split('&').filter { it.isNotEmpty() }.map { pair ->
            val at = pair.indexOf('=')
            if (at < 0) formDecode(pair) to "" else formDecode(pair.substring(0, at)) to formDecode(pair.substring(at + 1))
        }
    }

    fun param(name: String): String? = params.firstOrNull { it.first == name }?.second

    /** https, no user info, no port. */
    val plainHttps: Boolean get() = scheme == "https" && !hasUserInfo && !hasPort

    companion object {
        fun parse(raw: String): LinkUrl? {
            val text = raw.trim()
            val sep = text.indexOf("://")
            if (sep <= 0) return null
            val scheme = text.substring(0, sep).lowercase()
            if (!scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '-' || it == '.' }) return null
            val rest = text.substring(sep + 3)
            val end = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, end)
            var remainder = rest.substring(end)
            val fragment = remainder.indexOf('#')
            if (fragment >= 0) remainder = remainder.substring(0, fragment)
            val q = remainder.indexOf('?')
            val path = if (q < 0) remainder else remainder.substring(0, q)
            val query = if (q < 0) null else remainder.substring(q + 1)
            val userInfo = authority.contains('@')
            val hostPort = authority.substringAfterLast('@')
            val hasPort = hostPort.contains(':')
            val host = hostPort.substringBefore(':').lowercase()
            if (host.isEmpty() || !host.all { it in 'a'..'z' || it in '0'..'9' || it == '.' || it == '-' }) return null
            return LinkUrl(scheme, host, userInfo, hasPort, path, query)
        }

        /** `%XX` decoded as UTF-8 (a broken escape kept as written). */
        fun percentDecode(text: String): String {
            if ('%' !in text) return text
            val out = java.io.ByteArrayOutputStream()
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '%' && i + 2 < text.length) {
                    val hi = Character.digit(text[i + 1], 16)
                    val lo = Character.digit(text[i + 2], 16)
                    if (hi >= 0 && lo >= 0) {
                        out.write(hi * 16 + lo)
                        i += 3
                        continue
                    }
                }
                out.write(c.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return out.toString("UTF-8")
        }

        fun formDecode(text: String): String = percentDecode(text.replace('+', ' '))
    }
}
