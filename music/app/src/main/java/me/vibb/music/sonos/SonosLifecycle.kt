package me.vibb.music.sonos

import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem

/*
 * The Sonos session across the app's lifecycle (design 26a §4.7, §6.1): adopting a session found
 * alive at start, and the suspension's stop. Ported from palchrb/vibb `pi/daemon.py`
 * (`_sonos_poller`'s adopt-on-start, `_sonos_on_term`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Pure decisions and one
 * Android-free SOAP sequence; SonosLifecycleTest.
 */

/**
 * The one live classify of the stored room found OUR stream there, playing or paused (vibb: "armed
 * and PLAYING/PAUSED_PLAYBACK"): measured just now (the classify answered within its budget - the
 * fresh-equivalent of a start, when no poll has run yet), the room renderer.json names, and the
 * speaker's TrackURI equal to the stored built uri through `normUri`. A failed or late classify, a
 * foreign or empty uri, a STOPPED speaker: no. What adopt at start (§4.7) and the suspension's
 * Stop (§6.1) both ask before they touch anything.
 */
fun ourLiveSession(choice: RendererChoice, live: Fields?): Boolean {
    val uri = choice.uri
    if (!choice.sonos || uri.isNullOrEmpty() || live == null) return false
    return live.reachable && live.measuredAt != null && live.uid == choice.uid &&
        !live.uri.isNullOrEmpty() && normUri(live.uri) == normUri(uri) && live.transport?.live == true
}

/**
 * Where the adopted stream is in the rebuilt queue - by uri equality, as vibb (`e["url"] == uri`):
 * an item's own url, or for an NRK series the service uri built for its program id with the serial
 * the stored uri carries. Null when no item matches (the item left the library): nothing is adopted.
 */
fun adoptIndex(entry: Entry, items: List<PlayItem>, uri: String): Int? {
    val want = normUri(uri)
    val slug = if (entry.nrkSerie) nrkSeriesSlug(entry.target) else null
    val serial = uri.substringAfter("sn=", "").substringBefore('&')
    val i = items.indexOfFirst { item ->
        val built = if (slug != null) NrkService.programUri(slug, item.key, serial) else item.url
        built != null && normUri(built) == want
    }
    return i.takeIf { it >= 0 }
}

/**
 * The suspension's bookmark without the player (§6.1, the receiver's standalone path): the
 * position the live classify MEASURED, through vibb's rules (the regression guard against the
 * saved place; no hold, no user seek known - the controller that knew them is gone). Never an
 * extrapolation: no age term, the classify is this second's.
 */
fun suspensionBookmark(itemKey: String?, live: Fields, savedS: Double, nowMs: Long): BookmarkWrite? {
    val input = BookmarkInput(itemKey, live.ours, live.transport, live.relS, live.durS, durFromListing = false, ageMs = 0)
    return SonosBookmarks.decide(input, hold = null, prevS = savedS, seekAtMs = NO_SEEK, nowMs = nowMs).write
}

private const val NO_SEEK = Long.MIN_VALUE / 2

/**
 * The suspension edge with no player service (§6.1, H4/M5): one live classify of the stored room
 * within [budgetMs]; only OUR stream PLAYING or PAUSED ([ourLiveSession]) is touched - [bookmark]
 * with that classify first, then Stop, then the speaker's own queue as its source ([queueUri]) so
 * its Play button plays the empty queue, not the episode from 0:00. The session is disarmed either
 * way. Writing renderer.json back to the phone is the caller's. True when it stopped the speaker.
 */
suspend fun suspensionStop(transport: SonosTransport, choice: RendererChoice, budgetMs: Long, bookmark: suspend (Fields) -> Unit): Boolean {
    val uid = choice.uid ?: return false
    val uri = choice.uri?.takeIf { it.isNotEmpty() } ?: return false
    val live = transport.adoptLive(uid, kindOfUri(uri), uri, budgetMs) { false }
    if (!ourLiveSession(choice, live)) return false
    try {
        bookmark(live!!)
    } catch (e: Exception) {
        // the Stop matters more than the place: never left playing over a failed write
    }
    return transport.park(uid)
}

