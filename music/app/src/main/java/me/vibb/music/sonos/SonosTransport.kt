package me.vibb.music.sonos

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/*
 * The SOAP sequences against the session's speaker, ported from palchrb/vibb `pi/sonosd.py`
 * (`Session.play`, `_hush`, `_seek_settled`, `adopt`, `verb`, the stall retry; MIT License, Copyright
 * (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26a
 * §2.5. Android-free; SonosTransportTest.
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
}

/** Elapsed time (SystemClock.elapsedRealtime on the phone: it counts deep sleep) and the waits. */
class SonosClock(val elapsedMs: () -> Long, val sleep: suspend (Long) -> Unit = { delay(it) })

/**
 * `/play`'s answer: [uri] is the BUILT one (what renderer.json stores and adopt compares); [volume]
 * the cap the speaker was brought down to before Play ([SonosTransport.capVolume]), else null.
 */
data class PlayResult(val uid: String, val uri: String, val sought: Boolean, val didl: String, val volume: Int? = null)

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
     * inside the hush ([capVolume]). Throws [UnknownUid], [UpnpFault] or [SpeakerUnreachable] - the
     * failed-play path is the caller's.
     */
    suspend fun play(body: PlayBody, cap: Int? = null): PlayResult = mutex.withLock {
        session.beginPlay(body.uid, body.kind)
        val spk = speakers.speaker(body.uid, verify = true, budgetMs = null)
        val start = body.startS
        val unhush = hush(spk, start)
        val result = try {
            val capped = capVolume(spk, cap)
            val (uri, built) = when (body.kind) {
                Kind.URL -> {
                    try {
                        // the transport plays the uri directly; a leftover queue in the Sonos app next
                        // to a playing podcast read as "the queue is wrong" (cosmetic)
                        spk.removeAllTracksFromQueue()
                    } catch (_: Exception) {
                    }
                    val built = didl(body.uri, body.title, body.artist, body.album, body.art, body.durationS)
                    spk.setAvTransportUri(body.uri, built)
                    spk.play()
                    body.uri to built
                }
                Kind.NRK_PROGRAM -> {
                    val series = body.series ?: throw SonosException("nrk_program without a series")
                    val programId = body.programId ?: throw SonosException("nrk_program without a program id")
                    val uri = nrk.programUri(spk.ip, spk.port, series, programId)
                    val built = didl(
                        uri, body.title, body.artist, body.album, body.art, body.durationS,
                        upnpClass = NrkService.UPNP_CLASS, protocol = NrkService.PROTOCOL, desc = NrkService.DESC,
                    )
                    spk.setAvTransportUri(uri, built)
                    spk.play()
                    uri to built
                }
            }
            session.played(uri)
            if (capped != null) session.ownVolume(capped)
            val sought = if (start >= 5) seekSettled(spk, start) else true
            PlayResult(body.uid, uri, sought, built, capped)
        } finally {
            unhush()
        }
        wake()
        result
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

    /** Stop, and the session is over (disarmed) until the next play or adopt. */
    suspend fun stop(ifUid: String? = null): Boolean = verb(ifUid) {
        it.stop()
        session.disarm()
        wake()
    }

    /**
     * The suspension's Stop (design 26a §6.1, QA M5): Stop, then the speaker's own queue as its
     * source ([queueUri], no metadata), so the speaker's Play button plays that (empty) queue and
     * never our episode from 0:00 - after a Stop a Sonos keeps its AVTransportURI (sonosd.py
     * 894-896). Disarmed. A refused queue uri is logged; the Stop already happened.
     */
    suspend fun park(ifUid: String? = null): Boolean = verb(ifUid) {
        it.stop()
        session.disarm()
        val uid = session.uid.orEmpty()
        try {
            it.setAvTransportUri(queueUri(uid), "")
        } catch (e: SonosException) {
            log("park: the queue uri was refused (${e.javaClass.simpleName}) - stopped only")
        }
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
     * `verb`: vibb's `if_uid` 409 is an in-process check - a verb for a uid that is no longer the
     * session's is dropped (false). Each stamps the fast-cadence window. Speaker errors propagate.
     */
    private suspend fun verb(ifUid: String?, block: (SpeakerControl) -> Unit): Boolean = mutex.withLock {
        val uid = session.uid
        if (uid == null || (ifUid != null && ifUid != uid)) return@withLock false
        session.stampVerb()
        block(speakers.speaker(uid, verify = false, budgetMs = null))
        true
    }

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
    }
}

/** The migration probe over the household: the live topology, then each candidate's own transport. */
class HouseholdProbe(private val rooms: RoomDirectory, private val soap: Soap) : MigrationProbe {
    override fun refreshTopology(): SpeakerDoc = rooms.refreshTopology()

    override fun live(player: CachedPlayer): Pair<TransportState, String>? = try {
        val spk = SoapSpeaker(soap, player.ip, player.port, "")
        val pos = spk.positionInfo()
        TransportState.parse(spk.transportInfo()) to pos.trackUri.orEmpty()
    } catch (_: SonosException) {
        null // a sleepy candidate is not the answer
    }
}
