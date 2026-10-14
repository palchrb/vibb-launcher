package me.vibb.music.spotify

import me.vibb.music.logic.nextQueueAdd
import kotlin.math.abs

/*
 * The web player's state against what we told it (design 26c §5.4, QA H4, M5, L11; qa-26c-step4-code
 * H1, M1, L1, L2): the commands we sent and the states the page posts give the current queue index,
 * measured positions, the natural advance, the end of the window and of the queue, pauses, "not ours",
 * "elsewhere" and unplayable tracks. vibb's `play_origin` rule ("only a box-initiated session may
 * write") is its ownership rule:
 *
 * - **Only our uris are ours.** A state is ours when its track is the one we sent for the current
 *   index, or the expected next item (the next of our queue) - matched by uri, by the SDK's `linked`
 *   (undocumented; the Web API dropped `linked_from` in February 2026), or by title and duration (the
 *   server's listing title built from the state equals the item's, and the durations are within 2 s:
 *   a relinked copy of the same recording).
 * - **A later uri of ours is a skip only on evidence** (H1): every item between is named in the state's
 *   `prev` (Spotify played past it) and none of them, nor the state's own track, is one we added to
 *   Spotify's queue. Our own add left in Spotify's queue (it survives a new play) is recognised by its
 *   uri - remembered across plays until it is seen playing - and is not ours where it plays: paused, and
 *   our list goes on from where it was ([Event.NewWindow]). Any other later uri of ours is the same:
 *   not a skip, no item error.
 * - **Anything else is not ours** (the account's Autoplay after the window or the queue, another app
 *   transferring playback to "Vibb", a leftover session): [Event.NotOurs] - paused at once, never
 *   measured, never mapped to an index - and from then on every playing state is paused until our next
 *   play. After [Event.Ended], three unplayable in a row, or a confirmed `null`, the same.
 * - **A start passes over what played before it** (H1, L1; qa-26c-step5a-code M3): the track of the last
 *   state seen before the play call, the old play's expected next track (Spotify's own advance can only
 *   go there while the call is on its way) and our adds still in Spotify's queue are stale in a start,
 *   unless it is the sent track near the sent position (a restart of the same track from 0 isn't its old
 *   tick).
 * - **Shuffle or repeat on** (M1): a state that says so is read for nothing - no start, skip, end or
 *   foreign track; one [Event.ShuffleOrRepeat] asks the renderer to turn both off and play again.
 * - **A state naming no track** (no uri, no name; L2) is evidence of nothing: passed over.
 * - **The end of a track** is read from the state: our current track paused at position 0 after its
 *   last playing position, carried on by the time since (the page posts every 5 s), came within 3 s of
 *   its duration, with no pause of ours pending (or a track not ours playing then: Autoplay). Spotify's
 *   own advance shows as the expected next uri. **A paused state at the track's duration** after it ran
 *   out (the HTML audio element's `pause` before its `ended`, which the fake's stub posted; the real SDK
 *   may post one too - emulator round 2026-10-14 N1) is the track ending, never a pause: while Spotify
 *   knows our next item the state that follows says where it went (nothing is emitted, the player keeps
 *   playing and its locks), else it is the end as at 0. The advance plays again after a paused state of
 *   ours that wasn't our pause ([Event.Resumed]).
 * - **Unplayable** (QA M5) only from the SDK: a `playback_error` on our current track, or Spotify
 *   skipping past one of ours on the evidence above. A `playback_error` after our current track played
 *   out is the next item's (Spotify moved on to it and it failed before any state of its own; N2). After
 *   three in a row the renderer stops.
 * - **A `null` state** is confirmed before it counts (QA L11): [Event.Look] asks the page again; a
 *   `null` 2 s or more after the first, or a `not_ready`, is [Event.Elsewhere].
 * - **Context mode** (§5.9, step 6; [loadContext], [playedContext]): Spotify plays the entry's context (an artist,
 *   or a playlist no account may list) in its own order, so there is no queue to match. A state is ours when its
 *   `context` is the entry's target: every track Spotify picks there is ours ([Event.TrackChanged] when it moves
 *   on - its own advance, a step of ours through the SDK, the account's other controls). The SDK reference says a
 *   state's context "can be null": with none, our own live track and the track our last state named next are still
 *   ours, any other track is a uri list (qa-26c-step6-code M2). A state of any other context, or a uri list, is
 *   [Event.NotOurs] and paused, as in list mode; one of our list's adds left in Spotify's queue is
 *   [Event.LeftoverAdd] (skipped while the kid wants it playing). The end, on evidence only (M1): our live track
 *   paused at 0 after it ran out, or another of the context's tracks paused then that our last state didn't name next
 *   ([Event.Ended]; a track of another, non-null context playing then is Autoplay after it: not ours, and the end).
 *   No unplayable item (Spotify skips what the account can't play; the context's one synthetic item is no track), no
 *   window, no queue add.
 *
 * Positions are only ever measured: [measured] is the latest state of ours for the current item, as
 * received, never extrapolated (26a: no guess reaches Room). Pure and not thread-safe: the renderer
 * owns it on the main thread. SpotifyTrackerTest replays the sequences of `sdk_states/`.
 */

