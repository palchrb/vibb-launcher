package me.vibb.music.sync

import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.DownloadState
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.keepSet
import me.vibb.music.logic.mayRetry
import me.vibb.music.logic.removedEntries
import me.vibb.music.logic.retryDelayFor
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
 * The keep/prune rule (logic/Offline.kt) over the whole library: per NRK/RSS entry with a parsed
 * listing the wanted downloads and the copies outside its keep set (finished or partial), plus every
 * copy of an entry the library no longer names at all (an entry it names but the app can't read
 * keeps everything, qa-21-step2-code H1). An entry without a listing keeps what it has (QA #3); a
 * failed item waits for its retry delay or a new url (21b §6, `mayRetry`). Pure; OfflineTest.
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
        val version = snap.listingVersions[entry.id] ?: continue
        val listed = snap.listed[entry.id].orEmpty()
        val entryRows = byEntry[entry.id].orEmpty()
        val have = entryRows.filter { it.done }.map { it.itemKey }.toSet()
        val bookmark = snap.bookmarks[entry.key]?.itemKey
        val keep = keepSet(entry, listed.map { it.key }, bookmark)
        prune += entryRows.filter { it.itemKey !in keep }
        val rowOf = entryRows.associateBy { it.itemKey }
        val byKey = listed.associateBy { it.key }
        for (key in wantedDownloads(keep, listed.map { KeepItem(it.key, it.url != null) }, have, bookmark)) {
            val item = byKey.getValue(key)
            val row = rowOf[key]
            val delay = { code: String?, attempts: Int -> retryDelayFor(entry, code, attempts) }
            if (row == null || mayRetry(row.failedUrl, row.failedCode, row.failedAt, row.attempts, item.url!!, nowMs, delay)) {
                wanted += WantedDownload(entry, item)
            } else {
                val at = row.failedAt!! + delay(row.failedCode, row.attempts)
                nextRetryAt = minOf(nextRetryAt ?: at, at)
            }
        }
        val wantable = keep.filter { k -> byKey[k]?.url != null || k in have }
        states += DownloadState(entry.id, version, wantable.count { it in have }, wantable.size, null)
    }
    return DownloadPlan(wanted, prune, states, nextRetryAt)
}
