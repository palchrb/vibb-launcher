package me.vibb.music.sonos

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import me.vibb.music.logic.QUEUE_MAX_PAGES
import me.vibb.music.logic.QUEUE_PAGE
import me.vibb.music.logic.QueueMap
import me.vibb.music.logic.QueueRead
import me.vibb.music.logic.positionNearest
import me.vibb.music.logic.queuePagesAround
import me.vibb.music.logic.redactUri
import me.vibb.music.logic.sleepTimerOurs
import me.vibb.music.logic.trackSpotifyUri

/*
 * The SOAP sequences against the session's speaker, ported from palchrb/vibb `pi/sonosd.py`
 * (`Session.play`, `_hush`, `_seek_settled`, `adopt`, `verb`, the stall retry; MIT License, Copyright
 * (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26a
 * §2.5. Android-free; SonosTransportTest. Design 54: a share link's transfer into the speaker's own queue and its
 * jumps ([SonosTransport.playShare], [SonosTransport.queuePlay]; SoCo's `add_share_link_to_queue` and
 * `play_from_queue`, MIT License, Copyright (c) Rahim Sonawalla and individual contributors), the queue's reads, the
 * park's clear and the speaker's sleep timer.
 *
 * Everything runs behind one Mutex (vibb's session lock, sonosd.py:393-394), so a stall retry's
 * four calls are atomic against a concurrent play, and a bookmark's Stop follows it in order. Never
 * on the main thread: the calls block on the network (the caller's IO actor, design 26a §0).
 */

/** What the transport commands, on top of what a poll reads. */
interface SpeakerControl : SpeakerIo {
    val ip: String
    val port: Int

    fun setVolume(volume: Int)

    fun mute(): Boolean

    fun setMute(mute: Boolean)

    fun removeAllTracksFromQueue()

    fun setAvTransportUri(uri: String, metadata: String)

    fun play()

    fun pause()

    fun stop()

    /** REL_TIME to [target] (`H:MM:SS`). */
    fun seek(target: String)

    /**
     * Join [coordinator]'s group (soco `SoCo.join`, design 53 §0.2): `SetAVTransportURI` to
     * [rinconUri] with empty metadata on THIS room; a stream it coordinated stops.
     */
    fun joinGroup(coordinator: String)

    /**
     * Leave the group (soco `SoCo.unjoin`): `BecomeCoordinatorOfStandaloneGroup` on THIS room; when it
     * coordinated, Sonos promotes a member and the stream moves there (design 26a §4.8's follow).
     */
    fun becomeStandalone()

    // -- design 54 §0.2: the speaker's own queue, as SoCo drives it --

    /** `SetPlayMode(NewPlayMode)`: `NORMAL`, `REPEAT_ALL`, `REPEAT_ONE`, `SHUFFLE_NOREPEAT`, ... (SoCo's `PLAY_MODES`). */
    fun setPlayMode(mode: String)

    /** `GetTransportSettings`'s `PlayMode`. */
    fun playMode(): String?

    /**
     * `AddURIToQueue(InstanceID 0, EnqueuedURI, EnqueuedURIMetaData, DesiredFirstTrackNumberEnqueued 0, EnqueueAsNext 0)`
     * (SoCo's `add_share_link_to_queue`): appended at the queue's end.
     */
    fun addUriToQueue(uri: String, metadata: String): AddResult

    /** `Seek(Unit TRACK_NR, Target position)`: the queue's 1-based [position] (SoCo's `play_from_queue`: index + 1). */
    fun seekTrack(position: Int)

    /** `Browse(Q:0, BrowseDirectChildren, *, start, count, "")`: [start] 0-based (SoCo's `get_queue`). */
    fun browseQueue(start: Int, count: Int): QueuePage

    /** `GetMediaInfo`. */
    fun mediaInfo(): MediaInfo

    /** `ConfigureSleepTimer(NewSleepTimerDuration)`: `H:MM:SS`, or `""` to cancel (SoCo's `set_sleep_timer`). */
    fun configureSleepTimer(duration: String)

    /** `GetRemainingSleepTimerDuration`: `H:MM:SS`, or null when none is set. */
    fun remainingSleepTimer(): String?
}

/**
 * The speaker took the link nowhere (design 54 §2.2 step 6, §2.13): both service types answered with a UPnP fault, or
 * the add put 0 tracks in the queue - "Kan ikke spilles på Sonos - spør en voksen". A network failure is
 * [SpeakerUnreachable] instead (no second try).
 */
class ShareRefused(message: String) : SonosException(message)

/**
 * A share link's start (design 54 §2.2): the queue's [first] added position and [length], the [position] it plays
 * from, whether the start item was [found] in the queue (not found: `first` at 0, no seek - N4), the service type
 * [sn] that took it, and the pages read so far ([read]; the rest comes from [SonosTransport.readQueue]). [played] false:
 * the queue was replaced but the play's second gate closed - nothing was set playing (qa-54a1-code 2: the caller keeps
 * the map of every transfer that landed, design 54 §2.3).
 */