/** One item of the queue as the tracker matches it: its uri (`uriOf`), the listing's title and duration. */
data class SpotifyTrack(val uri: String, val title: String, val durationMs: Long?)

class SpotifyTracker {
    /** What a state, an error or a command means for the renderer. */
    sealed interface Event {
        /** Our first playing state after a play call: the start is done ([index] may be past the sent one after skips). */
        data class Started(val index: Int) : Event

        /** The current item is now [index]; [auto] = Spotify moved on by itself (the natural advance: nothing is sent). */
        data class ItemChanged(val index: Int, val auto: Boolean) : Event

        /** Item [index] can't be played (the item error `unplayable`); [stop] = the third in a row: the renderer stops. */
        data class Unplayable(val index: Int, val stop: Boolean) : Event

        /** Our track paused: by a pause of ours ([ours]), or by Spotify (another app's controls, a lost stream). */
        data class Paused(val ours: Boolean) : Event

        /** Our track plays again after a pause. */
        data object Resumed : Event

        /** A state that isn't ours: pause the player now if [playing]; nothing is saved, the player shows paused. */
        data class NotOurs(val playing: Boolean) : Event

        /** The queue's last item played out (`clear_state`, design 21). */
        data object Ended : Event

        /**
         * Play our list again from [index]: Spotify's list ran out before ours (a queue add that failed or
         * came late) - from 0 - or Spotify played something of ours out of our order while ours was
         * playing ([resume]: the current item, from its [measured] position).
         */
        data class NewWindow(val index: Int, val resume: Boolean = false) : Event

        /** Add queue item [index] to Spotify's queue now: one track ahead (§5.4, "Past the window"). */
        data class QueueAdd(val index: Int) : Event

        /**
         * The account's shuffle or repeat is on (qa-26c-step4-code M1): turn both off, then play from
         * [index] (at its [measured] position). States are read for nothing until that play.
         */
        data class ShuffleOrRepeat(val index: Int) : Event

        /** A first `null` state: ask the page for its state again ([LOOK_MS]). */
        data object Look : Event

        /** A confirmed `null`: the account plays elsewhere. The bookmark is [measured]; a play takes it back. */
        data object Elsewhere : Event

        /**
         * Context mode (§5.9): the context's live track is now [uri] - Spotify moved on in our context. [title] the
         * server's listing title built from the state (`"<name> — <artists>"`), null when the state named none.
         */
        data class TrackChanged(val uri: String, val title: String?) : Event

        /**
         * Context mode (qa-26c-step6-code M1): our list mode's add [uri] left in Spotify's queue plays (or sits, [playing]
         * false) where the context should go on - never ours and never the end: skip it while the kid wants it playing,
         * else pause it.
         */
        data class LeftoverAdd(val uri: String, val playing: Boolean) : Event
    }

    /** The latest state of ours for item [index]: [positionMs] as the SDK measured it at [atMs] (the elapsed clock). */
    data class Measured(val index: Int, val positionMs: Long, val atMs: Long, val playing: Boolean)

    enum class Mode {
        /** Nothing sent since the queue was loaded: any playing state is not ours. */
        IDLE,

        /** A play call answered 204: waiting for our first uri (stale states of what played before are passed over). */
        STARTING,

        /** Our track is on the player (playing or paused). */
        OURS,

        /** The queue played out: every later playing state is paused. */
        ENDED,

        /** Not ours any more (a foreign state, a confirmed `null`, three unplayable, a window's gap): paused until our next play. */
        LOST,
    }

    var mode = Mode.IDLE
        private set

    /** The current queue index (meaningful from the first play on). */
    var index = 0
        private set

