package me.vibb.music.sonos

import java.io.ByteArrayOutputStream
import java.util.Locale

/*
 * Time, URI and MIME helpers for the Sonos speaker, ported from palchrb/vibb `pi/sonosd.py` (`_hms`,
 * `_hms_to_s`, `MIME`, `_norm_uri`, `_mime_for`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26a §2.3. Pure;
 * SonosUrisTest. The encodings are Python's, byte for byte (QA L1): `unquote` keeps a `+`, `quote`
 * keeps a `/`, `escape` touches `&`, `<` and `>` only.
 */

/** `_hms`: whole seconds as `H:MM:SS` (null and negatives are 0; fractions are dropped, as `int()`). */
fun hms(sec: Double?): String {
    val s = (sec ?: 0.0).toLong().coerceAtLeast(0)
    // Locale.ROOT: ASCII digits whatever the phone's language (Python's f-string never localizes)
    return "%d:%02d:%02d".format(Locale.ROOT, s / 3600, s % 3600 / 60, s % 60)
}

private val HMS_H = Regex("-?[0-9]+")
private val HMS_S = Regex("-?[0-9]+(\\.[0-9]*)?")

/**
 * `_hms_to_s`: `H:MM:SS` in seconds. Null or empty is 0.0 (vibb's `s or "0:00:00"`), and so is
 * `"0:00:00"` - the duration carry depends on 0 being "no duration" (daemon.py:4398-4406).
 * `NOT_IMPLEMENTED` or anything else that isn't three numbers is null.
 */
fun hmsToS(s: String?): Double? {
    val parts = (s.takeUnless { it.isNullOrEmpty() } ?: "0:00:00").split(":")
    if (parts.size != 3) return null
    val (h, m, sec) = parts.map { it.trim() }
    if (!HMS_H.matches(h) || !HMS_H.matches(m) || !HMS_S.matches(sec)) return null
    return h.toLong() * 3600.0 + m.toLong() * 60.0 + sec.toDouble()
}

/** `MIME`: the protocolInfo's type by the path's extension. */
val MIME = linkedMapOf(
    ".mp3" to "audio/mpeg", ".m4a" to "audio/mp4", ".m4b" to "audio/mp4", ".aac" to "audio/aac",
    ".flac" to "audio/flac", ".wav" to "audio/wav", ".ogg" to "audio/ogg", ".opus" to "audio/ogg",
)

/** `_mime_for`: by the URL path's extension (`urlparse(uri).path`), else `audio/mpeg`. */
fun mimeFor(uri: String): String {
    val path = urlPath(uri).lowercase()
    return MIME.entries.firstOrNull { path.endsWith(it.key) }?.value ?: "audio/mpeg"
}

/** Python's `urlparse(u).path`: without the fragment, the query, `//netloc` and, for http(s), `;params`. */
internal fun urlPath(u: String): String {
    var rest = u.substringBefore('#').substringBefore('?')
    val scheme = Regex("^([A-Za-z][A-Za-z0-9+.-]*):").find(rest)?.groupValues?.get(1)?.lowercase()
    if (scheme != null) rest = rest.substring(scheme.length + 1)
    if (rest.startsWith("//")) {
        val slash = rest.indexOf('/', 2)
        rest = if (slash < 0) "" else rest.substring(slash)
    }
    if (scheme == null || scheme in PARAM_SCHEMES) {
        val last = rest.lastIndexOf('/')
        val semi = rest.indexOf(';', last + 1)
        if (semi >= 0) rest = rest.substring(0, semi)
    }
    return rest
}

/** `urllib.parse.uses_params`, the schemes whose last path segment may carry `;params`. */
private val PARAM_SCHEMES = setOf("ftp", "hdl", "prospero", "http", "imap", "https", "shttp", "rtsp", "rtspu", "sip", "sips", "mms", "sftp", "tel")

/** The prefixes `_norm_uri` strips (one, the first that matches). */
private val NORM_PREFIXES = listOf("x-rincon-mp3radio://", "aac://", "https://", "http://")

/**
 * `_norm_uri`: the speaker-vs-ours equality key - percent-decoded like Python's `unquote`, then one
 * scheme prefix off (some firmwares re-encode, or swap the prefix). Ours-detection and the migration
 * probe compare only through this.
 */
fun normUri(u: String?): String {
    val d = pyUnquote(u.orEmpty())
    val p = NORM_PREFIXES.firstOrNull { d.startsWith(it) } ?: return d
    return d.substring(p.length)
}

/**
 * Python's `urllib.parse.unquote` (UTF-8, errors replaced): `%XX` runs become bytes decoded as
 * UTF-8, a `%` without two hex digits stays, and a `+` stays a `+` (not `URLDecoder`).
 */
fun pyUnquote(s: String): String {
    if ('%' !in s) return s
    val out = StringBuilder()
    val bytes = ByteArrayOutputStream()
    fun flush() {
        if (bytes.size() > 0) {
            out.append(bytes.toByteArray().toString(Charsets.UTF_8))
            bytes.reset()
        }
    }
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c.code >= 0x80) {
            // Python decodes only the ASCII runs; anything else passes through as it is.
            flush()
            out.append(c)
            i++
        } else if (c == '%' && i + 2 < s.length && isHex(s[i + 1]) && isHex(s[i + 2])) {
            bytes.write(s.substring(i + 1, i + 3).toInt(16))
            i += 3
        } else {
            bytes.write(c.code)
            i++
        }
    }
    flush()
    return out.toString()
}

private fun isHex(c: Char) = c in '0'..'9' || c in 'a'..'f' || c in 'A'..'F'

/** Python's `urllib.parse.quote(s)` (safe = `/`): UTF-8, uppercase `%XX`, `A-Za-z0-9_.-~/` kept. */
fun pyQuote(s: String, safe: String = "/"): String {
    val out = StringBuilder()
    for (b in s.toByteArray(Charsets.UTF_8)) {
        val c = (b.toInt() and 0xFF)
        val ch = c.toChar()
        if (c < 0x80 && (ch.isLetterOrDigit() || ch in "_.-~" || ch in safe)) out.append(ch) else out.append("%%%02X".format(Locale.ROOT, c))
    }
    return out.toString()
}

/** Python's `xml.sax.saxutils.escape`: `&`, `<` and `>` only (quotes stay). */
fun xmlEscape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
