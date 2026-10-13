package me.vibb.music.sync

import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.DownloadState
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.fetchable
import me.vibb.music.logic.keepSet
import me.vibb.music.logic.mayRetry
import me.vibb.music.logic.removedEntries
import me.vibb.music.logic.retryDelayFor
import me.vibb.music.logic.sourceId
import me.vibb.music.logic.storytelKeepSet
import me.vibb.music.logic.storytelWanted
import me.vibb.music.logic.wantedDownloads
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source

/** One item the keep rule wants and the phone doesn't have yet. */
data class WantedDownload(val entry: Entry, val item: PlayItem)

/**
 * What a sync does with the downloads: fetch, delete, what it reports, and when an item now
 * waiting on its retry delay may be tried ([nextRetryAt], null = none waits).
 */
data class DownloadPlan(val wanted: List<WantedDownload>, val prune: List<DownloadRow>, val states: List<DownloadState>, val nextRetryAt: Long?)

/**
 * The keep/prune rule (logic/Offline.kt) over the whole library: per NRK/RSS/Storytel entry with a
 * parsed listing the wanted downloads and the copies outside its keep set (finished or partial), plus
 * every copy of an entry the library no longer names at all (an entry it names but the app can't read
 * keeps everything, qa-21-step2-code H1). An entry without a listing keeps what it has (QA #3); a
 * failed item waits for its retry delay or a new source (21b §6, `mayRetry`). A Storytel entry (design
 * 26b §3.3) keeps the first N books of the reading order plus the bookmarked book and the next
 * (`storytelKeepSet`), fetches the bookmarked book first and then in reading order (`storytelWanted`),
 * and names a book by its source id (`storytel:book:<cid>`), never by a URL minted per attempt, with
 * Storytel's retry classes (`retryDelayFor`). Pure; OfflineTest, StorytelDownloadTest.
 */
fun downloadPlan(snap: Snapshot, rows: List<DownloadRow>, nowMs: Long): DownloadPlan {
    val lib = snap.library
    val byEntry = rows.groupBy { it.entryId }
    val prune = mutableListOf<DownloadRow>()
    val wanted = mutableListOf<WantedDownload>()
    val states = mutableListOf<DownloadState>()
    var nextRetryAt: Long? = null
    removedEntries(byEntry.keys, lib?.entryIds).forEach { prune += byEntry.getValue(it) }
    for (entry in lib?.entries.orEmpty()) {
        if (entry.source == Source.OWN) continue
        // Design 26c §4.2, §4.4: a Spotify entry streams - no keep set, no download, no `downloads` row in
        // the report. A copy can't exist (nothing is ever fetched for one); if one somehow did, it goes.
        if (entry.source == Source.SPOTIFY) {
            prune += byEntry[entry.id].orEmpty()
            continue
        }
        val version = snap.listingVersions[entry.id] ?: continue
        val listed = snap.listed[entry.id].orEmpty()
        val listedKeys = listed.map { it.key }
        val entryRows = byEntry[entry.id].orEmpty()
        val have = entryRows.filter { it.done }.map { it.itemKey }.toSet()
        val bookmark = snap.bookmarks[entry.key]?.itemKey
        val storytel = entry.source == Source.STORYTEL
        val keep = if (storytel) storytelKeepSet(entry, listedKeys, bookmark) else keepSet(entry, listedKeys, bookmark)
        prune += entryRows.filter { it.itemKey !in keep }
        val rowOf = entryRows.associateBy { it.itemKey }
        val byKey = listed.associateBy { it.key }
        val order = if (storytel) {
            storytelWanted(keep, listedKeys, have, bookmark)
        } else {
            wantedDownloads(keep, listed.map { KeepItem(it.key, it.url != null, fetchable(entry, it)) }, have, bookmark)
        }
        val delay = { code: String?, attempts: Int -> retryDelayFor(entry, code, attempts) }
        for (key in order) {
            val item = byKey.getValue(key)
            val source = sourceId(entry, item) ?: continue // fetchable: a url or a Storytel book
            val row = rowOf[key]
            if (row == null || mayRetry(row.failedUrl, row.failedCode, row.failedAt, row.attempts, source, nowMs, delay)) {
                wanted += WantedDownload(entry, item)
            } else {
                val at = row.failedAt!! + delay(row.failedCode, row.attempts)
                nextRetryAt = minOf(nextRetryAt ?: at, at)
            }
        }
        val wantable = keep.filter { k -> byKey[k]?.let { fetchable(entry, it) } == true || k in have }
        states += DownloadState(entry.id, version, wantable.count { it in have }, wantable.size, null)
    }
    return DownloadPlan(wanted, prune, states, nextRetryAt)
}
