package com.kidslauncher.mdm.map

import java.util.Locale

/**
 * A link read offline (design 110 §2, §3, `map_link_rules.json`): what to open, in neutral words.
 *
 * - [Kind.PIN]: the place's point ([lat], [lon]), its zoom and its [label].
 * - [Kind.RESOLVE]: a short link the server turns into its long link ([short]).
 * - [Kind.LOOKUP]: a place id ([ftid]) the server looks up for its point, name and address; the link's own point (if
 *   any) and text go with it, and [query]/[centre] are the fallback search's.
 * - [Kind.SEARCH]: no point - Organic Maps' offline search for [query], centred at [centre] if known.
 * - [Kind.NONE]: no place in the link; [Kind.LIST]: a list of places.
 */
data class MapParse(
    val kind: Kind,
    val lat: Double? = null,
    val lon: Double? = null,
    val z: Int? = null,
    val label: String? = null,
    val ftid: String? = null,
    val short: String? = null,
    val query: String? = null,
    val centre: Pair<Double, Double>? = null,
) {
    enum class Kind { PIN, RESOLVE, LOOKUP, SEARCH, NONE, LIST }

    companion object {
        fun none() = MapParse(Kind.NONE)
        fun pin(point: Pair<Double, Double>, z: Int?, label: String?) =
            MapParse(Kind.PIN, point.first, point.second, z, label?.let(MapLinks::cutLabel))
        fun search(query: String, centre: Pair<Double, Double>?) = MapParse(Kind.SEARCH, query = query, centre = centre)
    }

    /** The `geo:` URI Organic Maps (or CoMaps) opens for a pin or a search; null for the rest. */
    fun geoUri(): String? = when (kind) {
        Kind.PIN -> MapLinks.pinUri(lat!!, lon!!, z, label)
        Kind.SEARCH -> MapLinks.searchUri(query!!, centre)
        else -> null
    }

    /** The fallback search of a link the server couldn't help with (§2.1): its text, centred; null without text. */
    fun fallback(): MapParse? = query?.let { search(it, centre) }
}

/** The neutral half of design 110 on the phone: the `geo:` forms, the label, the fallback's query and centre. */
object MapLinks {

    /** The apps a map link opens, in order (Q3: CoMaps when Organic Maps isn't there). */
    val TARGETS = listOf("app.organicmaps", "app.organicmaps.web", "app.comaps.fdroid", "app.comaps", "app.comaps.google", "app.comaps.huawei")

    private const val MAX_LABEL = 120

    /** The longest link a message's text gives (the handler's own limit). */
    const val MAX_LINK = 2048

    /** A label cut to 120 characters at a word (§2). */
    fun cutLabel(text: String): String {
        val clean = text.trim().replace(Regex("\\s+"), " ")
        if (clean.length <= MAX_LABEL) return clean
        val cut = clean.substring(0, MAX_LABEL)
        val space = cut.lastIndexOf(' ')
        return (if (space > MAX_LABEL / 2) cut.substring(0, space) else cut).trimEnd(',', ' ')
    }

    /** The fallback's query (§2.1, `query_parts`): the part after the first comma when it holds a digit (an address,
     * "Drammensveien 133, 0277 Oslo"), else the whole text. */
    fun queryOf(text: String?): String? {
        val clean = text?.trim()?.replace(Regex("\\s+"), " ")?.takeIf { it.isNotEmpty() } ?: return null
        val after = clean.substringAfter(',', "").trim()
        return if (after.any { it.isDigit() }) after else clean
    }

    /** Percent-encoding for the `geo:` URI: everything but `A-Za-z0-9-._~` as UTF-8 bytes (a space is `%20`). */
    fun encode(text: String): String = buildString {
        for (b in text.toByteArray(Charsets.UTF_8)) {
            val c = (b.toInt() and 0xff).toChar()
            if (c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '.' || c == '_' || c == '~') {
                append(c)
            } else {
                append('%').append(String.format(Locale.ROOT, "%02X", b.toInt() and 0xff))
            }
        }
    }

    private fun coord(x: Double) = String.format(Locale.ROOT, "%.6f", x)

    /** The zoom: the link's, clamped to 3-19, else 17. */
    fun zoom(z: Int?): Int = z?.coerceIn(3, 19) ?: 17

    /** §5: `geo:lat,lon?z=Z&q=lat,lon(label)`, or `geo:lat,lon?z=Z` without a label. */
    fun pinUri(lat: Double, lon: Double, z: Int?, label: String?): String {
        val at = "${coord(lat)},${coord(lon)}"
        val base = "geo:$at?z=${zoom(z)}"
        return if (label.isNullOrBlank()) base else "$base&q=$at(${encode(label)})"
    }

    /** §5: `geo:0,0?q=query`, or centred `geo:lat,lon?q=query`. */
    fun searchUri(query: String, centre: Pair<Double, Double>?): String {
        val at = centre?.let { "${coord(it.first)},${coord(it.second)}" } ?: "0,0"
        return "geo:$at?q=${encode(query)}"
    }

