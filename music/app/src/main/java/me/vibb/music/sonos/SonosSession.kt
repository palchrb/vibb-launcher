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
 *
 * Design 53 §2.4 (ours): the poll is stamped at GetPositionInfo's return; the 1 s display poll and an
 * event's confirm polls pass guards so they change nothing else (freshness, the stall, the probe, the aux).
 * Design 53-d (ours): while the socket is healthy the regular check relaxes to once a minute ([POLL_SLOW_MS]).
 *
 * Design 54 §2.5, §2.9 (vibb's `spotify_sharelink` in `_classify` and `_probe_migration`): a share link's session is
 * the speaker's own queue - "ours" is vibb's prefix rule unless contradicted (`logic/SonosQueue.kt` `shareOurs`), the
 * fields carry the queue position and the decoded track, no lost-session, no DIDL check, no stall retry, and the probe
 * follows only the exact track at a continuous position.
 */

import me.vibb.music.logic.QueueRead
import me.vibb.music.logic.containerContradicts
import me.vibb.music.logic.redactUri
import me.vibb.music.logic.sameStream
import me.vibb.music.logic.shareOurs
import me.vibb.music.logic.shareProbeMatches
import me.vibb.music.logic.trackSpotifyUri

/** Content kinds (`KINDS`). */
enum class Kind(val wire: String) {
    URL("url"),
    NRK_PROGRAM("nrk_program"),

    /**
     * Design 54: a Spotify link added to the speaker's own queue (vibb's `spotify_sharelink`, SoCo's ShareLink); the
     * session's uri is the queue (`x-rincon-queue:<uid>#0`), "ours" the prefix rule unless contradicted ([SonosSession]).
     */
    SPOTIFY_SHARELINK("spotify_sharelink"),
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
        "PositionInfo(track=$track, trackUri=${Fields.shown(trackUri)}, relTime=$relTime, trackDuration=$trackDuration, " +
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

    /**
     * Design 54 §2.3: the queue's container (`Browse Q:0 BrowseMetadata`, SoCo's `queue_size`): its `childCount` and
     * the answer's `UpdateID`. A read: the classify of a share link asks it once for a track neither list names.
     */
    fun queueMeta(): QueueMeta
}

/** A candidate's own transport, TrackURI and RelTime (design 54 §2.9: a share link's follow needs the position too). */
data class ProbeLive(val transport: TransportState, val trackUri: String, val relS: Double? = null) {
    override fun toString(): String = "ProbeLive(transport=$transport, trackUri=${Fields.shown(trackUri)}, relS=$relS)"
}

/** The topology and the other coordinators, for the migration probe. */
interface MigrationProbe {
    /** `refresh_topology()`: throws when nobody answers. */
    fun refreshTopology(): SpeakerDoc

    /** A candidate's own transport, TrackURI and position, or null when it doesn't answer. */
    fun live(player: CachedPlayer): ProbeLive?
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
    /** When [volume] was last READ from the speaker (an aux read or a read before a key press or the fade; design 53-d). */
    val volumeAt: Long? = null,
    val retriedAt: Long? = null,
    val streamMoved: Moved? = null,
    val error: String? = null,
    /** Consecutive failed polls (the §3.2 table: the first 3 read BUFFERING). */
    val failedPolls: Int = 0,
    val nextPollMs: Long = SonosSession.POLL_MS,
    val measuredCadenceMs: Long = SonosSession.POLL_MS,
    /** The speaker's raw state was TRANSITIONING ([transport] then reports the last real one). */
    val transitioning: Boolean = false,
    /** This poll ran the migration probe's last try and no coordinator carries our stream (design 53 §2.8's advance guard). */
    val probeMissed: Boolean = false,
    /** Design 54 §2.5: a share link's queue position (GetPositionInfo's `Track`, 1-based); null for every other kind. */
    val trackNo: Int? = null,
    /** Design 54 §2.5: a share link's playing track, decoded (`spotify:track:<id>`); null for every other kind or none. */
    val trackSpotifyUri: String? = null,
) {
    /**
     * The uris as [redactUri] prints them: a Storytel book's TrackURI is a signed URL (design 26b §0); a Spotify queue
     * track's id never (design 54 §2.14).
     */
    override fun toString(): String =
        "Fields(armed=$armed, uid=$uid, kind=$kind, seq=$seq, measuredAt=$measuredAt, reachable=$reachable, transport=$transport, " +
            "relS=$relS, durS=$durS, uri=${shown(uri)}, ours=$ours, foreignUri=${shown(foreignUri)}, trackNo=$trackNo, " +
            "groupedAway=$groupedAway, coordinator=$coordinator, lostSession=$lostSession, volume=$volume, volumeAt=$volumeAt, retriedAt=$retriedAt, " +
            "streamMoved=$streamMoved, error=$error, failedPolls=$failedPolls, nextPollMs=$nextPollMs, " +
            "measuredCadenceMs=$measuredCadenceMs, transitioning=$transitioning, probeMissed=$probeMissed)"

    /**
     * Fresh, defined once (QA H2): measured within the cadence chosen after that successful poll,
     * plus [FRESH_SLACK_MS]. A failed poll never extends it (qa-26a-step1-code M2: the backoff
     * cadence of a failure would). vibb's `stale_s < 12` was tuned for a daemon re-reading a sidecar
     * every 5 s; at a 15 s cruise it would flicker every poll.
     */
    fun fresh(nowMs: Long): Boolean = measuredAt != null && nowMs - measuredAt <= measuredCadenceMs + FRESH_SLACK_MS

    companion object {
        const val FRESH_SLACK_MS = 10_000L

        /** A uri for a line: a Spotify queue track by its prefix only (design 54 §2.14), anything else [redactUri]'s. */
        internal fun shown(u: String?): String? = when {
            u == null -> null
            trackSpotifyUri(u) != null -> u.substringBefore(':') + ":…"
            else -> redactUri(u)
        }
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

class SonosSession(
    private val elapsedMs: () -> Long,
    private val log: (String) -> Unit = {},
    /** The player screen shows (`PlayerHub.playerShown`, design 53 §2.4): read, never called from main. */
    private val playerShown: () -> Boolean = { false },
    /** The event socket is healthy (a volatile flag, design 53 §2.2). */
    private val socketHealthy: () -> Boolean = { false },
) {
    /** Volatile: the renderer reads it on main for the event socket's lifetime (design 53 §2.2). */
    @Volatile
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
    private var auxVolumeAt: Long? = null
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

    /** The slow check's last logged state (design 53-d). */
    private var slowLogged = false

    /** Design 53 §2.4's guards against the extra polls: the last counted stall poll, probe try and aux read. */
    private var stallAt: Long? = null
    private var probedAt: Long? = null
    private var auxAt: Long? = null

    /** The listing's duration of the item we played: the tail cadence's only (null after an adopt). */
    private var cadenceHintS: Double? = null

    /** Design 53 §2.8: the probe's candidate (asked first) and when our group last changed (the kept-TrackURI window). */
    private var hinted: String? = null
    private var groupAt: Long? = null

    /** The cadence chosen after the last successful poll (freshness). */
    private var lastCadence = POLL_MS

    /**
     * Design 54 §2.5: what makes a Spotify queue track ours - the target we transferred, our listing's uris, the queue we
     * read (its uris and `UpdateID`, the baseline) and the socket's last container. Null for every other kind, and for a
     * share session whose baseline isn't known (vibb's prefix rule alone: an old `renderer.json`).
     */
    private class Share(val target: String, val listed: Set<String>) {
        var queue: Set<String> = emptySet()
        var baseline: String? = null
        var container: String? = null

        /** The last track neither list names and whether the queue's version had moved for it (read once per track). */
        var unknown: Pair<String, Boolean>? = null
    }

    private var share: Share? = null

    /** vibb's `last_ours_track`/`last_ours_rel` (design 54 §2.9): the share link's last ours + live sighting, the probe's match. */
    private var lastOursTrack: String? = null
    private var lastOursRel: Double? = null

    /** Our own speaker sleep timer (design 54 §2.9 item 4): the uid, when it was set (elapsed) and for how long. */
    private var sleepSet: Triple<String, Long, Long>? = null

    /** The share link's target (design 54 §2.9: `renderer.json`'s `shareTarget`), or null. */
    val shareTarget: String? get() = share?.target

    /** Probe attempts left in this transition (tests). */
    val migrateTriesLeft: Int get() = migrateTries

    /** The pending hint, if any. */
    val streamMoved: Moved? get() = moved

    // -- lifecycle (SonosTransport, under its mutex) --

    /**
     * `play()`'s first half: uid and kind BEFORE any SOAP (452); a new session voids a pending hint, and a share link's
     * knowledge (a transfer sets it again once the queue is ours, [shareSet]).
     */
    fun beginPlay(uid: String, kind: Kind) {
        this.uid = uid
        this.kind = kind
        moved = null
        migrateTries = 0
        oursAt = null
        hinted = null
        share = null
        lastOursTrack = null
        lastOursRel = null
    }

    // -- design 54: a share link's session --

    /** A transfer (or an adopt that kept the session) of [target] with our listing's track uris [listed]: what is ours. */
    fun shareSet(target: String, listed: Collection<String>) {
        share = Share(target, listed.toSet())
    }

    /** A read of the queue: its uris (`ours` (b): "in the queue we read") and its `UpdateID`, the baseline (N3). */
    fun shareRead(read: QueueRead) {
        val sh = share ?: return
        sh.queue = read.uris
        read.updateId?.let { sh.baseline = it }
        sh.unknown = null
    }

    /** The socket's last `metadataStatus` container (§2.5 (a)): fed by the controller; null is no evidence. */
    fun shareContainer(objectId: String?) {
        share?.container = objectId
    }

    /**
     * A `queuePlay` went out (design 54 §2.4): the same session, now at another position - armed again (a jump after
     * the 10-minute disarm), the fast cadence, the target's listed duration as the tail cadence's hint.
     */
    fun jumped(hintS: Double?) {
        armed = true
        fastAt = elapsedMs()
        cadenceHintS = hintS
        frozen = 0
        lastPos = null
    }

    /** Our own sleep timer on [uid] was set for [durationMs] now, or ([durationMs] null) is ours no more. */
    fun ownSleepTimer(uid: String, durationMs: Long?) {
        sleepSet = durationMs?.let { Triple(uid, elapsedMs(), it) }
    }

    /** What our own sleep timer on [uid] has left now, or null when none of ours is set there. */
    fun ownSleepLeftMs(uid: String): Long? {
        val (on, at, ms) = sleepSet ?: return null
        if (on != uid) return null
        return ms - (elapsedMs() - at)
    }

    /** Design 53 §2.8: [uid] may carry our stream now (a `MOVED`'s player, a `groups` event's, our "−"'s first member) - the probe asks it first. */
    fun hint(uid: String) {
        hinted = uid
    }

    /**
     * Design 53 §2.8's advance guard: our group changed at [at]. For [GROUP_WINDOW_MS] the probe also takes a STOPPED
     * of ours that kept its TrackURI (an old coordinator may keep it after leaving), so the next episode never starts
     * here while another room plays the current one.
     */
    fun groupChanged(at: Long) {
        groupAt = at
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
        auxVolumeAt = null
        auxGroupedAway = false
        auxCoordinator = null
        auxN = 0
        fastAt = elapsedMs()
        newSession()
    }

    /** The extra polls' guards start over with a session (its first poll counts, reads the aux, may probe). */
    private fun resetGuards() {
        stallAt = null
        probedAt = null
        auxAt = null
    }

    /**
     * What a new session (a play or an adopt) must not inherit (qa-26a-step1-code H1, H2, M1): the
     * down streak and the failure count (a play after a long STOPPED would be disarmed by its own
     * first poll, its first failure backed off 300 s), the last real state (a buffering start would
     * read the last episode's STOPPED), and the last session's measurement (a failed first poll would
     * publish the old episode's position as ours). vibb's `down_since` is the same latent bug.
     */
    private fun newSession() {
        resetGuards()
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
        // design 54 §2.9: an adopted share session knows nothing of the queue until [shareSet] (vibb's prefix rule meanwhile)
        share = null
        lastOursTrack = null
        lastOursRel = null
        cadenceHintS = null
        armed = true
        frozen = 0
        lastPos = null
        retriedAt = null
        moved = null
        hinted = null
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

    /**
     * Design 53-d (qa-53d-code 2): a GetVolume read outside the aux (before a key press or the sleep fade, when no set
     * of ours is the base and the last read is old) - the snapshot's volume and its read time.
     */
    fun volumeRead(volume: Int) {
        val at = elapsedMs()
        auxVolume = volume
        auxVolumeAt = at
        snapshot = snapshot.copy(volume = volume, volumeAt = at)
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
            val missed = probe != null && maybeProbeMigration(fields, probe)
            publish(fields.copy(probeMissed = missed))
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
        // one reading of the socket's health for the cadence and the line (qa-53d-code 8)
        val slow = relaxed(snap)
        val delay = cadenceMs(tr, snap, fails, slow)
        // design 53-d: said once per change, for the device check (one line, never per poll)
        if (slow != slowLogged) {
            slowLogged = slow
            log(if (slow) "sonos: the regular UPnP check once a minute (socket healthy)" else "sonos: the regular UPnP check at 5/15 s")
        }
        // freshness keeps the normal rule's cadence, never the 1 s one: the screen going off must not read stale
        if (snap.reachable) lastCadence = delay
        val next = if (precise(snap, now)) minOf(delay, POLL_PRECISE_MS) else delay
        snapshot = snapshot.copy(nextPollMs = next, measuredCadenceMs = lastCadence)
        return Tick.Next(next, retry)
    }

    /**
     * The 1 s display poll (design 53 §2.4): while the player screen shows and the last snapshot is
     * ours, fresh and PLAYING - only without a healthy socket (it pushes every change, exactly).
     */
    private fun precise(snap: Fields, now: Long): Boolean =
        snap.reachable && snap.ours && snap.transport == TransportState.PLAYING && snap.fresh(now) && playerShown() && !socketHealthy()

    /**
     * `_classify`: one GetPositionInfo + one GetTransportInfo -> the snapshot's fields. Volume and
     * the group ride the [AUX_EVERY] sub-cadence (and the session start), kept on failure. Without
     * [aux] (the bedtime look, design 26a §6.1: its budget is the Stop's) neither is read and the
     * sub-cadence is left as it was.
     */
    fun classify(io: SpeakerIo, aux: Boolean = true): Fields {
        val pos = io.positionInfo()
        // design 53 §2.4: the poll is stamped where it measured - right after GetPositionInfo, the earlier and truer time
        val measuredAt = elapsedMs()
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
        val shareKind = kind == Kind.SPOTIFY_SHARELINK
        val spotify = if (shareKind) trackSpotifyUri(trackUri) else null
        // design 26b §5.3: a Storytel marker (a look before the live uri is known) matches by consumableId; design 54
        // §2.5: a share link's queue by vibb's prefix rule unless contradicted
        val ours = if (shareKind) shareIsOurs(io, spotify) else sameStream(uri, trackUri)
        if (!ours && !uri.isNullOrEmpty() && trackUri.isNotEmpty() && oursLogged != trackUri) {
            oursLogged = trackUri
            // design 26b §5.7: scheme, host and path only - a signed URL's query is a bearer; design 54 §2.14: no Spotify id
            log("not-ours? speaker=${Fields.shown(trackUri)} vs set=${Fields.shown(uri)}")
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
        val auxDue = auxAt?.let { measuredAt - it >= AUX_MIN_MS } ?: true
        if (aux && auxN <= 0 && auxDue) {
            auxN = AUX_EVERY
            auxAt = measuredAt
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
                auxVolumeAt = measuredAt
            } catch (_: Exception) {
                // keep the last known volume
            }
        }
        if (aux) auxN--
        // design 54 §2.5: no lost-session and no DIDL check for a share link (vibb's sharelink branch)
        val lost = !shareKind && transport == TransportState.STOPPED && trackUri.isEmpty()
        if (shareKind && ours && transport.live) {
            lastOursTrack = spotify
            lastOursRel = rel
        }
        if (ours && !didlChecked && !shareKind) {
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
            probedAt = null
            moved = null
        }
        return Fields(
            armed = armed, uid = uid, kind = kind, seq = seq, measuredAt = measuredAt,
            reachable = true, transport = transport, relS = rel, durS = dur, uri = trackUri, ours = ours,
            foreignUri = if (ours) null else trackUri.ifEmpty { null },
            groupedAway = auxGroupedAway, coordinator = auxCoordinator, lostSession = lost, volume = auxVolume, volumeAt = auxVolumeAt,
            transitioning = transitioning,
            trackNo = if (shareKind) pos.track?.trim()?.toIntOrNull() else null,
            trackSpotifyUri = spotify,
        )
    }

    /**
     * Design 54 §2.5 (54-1, N2): a share link's `ours` - vibb's prefix rule ([spotify] not null) unless contradicted by
     * the socket's container naming another playlist, or by a track in neither our listing nor the queue we read while
     * the queue's `UpdateID` moved from the baseline (one `BrowseMetadata`, read once per such track; no answer or no
     * baseline is no evidence). With no share knowledge (an adopt before its baseline) the prefix alone.
     */
    private fun shareIsOurs(io: SpeakerIo, spotify: String?): Boolean {
        val sh = share ?: return spotify != null
        if (spotify == null) return false
        val byContainer = containerContradicts(sh.container, sh.target)
        val known = spotify in sh.listed || spotify in sh.queue
        val moved = if (known || byContainer) {
            null
        } else {
            val base = sh.baseline
            sh.unknown?.takeIf { it.first == spotify }?.second ?: if (base == null) {
                null
            } else {
                try {
                    val now = io.queueMeta().updateId
                    (now != null && now != base).also { sh.unknown = spotify to it }
                } catch (_: Exception) {
                    null // no answer decides nothing against ours
                }
            }
        }
        return shareOurs(spotify, byContainer, known, moved)
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
     * Design 53-d (ours): under a healthy event socket PAUSED and PLAYING's cruise are [POLL_SLOW_MS] ([relaxed]),
     * the cruise cut short where the tail begins; the tail, the verb window, TRANSITIONING, the migration window and
     * the backoff stay as they are.
     */
    fun cadenceMs(tr: TransportState?, snap: Fields, fails: Int, slow: Boolean = relaxed(snap)): Long {
        val now = elapsedMs()
        if (tr == TransportState.UNREACHABLE) return minOf(POLL_MS * (1L shl minOf(fails, 6)), 300_000L)
        val fast = fastAt
        if (snap.transitioning || (fast != null && now - fast < VERB_FAST_MS)) return POLL_MS
        return when (tr) {
            TransportState.PAUSED_PLAYBACK -> if (slow) POLL_SLOW_MS else POLL_PAUSED_MS
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
                when {
                    tail -> POLL_MS
                    // design 53-d: the slow check never sleeps past the tail's start - the advance needs a poll within
                    // ENDS_NEAR_S of the end, and the tail's 5 s polls give it (and the bookmark near the end)
                    slow -> if (rel != null && dur != null && dur > 0.0) {
                        ((dur - TAIL_FAST_S - rel) * 1000).toLong().coerceIn(POLL_MS, POLL_SLOW_MS)
                    } else {
                        POLL_SLOW_MS
                    }
                    else -> POLL_CRUISE_MS
                }
            }
        }
    }

    /**
     * Design 53-d: the slow UPnP check - while the event socket is healthy (open, our coordinator's playback
     * subscribed, acknowledged and read, live: [socketHealthy]) every change is pushed and wakes confirm polls, so
     * PLAYING's 15 s cruise and PAUSED's 15 s relax to [POLL_SLOW_MS]. Never while the speaker didn't answer, nor
     * while the stall counter runs (a frozen position is retried at today's pace, not a minute apart). Without a
     * healthy socket nothing changes.
     */
    private fun relaxed(snap: Fields): Boolean = snap.reachable && frozen == 0 && socketHealthy()

    /**
     * `_stall_bookkeeping`: PLAYING + ours + the same `relS` for [STALL_POLLS] polls -> one retry
     * per session (the position to retry at; the transport does Stop, Play, seek). Whole polls, not
     * one: a Wi-Fi rebuffer would false-positive.
     */
    fun stallBookkeeping(fields: Fields): Double? {
        // design 54 §2.5: a share link has no stall retry (vibb 895-899) - a frozen queue track is the speaker's matter
        if (fields.transport != TransportState.PLAYING || !fields.ours || fields.kind == Kind.SPOTIFY_SHARELINK) {
            frozen = 0
            lastPos = null
            stallAt = null
            return null
        }
        // design 53 §2.4: a poll counts only POLL_MS or more after the last counted one; an uncounted one (a confirm
        // or a 1 s poll) leaves the count and the last position alone - "the same RelTime at polls 5 s or more apart"
        val at = fields.measuredAt ?: elapsedMs()
        if (stallAt?.let { at - it < POLL_MS } == true) return null
        stallAt = at
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
     * found hint ends the probing. A hijack (x-rincon) or an episode end (uri retained) never enters -
     * except, for [GROUP_WINDOW_MS] after our group changed (design 53 §2.8), a STOPPED of ours that kept
     * its TrackURI: the old coordinator may keep it after leaving. True when the probe's last try ran
     * and no coordinator carries our stream (the controller's advance guard reads it as [Fields.probeMissed]).
     */
    fun maybeProbeMigration(fields: Fields, probe: MigrationProbe): Boolean {
        val now = elapsedMs()
        val kept = fields.ours && groupAt?.let { now - it in 0 until GROUP_WINDOW_MS } == true
        if (!(fields.transport == TransportState.STOPPED && (fields.uri.isNullOrEmpty() || kept))) return false
        if (moved != null || migrateTries <= 0) return false
        val ours = oursAt ?: return false
        if (now - ours > MIGRATE_WINDOW_MS) return false
        // design 53 §2.4: a try only MIGRATE_SPACING_MS after the last, whatever woke the poll
        if (probedAt?.let { now - it < MIGRATE_SPACING_MS } == true) return false
        probedAt = now
        migrateTries--
        log("migration probe ${MIGRATE_TRIES - migrateTries}/$MIGRATE_TRIES: our uid stopped ${if (fields.uri.isNullOrEmpty()) "empty" else "after a group change"} - looking for the stream on another coordinator")
        try {
            probeMigration(probe)
        } catch (e: Exception) {
            log("migration probe failed (${e.javaClass.simpleName})")
        }
        if (moved == null && migrateTries <= 0) log("migration probe: no coordinator carries our stream - settling into lost-session")
        // only the last try's miss says nobody carries it: right after a coordinator left, the new one can still be
        // missing from the topology or TRANSITIONING at the first try (qa-53b-code 7)
        return moved == null && migrateTries <= 0
    }

    /**
     * `_probe_migration`: the LIVE topology, then each other coordinator's own transport (never the
     * aux coordinator, which ghosts) - the hinted one first (design 53 §2.8). Match: PLAYING/PAUSED and
     * [sameStream] (1b's [normUri] equality) with the session's uri. A hit records [Moved] with the session's uri.
     */
    private fun probeMigration(probe: MigrationProbe) {
        val doc = probe.refreshTopology()
        val own = uri ?: return
        val others = doc.groups.filter { it.coordinator.isNotEmpty() && it.coordinator != uid }
        val candidates = others.sortedByDescending { it.coordinator == hinted }.take(MAX_CANDIDATES)
        for (g in candidates) {
            val rec = doc.players[g.coordinator] ?: continue
            val live = probe.live(rec) ?: continue
            if (!live.transport.live) continue
            // design 54 §2.9: a share link only on the exact track and position continuity (vibb's sonos_migrate_probe.py 3)
            val carries = if (kind == Kind.SPOTIFY_SHARELINK) shareProbeMatches(lastOursTrack, lastOursRel, live.trackUri, live.relS) else sameStream(own, live.trackUri)
            if (!carries) continue
            moved = Moved(g.coordinator, rec.name, own)
            migrateTries = 0
            hinted = null
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

        /**
         * Design 53-d: the regular UPnP check while the event socket is healthy (PLAYING outside the tail, PAUSED).
         * 60 s, the low end of 60-120: a bookmark a crash can lose stays at most a minute (the socket never writes
         * Room), the bar's extrapolation cap and the freshness window stay above it ([SonosSpec.MAX_EXTRAPOLATE_MS],
         * [Fields.FRESH_SLACK_MS]), and STOPPED already polls at it.
         */
        const val POLL_SLOW_MS = 60_000L

        /** Design 53 §2.4: the display poll while the player shows without a healthy socket. */
        const val POLL_PRECISE_MS = 1_000L

        /** The aux reads (volume, the group's coordinator) at most this often, however many polls. */
        const val AUX_MIN_MS = 60_000L

        /** The migration probe's tries at least this far apart. */
        const val MIGRATE_SPACING_MS = 1_500L

        /** Design 53 §2.8: after our group changed, a STOPPED of ours with its TrackURI kept is probed this long (the controller's SETTLE_MS). */
        const val GROUP_WINDOW_MS = 8_000L
    }
}
