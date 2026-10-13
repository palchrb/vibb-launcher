package me.vibb.music.sync

import android.content.Context
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.WantedArt
import me.vibb.music.logic.keepSet
import me.vibb.music.logic.nowWindow
import me.vibb.music.logic.storytelKeepSet
import me.vibb.music.logic.storytelWanted
import me.vibb.music.logic.wantedArt
import me.vibb.music.logic.wantedDownloads
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.playback.PlayerHub

/**
 * The want set over the whole library (design 27 §2.2, logic/EpisodeArt.kt): the now-playing
 * window, then per NRK/RSS entry the art of its keep set (the bookmarked item and the next first,
 * then newest first, as the downloads go; a Storytel series' kept books in its download order, the
 * bookmarked book first, then reading order - `downloadPlan`'s rule, design 26b §3.3), then the Nytt
 * tray. Own entries' art is in the library's covers. The played extras' pictures are kept with the keep
 * sets' (design 30 §1.11, L3: a kept episode's picture stays as long as its audio). Pure; EpisodeArtTest.
 */
fun artWant(snap: Snapshot, nowQueue: List<PlayItem>, nowIndex: Int, held: Set<String>, recent: List<String>, extras: List<DownloadRow> = emptyList()): WantedArt {
    val lib = snap.library
    val kept = mutableListOf<String?>()
    for (entry in lib?.entries.orEmpty()) {
        if (entry.source == Source.OWN) continue
        val listed = snap.listed[entry.id].orEmpty()
        val bookmark = snap.bookmarks[entry.key]?.itemKey
        val keys = listed.map { it.key }
        val byKey = listed.associateBy { it.key }
        if (entry.source == Source.STORYTEL) {
            // The books the phone keeps, as downloadPlan keeps and fetches them (no url: minted per download).
            val keep = storytelKeepSet(entry, keys, bookmark)
            kept += storytelWanted(keep, keys, emptySet(), bookmark).map { byKey[it]?.artHash }
            continue
        }
        val keep = keepSet(entry, keys, bookmark)
        // Every kept item, a withdrawn one with its copy too: no url filter here.
        kept += wantedDownloads(keep, keys.map { KeepItem(it, true) }, emptySet(), bookmark).map { byKey[it]?.artHash }
    }
    kept += extras.map { snap.item(it.entryId, it.itemKey)?.artHash }
    val news = snap.news.map { snap.item(it.entryId, it.key)?.artHash }
    return wantedArt(nowWindow(nowQueue, nowIndex).map { it.artHash }, kept, news, recent, held, lib?.covers.orEmpty())
}

/** The queue the player holds (live), else the stored one, with its current index; empty when there is none. */
fun nowQueue(snap: Snapshot, hub: PlayerHub.State, row: NowPlayingRow?): Pair<List<PlayItem>, Int> {
    val (entryId, keys, index) = when {
        hub.live && hub.entryId != null -> Triple(hub.entryId, hub.keys, hub.index)
        row != null -> Triple(row.entryId, row.keys.split('\n').filter { it.isNotEmpty() }, row.index)
        else -> return emptyList<PlayItem>() to -1
    }
    val current = keys.getOrNull(index)
    val queue = keys.mapNotNull { snap.item(entryId, it) }
    return queue to queue.indexOfFirst { it.key == current }
}

/** The want set now, from the store and the player (off the main thread). */
object ArtWants {
    suspend fun compute(context: Context): WantedArt {
        val store = Store.get(context)
        val snap = store.snapshot()
        val (queue, index) = nowQueue(snap, PlayerHub.state.value, store.db.nowPlaying())
        val extras = downloadPlan(snap, store.db.downloads(), System.currentTimeMillis(), PlayerHub.heldLocal).extras
        return artWant(snap, queue, index, store.coverHashes(), store.recentArt(), extras)
    }
}
