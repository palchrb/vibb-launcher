package me.vibb.music.sonos

/*
 * The one Sonos session and its poll machine, ported from palchrb/vibb `pi/sonosd.py` (`Session`:
 * `publish`, `state`, `_classify`, `poll_loop`, `_cadence`, `_stall_bookkeeping`,
 * `_maybe_probe_migration`, `_probe_migration`; the closed sets of `tests/sonos_contract.py`; MIT
 * License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Design 26a §2.4. Pure: time is an injected elapsed clock (never the wall clock, vibb's
 * `monotonic`), the speaker comes through [SpeakerIo] and the household through [MigrationProbe], so
 * SonosSessionTest feeds canned polls. Not thread-safe: SonosTransport owns it behind its mutex.
 *
 * Storytel (design 26b §5.3, §5.7): "ours" is [sameStream] - a stored marker `storytel:book:<cid>`
 * matches the live signed URL by its `consumableId`, anything else is [normUri] equality as before; and
 * a uri goes into a log line only through [redactUri] (a signed URL's query is a bearer).
 */

import me.vibb.music.logic.redactUri
import me.vibb.music.logic.sameStream

/** Content kinds (`KINDS`; `spotify_sharelink` is phase 3). */
enum class Kind(val wire: String) {
    URL("url"),
    NRK_PROGRAM("nrk_program"),
}

/** `TRANSPORTS`, plus [UNKNOWN] for any other state a speaker reports (NO_MEDIA_PRESENT, ...). */
enum class TransportState {
    PLAYING,
    PAUSED_PLAYBACK,
    STOPPED,
    TRANSITIONING,
    UNREACHABLE,
    UNKNOWN,
    ;

    val live: Boolean get() = this == PLAYING || this == PAUSED_PLAYBACK

    companion object {
        /** `CurrentTransportState`; empty or missing is STOPPED (vibb's `or "STOPPED"`). */
        fun parse(raw: String?): TransportState {
            val s = raw?.trim().orEmpty().ifEmpty { "STOPPED" }
            return entries.firstOrNull { it.name == s && it != UNREACHABLE && it != UNKNOWN } ?: UNKNOWN
        }
    }
}

/** GetPositionInfo's fields, raw. */
data class PositionInfo(
    val track: String?,
    val trackUri: String?,
    val relTime: String?,
    val trackDuration: String?,
    val trackMetaData: String?,
) {
    /** A Storytel book's TrackURI (and the DIDL's `res`) is a signed URL: neither is printed (design 26b §0; qa L4). */
    override fun toString(): String =
        "PositionInfo(track=$track, trackUri=${trackUri?.let(::redactUri)}, relTime=$relTime, trackDuration=$trackDuration, " +
            "trackMetaData=${trackMetaData?.length} chars)"
}

/** What a poll reads from the session's speaker. Each call may throw (a [SonosException]). */
interface SpeakerIo {
    val uid: String

    fun positionInfo(): PositionInfo

    /** `CurrentTransportState`, raw. */
    fun transportInfo(): String?

    /** The coordinator of the group this speaker is in (`spk.group.coordinator.uid`), or null. */
    fun groupCoordinator(): String?

    fun volume(): Int
}

/** The topology and the other coordinators, for the migration probe. */
interface MigrationProbe {
    /** `refresh_topology()`: throws when nobody answers. */
    fun refreshTopology(): SpeakerDoc

    /** A candidate's own (transport, TrackURI), or null when it doesn't answer. */
    fun live(player: CachedPlayer): Pair<TransportState, String>?
}

/** The `stream_moved` hint: our stream now plays on [uid]; [uri] is the SESSION's (never empty). */
data class Moved(val uid: String, val name: String?, val uri: String) {
    /** A Storytel session's uri is a signed URL: never its token (design 26b §0). */
    override fun toString(): String = "Moved(uid=$uid, name=$name, uri=${redactUri(uri)})"
}

