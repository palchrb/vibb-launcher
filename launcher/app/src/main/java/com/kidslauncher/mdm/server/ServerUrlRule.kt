package com.kidslauncher.mdm.server

/*
 * The phones' server address rule (security review S-M1, the user's decision of 2026-11-04): `https://` to any host,
 * or `http://` to a Tailscale IP literal (100.64.0.0/10, fd7a:115c:a1e0::/48) - and that only through this phone's
 * own tailnet connection ([serverPath]). Nothing is looked up: a `*.ts.net` name must be https. Pure, and the
 * server judges the same rows the same way (`config::check_server_url`): `testdata/server_url_vectors.json`,
 * byte-identical in both, run by ServerTransportTest and `config::tests::the_server_url_vectors`.
 *
 * The grammar is deliberately narrower than a URL: after trimming ASCII whitespace at both ends only
 * `scheme://host[:port][/path]` passes, with a path of `A-Z a-z 0-9 . _ ~ / -`. No userinfo, query, fragment, percent
 * sign or backslash can then make two parsers (this one and OkHttp's) see two hosts.
 */

/** What [serverUrlVerdict] says about an address. */
enum class ServerUrlVerdict {
    /** `https://`: direct, or through tsnet's proxy when it runs. */
    HTTPS,

    /** `http://` to a Tailscale IP literal: only through tsnet's proxy, never over the phone's own network. */
    TAILNET_HTTP,

    /** Anything else: the phone never sends a request to it. */
    REFUSED,
}

/** An address the grammar takes: [host] lower case (an IPv6 literal without brackets), [ip] its bytes when it is an
 * IP literal, [port] null when not given, [path] "" or starting with `/`. */
class ServerUrlParts(val https: Boolean, val host: String, val ip: ByteArray?, val port: Int?, val path: String)

private val ASCII_WHITESPACE = charArrayOf(' ', '\t', '\n', '\u000c', '\r')

private fun Char.isAsciiLetter() = this in 'a'..'z' || this in 'A'..'Z'
private fun Char.isAsciiDigit() = this in '0'..'9'
private fun Char.isHex() = isAsciiDigit() || this in 'a'..'f' || this in 'A'..'F'
private fun Char.isPathChar() = isAsciiLetter() || isAsciiDigit() || this in "._~/-"

/** [url] split by the rule's grammar, or null when it doesn't fit it. */
fun parseServerUrl(url: String?): ServerUrlParts? {
    val text = url?.trim(*ASCII_WHITESPACE) ?: return null
    val sep = text.indexOf("://")
    if (sep <= 0) return null
    val scheme = text.substring(0, sep)
    if (!scheme.all { it.isAsciiLetter() }) return null
    val https = when (scheme.lowercase()) {
        "https" -> true
        "http" -> false
        else -> return null
    }
    val rest = text.substring(sep + 3)
    val slash = rest.indexOf('/')
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "" else rest.substring(slash)
    if (!path.all { it.isPathChar() }) return null

    val host: String
    val ip: ByteArray?
    val portText: String?
    if (authority.startsWith("[")) {
        val close = authority.indexOf(']')
        if (close < 0) return null
        val raw = authority.substring(1, close)
        ip = ipv6Literal(raw) ?: return null
        host = raw.lowercase()
        val after = authority.substring(close + 1)
        portText = when {
            after.isEmpty() -> null
            after.startsWith(":") -> after.substring(1)
            else -> return null
        }
    } else {
        val colon = authority.indexOf(':')
        val raw = if (colon < 0) authority else authority.substring(0, colon)
        portText = if (colon < 0) null else authority.substring(colon + 1)
        ip = ipv4Literal(raw)
        // Checked before lower-casing: `lowercase()` maps some non-ASCII letters (the Kelvin sign) to ASCII ones.
        if (ip == null && !dnsName(raw)) return null
        host = raw.lowercase()
    }
    val port = portText?.let { p ->
        if (p.isEmpty() || p.length > 5 || !p.all { it.isAsciiDigit() } || p.startsWith("0")) return null
        p.toInt().takeIf { it in 1..65535 } ?: return null
    }
    return ServerUrlParts(https, host, ip, port, path)
}

/** The rule's verdict on [url] (see the file's comment). */
fun serverUrlVerdict(url: String?): ServerUrlVerdict {
    val parts = parseServerUrl(url) ?: return ServerUrlVerdict.REFUSED
    return when {
        parts.https -> ServerUrlVerdict.HTTPS
        parts.ip != null && isTailnetAddress(parts.ip) -> ServerUrlVerdict.TAILNET_HTTP
        else -> ServerUrlVerdict.REFUSED
    }
}

/** 100.64.0.0/10 or fd7a:115c:a1e0::/48 - Tailscale's own ranges (an IPv4-mapped IPv6 address is neither). */
fun isTailnetAddress(bytes: ByteArray): Boolean = when (bytes.size) {
    4 -> (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 0x40
    16 -> TAILNET_V6_PREFIX.withIndex().all { (i, b) -> (bytes[i].toInt() and 0xFF) == b }
    else -> false
}

private val TAILNET_V6_PREFIX = listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0)

/** Four decimal parts 0-255, no leading zero (`100.064.0.1` is octal to some parsers). */
private fun ipv4Literal(text: String): ByteArray? {
    val parts = text.split('.')
    if (parts.size != 4) return null
    val bytes = ByteArray(4)
    for ((i, part) in parts.withIndex()) {
        if (part.isEmpty() || part.length > 3 || !part.all { it.isAsciiDigit() }) return null
        if (part.length > 1 && part.startsWith("0")) return null
        val value = part.toInt()
        if (value > 255) return null
        bytes[i] = value.toByte()
    }
    return bytes
}

/** Hex groups and one `::` only: no dotted IPv4 tail, no zone. */
private fun ipv6Literal(text: String): ByteArray? {
    if (text.isEmpty() || !text.all { it.isHex() || it == ':' }) return null
    val halves = text.split("::")
    if (halves.size > 2) return null
    fun groups(s: String): List<Int>? =
        if (s.isEmpty()) emptyList() else s.split(':').map { g -> if (g.length in 1..4) g.toInt(16) else return null }
    val head = groups(halves[0]) ?: return null
    val all = if (halves.size == 2) {
        val tail = groups(halves[1]) ?: return null
        if (head.size + tail.size > 7) return null
        head + List(8 - head.size - tail.size) { 0 } + tail
    } else {
        if (head.size != 8) return null
        head
    }
    val bytes = ByteArray(16)
    for ((i, g) in all.withIndex()) {
        bytes[2 * i] = (g shr 8).toByte()
        bytes[2 * i + 1] = g.toByte()
    }
    return bytes
}

/**
 * A DNS name of ASCII labels (letters, digits, `-` inside), no empty label (so no trailing dot), at most 253
 * characters. A last label that is a number (decimal, or `0x` hex) is refused: URL parsers read such a host as an
 * IPv4 address (`1681915905` is 100.64.0.1), and the strict dotted quad was tried before.
 */
private fun dnsName(text: String): Boolean {
    if (text.isEmpty() || text.length > 253) return false
    val labels = text.split('.')
    val okLabels = labels.all { l ->
        l.length in 1..63 && !l.startsWith("-") && !l.endsWith("-") && l.all { it.isAsciiLetter() || it.isAsciiDigit() || it == '-' }
    }
    if (!okLabels) return false
    val last = labels.last()
    val numeric = last.all { it.isAsciiDigit() } ||
        (last.length >= 2 && last[0] == '0' && (last[1] == 'x' || last[1] == 'X') && last.drop(2).all { it.isHex() })
    return !numeric
}
