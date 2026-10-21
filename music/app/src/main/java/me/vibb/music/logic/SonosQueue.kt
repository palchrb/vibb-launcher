package me.vibb.music.logic

import me.vibb.music.sonos.hms
import me.vibb.music.sonos.pyUnquote
import me.vibb.music.spotify.SpotifyKind
import me.vibb.music.spotify.spotifyTarget
import kotlin.math.abs
import kotlin.math.ceil

/*
 * Spotify on a Sonos room (design 54): the pure rules of a link played from the speaker's own queue. The decode of a
 * queue track, the prefix rule of "ours" and the migration probe's match are palchrb/vibb's (`pi/sonosd.py`
 * `_classify` and `_probe_migration` for `spotify_sharelink`, `tests/sonos_migrate_probe.py` 3; MIT License,
 * Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. The map of one
 * read of the queue (slots), its trust by the queue's version, the contradiction of "ours", the natural-or-pressed
 * boundary, the at-end actions and the speaker's sleep timer are ours (design 54 §2.3-§2.9, §6). Pure; SonosQueueTest.
 *
 * Positions are the speaker's: 1-based, as `Track` and `Seek TRACK_NR` count them. Indices are our listing's: 0-based.
 * No line here carries a Spotify id (design 54 §2.14): every `toString` prints counts.
 */

/** The TrackURI prefixes of a track of a Spotify queue on a Sonos (vibb's prefix rule). */
val SPOTIFY_QUEUE_PREFIXES = listOf("x-sonos-spotify:", "x-sonosprog-spotify:")

/**
 * vibb's `track_spotify_uri`: a Spotify queue track's TrackURI (or a Browse item's `res`) -> the part after the first
 * `:`, before `?`, percent-decoded as Python's `unquote` (a `+` stays) - `spotify:track:<id>`; anything else null.
 */
fun trackSpotifyUri(trackUri: String?): String? {
    val u = trackUri ?: return null
    if (SPOTIFY_QUEUE_PREFIXES.none { u.startsWith(it) }) return null
    return pyUnquote(u.substringAfter(':').substringBefore('?')).takeIf { it.isNotEmpty() }
}

/**
 * One read of the speaker's queue (design 54 §2.3): [length] positions, the decoded uri of each position read
 * ([tracks]; null for an item that isn't a Spotify track), and the Browse's `UpdateID` ([updateId]) - plus the
 * socket's `queueVersion` when the socket was healthy at the read ([socketVersion], N5). A start reads only the page
 * of its track; the rest follows off the hush ([complete] then).
 */
data class QueueRead(
    val length: Int,
    val tracks: Map<Int, String?>,
    val updateId: String?,
    val socketVersion: String? = null,
) {
    /** Every position 1..[length] read. */
    val complete: Boolean get() = (1..length).all { it in tracks }

    /** The uris of the positions read (`ours` (b): "in the queue we read"). */
    val uris: Set<String> get() = tracks.values.filterNotNull().toSet()

    /** This read with one more page: the items from 1-based [first]; a page past [length] is cut. */
    fun withPage(first: Int, items: List<String?>, updateId: String? = this.updateId): QueueRead {
        val add = items.withIndex().associate { (i, u) -> first + i to u }.filterKeys { it in 1..length }
        return copy(tracks = tracks + add, updateId = updateId)
    }

    override fun toString(): String = "QueueRead(length=$length, read=${tracks.size}, updateId=$updateId, socketVersion=$socketVersion)"
}

/** Where a version sample comes from (N5): the Browse's `UpdateID`, or the socket's `queueVersion`. */
enum class VersionSource { UPNP, SOCKET }

/** A later version against the read's: the same, moved, or "read the version again" (the read lacks that source). */
enum class VersionCheck { SAME, MOVED, REREAD }

/**
 * N5: a later [value] from [source] compared with [read]'s value of the same source only. The read lacking that source
 * (the socket came back after the read) is [VersionCheck.REREAD] - one `BrowseMetadata` - never a mismatch; no
 * [value] is no evidence ([VersionCheck.SAME]).
 */
