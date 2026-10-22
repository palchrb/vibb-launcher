package me.vibb.music.logic

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/*
 * What the app reports about itself (design 21 §1.3 `music_state`, 21b §4.3): built here and kept
 * in the app (filesDir/music/report.json) until step 3's bridge hands it to the launcher with
 * `reportState`. Never positions or "now playing". Pure; ReportTest.
 */

const val ITEM_ERRORS_MAX = 50
const val ENTRY_ERRORS_MAX = 50
const val DOWNLOADS_MAX = 200

/** At most this many Sonos rooms are reported, their names cut to [SONOS_ROOM_NAME_MAX] (design 26a §5.5). */
const val SONOS_ROOMS_MAX = 32
const val SONOS_ROOM_NAME_MAX = 40

/** A Sonos speaker's uid as the server accepts it (design 26a §5.5). */
private val SONOS_UID = Regex("RINCON_[A-Z0-9]{12,20}")
/** Unicode White_Space, spelled out: Android's ICU regex rejects `(?U)` (a PatternSyntaxException that killed every
 * sync's report on the phone, while the JVM tests passed). */
private val SPACES = Regex("[\\u0009-\\u000D\\u0020\\u0085\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000]+")

/** One Sonos room the speaker cache knows: what the parent can scope the phone to (design 26a §5.5). */
data class ReportedRoom(val uid: String, val name: String)

/**
 * `music_state.sonos_rooms` from the speaker cache's players (uid -> name): Sonos-shaped uids only,
 * names exactly as the server's `cut_title` keeps them - split on Unicode White_Space and joined
 * with one space, then control characters (Cc) out, then cut to [SONOS_ROOM_NAME_MAX] characters, in
 * that order (qa-26a-step3c-code L6: so the stored report equals the sent one) - by name, at most
 * [SONOS_ROOMS_MAX]. Never an ip.
 */
fun reportedRooms(players: Map<String, String?>): List<ReportedRoom> =
    players.filterKeys { SONOS_UID.matches(it) }
        .map { (uid, name) -> ReportedRoom(uid, roomName(name)) }
        .sortedWith(compareBy({ it.name.lowercase() }, { it.uid }))
        .take(SONOS_ROOMS_MAX)

private fun roomName(name: String?): String {
    // Rust's split_whitespace (White_Space, not Java's isWhitespace, which also takes U+001C-U+001F), then is_control
    val clean = name.orEmpty().split(SPACES).filter { it.isNotEmpty() }.joinToString(" ").filterNot { Character.isISOControl(it) }
    val points = clean.codePoints().limit(SONOS_ROOM_NAME_MAX.toLong()).toArray()
    return String(points, 0, points.size)
}

/** A failed download or stream of a listed url (21b §4.3). */
data class ItemError(val entry: Long, val version: String, val item: String, val error: String)

/** The app's own trouble with an entry (`bad_listing`). */
data class EntryError(val entry: Long, val error: String)

/** One entry's downloads: the listing version applied, have / want, and what they wait for. */
data class DownloadState(val entry: Long, val version: String, val have: Int, val want: Int, val waiting: Waiting?)

/**
 * Each entry's `waiting` while it still wants downloads (21b §4.3; design 26b §4.5, QA M4): [shared]
 * (the network and the 500 MB storage rule every entry gets), but for a Storytel entry ([storytel]
 * ids) `storytelWaiting` - `login` while [account] isn't `ok`, `storage` below the 1.5 GB floor
 * ([freeBytes] of noBackupFilesDir), else [shared]. An entry with everything on the phone waits for
 * nothing.
 */
fun reportWaiting(states: List<DownloadState>, storytel: Set<Long>, shared: Waiting?, account: String, freeBytes: Long): List<DownloadState> =
    states.map { s ->
        when {
            s.have >= s.want -> s
            s.entry in storytel -> s.copy(waiting = storytelWaiting(account, freeBytes, shared))
            else -> s.copy(waiting = shared)
        }
    }

/** `http_<code>` for an HTTP status from the source, `bad_media` for media that isn't audio, else `network`. */
fun itemErrorCode(httpStatus: Int?, badMedia: Boolean): String = when {
    httpStatus != null && httpStatus in 100..599 -> "http_$httpStatus"
    badMedia -> "bad_media"
    else -> "network"
}

/** [log] (oldest first) with [error]: one row per item, the latest, and at most [ITEM_ERRORS_MAX] (the newest kept). */
fun addItemError(log: List<ItemError>, error: ItemError): List<ItemError> =
    (log.filterNot { it.entry == error.entry && it.item == error.item } + error).takeLast(ITEM_ERRORS_MAX)

/** The report, in `music_state`'s field names. */
fun stateReport(
    versionCode: Long,
    libraryVersion: String?,
    cacheBytes: Long,
    /** The audio cache (`MediaCache.totalBytes`): what the parent's storage limit counts (design 30 §1.10). */
    audioBytes: Long,
    /** The played extras' bytes in it (design 30 §1.8); 0 until step 3b keeps any. */
    extraBytes: Long,
    entryErrors: List<EntryError>,
    itemErrors: List<ItemError>,
    downloads: List<DownloadState>,
    /** The phone's Storytel session: `none`, `refused` or `ok` (design 26b §4.5, design 58 §5.2); never a JWT or a URL. */
    storytel: String? = null,
    /** The rooms the speaker cache knows ([reportedRooms]). */
    sonosRooms: List<ReportedRoom> = emptyList(),
    /**
     * The phone's last Spotify outcome (design 26c §4.4): one of `none`, `ok`, `reauthorize`,
     * `refused`, `not_premium`, `no_widevine`, `unreachable`, `quota` - never a token, an account or a
     * device id. The sync reads it from `spotify_state.json` (`SpotifyOutcome.reported`: `none` without
     * the file); a track the account can't play is an `item_errors` row (`unplayable`), never this.
     */
    spotify: String = "none",
): JsonObject = buildJsonObject {
    put("version_code", versionCode)
    put("library_version", libraryVersion)
    put("cache_bytes", cacheBytes)
    // Always sent, 0 included (design 30 §1.10): their presence tells the PWA this app reads both switches.
    put("audio_bytes", audioBytes)
    put("extra_bytes", extraBytes)
    put("downloads_waiting", downloads.sumOf { (it.want - it.have).coerceAtLeast(0) })
    put("entry_errors", buildJsonArray {
        for (e in entryErrors.take(ENTRY_ERRORS_MAX)) add(buildJsonObject { put("entry", e.entry); put("error", e.error) })
    })
    put("item_errors", buildJsonArray {
        for (e in itemErrors.takeLast(ITEM_ERRORS_MAX)) {
            add(buildJsonObject { put("entry", e.entry); put("version", e.version); put("item", e.item); put("error", e.error) })
        }
    })
    put("storytel", storytel)
    put("spotify", spotify)
    put("downloads", buildJsonArray {
        for (d in downloads.take(DOWNLOADS_MAX)) {
            add(buildJsonObject {
                put("entry", d.entry); put("version", d.version); put("have", d.have); put("want", d.want)
                put("waiting", d.waiting?.wire)
            })
        }
    })
    put("sonos_rooms", buildJsonArray {
        for (r in sonosRooms.take(SONOS_ROOMS_MAX)) add(buildJsonObject { put("uid", r.uid); put("name", r.name) })
    })
}