/**
 * One published snapshot (`/state`, contract §3). [measuredAt] is the elapsed time of the last
 * successful poll of THIS session (a failed poll keeps it and the last measured values, with
 * [reachable] false; a new play or adopt clears them); [measuredCadenceMs] is the cadence the poller
 * chose after that poll, so [fresh] needs nothing else; [nextPollMs] is the delay chosen after the
 * latest poll, failed or not (the backoff).
 */
data class Fields(
    val armed: Boolean,
    val uid: String?,
    val kind: Kind?,
    val seq: Long,
    val measuredAt: Long?,
    val reachable: Boolean = false,
    val transport: TransportState? = null,
    val relS: Double? = null,
    val durS: Double? = null,
    val uri: String? = null,
    val ours: Boolean = false,
    val foreignUri: String? = null,
    val groupedAway: Boolean = false,
    val coordinator: String? = null,
    val lostSession: Boolean = false,
    val volume: Int? = null,
    val retriedAt: Long? = null,
    val streamMoved: Moved? = null,
    val error: String? = null,
    /** Consecutive failed polls (the §3.2 table: the first 3 read BUFFERING). */
    val failedPolls: Int = 0,
    val nextPollMs: Long = SonosSession.POLL_MS,
    val measuredCadenceMs: Long = SonosSession.POLL_MS,
    /** The speaker's raw state was TRANSITIONING ([transport] then reports the last real one). */
    val transitioning: Boolean = false,
) {
    /** The uris as [redactUri] prints them: a Storytel book's TrackURI is a signed URL (design 26b §0). */
    override fun toString(): String =
        "Fields(armed=$armed, uid=$uid, kind=$kind, seq=$seq, measuredAt=$measuredAt, reachable=$reachable, transport=$transport, " +
            "relS=$relS, durS=$durS, uri=${uri?.let(::redactUri)}, ours=$ours, foreignUri=${foreignUri?.let(::redactUri)}, " +
            "groupedAway=$groupedAway, coordinator=$coordinator, lostSession=$lostSession, volume=$volume, retriedAt=$retriedAt, " +
            "streamMoved=$streamMoved, error=$error, failedPolls=$failedPolls, nextPollMs=$nextPollMs, " +
            "measuredCadenceMs=$measuredCadenceMs, transitioning=$transitioning)"

    /**
     * Fresh, defined once (QA H2): measured within the cadence chosen after that successful poll,
     * plus [FRESH_SLACK_MS]. A failed poll never extends it (qa-26a-step1-code M2: the backoff
     * cadence of a failure would). vibb's `stale_s < 12` was tuned for a daemon re-reading a sidecar
     * every 5 s; at a 15 s cruise it would flicker every poll.
     */
    fun fresh(nowMs: Long): Boolean = measuredAt != null && nowMs - measuredAt <= measuredCadenceMs + FRESH_SLACK_MS

    companion object {
        const val FRESH_SLACK_MS = 10_000L
    }
}

/** What one tick of the poll loop decided. */
sealed interface Tick {
    /** Not armed: wait for a wake (a play or an adopt). */
    data object Idle : Tick

    /** The session stood down just now (`DISARM_AFTER_S`); wait for a wake. */
    data object Disarmed : Tick

    /** Poll again after [delayMs]; [stallRetryAtS] = the frozen position to retry at (one retry). */
    data class Next(val delayMs: Long, val stallRetryAtS: Double? = null) : Tick
}

class SonosSession(private val elapsedMs: () -> Long, private val log: (String) -> Unit = {}) {
    var armed = false
        private set
    var uid: String? = null
        private set
    var kind: Kind? = null
        private set

    /** What WE set (ours-detection): the built uri. */
    var uri: String? = null
        private set

    /**
     * The last published snapshot. Written only by the actor (SonosTransport's mutex); volatile so
     * another thread reading it sees a whole, current copy (qa-26a-step1-code L7).
     */
    @Volatile
    var snapshot: Fields = Fields(armed = false, uid = null, kind = null, seq = 0, measuredAt = null)
        private set

