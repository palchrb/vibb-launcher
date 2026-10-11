package me.vibb.music.sonos

import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.PauseCause
import me.vibb.music.playback.RendererState
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The controller side of a Sonos session, ported from palchrb/vibb `pi/daemon.py` (the `_sonos_*`
 * fields and methods: `_sonos_position`, `_sonos_bookmark_now`, `_sonos_play_entry`, `sonos_step` and
 * its worker, `seek` and `_sonos_seek_worker`, `_sonos_command`, `pause`, `unpause`, `play`'s same-tile
 * shortcut, `_sonos_stream_moved`, `_sonos_poller`'s tick, `_renderer_to_box`'s first half, and
 * `status`'s renderer fields; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under
 * the GPL-3.0 (or later) - see NOTICE.md. Design 26a §3.2, §4.2-§4.6, §4.8, §5.2.
 *
 * Main-thread state (design 26a §0, QA M2): every method runs on the main thread and returns at once;
 * the SOAP goes through [SonosWork] (the actor) and its result comes back to the main thread in the
 * order the actor ran it. Intent is expressed through the optimistic fields (optTr, optPos, pending),
 * exactly as vibb's daemon does against its sidecar. Pure: the clock, the actor and the bookmark
 * store are injected, so SonosControllerTest drives it with a recording speaker.
 *
 * Positions: the snapshot holds what the speaker MEASURED (plus the duration carry). A seek's target,
 * the pause freeze and the extrapolation are display facts kept beside it, so the bookmark - the
 * only write, [bookmarkNow] - can never persist a guess (QA H3).
 */

/** The actor (design 26a §0): SOAP in order on one IO consumer, results back on the main thread. */
interface SonosWork {
    /** Run [job] after everything queued before it; [then] runs on the main thread with its result. */
    fun <T> submit(job: suspend () -> T, then: (Result<T>) -> Unit)

    /** [block] on the main thread after [delayMs] (the seek spacing). */
    fun after(delayMs: Long, block: () -> Unit)
}

/** Where a bookmark goes (`Bookmarks.save`: the item's position, the entry's bookmark, the stored queue). */
interface SonosStore {
    /** The saved place of [itemKey] in ms (vibb's `episode_pos`: 0 when unknown or done). */
    fun savedPosMs(itemKey: String): Long

    /**
     * Persist [posMs] of item [index] ([itemKey]); [durationMs] decides "heard to the end" and is the
     * speaker's own measurement or null; [shownDurationMs] is only for the stored now-playing row.
     * With [force] the write lands before this returns.
     */
    fun save(index: Int, itemKey: String, posMs: Long, durationMs: Long?, shownDurationMs: Long?, force: Boolean)
}

/** What the controller tells its renderer. */
interface SonosHost {
    /** The state moved: refresh the player and report (on the main thread; maybe inside a player handler). */
    fun changed()

    /** A play landed: renderer.json learns the built uri (what a later adopt compares against). */
    fun played(itemKey: String, uri: String) {}

    /** Migration follow: renderer.json gets the new room (after the adopt, never before). */
    fun followed(uid: String, name: String?) {}

    /** The sleep ramp runs from [volume] (null: it ended, the volume is back). */
    fun ramp(volume: Int?) {}

    /** Whether the speaker plays for us now (3b holds its wake and Wi-Fi locks across this). */
    fun armedPlaying(playing: Boolean) {}

    fun log(message: String) {}
}

/** The player's phase (Media3's playback states; mapped in SonosRenderer). */
enum class PlayPhase { IDLE, BUFFERING, READY, ENDED }

/**
 * What the player shows (design 26a §3.2's state table): [positionMs] at [anchorAt]; while [moving]
 * it advances with the clock from there (at most [MAX_EXTRAPOLATE_MS]), never past [durationMs].
 */
data class SonosSpec(
    val phase: PlayPhase,
    val playWhenReady: Boolean,
    val index: Int,
    val positionMs: Long,
    val anchorAt: Long,
    val moving: Boolean,
    val durationMs: Long?,
) {
    fun positionAt(nowMs: Long): Long {
        val p = if (moving) positionMs + (nowMs - anchorAt).coerceIn(0, MAX_EXTRAPOLATE_MS) else positionMs
        return durationMs?.let { p.coerceAtMost(it) } ?: p
    }

    companion object {
        /** vibb never extrapolates past ~60 s of staleness (`_sonos_position`). */
        const val MAX_EXTRAPOLATE_MS = 60_000L
    }
}

/** Why a play went out: a start's failure reverts the renderer (§4.2); a step's or an advance's is dropped. */
enum class PlayWhy { START, STEP, ADVANCE }

class SonosController(
    private val transport: SonosTransport,
    private val work: SonosWork,
    private val store: SonosStore,
    private val elapsedMs: () -> Long,
    uid: String,
    name: String?,
    private val host: SonosHost,
    /** The ramp's waits (the actor's clock). */
    private val sleep: suspend (Long) -> Unit,
    /** A volume to put back with the next play: a sleep ramp the process didn't live to finish (§5.2). */
    private var restoreVolume: Int? = null,
) {
    /** The room: the uid we command (a group by its coordinator), and its label. Migration follow moves them. */
    var uid: String = uid
        private set
    var name: String? = name
        private set

    var entry: Entry? = null
        private set

    /** The queue as the service holds it (`Active.items`, already speaker-playable): never filtered here. */
    var items: List<PlayItem> = emptyList()
        private set

    /** The current index (optimistic while a step is in flight). */
    var index: Int = 0
        private set

    /** A play went out for this queue and no stop, disarm or hand-off since: the speaker is ours to drive. */
    var session: Boolean = false
        private set

    /** After a hand-off or a release: nothing is sent or written any more. */
    var closed: Boolean = false
        private set

    // Not yet started (a queue loaded paused): where it starts.
    private var startPosMs = 0L
    private var deliberateStart = false

    /** The last snapshot: measured values only, plus the duration carry. */
    var snap: Fields? = null
        private set
    private var durFromListing = false

    /** The snapshot is the seed of our own play (nothing measured yet). */
    private var seeded = false
    private var lastOursRel: Double? = null
    private var lastOursDur: Double? = null

    private class Opt(val state: TransportState, val at: Long)

    private class OptPos(val s: Double, val at: Long, val uri: String?)

    private class Pending(val itemKey: String, var at: Long, var uri: String?)

    /** The item actually on the speaker: what the bookmark is about. */
    private class Playing(val index: Int, val itemKey: String, val uri: String)

    private var optTr: Opt? = null
    private var optPos: OptPos? = null
    private var frozen: OptPos? = null
    private var pending: Pending? = null
    private var playing: Playing? = null
    private var bmHold: BmHold? = null
    private var seekAt = FAR_PAST

    private var stepWant: Int? = null
    private var stepping = false
    private var seekWant: Double? = null
    private var seeking = false
    private var endsNear = 0
    private var adopting = false
    private var fade: AtomicBoolean? = null

    /** Our own last volume set: the base of the next press (vibb's `_sonos_vol_opt`). */
    var volOpt: Int? = null
        private set

    /** The last playWhenReady the player showed: held through a transient (§3.2). */
    private var heldPwr = false
    private var autoFlag = false

    /** The queue played to its end on the speaker (the player shows ENDED until a new load). */
    var ended: Boolean = false
        private set
    private var endedFlag = false
    private var lost: Exception? = null
    private var wasArmedPlaying = false

    // -- what the renderer reads --

    /** True once per natural advance: the next index change is an automatic one. */
    fun takeAuto(): Boolean = autoFlag.also { autoFlag = false }

    /** True once when the queue played to its end. */
    fun takeEnded(): Boolean = endedFlag.also { endedFlag = false }

    /** A start that failed (once): the renderer reports it and the service goes back to the phone. */
    fun takeLost(): Exception? = lost.also { lost = null }

    private fun opt(now: Long): Opt? = optTr?.takeIf { now - it.at <= SETTLE_MS }

    /** The transport the screens should believe: our own verb's while it is held, else the speaker's. */
    fun effectiveTransport(now: Long): TransportState? = opt(now)?.state ?: snap?.transport

    /**
     * `status()`'s `playing` (design 26a §3.3): a fresh snapshot, PLAYING, reachable, ours. What the
     * play button, `setNowPlaying` and screen time read; a stale snapshot is not playing.
     */
    fun showsPlaying(now: Long): Boolean {
        val s = snap ?: return false
        return session && s.fresh(now) && effectiveTransport(now) == TransportState.PLAYING && s.reachable && s.ours
    }

    /** `status()`'s `renderer_state`, in its precedence (daemon.py `status`, sonos_grouped.py check 2). */
    fun rendererState(now: Long): RendererState? {
        if (!session) return null
        val s = snap ?: return null
        if (seeded || pending != null) return null
        if (!s.fresh(now)) return RendererState.UNREACHABLE
        return when {
            s.groupedAway -> RendererState.GROUPED_AWAY
            !s.ours && s.foreignUri != null -> RendererState.TAKEN_OVER
            s.lostSession -> RendererState.LOST_SESSION
            else -> null
        }
    }

    /** The volume the slider shows: our last set, else the last read. */
    fun deviceVolume(): Int = (volOpt ?: snap?.volume ?: 0).coerceIn(0, 100)

    /** Why the player stopped wanting to play: ours or the speaker's own pause is USER (the sleep timer ends). */
    fun pauseCause(now: Long): PauseCause =
        if (effectiveTransport(now) == TransportState.PAUSED_PLAYBACK && snap?.ours == true) PauseCause.USER else PauseCause.OTHER

    /**
     * `_sonos_position` (§4.3): a held seek target wins while <= 12 s old and on the same uri, then the
     * pause freeze; otherwise the measured position of OUR stream - plus the one age term while it
     * plays - clamped to the duration. Not ours: the last ours position (a grouped-away member reports
     * the parent's RelTime, never shown). Before a start: where it will start.
     */
    fun spec(now: Long): SonosSpec {
        val idx = index
        if (items.isEmpty()) return SonosSpec(if (ended) PlayPhase.ENDED else PlayPhase.IDLE, false, 0, 0, now, false, null)
        val listed = items.getOrNull(idx)?.durationMs
        if (!session) {
            val shown = if (playing == null) startPosMs else ((lastOursRel ?: 0.0) * 1000).toLong()
            return SonosSpec(if (ended) PlayPhase.ENDED else PlayPhase.IDLE, false, idx, shown, now, false, currentDurS()?.let { (it * 1000).toLong() } ?: listed)
        }
        val s = snap
        val tr = effectiveTransport(now)
        val dur = currentDurS()?.let { (it * 1000).toLong() } ?: listed
        val (pos, anchor, moving) = position(now)
        fun spec(phase: PlayPhase, pwr: Boolean) = SonosSpec(phase, pwr, idx, pos, anchor, moving && pwr, dur)
        val p = pending
        return when {
            // a play in flight: the speaker may still report the old track (its settle window)
            p != null -> spec(PlayPhase.BUFFERING, opt(now)?.state?.let { it == TransportState.PLAYING } ?: heldPwr)
            s == null -> spec(PlayPhase.BUFFERING, heldPwr)
            // the first failed polls are a transient: BUFFERING keeps the FGS (one failure must never read IDLE)
            !s.reachable -> if (s.failedPolls <= TRANSIENT_FAILURES) spec(PlayPhase.BUFFERING, heldPwr) else spec(PlayPhase.READY, false)
            s.transitioning -> spec(PlayPhase.BUFFERING, heldPwr)
            !s.fresh(now) -> spec(PlayPhase.READY, false)
            !s.ours -> spec(PlayPhase.READY, false)
            tr == TransportState.PLAYING -> spec(PlayPhase.READY, true)
            else -> spec(PlayPhase.READY, false)
        }
    }

    /** Called by the renderer after each refresh: the playWhenReady it showed (held through transients). */
    fun shown(spec: SonosSpec) {
        heldPwr = spec.playWhenReady
        val armedPlaying = session && (spec.playWhenReady || seeking || stepping)
        if (armedPlaying != wasArmedPlaying) {
            wasArmedPlaying = armedPlaying
            host.armedPlaying(armedPlaying)
        }
    }

    private fun currentDurS(): Double? {
        val s = snap ?: return lastOursDur
        return if (s.ours) s.durS?.takeIf { it > 0 } ?: lastOursDur else lastOursDur
    }

    /** (position ms at anchor, anchor, moving). */
    private fun position(now: Long): Triple<Long, Long, Boolean> {
        val s = snap
        optPos?.let { o -> if (now - o.at <= SEEK_HOLD_MS && o.uri == s?.uri) return Triple((o.s * 1000).toLong(), now, false) }
        frozen?.let { f -> if (opt(now)?.state == TransportState.PAUSED_PLAYBACK) return Triple((f.s * 1000).toLong(), now, false) }
        val last = ((lastOursRel ?: 0.0) * 1000).toLong()
        if (s == null || !s.ours) return Triple(last, now, false)
        val rel = s.relS ?: return Triple(last, now, false)
        val relMs = (rel * 1000).toLong()
        val measuredAt = s.measuredAt ?: return Triple(relMs, now, false)
        if (!s.fresh(now)) return Triple(relMs, now, false)
        return when {
            // one age term: the poll's own measuredAt (vibb summed two clocks across its process split)
            s.transport == TransportState.PLAYING && opt(now)?.state != TransportState.PAUSED_PLAYBACK -> Triple(relMs, measuredAt, true)
            // our resume while the speaker still reads PAUSED: it plays from our verb on
            opt(now)?.state == TransportState.PLAYING -> Triple(relMs, opt(now)!!.at, true)
            else -> Triple(relMs, now, false)
        }
    }

    // -- the queue --

    /**
     * A new queue (`Active.items` - the speaker-playable ones; §4.2), started by [play]. [startMs] is
     * where it starts; starting below the item's saved place is a deliberate restart (QA L4: the
     * regression guard must not keep the old bookmark against the kid's own choice).
     */
    fun load(entry: Entry, items: List<PlayItem>, startIndex: Int, startMs: Long) {
        if (closed) return
        this.entry = entry
        this.items = items
        index = if (items.isEmpty()) 0 else startIndex.coerceIn(items.indices)
        startPosMs = startMs.coerceAtLeast(0)
        deliberateStart = items.getOrNull(index)?.let { startPosMs < store.savedPosMs(it.key) } ?: false
        session = false
        playing = null
        pending = null
        optTr = null
        optPos = null
        frozen = null
        bmHold = null
        stepWant = null
        seekWant = null
        endsNear = 0
        ended = false
        endedFlag = false
        host.changed()
    }

    /** Shuffle on Sonos keeps the current item playing; [after] follow it (the service's order). */
    fun reorder(keep: Int, after: List<PlayItem>) {
        val current = items.getOrNull(keep) ?: return
        items = listOf(current) + after
        index = 0
        playing = playing?.let { Playing(0, it.itemKey, it.uri) }
        host.changed()
    }

    /** The queue is over (the service's `queueEnded`, or a clear): nothing more to show. */
    fun clear() {
        items = emptyList()
        index = 0
        session = false
        playing = null
        pending = null
        optTr = null
        stepWant = null
        host.changed()
    }

    // -- verbs (§4.5) --

    /**
     * The player's play (playpause's resume side, `_sonos_command`): not ours-and-live -> a full start
     * from the bookmark (a STOPPED speaker has no position to resume from, so a bare resume would
     * restart the track at 0); PAUSED -> one resume; PLAYING -> nothing.
     */
    fun playPause() {
        if (closed || items.isEmpty()) return
        if (!session) return start()
        if (pending != null) return // a play is in flight: it is the answer
        val now = elapsedMs()
        val s = snap
        val live = s != null && s.fresh(now) && s.ours && (effectiveTransport(now)?.live == true)
        when {
            !live -> restart()
            effectiveTransport(now) == TransportState.PAUSED_PLAYBACK -> resume()
            else -> Unit
        }
        host.changed()
    }

    /**
     * A tap on the loaded entry (`play()`'s same-tile shortcut, sonos_same_tile.py): only a steady
     * live session of ours - fresh, ours, its item known, nothing settling - is trusted: PLAYING ->
     * nothing is sent, PAUSED -> one resume. Anything else (stale, grouped away, a press in flight)
     * is a full start to OUR OWN uid, never the coordinator: the transfer is the heal.
     */
    fun playTile() {
        if (closed || items.isEmpty()) return
        if (!session) return start()
        val now = elapsedMs()
        val s = snap
        val settling = pending != null || opt(now) != null
        val steady = s != null && s.fresh(now) && s.ours && !seeded && !settling && playing?.index == index
        when {
            steady && s!!.transport == TransportState.PLAYING -> Unit
            steady && s!!.transport == TransportState.PAUSED_PLAYBACK -> resume()
            else -> restart()
        }
        host.changed()
    }

    /** The first play of a loaded queue; after a session (a stop, a disarm) a full start from the bookmark. */
    private fun start() {
        if (playing != null) restart() else playIndex(index, startPosMs / 1000.0, deliberateStart, PlayWhy.START)
        host.changed()
    }

    /** A full start of the current item from its saved place (`sonos_start_target`'s bookmark). */
    private fun restart() {
        val item = items.getOrNull(index) ?: return
        playIndex(index, store.savedPosMs(item.key) / 1000.0, deliberate = false, PlayWhy.START)
    }

    private fun resume() {
        val now = elapsedMs()
        optTr = Opt(TransportState.PLAYING, now)
        frozen = null
        val target = uid
        work.submit({ transport.resume(target) }) { r ->
            r.exceptionOrNull()?.let {
                host.log("sonos: resume failed (${it.javaClass.simpleName})")
                optTr = null
            }
            host.changed()
        }
    }

    /**
     * `pause()`: ours-gated on the raw snapshot (grouped away or taken over, the speaker carries
     * someone else's stream and is not ours to pause); the bookmark first, then the verb, with the
     * extrapolated position frozen for the screen only.
     */
    fun pause() {
        if (closed || !session) return
        // a play in flight is ours: its pause follows it through the actor
        if (pending == null && snap?.ours != true) return
        val now = elapsedMs()
        bookmarkNow(force = false)
        if (effectiveTransport(now) == TransportState.PLAYING) {
            frozen = OptPos(spec(now).positionAt(now) / 1000.0, now, snap?.uri)
        }
        optTr = Opt(TransportState.PAUSED_PLAYBACK, now)
        val target = uid
        work.submit({ transport.pause(target) }) { r ->
            r.exceptionOrNull()?.let {
                host.log("sonos: pause failed (${it.javaClass.simpleName})")
                optTr = null
                frozen = null
            }
            host.changed()
        }
        host.changed()
    }

    /** The player's stop: the bookmark, then Stop when the stream is ours; the session is over. */
    fun stop() {
        if (closed || !session) return
        bookmarkNow(force = true)
        val ours = snap?.ours == true
        endSession()
        if (ours) {
            val target = uid
            work.submit({ transport.stop(target) }) { r ->
                r.exceptionOrNull()?.let { host.log("sonos: stop failed (${it.javaClass.simpleName})") }
                host.changed()
            }
        }
        host.changed()
    }

    private fun endSession() {
        session = false
        pending = null
        optTr = null
        optPos = null
        frozen = null
        stepWant = null
        seekWant = null
    }

    /**
     * `seek` (§4.5): an absolute target clamped short of the end (`SEEK_TAIL_S`); no duration (a live
     * stream, a stale snapshot) refuses it. Coalesced, latest wins, 0.7 s between SOAPs; the target is
     * held for the screen ([optPos]) and never written into the snapshot or the bookmark. A deliberate
     * seek releases the refused-seek hold and opens the regression guard.
     */
    fun seek(ms: Long) {
        if (closed || items.isEmpty()) return
        val now = elapsedMs()
        if (!session) {
            startPosMs = ms.coerceAtLeast(0)
            deliberateStart = items.getOrNull(index)?.let { startPosMs < store.savedPosMs(it.key) } ?: false
            playing = null
            host.changed()
            return
        }
        val s = snap?.takeIf { it.fresh(now) } ?: return host.log("seek refused: nothing fresh")
        val dur = currentDurS() ?: return host.log("seek refused: live (no duration)")
        val target = (ms / 1000.0).coerceAtMost(dur - SEEK_TAIL_S).coerceAtLeast(0.0)
        seekWant = target
        optPos = OptPos(target, now, s.uri)
        frozen = null
        bmHold = null
        seekAt = now
        if (!seeking) {
            seeking = true
            seekNext()
        }
        host.changed()
    }

    private fun seekNext() {
        val want = seekWant
        if (want == null || closed || !session) {
            seeking = false
            seekWant = null
            return
        }
        seekWant = null
        val target = uid
        work.submit({ transport.seek(want, target) }) { r ->
            r.exceptionOrNull()?.let { host.log("sonos: seek failed (${it.javaClass.simpleName}) - the poller re-reads the truth") }
            // pace outside the actor; latest-wins keeps the final target
            work.after(SEEK_SPACING_MS) { seekNext() }
        }
    }

    /**
     * Another item (`sonos_step`, §4.5): the index flips at once (optimistic), one worker plays the
     * LATEST wanted index from 0 (`playIndex(want, 0, deliberate)`), a mash costs one play per
     * settled want. Before a start it only moves where the queue will start ([ms]).
     */
    fun stepTo(idx: Int, ms: Long) {
        if (closed || idx !in items.indices) return
        if (!session) {
            index = idx
            startPosMs = ms.coerceAtLeast(0)
            deliberateStart = startPosMs < store.savedPosMs(items[idx].key)
            playing = null
            host.changed()
            return
        }
        val now = elapsedMs()
        pending = Pending(items[idx].key, now, null)
        stepWant = idx
        index = idx
        endsNear = 0
        optPos = null
        frozen = null
        if (!stepping) {
            stepping = true
            stepNext()
        }
        host.changed()
    }

    private fun stepNext() {
        val want = stepWant
        if (want == null || closed || !session) {
            stepping = false
            stepWant = null
            return
        }
        stepWant = null
        // MUST NOT wedge: whatever happens, the next want is taken (vibb field 2026-09-01)
        playIndex(want, 0.0, deliberate = true, PlayWhy.STEP) { stepNext() }
    }

    /**
     * `_sonos_play_entry` (§4.2): the body built ONCE, the play in the actor; on success the index is
     * confirmed (unless a newer press is on the screen), pending and optTr hold against the old track's
     * polls, the snapshot is seeded at the start position (shown, never bookmarked), and a refused
     * start-seek arms the bounded hold. A deliberate restart stamps [seekAt].
     */
    private fun playIndex(idx: Int, startS: Double, deliberate: Boolean, why: PlayWhy, done: () -> Unit = {}) {
        val e = entry
        val item = items.getOrNull(idx)
        val body = if (e != null && item != null) PlayBodies.build(e, item, uid, startS) else null
        if (body == null || item == null) {
            host.log("sonos: nothing a speaker can play at ${idx + 1}")
            failed(why, SonosException("nothing-remote-playable"))
            done()
            return
        }
        val now = elapsedMs()
        session = true
        ended = false
        if (deliberate) seekAt = now
        index = idx
        pending = Pending(item.key, now, null)
        optTr = Opt(TransportState.PLAYING, now)
        optPos = null
        frozen = null
        bmHold = null
        endsNear = 0
        val volume = restoreVolume
        val target = uid
        work.submit({
            val result = transport.play(body)
            if (volume != null) {
                try {
                    transport.volume(volume, target)
                } catch (_: SonosException) {
                    // best effort: the next play tries again
                }
            }
            result
        }) { r ->
            try {
                r.fold({ played(idx, item, it, startS, volume) }, { failed(why, it) })
            } catch (t: Exception) {
                host.log("sonos: play result failed (${t.javaClass.simpleName})")
            } finally {
                done()
                host.changed()
            }
        }
    }

    private fun played(idx: Int, item: PlayItem, res: PlayResult, startS: Double, restored: Int?) {
        if (closed) return
        val now = elapsedMs()
        if (restored != null) {
            restoreVolume = null
            host.ramp(null)
        }
        if (stepWant == null) index = idx // no newer press: confirm (a completed older jump never blinks the title back)
        playing = Playing(idx, item.key, res.uri)
        pending = Pending(item.key, now, res.uri)
        // a pause pressed while the play was in flight went out after it: that intent stands
        if (optTr?.state != TransportState.PAUSED_PLAYBACK) optTr = Opt(TransportState.PLAYING, now)
        val listed = item.durationMs?.let { it / 1000.0 }
        snap = Fields(
            armed = true, uid = res.uid, kind = transport.session.kind, seq = -1, measuredAt = now,
            reachable = true, transport = TransportState.PLAYING, relS = startS, durS = listed, uri = res.uri, ours = true,
            volume = snap?.volume,
        )
        durFromListing = listed != null
        seeded = true
        lastOursRel = startS
        lastOursDur = listed
        if (startS >= 5 && !res.sought) {
            bmHold = BmHold(item.key, startS, now)
            host.log("sonos: seek refused - playing from the top, bookmark held at ${startS.toInt()}s")
        }
        host.played(item.key, res.uri)
        host.log("sonos: playing [${idx + 1}/${items.size}] ${item.title}")
    }

    private fun failed(why: PlayWhy, e: Throwable) {
        host.log("sonos: play failed (${e.javaClass.simpleName}: ${e.message}) [$why]")
        pending = null
        optTr = null
        when (why) {
            PlayWhy.START -> {
                endSession()
                lost = (e as? Exception) ?: SonosException(e.toString())
            }
            // a lost step is a shrug, a dead coalescer is a brick: back to what the speaker plays
            PlayWhy.STEP, PlayWhy.ADVANCE -> if (stepWant == null) playing?.let { index = it.index }
        }
    }

    // -- the poll (§4.3) --

    /**
     * One poll's snapshot, on the main thread (`_sonos_poller`'s tick). In order: the settle window
     * of a play in flight, the duration carry, the optimistic transport and position, migration
     * follow (before the ours-gate: the migration window IS a not-ours window), the gate `fresh &&
     * ours`, the bookmark and the end-of-episode advance. One guard for the whole tick: a tick that
     * throws never stops the next one.
     */
    fun onPoll(tick: Tick, fields: Fields) {
        if (closed) return
        try {
            pollTick(tick, fields)
        } catch (e: Exception) {
            host.log("sonos poll tick failed: $e")
        }
        host.changed()
    }

    private fun pollTick(tick: Tick, polled: Fields) {
        if (tick == Tick.Idle || !session) return
        if (polled.uid != uid) return // a snapshot of a session we no longer drive (a re-pick, a follow)
        val now = elapsedMs()
        val p = pending
        if (p != null) {
            val landed = p.uri != null && polled.reachable && polled.ours && normUri(polled.uri) == normUri(p.uri)
            if (landed || (p.uri != null && now - p.at > SETTLE_MS)) {
                pending = null
            } else {
                return // settle: the speaker may still report the PREVIOUS track; the seed stands
            }
        }
        if (tick == Tick.Disarmed || !polled.armed) {
            accept(polled)
            endSession()
            host.log("sonos: the session stood down")
            return
        }
        accept(polled)
        val s = snap ?: return
        optTr?.let { o -> if (s.transport == o.state || now - o.at > SETTLE_MS) optTr = null }
        if (optTr == null) frozen = null
        optPos?.let { o ->
            val rel = s.relS
            val landed = rel != null && abs(rel - o.s) <= SEEK_TOL_S
            if (landed || now - o.at > SEEK_HOLD_MS || o.uri != s.uri) optPos = null
        }
        if (follow(s, now)) return
        if (!s.fresh(now) || !s.ours) return // foreign, hijacked or old data: observe, never act
        val rel = s.relS
        val dur = s.durS
        if (rel != null && s.transport?.live == true) {
            bookmarkNow(force = false)
            endsNear = if (dur != null && dur > 0 && rel > dur - ENDS_NEAR_S) endsNear + 1 else 0
        }
        if (s.transport == TransportState.STOPPED && endsNear > 0 && playing != null) {
            endsNear = 0
            advance()
        }
    }

    /**
     * A measured snapshot becomes the controller's, with the duration carry (4398-4406): a falsy
     * duration (a Sonos reports 0:00 for an extension-less url until buffered) takes the listing's for
     * OUR item, flagged so it never decides "heard to the end", else the previous value - only for the
     * same uri: a length dragged across a track change destroys bookmarks.
     */
    private fun accept(polled: Fields) {
        var s = polled
        var fromListing = false
        if (s.durS == null || s.durS == 0.0) {
            val listed = playing?.takeIf { s.ours }?.let { pl -> items.getOrNull(pl.index)?.takeIf { it.key == pl.itemKey }?.durationMs }
            val prev = snap
            if (listed != null) {
                s = s.copy(durS = listed / 1000.0)
                fromListing = true
            } else if (prev != null && (prev.durS ?: 0.0) > 0 && !prev.uri.isNullOrEmpty() && prev.uri == s.uri) {
                s = s.copy(durS = prev.durS)
                fromListing = durFromListing
            }
        }
        snap = s
        durFromListing = fromListing
        seeded = false
        if (s.ours && s.relS != null) {
            lastOursRel = s.relS
            lastOursDur = s.durS?.takeIf { it > 0 } ?: lastOursDur
        }
    }

    /**
     * `_sonos_stream_moved` (§4.8): our stream now plays on a promoted coordinator. Adopt FIRST (zero
     * transport commands), then the identity (renderer.json): a failed adopt changes nothing and the
     * hint, still published, retries next tick. A hint for the uid we have, a snapshot of another
     * session, or a stale one is a no-op.
     */
    private fun follow(s: Fields, now: Long): Boolean {
        val mv = s.streamMoved ?: return false
        if (mv.uid.isEmpty() || mv.uid == uid || s.uid != uid || !s.fresh(now) || adopting) return false
        adopting = true
        val kind = s.kind ?: Kind.URL
        work.submit({ transport.adopt(mv.uid, kind, mv.uri) }) { r ->
            adopting = false
            r.onSuccess {
                uid = mv.uid
                name = mv.name
                volOpt = null // new speaker, new volume world
                playing = playing?.let { Playing(it.index, it.itemKey, mv.uri) }
                host.followed(mv.uid, mv.name)
                host.log("sonos: stream moved to ${mv.name} (${mv.uid}) - following")
            }
            r.onFailure { host.log("sonos: adopt failed (${it.javaClass.simpleName}) - retried next tick") }
            host.changed()
        }
        return true
    }

    /**
     * The episode ran off its end (STOPPED right after the tail): the next item at 0 (the service
     * marks the finished one done on the automatic change), or at the queue's end a Stop and the
     * service's `queueEnded`. A stop far from the end is a human and stays stopped.
     */
    private fun advance() {
        val cur = playing ?: return
        val next = cur.index + 1
        if (next < items.size) {
            host.log("sonos: episode ended - next")
            autoFlag = true
            playIndex(next, 0.0, deliberate = false, PlayWhy.ADVANCE)
        } else {
            host.log("sonos: queue finished - stop")
            endSession()
            ended = true
            endedFlag = true
            val target = uid
            work.submit({ transport.stop(target) }) { r ->
                r.exceptionOrNull()?.let { host.log("sonos: stop failed (${it.javaClass.simpleName})") }
                host.changed()
            }
        }
    }

    // -- the bookmark (§4.4) --

    /**
     * `_sonos_bookmark_now`: the one write of a Sonos position (`Renderer.saveNow`). The item actually
     * on the speaker, a measured position of OUR stream (the seed of our own play is not measured),
     * not STOPPED (after a Stop the transport reads 0:00); then SonosBookmarks' hold and regression
     * guard; the duration only when the speaker measured it.
     */
    fun bookmarkNow(force: Boolean) {
        if (closed) return
        val s = snap ?: return
        val pl = playing ?: return
        if (seeded || !s.ours || normUri(s.uri) != normUri(pl.uri)) return
        val now = elapsedMs()
        val age = s.measuredAt?.takeIf { s.fresh(now) }?.let { now - it } ?: 0L
        val input = BookmarkInput(pl.itemKey, s.ours, s.transport, s.relS, s.durS, durFromListing, age)
        val verdict = SonosBookmarks.decide(input, bmHold, store.savedPosMs(pl.itemKey) / 1000.0, seekAt, now)
        bmHold = verdict.hold
        val w = verdict.write
        if (w == null) {
            if (verdict.why != "nothing measured") host.log("sonos: bookmark not written: ${verdict.why}")
            return
        }
        val shown = s.durS?.takeIf { it > 0 }?.let { (it * 1000).toLong() }
        store.save(pl.index, w.itemKey, w.posMs, w.durationMs, shown, force)
    }

    // -- switching (§4.6) --

    /**
     * The way off this speaker (`_renderer_to_box`'s first half; room -> room's old room): one live
     * classify within [LIVE_BUDGET_MS] (any failure keeps the snapshot we have), the bookmark at that
     * exact second, then Stop - ours-gated - to this uid. [then] gets whether the speaker was PLAYING
     * (the phone carries on only then). Nothing is sent or written by this controller afterwards.
     */
    fun handOff(then: (wasPlaying: Boolean) -> Unit) {
        if (closed) return then(false)
        if (!session) {
            closed = true
            return then(false)
        }
        work.submit({ transport.liveRefresh(LIVE_BUDGET_MS) }) { r ->
            val wasPlaying = try {
                r.getOrNull()?.let { live(it) }
                bookmarkNow(force = true)
                val s = snap
                s != null && s.ours && s.reachable && s.transport == TransportState.PLAYING
            } catch (e: Exception) {
                host.log("sonos: hand-off bookkeeping failed (${e.javaClass.simpleName})")
                false
            }
            val ours = snap?.ours == true
            endSession()
            closed = true
            val target = uid
            if (ours) {
                work.submit({ transport.stop(target) }) { stopped ->
                    stopped.exceptionOrNull()?.let { host.log("sonos: stop failed (${it.javaClass.simpleName}) - the speaker may play on") }
                    then(wasPlaying)
                }
            } else {
                then(wasPlaying)
            }
        }
    }

    /** `_sonos_refresh_live`: a live snapshot replaces ours only when armed and measured. */
    private fun live(f: Fields) {
        if (!f.armed || f.relS == null || f.uid != uid || !f.reachable) return
        pending = null
        accept(f)
    }

    /** No more SOAP and no more writes (the renderer is going). */
    fun close() {
        closed = true
        fade?.set(true)
        endSession()
    }

    // -- volume (§5.3; the cap is step 3b) --

    /** `SetVolume`, clamped 0..100 (the parent's cap is 3b); the base of the next press. */
    fun setVolume(volume: Int) {
        if (closed) return
        val v = volume.coerceIn(0, 100)
        volOpt = v
        val target = uid
        work.submit({ transport.volume(v, target) }) { r ->
            r.exceptionOrNull()?.let { host.log("sonos: volume failed (${it.javaClass.simpleName})") }
            host.changed()
        }
        host.changed()
    }

    /** A volume key: our own last set (never the stale poll), else the last read, else 50 (vibb), +- [delta]. */
    fun adjustVolume(delta: Int) {
        setVolume((volOpt ?: snap?.volume ?: 50) + delta)
    }

    // -- the sleep timer's end (§5.2) --

    /**
     * Three `SetVolume` steps (2/3, 1/3, 0 of the volume we last read) 1 s apart, Pause, then the
     * volume put back so the next play isn't silent - one actor job, cancelled by [cancelFade] (the
     * volume is restored at once). [done] runs on the main thread when it is over. Nothing of ours
     * playing: just [done].
     */
    fun fadeOutAndPause(done: () -> Unit) {
        val now = elapsedMs()
        val s = snap
        if (closed || !session || s == null || !s.ours || effectiveTransport(now) != TransportState.PLAYING) return done()
        val base = volOpt ?: s.volume
        if (base == null) {
            pause()
            return done()
        }
        fade?.set(true)
        val cancel = AtomicBoolean(false)
        fade = cancel
        bookmarkNow(force = false)
        host.ramp(base)
        val target = uid
        work.submit({
            var paused = false
            try {
                for (f in listOf(2.0 / 3, 1.0 / 3, 0.0)) {
                    if (cancel.get()) break
                    transport.volume((base * f).roundToInt(), target)
                    sleep(RAMP_STEP_MS)
                }
                if (!cancel.get()) paused = transport.pause(target)
            } finally {
                transport.volume(base, target)
            }
            paused
        }) { r ->
            if (fade === cancel) fade = null
            host.ramp(null)
            r.exceptionOrNull()?.let { host.log("sonos: sleep fade failed (${it.javaClass.simpleName})") }
            if (r.getOrNull() == true) {
                val at = elapsedMs()
                frozen = OptPos(spec(at).positionAt(at) / 1000.0, at, snap?.uri)
                optTr = Opt(TransportState.PAUSED_PLAYBACK, at)
            }
            done()
            host.changed()
        }
    }

    /** A pause or a new timer during the ramp: stop stepping and put the volume back. */
    fun cancelFade() {
        fade?.set(true)
    }

    companion object {
        /** `pending`/`optTr` hold against stale polls until confirmed or this passes (QA A, 4408-4420). */
        const val SETTLE_MS = 8_000L

        /** `SONOS_SEEK_TOL_S`, `SONOS_SEEK_HOLD_S`, `SONOS_SEEK_SPACING_S`, `SEEK_TAIL_S`. */
        const val SEEK_TOL_S = 6.0
        const val SEEK_HOLD_MS = 12_000L
        const val SEEK_SPACING_MS = 700L
        const val SEEK_TAIL_S = 5.0

        /** The tail that makes a following STOPPED an episode's natural end. */
        const val ENDS_NEAR_S = 20.0

        /** The state table's transient: the first failed polls read BUFFERING (§3.2). */
        const val TRANSIENT_FAILURES = 3

        /** The live classify of a switch home (`/state?live=1`, 1021-1039). */
        const val LIVE_BUDGET_MS = 1_500L

        /** The sleep ramp's step (§5.2). */
        const val RAMP_STEP_MS = 1_000L

        private const val FAR_PAST = -1_000_000_000_000L
    }
}