    /**
     * How the target is started (2026-11-19, the user's "Organic Maps in the background doesn't move"; Organic Maps
     * 2026.11.11 and CoMaps 2026.11.07 on Android 14 and 16, `docs/notes/launcher/map-links.md`):
     * - Both take `geo:` in `SplashActivity` (standard launch mode, the app's own task), which hands it on to the
     *   `singleTask` `MwmActivity`. A `FLAG_ACTIVITY_NEW_TASK` start whose intent `filterEquals` the root intent of the
     *   app's task (a link opened before) only brings that task to the front: the link is lost.
     * - With the app running, a new pin moves the map, but the place card doesn't open while the previous one is still
     *   up (Android 16, both apps, any start).
     * So the app's task is cleared ([clearsTask]): a fresh `MwmActivity` always opens the pin with its card. Not when
     * the app itself handed us the link - it is on screen, and recreating it there leaves its map black (its old
     * activity's GL surface teardown comes after the new one's attach); then the fresh [startIdentity] alone keeps the
     * start from being dropped.
     */
    fun clearsTask(referrer: String?, target: String): Boolean = referrer != target

    /** A fresh identity per start: part of `filterEquals` ([android.content.Intent.setIdentifier]), see [clearsTask]. */
    fun startIdentity(): String = "vibb-map-" + java.util.UUID.randomUUID()

    /** The label of a place the server found: "name, address" (either alone, or null). */
    fun placeLabel(name: String?, address: String?): String? = listOfNotNull(name?.takeIf { it.isNotBlank() }, address?.takeIf { it.isNotBlank() })
        .joinToString(", ").takeIf { it.isNotEmpty() }?.let(::cutLabel)

    // --- The fallback's centre: the place id's coarse S2 cell (§2.1) ---------------------------------------------

    private val POS_TO_IJ = arrayOf(intArrayOf(0, 1, 3, 2), intArrayOf(0, 2, 3, 1), intArrayOf(3, 2, 0, 1), intArrayOf(3, 1, 0, 2))
    private val POS_TO_ORIENTATION = intArrayOf(1, 0, 0, 3)

    private fun stToUv(s: Double) = if (s >= 0.5) (4 * s * s - 1) / 3 else (1 - 4 * (1 - s) * (1 - s)) / 3

    /** The centre of the level-8 S2 cell (about 40 km) a place id's first half names: offline, never the place
     * itself - only where Organic Maps' search starts. Null for a `0x0:` id (a `cid`'s) or an unreadable one. */
    fun cellCentre(ftid: String): Pair<Double, Double>? {
        val first = ftid.substringBefore(':').removePrefix("0x").toULongOrNull(16)?.takeIf { it != 0uL } ?: return null
        val level = 8
        val keepBits = 3 + 2 * level
        val id = (first shr (64 - keepBits) shl (64 - keepBits)) or (1uL shl (64 - keepBits - 1))
        val face = (id shr 61).toInt()
        if (face > 5) return null
        val pos = (id shr 1) and ((1uL shl 60) - 1uL)
        var orientation = face and 1
        var i = 0L
        var j = 0L
        for (k in 29 downTo 0) {
            val p = ((pos shr (2 * k)) and 3uL).toInt()
            val ij = POS_TO_IJ[orientation][p]
            i = (i shl 1) or (ij shr 1).toLong()
            j = (j shl 1) or (ij and 1).toLong()
            orientation = orientation xor POS_TO_ORIENTATION[p]
        }
        val size = 1L shl (30 - level)
        i = i / size * size + size / 2
        j = j / size * size + size / 2
        val max = (1L shl 30).toDouble()
        val u = stToUv(i / max)
        val v = stToUv(j / max)
        val (x, y, z) = when (face) {
            0 -> Triple(1.0, u, v)
            1 -> Triple(-u, 1.0, v)
            2 -> Triple(-u, -v, 1.0)
            3 -> Triple(-1.0, -v, -u)
            4 -> Triple(v, -1.0, -u)
            else -> Triple(v, u, -1.0)
        }
        val n = Math.sqrt(x * x + y * y + z * z)
        return Math.toDegrees(Math.asin(z / n)) to Math.toDegrees(Math.atan2(y, x))
    }

    // --- Web links in a message (§15 Q1) --------------------------------------------------------------------------

    /** Where each `http`/`https` link sits in [text] (start, end exclusive), in order: a link runs to the first space
     * or angle bracket, a trailing `.,;:!?` and an unbalanced `)` left out. Phone numbers, e-mail addresses and
     * street addresses stay plain text. */
    fun webLinks(text: String): List<IntRange> {
        val found = mutableListOf<IntRange>()
        val start = Regex("(?i)\\bhttps?://")
        var from = 0
        while (true) {
            val m = start.find(text, from) ?: break
            var end = m.range.first
            // Code QA L3: a link is at most MAX_LINK long, and its brackets are counted once (no quadratic trim).
            val limit = minOf(text.length, m.range.first + MAX_LINK)
            while (end < limit && !text[end].isWhitespace() && text[end] != '<' && text[end] != '>' && text[end] != '"') end++
            var opens = 0
            var closes = 0
            for (i in m.range.first until end) {
                if (text[i] == '(') opens++ else if (text[i] == ')') closes++
            }
            while (end > m.range.last + 1) {
                val c = text[end - 1]
                if (c in ".,;:!?'" || (c == ')' && closes > opens)) {
                    if (c == ')') closes--
                    end--
                } else {
                    break
                }
            }
            if (end > m.range.last + 1 && LinkUrl.parse(text.substring(m.range.first, end)) != null) {
                found += m.range.first until end
            }
            from = maxOf(end, m.range.last + 1)
        }
        return found
    }
}