data class ShareStart(
    val first: Int,
    val length: Int,
    val position: Int,
    val found: Boolean,
    val sn: Int,
    val read: QueueRead,
    val played: Boolean = true,
)

/** Elapsed time (SystemClock.elapsedRealtime on the phone: it counts deep sleep) and the waits. */
class SonosClock(val elapsedMs: () -> Long, val sleep: suspend (Long) -> Unit = { delay(it) })

/**
 * `/play`'s answer: [uri] is the BUILT one (what renderer.json stores and adopt compares); [volume]
 * the last volume we set on the speaker in this job - the cap it was brought down to before Play
 * ([SonosTransport.capVolume]), or a sleep ramp's leftover put back after it ([restored]) - else null.
 */
data class PlayResult(
    val uid: String,
    val uri: String,
    val sought: Boolean,
    val didl: String,
    val volume: Int? = null,
    val restored: Boolean = false,
    /** A share link's start (design 54 §2.2): where it plays and what of the queue was read; null for every other play. */
    val share: ShareStart? = null,
) {
    /** A Storytel play's uri (and the DIDL's `res`) is a signed URL: neither is printed (design 26b §0). */
    override fun toString(): String =
        "PlayResult(uid=$uid, uri=${redactUri(uri)}, sought=$sought, didl=${didl.length} chars, volume=$volume, restored=$restored, share=$share)"
}