    /** The latest measurement of ours for the current item, or null (none yet). */
    var measured: Measured? = null
        private set

    private var queue: List<SpotifyTrack> = emptyList()

    /** Context mode (§5.9): the entry's target, whose every track is ours; null in list mode. */
    var context: String? = null
        private set

    /** Context mode: the live track's uri - the one on the player, else the stored one ([loadContext]); null before any. */
    var liveUri: String? = null
        private set

    /** Context mode: the offset of the last play call (a playlist's live track; none for an artist). */
    private var offsetUri: String? = null

    /** Context mode: the tracks the last state of ours named next (`next_tracks`) - our advance, by evidence (M1, M2). */
    private var lastNext: List<String> = emptyList()

    /** Context mode: our list's leftover add playing now (its states until another track shows are it, M1). */
    private var leftoverUri: String? = null

    /** The last queue index Spotify knows: the window's end, or the last item added to its queue. */
    private var known = -1
    private var addAsked = -1
    private var pausePending = false
    private var lastPaused = true
    private var nullAt: Long? = null

    /** The uris we asked Spotify to queue, not yet seen playing: Spotify's queue survives a new play (H1). */
    private val adds = LinkedHashSet<String>()

    /** The track of the last state seen; what a start passes over (H1, L1, qa-26c-step5a-code M3). */
    private var lastSeenUri: String? = null
    private var prePlayUris: Set<String> = emptySet()

    /** The old play's expected next track, kept across a [load] (a new queue's first play). */
    private var oldNextUri: String? = null
    private var sentPositionMs = 0L

    /** A [Event.ShuffleOrRepeat] was asked for: nothing is read until the next play (M1). */
    private var correcting = false

    /** The current item's first and furthest playing positions, and its last one (the end rule). */
    private var firstPlaying: Long? = null
    private var furthest = 0L
    private var lastPlaying: Long? = null
    private var lastPlayingAt = 0L
    private var durationSeen: Long? = null

    private var unplayableRun = 0
    private val reported = mutableSetOf<Int>()

    /** The queue ([load]: the service's items in play order, never filtered) and where it stands; nothing is sent. */
    fun load(queue: List<SpotifyTrack>, index: Int) {
        oldNextUri = expectedNext()
        context = null
        liveUri = null
        offsetUri = null
        lastNext = emptyList()
        leftoverUri = null
        this.queue = queue
        this.index = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        mode = Mode.IDLE
        known = -1
        addAsked = -1
        measured = null
        correcting = false
        reported.clear()
        unplayableRun = 0
        resetItem()
    }

    /**
     * Context mode (§5.9): the entry's context [contextUri] (its target) - Spotify picks the tracks - and the live
     * track stored for it ([live]: where a playlist context goes on); nothing is sent.
     */
    fun loadContext(contextUri: String, live: String?) {
        load(emptyList(), 0)
        context = contextUri
        liveUri = live
    }

    /**
     * Context mode: a play call of the context answered 204, from [offset] (a playlist's live track; null for an
     * artist: Spotify's start) at [positionMs] (sent with the offset only). [fresh] as [played].
     */
    fun playedContext(offset: String?, positionMs: Long = 0, fresh: Boolean = true): List<Event> {
        if (context == null) return emptyList()
        oldNextUri = null
        prePlayUris = setOfNotNull(lastSeenUri) + adds
        index = 0
        known = 0
        mode = Mode.STARTING
        addAsked = -1
        pausePending = false
        correcting = false
        offsetUri = offset
        lastNext = emptyList()
        leftoverUri = null
        sentPositionMs = if (offset == null) 0 else positionMs.coerceAtLeast(0)
        reported.clear()
        if (fresh) unplayableRun = 0
        resetItem()
        measured = null
        return emptyList()
    }

    /**
     * Context mode (qa-26c-step6-code M3): Spotify refused the stored live track as the play's offset (it left the
     * playlist) - the next play starts from the context's top.
     */
    fun dropLive() {
        if (context != null) liveUri = null
    }

    /** A new player device (the host's `ready` with another device id): its Spotify queue is empty. */
    fun newDevice() {
        adds.clear()
        lastSeenUri = null
        oldNextUri = null
    }

