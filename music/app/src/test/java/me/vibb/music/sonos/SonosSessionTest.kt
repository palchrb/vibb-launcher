package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import me.vibb.music.sonos.SonosFakes.position
import me.vibb.music.sonos.SonosFakes.transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The session machine (design 26a §2.4), porting palchrb/vibb (MIT, Copyright (c) 2026 palchrb)
 * `tests/sonos_grouped.py` check 1 (the sidecar half; checks 2-3 are the controller's, step 3),
 * `tests/sonos_migrate_probe.py` checks 1, 2, 2b, 2c, 4 and 5 (check 3 is `spotify_sharelink`,
 * phase 3), and `tests/sonos_contract.py`'s canonical snapshots as expected [Fields]. The rest pins
 * `sonosd.py`'s poll loop: stall, disarm, cadence, freshness, a failed poll.
 */
class SonosSessionTest {
    private val clock = FakeClock()
    private val uri = "https://cdn.example/signed/ep12.mp3?tok=abc"
    private val kid = "RINCON_KID"
    private val logs = mutableListOf<String>()

    private fun session(kind: Kind = Kind.URL, u: String = uri): SonosSession =
        SonosSession({ clock.now }, { logs.add(it) }).apply { adopt(kid, kind, u) }

    private fun speaker(trackUri: String, tr: String = "PLAYING", rel: String = "0:07:11", dur: String = "0:24:13", meta: String = "<x/>") =
        FakeSpeaker(kid).apply {
            position = PositionInfo("1", trackUri, rel, dur, meta)
            transport = tr
        }

    /** One ours+live poll: the aux ran (11 polls until the next), the probe machinery is armed. */
    private fun warm(s: SonosSession) {
        s.classify(speaker(uri))
    }

    private fun fields(tr: TransportState, trackUri: String) =
        Fields(armed = true, uid = kid, kind = Kind.URL, seq = 0, measuredAt = clock.now, reachable = true, transport = tr, uri = trackUri)

    // --- sonos_grouped.py check 1: instant grouped-away detection ------------------------------

    @Test
    fun `grouped 1 - an x-rincon member uri is grouped away with its coordinator on the same poll`() {
        val s = session()
        warm(s)
        val f = s.classify(speaker("x-rincon:RINCON_PARENT"))
        assertFalse(f.ours)
        assertTrue("the x-rincon uri IS the signal - same poll", f.groupedAway)
        assertEquals("RINCON_PARENT", f.coordinator)
    }

    @Test
    fun `grouped 1b - x-rincon-mp3radio is a stream, not a membership`() {
        val s = session()
        warm(s)
        val f = s.classify(speaker("x-rincon-mp3radio://stream.example/radio"))
        assertFalse(f.groupedAway)
        assertNull(f.coordinator)
    }

    @Test
    fun `grouped 1c - an empty uri holds the state, a real uri clears it`() {
        val s = session()
        warm(s)
        s.classify(speaker("x-rincon:RINCON_PARENT"))
        assertTrue("an empty uri must not clear the state", s.classify(speaker("", tr = "STOPPED")).groupedAway)
        val f = s.classify(speaker(uri))
        assertFalse("our own track back on the transport clears the membership", f.groupedAway)
        assertNull(f.coordinator)
    }

    @Test
    fun `the aux - group and volume at the start and every 12 polls, the last value kept on failure`() {
        val s = session()
        val spk = speaker(uri).apply { coordinator = "RINCON_PARENT"; vol = 31 }
        val first = s.classify(spk)
        assertTrue("the topology says we're a member", first.groupedAway)
        assertEquals(31, first.volume)
        assertEquals(1, spk.log.count { it == "getgroup" })
        spk.vol = 40
        spk.failOn = mapOf("getgroup" to { SpeakerUnreachable("x") })
        // an empty uri decides nothing, so only the aux could change the membership
        spk.position = spk.position.copy(trackUri = "")
        repeat(11) {
            clock.advance(5_000) // 12 polls at the 5 s cadence: the aux's 60 s floor too (design 53 §2.4)
            s.classify(spk)
        }
        assertEquals("not again before the 12th poll", 1, spk.log.count { it == "getgroup" })
        clock.advance(5_000)
        val f = s.classify(spk)
        assertEquals("the volume refreshed", 40, f.volume)
        assertTrue("a failed group read keeps the last topology", f.groupedAway)
    }

    // --- sonos_migrate_probe.py ----------------------------------------------------------------

    private class Probe : MigrationProbe {
        var topology = 0
        val candidates = mutableMapOf<String, Pair<TransportState, String>>()

        /** The candidates' ips in the order the probe asked them (design 53 §2.8: the hinted one first). */
        val asked = mutableListOf<String>()

        override fun refreshTopology(): SpeakerDoc {
            topology++
            return SpeakerDoc(
                players = mapOf(
                    "RINCON_KID" to CachedPlayer("10.0.0.10", name = "Barnerom"),
                    "RINCON_KITCHEN" to CachedPlayer("10.0.0.11", name = "Kjøkken"),
                    "RINCON_BATH" to CachedPlayer("10.0.0.12", name = "Bad"),
                ),
                groups = listOf(
                    CachedGroup("RINCON_KITCHEN", listOf("RINCON_KITCHEN")),
                    CachedGroup("RINCON_BATH", listOf("RINCON_BATH")),
                    CachedGroup("RINCON_KID", listOf("RINCON_KID")),
                ),
            )
        }

        override fun live(player: CachedPlayer): Pair<TransportState, String>? {
            asked += player.ip
            return candidates[player.ip] ?: (TransportState.STOPPED to "")
        }
    }

    private val stoppedEmpty get() = fields(TransportState.STOPPED, "")

    @Test
    fun `d53 the hinted candidate is asked first, and right after a group change a STOPPED of ours that kept its TrackURI probes too`() {
        val s = session()
        warm(s)
        val probe = Probe()
        s.hint("RINCON_BATH")
        assertTrue("it ran, nobody carries the stream: the advance guard's probeMissed", s.maybeProbeMigration(stoppedEmpty, probe))
        assertEquals("the hinted Bad first, then Kjøkken", listOf("10.0.0.12", "10.0.0.11"), probe.asked)
        // our TrackURI kept, STOPPED: an episode's end - never probed outside the window...
        val kept = fields(TransportState.STOPPED, uri).copy(ours = true)
        clock.advance(2_000)
        assertFalse(s.maybeProbeMigration(kept, probe))
        assertEquals(1, probe.topology)
        // ... but right after our group changed (an old coordinator may keep it): probed, and the stream found
        s.groupChanged(clock.now)
        probe.candidates["10.0.0.11"] = TransportState.PLAYING to uri
        assertFalse("found: not missed", s.maybeProbeMigration(kept, probe))
        assertEquals(Moved("RINCON_KITCHEN", "Kjøkken", uri), s.streamMoved)
        // the window closes after GROUP_WINDOW_MS
        val s2 = session()
        warm(s2)
        s2.groupChanged(clock.now)
        clock.advance(SonosSession.GROUP_WINDOW_MS)
        assertFalse(s2.maybeProbeMigration(kept, probe))
        assertEquals(2, probe.topology)
    }

    @Test
    fun `migrate 1 - only the migration signature probes`() {
        val s = session()
        warm(s)
        val probe = Probe()
        s.maybeProbeMigration(fields(TransportState.STOPPED, "x-rincon:RINCON_PARENT"), probe)
        s.maybeProbeMigration(fields(TransportState.STOPPED, uri), probe)
        s.maybeProbeMigration(fields(TransportState.PLAYING, ""), probe)
        assertEquals("hijack / episode end / mid-transition must never probe", 0, probe.topology)
        clock.advance(9_999_000)
        s.maybeProbeMigration(stoppedEmpty, probe)
        assertEquals("an old session must not probe", 0, probe.topology)
    }

    @Test
    fun `migrate 2 - the url stream found on the promoted coordinator, encoding-tolerant`() {
        val s = session()
        warm(s)
        val probe = Probe()
        probe.candidates["10.0.0.11"] = TransportState.PLAYING to "https://cdn.example/signed/ep12.mp3%3Ftok%3Dabc"
        probe.candidates["10.0.0.12"] = TransportState.PLAYING to "https://elsewhere.example/x.mp3"
        s.maybeProbeMigration(stoppedEmpty, probe)
        assertEquals(Moved("RINCON_KITCHEN", "Kjøkken", uri), s.streamMoved)
        assertEquals("a found hint ends the probing", 0, s.migrateTriesLeft)
    }

    @Test
    fun `migrate 2b - the hint rides every published snapshot, carrying the session's uri`() {
        val s = session()
        warm(s)
        val probe = Probe()
        probe.candidates["10.0.0.11"] = TransportState.PAUSED_PLAYBACK to uri
        s.maybeProbeMigration(stoppedEmpty, probe)
        s.publish(stoppedEmpty)
        assertEquals("RINCON_KITCHEN", s.snapshot.streamMoved!!.uid)
        assertEquals("the hint must echo the session uri - the snapshot's own is empty", uri, s.snapshot.streamMoved!!.uri)
        s.publish(stoppedEmpty)
        assertEquals("on every snapshot until cleared", uri, s.snapshot.streamMoved!!.uri)
    }

    @Test
    fun `migrate 2c - a candidate that isn't playing or paused never qualifies`() {
        val s = session()
        warm(s)
        val probe = Probe()
        probe.candidates["10.0.0.11"] = TransportState.STOPPED to uri
        s.maybeProbeMigration(stoppedEmpty, probe)
        assertNull(s.streamMoved)
    }

    @Test
    fun `migrate 4 - exactly three attempts, an ours+live sighting re-arms and clears, adopt clears`() {
        val s = session()
        warm(s)
        val probe = Probe()
        repeat(5) {
            clock.advance(1_500) // tries 1.5 s apart (design 53 §2.4)
            s.maybeProbeMigration(stoppedEmpty, probe)
        }
        assertEquals(SonosSession.MIGRATE_TRIES, probe.topology)
        assertNull(s.streamMoved)
        assertEquals(3, logs.count { it.startsWith("migration probe ") && "/3:" in it })
        assertTrue(logs.any { "settling into lost-session" in it })

        // a hint, then an ours+live classify: re-armed and cleared
        probe.candidates["10.0.0.11"] = TransportState.PLAYING to uri
        val s2 = session()
        warm(s2)
        s2.maybeProbeMigration(stoppedEmpty, probe)
        assertTrue(s2.streamMoved != null)
        s2.classify(speaker(uri))
        assertEquals(SonosSession.MIGRATE_TRIES, s2.migrateTriesLeft)
        assertNull(s2.streamMoved)

        s2.maybeProbeMigration(stoppedEmpty, probe)
        assertTrue(s2.streamMoved != null)
        s2.adopt("RINCON_KITCHEN", Kind.URL, uri)
        assertNull("adopt clears the hint it answers", s2.streamMoved)
    }

    @Test
    fun `migrate 4b - a probe that throws is one used attempt, never an escape`() {
        val s = session()
        warm(s)
        val failing = object : MigrationProbe {
            override fun refreshTopology(): SpeakerDoc = throw SpeakerUnreachable("nobody")

            override fun live(player: CachedPlayer): Pair<TransportState, String>? = null
        }
        s.maybeProbeMigration(stoppedEmpty, failing)
        assertEquals(2, s.migrateTriesLeft)
        assertTrue(logs.any { it == "migration probe failed (SpeakerUnreachable)" })
    }

    @Test
    fun `migrate 5 - the cadence is fast only while a migration transition pends`() {
        val s = session()
        warm(s)
        clock.advance(31_000) // past the arm's fast window, inside the 90 s migration window
        val snap = fields(TransportState.STOPPED, "")
        assertEquals("attempts land at seconds, not the 60 s stopped cadence", 5_000L, s.cadenceMs(TransportState.STOPPED, snap, 0))
        assertEquals("an episode-end STOPPED (uri retained) keeps its cadence", 60_000L, s.cadenceMs(TransportState.STOPPED, fields(TransportState.STOPPED, uri), 0))
        repeat(3) {
            clock.advance(1_500)
            s.maybeProbeMigration(snap, Probe())
        }
        assertEquals(60_000L, s.cadenceMs(TransportState.STOPPED, snap, 0))
        val s2 = session()
        warm(s2)
        clock.advance(91_000)
        assertEquals("past the window", 60_000L, s2.cadenceMs(TransportState.STOPPED, snap, 0))
    }

    // --- the poll loop -------------------------------------------------------------------------

    @Test
    fun `cadence - paused 15 s, cruise 15 s, 5 s near the end or after a verb, unreachable backs off`() {
        val s = session()
        val playing = fields(TransportState.PLAYING, uri).copy(relS = 400.0, durS = 1453.0)
        assertEquals("within 30 s of the arm", 5_000L, s.cadenceMs(TransportState.PLAYING, playing, 0))
        clock.advance(30_000)
        assertEquals(15_000L, s.cadenceMs(TransportState.PLAYING, playing, 0))
        assertEquals("the tail", 5_000L, s.cadenceMs(TransportState.PLAYING, playing.copy(relS = 1408.0), 0))
        assertEquals("no duration, no tail", 15_000L, s.cadenceMs(TransportState.PLAYING, playing.copy(durS = 0.0, relS = 0.0), 0))
        s.stampVerb()
        assertEquals(5_000L, s.cadenceMs(TransportState.PLAYING, playing, 0))
        assertEquals("after a verb whatever the state (M1)", 5_000L, s.cadenceMs(TransportState.PAUSED_PLAYBACK, playing, 0))
        assertEquals(5_000L, s.cadenceMs(TransportState.STOPPED, playing, 0))
        clock.advance(30_000)
        assertEquals(15_000L, s.cadenceMs(TransportState.PAUSED_PLAYBACK, playing, 0))
        assertEquals(60_000L, s.cadenceMs(TransportState.STOPPED, playing, 0))
        assertEquals("a TRANSITIONING read polls fast (M1)", 5_000L, s.cadenceMs(TransportState.PAUSED_PLAYBACK, playing.copy(transitioning = true), 0))
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 160_000L, 300_000L, 300_000L), listOf(1, 2, 3, 5, 6, 9).map { s.cadenceMs(TransportState.UNREACHABLE, playing, it) })
    }

    @Test
    fun `d53 the 1 s display poll only while the player shows, the socket isn't healthy and ours plays fresh - freshness keeps the normal cadence`() {
        var shown = true
        var healthy = false
        val s = SonosSession({ clock.now }, playerShown = { shown }, socketHealthy = { healthy })
        s.adopt(kid, Kind.URL, uri)
        val spk = speaker(uri)
        clock.advance(31_000) // past the arm's fast window: the cruise is 15 s
        assertEquals(1_000L, (s.tick({ spk }, null) as Tick.Next).delayMs)
        assertEquals("freshness is the normal rule's cadence, never 1 s", 15_000L, s.snapshot.measuredCadenceMs)
        assertTrue("so the screen going off doesn't read stale for seconds", s.snapshot.fresh(clock.now + 20_000))
        healthy = true
        assertEquals("a healthy socket pushes every change", 15_000L, (s.tick({ spk }, null) as Tick.Next).delayMs)
        healthy = false
        shown = false
        assertEquals(15_000L, (s.tick({ spk }, null) as Tick.Next).delayMs)
        shown = true
        spk.transport = "PAUSED_PLAYBACK"
        assertEquals("paused: no", 15_000L, (s.tick({ spk }, null) as Tick.Next).delayMs)
        spk.transport = "PLAYING"
        spk.position = spk.position.copy(trackUri = "https://elsewhere.example/x.mp3")
        assertEquals("not ours: no", 15_000L, (s.tick({ spk }, null) as Tick.Next).delayMs)
    }

    @Test
    fun `d53 an uncounted poll leaves the stall count and the last position alone - the stall needs frozen polls 5 s apart`() {
        val s = session()
        val spk = speaker(uri, rel = "0:07:11")
        fun poll(rel: String): Double? {
            spk.position = spk.position.copy(relTime = rel)
            return (s.tick({ spk }, null) as Tick.Next).stallRetryAtS
        }
        clock.advance(5_000)
        val retries = mutableListOf(poll("0:07:11"))
        repeat(3) {
            // a confirm poll a second later, another second read: neither a reset nor a count
            clock.advance(1_000)
            retries += poll("0:07:12")
            clock.advance(4_000)
            retries += poll("0:07:11")
        }
        assertEquals("the fourth counted frozen poll retries", listOf(null, null, null, null, null, null, 431.0), retries)
    }

    @Test
    fun `d53 the probe's tries at least 1_5 s apart whatever woke the poll, the aux at most every 60 s, measuredAt at GetPositionInfo's return`() {
        val s = session()
        warm(s)
        val probe = Probe()
        s.maybeProbeMigration(stoppedEmpty, probe)
        clock.advance(500)
        s.maybeProbeMigration(stoppedEmpty, probe)
        assertEquals("a confirm poll 0.5 s later doesn't try", 2, s.migrateTriesLeft)
        clock.advance(1_000)
        s.maybeProbeMigration(stoppedEmpty, probe)
        assertEquals(1, s.migrateTriesLeft)

        val a = session()
        val spk = speaker(uri)
        repeat(13) {
            clock.advance(1_000) // the 1 s display poll
            a.classify(spk)
        }
        assertEquals("12 polls but 13 s: one group read", 1, spk.log.count { it == "getgroup" })
        clock.advance(60_000)
        a.classify(spk)
        assertEquals(2, spk.log.count { it == "getgroup" })

        val stamped = session()
        val slow = object : SpeakerIo by speaker(uri) {
            override fun transportInfo(): String? {
                clock.advance(400) // GetTransportInfo takes its time after GetPositionInfo returned
                return "PLAYING"
            }
        }
        val before = clock.now
        assertEquals("stamped where it measured, not after the other reads", before, stamped.classify(slow, aux = false).measuredAt)
    }

    @Test
    fun `d53 the tail cadence from the listing's hint when the speaker reads 0-00 - ours only, an adopt has none`() {
        // design 53 §2.4: the DIDL no longer carries a podcast's duration, so a speaker that reads 0:00 keeps
        // the 5 s tail polls from our play's listed duration - cadence only
        val s = SonosSession({ clock.now })
        s.beginPlay(kid, Kind.URL)
        s.played(uri, hintS = 1453.0)
        clock.advance(30_000)
        val silent = fields(TransportState.PLAYING, uri).copy(ours = true, relS = 1420.0, durS = 0.0)
        assertEquals("the hint's tail", 5_000L, s.cadenceMs(TransportState.PLAYING, silent, 0))
        assertEquals("far from the end", 15_000L, s.cadenceMs(TransportState.PLAYING, silent.copy(relS = 400.0), 0))
        assertEquals("the speaker's own duration wins", 15_000L, s.cadenceMs(TransportState.PLAYING, silent.copy(durS = 3600.0), 0))
        assertEquals("not our stream: no hint", 15_000L, s.cadenceMs(TransportState.PLAYING, silent.copy(ours = false), 0))
        s.adopt(kid, Kind.URL, uri)
        clock.advance(30_000)
        assertEquals("an adopt has no hint", 15_000L, s.cadenceMs(TransportState.PLAYING, silent, 0))
    }

    @Test
    fun `TRANSITIONING reports the last real state, or PLAYING at a fresh start`() {
        val s = session()
        assertEquals(TransportState.PLAYING, s.classify(speaker(uri, tr = "TRANSITIONING")).transport)
        s.classify(speaker(uri, tr = "PAUSED_PLAYBACK"))
        assertEquals(TransportState.PAUSED_PLAYBACK, s.classify(speaker(uri, tr = "TRANSITIONING")).transport)
    }

    @Test
    fun `stall - three frozen polls while PLAYING and ours give one retry per session`() {
        val s = session()
        val spk = speaker(uri, rel = "0:07:11")
        val retries = (1..8).map {
            clock.advance(5_000) // polls 5 s apart (design 53 §2.4)
            (s.tick({ spk }, null) as Tick.Next).stallRetryAtS
        }
        assertEquals(listOf(null, null, null, 431.0, null, null, null, null), retries)
        assertTrue(s.snapshot.retriedAt != null)
        // not ours, or not PLAYING: never
        val s2 = session()
        val paused = speaker(uri, tr = "PAUSED_PLAYBACK")
        val foreign = speaker("https://elsewhere.example/x.mp3")
        assertTrue((1..6).all { (s2.tick({ if (it % 2 == 0) paused else foreign }, null) as Tick.Next).stallRetryAtS == null })
    }

    @Test
    fun `stall - a moving position resets the count`() {
        val s = session()
        val spk = speaker(uri)
        val rels = listOf("0:07:11", "0:07:11", "0:07:11", "0:07:26", "0:07:26", "0:07:26", "0:07:26")
        val retries = rels.map { r ->
            clock.advance(5_000)
            spk.position = spk.position.copy(relTime = r)
            (s.tick({ spk }, null) as Tick.Next).stallRetryAtS
        }
        assertEquals(listOf(null, null, null, null, null, null, 446.0), retries)
    }

    @Test
    fun `disarm - neither playing nor paused for 600 s stands the session down, republished at once`() {
        val s = session()
        val stopped = speaker(uri, tr = "STOPPED", rel = "0:00:00")
        assertTrue(s.tick({ stopped }, null) is Tick.Next)
        clock.advance(599_000)
        assertTrue(s.tick({ stopped }, null) is Tick.Next)
        clock.advance(1_000)
        val seqBefore = s.snapshot.seq
        assertEquals(Tick.Disarmed, s.tick({ stopped }, null))
        assertFalse(s.armed)
        assertFalse("the snapshot says armed:false NOW", s.snapshot.armed)
        assertTrue(s.snapshot.seq > seqBefore)
        assertEquals("only a play or an adopt arms again", Tick.Idle, s.tick({ stopped }, null))
        s.adopt(kid, Kind.URL, uri)
        assertTrue(s.tick({ stopped }, null) is Tick.Next)
    }

    @Test
    fun `disarm - a live poll resets the streak, an unreachable speaker counts`() {
        val s = session()
        val stopped = speaker(uri, tr = "STOPPED")
        s.tick({ stopped }, null)
        clock.advance(500_000)
        s.tick({ speaker(uri, tr = "PAUSED_PLAYBACK") }, null)
        clock.advance(500_000)
        assertTrue("the streak restarted at the paused poll", s.tick({ stopped }, null) is Tick.Next)
        clock.advance(599_000)
        assertTrue(s.tick({ throw SpeakerUnreachable("gone") }, null) is Tick.Next)
        clock.advance(1_000)
        assertEquals(Tick.Disarmed, s.tick({ throw SpeakerUnreachable("gone") }, null))
    }

    @Test
    fun `a failed poll publishes UNREACHABLE and keeps the last measured values`() {
        val s = session()
        s.tick({ speaker(uri) }, null)
        val good = s.snapshot
        clock.advance(15_000)
        val tick = s.tick({ throw SpeakerUnreachable("timeout") }, null)
        val bad = s.snapshot
        assertFalse(bad.reachable)
        assertEquals(TransportState.UNREACHABLE, bad.transport)
        assertEquals("SpeakerUnreachable", bad.error)
        assertEquals(good.relS, bad.relS)
        assertEquals(good.uri, bad.uri)
        assertTrue(bad.ours)
        assertEquals("freshness stays anchored at the last good poll", good.measuredAt, bad.measuredAt)
        assertEquals(1, bad.failedPolls)
        assertEquals(10_000L, (tick as Tick.Next).delayMs)
        s.tick({ throw SpeakerUnreachable("timeout") }, null)
        assertEquals(2, s.snapshot.failedPolls)
        s.tick({ speaker(uri) }, null)
        assertEquals(0, s.snapshot.failedPolls)
        assertTrue(s.snapshot.reachable)
    }

    @Test
    fun `freshness - measured within its cadence plus 10 s`() {
        val s = session()
        clock.advance(31_000) // past the arm's fast window: the cruise
        s.tick({ speaker(uri) }, null)
        val snap = s.snapshot
        assertEquals(15_000L, snap.nextPollMs)
        assertTrue(snap.fresh(clock.now))
        assertTrue(snap.fresh(clock.now + 25_000))
        assertFalse(snap.fresh(clock.now + 25_001))
        assertFalse(Fields(armed = false, uid = null, kind = null, seq = 0, measuredAt = null).fresh(clock.now))
    }

    @Test
    fun `lost session is STOPPED with an empty uri, and the DIDL check logs once per session`() {
        val ep = "https://podkast.example/ep12.mp3"
        val s = session(u = ep)
        val empty = FakeSpeaker(kid).apply {
            position = position("position_stopped_empty.xml")
            transport = transport("transport_stopped.xml")
        }
        assertTrue(s.classify(empty).lostSession)
        assertFalse("an episode end keeps its uri", s.classify(FakeSpeaker(kid).apply { position = position("position_stopped_retained.xml"); transport = "STOPPED" }).lostSession)
        assertEquals(listOf("didl accepted"), logs.map { it.substringBefore(" (") })
        logs.clear()
        val rejected = session(u = ep)
        repeat(2) { rejected.classify(FakeSpeaker(kid).apply { position = position("position_not_implemented.xml") }) }
        assertEquals(listOf("didl REJECTED by the speaker"), logs.map { it.substringBefore(" (") })
    }

    @Test
    fun `an nrk_program session is ours by its built x-sonos-http uri`() {
        val built = "x-sonos-http:series%3abarnas-supersommer%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=14"
        val s = session(Kind.NRK_PROGRAM, built)
        val f = s.classify(FakeSpeaker(kid).apply { position = position("position_nrk.xml") })
        assertTrue(f.ours)
        assertEquals(750.0, f.relS!!, 0.0)
    }

    // --- tests/sonos_contract.py's canonical snapshots ----------------------------------------

    @Test
    fun `contract - STATE_PLAYING, STATE_FOREIGN, STATE_LOST, STATE_GROUPED, STATE_MOVED, STATE_UNREACHABLE, STATE_IDLE`() {
        val ep = "https://podkast.example/ep12.mp3"
        val idle = SonosSession({ clock.now })
        assertEquals("STATE_IDLE", Fields(armed = false, uid = null, kind = null, seq = 0, measuredAt = null), idle.snapshot)
        assertEquals(Tick.Idle, idle.tick({ fail(); throw IllegalStateException() }, null))

        val s = session(u = ep)
        clock.advance(31_000)
        val spk = FakeSpeaker(kid).apply {
            position = position("position_playing_ours.xml")
            transport = transport("transport_playing.xml")
            vol = 31
        }
        s.tick({ spk }, null)
        val playing = Fields(
            armed = true, uid = kid, kind = Kind.URL, seq = 1, measuredAt = clock.now, reachable = true,
            transport = TransportState.PLAYING, relS = 431.0, durS = 1453.0, uri = ep, ours = true, foreignUri = null,
            groupedAway = false, coordinator = null, lostSession = false, volume = 31, retriedAt = null, nextPollMs = 15_000,
            measuredCadenceMs = 15_000,
        )
        assertEquals("STATE_PLAYING", playing, s.snapshot)

        spk.position = position("position_foreign.xml")
        s.tick({ spk }, null)
        val foreignUri = "x-sonos-spotify:spotify%3atrack%3aGUEST?sid=9&flags=8224&sn=1"
        assertEquals("STATE_FOREIGN", playing.copy(seq = 2, relS = 65.0, durS = 200.0, uri = foreignUri, ours = false, foreignUri = foreignUri), s.snapshot)

        spk.position = position("position_x_rincon.xml")
        s.tick({ spk }, null)
        assertEquals(
            "STATE_GROUPED",
            playing.copy(
                seq = 3, relS = 1234.0, durS = 240.0, uri = "x-rincon:RINCON_PARENT01400", ours = false,
                foreignUri = "x-rincon:RINCON_PARENT01400", groupedAway = true, coordinator = "RINCON_PARENT01400",
                nextPollMs = 5_000, measuredCadenceMs = 5_000, // the parent's position is past its track's end: the tail
            ),
            s.snapshot,
        )

        spk.position = position("position_playing_ours.xml")
        s.tick({ spk }, null)
        spk.position = position("position_stopped_empty.xml")
        spk.transport = transport("transport_stopped.xml")
        s.tick({ spk }, null)
        val lost = playing.copy(
            seq = 5, transport = TransportState.STOPPED, relS = 0.0, durS = 0.0, uri = "", ours = false, lostSession = true,
            nextPollMs = 5_000, measuredCadenceMs = 5_000,
        )
        assertEquals("STATE_LOST (the migration window: 5 s polls)", lost, s.snapshot)

        val probe = Probe().apply { candidates["10.0.0.11"] = TransportState.PLAYING to ep }
        s.tick({ spk }, probe)
        assertEquals("STATE_MOVED", lost.copy(seq = 6, streamMoved = Moved("RINCON_KITCHEN", "Kjøkken", ep), nextPollMs = 60_000, measuredCadenceMs = 60_000), s.snapshot)

        s.tick({ throw SpeakerUnreachable("timeout") }, null)
        val unreachable = s.snapshot
        assertEquals("STATE_UNREACHABLE", Triple(false, TransportState.UNREACHABLE, 7L), Triple(unreachable.reachable, unreachable.transport, unreachable.seq))
        assertTrue(unreachable.armed && unreachable.uid == kid && unreachable.kind == Kind.URL && unreachable.retriedAt == null)
    }

    // --- a new session inherits nothing measured (qa-26a-step1-code H1, H2, M1, M2) ------------

    private fun transport(s: SonosSession, spk: FakeSpeaker) = SonosTransport(s, { _, _, _ -> spk }, NrkService({ _, _ -> "" }), clock.clock)

    private fun play(u: String) = PlayBody(kid, Kind.URL, u, "T", null, null, null, 0.0)

    @Test
    fun `H1 - a play after 595 s STOPPED stays armed, and its first failed poll backs off 10 s`() = runBlocking {
        val s = session()
        val stopped = speaker(uri, tr = "STOPPED", rel = "0:00:00")
        s.tick({ stopped }, null) // the episode ended: the down streak starts
        clock.advance(595_000)
        repeat(3) { s.tick({ throw SpeakerUnreachable("blip") }, null) }
        val next = "https://podkast.example/next.mp3"
        val spk = FakeSpeaker(kid)
        transport(s, spk).play(play(next))
        clock.advance(6_000) // 601 s after the old streak began
        spk.position = PositionInfo("1", next, "0:00:00", "", "")
        spk.transport = "STOPPED" // still buffering
        assertTrue(s.tick({ spk }, null) is Tick.Next)
        assertTrue("not disarmed by its own first poll", s.armed)
        val failed = s.tick({ throw SpeakerUnreachable("blip") }, null) as Tick.Next
        assertEquals("the old session's failures don't count", 10_000L, failed.delayMs)
        assertEquals(1, s.snapshot.failedPolls)
    }

    @Test
    fun `H2 - a failed first poll after a play never shows the last episode's position as ours`() = runBlocking {
        val ep1 = "https://podkast.example/ep1.mp3"
        val s = session(u = ep1)
        s.tick({ speaker(ep1, rel = "0:23:50") }, null)
        assertEquals(1430.0, s.snapshot.relS!!, 0.0)
        transport(s, FakeSpeaker(kid)).play(play("https://podkast.example/ep2.mp3"))
        s.tick({ throw SpeakerUnreachable("blip") }, null)
        val snap = s.snapshot
        assertEquals(TransportState.UNREACHABLE, snap.transport)
        assertFalse(snap.ours)
        assertNull(snap.relS)
        assertNull(snap.uri)
        assertNull(snap.measuredAt)
        assertFalse(snap.fresh(clock.now))
        // and an adopt the same
        s.tick({ speaker("https://podkast.example/ep2.mp3", rel = "0:01:00") }, null)
        s.adopt(kid, Kind.URL, "https://podkast.example/ep3.mp3")
        assertNull(s.snapshot.relS)
        assertNull(s.snapshot.measuredAt)
    }

    @Test
    fun `M1 - two sessions in a row - the new one's TRANSITIONING reads PLAYING and polls in 5 s`() = runBlocking {
        val s = session()
        s.tick({ speaker(uri, tr = "STOPPED") }, null) // the last episode ended: the last real state is STOPPED
        clock.advance(40_000)
        val next = "https://podkast.example/next.mp3"
        transport(s, FakeSpeaker(kid)).play(play(next))
        clock.advance(31_000) // past the arm's window
        val tick = s.tick({ speaker(next, tr = "TRANSITIONING", rel = "0:00:00") }, null) as Tick.Next
        assertEquals("a fresh start, not the old STOPPED", TransportState.PLAYING, s.snapshot.transport)
        assertEquals(5_000L, tick.delayMs)
    }

    @Test
    fun `M1 - a resume that reads TRANSITIONING (reported PAUSED) re-polls in 5 s, not 15`() {
        val s = session()
        clock.advance(31_000)
        assertEquals(15_000L, (s.tick({ speaker(uri, tr = "PAUSED_PLAYBACK") }, null) as Tick.Next).delayMs)
        s.stampVerb() // resume
        val inWindow = s.tick({ speaker(uri, tr = "TRANSITIONING") }, null) as Tick.Next
        assertEquals("the last real state", TransportState.PAUSED_PLAYBACK, s.snapshot.transport)
        assertTrue(s.snapshot.transitioning)
        assertEquals(5_000L, inWindow.delayMs)
        clock.advance(31_000)
        assertEquals("still buffering past the window", 5_000L, (s.tick({ speaker(uri, tr = "TRANSITIONING") }, null) as Tick.Next).delayMs)
    }

    @Test
    fun `M2 - failed polls never extend freshness`() {
        val s = session()
        clock.advance(31_000)
        s.tick({ speaker(uri) }, null) // cruise: 15 s
        val measured = clock.now
        clock.advance(15_000)
        for (i in 1..6) {
            val delay = (s.tick({ throw SpeakerUnreachable("down") }, null) as Tick.Next).delayMs
            val snap = s.snapshot
            assertEquals(15_000L, snap.measuredCadenceMs)
            assertEquals("failure $i", clock.now - measured <= 25_000, snap.fresh(clock.now))
            if (i >= 3) assertFalse("failure $i", snap.fresh(clock.now))
            clock.advance(delay)
        }
        assertTrue(s.armed)
    }

    // --- play: uid and kind before the SOAP, the uri only after it ----------------------------

    @Test
    fun `a play sets uid and kind before the SOAP and the uri only after success`() = runBlocking {
        val s = session(u = uri)
        val spk = FakeSpeaker("RINCON_NEW").apply { failOn = mapOf("seturi" to { UpnpFault(714, "Illegal MIME-type") }) }
        val t = SonosTransport(s, { _, _, _ -> spk }, NrkService({ _, _ -> "" }), clock.clock)
        try {
            t.play(PlayBody("RINCON_NEW", Kind.URL, "https://podkast.example/new.mp3", "T", null, null, null, 0.0))
            fail()
        } catch (e: UpnpFault) {
            assertEquals(714, e.code)
        }
        assertEquals("RINCON_NEW", s.uid)
        assertEquals(Kind.URL, s.kind)
        assertEquals("the uri stays the old one until a play succeeds", uri, s.uri)
        spk.failOn = emptyMap()
        t.play(PlayBody("RINCON_NEW", Kind.URL, "https://podkast.example/new.mp3", "T", null, null, null, 0.0))
        assertEquals("https://podkast.example/new.mp3", s.uri)
        assertTrue(s.armed)
    }

    // --- Storytel (design 26b §5.3, §5.7) ------------------------------------------------------

    @Test
    fun `the not-ours line never prints a signed URL's token - scheme, host and path only`() {
        val s = session()
        warm(s)
        val minted = "https://cdn.example.invalid/mp3/222/abook.mp3?consumableId=222&token=FAKE-SIG-4"
        s.classify(speaker(minted))
        val line = logs.single { it.startsWith("not-ours?") }
        assertFalse(line, line.contains("FAKE-SIG") || line.contains("tok=") || line.contains("consumableId"))
        assertEquals("not-ours? speaker=https://cdn.example.invalid/mp3/222/abook.mp3 vs set=https://cdn.example/signed/ep12.mp3", line)
    }

    @Test
    fun `a stored marker is ours by consumableId - the bedtime look and the adopt's first classify - another book is not`() {
        val book = "https://cdn.example.invalid/mp3/111/abook.mp3?consumableId=111&token=FAKE-SIG-1"
        val s = session(u = "storytel:book:111")
        assertTrue(s.classify(speaker(book), aux = false).ours)
        val other = s.classify(speaker("https://cdn.example.invalid/mp3/222/abook.mp3?consumableId=222&token=FAKE-SIG-111"), aux = false)
        assertFalse(other.ours)
        assertFalse(logs.any { it.contains("FAKE-SIG") })
        // a fresh mint of our book is a new URL: the session that sent it holds it, so it is ours by normUri
        val sent = session(u = book)
        assertTrue(sent.classify(speaker(book)).ours)
        assertFalse("another token is another stream", sent.classify(speaker(book.replace("FAKE-SIG-1", "FAKE-SIG-2"))).ours)
    }
}
