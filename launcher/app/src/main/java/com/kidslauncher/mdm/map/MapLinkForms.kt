package com.kidslauncher.mdm.map

/**
 * The one place that knows the map services' links (design 110 §2, §3; the integration-boundary pilot): Google
 * Maps' and Apple Maps' hosts and forms, read into a neutral [MapParse]. Everything else in `map/` - the activity, the
 * plan, the report, Meldinger's links - speaks only of "links the server resolves", pins and searches.
 *
 * The rules shared with the server (`short_links`, `long_hosts`, `feature_ids`) are pinned by
 * `map_links/contract.json` (`MapLinksContractTest`); every form's reading by `map_link_rules.json` (`MapLinksTest`).
 */
object MapLinkForms {

    /** The package that holds the short-link host as a system-configured app link (§6.2): step 1 turns its link
     * handling off, §6.3's row gives it back. */
    const val SHORT_LINK_HOLDER = "com.google.android.gms"

    /** The hosts [MapLinkActivity] takes (its manifest's two filters, `MapLinksRuntime.preferredFilters`, pinned by
     * `MapLinksTest`): every path on the map hosts, `/maps…` on the general ones (code QA L7: named here alone). */
    val ANY_PATH_HOSTS = listOf(
        "maps.app.goo.gl", "maps.google.com", "maps.google.no", "maps.google.se", "maps.google.dk", "maps.google.fi",
        "maps.google.de", "maps.google.co.uk", "maps.apple.com", "maps.apple",
    )
    val MAPS_PATH_HOSTS = listOf(
        "www.google.com", "google.com", "www.google.no", "www.google.se", "www.google.dk", "www.google.fi",
        "www.google.de", "www.google.co.uk", "goo.gl",
    )