class SonosTransport(
    val session: SonosSession,
    private val speakers: SpeakerResolver,
    private val nrk: NrkService,
    private val clock: SonosClock,
    /** Re-poll now (the cached snapshot still says the old state). */
    private val wake: () -> Unit = {},
    private val log: (String) -> Unit = {},
) {
    private val mutex = Mutex()

    /**
     * `play`: uid and kind before the SOAP, the uri only after it succeeded; [hush] across the
     * Play -> Seek window; `url` clears the queue (cosmetic, errors ignored), sets the uri with its
     * DIDL and plays; `nrk_program` builds the service uri and DIDL. Then armed, and a start of 5 s
     * or more seeks once the transport plays ([seekSettled]; false = playing from the top). With a
     * [cap] (a session's start, design 26a §5.3) the speaker's volume is brought down to it first,
     * inside the hush ([capVolume]), and so is every other room of its group ([members], the row's
     * uids; each its own RenderingControl, best effort within [MEMBER_CAP_BUDGET_MS]: a member that
     * doesn't answer never holds the play up). [gate] is asked right before `SetAVTransportURI` (the
     * play job's token, design 26b §5.1, qa-26b-step4b-code L1-L2): false ends the play there with
     * nothing sent - null (the mute put back; a cap already set stays, which is harmless). Throws
     * [UnknownUid], [UpnpFault] or [SpeakerUnreachable] - the failed-play path is the caller's.
     */
    suspend fun play(body: PlayBody, cap: Int? = null, members: List<String> = emptyList(), gate: () -> Boolean = { true }): PlayResult? = mutex.withLock {
        // design 54: a share link is the speaker's own queue - its own sequence; refused before the session or a speaker is touched
        if (body.kind == Kind.SPOTIFY_SHARELINK) throw SonosException("spotify_sharelink plays through playShare")
        session.beginPlay(body.uid, body.kind)
        val spk = speakers.speaker(body.uid, verify = true, budgetMs = null)
        HandOverTiming.step("the speaker resolved (its description read)")
        val start = body.startS
        val unhush = hush(spk, start)
        val result = try {
            val capped = capRow(spk, body.uid, cap, members)
            if (start >= 5 || cap != null) HandOverTiming.step("hush and cap done")
            val (uri, built) = when (body.kind) {
                Kind.URL -> {
                    try {
                        // the transport plays the uri directly; a leftover queue in the Sonos app next
                        // to a playing podcast read as "the queue is wrong" (cosmetic)
                        spk.removeAllTracksFromQueue()
                    } catch (_: Exception) {
                    }
                    HandOverTiming.step("RemoveAllTracksFromQueue answered")
                    val built = builtDidl(body, body.uri)
                    if (!gate()) return@withLock null
                    spk.setAvTransportUri(body.uri, built)
                    HandOverTiming.step("SetAVTransportURI answered")
                    spk.play()
                    HandOverTiming.step("Play answered")
                    body.uri to built
                }
                Kind.NRK_PROGRAM -> {
                    val series = body.series ?: throw SonosException("nrk_program without a series")
                    val programId = body.programId ?: throw SonosException("nrk_program without a program id")
                    val uri = nrk.programUri(spk.ip, spk.port, series, programId)
                    val built = builtDidl(body, uri)
                    if (!gate()) return@withLock null
                    spk.setAvTransportUri(uri, built)
                    HandOverTiming.step("SetAVTransportURI answered")
                    spk.play()
                    HandOverTiming.step("Play answered")
                    uri to built
                }
                Kind.SPOTIFY_SHARELINK -> throw SonosException("spotify_sharelink plays through playShare") // refused above
            }
            session.played(uri, body.cadenceHintS)
            if (capped != null) session.ownVolume(capped)
            val sought = if (start >= 5) seekSettled(spk, start).also { HandOverTiming.step("the settled seek done (sought: $it)") } else true
            PlayResult(body.uid, uri, sought, built, capped)
        } finally {
            unhush()
        }
        wake()
        HandOverTiming.step("the play job done, a poll woken")
        result
    }

    /**
     * The cap of a session's start (design 26a §5.3) on the coordinator and every other room of its row ([members], each
     * its own RenderingControl within [MEMBER_CAP_BUDGET_MS], best effort). The coordinator's volume set, or null.
     */
    private fun capRow(spk: SpeakerControl, uid: String, cap: Int?, members: List<String>): Int? {
        val capped = capVolume(spk, cap)
        if (cap != null) {
            for (m in members.distinct()) {
                if (m == uid) continue
                try {
                    capVolume(speakers.speaker(m, verify = false, budgetMs = MEMBER_CAP_BUDGET_MS), cap)
                } catch (e: Exception) {
                    log("volume cap: a grouped room isn't known (${e.javaClass.simpleName}) - playing anyway")
                }
            }
        }
        return capped
    }

    /**
     * Design 54 §2.2, vibb's `/play` for `spotify_sharelink` (sonosd.py `Session.play`, SoCo's ShareLink): the whole link
     * into the speaker's own queue, then a positional start - all inside the hush. [body] is a share body: `uri` the
     * canonical target, `trackIndex` the start item's index in the listing's own order, `listed` its track uris. The
     * order: the cap (a START: [cap] given, every room of the row), [gate], `SetPlayMode NORMAL` (errors ignored, vibb's
     * `try`), `RemoveAllTracksFromQueue`, `AddURIToQueue` (2311, then 3079 only after a UPnP fault - [ShareRefused] when
     * both refuse or 0 tracks came in, [SpeakerUnreachable] at once on a network failure, both before any Seek), the
     * start's page of the queue ([findInQueue]), [gate] again, `play_from_queue` (the queue as the source, `Seek
     * TRACK_NR`, `Play`), the settled seek for a start of 5 s or more at a found position (not found: the first added
     * position at 0, no seek - N4), then the speaker's sleep timer ([sleepTimer] `H:MM:SS`; null clears only our own,
     * §2.9 item 4). The rest of the queue is [readQueue]'s, its own actor job. Logs carry kinds, counts and positions
     * only (§2.14).
     */
    suspend fun playShare(body: PlayBody, cap: Int? = null, members: List<String> = emptyList(), sleepTimer: String? = null, gate: () -> Boolean = { true }): PlayResult? = mutex.withLock {
        val link = ShareLink.of(body.uri) ?: throw ShareRefused("not a share link (${body.kind})")
        session.beginPlay(body.uid, Kind.SPOTIFY_SHARELINK)
        val spk = speakers.speaker(body.uid, verify = true, budgetMs = null)
        HandOverTiming.step("the speaker resolved (its description read)")
        val unhush = hush(spk, body.startS)
        val result = try {
            val capped = capRow(spk, body.uid, cap, members)
            if (body.startS >= 5 || cap != null) HandOverTiming.step("hush and cap done")
            if (!gate()) return@withLock null
            try {
                // vibb: "NORMAL kills family shuffle/repeat leftovers AND makes the queue's play order equal its index order"
                spk.setPlayMode(PLAY_MODE_NORMAL)
            } catch (_: Exception) {
            }
            HandOverTiming.step("SetPlayMode NORMAL answered")
            spk.removeAllTracksFromQueue()
            HandOverTiming.step("RemoveAllTracksFromQueue answered")
            val (sn, added) = addShare(spk, link)
            if (added.added == 0 || added.newLength == 0) throw ShareRefused("the link added no tracks")
            // "never assume the queue starts at 1, even after clear_queue"; unparsable -> 1
            val first = added.firstTrack ?: 1
            val length = added.newLength ?: spk.queueMeta().childCount ?: (first - 1 + (added.added ?: 0))
            if (length < first) throw ShareRefused("the link added no tracks")
            log("share: ${link.type.wire} added ${added.added ?: "?"} (first $first, queue $length) by service $sn")
            HandOverTiming.step("the ShareLink request answered: ${added.added ?: "?"} added (first $first, queue $length) by service $sn")
            val idx = body.trackIndex
            val listed = body.listed.orEmpty()
            var read = QueueRead(length, emptyMap(), null)
            var position: Int? = null
            if (idx != null && idx in listed.indices) {
                val (p, r) = findInQueue(spk, listed, idx, first + idx, read)
                position = p
                read = r
                HandOverTiming.step("the start item's page read (Browse): position ${p ?: "not found"}")
            }
            val start = position ?: first
            log(if (position != null) "share: the start item at queue position $start" else "share: the start item isn't in the queue - position $start, no seek")
            // the queue is the target's from here on, whatever the gate says (qa-54a1-code 2): the session knows it, so a
            // Play pressed on the speaker classifies as ours, and the caller gets the map of a transfer that landed
            session.shareSet(link.canonical, listed)
            session.shareRead(read)
            if (!gate()) {
                log("share: the queue was replaced, the play went no further (gate)")
                return@withLock PlayResult(body.uid, queueUri(body.uid), true, link.metadata(sn), capped, share = ShareStart(first, length, start, position != null, sn, read, played = false))
            }
            playFromQueue(spk, body.uid, start)
            session.played(queueUri(body.uid), body.cadenceHintS)
            if (capped != null) session.ownVolume(capped)
            val sought = if (position != null && body.startS >= 5) seekSettled(spk, body.startS).also { HandOverTiming.step("the settled seek done (sought: $it)") } else true
            applySleepTimer(spk, body.uid, sleepTimer)
            HandOverTiming.step("the speaker's sleep timer set or cleared")
            PlayResult(body.uid, queueUri(body.uid), sought, link.metadata(sn), capped, share = ShareStart(first, length, start, position != null, sn, read))
        } finally {
            unhush()
        }
        wake()
        HandOverTiming.step("the play job done, a poll woken")
        result
    }

    /** SoCo's service loop: 2311, then 3079 only after a UPnP fault; a network failure ends it at once (design 54 §0.2). */
    private fun addShare(spk: SpeakerControl, link: ShareLink): Pair<Int, AddResult> {
        var fault: UpnpFault? = null
        for (sn in SHARE_SERVICES) {
            try {
                HandOverTiming.step("the ShareLink request sent (AddURIToQueue, service $sn)")
                return sn to spk.addUriToQueue(link.enqueuedUri, link.metadata(sn))
            } catch (e: UpnpFault) {
                fault = e
                log("share: service $sn refused the ${link.type.wire} (UPnP ${e.code ?: "?"})")
            }
        }
        throw ShareRefused("no service took the link (UPnP ${fault?.code ?: "?"})")
    }

    /** SoCo's `play_from_queue` (1-based here): the queue as the source, `Seek TRACK_NR`, `Play`. */
    private fun playFromQueue(spk: SpeakerControl, uid: String, position: Int) {
        spk.setAvTransportUri(queueUri(uid), "")
        HandOverTiming.step("SetAVTransportURI (the queue) answered")
        spk.seekTrack(position)
        HandOverTiming.step("Seek TRACK_NR $position answered")
        spk.play()
        HandOverTiming.step("Play answered")
    }

    /**
     * The start's position (design 54 §2.2 step 7): our item [index] of [listed] in the queue - the guess [near]'s page of
     * [QUEUE_PAGE] first, then the others outward, at most [QUEUE_MAX_PAGES]. Its slot as the map counts it (the k-th
     * occurrence for our k-th, `QueueMap`) once every position before that slot is read - always for a queue of one page,
     * so a start and the map never disagree on a duplicate (qa-54a1-code 5); a partial read falls back to the occurrence
     * nearest the guess. A complete read without a slot is no hit. The pages read go into the returned read; a Browse that
     * fails ends the search with no hit (the start plays the first added position).
     */
    internal fun findInQueue(spk: SpeakerControl, listed: List<String>, index: Int, near: Int, from: QueueRead): Pair<Int?, QueueRead> {
        var read = from
        val uri = listed[index]
        try {
            for (start in queuePagesAround(near, read.length)) {
                read = readRange(spk, read, start, start + QUEUE_PAGE)
                val slot = QueueMap(listed, read).slotOf(index)
                if (slot != null && (1 until slot).all { it in read.tracks }) return slot to read
                if (read.complete) return null to read
                positionNearest(read, uri, near)?.let { return it to read }
            }
        } catch (e: SonosException) {
            log("share: the queue couldn't be read (${e.javaClass.simpleName})")
        }
        return null to read
    }

    /**
     * One Browse at 0-based [start] for [count] items into [read]: its items decoded (vibb's `track_spotify_uri`), its
     * `TotalMatches` the length. Also how many came back (a speaker may answer fewer than asked, `NumberReturned`).
     */
    private fun readPage(spk: SpeakerControl, read: QueueRead, start: Int, count: Int = QUEUE_PAGE): Pair<QueueRead, Int> {
        val page = spk.browseQueue(start, count)
        val sized = page.total?.takeIf { it >= 0 }?.let { read.copy(length = minOf(it, QUEUE_PAGE * QUEUE_MAX_PAGES)) } ?: read
        return sized.withPage(start + 1, page.res.map(::trackSpotifyUri), page.updateId ?: read.updateId) to page.res.size
    }

    /** The 0-based range [from] until [until] of the queue, read until covered - a short page asks again from its end (qa-54a1-code 4). */
    private fun readRange(spk: SpeakerControl, from0: QueueRead, from: Int, until: Int): QueueRead {
        var read = from0
        while (true) {
            val missing = (from + 1..minOf(until, read.length)).firstOrNull { it !in read.tracks } ?: return read
            val (next, got) = readPage(spk, read, missing - 1, until - (missing - 1))
            read = next
            if (got == 0) return read
        }
    }

    /**
     * Every position of the queue [from] lacks (design 54 §2.3), at most [QUEUE_PAGE] x [QUEUE_MAX_PAGES]; a short page
     * asks again from where it ended (qa-54a1-code 4). A page whose `UpdateID` differs from what was read before means the
     * queue changed under the read: it starts over once, from nothing; a second change returns the read without a
     * baseline (`updateId` null: `versionCheck` says read again - qa-54a1-code 3).
     */
    private fun readRest(spk: SpeakerControl, from: QueueRead): QueueRead {
        var read = from
        var restarted = false
        while (true) {
            val missing = (1..minOf(read.length, QUEUE_PAGE * QUEUE_MAX_PAGES)).firstOrNull { it !in read.tracks } ?: return read
            val before = read.updateId
            val (next, got) = readPage(spk, read, missing - 1)
            read = next
            if (before != null && read.updateId != null && read.updateId != before) {
                if (restarted) {
                    log("share: the queue changed again while it was read - no baseline")
                    return read.copy(updateId = null)
                }
                restarted = true
                log("share: the queue changed while it was read - reading it again")
                read = QueueRead(read.length, emptyMap(), null)
                continue
            }
            if (got == 0) return read
        }
    }

    /**
     * Design 54 §2.2 step 12: the rest of a transfer's queue, as its own actor job outside the hush - [partial] (the
     * start's [ShareStart.read]) completed. The session's map knowledge follows only while [ifUid] is its share session.
     * Null when [ifUid] is no longer the session's; speaker errors propagate.
     */
    suspend fun readQueue(ifUid: String, partial: QueueRead): QueueRead? = mutex.withLock {
        val uid = session.uid
        if (uid == null || uid != ifUid || session.kind != Kind.SPOTIFY_SHARELINK) return@withLock null
        val read = readRest(speakers.speaker(uid, verify = false, budgetMs = null), partial)
        session.shareRead(read)
        log("share: the queue read - ${read.tracks.size} of ${read.length} positions")
        read
    }

    /**
     * Design 54 §2.9: adopt's read (reads only, zero transport commands) - the queue's container (its length and
     * `UpdateID`) and every page. It becomes the session's baseline only through [shareBaseline], when the adopt kept the
     * session. Null when [ifUid] is no longer the session's; speaker errors propagate.
     */
    suspend fun learnQueue(ifUid: String): QueueRead? = mutex.withLock {
        val uid = session.uid
        if (uid == null || uid != ifUid) return@withLock null
        val spk = speakers.speaker(uid, verify = false, budgetMs = null)
        val meta = spk.queueMeta()
        // no childCount: one page, whose TotalMatches gives the length
        readRest(spk, QueueRead(meta.childCount ?: 1, emptyMap(), meta.updateId))
    }

    /** The session's share baseline after an adopt kept it (design 54 §2.9, N3): the target, our listing and the read. */
    suspend fun shareBaseline(target: String, listed: List<String>, read: QueueRead) = mutex.withLock {
        session.shareSet(target, listed)
        session.shareRead(read)
    }

    /**
     * vibb's `queue_play {index, start_s}` (design 54 §2.4; `sonos_hush.py` 5): a jump within the speaker's queue -
     * hushed, the cap on a START only ([cap] given), [gate], `play_from_queue`([position]), the settled seek for 5 s or
     * more, unhushed; no clear, no add. Null when [ifUid] is no longer the share session's or [gate] said no (nothing
     * sent). [hintS] is the target item's listed duration (the tail cadence's, §2.8).
     */
    suspend fun queuePlay(
        position: Int,
        startS: Double,
        ifUid: String,
        cap: Int? = null,
        members: List<String> = emptyList(),
        hintS: Double? = null,
        gate: () -> Boolean = { true },
    ): PlayResult? = mutex.withLock {
        val uid = session.uid
        if (uid == null || uid != ifUid || session.kind != Kind.SPOTIFY_SHARELINK) return@withLock null
        val spk = speakers.speaker(uid, verify = true, budgetMs = null)
        val unhush = hush(spk, startS)
        val result = try {
            val capped = capRow(spk, uid, cap, members)
            if (!gate()) return@withLock null
            playFromQueue(spk, uid, position)
            session.jumped(hintS)
            if (capped != null) session.ownVolume(capped)
            val sought = if (startS >= 5) seekSettled(spk, startS) else true
            PlayResult(uid, queueUri(uid), sought, "", capped)
        } finally {
            unhush()
        }
        wake()
        result
    }

    /** `SetPlayMode` on the session's speaker (design 54 §2.7, §2.12); false when [ifUid] is no longer the session's. */
    suspend fun setPlayMode(mode: String, ifUid: String? = null): Boolean = verb(ifUid) {
        it.setPlayMode(mode)
        wake()
    }

    /** `GetTransportSettings`'s play mode (design 54 §2.6: the mode read before a correction without a socket). A read. */
    suspend fun playMode(ifUid: String? = null): String? = mutex.withLock {
        val uid = session.uid
        if (uid == null || (ifUid != null && ifUid != uid)) return@withLock null
        speakers.speaker(uid, verify = false, budgetMs = null).playMode()
    }

    /**
     * The speaker's sleep timer (design 54 §2.9 item 4) at a boundary, a follow or a restriction change: [value]
     * `H:MM:SS` sets ours, null clears ours (only while it still is). False when [ifUid] is no longer the session's.
     */
    suspend fun sleepTimer(value: String?, ifUid: String? = null): Boolean = verb(ifUid) { applySleepTimer(it, session.uid.orEmpty(), value) }

    /** Set ([value]) or clear our own (null) sleep timer on [spk]; errors are logged, never a failed play. */
    private fun applySleepTimer(spk: SpeakerControl, uid: String, value: String?) {
        if (value == null) {
            clearOwnSleepTimer(spk, uid)
            return
        }
        try {
            spk.configureSleepTimer(value)
            session.ownSleepTimer(uid, ((hmsToS(value) ?: 0.0) * 1000).toLong())
        } catch (e: SonosException) {
            log("sleep timer not set (${e.javaClass.simpleName})")
        }
    }

    /**
     * Our own sleep timer off (design 54 §2.9 item 4): only when one of ours was set on [uid] and the speaker's remaining
     * time is still within 30 s of what ours has left - one set in the Sonos app afterwards is theirs and stays.
     */
    private fun clearOwnSleepTimer(spk: SpeakerControl, uid: String) {
        val own = session.ownSleepLeftMs(uid) ?: return
        session.ownSleepTimer(uid, null)
        try {
            if (sleepTimerOurs(hmsToS(spk.remainingSleepTimer()), own)) spk.configureSleepTimer("")
        } catch (e: SonosException) {
            log("sleep timer not cleared (${e.javaClass.simpleName})")
        }
    }

    /**
     * `_hush`: a resume can't seek before the transport plays, so about a second always plays from
     * 0:00 first - muted. Returns the restore, which puts the OLD mute back (a deliberately muted
     * speaker stays muted). Below 5 s nothing is touched; both directions are best-effort.
     */
    internal fun hush(spk: SpeakerControl, startS: Double): () -> Unit {
        if (startS < 5) return {}
        val was = try {
            val m = spk.mute()
            spk.setMute(true)
            m
        } catch (_: Exception) {
            return {}
        }
        return {
            try {
                spk.setMute(was)
            } catch (_: Exception) {
            }
        }
    }

    /**
     * The parent's cap at a session's start (design 26a §5.3): a speaker left louder than [cap]
     * (1..99 %; anything else is no cap) - by the last listener, by the Sonos app - is set to the cap
     * once, before Play, so our stream never starts above it; inside the hush when there is one, so
     * the step isn't heard on what was playing. A speaker at or below the cap is left alone, and
     * during the session a louder volume set elsewhere is never pulled down (vibb never fights an
     * external change). Best effort: a speaker that won't say or set its volume still plays (logged).
     * Returns the volume set, or null.
     */
    internal fun capVolume(spk: SpeakerControl, cap: Int?): Int? {
        if (cap == null || cap !in 1..99) return null
        return try {
            val was = spk.volume()
            if (was <= cap) {
                null
            } else {
                spk.setVolume(cap)
                log("volume $was above the cap - set to $cap before Play")
                cap
            }
        } catch (e: Exception) {
            log("volume cap not checked (${e.javaClass.simpleName}) - playing anyway")
            null
        }
    }

    /**
     * `_seek_settled`: wait (0.3 s steps, up to [timeoutMs]) for PLAYING - a Seek against a STOPPED
     * transport is UPnP 701 - then up to three Seeks, retrying only faults 701/710/711 with 0.4 s
     * between. False is a degrade ("playing from the top"), never an error.
     */
    internal suspend fun seekSettled(spk: SpeakerControl, startS: Double, timeoutMs: Long = 8_000): Boolean {
        val deadline = clock.elapsedMs() + timeoutMs
        while (clock.elapsedMs() < deadline) {
            val playing = try {
                TransportState.parse(spk.transportInfo()) == TransportState.PLAYING
            } catch (_: Exception) {
                false
            }
            if (playing) break
            clock.sleep(300)
        }
        repeat(3) {
            try {
                spk.seek(hms(startS))
                return true
            } catch (e: UpnpFault) {
                if (e.code !in RETRY_FAULTS) return false
            } catch (_: Exception) {
                return false
            }
            clock.sleep(400)
        }
        log("seek to ${hms(startS)} refused - playing from the top")
        return false
    }

    /** `adopt`: re-attach to a session already playing (a restart) - zero transport commands. */
    suspend fun adopt(uid: String, kind: Kind, uri: String) = mutex.withLock {
        session.adopt(uid, kind, uri)
        wake()
    }

    /**
     * Adopt at start (design 26a §4.7) and the suspension's look (§6.1): [adopt]'s bookkeeping (zero
     * transport commands), then ONE classify of [uid] within [budgetMs], published. [keep] decides on
     * it and names the uri the session goes on with (the stored built uri, or the speaker's own when
     * none was stored yet - then the published snapshot is ours): kept, the poller is woken; null, the
     * session is disarmed again. Null when the speaker didn't answer in time (disarmed too). Without
     * [aux] the look reads no group and no volume (the bedtime look, §6.1).
     */
    suspend fun adoptLive(uid: String, kind: Kind, uri: String, budgetMs: Long, aux: Boolean = true, keep: (Fields) -> String?): Fields? = mutex.withLock {
        session.adopt(uid, kind, uri)
        val fields = try {
            session.classify(speakers.speaker(uid, verify = false, budgetMs = budgetMs), aux)
        } catch (e: SonosException) {
            log("adopt: no answer from the room (${e.javaClass.simpleName})")
            session.disarm()
            return@withLock null
        }
        session.publish(fields)
        val kept = keep(session.snapshot)
        if (kept == null) {
            session.disarm()
        } else {
            if (kept != uri) {
                // the uri renderer.json didn't hold yet: the session's from now on
                session.adopt(uid, kindOfUri(kept), kept)
                session.publish(fields.copy(ours = true, foreignUri = null))
            }
            wake()
        }
        session.snapshot
    }

    suspend fun pause(ifUid: String? = null): Boolean = verb(ifUid) {
        it.pause()
        wake()
    }

    suspend fun resume(ifUid: String? = null): Boolean = verb(ifUid) {
        it.play()
        wake()
    }

    /**
     * Stop, and the session is over (disarmed) until the next play or adopt. A normal Stop leaves the speaker's queue
     * (vibb); our own sleep timer goes with it (design 54 §2.9 item 4, only while it is still ours).
     */
    suspend fun stop(ifUid: String? = null): Boolean = verb(ifUid) {
        it.stop()
        session.disarm()
        clearOwnSleepTimer(it, session.uid.orEmpty())
        wake()
    }

    /**
     * The suspension's Stop (design 26a §6.1, QA M5): Stop, then the speaker's own queue as its source ([queueUri], no
     * metadata), so the speaker's Play button plays that queue and never our episode from 0:00 - after a Stop a Sonos keeps
     * its AVTransportURI (sonosd.py 894-896). For a share link the queue is then cleared too (design 54 §2.9: its Play
     * button would otherwise play the playlist during a time rule) - after the queue uri, so a slow clear never costs the
     * one 26a M5 needs, and only for that kind: a queue the household built is kept at an NRK or podcast session's park
     * (qa-54a1-code 8). Disarmed. A refused queue uri or clear is logged; the Stop already happened. Our own sleep timer
     * last (only while it is still ours). [budgetMs]: every SOAP within it (the bedtime path's time left, §6.1), null for
     * the client's timeouts.
     */
    suspend fun park(ifUid: String? = null, budgetMs: Long? = null): Boolean = verb(ifUid, budgetMs) {
        it.stop()
        session.disarm()
        val uid = session.uid.orEmpty()
        try {
            it.setAvTransportUri(queueUri(uid), "")
        } catch (e: SonosException) {
            log("park: the queue uri was refused (${e.javaClass.simpleName}) - stopped only")
        }
        if (session.kind == Kind.SPOTIFY_SHARELINK) {
            try {
                it.removeAllTracksFromQueue()
            } catch (e: SonosException) {
                log("park: the queue wasn't cleared (${e.javaClass.simpleName})")
            }
        }
        clearOwnSleepTimer(it, uid)
        wake()
    }

    suspend fun seek(seconds: Double, ifUid: String? = null): Boolean = verb(ifUid) {
        it.seek(hms(seconds))
        wake()
    }

    /** Clamped 0..100 and written straight into the snapshot (no re-poll needed). */
    suspend fun volume(volume: Int, ifUid: String? = null): Boolean = verb(ifUid) {
        val v = volume.coerceIn(0, 100)
        it.setVolume(v)
        session.ownVolume(v)
    }

    /**
     * Design 53-d (qa-53d-code 2): one GetVolume of the session's speaker, outside the aux - a key press's or the sleep
     * fade's base when no set of ours is one and the last read is old. Null when [ifUid] is no longer the session's;
     * speaker errors propagate. A read, not a verb: no fast-cadence window.
     */
    suspend fun readVolume(ifUid: String? = null): Int? = mutex.withLock {
        val uid = session.uid
        if (uid == null || (ifUid != null && ifUid != uid)) return@withLock null
        val v = speakers.speaker(uid, verify = false, budgetMs = null).volume()
        session.volumeRead(v)
        v
    }

    /**
     * `verb`: vibb's `if_uid` 409 is an in-process check - a verb for a uid that is no longer the
     * session's is dropped (false). Each stamps the fast-cadence window. Speaker errors propagate.
     */
    private suspend fun verb(ifUid: String?, budgetMs: Long? = null, block: (SpeakerControl) -> Unit): Boolean = mutex.withLock {
        val uid = session.uid
        if (uid == null || (ifUid != null && ifUid != uid)) return@withLock false
        session.stampVerb()
        block(speakers.speaker(uid, verify = false, budgetMs = budgetMs))
        true
    }

    /**
     * "−" on our own coordinator (design 53 §2.6): `BecomeCoordinatorOfStandaloneGroup` on [ifUid], in our actor -
     * ordered against our plays. Sonos promotes a remaining member and moves the stream there; the controller's
     * follow takes it (the poll burst, the probe's candidate). False when [ifUid] is no longer the session's.
     */
    suspend fun leave(ifUid: String): Boolean = verb(ifUid, GroupVerbs.VERB_BUDGET_MS) {
        it.becomeStandalone()
        wake()
    }

    /**
     * A grouped room that joined from outside (design 53 §2.9): brought down to [cap] once when louder - each its own
     * RenderingControl within [MEMBER_CAP_BUDGET_MS], best effort, through our actor. The volumes set, by uid.
     */
    suspend fun capMembers(uids: List<String>, cap: Int): Map<String, Int> = mutex.withLock {
        val set = mutableMapOf<String, Int>()
        for (m in uids.distinct()) {
            try {
                capVolume(speakers.speaker(m, verify = false, budgetMs = MEMBER_CAP_BUDGET_MS), cap)?.let { set[m] = it }
            } catch (e: Exception) {
                log("volume cap: a joining room isn't known (${e.javaClass.simpleName})")
            }
        }
        set
    }

    /** The migration probe's candidate (design 53 §2.8: a `MOVED`'s player, a `groups` event's, our "−"'s first member): asked first. */
    suspend fun hint(uid: String) = mutex.withLock { session.hint(uid) }

    /** Our group changed (design 53 §2.8): for a while a STOPPED of ours with its TrackURI kept is probed too. */
    suspend fun groupChanged(at: Long) = mutex.withLock { session.groupChanged(at) }

    /**
     * `/state?live=1` (sonosd.py 1021-1039): one classify within [budgetMs] for the bookmark at the
     * exact second (a switch home, a hand-off); published like a poll. Null when the session isn't
     * armed ([armedOnly]; the suspension's look also reads a stood-down session's room, §6.1) or the
     * speaker didn't answer in time - the caller keeps the snapshot it has.
     */
    suspend fun liveRefresh(budgetMs: Long, armedOnly: Boolean = true): Fields? = mutex.withLock {
        val uid = session.uid
        if ((armedOnly && !session.armed) || uid == null) return@withLock null
        try {
            val fields = session.classify(speakers.speaker(uid, verify = false, budgetMs = budgetMs))
            session.publish(fields)
            session.snapshot
        } catch (e: SonosException) {
            log("live refresh failed (${e.javaClass.simpleName}) - keeping the last snapshot")
            null
        }
    }

    /**
     * One poll (`poll_loop`'s body) and, when the stall bookkeeping asks for it, the one in-place
     * retry: Stop, Play, and a settled seek back when the frozen position was 5 s or more.
     */
    suspend fun poll(probe: MigrationProbe?): Tick = mutex.withLock {
        val tick = session.tick({ speakers.speaker(session.uid ?: throw UnknownUid("none"), verify = false, budgetMs = null) }, probe)
        val retryAt = (tick as? Tick.Next)?.stallRetryAtS
        if (retryAt != null) {
            try {
                val spk = speakers.speaker(session.uid ?: throw UnknownUid("none"), verify = false, budgetMs = null)
                spk.stop()
                spk.play()
                if (retryAt >= 5) seekSettled(spk, retryAt)
            } catch (e: SonosException) {
                log("stall retry failed (${e.javaClass.simpleName})")
            }
        }
        tick
    }

    companion object {
        /** "Transition not available", "seek mode not supported", "illegal seek target" - retried. */
        val RETRY_FAULTS = setOf(701, 710, 711)

        /** A grouped room's cap at a start (§5.3) and a joiner's (design 53 §2.9): its GetVolume and SetVolume together. */
        const val MEMBER_CAP_BUDGET_MS = 1_500L

        /** vibb's play mode for a share link (design 54 §2.7): the queue never wraps or repeats by itself. */
        const val PLAY_MODE_NORMAL = "NORMAL"
    }
}

/** The migration probe over the household: the live topology, then each candidate's own transport. */
class HouseholdProbe(private val rooms: RoomDirectory, private val soap: Soap) : MigrationProbe {
    override fun refreshTopology(): SpeakerDoc = rooms.refreshTopology()

    override fun live(player: CachedPlayer): ProbeLive? = try {
        val spk = SoapSpeaker(soap, player.ip, player.port, "")
        val pos = spk.positionInfo()
        ProbeLive(TransportState.parse(spk.transportInfo()), pos.trackUri.orEmpty(), hmsToS(pos.relTime))
    } catch (_: SonosException) {
        null // a sleepy candidate is not the answer
    }
}