fun versionCheck(read: QueueRead, source: VersionSource, value: String?): VersionCheck {
    if (value == null) return VersionCheck.SAME
    val base = when (source) {
        VersionSource.UPNP -> read.updateId
        VersionSource.SOCKET -> read.socketVersion
    } ?: return VersionCheck.REREAD
    return if (base == value) VersionCheck.SAME else VersionCheck.MOVED
}

/**
 * The map (design 54 §2.3): for each item of our listing ([listed], the track uris in the listing's own order) its
 * queue position or null ([slots]) - duplicates matched in order, the k-th occurrence of a uri in our listing to the
 * k-th in the queue - so gaps, extras, either direction and any cap come out exact. [itemAt] is the inverse. Exact
 * once the read is [QueueRead.complete]; from a partial read it maps what was read.
 */
class QueueMap(private val listed: List<String>, val read: QueueRead) {
    val slots: List<Int?>
    private val items: Map<Int, Int>

    init {
        val byUri = mutableMapOf<String, ArrayDeque<Int>>()
        for (p in read.tracks.keys.sorted()) read.tracks[p]?.let { byUri.getOrPut(it) { ArrayDeque() }.addLast(p) }
        slots = listed.map { byUri[it]?.removeFirstOrNull() }
        items = slots.withIndex().mapNotNull { (i, p) -> p?.let { it to i } }.toMap()
    }

    /** The queue position of our item [index], or null (no slot: the item can't play on this room). */
    fun slotOf(index: Int): Int? = slots.getOrNull(index)

    /** Our item at queue [position], or null (a track our listing doesn't have, or none). */
    fun itemAt(position: Int): Int? = items[position]

    /** The confirm (§2.5): the polled [position] holds our item whose uri is [uri]. */
    fun verifies(position: Int, uri: String?): Boolean = itemAt(position)?.let { listed[it] == uri } == true

    /** Untrusted (§2.3): our item with [uri], the occurrence nearest the [expected] index; null when we don't list it. */
    fun nearest(uri: String, expected: Int): Int? =
        listed.indices.filter { listed[it] == uri }.minByOrNull { abs(it - expected) }

    /**
     * `follows` (§2.6): for our [current] item, the next queue position holds our [next] item - the speaker's own
     * advance is ours then. No next (our last item) never follows: the at-end rules decide ([atEndAction]).
     */
    fun follows(current: Int, next: Int?): Boolean {
        val slot = slotOf(current) ?: return false
        return next != null && itemAt(slot + 1) == next
    }

    override fun toString(): String = "QueueMap(listed=${listed.size}, slotted=${items.size}, read=$read)"
}

/** Browse pages of this many items (SoCo's `get_queue` default, design 54 §2.3). */
const val QUEUE_PAGE = 100

/** At most this many pages a read (the server's 1000-track cap). */
const val QUEUE_MAX_PAGES = 10

/**
 * The pages a search for one track reads, in order (§2.2 step 7): the page holding [near] (1-based) first, then the
 * others outward - after, before, after, ... - at most [QUEUE_MAX_PAGES]. Each is a 0-based `StartingIndex`.
 */
fun queuePagesAround(near: Int, length: Int): List<Int> {
    if (length <= 0) return emptyList()
    val pages = (length + QUEUE_PAGE - 1) / QUEUE_PAGE
    val home = ((near - 1).coerceIn(0, length - 1)) / QUEUE_PAGE
    val order = mutableListOf(home)
    var d = 1
    while (order.size < pages && order.size < QUEUE_MAX_PAGES) {
        if (home + d < pages) order += home + d
        if (order.size < QUEUE_MAX_PAGES && home - d >= 0) order += home - d
        d++
    }
    return order.take(QUEUE_MAX_PAGES).map { it * QUEUE_PAGE }
}

/** The position of [uri] in what [read] holds nearest [near], or null. */
fun positionNearest(read: QueueRead, uri: String, near: Int): Int? =
    read.tracks.filterValues { it == uri }.keys.minByOrNull { abs(it - near) }

