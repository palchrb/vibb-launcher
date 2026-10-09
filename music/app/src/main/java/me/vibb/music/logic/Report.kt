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

/** A failed download or stream of a listed url (21b §4.3). [sync]: the sync run it happened in. */
data class ItemError(val entry: Long, val version: String, val item: String, val error: String, val sync: Long)

/** The app's own trouble with an entry (`bad_listing`). */
data class EntryError(val entry: Long, val error: String)

/** One entry's downloads: the listing version applied, have / want, and what they wait for. */
data class DownloadState(val entry: Long, val version: String, val have: Int, val want: Int, val waiting: Waiting?)

/** `http_<code>` for an HTTP status from the source, `bad_media` for media that isn't audio, else `network`. */
fun itemErrorCode(httpStatus: Int?, badMedia: Boolean): String = when {
    httpStatus != null && httpStatus in 100..599 -> "http_$httpStatus"
    badMedia -> "bad_media"
    else -> "network"
}

/** [log] with [error]: at most once per item per sync (the first one stands), the latest per
 * item otherwise, and at most [ITEM_ERRORS_MAX] (the newest kept). */
fun addItemError(log: List<ItemError>, error: ItemError): List<ItemError> {
    if (log.any { it.entry == error.entry && it.item == error.item && it.sync == error.sync }) return log
    return (log.filterNot { it.entry == error.entry && it.item == error.item } + error).takeLast(ITEM_ERRORS_MAX)
}

/** The report, in `music_state`'s field names. */
fun stateReport(
    versionCode: Long,
    libraryVersion: String?,
    cacheBytes: Long,
    entryErrors: List<EntryError>,
    itemErrors: List<ItemError>,
    downloads: List<DownloadState>,
): JsonObject = buildJsonObject {
    put("version_code", versionCode)
    put("library_version", libraryVersion)
    put("cache_bytes", cacheBytes)
    put("downloads_waiting", downloads.sumOf { (it.want - it.have).coerceAtLeast(0) })
    put("entry_errors", buildJsonArray {
        for (e in entryErrors.take(ENTRY_ERRORS_MAX)) add(buildJsonObject { put("entry", e.entry); put("error", e.error) })
    })
    put("item_errors", buildJsonArray {
        for (e in itemErrors.takeLast(ITEM_ERRORS_MAX)) {
            add(buildJsonObject { put("entry", e.entry); put("version", e.version); put("item", e.item); put("error", e.error) })
        }
    })
    put("downloads", buildJsonArray {
        for (d in downloads.take(DOWNLOADS_MAX)) {
            add(buildJsonObject {
                put("entry", d.entry); put("version", d.version); put("have", d.have); put("want", d.want)
                put("waiting", d.waiting?.wire)
            })
        }
    })
}
