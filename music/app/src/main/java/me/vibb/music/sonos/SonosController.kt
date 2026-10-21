package me.vibb.music.sonos

import kotlinx.coroutines.CancellationException
import me.vibb.music.logic.Repeat
import me.vibb.music.logic.SonosAnchor
import me.vibb.music.logic.SonosMeasure
import me.vibb.music.logic.advanceIndex
import me.vibb.music.logic.durationByConsumableId
import me.vibb.music.logic.sonosAnchor
import me.vibb.music.logic.sonosMintCode
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.PauseCause
import me.vibb.music.playback.RendererState
import me.vibb.music.storytel.Mint
import me.vibb.music.storytel.StorytelEndpoints
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * The controller side of a Sonos session, ported from palchrb/vibb `pi/daemon.py` (the `_sonos_*`
 * fields and methods: `_sonos_position`, `_sonos_bookmark_now`, `_sonos_play_entry`, `sonos_step` and
 * its worker, `seek` and `_sonos_seek_worker`, `_sonos_command`, `pause`, `unpause`, `play`'s same-tile
 * shortcut, `_sonos_stream_moved`, `_sonos_poller`'s tick, `_renderer_to_box`'s first half, and
 * `status`'s renderer fields; `_sonos_body`'s Storytel branch and `_sonos_known_duration`; MIT
 * License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Design 26a §3.2, §4.2-§4.6, §4.8, §5.2; design 26b §5.1-§5.4 (Storytel on a room).
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
 *
 * Design 53 (round 53-a, ours): the speaker's event socket ([onSocket]) confirms our own verbs, re-bases
 * the bar ([sonosAnchor], display only) and wakes confirm polls; every decision - ours, the advance,
 * the follow, grouped away, the bookmark - stays a poll's.
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

/**
 * A Storytel book's signed CDN URL, minted at the last moment (design 26b §4.2's one signature:
 * `StorytelAccount.mint`, interruptible on IO). Called only inside a play's actor job; never throws but
 * for its caller's cancellation, which a room's actor never does (qa-26b-merge-code Low 1).
 */
fun interface StorytelMinter {
    suspend fun mint(cid: String, timeoutMs: Long, deadlineMs: Long): Mint
}

/**
 * A Storytel play that got no URL (design 26b §5.1): [code] is [sonosMintCode]'s (`no_audio`,
 * `http_<n>`, `network`, `storytel_off`, `login_refused`, `login_failed`) or `address` (a minted URL
 * a speaker mustn't fetch). 1b's failed play: nothing was sent, no volume touched. No URL in the message.
 */
class StorytelUnavailable(val code: String) : SonosException("storytel url unavailable: $code")

/** What the controller tells its renderer. */
interface SonosHost {
    /** The state moved: refresh the player and report (on the main thread; maybe inside a player handler). */
    fun changed()

    /**
     * A play landed: renderer.json learns the built uri (what a later adopt compares against) - for a
     * Storytel book its marker, never the signed URL ([me.vibb.music.logic.storedUri], design 26b §5.3).
     */
    fun played(itemKey: String, uri: String) {}

    /**
     * Migration follow: renderer.json gets the new room (after the adopt, never before), and the uri
     * the adopt went out with for the item [itemKey] (through `storedUri`, as [played]).
     */
    fun followed(uid: String, name: String?, itemKey: String?, uri: String) {}

    /**
     * A Storytel play got no URL (design 26b §5.1): `no_audio` is the book's item error, under the entry
     * the play was sent for ([entryId], captured at submit - never the service's current one; qa L3).
     */
    fun storytelUnavailable(entryId: Long, itemKey: String, code: String) {}

    /** The sleep ramp runs from [volume] (null: it ended, the volume is back). */
    fun ramp(volume: Int?) {}

    /**
     * Whether the phone must stay awake for this session (design 26a §6.3): the wake lock and the
     * Wi-Fi lock are held while true. True on every refresh while it holds, false once when it ends
     * ([SonosController.armedPlaying] defines it).
     */
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
    /**
     * Whether anything may start playing now (`playAllowed`: not while the launcher has suspended the
     * app). Every play the controller sends asks first - a skip, an advance, a start (qa-26a-step3a-code H1).
     */
    private val mayPlay: () -> Boolean = { true },
    /**
     * The parent's volume cap in force, 1..99 % (the launcher's `volume_cap_pct`, read live), or
     * null: our own `SetVolume`s never go above it (design 26a §5.3).
     */
    private val cap: () -> Int? = { null },
    /**
     * The uids that sound when [uid] plays (its row's members, the coordinator first; [uid] alone for a
     * room on its own): a start caps them all (design 26a §5.3, qa-26a-corrections M2).
     */
    private val members: (String) -> List<String> = { listOf(it) },
    /** A Storytel book's URL, minted inside its play's actor job (design 26b §5.1). */
    private val minter: StorytelMinter = StorytelMinter { _, _, _ -> Mint.NotConfigured },
    /** Design 26b §4.6 (d): the debug switch's fake on 10.0.2.2 may hand the speaker a private literal. */
    private val privateMintAllowed: () -> Boolean = { StorytelEndpoints.privateMintAllowed() },
    /** The speaker's event socket (design 53 §2.2): its health and the liveness rule's hooks. */
    private val events: SonosEventLink = SonosEventLink.NONE,
    /** Wake the poller now: a confirm poll (design 53 §2.3). */
    private val wakePoll: () -> Unit = {},
) {
    /**
     * The room: the uid we command (a group by its coordinator), and its label. Migration follow moves
     * them. Volatile: a play job behind a mint reads it off the main thread through [mayPlay] (the scope's belt).
     */
    @Volatile
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

    /**
     * After a hand-off or a release: nothing is sent or written any more. Volatile: a play job checks
     * it off the main thread after its mint (design 26b §5.1, M6).
     */
    @Volatile
    var closed: Boolean = false
        private set

    // Not yet started (a queue loaded paused, or an item picked while the speaker isn't playing): where it starts.
    private var startPosMs = 0L
    private var deliberateStart = false

    /** The next play starts [index] at [startPosMs] (not the speaker's own item from its bookmark). */
    private var startPending = false

    /**
     * Bumped by everything that supersedes a play in flight (a load, a clear, a step, a new play): a
     * result of an older generation only says what the speaker now holds (qa-26a-step3a-code H2).
     * Written on the main thread only; an [AtomicLong] so a play job behind a mint reads it on IO (the
     * job's token, design 26b §5.1, M6).
     */
    private val generation = AtomicLong()
    private var gen: Long
        get() = generation.get()
        set(value) = generation.set(value)

    /** A play of ours went out to this uid and succeeded, and no Stop, hand-off or disarm since. */
    var ownsSpeaker: Boolean = false
        private set

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

    /** The item actually on the speaker (of entry [entryId]): what the bookmark is about. */
    private class Playing(val entryId: Long?, val index: Int, val itemKey: String, val uri: String)

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

    /**
     * "Til slutten" (design 50 §1.2): set, the episode's natural end holds at the next item at 0 - as [advance]'s
     * branch for a play that isn't allowed - and this runs once (then it is cleared). Main thread.
     */
    var holdAtEnd: (() -> Unit)? = null

    /** Our own last volume set: the base of the next press (vibb's `_sonos_vol_opt`). */
    var volOpt: Int? = null
        private set

    /** Bumped by every volume set of our own: a play's result never overwrites a newer one (L1). */
    private var volSets = 0

    /** The last playWhenReady the player showed: held through a transient (§3.2). */
    private var heldPwr = false
    private var autoFlag = false

    /** The loaded entry's repeat (design 49 §1.9, the service's through `SonosRenderer.setModes`): [advance] wraps or replays. */
    var repeat: Repeat = Repeat.OFF

    /** The queue played to its end on the speaker (the player shows ENDED until a new load). */
    var ended: Boolean = false
        private set
    private var endedFlag = false
    private var lost: Exception? = null
    private var refusedFlag = false
    private var wasArmedPlaying = false

    /** Our last verb or play: its fast-poll window keeps the locks (§6.3). */
    private var verbAt = FAR_PAST

    // -- design 53: the event socket and the display anchor --

    /** What the bar shows ([sonosAnchor]): re-based by every measurement of ours, display only. */
    private var anchor: SonosAnchor? = null

    /** The socket's last state of our group and when it came (the liveness and disagreement rules). */
    private var wsState: WsPlayback? = null
    private var wsAt = FAR_PAST

    /** A socket state that passed the anchor's gate: the effective transport until a newer poll. */
    private var wsTr: Opt? = null

    /** An IDLE, or a BUFFERING we didn't cause, came since the last ours poll: no socket measurement counts. */
    private var wsBlocked = false

    /** The generation an IDLE asked a confirm poll for. */
    private var idleGen: Long? = null

    /** The takeover race (§2.3): that generation's one STOPPED near the end decides nothing. */
    private var skipAdvance: Long? = null

    /** An event that came while our answered play settled: the poll that sees it land applies it. */
    private var parked: SocketEvent.Playback? = null
    private var lastWakeAt = FAR_PAST
    private var wakeQueued = false
    private var disagreements = 0

    /** The last non-forced bookmark from a poll: at most every [BOOKMARK_MIN_MS], unless the transport changed. */
    private var bmAt = FAR_PAST
    private var bmTr: TransportState? = null
    private var bmStream: String? = null

    // -- what the renderer reads --

    /** True once per natural advance: the next index change is an automatic one. */
    fun takeAuto(): Boolean = autoFlag.also { autoFlag = false }

    /** True once when the queue played to its end. */
    fun takeEnded(): Boolean = endedFlag.also { endedFlag = false }

    /** A start that failed (once): the renderer reports it and the service goes back to the phone. */
    fun takeLost(): Exception? = lost.also { lost = null }

    /** True once when a start was refused while our session on this uid plays on: "Kan ikke spilles på Sonos". */
    fun takeRefused(): Boolean = refusedFlag.also { refusedFlag = false }

    private fun opt(now: Long): Opt? = optTr?.takeIf { now - it.at <= SETTLE_MS }

    /**
     * The transport the screens should believe: our own verb's while it is held, else a socket state
     * newer than the last poll (a pause from the Sonos app shows at once, design 53 §2.3), else the
     * speaker's as polled.
     */
    fun effectiveTransport(now: Long): TransportState? = opt(now)?.state ?: wsNewer()?.state ?: snap?.transport

    private fun wsNewer(): Opt? = wsTr?.takeIf { it.at > (snap?.measuredAt ?: FAR_PAST) }

    /** The socket's last word is BUFFERING, newer than the last poll: the phase only (§2.3), never the transport. */
    private fun wsBuffering(): Boolean = wsState == WsPlayback.BUFFERING && wsAt > (snap?.measuredAt ?: FAR_PAST)

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
     * plays - clamped to the duration. A fresh snapshot of a stream that isn't ours (grouped away,
     * taken over, lost) shows no position and no duration ([hidesPosition], vibb `status`'s
     * ours-gated position, `sonos_grouped.py` check 2: a member's RelTime is the coordinator's); a
     * stale one keeps the last ours values. Before a start: where it will start.
     */
    fun spec(now: Long): SonosSpec {
        val idx = index
        if (items.isEmpty()) return SonosSpec(if (ended) PlayPhase.ENDED else PlayPhase.IDLE, false, 0, 0, now, false, null)
        val listed = items.getOrNull(idx)?.durationMs
        if (!session) {
            val shown = if (startPending) startPosMs else ((lastOursRel ?: 0.0) * 1000).toLong()
            return SonosSpec(if (ended) PlayPhase.ENDED else PlayPhase.IDLE, false, idx, shown, now, false, currentDurS()?.let { (it * 1000).toLong() } ?: listed)
        }
        val s = snap
        val tr = effectiveTransport(now)
        val hidden = hidesPosition(now)
        val dur = if (hidden) null else if (startPending) listed else currentDurS()?.let { (it * 1000).toLong() } ?: listed
        val (pos, anchor, moving) = if (hidden) Triple(0L, now, false) else position(now)
        fun spec(phase: PlayPhase, pwr: Boolean) = SonosSpec(phase, pwr, idx, pos, anchor, moving && pwr, dur)
        val p = pending
        return when {
            // a play in flight: the speaker may still report the old track (its settle window)
            p != null -> spec(PlayPhase.BUFFERING, opt(now)?.state?.let { it == TransportState.PLAYING } ?: heldPwr)
            s == null -> spec(PlayPhase.BUFFERING, heldPwr)
            // the first failed polls are a transient: BUFFERING keeps the FGS (one failure must never read IDLE)
            !s.reachable -> if (s.failedPolls <= TRANSIENT_FAILURES) spec(PlayPhase.BUFFERING, heldPwr) else spec(PlayPhase.READY, false)
            // the socket's BUFFERING is TRANSITIONING (design 53 §2.3): the phase, never the transport or playWhenReady
            s.transitioning || (wsBuffering() && s.fresh(now) && s.ours) -> spec(PlayPhase.BUFFERING, heldPwr)
            !s.fresh(now) -> spec(PlayPhase.READY, false)
            !s.ours -> spec(PlayPhase.READY, false)
            tr == TransportState.PLAYING -> spec(PlayPhase.READY, true)
            else -> spec(PlayPhase.READY, false)
        }
    }

    /**
     * The speaker answers, just now, with a stream that isn't ours (and no play of ours is settling):
     * its position belongs to someone else's stream - never painted under our title (vibb `status`).
     */
    fun hidesPosition(now: Long): Boolean {
        val s = snap ?: return false
        return session && !startPending && pending == null && !seeded && s.reachable && s.fresh(now) && !s.ours
    }

    /** Called by the renderer after each refresh: the playWhenReady it showed (held through transients). */
    fun shown(spec: SonosSpec) {
        heldPwr = spec.playWhenReady
        lockFor(armedPlaying(spec, elapsedMs()))
    }

    /**
     * The wake and Wi-Fi locks' rule (design 26a §6.3, QA H1): an FGS keeps the network in Doze but
     * not the CPU, and the poller (every bookmark) needs both. True while a session of ours is
     * driven and - the player shows it playing (PLAYING, or held through the first failed polls,
     * §3.2), or a play, a step or a seek is in flight, or we sent a verb or a play less than
     * [SonosSession.VERB_FAST_MS] ago (the window the session polls fast in, so the pause or resume
     * is confirmed; the tail's fast window is PLAYING's own). False when it shows paused (once the
     * verb window is over), stopped, ended, unreachable past the transient, disarmed, handed off or
     * closed - the switch home and the release say so at once.
     */
    fun armedPlaying(spec: SonosSpec, now: Long): Boolean =
        !closed && session && (spec.playWhenReady || pending != null || stepping || seeking || now - verbAt < SonosSession.VERB_FAST_MS)

    /** True is re-asserted on every refresh (the holder renews its timeout only on these, qa-26a-step3b-code L3); false once, on the change. */
    private fun lockFor(want: Boolean) {
        if (!want && !wasArmedPlaying) return
        wasArmedPlaying = want
        host.armedPlaying(want)
    }

    private fun currentDurS(): Double? {
        val s = snap ?: return lastOursDur
        return if (s.ours) s.durS?.takeIf { it > 0 } ?: lastOursDur else lastOursDur
    }

    /** (position ms at anchor, anchor, moving). */
    private fun position(now: Long): Triple<Long, Long, Boolean> {
        if (startPending) return Triple(startPosMs, now, false) // an item picked while the speaker holds another
        val s = snap
        optPos?.let { o -> if (now - o.at <= SEEK_HOLD_MS && o.uri == s?.uri) return Triple((o.s * 1000).toLong(), now, false) }
        frozen?.let { f -> if (opt(now)?.state == TransportState.PAUSED_PLAYBACK) return Triple((f.s * 1000).toLong(), now, false) }
        // design 53 §2.4: the anchor every measurement of ours re-bases (a poll's RelTime + 0.5 s, an event's exact
        // position); a play or a step in flight keeps the old one until its seed (the speaker may still play the old item)
        val a = anchor
        val last = a?.posMs ?: ((lastOursRel ?: 0.0) * 1000).toLong()
        if (s == null || !s.ours || a == null) return Triple(last, now, false)
        if (!s.fresh(now)) return Triple(a.posMs, now, false)
        val o = opt(now)
        return when {
            // one age term from the measurement's own instant (vibb summed two clocks across its process split)
            a.moving && o?.state != TransportState.PAUSED_PLAYBACK -> Triple(a.posMs, a.at, true)
            // our resume while the speaker still reads PAUSED: it plays from our verb on
            !a.moving && o?.state == TransportState.PLAYING -> Triple(a.posMs, maxOf(o.at, a.at), true)
            else -> Triple(a.posMs, now, false)
        }
    }

    /** What the anchor is about: the generation, the item on the speaker and its uri, the last deliberate seek or start. */
    private fun anchorKey(): String = "$gen|${playing?.itemKey}|${playing?.uri?.let(::normUri)}|$seekAt"

    /**
     * A measurement of our stream re-bases the anchor ([sonosAnchor]); [poll]: `RelTime` truncates, so
     * its display value is half a second on (display only, never a [Fields] value the bookmark reads).
     */
    private fun measure(posMs: Long, at: Long, moving: Boolean, poll: Boolean, seed: Boolean = false) {
        // a seed is no measurement: its own key, so the first real one after a play is taken at once
        val key = if (seed) anchorKey() + "|seed" else anchorKey()
        val m = SonosMeasure(if (poll) posMs + POLL_HALF_MS else posMs, at, moving, key, poll)
        anchor = sonosAnchor(anchor, m, events.healthy)
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
        startPending = true
        gen++
        session = false
        // what the speaker holds stays known (a failed start must not lose it); its index is this queue's
        playing = playing?.let { p -> Playing(p.entryId, if (p.entryId == entry.id) items.indexOfFirst { it.key == p.itemKey } else -1, p.itemKey, p.uri) }
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
        anchor = null
        forgetEvents()
        host.changed()
    }

    /** Shuffle on Sonos keeps the current item playing; [after] follow it (the service's order). */
    fun reorder(keep: Int, after: List<PlayItem>) {
        val current = items.getOrNull(keep) ?: return
        items = listOf(current) + after
        index = 0
        playing = playing?.let { Playing(it.entryId, 0, it.itemKey, it.uri) }
        host.changed()
    }

    /** The queue is over (the service's `queueEnded`, or a clear): nothing more to show. */
    fun clear() {
        items = emptyList()
        index = 0
        gen++
        session = false
        startPending = false
        playing = null
        pending = null
        optTr = null
        stepWant = null
        anchor = null
        forgetEvents()
        host.changed()
    }

    /**
     * A session found alive at start (design 26a §4.7, `_sonos_poller`'s adopt-on-start): the
     * transport has adopted it (bookkeeping, zero transport commands) and [live] is its one classify.
     * The queue is the stored entry's, speaker-playable, at [idx] (the item whose uri the speaker
     * plays); the duration comes from the listing when the speaker reports none (durFromListing:
     * it never decides "heard to the end"). Nothing is sent: a playing speaker plays on, a paused
     * one stays paused.
     */
    fun adopt(entry: Entry, items: List<PlayItem>, idx: Int, uri: String, live: Fields) {
        if (closed || idx !in items.indices) return
        this.entry = entry
        this.items = items
        index = idx
        gen++
        startPending = false
        session = true
        ownsSpeaker = true
        ended = false
        playing = Playing(entry.id, idx, items[idx].key, uri)
        pending = null
        optTr = null
        optPos = null
        frozen = null
        bmHold = null
        stepWant = null
        seekWant = null
        endsNear = 0
        anchor = null
        forgetEvents()
        accept(live)
        host.log("sonos: adopted a live session on start [${idx + 1}/${items.size}] ${items[idx].title}")
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
        if (!session || startPending) return start()
        val now = elapsedMs()
        if (pending != null) {
            // a play in flight is the answer - unless a pause went after it: the last intent wins (L3)
            if (opt(now)?.state == TransportState.PAUSED_PLAYBACK) resume(fallback = false)
            host.changed()
            return
        }
        val s = snap
        val live = s != null && s.fresh(now) && s.ours && (effectiveTransport(now)?.live == true)
        when {
            !live -> restart()
            effectiveTransport(now) == TransportState.PAUSED_PLAYBACK -> resume(fallback = false)
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
        if (!session || startPending) return start()
        val now = elapsedMs()
        val s = snap
        val settling = pending != null || opt(now) != null
        val steady = s != null && s.fresh(now) && s.ours && !seeded && !settling && playing?.index == index
        when {
            steady && s!!.transport == TransportState.PLAYING -> Unit
            steady && s!!.transport == TransportState.PAUSED_PLAYBACK -> resume(fallback = true)
            else -> restart()
        }
        host.changed()
    }

    /**
     * The first play of a loaded queue, or of an item picked while the speaker wasn't playing, at its
     * planned place; after a session (a stop, a disarm) a full start from the bookmark.
     */
    private fun start() {
        if (startPending) playIndex(index, startPosMs / 1000.0, deliberateStart, PlayWhy.START) else restart()
        host.changed()
    }

    /** A full start of the current item from its saved place (`sonos_start_target`'s bookmark). */
    private fun restart() {
        val item = items.getOrNull(index) ?: return
        playIndex(index, store.savedPosMs(item.key) / 1000.0, deliberate = false, PlayWhy.START)
    }

    /** One resume; [fallback]: a refusal falls through to the full start - the transfer is the heal (same tile, L2). */
    private fun resume(fallback: Boolean) {
        if (!mayPlay()) return host.log("sonos: resume refused - playing isn't allowed now")
        val now = elapsedMs()
        // the liveness rule (design 53 §2.2, N3): a resume on a confirmed PAUSED owes an event
        val owes = socketAgrees(TransportState.PAUSED_PLAYBACK, now)
        verbAt = now
        optTr = Opt(TransportState.PLAYING, now)
        frozen = null
        val target = uid
        val g = gen
        work.submit({ transport.resume(target) }) { r ->
            r.exceptionOrNull()?.let {
                host.log("sonos: resume failed (${it.javaClass.simpleName})")
                optTr = null
                if (fallback && g == gen && !closed) restart()
            }
            if (owes && r.getOrNull() == true) events.owed(now, elapsedMs())
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
        // the liveness rule (design 53 §2.2, N3): a pause on a confirmed PLAYING owes an event; one while paused doesn't
        val owes = pending == null && socketAgrees(TransportState.PLAYING, now)
        if (effectiveTransport(now) == TransportState.PLAYING) {
            frozen = OptPos(spec(now).positionAt(now) / 1000.0, now, snap?.uri)
        }
        optTr = Opt(TransportState.PAUSED_PLAYBACK, now)
        verbAt = now
        val target = uid
        work.submit({ transport.pause(target) }) { r ->
            r.exceptionOrNull()?.let {
                host.log("sonos: pause failed (${it.javaClass.simpleName})")
                optTr = null
                frozen = null
            }
            if (owes && r.getOrNull() == true) events.owed(now, elapsedMs())
            host.changed()
        }
        host.changed()
    }

    /**
     * The socket and a fresh ours poll agree on the state ([tr] when given) - the liveness rule owes an
     * event only for a verb against a confirmed state (design 53 §2.2, revision check N3).
     */
    private fun socketAgrees(tr: TransportState?, now: Long): Boolean {
        val ws = wsState ?: return false
        val s = snap ?: return false
        if (!events.healthy || ws == WsPlayback.BUFFERING || !s.fresh(now) || !s.ours || s.transitioning) return false
        return ws.transport == s.transport && (tr == null || s.transport == tr)
    }

    /** The player's stop: the bookmark, then Stop when the stream is ours; the session is over. */
    fun stop() {
        if (closed || !session) return
        bookmarkNow(force = true)
        val ours = snap?.ours == true
        endSession()
        ownsSpeaker = false
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
        forgetEvents()
    }

    /** What the socket said before this play, load or end (design 53 §2.3): never carried across one. */
    private fun forgetEvents() {
        wsTr = null
        wsBlocked = false
        idleGen = null
        skipAdvance = null
        parked = null
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
        if (!session || startPending) {
            startPosMs = ms.coerceAtLeast(0)
            deliberateStart = items.getOrNull(index)?.let { startPosMs < store.savedPosMs(it.key) } ?: false
            startPending = true
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
        verbAt = now
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
     * Another item (`sonos_step`, §4.5): while the speaker plays, the index flips at once
     * (optimistic) and one worker plays the LATEST wanted index from 0 (`playIndex(want, 0,
     * deliberate)`), a mash costing one play per settled want. While it doesn't play (paused,
     * stopped, stale, before a start) or nothing may play, a skip only moves where the next play
     * starts ([ms]): a skip on a paused phone stays paused too, and a headset's "next" after a time
     * rule must not start the speaker (qa-26a-step3a-code H1).
     */
    fun stepTo(idx: Int, ms: Long) {
        if (closed || idx !in items.indices) return
        val now = elapsedMs()
        if (!session || effectiveTransport(now) != TransportState.PLAYING || !mayPlay()) {
            gen++
            index = idx
            startPosMs = ms.coerceAtLeast(0)
            deliberateStart = startPosMs < store.savedPosMs(items[idx].key)
            startPending = true
            stepWant = null
            pending = null
            optPos = null
            frozen = null
            host.changed()
            return
        }
        gen++
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
        if (!mayPlay()) {
            host.log("sonos: a play refused - playing isn't allowed now")
            done()
            return
        }
        val e = entry
        val item = items.getOrNull(idx)
        // art = null: the phone holds only hashes (a listing's `art`, the library's `cover`) the server serves
        // to the phone's bearer - no http URL a speaker could fetch, so vibb's http-only rule yields nothing
        // (design 53 §0.3, §2.1)
        val body = if (e != null && item != null) PlayBodies.build(e, item, uid, startS, art = null) else null
        if (body == null || item == null) {
            host.log("sonos: nothing a speaker can play at ${idx + 1}")
            failed(why, SonosException("nothing-remote-playable"))
            done()
            return
        }
        val now = elapsedMs()
        val g = ++gen
        val entryId = e?.id
        startPending = false
        session = true
        ended = false
        verbAt = now
        if (deliberate) seekAt = now
        index = idx
        // the liveness rule (design 53 §2.2, N3): a new item against a state the socket and the poll agree on owes an event
        val owes = socketAgrees(null, now)
        pending = Pending(item.key, now, null)
        optTr = Opt(TransportState.PLAYING, now)
        optPos = null
        frozen = null
        bmHold = null
        endsNear = 0
        forgetEvents()
        val volume = restoreVolume
        // a session's start brings a louder speaker down to the parent's cap before Play (§5.3); a
        // step or an advance inside the session never does (a louder volume set elsewhere stays)
        val startCap = if (why == PlayWhy.START) cap()?.takeIf { it in 1..99 } else null
        // the ramp's leftover is our own set: never above the cap either
        val restore = volume?.let { volumeTarget(it, startCap ?: cap()) }
        val target = uid
        val room = if (startCap != null) members(target) else emptyList()
        val volSet = volSets
        val cid = body.mint
        // The job's token (design 26b §5.1, M6; qa-26b-step4b-code L1, L2): a Storytel play is the kid's latest
        // want or nothing (the generation too); every play - an NRK/RSS one queued behind a mint included - only
        // while not released and playing is allowed. Asked before the job's work and again right before
        // SetAVTransportURI (inside transport.play); a dropped job is null, with nothing sent.
        val gate: () -> Boolean = if (cid != null) ({ stillWanted(g) }) else ({ !closed && mayPlay() })
        work.submit<PlayResult?>({
            if (!gate()) return@submit null
            var b = body
            if (cid != null) {
                // a job superseded while it queued never mints; one superseded during the mint sends nothing
                b = body.copy(uri = mintFor(cid), mint = null)
                if (!gate()) return@submit null
            }
            var result = transport.play(b, startCap, room, gate) ?: return@submit null
            if (restore != null) {
                try {
                    transport.volume(restore, target)
                    // what the speaker holds now (below the cap it may have been brought to, L1)
                    result = result.copy(volume = restore, restored = true)
                } catch (_: SonosException) {
                    // best effort: the next play tries again
                }
            }
            result
        }) { r ->
            try {
                (r.exceptionOrNull() as? StorytelUnavailable)?.let { u -> entryId?.let { host.storytelUnavailable(it, item.key, u.code) } }
                if (closed) {
                    Unit
                } else if (g != gen) {
                    // superseded (a newer load, step or play): only what the speaker holds now
                    r.onSuccess { res ->
                        if (res == null) return@onSuccess // dropped by its token: nothing went out
                        playing = Playing(entryId, if (entry?.id == entryId) items.indexOfFirst { it.key == item.key } else -1, item.key, res.uri)
                        ownsSpeaker = true
                        tookVolume(res, volSet)
                        if (res.restored) restoredBy(volume)
                    }
                    r.onFailure { host.log("sonos: a superseded play failed (${it.javaClass.simpleName})") }
                } else {
                    r.fold({ res ->
                        if (res == null) {
                            dropped(idx, startS, deliberate)
                        } else {
                            played(idx, item, res, startS, volume, volSet)
                            if (owes) events.owed(now, elapsedMs())
                        }
                    }, { failed(why, it) })
                }
            } catch (t: Exception) {
                host.log("sonos: play result failed (${t.javaClass.simpleName})")
            } finally {
                done()
                host.changed()
            }
        }
    }

    /**
     * The job's token, read on IO by a play job behind a mint (design 26b §5.1, M6): no release or
     * hand-off since ([closed]), nothing newer pressed (the [generation] it was queued at), and playing
     * still allowed ([mayPlay]: `playAllowed(suspendedNow())` and the room scope's belt, both safe off main).
     */
    private fun stillWanted(g: Long): Boolean = !closed && generation.get() == g && mayPlay()

    /**
     * The late mint (design 26b §5.1, vibb `_sonos_body`'s Storytel branch): one URL per play, the asset
     * call within [MINT_TIMEOUT_MS] (vibb's `timeout=6`), the whole mint - the login and the wait for its
     * lock included - within [MINT_BUDGET_MS]; a [Mint.Url] (already `StorytelApi.mintedUrl`'s canonical
     * form) only through [PlayBodies.mintedDropReason]. Anything else is [StorytelUnavailable]: 1b's failed
     * play - nothing is sent and no volume touched (the cap and the hush are inside `transport.play`).
     * Never another exception (vibb `storytel_sonos.py` 4); the actor never cancels a job, so a
     * cancellation here is the actor's own end and goes on up.
     */
    private suspend fun mintFor(cid: String): String {
        val mint = try {
            minter.mint(cid, MINT_TIMEOUT_MS, elapsedMs() + MINT_BUDGET_MS)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Mint.Unreachable("minter")
        }
        val url = (mint as? Mint.Url)?.url ?: throw StorytelUnavailable(sonosMintCode(mint) ?: "network")
        PlayBodies.mintedDropReason(url, privateMintAllowed())?.let { why ->
            host.log("sonos: the minted URL for $cid can't go to a speaker ($why)")
            throw StorytelUnavailable("address")
        }
        return url
    }

    /**
     * A play of the current generation its token dropped (design 26b §5.1; qa-26b-step4b-code L2): playing
     * stopped being allowed while it queued or minted (a time rule's suspension). Nothing went out - no
     * failed-play path, no refusal: the item waits for the next play at its place, as a skip that may
     * not play does.
     */
    private fun dropped(idx: Int, startS: Double, deliberate: Boolean) {
        host.log("sonos: a play dropped before SetAVTransportURI - playing isn't allowed now")
        pending = null
        optTr = null
        index = idx
        startPosMs = (startS * 1000).toLong()
        deliberateStart = deliberate
        startPending = true
        if (!ownsSpeaker) session = false
    }

    private fun restoredBy(volume: Int?) {
        if (volume != null && restoreVolume == volume) {
            restoreVolume = null
            host.ramp(null)
        }
    }

    /**
     * A play's own volume sets (the start cap, a ramp's leftover put back): the base of the next key
     * press is what the speaker holds now - unless a press of ours went out after the play was sent
     * ([volSet] behind [volSets]): its set ran later in the actor and stands.
     */
    private fun tookVolume(res: PlayResult, volSet: Int) {
        if (volSet == volSets) res.volume?.let { volOpt = it }
    }

    private fun played(idx: Int, item: PlayItem, res: PlayResult, startS: Double, restored: Int?, volSet: Int) {
        val now = elapsedMs()
        if (res.restored) restoredBy(restored)
        // brought down to the cap before Play, or the ramp's leftover put back: the base of the next key press
        tookVolume(res, volSet)
        index = idx // the current generation: nothing newer was pressed
        ownsSpeaker = true
        playing = Playing(entry?.id, idx, item.key, res.uri)
        pending = Pending(item.key, now, res.uri)
        // a pause pressed while the play was in flight went out after it: that intent stands
        if (optTr?.state != TransportState.PAUSED_PLAYBACK) optTr = Opt(TransportState.PLAYING, now)
        val listed = item.durationMs?.let { it / 1000.0 }
        snap = Fields(
            armed = true, uid = res.uid, kind = transport.session.kind, seq = -1, measuredAt = now,
            reachable = true, transport = TransportState.PLAYING, relS = startS, durS = listed, uri = res.uri, ours = true,
            volume = res.volume ?: snap?.volume,
        )
        durFromListing = listed != null
        seeded = true
        lastOursRel = startS
        lastOursDur = listed
        // the seed's anchor: a new key (a play), so the bar jumps to the start at once (design 53 §2.4)
        measure((startS * 1000).toLong(), now, moving = true, poll = false, seed = true)
        if (startS >= 5 && !res.sought) {
            bmHold = BmHold(item.key, startS, now)
            host.log("sonos: seek refused - playing from the top, bookmark held at ${startS.toInt()}s")
        }
        host.played(item.key, res.uri)
        host.log("sonos: playing [${idx + 1}/${items.size}] ${item.title}")
    }

    /**
     * A play of the current generation failed. The first start on this room is lost (the service goes
     * back to the phone); while a session of ours lives on this uid a failed start is refused like a
     * failed step - "Kan ikke spilles på Sonos", the index back on what the speaker plays, the renderer
     * stays (vibb keeps the renderer and warns; qa-26a-step3a-code H2). A lost step is a shrug, a dead
     * coalescer is a brick.
     */
    private fun failed(why: PlayWhy, e: Throwable) {
        host.log("sonos: play failed (${e.javaClass.simpleName}: ${e.message}) [$why]")
        pending = null
        optTr = null
        val ours = ownsSpeaker && snap?.ours == true
        if (why == PlayWhy.START && !ours) {
            endSession()
            lost = (e as? Exception) ?: SonosException(e.toString())
            return
        }
        if (why == PlayWhy.START) refusedFlag = true
        session = true
        val back = playing?.takeIf { it.entryId == entry?.id && it.index in items.indices }
        if (stepWant == null && back != null) {
            index = back.index
            startPending = false
        } else if (back == null) {
            // the speaker holds another entry's item: the next play starts this one at its place
            startPending = true
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
                // design 53 §2.3: an event parked while our play settled counts once this poll saw the play land
                parked?.let { e ->
                    parked = null
                    if (landed) playback(e, now)
                }
            } else {
                return // settle: the speaker may still report the PREVIOUS track; the seed stands
            }
        }
        if (tick == Tick.Disarmed || !polled.armed) {
            accept(polled)
            endSession()
            ownsSpeaker = false
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
        // an ours poll: the socket's measurements count again (design 53 §2.3)
        wsBlocked = false
        agreement(s, now)
        val rel = s.relS
        val dur = s.durS
        if (rel != null && s.transport?.live == true) {
            // design 53 §2.4: the extra polls (confirm, 1 s) write at most every BOOKMARK_MIN_MS - a changed transport
            // (a pause from the Sonos app) at once: that pause point is the bookmark that matters most; and another
            // stream (a new item, a follow's uri) is another bookmark
            val stream = playing?.let { "${it.itemKey}|${normUri(it.uri)}" }
            if (now - bmAt >= BOOKMARK_MIN_MS || s.transport != bmTr || stream != bmStream) {
                bmAt = now
                bmTr = s.transport
                bmStream = stream
                bookmarkNow(force = false)
            }
            endsNear = if (dur != null && dur > 0 && rel > dur - ENDS_NEAR_S) endsNear + 1 else 0
        }
        if (s.transport == TransportState.STOPPED && endsNear > 0 && playing != null) {
            endsNear = 0
            idleGen = null
            if (skipAdvance == gen) {
                // the takeover race (design 53 §2.3): someone started a stream on our room at the episode's end
                skipAdvance = null
                host.log("sonos: the end was followed by a stream we didn't start - no advance")
            } else {
                advance()
            }
            return
        }
        // our own stream plays or pauses again: the takeover skip is over
        if (s.transport?.live == true && !s.transitioning) skipAdvance = null
        // an IDLE's confirm poll that still reads PLAYING or TRANSITIONING asks one more after a second
        if (idleGen == gen && (s.transport == TransportState.PLAYING || s.transitioning)) {
            idleGen = null
            work.after(IDLE_RECHECK_MS) { if (!closed) wakePoll() }
        }
    }

    /**
     * The disagreement rule (design 53 §2.2): two ours polls in a row that see PLAYING against the
     * socket's PAUSED or IDLE (or the reverse), each [DISAGREE_AFTER_MS] or more after the socket's
     * last event - it missed a change made outside the app: dead.
     */
    private fun agreement(s: Fields, now: Long) {
        val ws = wsState
        val polled = s.transport
        val differs = events.healthy && ws != null && !s.transitioning && now - wsAt >= DISAGREE_AFTER_MS &&
            ((polled == TransportState.PLAYING && (ws == WsPlayback.PAUSED || ws == WsPlayback.IDLE)) ||
                ((polled == TransportState.PAUSED_PLAYBACK || polled == TransportState.STOPPED) && ws == WsPlayback.PLAYING))
        disagreements = if (differs) disagreements + 1 else 0
        if (disagreements >= 2) {
            disagreements = 0
            host.log("sonos: two polls disagree with the socket ($polled vs $ws) - closing it")
            events.disagreed()
        }
    }

    // -- the event socket (design 53 §2.3; main thread) --

    /**
     * One event of the speaker's socket. It never starts, stops or writes anything: it confirms our
     * own verb, re-bases the bar, and wakes a confirm poll - the poll still decides ours, the advance,
     * the follow and grouped away, only sooner.
     */
    fun onSocket(e: SocketEvent) {
        if (closed) return
        try {
            val now = elapsedMs()
            when (e) {
                is SocketEvent.Playback -> {
                    wsState = e.state
                    wsAt = e.at
                    if (session) {
                        wake(immediate = e.state == WsPlayback.IDLE, now)
                        playback(e, now)
                    }
                }
                is SocketEvent.Moved -> if (session) wake(immediate = true, now)
                is SocketEvent.Groups -> if (session) wake(immediate = false, now)
                is SocketEvent.Health -> if (!e.healthy) {
                    // a socket that went away says nothing more: polling alone, as before it
                    wsState = null
                    wsTr = null
                    wsBlocked = false
                    parked = null
                    disagreements = 0
                }
            }
        } catch (x: Exception) {
            host.log("sonos socket event failed: $x")
        }
        host.changed()
    }

    /** A confirm poll, at most once per [EVENT_WAKE_MIN_MS] (an IDLE or a `MOVED` at once), never while the last poll failed. */
    private fun wake(immediate: Boolean, now: Long) {
        if (snap?.reachable == false) return // the UNREACHABLE backoff stands
        val wait = EVENT_WAKE_MIN_MS - (now - lastWakeAt)
        if (immediate || wait <= 0) {
            lastWakeAt = now
            wakePoll()
            return
        }
        if (wakeQueued) return
        wakeQueued = true
        work.after(wait) {
            wakeQueued = false
            if (!closed && session && snap?.reachable != false) {
                lastWakeAt = elapsedMs()
                wakePoll()
            }
        }
    }

    /**
     * A `playbackStatus` of our group (§2.3): BUFFERING is TRANSITIONING - the phase only; our own
     * verb wins until it settles; outside it PLAYING and PAUSED are the effective transport and a
     * measurement, under one gate: the snapshot fresh and ours, no play settling, no IDLE and no
     * BUFFERING we didn't cause since the last ours poll. While our answered play settles the event
     * is parked (the landing poll applies it); before the play answered it is the old track's, dropped.
     */
    private fun playback(e: SocketEvent.Playback, now: Long) {
        val p = pending
        if (p != null) {
            if (p.uri != null) parked = e
            return
        }
        val ours = now - verbAt <= SETTLE_MS
        val s = snap
        val open = s != null && s.fresh(now) && s.ours
        when (e.state) {
            WsPlayback.IDLE -> {
                // the end, a takeover, a stop: the confirm poll decides (pollTick's STOPPED near the end)
                wsBlocked = true
                idleGen = gen
            }
            WsPlayback.BUFFERING -> {
                if (!ours) {
                    wsBlocked = true
                    if (idleGen == gen) skipAdvance = gen
                }
                // at most the anchor stops moving, at the extrapolated value - not under a verb of ours (a resume's
                // start, a seek: the verb's intent and the seek hold show meanwhile)
                val a = anchor
                if (open && a != null && a.moving && opt(now) == null) anchor = a.copy(posMs = a.positionAt(e.at), at = e.at, moving = false)
            }
            WsPlayback.PLAYING, WsPlayback.PAUSED -> {
                if (e.state == WsPlayback.PLAYING && !ours && idleGen == gen) skipAdvance = gen
                val tr = e.state.transport
                val o = opt(now)
                if (o != null && o.state != tr) return // our own verb wins until SETTLE_MS or a poll decides
                if (!open || wsBlocked) return
                if (o != null) {
                    // confirmed: the button settles now
                    optTr = null
                    frozen = null
                }
                wsTr = Opt(tr, e.at)
                val pos = e.positionMs ?: return
                // the seek hold ends on an exact position near its target - never on a BUFFERING's pre-seek one
                optPos?.let { if (abs(pos - (it.s * 1000).toLong()) <= SEEK_EVENT_TOL_MS) optPos = null }
                measure(pos, e.at, moving = e.state == WsPlayback.PLAYING, poll = false)
            }
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
            // design 26b §5.4, vibb's "authoritative first" (`_sonos_known_duration`): a Storytel book's length
            // by the snapshot's consumableId - exact, also for a stream we didn't start (an adopt, the speaker's
            // own Play); flagged like every listing length (never decides "heard to the end")
            val known = entry?.let { durationByConsumableId(it, s.uri, items) }
            val listed = known ?: playing?.takeIf { s.ours }?.let { pl -> items.getOrNull(pl.index)?.takeIf { it.key == pl.itemKey }?.durationMs }
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
            // the bar's anchor (design 53 §2.4), at the instant the speaker measured it (GetPositionInfo's return)
            if (s.reachable) measure((s.relS * 1000).toLong(), s.measuredAt ?: elapsedMs(), moving = s.transport == TransportState.PLAYING, poll = true)
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
                playing = playing?.let { Playing(it.entryId, it.index, it.itemKey, mv.uri) }
                host.followed(mv.uid, mv.name, playing?.itemKey, mv.uri)
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
     * service's `queueEnded`. A stop far from the end is a human and stays stopped. With the entry's
     * repeat (design 49 §1.9, [advanceIndex]) all wraps to the first item and one plays the same item
     * again, both through the same gated path (a new URI set + Play behind [mayPlay]); such a start
     * at 0 is deliberate, so the bookmark's regression guard takes it.
     */
    private fun advance() {
        val cur = playing?.takeIf { it.index >= 0 && it.entryId == entry?.id } ?: return
        val next = advanceIndex(cur.index, items.size, repeat)
        if (next != null) {
            val again = next <= cur.index
            host.log(if (again) "sonos: episode ended - repeat" else "sonos: episode ended - next")
            // the same item again is no item change (as the phone's REASON_REPEAT: no done mark)
            if (next != cur.index) autoFlag = true
            // "Til slutten" (design 50 §1.2) is checked first: it beats a repeat's wrap or replay (49)
            val held = holdAtEnd
            if (mayPlay() && held == null) {
                playIndex(next, 0.0, deliberate = again, PlayWhy.ADVANCE)
            } else {
                // nothing may play now, or "Til slutten" ends here: the next item waits for a play, at 0
                gen++
                index = next
                startPosMs = 0
                deliberateStart = again
                startPending = true
                if (held != null) {
                    host.log("sonos: episode ended - held for the sleep timer")
                    holdAtEnd = null
                    held()
                }
            }
        } else {
            host.log("sonos: queue finished - stop")
            endSession()
            ownsSpeaker = false
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
        val pl = playing?.takeIf { it.index >= 0 && it.entryId == entry?.id } ?: return
        if (seeded || !s.ours || normUri(s.uri) != normUri(pl.uri)) return
        val now = elapsedMs()
        // the one age term, at most BOOKMARK_AGE_MAX_MS (vibb added only the sidecar's <= 5 s; qa L4)
        val age = s.measuredAt?.takeIf { s.fresh(now) }?.let { (now - it).coerceAtMost(BOOKMARK_AGE_MAX_MS) } ?: 0L
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
     * The way off this speaker (`_renderer_to_box`'s first half; room -> room's old room; the service
     * going, vibb `_sonos_on_term`): one live classify within [LIVE_BUDGET_MS] (any failure keeps the
     * snapshot we have), the bookmark at that exact second, then Stop - ours-gated - to this uid.
     * [then] gets whether the speaker was PLAYING (the phone carries on only then). Nothing is sent
     * or written by this controller afterwards.
     *
     * With [park] (the suspension edge, design 26a §6.1) the look also reads a session that stood
     * down (a speaker paused for ten minutes is still ours to stop), the gate is ours AND playing or
     * paused, and the Stop is [SonosTransport.park]: Stop, then the speaker's own queue as its source.
     */
    fun handOff(park: Boolean = false, then: (wasPlaying: Boolean) -> Unit) {
        if (closed) return then(false)
        if (!park && !session && !ownsSpeaker) {
            closed = true
            lockFor(false)
            return then(false)
        }
        work.submit({ transport.liveRefresh(LIVE_BUDGET_MS, armedOnly = !park) }) { r ->
            val wasPlaying = try {
                r.getOrNull()?.let { live(it, anyArmed = park) }
                bookmarkNow(force = true)
                val s = snap
                // our own verb's intent counts (a pause pressed just before the switch, the live read failed; L5)
                s != null && s.ours && s.reachable && effectiveTransport(elapsedMs()) == TransportState.PLAYING
            } catch (e: Exception) {
                host.log("sonos: hand-off bookkeeping failed (${e.javaClass.simpleName})")
                false
            }
            val ours = if (park) parkGate() else snap?.ours == true || pending != null
            endSession()
            ownsSpeaker = false
            closed = true
            lockFor(false)
            val target = uid
            if (ours) {
                work.submit({ if (park) transport.park(target) else transport.stop(target) }) { stopped ->
                    stopped.exceptionOrNull()?.let { host.log("sonos: stop failed (${it.javaClass.simpleName}) - the speaker may play on") }
                    then(wasPlaying)
                }
            } else {
                then(wasPlaying)
            }
        }
    }

    /** §6.1's gate: our stream, playing or paused (a play of ours in flight counts) - never a speaker someone else uses. */
    private fun parkGate(): Boolean {
        if (pending != null) return true
        val s = snap ?: return false
        return s.ours && s.transport?.live == true
    }

    /** `_sonos_refresh_live`: a live snapshot replaces ours only when armed (or [anyArmed]) and measured. */
    private fun live(f: Fields, anyArmed: Boolean = false) {
        if ((!f.armed && !anyArmed) || f.relS == null || f.uid != uid || !f.reachable) return
        pending = null
        accept(f)
    }

    /**
     * A hand-off that didn't finish in time (the actor was busy: a hushed play, the ramp): the bookmark
     * from the last measured snapshot, and a Stop queued behind whatever the actor holds - ours-gated
     * (our stream on the speaker, or a play of ours in flight). The renderer's release drains the actor,
     * so the Stop still goes out (qa-26a-step3a-code M1).
     */
    fun abandon() {
        if (closed) return
        bookmarkNow(force = true)
        val ours = (ownsSpeaker && snap?.ours == true) || pending != null
        endSession()
        ownsSpeaker = false
        closed = true
        lockFor(false)
        if (ours) {
            val target = uid
            work.submit({ transport.stop(target) }) { r -> r.exceptionOrNull()?.let { host.log("sonos: stop failed (${it.javaClass.simpleName})") } }
        }
    }

    /** No more SOAP and no more writes (the renderer is going); the locks go with it. */
    fun close() {
        closed = true
        fade?.set(true)
        endSession()
        lockFor(false)
    }

    // -- volume (§5.3) --

    /**
     * `SetVolume` of our own (the slider, a key press): clamped 0..100 and to the parent's cap in
     * force ([volumeTarget]); the base of the next press. A volume set from elsewhere (the Sonos
     * app, the speaker's buttons) during a session is never pulled down: only what we send is clamped
     * (vibb never fights an external change). A session's start is the one exception: a louder
     * speaker is set to the cap once, before Play ([SonosTransport.capVolume], §5.3).
     */
    fun setVolume(volume: Int) {
        if (closed) return
        val v = volumeTarget(volume, cap())
        volOpt = v
        volSets++
        val target = uid
        work.submit({ transport.volume(v, target) }) { r ->
            r.exceptionOrNull()?.let { host.log("sonos: volume failed (${it.javaClass.simpleName})") }
            host.changed()
        }
        host.changed()
    }

    /**
     * A volume key: our own last set (never the stale poll), else the last read, else 50 (vibb), +-
     * [delta]. A press up on a speaker already at or above the cap sends nothing: it would be pulled
     * down by an "up" (and the cap forbids going higher).
     */
    fun adjustVolume(delta: Int) {
        if (closed) return
        val base = volOpt ?: snap?.volume ?: 50
        val c = cap()?.takeIf { it in 1..99 }
        if (delta > 0 && c != null && base >= c) return host.log("sonos: volume at the cap ($c) - up ignored")
        setVolume(base + delta)
    }

    /**
     * The managed config changed (the restrictions broadcast; design 26a §5.3, qa-26a-corrections M1):
     * the cap in force went below our own last set (the override or the pause ended after the keys
     * turned the room up) - one `SetVolume` of the cap. A volume nobody of ours set (the Sonos app's,
     * [volOpt] null) or one at or below the cap is left alone: vibb never fights an external change.
     */
    fun capChanged() {
        if (closed || !session) return
        val c = cap()?.takeIf { it in 1..99 } ?: return
        val v = volOpt ?: return
        if (v <= c) return
        host.log("sonos: the cap went down to $c - our own $v follows it")
        setVolume(c)
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
        verbAt = now
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
            // a cancelled ramp (a pause, a new timer) never reports the timer's end (qa-26a-step3a-code M3)
            if (!cancel.get()) done()
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

        /** The bookmark's age term at most (vibb's sidecar measurement lag; design 26a §9). */
        const val BOOKMARK_AGE_MAX_MS = 5_000L

        /** Design 53 §2.4: a poll's non-forced bookmark at most this often (the confirm and 1 s polls), unless the transport changed. */
        const val BOOKMARK_MIN_MS = 5_000L

        /** Design 53 §2.3: an event wakes a confirm poll at most this often (an IDLE or a `MOVED` at once). */
        const val EVENT_WAKE_MIN_MS = 1_000L

        /** An IDLE's confirm poll that still reads PLAYING or TRANSITIONING asks once more after this. */
        const val IDLE_RECHECK_MS = 1_000L

        /** The seek hold ends on a PLAYING or PAUSED event this close to the target (the socket's position is exact). */
        const val SEEK_EVENT_TOL_MS = 1_000L

        /** `RelTime` truncates: a poll's display anchor is half a second on (design 53 §2.4, display only). */
        const val POLL_HALF_MS = 500L

        /** The disagreement rule: a poll counts against the socket only this long after its last event. */
        const val DISAGREE_AFTER_MS = 3_000L

        /** The live classify of a switch home (`/state?live=1`, 1021-1039). */
        const val LIVE_BUDGET_MS = 1_500L

        /** The sleep ramp's step (§5.2). */
        const val RAMP_STEP_MS = 1_000L

        /** A Storytel mint on a room (design 26b §5.1): the asset call (vibb `asset_url(id, timeout=6)`). */
        const val MINT_TIMEOUT_MS = 6_000L

        /**
         * ... and the whole mint, the login and the wait for its lock included. Every job behind it in the
         * room's actor - a poll, a pause, a volume key, a hand-off - waits up to this long (the actor is
         * serial and never cancels a job): accepted, as the hand-off's budget (`HANDOFF_MS`) and the
         * actor's drain (`SonosActor.DRAIN_MS`) cover it (design 26b §5.1, the merge's 4b list).
         */
        const val MINT_BUDGET_MS = 10_000L

        private const val FAR_PAST = -1_000_000_000_000L
    }
}