    /**
     * A play call for [index] answered 204, its window ending at [known] (`index + window size - 1`),
     * started at [positionMs]. [fresh] = the kid's command (a tap, a step, a resume after a drop): the
     * unplayable run starts again; false = the renderer moving on by itself (after an unplayable item, a
     * window's gap).
     */
    fun played(index: Int, known: Int, positionMs: Long = 0, fresh: Boolean = true): List<Event> {
        if (index !in queue.indices) return emptyList()
        val oldNext = expectedNext() ?: oldNextUri
        oldNextUri = null
        prePlayUris = setOfNotNull(lastSeenUri, oldNext) + adds
        this.index = index
        this.known = known.coerceIn(index, queue.size - 1)
        mode = Mode.STARTING
        addAsked = -1
        pausePending = false
        correcting = false
        sentPositionMs = positionMs.coerceAtLeast(0)
        reported.clear()
        if (fresh) unplayableRun = 0
        resetItem()
        measured = null // the start position is the stored bookmark, not a measurement
        return emptyList()
    }

    /** Queue item [index] was added to Spotify's queue (the add's 204). */
    fun queued(index: Int): List<Event> {
        if (index !in queue.indices) return emptyList()
        remember(queue[index].uri)
        if (index <= known) return emptyList()
        known = index
        return if (mode == Mode.OURS) aheadAdd() else emptyList()
    }

    /** We sent `vibb.pause()` for our track: the next paused state is ours, not an end. */
    fun pauseSent() {
        pausePending = true
    }

    /** We sent `vibb.resume()`. */
    fun resumeSent() {
        pausePending = false
    }

    /** The renderer let go of the player (detach, the host destroyed, an error that stops it). */
    fun lost() {
        if (mode != Mode.ENDED) mode = Mode.LOST
        nullAt = null
    }

    /** The SDK's `not_ready`: confirms a `null` being looked at; otherwise nothing (the 30 s hold covers a gap). */
    fun onNotReady(): List<Event> {
        if (mode != Mode.OURS || nullAt == null) return emptyList()
        mode = Mode.LOST
        nullAt = null
        return listOf(Event.Elsewhere)
    }

    /**
     * The SDK's `playback_error` at [nowMs]: our current track can't be played - or, once it played out with our next
     * item on Spotify's queue, the next one (emulator round 2026-10-14 N2: the error comes when Spotify reaches it, before
     * any state names it), which becomes the current item; the state that follows says where Spotify went from there.
     */
    fun onPlaybackError(nowMs: Long): List<Event> {
        if (mode != Mode.OURS && mode != Mode.STARTING) return emptyList()
        if (context != null) return emptyList() // Spotify skips it in its own order; the synthetic item is no track
        val next = index + 1
        if (mode == Mode.OURS && next <= known && next in queue.indices && ranOut(nowMs)) {
            moveTo(next)
            lastPaused = false // the play goes on past it: Spotify's next state is no resume
            // the advance first, so the track that played out is marked done (qa-26c-emu-music-code L1)
            return listOf(Event.ItemChanged(next, auto = true)) + unplayable(next)
        }
        return unplayable(index)
    }

    /** One posted state ([SdkState.parse]) at [nowMs] (the elapsed clock). */
    fun onState(snapshot: SdkSnapshot, nowMs: Long): List<Event> {
        val s = when (snapshot) {
            Inactive -> {
                lastSeenUri = null
                return onInactive(nowMs)
            }
            is SdkState -> snapshot
        }
        if (s.uri != null) lastSeenUri = s.uri
        if (mode == Mode.STARTING || mode == Mode.OURS) {
            if (mode == Mode.OURS) nullAt = null
            if (s.trackless) return emptyList() // L2: evidence of nothing
            if (correcting) return emptyList()
            if (s.reordered) {
                correcting = true
                return listOf(Event.ShuffleOrRepeat(index))
            }
        }
        return when (mode) {
            Mode.IDLE, Mode.ENDED, Mode.LOST -> if (s.paused) emptyList() else listOf(Event.NotOurs(playing = true))
            Mode.STARTING -> if (context != null) onContextStarting(s, nowMs) else onStarting(s, nowMs)
            Mode.OURS -> if (context != null) onContextOurs(s, nowMs) else onOurs(s, nowMs)
        }
    }

    // --- context mode (§5.9) ---

    /**
     * A state that names our context (qa-26c-step6-code M2): its `context` is the entry's target - or the reference's
     * documented `null` ("the URI of the context (can be null)") on our own live track or on the track our last state named
     * next (our advance). Any other track with no context is a uri list: another app's play.
     */
    private fun namesOurContext(s: SdkState, live: Boolean, named: Boolean): Boolean = when (s.contextUri) {
        context -> true
        null -> live || named
        else -> false
    }

