package me.vibb.music.spotify

import me.vibb.music.logic.nextQueueAdd
import kotlin.math.abs

/*
 * The web player's state against what we told it (design 26c §5.4, QA H4, M5, L11): the commands we
 * sent and the states the page posts give the current queue index, measured positions, the natural
 * advance, the end of the window and of the queue, pauses, "not ours", "elsewhere" and unplayable
 * tracks. vibb's `play_origin` rule ("only a box-initiated session may write") is its ownership rule:
 *
 * - **Only our uris are ours.** A state is ours when its track is the one we sent for the current
 *   index, or the expected next item (the next of our queue) - matched by uri, by the SDK's `linked`
 *   (undocumented; the Web API dropped `linked_from` in February 2026), or by title and duration (the
 *   server's listing title built from the state equals the item's, and the durations are within 2 s:
 *   a relinked copy of the same recording). A later uri of the window Spotify skipped to is ours too
 *   (by uri only): the tracks between were skipped as unplayable.
 * - **Anything else is not ours** (the account's Autoplay after the window or the queue, another app
 *   transferring playback to "Vibb", a leftover session): [Event.NotOurs] - paused at once, never
 *   measured, never mapped to an index - and from then on every playing state is paused until our next
 *   play. After [Event.Ended], three unplayable in a row, or a confirmed `null`, the same.
 * - **The end of a track** is read from the state: our current track paused at position 0 after its
 *   last playing position came within 3 s of its duration, with no pause of ours pending (or a track
 *   not ours playing then: Autoplay). Spotify's own advance shows as the expected next uri.
 * - **Unplayable** (QA M5) only from the SDK: a `playback_error` on our current track, or Spotify
 *   skipping past one of ours (a later uri of ours before the current one played 3 s). After three in
 *   a row the renderer stops.
 * - **A `null` state** is confirmed before it counts (QA L11): [Event.Look] asks the page again; a
 *   `null` 2 s or more after the first, or a `not_ready`, is [Event.Elsewhere].
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

        /** Spotify's list ran out before ours (a queue add that failed or came late): start a new window at [index]. */
        data class NewWindow(val index: Int) : Event

        /** Add queue item [index] to Spotify's queue now: one track ahead (§5.4, "Past the window"). */
        data class QueueAdd(val index: Int) : Event

        /** A first `null` state: ask the page for its state again ([LOOK_MS]). */
        data object Look : Event

        /** A confirmed `null`: the account plays elsewhere. The bookmark is [measured]; a play takes it back. */
        data object Elsewhere : Event
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

    /** The last queue index Spotify knows: the window's end, or the last item added to its queue. */
    private var known = -1
    private var addAsked = -1
    private var pausePending = false
    private var lastPaused = true
    private var nullAt: Long? = null

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
        this.queue = queue
        this.index = index.coerceIn(0, (queue.size - 1).coerceAtLeast(0))
        mode = Mode.IDLE
        known = -1
        addAsked = -1
        measured = null
        reported.clear()
        unplayableRun = 0
        resetItem()
    }

    /**
     * A play call for [index] answered 204, its window ending at [known] (`index + window size - 1`).
     * [fresh] = the kid's command (a tap, a step, a resume after a drop):
     * the unplayable run starts again; false = the renderer moving on by itself (after an unplayable
     * item, a window's gap).
     */
    fun played(index: Int, known: Int, fresh: Boolean = true): List<Event> {
        if (index !in queue.indices) return emptyList()
        this.index = index
        this.known = known.coerceIn(index, queue.size - 1)
        mode = Mode.STARTING
        addAsked = -1
        pausePending = false
        reported.clear()
        if (fresh) unplayableRun = 0
        resetItem()
        measured = null // the start position is the stored bookmark, not a measurement
        return emptyList()
    }

    /** Queue item [index] was added to Spotify's queue (the add's 204). */
    fun queued(index: Int): List<Event> {
        if (index !in queue.indices || index <= known) return emptyList()
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

    /** The SDK's `playback_error`: our current track can't be played. */
    fun onPlaybackError(): List<Event> {
        if (mode != Mode.OURS && mode != Mode.STARTING) return emptyList()
        return unplayable(index)
    }

    /** One posted state ([SdkState.parse]) at [nowMs] (the elapsed clock). */
    fun onState(snapshot: SdkSnapshot, nowMs: Long): List<Event> {
        val s = when (snapshot) {
            Inactive -> return onInactive(nowMs)
            is SdkState -> snapshot
        }
        return when (mode) {
            Mode.IDLE, Mode.ENDED, Mode.LOST -> if (s.paused) emptyList() else listOf(Event.NotOurs(playing = true))
            Mode.STARTING -> onStarting(s, nowMs)
            Mode.OURS -> onOurs(s, nowMs)
        }
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
        if (matches(s, index)) {
            if (s.paused) return emptyList() // loaded, not playing yet
            mode = Mode.OURS
            observe(s, nowMs)
            return listOf(Event.Started(index)) + aheadAdd()
        }
        // Spotify skipped our first track(s) at once: a later uri of the window, playing, with ours among its previous ones.
        val j = s.uri?.let { uri -> (index + 1..minOf(known, queue.size - 1)).firstOrNull { queue[it].uri == uri } }
        if (j != null && !s.paused && queue[index].uri in s.prevUris) {
            val events = mutableListOf<Event>()
            for (k in index until j) events += unplayable(k)
            if (mode == Mode.LOST) return events
            moveTo(j)
            mode = Mode.OURS
            observe(s, nowMs)
            events += Event.Started(j)
            events += aheadAdd()
            return events
        }
        return emptyList() // a stale state of what played before our play took effect
    }

    private fun onOurs(s: SdkState, nowMs: Long): List<Event> {
        nullAt = null
        if (matches(s, index)) return ourState(s, nowMs)
        val next = index + 1
        if (next in queue.indices && matches(s, next)) return advance(next, s, nowMs)
        val later = laterOfOurs(s)
        if (later != null) return advance(later, s, nowMs)
        // Not ours. When our track had just played out, this is Spotify's Autoplay after our list.
        val ended = ranOut(nowMs)
        mode = Mode.LOST
        val events = mutableListOf<Event>(Event.NotOurs(playing = !s.paused))
        if (ended) events += end()
        return events
    }

    /** A state of our current track. */
    private fun ourState(s: SdkState, nowMs: Long): List<Event> {
        if (s.paused) {
            if (!pausePending && s.positionMs < END_RESET_MS && ranOut(nowMs)) return end()
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

    /** Spotify moved on to [to] (the expected next, or a later uri of ours past skipped ones). */
    private fun advance(to: Int, s: SdkState, nowMs: Long): List<Event> {
        val events = mutableListOf<Event>()
        if (!playedEnough(nowMs)) events += unplayable(index)
        for (k in index + 1 until to) events += unplayable(k)
        if (mode == Mode.LOST) return events
        moveTo(to)
        events += Event.ItemChanged(to, auto = true)
        observe(s, nowMs)
        if (s.paused) {
            lastPaused = true
            events += Event.Paused(ours = false)
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
        return listOf(Event.QueueAdd(add))
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
    }

    /** A state of ours for the current item: measured, and the end rule's and the unplayable rule's marks. */
    private fun observe(s: SdkState, nowMs: Long) {
        measured = Measured(index, s.positionMs, nowMs, playing = !s.paused)
        s.durationMs?.let { durationSeen = it }
        if (!s.paused) {
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

    /** A later uri of the window Spotify knows (by uri only), past the expected next. */
    private fun laterOfOurs(s: SdkState): Int? {
        val uri = s.uri ?: return null
        for (k in index + 2..minOf(known, queue.size - 1)) if (queue[k].uri == uri) return k
        return null
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

        /** ...and the paused state's position under this (Spotify shows the played-out track at 0). */
        const val END_RESET_MS = 1_000L

        /** A track that played this long isn't unplayable. */
        const val PLAYED_MS = 3_000L

        /** A relinked copy's duration is within this of the listing's. */
        const val RELINK_DURATION_MS = 2_000L

        /** After this many unplayable items in a row the renderer stops (§5.4: at most three calls). */
        const val UNPLAYABLE_STOP = 3
    }
}
