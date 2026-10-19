package me.vibb.music.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import me.vibb.music.bridge.LiveBridge
import me.vibb.music.logic.listArtMissing
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.data.Store
import me.vibb.music.logic.KeepItem
import me.vibb.music.logic.SPOTIFY_ART_AHEAD
import me.vibb.music.logic.WantedArt
import me.vibb.music.logic.coverTapPlan
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.keepSet
import me.vibb.music.logic.artSkipsAfter
import me.vibb.music.logic.skippedArt
import me.vibb.music.logic.nowArt
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
 * tray; in `now` (streaming's gate, the user's rule of 2026-10-19) after the window and the queue's next
 * items ([nowArt]), the playing Spotify list-mode entry's next [SPOTIFY_ART_AHEAD] tracks (every other one's in
 * `later`, qa-art-asap-code M3) in the order a cover tap plays them (from the bookmark when resume is on; an unstarted entry from its first; a shuffled one in
 * list order, since its permutation can't be foreseen). Own entries'
 * art is in the library's covers. The played extras' pictures are kept with the keep sets' (design 30
 * §1.11, L3: a kept episode's picture stays as long as its audio). Last, every item's picture of every
 * ticked entry in its play order (the user, 2026-10-19: "alle bilder lastes ned ASAP så lenge den er på
 * wifi"): always in the prune's universe, in `later` only when [unmetered]. Pure; EpisodeArtTest.
 */
fun artWant(
    snap: Snapshot,
    nowQueue: List<PlayItem>,
    nowIndex: Int,
    held: Set<String>,
    recent: List<String>,
    extras: List<DownloadRow> = emptyList(),
    /** The episode list's held pictures (design 39 §2.2, `list_art.txt`). */
    list: List<String> = emptyList(),
    /** The phone is on an unmetered network (Wi-Fi): every picture of every ticked entry is wanted now. */
    unmetered: Boolean = false,
    /** Pictures the launcher keeps passing over ([skippedArt]). */
    skip: Set<String> = emptySet(),
): WantedArt {
    val lib = snap.library
    val kept = mutableListOf<String?>()
    // The playing entry's Spotify look-ahead is streaming's (`now`); every other entry's waits for Wi-Fi (M3).
    val playing = nowQueue.getOrNull(nowIndex)?.entryId
    val ahead = mutableListOf<String?>()
    val laterAhead = mutableListOf<String?>()
    val all = mutableListOf<String?>()
    for (entry in lib?.entries.orEmpty()) {
        if (entry.source == Source.OWN) continue
        if (!(entry.source == Source.SPOTIFY && isSpotifyContext(entry))) all += snap.order(entry).map { it.artHash }
        if (entry.source == Source.SPOTIFY) {
            // Nothing is kept; a context-mode entry's one item has no picture of its own.
            if (isSpotifyContext(entry)) continue
            val order = snap.order(entry)
            val plan = coverTapPlan(order.map { it.key }, entry.resume, snap.bookmarks[entry.key], shuffle = false) ?: continue
            val byKey = order.associateBy { it.key }
            val lookAhead = plan.keys.take(SPOTIFY_ART_AHEAD).map { byKey[it]?.artHash }
            if (entry.id == playing) ahead += lookAhead else laterAhead += lookAhead
            continue
        }
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
    val now = nowArt(nowQueue, nowIndex, ahead) { it.artHash }
    return wantedArt(now, kept, news, recent, held, lib?.covers.orEmpty(), ahead = laterAhead, list = list, all = all, allLater = unmetered, skip = skip)
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

/**
 * The want set now, from the store and the player (off the main thread). It reads the list's held pictures itself
 * (design 39 §2.2), so every sender - the player, the sync's end, the list - carries them: no `later` ever goes
 * without them (which would drop list pictures in flight, design 27 QA L4), and the prune keeps them.
 */
object ArtWants {
    /** [onWifi]: the network to compute for (null = the phone's now, `netCaps`). */
    suspend fun compute(context: Context, onWifi: Boolean? = null): WantedArt {
        val store = Store.get(context)
        val snap = store.snapshot()
        val (queue, index) = nowQueue(snap, PlayerHub.state.value, store.db.nowPlaying())
        val extras = downloadPlan(snap, store.db.downloads(), System.currentTimeMillis(), PlayerHub.heldLocal).extras
        val unmetered = onWifi ?: (netCaps(context)?.notMetered == true)
        return artWant(snap, queue, index, store.coverHashes(), store.recentArt(), extras, store.listArt()?.hashes.orEmpty(), unmetered, ArtSkips.skipped())
    }
}

/**
 * The `later` pictures the launcher keeps passing over (qa-art-asap-code L6, [artSkipsAfter]), in memory: a restart
 * tries them again. The sync's send records its page ([sent]); the next sync's pull counts what was passed over.
 */
object ArtSkips {
    private var skips: Map<String, Int> = emptyMap()
    private var lastSent: List<String> = emptyList()

    @Synchronized
    fun sent(later: List<String>) {
        lastSent = later
    }

    @Synchronized
    fun note(held: Set<String>) {
        skips = artSkipsAfter(skips, lastSent, held)
        lastSent = emptyList()
    }

    @Synchronized
    fun skipped(): Set<String> = skippedArt(skips)
}

/**
 * The episode list's rows showed (design 39 §2.2): [window] (`listArtWindow`: the visible rows' art and 10 each side)
 * of [entryId]'s list. Updates the held list; when the window lacks nothing on the phone it stops there (no want set
 * per scroll), else computes the want set and posts it to the main thread as a forced `LiveBridge.wantArt` (the
 * launcher fetches `later` with the downloads' network gate).
 */
object ListArtWants {
    private val main = Handler(Looper.getMainLooper())

    suspend fun note(context: Context, entryId: Long, window: List<String?>) = withContext(Dispatchers.IO) {
        val store = Store.get(context)
        val covers = store.library()?.covers.orEmpty()
        store.noteListArt(entryId, window, covers)
        if (!listArtMissing(window, store.coverHashes(), covers)) return@withContext
        val want = ArtWants.compute(context)
        main.post { LiveBridge.wantArt(want.now, want.later, force = true) }
    }
}
