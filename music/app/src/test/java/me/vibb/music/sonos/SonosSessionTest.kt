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
        repeat(11) { s.classify(spk) }
        assertEquals("not again before the 12th poll", 1, spk.log.count { it == "getgroup" })
        val f = s.classify(spk)
        assertEquals("the volume refreshed", 40, f.volume)
        assertTrue("a failed group read keeps the last topology", f.groupedAway)
    }

    // --- sonos_migrate_probe.py ----------------------------------------------------------------

    private class Probe : MigrationProbe {
        var topology = 0
        val candidates = mutableMapOf<String, Pair<TransportState, String>>()

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

        override fun live(player: CachedPlayer): Pair<TransportState, String>? = candidates[player.ip] ?: (TransportState.STOPPED to "")
    }

    private val stoppedEmpty get() = fields(TransportState.STOPPED, "")

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
        repeat(5) { s.maybeProbeMigration(stoppedEmpty, probe) }
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
        val snap = fields(TransportState.STOPPED, "")
        assertEquals("attempts land at seconds, not the 60 s stopped cadence", 5_000L, s.cadenceMs(TransportState.STOPPED, snap, 0))
        assertEquals("an episode-end STOPPED (uri retained) keeps its cadence", 60_000L, s.cadenceMs(TransportState.STOPPED, fields(TransportState.STOPPED, uri), 0))
        repeat(3) { s.maybeProbeMigration(snap, Probe()) }
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
        assertEquals(15_000L, s.cadenceMs(TransportState.PAUSED_PLAYBACK, playing, 0))
        assertEquals(listOf(10_000L, 20_000L, 40_000L, 160_000L, 300_000L, 300_000L), listOf(1, 2, 3, 5, 6, 9).map { s.cadenceMs(TransportState.UNREACHABLE, playing, it) })
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
        val retries = (1..8).map { (s.tick({ spk }, null) as Tick.Next).stallRetryAtS }
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
                nextPollMs = 5_000, // the parent's position is past its own track's end: the tail cadence
            ),
            s.snapshot,
        )

        spk.position = position("position_playing_ours.xml")
        s.tick({ spk }, null)
        spk.position = position("position_stopped_empty.xml")
        spk.transport = transport("transport_stopped.xml")
        s.tick({ spk }, null)
        val lost = playing.copy(seq = 5, transport = TransportState.STOPPED, relS = 0.0, durS = 0.0, uri = "", ours = false, lostSession = true, nextPollMs = 5_000)
        assertEquals("STATE_LOST (the migration window: 5 s polls)", lost, s.snapshot)

        val probe = Probe().apply { candidates["10.0.0.11"] = TransportState.PLAYING to ep }
        s.tick({ spk }, probe)
        assertEquals("STATE_MOVED", lost.copy(seq = 6, streamMoved = Moved("RINCON_KITCHEN", "Kjøkken", ep), nextPollMs = 60_000), s.snapshot)

        s.tick({ throw SpeakerUnreachable("timeout") }, null)
        val unreachable = s.snapshot
        assertEquals("STATE_UNREACHABLE", Triple(false, TransportState.UNREACHABLE, 7L), Triple(unreachable.reachable, unreachable.transport, unreachable.seq))
        assertTrue(unreachable.armed && unreachable.uid == kid && unreachable.kind == Kind.URL && unreachable.retriedAt == null)
    }

    // --- play: uid and kind before the SOAP, the uri only after it ----------------------------

    @Test
    fun `a play sets uid and kind before the SOAP and the uri only after success`() = runBlocking {
        val s = session(u = uri)
        val spk = FakeSpeaker("RINCON_NEW").apply { failOn = mapOf("seturi" to { UpnpFault(714, "Illegal MIME-type") }) }
        val t = SonosTransport(s, { _, _ -> spk }, NrkService({ _, _ -> "" }), clock.clock)
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
}
