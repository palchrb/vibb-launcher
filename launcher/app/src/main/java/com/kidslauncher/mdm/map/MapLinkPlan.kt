package com.kidslauncher.mdm.map

import kotlinx.serialization.Serializable

/** The target app's state on the phone (§7.1): only [ALLOWED] starts. */
enum class TargetState(val wire: String) { ALLOWED("allowed"), HIDDEN("hidden"), SUSPENDED("suspended"), MISSING("missing") }

/** The kid's calm notes (§7.4). */
enum class MapNote { TARGET_MISSING, TARGET_SUSPENDED, NO_PLACE, LIST, DEAD, RETRY }

/** What [MapLinkActivity] does next. */
sealed interface MapStep {
    /** Finish, showing nothing (a time rule or the keyguard). */
    data object Nothing : MapStep
    /** Start the target with this `geo:` URI. */
    data class Open(val uri: String) : MapStep
    /** Ask the server (`POST /api/devices/map-links/resolve`). */
    data class Ask(val request: MapResolveRequest) : MapStep
    /** Show a note; [sync] asks for a policy sync too (a 409 `off`). */
    data class Note(val note: MapNote, val sync: Boolean = false) : MapStep
}

/** The resolve route's request: a short link, or a place id with the link's text (the server's `text` check only). */
@Serializable
data class MapResolveRequest(val url: String? = null, val ftid: String? = null, val text: String? = null)

/** The resolve route's answers: 200 `{url?, place?}`, else `{error}`. */
@Serializable
data class MapResolveAnswer(val url: String? = null, val place: MapPlace? = null, val error: String? = null)

@Serializable
data class MapPlace(val lat: Double, val lon: Double, val name: String? = null, val address: String? = null)

/** The text sent with a place id: the link's text, cut to the server's 200. */
private fun requestText(text: String?) = text?.take(200)

/**
 * The first step for a tapped link (§7.1, each a `mapLinkPlan` row): nothing over a time rule or the keyguard; a note
 * when the target can't start; then what the link says.
 */
fun mapLinkPlan(locked: Boolean, target: TargetState, parsed: MapParse): MapStep = when {
    locked -> MapStep.Nothing
    target == TargetState.MISSING || target == TargetState.HIDDEN -> MapStep.Note(MapNote.TARGET_MISSING)
    target == TargetState.SUSPENDED -> MapStep.Note(MapNote.TARGET_SUSPENDED)
    else -> when (parsed.kind) {
        MapParse.Kind.PIN, MapParse.Kind.SEARCH -> MapStep.Open(parsed.geoUri()!!)
        MapParse.Kind.RESOLVE -> MapStep.Ask(MapResolveRequest(url = parsed.short))
        MapParse.Kind.LOOKUP -> MapStep.Ask(MapResolveRequest(ftid = parsed.ftid, text = requestText(parsed.label)))
        MapParse.Kind.NONE -> MapStep.Note(MapNote.NO_PLACE)
        MapParse.Kind.LIST -> MapStep.Note(MapNote.LIST)
    }
}

/**
 * The phone's action for the server's answer (`map_links/contract.json`'s `resolve.answers[].phone`): `pin` (a place),
 * `parse` (the long link alone), `fallback` (nothing found), `retry_sync` (409), `dead` (422), `retry` (a short link
 * the server couldn't resolve now). [status] 0 = no answer (offline, timed out).
 */
fun mapLinkAnswer(status: Int, answer: MapResolveAnswer?, shortLink: Boolean): String = when {
    status == 200 && answer?.place != null -> "pin"
    status == 200 && answer?.url != null -> "parse"
    status == 200 -> "fallback"
    status == 409 -> "retry_sync"
    status == 422 -> "dead"
    shortLink -> "retry"
    else -> "fallback"
}

/** The step after the server's answer: the pin with the place's name and address (the link's own point kept when it
 * has one), the long link read, the fallback search, or a note. */
fun mapLinkAfter(status: Int, answer: MapResolveAnswer?, parsed: MapParse): MapStep {
    val shortLink = parsed.kind == MapParse.Kind.RESOLVE
    val long = answer?.url?.let(MapLinkForms::parse)
    return when (mapLinkAnswer(status, answer, shortLink)) {
        "pin" -> {
            val place = answer!!.place!!
            val own = listOf(parsed, long).firstOrNull { it?.lat != null && it.lon != null }
            val lat = own?.lat ?: place.lat
            val lon = own?.lon ?: place.lon
            val label = MapLinks.placeLabel(place.name, place.address) ?: own?.label ?: parsed.label ?: long?.label
            MapStep.Open(MapLinks.pinUri(lat, lon, own?.z ?: parsed.z, label))
        }
        "parse" -> if (long == null) MapStep.Note(MapNote.NO_PLACE) else when (long.kind) {
            MapParse.Kind.PIN, MapParse.Kind.SEARCH -> MapStep.Open(long.geoUri()!!)
            MapParse.Kind.LOOKUP -> if (long.lat != null) {
                MapStep.Open(MapLinks.pinUri(long.lat, long.lon!!, long.z, long.label?.let(MapLinks::cutLabel)))
            } else {
                long.fallback()?.let { MapStep.Open(it.geoUri()!!) } ?: MapStep.Note(MapNote.NO_PLACE)
            }
            MapParse.Kind.LIST -> MapStep.Note(MapNote.LIST)
            MapParse.Kind.RESOLVE, MapParse.Kind.NONE -> MapStep.Note(MapNote.NO_PLACE)
        }
        "fallback" -> when {
            parsed.lat != null -> MapStep.Open(MapLinks.pinUri(parsed.lat, parsed.lon!!, parsed.z, parsed.label?.let(MapLinks::cutLabel)))
            parsed.fallback() != null -> MapStep.Open(parsed.fallback()!!.geoUri()!!)
            status == 200 -> MapStep.Note(MapNote.NO_PLACE)
            else -> MapStep.Note(MapNote.RETRY)
        }
        "retry_sync" -> MapStep.Note(MapNote.RETRY, sync = true)
        "dead" -> MapStep.Note(MapNote.DEAD)
        else -> MapStep.Note(MapNote.RETRY)
    }
}

/** "Open in browser" (§7.2): only in a pause or the parent code's override, only to a named browser that isn't us -
 * the original link, never the resolved one. */
fun browserOffered(windowOpen: Boolean, browser: String?, ownPackage: String): Boolean =
    windowOpen && browser != null && browser != ownPackage