/**
 * Only a playlist's container was measured (design 54 §0.3, N2): an absent, empty, non-Spotify or unseen form (an
 * album's, a show's, a track's - [D8+]) is no evidence. A playlist container other than our [target] is.
 */
fun containerContradicts(container: String?, target: String?): Boolean {
    val c = spotifyTarget(container) ?: return false
    if (c.kind != SpotifyKind.PLAYLIST || target == null) return false
    return c.target != target
}

/**
 * `ours` for a share link (design 54 §2.5, N2): vibb's prefix rule ([spotifyUri] not null) unless contradicted - (a)
 * the socket's container names another playlist ([byContainer]), or (b) the track is in neither our listing nor the
 * queue we read ([inEitherList] false) **and** the queue's version moved by an edit not ours ([versionMoved] true; null
 * = no baseline or no answer, no evidence). A moved version alone keeps it ours.
 */
fun shareOurs(spotifyUri: String?, byContainer: Boolean, inEitherList: Boolean, versionMoved: Boolean?): Boolean =
    spotifyUri != null && !byContainer && (inEitherList || versionMoved != true)

/**
 * vibb's `_probe_migration` for a share link (`sonos_migrate_probe.py` 3): a candidate carries our stream only on the
 * exact decoded track ([lastTrack]) and position continuity (`lastRel - 5 <= rel <= lastRel + 45`); never on the
 * prefix alone, never with no recorded track.
 */
fun shareProbeMatches(lastTrack: String?, lastRelS: Double?, liveTrackUri: String?, liveRelS: Double?): Boolean {
    if (lastTrack == null || lastRelS == null || liveRelS == null) return false
    if (trackSpotifyUri(liveTrackUri) != lastTrack) return false
    return liveRelS >= lastRelS - PROBE_BEHIND_S && liveRelS <= lastRelS + PROBE_AHEAD_S
}

const val PROBE_BEHIND_S = 5.0
const val PROBE_AHEAD_S = 45.0

/** A track change: the speaker's own advance at the end ([NATURAL]), by the anchor or by the fallback, or pressed. */
enum class Boundary {
    /** The previous track's anchor, extrapolated to the change, was within [NATURAL_END_MS] of its end. */
    NATURAL,

    /** No anchor could say; the poll's `endsNear` did - a done mark only, never the correction (N8). */
    NATURAL_GUESS,

    /** The speaker's buttons, the Sonos app: followed as it is. */
    PRESSED,
    ;

    val natural: Boolean get() = this != PRESSED

    /** The correction (§2.6) runs only on the anchor's verdict (N8). */
    val correctable: Boolean get() = this == NATURAL
}

/** Natural iff the previous track's extrapolated place is within this of its duration (§2.5). */
const val NATURAL_END_MS = 3_000L

/**
 * `boundary` (§2.5 step 3): [anchor] is the previous track's last ours measurement (null when there was none),
 * [durationMs] its duration, [changeAt] the change's time (the `itemId` event's, else the confirming poll's), and
 * [endsNear] the poll's fallback.
 */
fun boundary(anchor: SonosAnchor?, durationMs: Long?, changeAt: Long, endsNear: Boolean): Boundary {
    if (anchor != null && durationMs != null && durationMs > 0) {
        return if (durationMs - anchor.positionAt(changeAt) <= NATURAL_END_MS) Boundary.NATURAL else Boundary.PRESSED
    }
    return if (endsNear) Boundary.NATURAL_GUESS else Boundary.PRESSED
}

/**
 * N1b: the queue's end is near by the anchor - the last ours measurement of the playing track extrapolated to [at] (a
 * STOPPED poll's `measuredAt`, an IDLE's `at`) within [endsNearS] of its duration.
 */
fun anchorNearEnd(anchor: SonosAnchor?, durationMs: Long?, at: Long, endsNearS: Double): Boolean {
    if (anchor == null || durationMs == null || durationMs <= 0) return false
    return durationMs - anchor.positionAt(at) <= (endsNearS * 1000).toLong()
}

/** What the at-end timer does 400 ms before a mapped track's end (§2.7). */
enum class AtEnd {
    NONE,