    private var seq = 0L
    private var lastOkAt: Long? = null
    private var frozen = 0
    private var lastPos: Double? = null
    private var retriedAt: Long? = null
    private var auxVolume: Int? = null
    private var auxGroupedAway = false
    private var auxCoordinator: String? = null
    private var auxN = 0
    private var fastAt: Long? = null
    private var oursAt: Long? = null
    private var migrateTries = 0
    private var moved: Moved? = null
    private var lastTr: TransportState? = null
    private var didlChecked = false
    private var oursLogged: String? = null
    private var downSince: Long? = null
    private var fails = 0

    /** The listing's duration of the item we played: the tail cadence's only (null after an adopt). */
    private var cadenceHintS: Double? = null

    /** The cadence chosen after the last successful poll (freshness). */
    private var lastCadence = POLL_MS

    /** Probe attempts left in this transition (tests). */
    val migrateTriesLeft: Int get() = migrateTries

    /** The pending hint, if any. */
    val streamMoved: Moved? get() = moved

    // -- lifecycle (SonosTransport, under its mutex) --

    /** `play()`'s first half: uid and kind BEFORE any SOAP (452); a new session voids a pending hint. */
    fun beginPlay(uid: String, kind: Kind) {
        this.uid = uid
        this.kind = kind
        moved = null
        migrateTries = 0
        oursAt = null
    }

    /**
     * `play()`'s success: the built [uri] (only now), armed, the stall and aux state reset, fast cadence.
     * [hintS] is the listing's duration ([PlayBody.cadenceHintS]): the tail cadence's when the speaker
     * reads none (design 53 §2.4), never published, never the bookmark's.
     */
    fun played(uri: String, hintS: Double? = null) {
        this.uri = uri
        cadenceHintS = hintS
        armed = true
        didlChecked = false
        frozen = 0
        lastPos = null
        retriedAt = null
        // new session: forget the old speaker's volume and topology, refresh them on the first poll
        auxVolume = null
        auxGroupedAway = false
        auxCoordinator = null
        auxN = 0
        fastAt = elapsedMs()
        newSession()
    }

    /**
     * What a new session (a play or an adopt) must not inherit (qa-26a-step1-code H1, H2, M1): the
     * down streak and the failure count (a play after a long STOPPED would be disarmed by its own
     * first poll, its first failure backed off 300 s), the last real state (a buffering start would
     * read the last episode's STOPPED), and the last session's measurement (a failed first poll would
     * publish the old episode's position as ours). vibb's `down_since` is the same latent bug.
     */
    private fun newSession() {
        downSince = null
        fails = 0
        lastTr = null
        lastOkAt = null
        lastCadence = POLL_MS
        snapshot = Fields(armed = armed, uid = uid, kind = kind, seq = seq, measuredAt = null)
    }

    /** `adopt()`: re-attach to a session already playing - bookkeeping only. */
    fun adopt(uid: String, kind: Kind, uri: String) {
        this.uid = uid
        this.kind = kind
        this.uri = uri
        cadenceHintS = null
        armed = true
        frozen = 0
        lastPos = null
        retriedAt = null
        moved = null
        migrateTries = 0
        oursAt = null
        auxN = 0
        fastAt = elapsedMs()
        newSession()
    }

    /** A verb went out: poll fast while it settles. */
    fun stampVerb() {
        fastAt = elapsedMs()
    }

    /** `verb("stop")`: the session is over until the next play or adopt. */
    fun disarm() {
        armed = false
    }

    /** Our own `SetVolume`: the snapshot must not lag it (GetVolume rides the slow sub-cadence). */
    fun ownVolume(volume: Int) {
        auxVolume = volume
        snapshot = snapshot.copy(volume = volume)
    }

    // -- the poll --

