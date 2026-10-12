package me.vibb.music.sonos

import me.vibb.music.logic.consumableIdOf
import me.vibb.music.logic.sameStream
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source

/*
 * The Sonos session across the app's lifecycle (design 26a §4.7, §6.1): adopting a session found
 * alive at start, and the suspension's stop. Ported from palchrb/vibb `pi/daemon.py`
 * (`_sonos_poller`'s adopt-on-start, `_sonos_on_term`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Pure decisions and one
 * Android-free SOAP sequence; SonosLifecycleTest.
 */

/** What one live classify of the room renderer.json names says (design 26a §4.7, §6.1; qa-26a-step3b-code M1). */
enum class RoomVerdict {
    /** Our stream, PLAYING or PAUSED: adopt it; at a suspension bookmark, Stop and park it. */
    OURS_LIVE,

    /** Our uri retained on a stopped speaker: nothing to adopt; a suspension parks it (its Play button would replay ours from 0:00). */
    OURS_STOPPED,

    /** A definite answer that it isn't ours (foreign, empty): renderer.json goes back to the phone. */
    NOT_OURS,

    /** No answer (no Wi-Fi, unreachable, late, another room answering): nothing is known - the room stays recorded. */
    NO_ANSWER,
}

/**
 * [live] is the one classify of the stored room (vibb keeps `renderer = sonos` on a transport
 * failure and reverts only on a definite not-armed or not-PLAYING/PAUSED answer, qa-26a-step3b-code
 * M1): measured just now (the fresh-equivalent of a start, when no poll has run yet) by the room
 * renderer.json names, or else NO_ANSWER. Ours = the speaker's TrackURI equal to the stored built
 * uri through `normUri` - for a Storytel book the stored marker `storytel:book:<cid>` against the live
 * signed URL's `consumableId` ([sameStream], design 26b §5.3); with no stored uri yet (a process gone
 * between the switch's write and the play's result, after SetAVTransportURI and Play went out)
 * [ofEntry] says whether the speaker plays an item of the stored entry.
 */
fun roomVerdict(choice: RendererChoice, live: Fields?, ofEntry: (String) -> Boolean = { false }): RoomVerdict {
    if (!choice.sonos) return RoomVerdict.NOT_OURS
    if (live == null || !live.reachable || live.measuredAt == null || live.uid != choice.uid) return RoomVerdict.NO_ANSWER
    val uri = live.uri.orEmpty()
    if (uri.isEmpty()) return RoomVerdict.NOT_OURS
    val stored = choice.uri
    val ours = if (!stored.isNullOrEmpty()) sameStream(stored, uri) else ofEntry(uri)
    return when {
        !ours -> RoomVerdict.NOT_OURS
        live.transport?.live == true -> RoomVerdict.OURS_LIVE
        else -> RoomVerdict.OURS_STOPPED
    }
}

/** Our stream there, playing or paused ([roomVerdict] OURS_LIVE): what adopt at start keeps. */
fun ourLiveSession(choice: RendererChoice, live: Fields?, ofEntry: (String) -> Boolean = { false }): Boolean =
    roomVerdict(choice, live, ofEntry) == RoomVerdict.OURS_LIVE

/** What adopt rebuilds (design 26a §4.7): the queue's keys, speaker-playable, its shuffle flag, the index of the adopted uri. */
data class AdoptPlan(val keys: List<String>, val shuffled: Boolean, val index: Int)

/**
 * The adopted queue: the stored queue's order ([storedKeys], the stored entry's `NowPlayingRow`,
 * with its shuffle flag) when there is one, else the entry's play [order]; through the
 * speaker-playable filter (§4.2); the index by [adoptIndex]. Null when the speaker's item isn't in
 * it any more (left the library).
 */
fun adoptPlan(entry: Entry, order: List<PlayItem>, storedKeys: List<String>?, storedShuffled: Boolean, uri: String): AdoptPlan? {
    val byKey = order.associateBy { it.key }
    val items = (storedKeys ?: order.map { it.key }).mapNotNull { byKey[it] }.filter { PlayBodies.speakerPlayable(entry, it) }
    val index = adoptIndex(entry, items, uri) ?: return null
    return AdoptPlan(items.map { it.key }, storedKeys != null && storedShuffled, index)
}