    /** [s]'s track is the one our last state of the context named next (`next_tracks`), by uri or `linked`. */
    private fun namedNext(s: SdkState): Boolean =
        (s.uri != null && s.uri in lastNext) || (s.linkedUri != null && s.linkedUri in lastNext)

    /**
     * Our list mode's add left in Spotify's queue (qa-26c-step6-code M1): Spotify plays its queue before the context goes
     * on. Never ours, never the end, never measured: the renderer skips it (`vibb.next()`) while the kid wants it
     * playing, else pauses it. It is used up once seen ([adds] forgets it), and its later states until another track
     * shows are still it ([leftoverUri]).
     */
    private fun leftoverAdd(s: SdkState): Boolean {
        val uri = s.uri ?: return false
        if (uri == leftoverUri) return true
        if (!isAdd(s)) return false
        forget(s)
        leftoverUri = uri
        return true
    }

    private fun onContextStarting(s: SdkState, nowMs: Long): List<Event> {
        val uri = s.uri ?: return emptyList()
        if (isAdd(s)) return emptyList() // our list's add: Spotify plays a new play's context first
        val sent = offsetUri
        val near = abs(s.positionMs - sentPositionMs) <= START_NEAR_MS
        val atOffset = sent != null && (uri == sent || s.linkedUri == sent) && near
        // what played before our play took effect - unless it names no context and is the offset we sent at its place (M2)
        if (s.contextUri != context && !(s.contextUri == null && atOffset)) return emptyList()
        if (uri in prePlayUris) {
            // the track of the last state before the play: stale, unless it is the offset we sent near its position
            // (an artist has no offset: Spotify's start, near 0)
            if (!((sent == null || uri == sent) && near)) return emptyList()
        }
        if (s.paused) return emptyList() // loaded, not playing yet
        mode = Mode.OURS
        val changed = uri != liveUri
        liveUri = uri
        observe(s, nowMs)
        return listOf(Event.Started(0)) + if (changed) listOf(Event.TrackChanged(uri, s.title)) else emptyList()
    }

    private fun onContextOurs(s: SdkState, nowMs: Long): List<Event> {
        val uri = s.uri ?: return emptyList()
        if (leftoverAdd(s)) return listOf(Event.LeftoverAdd(uri, playing = !s.paused))
        leftoverUri = null
        val live = uri == liveUri || (s.linkedUri != null && s.linkedUri == liveUri)
        val named = namedNext(s)
        if (!namesOurContext(s, live, named)) {
            // not ours (M1, M2): a track of another, non-null context right after ours ran out is Autoplay after the end;
            // a uri list (no context) is no evidence of an end - paused, and the place stays
            val ended = s.contextUri != null && ranOut(nowMs)
            mode = Mode.LOST
            val events = mutableListOf<Event>(Event.NotOurs(playing = !s.paused))
            if (ended) events += contextEnd()
            return events
        }
        // the end, on evidence only (M1): our live track paused at 0 after it ran out, or Spotify back at another of the
        // context's tracks, paused, then - one our last state didn't name next (the next track posted paused while it
        // loads is not the end)
        if (s.paused && !pausePending && s.positionMs < END_RESET_MS && ranOut(nowMs) && (live || !named)) return contextEnd()
        if (live) return ourState(s, nowMs)
        // another track of the context: Spotify moved on (its own advance, a step through the SDK, the other controls)
        val wasPaused = lastPaused
        resetItem()
        measured = null
        liveUri = uri
        observe(s, nowMs)
        val events = mutableListOf<Event>(Event.TrackChanged(uri, s.title))
        when {
            s.paused -> {
                lastPaused = true
                if (!wasPaused) events += Event.Paused(ours = false)
            }
            wasPaused && !pausePending -> events += Event.Resumed
        }
        return events
    }

    /** The context played out (vibb's `clear_state` follows): every later playing state is paused. */
    private fun contextEnd(): List<Event> {
        lastPaused = true
        mode = Mode.ENDED
        return listOf(Event.Ended)
    }