    /**
     * One pass of `poll_loop`: classify, the stall bookkeeping, the migration probe, publish; a
     * failure publishes UNREACHABLE. Then the down streak: neither PLAYING nor PAUSED for
     * [DISARM_AFTER_MS] stands the session down (republished at once). [io] resolves the speaker
     * inside the guard (an unknown uid is a failed poll too).
     */
    fun tick(io: () -> SpeakerIo, probe: MigrationProbe?): Tick {
        if (!armed) {
            downSince = null
            fails = 0
            return Tick.Idle
        }
        var retry: Double? = null
        try {
            val fields = classify(io())
            fails = 0
            retry = stallBookkeeping(fields)
            if (probe != null) maybeProbeMigration(fields, probe)
            publish(fields)
        } catch (e: Exception) {
            fails++
            publishUnreachable(e.javaClass.simpleName)
        }
        val snap = snapshot
        val tr = snap.transport
        val now = elapsedMs()
        if (tr != null && tr.live) {
            downSince = null
        } else if (downSince == null) {
            downSince = now
        }
        val since = downSince
        if (since != null && now - since >= DISARM_AFTER_MS) {
            log("session stood down ($tr for ${(now - since) / 1000}s) - polling stops until the next play")
            armed = false
            seq++
            snapshot = snap.copy(armed = false, seq = seq)
            return Tick.Disarmed
        }
        val delay = cadenceMs(tr, snap, fails)
        if (snap.reachable) lastCadence = delay
        snapshot = snapshot.copy(nextPollMs = delay, measuredCadenceMs = lastCadence)
        return Tick.Next(delay, retry)
    }

    /**
     * `_classify`: one GetPositionInfo + one GetTransportInfo -> the snapshot's fields. Volume and
     * the group ride the [AUX_EVERY] sub-cadence (and the session start), kept on failure. Without
     * [aux] (the bedtime look, design 26a §6.1: its budget is the Stop's) neither is read and the
     * sub-cadence is left as it was.
     */
    fun classify(io: SpeakerIo, aux: Boolean = true): Fields {
        val pos = io.positionInfo()
        var transport = TransportState.parse(io.transportInfo())
        val transitioning = transport == TransportState.TRANSITIONING
        if (transitioning) {
            // buffering / track-change limbo: the last REAL state (a pause icon on every fresh start, G1-d)
            transport = lastTr ?: TransportState.PLAYING
        } else {
            lastTr = transport
        }
        val trackUri = pos.trackUri.orEmpty()
        val rel = hmsToS(pos.relTime)
        val dur = hmsToS(pos.trackDuration)
        // design 26b §5.3: a Storytel marker (a look before the live uri is known) matches by consumableId
        val ours = sameStream(uri, trackUri)
        if (!ours && !uri.isNullOrEmpty() && trackUri.isNotEmpty() && oursLogged != trackUri) {
            oursLogged = trackUri
            // design 26b §5.7: scheme, host and path only - a signed URL's query is a bearer
            log("not-ours? speaker=${redactUri(trackUri)} vs set=${redactUri(uri)}")
        }
        // Grouped away, on the same poll (stage B1): a member's own transport names its coordinator.
        // x-rincon-mp3radio:// and x-rincon-queue: can't match (char 9 is '-'); an EMPTY uri decides nothing.
        if (trackUri.startsWith("x-rincon:")) {
            auxGroupedAway = true
            auxCoordinator = trackUri.substringAfter(':')
        } else if (trackUri.isNotEmpty()) {
            auxGroupedAway = false
            auxCoordinator = null
        }
        if (aux && auxN <= 0) {
            auxN = AUX_EVERY
            try {
                val coordinator = io.groupCoordinator()
                val away = coordinator != null && coordinator != io.uid
                auxGroupedAway = away
                auxCoordinator = if (away) coordinator else null
            } catch (_: Exception) {
                // keep the last known topology
            }
            try {
                auxVolume = io.volume()
            } catch (_: Exception) {
                // keep the last known volume
            }
        }
        if (aux) auxN--
        val lost = transport == TransportState.STOPPED && trackUri.isEmpty()
        if (ours && !didlChecked) {
            didlChecked = true
            val meta = pos.trackMetaData.orEmpty()
            if (meta.isEmpty() || meta == "NOT_IMPLEMENTED") {
                log("didl REJECTED by the speaker (no TrackMetaData) - metadata will not render in the Sonos app")
            } else {
                log("didl accepted (${meta.length} chars echoed)")
            }
        }
        // migration-follow bookkeeping: every ours+live sighting re-arms the probe and clears a hint
        if (ours && transport.live) {
            oursAt = elapsedMs()
            migrateTries = MIGRATE_TRIES
            moved = null
        }
        return Fields(
            armed = armed, uid = uid, kind = kind, seq = seq, measuredAt = elapsedMs(),
            reachable = true, transport = transport, relS = rel, durS = dur, uri = trackUri, ours = ours,
            foreignUri = if (ours) null else trackUri.ifEmpty { null },
            groupedAway = auxGroupedAway, coordinator = auxCoordinator, lostSession = lost, volume = auxVolume,
            transitioning = transitioning,
        )
    }

