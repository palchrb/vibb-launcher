package me.vibb.music.sync

import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.DownloadState
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.keepSet
import me.vibb.music.logic.removedEntries
import me.vibb.music.logic.wantedDownloads
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source

/** One item the keep rule wants and the phone doesn't have yet. */
data class WantedDownload(val entry: Entry, val item: PlayItem)

/** What a sync does with the downloads: fetch, delete, and what it reports. */
data class DownloadPlan(val wanted: List<WantedDownload>, val prune: List<DownloadRow>, val states: List<DownloadState>)

/**
 * The keep/prune rule (logic/Offline.kt) over the whole library: per NRK/RSS entry with a parsed
 * listing the wanted downloads and the copies outside its keep set (finished or partial), plus every
 * copy of an entry the parsed library no longer has. An entry without a listing keeps what it has
 * (QA #3); an item whose url failed with a 4xx waits for a new url (21b §6). Pure; OfflineTest.
 */
fun downloadPlan(snap: Snapshot, rows: List<DownloadRow>): DownloadPlan {
    val lib = snap.library
    val byEntry = rows.groupBy { it.entryId }
    val prune = mutableListOf<DownloadRow>()
    val wanted = mutableListOf<WantedDownload>()
    val states = mutableListOf<me.vibb.music.logic.DownloadState>()
    val removed = removedEntries(byEntry.keys, lib?.entries?.map { it.id }?.toSet())
    removed.forEach { prune += byEntry.getValue(it) }
    for (entry in lib?.entries.orEmpty()) {
        if (entry.source == Source.OWN) continue
        val version = snap.listingVersions[entry.id] ?: continue
        val listed = snap.listed[entry.id].orEmpty()
        val entryRows = byEntry[entry.id].orEmpty()
        val have = entryRows.filter { it.done }.map { it.itemKey }.toSet()
        val bookmark = snap.bookmarks[entry.key]?.itemKey
        val keep = keepSet(entry, listed.map { it.key }, bookmark)
        prune += entryRows.filter { it.itemKey !in keep && it.itemKey != bookmark }
        val failed = entryRows.filter { it.failedUrl != null }.associate { it.itemKey to it.failedUrl }
        val byKey = listed.associateBy { it.key }
        wanted += wantedDownloads(keep, listed.map { KeepItem(it.key, it.url != null) }, have, bookmark)
            .map { byKey.getValue(it) }
            .filter { failed[it.key] == null || failed[it.key] != it.url }
            .map { WantedDownload(entry, it) }
        val wantable = keep.filter { k -> byKey[k]?.url != null || k in have }
        states += DownloadState(entry.id, version, wantable.count { it in have }, wantable.size, null)
    }
    return DownloadPlan(wanted, prune, states)
}