    private fun onInactive(nowMs: Long): List<Event> {
        if (mode != Mode.OURS) return emptyList() // during a start the player isn't active yet; afterwards nothing is ours
        val first = nullAt
        if (first == null) {
            nullAt = nowMs
            return listOf(Event.Look)
        }
        if (nowMs - first < LOOK_MS) return emptyList()
        mode = Mode.LOST
        nullAt = null
        return listOf(Event.Elsewhere)
    }

    private fun onStarting(s: SdkState, nowMs: Long): List<Event> {
        if (stale(s)) return emptyList() // what played before our play took effect
        if (matches(s, index)) {
            if (s.paused) return emptyList() // loaded, not playing yet
            mode = Mode.OURS
            forget(s)
            observe(s, nowMs)
            return listOf(Event.Started(index)) + aheadAdd()
        }
        // Spotify skipped our first track(s) at once: a later uri of the window, playing, past every one
        // of them on the evidence of its `prev`, none of them an add of ours (H1).
        val uri = s.uri ?: return emptyList()
        val j = (index + 1..minOf(known, queue.size - 1)).firstOrNull { queue[it].uri == uri } ?: return emptyList()
        if (s.paused || !skipProven(s, index until j)) return emptyList()
        val events = mutableListOf<Event>()
        for (k in index until j) events += unplayable(k)
        if (mode == Mode.LOST) return events
        moveTo(j)
        mode = Mode.OURS
        forget(s)
        observe(s, nowMs)
        events += Event.Started(j)
        events += aheadAdd()
        return events
    }

    /**
     * A state of what the old play was or would be playing (H1, L1, qa-26c-step5a-code M3): its last
     * track, its expected next (Spotify's own advance while the call was on its way), an add of ours -
     * unless it is the sent track near the sent position.
     */
    private fun stale(s: SdkState): Boolean {
        val uri = s.uri ?: return false
        if (uri !in prePlayUris) return false
        return !(uri == queue[index].uri && abs(s.positionMs - sentPositionMs) <= START_NEAR_MS)
    }

    /** The next item of a play of ours that is on the player, or null. */
    private fun expectedNext(): String? =
        if (mode == Mode.OURS || mode == Mode.STARTING) queue.getOrNull(index + 1)?.uri else null

    private fun onOurs(s: SdkState, nowMs: Long): List<Event> {
        if (matches(s, index)) return ourState(s, nowMs)
        val next = index + 1
        if (next in queue.indices && matches(s, next)) {
            val skipped = if (!playedEnough(nowMs) && skipProven(s, index..index)) listOf(index) else emptyList()
            return advance(next, skipped, s, nowMs)
        }
        val later = s.uri?.let { uri -> (index + 2 until queue.size).firstOrNull { queue[it].uri == uri } }
        if (later != null && !isAdd(s) && later <= known) {
            val between = (index + 1 until later)
            val current = if (playedEnough(nowMs)) IntRange.EMPTY else index..index
            if (skipProven(s, between) && skipProven(s, current)) return advance(later, current.toList() + between.toList(), s, nowMs)
        }
        if (later != null || isAdd(s)) {
            // Ours by uri but out of our order, with no evidence of a skip: our own add left in Spotify's
            // queue (H1), or anything else that would take the list away from us. Paused; our list goes on.
            forget(s)
            return notOurs(s, nowMs, goOn = true)
        }
        return notOurs(s, nowMs, goOn = false)
    }

    /**
     * Not ours: [Event.NotOurs], and - when our track had just played out - the end ([Event.NewWindow]
     * from the next index, or [Event.Ended]: Autoplay); with [goOn] and ours still playing mid-track, a
     * new window from the current item at its place.
     */
    private fun notOurs(s: SdkState, nowMs: Long, goOn: Boolean): List<Event> {
        val ended = ranOut(nowMs)
        val wasPlaying = !lastPaused
        mode = Mode.LOST
        val events = mutableListOf<Event>(Event.NotOurs(playing = !s.paused))
        when {
            ended -> events += end()
            goOn && wasPlaying -> {
                lastPaused = true
                events += Event.NewWindow(index, resume = true)
            }
        }
        return events
    }

    /** A state of our current track. */
    private fun ourState(s: SdkState, nowMs: Long): List<Event> {
        if (s.paused) {
            if (!pausePending && ranOut(nowMs)) {
                if (s.positionMs < END_RESET_MS) return end()
                // N1: paused at its duration - the track ended (the element's `pause` before `ended`), never a pause
                if (atDuration(s)) return playedOut(nowMs)
            }
            val ours = pausePending
            pausePending = false
            observe(s, nowMs)
            if (lastPaused) return emptyList()
            lastPaused = true
            return listOf(Event.Paused(ours))
        }
        if (pausePending) return emptyList() // a state from before our pause took effect: nothing new
        val wasPaused = lastPaused
        observe(s, nowMs)
        return if (wasPaused) listOf(Event.Resumed) else emptyList()
    }