    /** "Til slutten" (design 50): bookmark, Pause, our next waits at 0. */
    HOLD,

    /** Repeat one: a gated `Seek REL_TIME 0:00:00`. */
    REPEAT_ONE,

    /** Our last item while the queue goes on after it, repeat off: Pause, then ended - never a Stop. */
    END,

    /** Our last item while the queue goes on after it, repeat all: a gated `queuePlay` of our first item. */
    WRAP,
}

/**
 * The at-end timer's arming (§2.7, 54-8): "Til slutten" first, then repeat one, then our last item ([ourLast]) when a
 * queue position follows its slot ([slotAfter]). Our last item at the queue's last position arms nothing: the speaker
 * stops by itself and STOPPED near the end is the advance.
 */
fun atEndAction(holdAtEnd: Boolean, repeat: Repeat, ourLast: Boolean, slotAfter: Boolean): AtEnd = when {
    holdAtEnd -> AtEnd.HOLD
    repeat == Repeat.ONE -> AtEnd.REPEAT_ONE
    ourLast && slotAfter && repeat == Repeat.ALL -> AtEnd.WRAP
    ourLast && slotAfter -> AtEnd.END
    else -> AtEnd.NONE
}

/** The at-end timer fires this long before the track's end (the web player's [AT_END_SPOTIFY_PAUSE_MS]). */
const val AT_END_QUEUE_PAUSE_MS = AT_END_SPOTIFY_PAUSE_MS

/** The at-end timer's delay from a measurement at [posMs] of a [durationMs] track; 0 when it is already due. */
fun atEndDelayMs(durationMs: Long, posMs: Long): Long = (durationMs - posMs - AT_END_QUEUE_PAUSE_MS).coerceAtLeast(0)

/** The unmapped look (Q6, §2.8): the speaker's title in the server's `"<title> — <artists>"` form, or the title alone. */
fun unmappedTitle(name: String?, artist: String?): String? {
    val n = name?.trim().takeUnless { it.isNullOrEmpty() } ?: return null
    val a = artist?.trim().takeUnless { it.isNullOrEmpty() } ?: return n
    return "$n — $a"
}

/** The speaker's sleep timer at most this (SoCo's `set_sleep_timer`: 0..86399 s). */
const val SLEEP_TIMER_MAX_S = 86_399L

/** Our own timer is re-set when it would differ more than this, and only cleared while it is within this of ours. */
const val SLEEP_TIMER_SLACK_MS = 30_000L

/**
 * The speaker's sleep timer to the next suspension edge (§2.9 item 4): `H:MM:SS` until [suspendAtMs] (epoch ms) from
 * [nowMs], rounded up and capped at 23:59:59; null - none to set - for no edge (0), one past, or more than a day away.
 */
fun sleepTimerFor(suspendAtMs: Long, nowMs: Long): String? {
    if (suspendAtMs <= 0) return null
    val left = suspendAtMs - nowMs
    if (left <= 0 || left > 86_400_000L) return null
    return hms(minOf(ceil(left / 1000.0).toLong(), SLEEP_TIMER_MAX_S).toDouble())
}

/** A boundary re-sets our timer only when [wantedLeftMs] differs from what our last set has left ([ownLeftMs]) by more than 30 s. */
fun sleepTimerResetDue(ownLeftMs: Long?, wantedLeftMs: Long): Boolean = ownLeftMs == null || abs(ownLeftMs - wantedLeftMs) > SLEEP_TIMER_SLACK_MS

/**
 * The speaker's timer is still ours (§2.9 item 4): its remaining time ([remainingS], `GetRemainingSleepTimerDuration`;
 * null = none set) within 30 s of what our own last set has left ([ownLeftMs]). One someone set in the Sonos app
 * afterwards is theirs: never cleared.
 */
fun sleepTimerOurs(remainingS: Double?, ownLeftMs: Long?): Boolean =
    remainingS != null && ownLeftMs != null && abs((remainingS * 1000).toLong() - ownLeftMs) <= SLEEP_TIMER_SLACK_MS