/**
 * Where the adopted stream is in the rebuilt queue - by uri equality, as vibb (`e["url"] == uri`):
 * an item's own url, or for an NRK series the service uri built for its program id with the serial
 * the stored uri carries; a Storytel book by the live signed URL's `consumableId` query parameter,
 * parsed and exact (vibb adopt 4527-4570, `storytel_sonos.py` 7: the token can hold another book's
 * digits, so never a substring). Null when no item matches (the item left the library): nothing is
 * adopted.
 */
fun adoptIndex(entry: Entry, items: List<PlayItem>, uri: String): Int? {
    if (entry.source == Source.STORYTEL) {
        val cid = consumableIdOf(uri) ?: return null
        return items.indexOfFirst { it.key == cid }.takeIf { it >= 0 }
    }
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
 * The suspension edge with no player service (§6.1, H4/M5): a live classify of the stored room
 * ([roomVerdict]) - the first within [budgetMs], and while nothing definite came back and [lookMs]
 * (all the looks together) has time left, up to [BEDTIME_MAX_LOOKS] in all, each later one with everything
 * that is left (a speaker slow to answer a 1.5 s look gets its Stop on the next; correction
 * 2026-10-12). No group, no volume on these looks: their budget is the Stop's. Our stream PLAYING or
 * PAUSED: [bookmark] with that classify first, then Stop and the speaker's own queue as its source
 * ([queueUri]) so its Play button plays the empty queue, not the episode from 0:00; our uri on a
 * stopped speaker: the park alone (the same Play button). Anything else is left alone. The session is
 * disarmed either way. The verdict goes back: renderer.json returns to the phone on any definite
 * answer, and stays on the room after none (the caller's; qa-26a-step3b-code M1). [stopMs]: the
 * Stop and the park end within this long after the call started (the receiver's budget,
 * qa-26a-corrections L2; at least [BEDTIME_MIN_LOOK_MS] after a late answer); null: the client's
 * timeouts. A Stop that fails or runs out of time is NO_ANSWER - the room stays recorded and the
 * next look parks it.
 */
suspend fun suspensionStop(
    transport: SonosTransport,
    choice: RendererChoice,
    budgetMs: Long,
    ofEntry: (String) -> Boolean = { false },
    lookMs: Long = budgetMs,
    clock: SonosClock = SonosClock({ System.nanoTime() / 1_000_000 }),
    stopMs: Long? = null,
    bookmark: suspend (Fields) -> Unit,
): RoomVerdict {
    val uid = choice.uid ?: return RoomVerdict.NOT_OURS
    val uri = choice.uri.orEmpty()
    val start = clock.elapsedMs()
    var live: Fields? = null
    var verdict = RoomVerdict.NO_ANSWER
    var looks = 0
    while (true) {
        val left = lookMs - (clock.elapsedMs() - start)
        val budget = if (looks == 0) minOf(budgetMs, left) else left
        if (budget < BEDTIME_MIN_LOOK_MS) break
        live = transport.adoptLive(uid, kindOfUri(uri), uri, budget, aux = false) { null }
        looks++
        verdict = roomVerdict(choice, live, ofEntry)
        if (verdict != RoomVerdict.NO_ANSWER || looks >= BEDTIME_MAX_LOOKS) break
        clock.sleep(BEDTIME_LOOK_PAUSE_MS)
    }
    if (verdict == RoomVerdict.OURS_LIVE) {
        try {
            bookmark(live!!.copy(ours = true))
        } catch (e: Exception) {
            // the Stop matters more than the place: never left playing over a failed write
        }
    }
    if (verdict == RoomVerdict.OURS_LIVE || verdict == RoomVerdict.OURS_STOPPED) {
        val left = stopMs?.let { (it - (clock.elapsedMs() - start)).coerceAtLeast(BEDTIME_MIN_LOOK_MS) }
        try {
            transport.park(uid, left)
        } catch (e: SonosException) {
            return RoomVerdict.NO_ANSWER
        }
    }
    return verdict
}

/** The bedtime looks at most ([suspensionStop]); a look needs at least [BEDTIME_MIN_LOOK_MS], [BEDTIME_LOOK_PAUSE_MS] between them. */
internal const val BEDTIME_MAX_LOOKS = 3
internal const val BEDTIME_MIN_LOOK_MS = 500L
internal const val BEDTIME_LOOK_PAUSE_MS = 250L

/**
 * The volume our own `SetVolume` sends (design 26a §5.3): [requested] within 0..100 and never above
 * the parent's [cap] in force (1..99 %, null = none).
 */
fun volumeTarget(requested: Int, cap: Int?): Int {
    val v = requested.coerceIn(0, 100)
    return if (cap != null && cap in 1..99) minOf(v, cap) else v
}