    /**
     * [s] is our current track paused at (or past) its duration: the end of the track, as the HTML audio element's
     * `pause` before its `ended` (N1). Within [END_RESET_MS] of the state's duration, else the listing's.
     */
    private fun atDuration(s: SdkState): Boolean {
        val duration = s.durationMs ?: durationSeen ?: queue.getOrNull(index)?.durationMs ?: return false
        return duration > 0 && s.positionMs >= duration - END_RESET_MS
    }

    /**
     * Our current track ended with a paused state at its duration (N1): while Spotify knows our next item (the window,
     * or our add one ahead) - or in a context, where Spotify picks the next - its own advance follows, and the state
     * after this one says where it went (still playing, the locks kept, nothing measured); with nothing of ours left on
     * Spotify's side it is the end, as the reset to 0 is. Spotify not following (the end of a context, a dropped add):
     * the page is asked again ([Event.Look]) and the same paused state [LOOK_MS] later is the end - a new window from
     * the next item, or the context's end - never the 30 s state gap's "trenger internett" (qa-26c-emu-music-code L2).
     */
    private fun playedOut(nowMs: Long): List<Event> {
        if (context == null && index + 1 > known) return end()
        val first = endHeldAt
        if (first == null) {
            endHeldAt = nowMs
            return listOf(Event.Look)
        }
        if (nowMs - first < LOOK_MS) return emptyList()
        endHeldAt = null
        return if (context != null) contextEnd() else end()
    }

    /** When our current track's paused-at-its-end state was first seen while Spotify should go on ([playedOut]). */
    private var endHeldAt: Long? = null

    /** Spotify moved on to [to] (the expected next, or a later uri of ours past [skipped] ones it proved it skipped). */
    private fun advance(to: Int, skipped: List<Int>, s: SdkState, nowMs: Long): List<Event> {
        val events = mutableListOf<Event>()
        for (k in skipped) events += unplayable(k)
        if (mode == Mode.LOST) return events
        val wasPaused = lastPaused
        moveTo(to)
        forget(s)
        events += Event.ItemChanged(to, auto = true)
        observe(s, nowMs)
        when {
            s.paused -> {
                lastPaused = true
                events += Event.Paused(ours = false)
            }
            // the next track plays after a paused state of ours that wasn't our pause (N1: a pause posted at a
            // track's end): playing again, as context mode's advance says it
            wasPaused && !pausePending -> events += Event.Resumed
        }
        events += aheadAdd()
        return events
    }

    /** Our current item played out with nothing of ours after it on the player. */
    private fun end(): List<Event> {
        val next = index + 1
        lastPaused = true
        return if (next >= queue.size) {
            mode = Mode.ENDED
            listOf(Event.Ended)
        } else {
            mode = Mode.LOST
            listOf(Event.NewWindow(next))
        }
    }

    private fun unplayable(k: Int): List<Event> {
        if (!reported.add(k)) return emptyList()
        unplayableRun++
        val stop = unplayableRun >= UNPLAYABLE_STOP
        if (stop) mode = Mode.LOST
        return listOf(Event.Unplayable(k, stop))
    }

    private fun aheadAdd(): List<Event> {
        val add = nextQueueAdd(index, known, queue.size) ?: return emptyList()
        if (add == addAsked) return emptyList()
        addAsked = add
        remember(queue[add].uri)
        return listOf(Event.QueueAdd(add))
    }

    /**
     * Spotify skipped every item of [items] (H1): each is named in the state's `prev`, and neither it nor
     * the state's own track is one we added to Spotify's queue. No evidence, no skip.
     */
    private fun skipProven(s: SdkState, items: IntRange): Boolean =
        !isAdd(s) && items.all { k -> queue[k].uri in s.prevUris && queue[k].uri !in adds }

    private fun isAdd(s: SdkState): Boolean = (s.uri != null && s.uri in adds) || (s.linkedUri != null && s.linkedUri in adds)

    private fun remember(uri: String) {
        adds.remove(uri)
        adds.add(uri)
        while (adds.size > ADDS_MAX) adds.remove(adds.first())
    }