    /**
     * `publish`: a new [seq], the session's identity, and the hint while one is pending. A reachable
     * [fields] is a successful poll: its [Fields.measuredAt] is the new freshness anchor.
     */
    fun publish(fields: Fields) {
        seq++
        if (fields.reachable) lastOkAt = fields.measuredAt
        snapshot = fields.copy(armed = armed, uid = uid, kind = kind, seq = seq, retriedAt = retriedAt, streamMoved = moved, failedPolls = fails)
    }

    /** A failed poll: UNREACHABLE with the error, the last measured values kept (freeze, never zero). */
    private fun publishUnreachable(error: String) {
        seq++
        snapshot = snapshot.copy(
            armed = armed, uid = uid, kind = kind, seq = seq, measuredAt = lastOkAt, reachable = false,
            transport = TransportState.UNREACHABLE, retriedAt = retriedAt, streamMoved = moved, error = error, failedPolls = fails,
            transitioning = false, measuredCadenceMs = lastCadence,
        )
    }

    /**
     * `_cadence`: PAUSED 15 s; UNREACHABLE backs off from 5 s to 300 s; STOPPED 5 s only while a
     * migration transition pends, else 60 s; PLAYING 5 s near the end (the boundary machinery; the
     * speaker's duration, else our play's listed one, design 53 §2.4), else the 15 s cruise. Deviation (qa-26a-step1-code M1, design 26a §9): 5 s whatever the state while
     * the speaker reads TRANSITIONING or inside the window after a verb or an arm - vibb fast-polls
     * only PLAYING there, so a resume reported as PAUSED (TRANSITIONING keeps the last real state)
     * slept 15 s, and a start buffering after a STOPPED 60 s, longer than the controller's 8 s hold.
     */
    fun cadenceMs(tr: TransportState?, snap: Fields, fails: Int): Long {
        val now = elapsedMs()
        if (tr == TransportState.UNREACHABLE) return minOf(POLL_MS * (1L shl minOf(fails, 6)), 300_000L)
        val fast = fastAt
        if (snap.transitioning || (fast != null && now - fast < VERB_FAST_MS)) return POLL_MS
        return when (tr) {
            TransportState.PAUSED_PLAYBACK -> POLL_PAUSED_MS
            TransportState.STOPPED -> {
                val ours = oursAt
                if (snap.uri.isNullOrEmpty() && moved == null && migrateTries > 0 && ours != null && now - ours <= MIGRATE_WINDOW_MS) POLL_MS else POLL_STOPPED_MS
            }
            else -> {
                val rel = snap.relS
                // the speaker's own duration, else the listing's hint (design 53 §2.4: a podcast whose speaker
                // reads 0:00 keeps its 5 s polls near the end; cadence only)
                val dur = snap.durS?.takeIf { it != 0.0 } ?: cadenceHintS?.takeIf { snap.ours }
                val tail = rel != null && dur != null && dur != 0.0 && dur - rel <= TAIL_FAST_S
                if (tail) POLL_MS else POLL_CRUISE_MS
            }
        }
    }

