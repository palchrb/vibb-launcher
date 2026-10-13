package me.vibb.music.sync

import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.DownloadState
import me.vibb.music.logic.ItemRef
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
 * waiting on its retry delay may be tried ([nextRetryAt], null = none waits). [extras]: the played
 * extras (design 30 §1.8), kept and never fetched - eviction picks from them; [wantsStorytel]: a
 * Storytel book is still wanted (the free floor is Storytel's then, `extrasFloor`).
 */
data class DownloadPlan(
    val wanted: List<WantedDownload>,
    val prune: List<DownloadRow>,
    val states: List<DownloadState>,
    val nextRetryAt: Long?,
    val extras: List<DownloadRow> = emptyList(),
    val wantsStorytel: Boolean = false,
)

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
 *
 * Design 30 §1.6, §1.8: a row outside the keep set that the phone played (`playedAt`), whose item is
 * still listed, of an NRK/RSS entry above depth 0, is a played extra - neither pruned nor fetched. The
 * [held] items (the phone's player's current one and the next, `PlayerHub.heldLocal`) are never pruned,
 * and the held ones that are progressive and not done are never fetched: the downloader would wait on
 * the player's lock.
 */
fun downloadPlan(snap: Snapshot, rows: List<DownloadRow>, nowMs: Long, held: Set<ItemRef> = emptySet()): DownloadPlan {
    val lib = snap.library
    val byEntry = rows.groupBy { it.entryId }
    val prune = mutableListOf<DownloadRow>()
    val extras = mutableListOf<DownloadRow>()
    val wanted = mutableListOf<WantedDownload>()
    val states = mutableListOf<DownloadState>()
    var nextRetryAt: Long? = null
    var wantsStorytel = false
    fun held(row: DownloadRow) = ItemRef(row.entryId, row.itemKey) in held
    removedEntries(byEntry.keys, lib?.entryIds).forEach { id -> prune += byEntry.getValue(id).filterNot(::held) }
    for (entry in lib?.entries.orEmpty()) {
        if (entry.source == Source.OWN) continue
        val version = snap.listingVersions[entry.id] ?: continue
        val listed = snap.listed[entry.id].orEmpty()
        val listedKeys = listed.map { it.key }
        val entryRows = byEntry[entry.id].orEmpty()
        val have = entryRows.filter { it.done }.map { it.itemKey }.toSet()
        val bookmark = snap.bookmarks[entry.key]?.itemKey
        val storytel = entry.source == Source.STORYTEL
        val keep = if (storytel) storytelKeepSet(entry, listedKeys, bookmark) else keepSet(entry, listedKeys, bookmark)
        val listedSet = listedKeys.toSet()
        for (row in entryRows.filter { it.itemKey !in keep }) {
            val extra = row.playedAt != null && row.itemKey in listedSet && entry.cache != 0 && !storytel
            when {
                extra -> extras += row
                !held(row) -> prune += row
            }
        }
        val rowOf = entryRows.associateBy { it.itemKey }
        val byKey = listed.associateBy { it.key }
        val order = if (storytel) {
            storytelWanted(keep, listedKeys, have, bookmark)
        } else {
            wantedDownloads(keep, listed.map { KeepItem(it.key, it.url != null, fetchable(entry, it)) }, have, bookmark)
        }
        if (storytel && order.isNotEmpty()) wantsStorytel = true
        val delay = { code: String?, attempts: Int -> retryDelayFor(entry, code, attempts) }
        for (key in order) {
            val item = byKey.getValue(key)
            val source = sourceId(entry, item) ?: continue // fetchable: a url or a Storytel book
            // Busy (design 30 §1.6): the player holds it open and the downloader would block on its lock.
            if (ItemRef(entry.id, key) in held && !item.hls) continue
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
    return DownloadPlan(wanted, prune, states, nextRetryAt, extras, wantsStorytel)
}