    /** [s]'s track was seen playing where it plays: an add of it is used up. */
    private fun forget(s: SdkState) {
        s.uri?.let { adds.remove(it) }
        s.linkedUri?.let { adds.remove(it) }
    }

    private fun moveTo(to: Int) {
        index = to
        if (to > known) known = to
        resetItem()
        measured = null
    }

    private fun resetItem() {
        firstPlaying = null
        furthest = 0
        lastPlaying = null
        lastPlayingAt = 0
        durationSeen = null
        lastPaused = true
        nullAt = null
        endHeldAt = null
    }

    /** A state of ours for the current item: measured, and the end rule's and the unplayable rule's marks. */
    private fun observe(s: SdkState, nowMs: Long) {
        measured = Measured(index, s.positionMs, nowMs, playing = !s.paused)
        if (context != null) lastNext = s.nextUris
        s.durationMs?.let { durationSeen = it }
        if (!s.paused) {
            endHeldAt = null
            lastPaused = false
            if (firstPlaying == null) firstPlaying = s.positionMs
            if (s.positionMs > furthest) furthest = s.positionMs
            lastPlaying = s.positionMs
            lastPlayingAt = nowMs
            if (playedEnough(nowMs)) unplayableRun = 0
        }
    }

    /**
     * The current item played 3 s (its furthest playing position past its first, or - still playing -
     * the time since its last playing state carried on), or came within 3 s of its end: it isn't
     * unplayable. An item Spotify skipped never had a playing state of its own, or only a moment's.
     */
    private fun playedEnough(nowMs: Long): Boolean {
        val first = firstPlaying ?: return false
        val last = lastPlaying ?: first
        val carried = if (lastPaused) 0 else (nowMs - lastPlayingAt).coerceAtLeast(0)
        return maxOf(furthest - first, last - first + carried) >= PLAYED_MS || ranOut(nowMs)
    }

    /**
     * The current item has played out by now: its last playing position, carried on by the time since
     * that state (the page posts only every 5 s, so the last one can be up to 5 s short of the end), is
     * within [END_MARGIN_MS] of its duration or past it - and it was playing then. Only for reading
     * the end - never a position that is saved ([measured] stays as received).
     */
    private fun ranOut(nowMs: Long): Boolean {
        if (lastPaused) return false // a track that was paused didn't play out: the end follows a playing state
        val last = lastPlaying ?: return false
        val duration = durationSeen ?: queue.getOrNull(index)?.durationMs ?: return false
        val now = last + (nowMs - lastPlayingAt).coerceAtLeast(0)
        return duration > 0 && now >= duration - END_MARGIN_MS
    }

    /** Is [s]'s track queue item [k]: by uri, by `linked`, or by title and duration within 2 s. */
    private fun matches(s: SdkState, k: Int): Boolean {
        val item = queue.getOrNull(k) ?: return false
        if (s.uri != null && s.uri == item.uri) return true
        if (s.linkedUri != null && s.linkedUri == item.uri) return true
        val name = s.name ?: return false
        val duration = s.durationMs ?: return false
        val listed = item.durationMs ?: return false
        if (abs(duration - listed) > RELINK_DURATION_MS) return false
        val title = cutTitle(item.title)
        return title == listingTitle(name, s.artists) || (s.type == "episode" && title == cutTitle(name))
    }

    companion object {
        /** A `null` is confirmed by another this long after the first (the page's `vibb.state()` 2 s later). */
        const val LOOK_MS = 2_000L

        /** The end rule: the last playing position within this of the duration. */
        const val END_MARGIN_MS = 3_000L

        /**
         * ...and the paused state's position under this (Spotify shows the played-out track at 0) - or within this of
         * its duration (N1: a pause at the track's end).
         */
        const val END_RESET_MS = 1_000L

        /** A track that played this long isn't unplayable. */
        const val PLAYED_MS = 3_000L

        /** A relinked copy's duration is within this of the listing's. */
        const val RELINK_DURATION_MS = 2_000L

        /** After this many unplayable items in a row the renderer stops (§5.4: at most three calls). */
        const val UNPLAYABLE_STOP = 3

        /** A start's first state of the track that played before is ours only within this of the sent position (L1). */
        const val START_NEAR_MS = 3_000L

        /** At most this many remembered adds (one per window's end; a session never needs many). */
        const val ADDS_MAX = 64
    }
}