    /**
     * `_stall_bookkeeping`: PLAYING + ours + the same `relS` for [STALL_POLLS] polls -> one retry
     * per session (the position to retry at; the transport does Stop, Play, seek). Whole polls, not
     * one: a Wi-Fi rebuffer would false-positive.
     */
    fun stallBookkeeping(fields: Fields): Double? {
        if (fields.transport != TransportState.PLAYING || !fields.ours) {
            frozen = 0
            lastPos = null
            return null
        }
        val rel = fields.relS
        if (rel != null && rel == lastPos) frozen++ else frozen = 0
        lastPos = rel
        if (frozen >= STALL_POLLS && retriedAt == null) {
            retriedAt = elapsedMs()
            log("position frozen - one in-place retry")
            return rel ?: 0.0
        }
        return null
    }

    /**
     * `_maybe_probe_migration`: only the migration signature (STOPPED with an EMPTY TrackURI) after
     * an ours+live sighting within [MIGRATE_WINDOW_MS], [MIGRATE_TRIES] attempts per transition; a
     * found hint ends the probing. A hijack (x-rincon) or an episode end (uri retained) never enters.
     */
    fun maybeProbeMigration(fields: Fields, probe: MigrationProbe) {
        if (!(fields.transport == TransportState.STOPPED && fields.uri.isNullOrEmpty())) return
        if (moved != null || migrateTries <= 0) return
        val ours = oursAt ?: return
        if (elapsedMs() - ours > MIGRATE_WINDOW_MS) return
        migrateTries--
        log("migration probe ${MIGRATE_TRIES - migrateTries}/$MIGRATE_TRIES: our uid stopped empty - looking for the stream on another coordinator")
        try {
            probeMigration(probe)
        } catch (e: Exception) {
            log("migration probe failed (${e.javaClass.simpleName})")
        }
        if (moved == null && migrateTries <= 0) log("migration probe: no coordinator carries our stream - settling into lost-session")
    }

    /**
     * `_probe_migration`: the LIVE topology, then each other coordinator's own transport (never the
     * aux coordinator, which ghosts). Match: PLAYING/PAUSED and [sameStream] (1b's [normUri] equality) with the session's
     * uri. A hit records [Moved] with the session's uri.
     */
    private fun probeMigration(probe: MigrationProbe) {
        val doc = probe.refreshTopology()
        val own = uri ?: return
        val candidates = doc.groups.filter { it.coordinator.isNotEmpty() && it.coordinator != uid }.take(MAX_CANDIDATES)
        for (g in candidates) {
            val rec = doc.players[g.coordinator] ?: continue
            val (tr, trackUri) = probe.live(rec) ?: continue
            if (!tr.live) continue
            if (!sameStream(own, trackUri)) continue
            moved = Moved(g.coordinator, rec.name, own)
            migrateTries = 0
            log("stream moved to ${rec.name} (${g.coordinator}) - hinting the controller")
            return
        }
    }

    companion object {
        const val POLL_MS = 5_000L
        const val POLL_PAUSED_MS = 15_000L
        const val POLL_STOPPED_MS = 60_000L
        const val POLL_CRUISE_MS = 15_000L
        const val TAIL_FAST_S = 45.0
        const val VERB_FAST_MS = 30_000L
        const val AUX_EVERY = 12
        const val DISARM_AFTER_MS = 600_000L
        const val MIGRATE_TRIES = 3
        const val MIGRATE_WINDOW_MS = 90_000L
        const val STALL_POLLS = 3
        const val MAX_CANDIDATES = 6
    }
}