    /** A short link's id: `[A-Za-z0-9_-]{1,64}`. */
    private fun shortId(id: String) = id.length in 1..64 && id.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '-' }

    /** A short link rebuilt from its host and id (https, no query), or null (`short_links`). */
    fun shortLink(url: String): String? {
        val link = LinkUrl.parse(url)?.takeIf { it.plainHttps } ?: return null
        val (prefix, id) = when (link.host) {
            "maps.app.goo.gl" -> "https://maps.app.goo.gl/" to link.path.removePrefix("/").takeIf { link.path.startsWith("/") }
            "goo.gl" -> "https://goo.gl/maps/" to link.path.takeIf { it.startsWith("/maps/") }?.removePrefix("/maps/")
            "maps.apple" -> "https://maps.apple/p/" to link.path.takeIf { it.startsWith("/p/") }?.removePrefix("/p/")
            else -> return null
        }
        return id?.takeIf(::shortId)?.let { prefix + it }
    }

    private fun countryCode(text: String) = text.length == 2 && text.all { it in 'a'..'z' }

    private fun googleTld(rest: String) = rest == "com" || countryCode(rest) ||
        (rest.removePrefix("co.").takeIf { rest.startsWith("co.") } ?: rest.removePrefix("com.").takeIf { rest.startsWith("com.") })
            ?.let(::countryCode) == true

    /** `maps.google.<tld>` (true, any path) or `www.google.<tld>`/`google.<tld>` (false, `/maps…` only); else null. */
    private fun googleHost(host: String): Boolean? {
        if (host.startsWith("maps.google.")) return if (googleTld(host.removePrefix("maps.google."))) true else null
        val rest = host.removePrefix("www.")
        return if (rest.startsWith("google.") && googleTld(rest.removePrefix("google."))) false else null
    }

    private fun mapsPath(path: String) = path == "/maps" || path.startsWith("/maps/")

    private fun googleMap(link: LinkUrl): Boolean = when (googleHost(link.host)) {
        true -> if (link.path.isEmpty() || link.path == "/") {
            link.params.any { it.first in setOf("q", "query", "ll", "cid", "ftid") }
        } else {
            true
        }
        false -> mapsPath(link.path)
        null -> false
    }

    /** A long map link (`long_hosts`). */
    fun longMapUrl(url: String): Boolean {
        val link = LinkUrl.parse(url)?.takeIf { it.plainHttps } ?: return false
        return googleMap(link) || link.host == "maps.apple.com"
    }

    /** A feature id `0x<1-16 hex>:0x<1-16 hex>`, lowercased. */
    fun validFtid(text: String): String? {
        val parts = text.split(':')
        if (parts.size != 2) return null
        val halves = parts.map { half ->
            if (half.length < 3 || !half.substring(0, 2).equals("0x", ignoreCase = true)) return null
            val hex = half.substring(2)
            if (hex.length !in 1..16 || !hex.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
            hex.lowercase()
        }
        return "0x${halves[0]}:0x${halves[1]}"
    }

    private fun dataFtids(path: String): List<String> =
        LinkUrl.percentDecode(path).split('!').mapNotNull { piece -> piece.takeIf { it.startsWith("1s") }?.let { validFtid(it.substring(2)) } }

    /** A Google long link's feature id (`feature_ids`). */
    fun featureId(url: String): String? {
        val link = LinkUrl.parse(url)?.takeIf { it.plainHttps && googleMap(it) } ?: return null
        return featureIdOf(link)
    }

    private fun featureIdOf(link: LinkUrl): String? {
        link.params.firstOrNull { it.first == "ftid" }?.let { return validFtid(it.second) }
        if (link.path.contains("!1s") || link.path.contains("%211s")) {
            val found = dataFtids(link.path)
            (if (link.path.contains("/dir/")) found.lastOrNull() else found.firstOrNull())?.let { return it }
        }
        val cid = link.param("cid") ?: return null
        val n = cid.takeIf { c -> c.isNotEmpty() && c.all { it.isDigit() } }?.toULongOrNull() ?: return null
        return "0x0:0x${n.toString(16)}"
    }

    /** Whether [url] is a map link this phone hands to its handler: a short link, a long map link, or Google's
     * `maps.app.goo.gl/?link=` wrapper. http counts (it is upgraded first). */
    fun isMapLink(url: String): Boolean {
        val https = upgrade(url) ?: return false
        return shortLink(https) != null || longMapUrl(https) || wrapped(https) != null
    }

    /** http upgraded to https; anything else as it is, or null when it isn't a web link. */
    fun upgrade(url: String): String? {
        val trimmed = url.trim()
        return when {
            trimmed.startsWith("https://", ignoreCase = true) -> "https://" + trimmed.substring(8)
            trimmed.startsWith("http://", ignoreCase = true) -> "https://" + trimmed.substring(7)
            else -> null
        }
    }

    /** G1: `maps.app.goo.gl/?link=<encoded long link>`, the long link inside. */
    private fun wrapped(url: String): String? {
        val link = LinkUrl.parse(url)?.takeIf { it.plainHttps && it.host == "maps.app.goo.gl" } ?: return null
        if (link.path.isNotEmpty() && link.path != "/") return null
        return link.param("link")?.takeIf { longMapUrl(it) }
    }

    /** Which class of link a host is, for the report's `reach` (§7.5): `short`, `google` or `apple`. */
    fun hostClass(host: String): String? = when {
        host == "maps.app.goo.gl" || host == "goo.gl" -> "short"
        host == "maps.apple.com" || host == "maps.apple" -> "apple"
        googleHost(host) != null -> "google"
        else -> null
    }

    /** One https link of each class, for the report's `reach` (§7.5): what Android resolves them to. */
    val REACH_PROBES: Map<String, String> = mapOf(
        "short" to "https://maps.app.goo.gl/probe",
        "google" to "https://www.google.com/maps/place/probe",
        "apple" to "https://maps.apple.com/place?name=probe",
    )

    // --- Reading a link -------------------------------------------------------------------------------------------

    private fun latLon(text: String?): Pair<Double, Double>? {
        val parts = text?.split(',') ?: return null
        if (parts.size != 2) return null
        val lat = parts[0].trim().trim('+').toDoubleOrNull() ?: return null
        val lon = parts[1].trim().trim('+').toDoubleOrNull() ?: return null
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) lat to lon else null
    }

    private val PLUS_CODE = Regex("^[23456789CFGHJMPQRVWX]{2,8}\\+[23456789CFGHJMPQRVWX]{0,3}( .*)?$", RegexOption.IGNORE_CASE)

    /** Apple's dropped pin's own name, in the languages a share may carry. */
    private val DROPPED_PIN = setOf("marked location", "markert sted", "dropped pin", "markør")

    /** `!3d<lat>!4d<lon>`: the place's own point; for a route the destination's `!1d<lon>!2d<lat>` (the last). */
    private fun dataPoint(path: String): Pair<Double, Double>? {
        val decoded = LinkUrl.percentDecode(path)
        Regex("!3d(-?[0-9.]+)!4d(-?[0-9.]+)").findAll(decoded).lastOrNull()?.let { m ->
            latLon("${m.groupValues[1]},${m.groupValues[2]}")?.let { return it }
        }
        if (decoded.contains("/dir/")) {
            Regex("!1d(-?[0-9.]+)!2d(-?[0-9.]+)").findAll(decoded).lastOrNull()?.let { m ->
                latLon("${m.groupValues[2]},${m.groupValues[1]}")?.let { return it }
            }
        }
        return null
    }

    /** The `@lat,lon,<z>z` viewport of a path: the centre and the zoom. */
    private fun viewport(path: String): Triple<Double, Double, Int?>? {
        val m = Regex("@(-?[0-9.]+),(-?[0-9.]+)(?:,([0-9.]+)z)?").find(path) ?: return null
        val (lat, lon) = latLon("${m.groupValues[1]},${m.groupValues[2]}") ?: return null
        return Triple(lat, lon, m.groupValues[3].toDoubleOrNull()?.toInt())
    }

    private fun segmentText(segment: String): String = LinkUrl.formDecode(segment).trim()

    /** A link read offline (§2, §3): an https map link, or [MapParse.none] for anything else. */
    fun parse(raw: String): MapParse {
        val url = upgrade(raw) ?: return MapParse.none()
        wrapped(url)?.let { return parse(it) }
        shortLink(url)?.let { return MapParse(MapParse.Kind.RESOLVE, short = it) }
        val link = LinkUrl.parse(url)?.takeIf { it.plainHttps } ?: return MapParse.none()
        return when {
            googleMap(link) -> google(link)
            link.host == "maps.apple.com" -> apple(link)
            else -> MapParse.none()
        }
    }

    private fun google(link: LinkUrl): MapParse {
        val segments = link.path.split('/').filter { it.isNotEmpty() }
        if ("placelists" in segments || segments.getOrNull(1) == "d") {
            return MapParse(MapParse.Kind.LIST)
        }
        val ftid = featureIdOf(link)
        val view = viewport(link.path)
        var point: Pair<Double, Double>? = dataPoint(link.path)
        var text: String? = null
        for (name in listOf("q", "query")) {
            val value = link.param(name)?.trim()?.takeIf { it.isNotEmpty() } ?: continue
            val ll = latLon(value)
            if (ll != null) point = point ?: ll else if (text == null) text = value
        }
        val destination = link.param("destination") ?: link.param("daddr")
        destination?.trim()?.takeIf { it.isNotEmpty() }?.let { d ->
            val ll = latLon(d)
            if (ll != null) point = point ?: ll else if (text == null) text = d
        }
        var centre: Pair<Double, Double>? = latLon(link.param("sll")) ?: latLon(link.param("center"))
        val ll = latLon(link.param("ll"))
        if (ll != null) {
            if (text == null) point = point ?: ll else centre = centre ?: ll
        }
        val placeAt = segments.indexOfFirst { it == "place" || it == "search" }
        val placeSegment = segments.getOrNull(placeAt + 1)?.takeIf { placeAt >= 0 && !it.startsWith("@") && !it.startsWith("data=") }
        val dirAt = segments.indexOf("dir")
        val dirSegment = if (dirAt >= 0) segments.drop(dirAt + 1).lastOrNull { !it.startsWith("@") && !it.startsWith("data=") } else null
        (placeSegment ?: dirSegment)?.let { segment ->
            val value = segmentText(segment)
            val segPoint = latLon(value)
            if (segPoint != null) {
                point = point ?: segPoint
            } else if (value.isNotEmpty() && text == null) {
                if (PLUS_CODE.matches(value)) return if (point != null) MapParse.pin(point!!, view?.third, null) else MapParse(MapParse.Kind.NONE)
                text = value
            }
        }
        // G4's one fallback: the `@` viewport, only when a place segment exists.
        if (point == null && placeSegment != null && view != null && placeAt >= 0 && segments[placeAt] == "place") {
            point = view.first to view.second
        }
        val z = view?.third
        val p = point
        return when {
            p != null && ftid != null && !hasAddress(text) -> MapParse(MapParse.Kind.LOOKUP, p.first, p.second, z, text, ftid, query = MapLinks.queryOf(text), centre = centre)
            p != null -> MapParse.pin(p, z, text)
            ftid != null -> MapParse(MapParse.Kind.LOOKUP, z = z, label = text, ftid = ftid, query = MapLinks.queryOf(text), centre = centre ?: MapLinks.cellCentre(ftid))
            text != null -> MapParse.search(MapLinks.queryOf(text)!!, centre)
            view != null -> MapParse.pin(view.first to view.second, view.third, null)
            centre != null && link.param("map_action") == "map" -> MapParse.pin(centre, null, null)
            else -> MapParse(MapParse.Kind.NONE)
        }
    }

    /** The text names an address: a part after the first comma holds a digit (G4's rule). */
    fun hasAddress(text: String?): Boolean = text?.substringAfter(',', "")?.any { it.isDigit() } == true

    private fun apple(link: LinkUrl): MapParse {
        val point = latLon(link.param("ll")) ?: latLon(link.param("coordinate")) ?: latLon(link.param("q"))
            ?: latLon(link.param("destination") ?: link.param("daddr"))
        val name = link.param("name")?.trim()?.takeIf { it.isNotEmpty() && it.lowercase() !in DROPPED_PIN }
        val address = link.param("address")?.trim()?.takeIf { it.isNotEmpty() }
        val qText = link.param("q")?.trim()?.takeIf { it.isNotEmpty() && latLon(it) == null }
        val destText = (link.param("destination") ?: link.param("daddr"))?.trim()?.takeIf { it.isNotEmpty() && latLon(it) == null }
        val label = when {
            name != null && address != null -> if (address.startsWith("$name, ") || address == name) address else "$name, $address"
            name != null -> name
            address != null -> address
            else -> qText ?: destText
        }
        val centre = latLon(link.param("sll")) ?: latLon(link.param("near")) ?: latLon(link.param("center"))
        val z = link.param("z")?.toDoubleOrNull()?.toInt()
        return when {
            point != null -> MapParse.pin(point, z, label)
            // An address alone is searched whole; "name, address" by its address (§2.1).
            label != null -> MapParse.search(if (name == null && address != null) address else MapLinks.queryOf(label)!!, centre)
            else -> MapParse(MapParse.Kind.NONE)
        }
    }
}
