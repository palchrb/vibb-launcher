package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The transport sequences (design 26a §2.5) with a speaker that records every call: port of
 * palchrb/vibb `tests/sonos_hush.py` checks 1-4 (check 5 is `queue_play`, the speaker-owned queue of
 * phase 3; MIT, Copyright (c) 2026 palchrb), `_seek_settled`'s fault rules, `adopt`'s zero commands,
 * the verbs and the stall retry of `sonosd.py`.
 */
class SonosTransportTest {
    private val clock = FakeClock()
    private val wakes = mutableListOf<String>()
    private val logs = mutableListOf<String>()

    private fun rig(spk: FakeSpeaker, accounts: String = SonosFakes.fixture("accounts.xml")): SonosTransport {
        val session = SonosSession({ clock.now }, { logs.add(it) })
        return SonosTransport(session, { _, _, _ -> spk }, NrkService({ _, _ -> accounts }), clock.clock, { wakes.add("wake") }, { logs.add(it) })
    }

    private val body = PlayBody("RINCON_X", Kind.URL, "http://c/e.mp3", "Sommerfuglen", "Barnas Supersommer", null, 0.0, 0.0)

    @Test
    fun `hush 1 - a resume is muted across the Play to Seek window, then back`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val r = rig(spk).play(body.copy(startS = 120.0))!!
        assertTrue(r.sought)
        assertEquals(listOf("mute true", "seturi", "play", "seek 0:02:00", "mute false"), spk.commands)
        assertFalse(spk.muted)
        assertEquals(
            "the full sequence: mute read, queue cleared, transport polled before the seek",
            listOf("getmute", "mute true", "clearqueue", "seturi", "play", "gettransport", "seek 0:02:00", "mute false"),
            spk.log,
        )
    }

    @Test
    fun `hush 2 - a deliberately muted speaker stays muted`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X", muted = true)
        rig(spk).play(body.copy(startS = 60.0))!!
        assertEquals("mute true", spk.commands.first())
        assertEquals("mute true", spk.commands.last())
        assertTrue(spk.muted)
    }

    @Test
    fun `hush 3 - a from-the-top play never touches mute`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val r = rig(spk).play(body.copy(startS = 4.9))!!
        assertTrue(r.sought)
        assertFalse(spk.log.any { it.contains("mute") })
        assertEquals(listOf("seturi", "play"), spk.commands)
    }

    @Test
    fun `hush 4 - SetURI dies mid-flight - play throws but the mute is restored`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("seturi" to { SpeakerUnreachable("speaker went away") }) }
        try {
            rig(spk).play(body.copy(startS = 120.0))!!
            fail("play() should have thrown")
        } catch (_: SpeakerUnreachable) {
        }
        assertEquals(listOf("mute true", "mute false"), spk.commands)
        assertFalse(spk.muted)
    }

    @Test
    fun `hush - a speaker that can't read its mute is played unhushed`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("getmute" to { UpnpFault(402, null) }) }
        rig(spk).play(body.copy(startS = 120.0))!!
        assertEquals(listOf("seturi", "play", "seek 0:02:00"), spk.commands)
    }

    @Test
    fun `seek settled - 701 retried, then the degrade after three`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        repeat(3) { spk.seekFaults.add(UpnpFault(701, null)) }
        val r = rig(spk).play(body.copy(startS = 427.0))!!
        assertFalse("sought=false is a degrade, never an error", r.sought)
        assertEquals(3, spk.log.count { it == "seek 0:07:07 refused" })
        assertEquals(listOf(400L, 400L, 400L), clock.sleeps)
        assertTrue(logs.contains("seek to 0:07:07 refused - playing from the top"))
        assertEquals("the mute is restored anyway", "mute false", spk.commands.last())
    }

    @Test
    fun `seek settled - 710 and 711 are retried too, the next try lands`() = runBlocking {
        for (code in listOf(710, 711)) {
            val spk = FakeSpeaker("RINCON_X")
            spk.seekFaults.add(UpnpFault(code, null))
            assertTrue(rig(spk).play(body.copy(startS = 60.0))!!.sought)
            assertEquals(listOf("seek 0:01:00 refused", "seek 0:01:00"), spk.log.filter { it.startsWith("seek") })
        }
    }

    @Test
    fun `seek settled - any other fault or error degrades at once`() = runBlocking {
        for (e in listOf(UpnpFault(500, "Internal error"), SpeakerUnreachable("timeout"))) {
            val spk = FakeSpeaker("RINCON_X")
            spk.seekFaults.add(e)
            spk.seekFaults.add(UpnpFault(701, null))
            assertFalse(rig(spk).play(body.copy(startS = 60.0))!!.sought)
            assertEquals(1, spk.log.count { it.startsWith("seek") })
        }
    }

    @Test
    fun `seek settled - waits for PLAYING in 0,3 s steps, up to 8 s, then seeks anyway`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { transport = "TRANSITIONING" }
        val t = rig(spk)
        clock.sleeps.clear()
        assertTrue(t.seekSettled(spk, 60.0))
        assertEquals(27, clock.sleeps.size) // 8 s / 0.3 s, rounded up
        assertTrue(clock.sleeps.all { it == 300L })
        val playing = FakeSpeaker("RINCON_X")
        clock.sleeps.clear()
        t.seekSettled(playing, 60.0)
        assertEquals("already playing: no wait", emptyList<Long>(), clock.sleeps)
    }

    @Test
    fun `nrk_program - the service uri from the household serial, the show DIDL, the built uri returned`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val r = rig(spk).play(
            PlayBody("RINCON_X", Kind.NRK_PROGRAM, "https://radio.nrk.no/serie/barnas-supersommer", "Kap. 3", "Barnas Supersommer", null, null, 0.0, series = "barnas-supersommer", programId = "MKTT81008181"),
        )!!
        val built = "x-sonos-http:series%3abarnas-supersommer%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=7"
        assertEquals(built, r.uri)
        assertEquals(built, spk.lastUri)
        assertTrue(spk.lastMeta!!.contains("<upnp:class>object.item.audioItem.show</upnp:class>"))
        assertTrue(spk.lastMeta!!.contains(">SA_RINCON70919_X_#Svc70919-0-Token</desc>"))
        assertFalse("no queue clear for a service item", spk.log.contains("clearqueue"))
    }

    @Test
    fun `url - the queue clear's errors are ignored, the DIDL carries the body - never the cadence hint`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("clearqueue" to { UpnpFault(804, null) }) }
        // design 53 §2.1: a url body has no DIDL duration (vibb's); the listing's rides as the session's hint only
        val r = rig(spk).play(body.copy(durationS = null, art = "http://10.0.0.5/c.jpg", cadenceHintS = 1453.0))!!
        assertEquals("http://c/e.mp3", r.uri)
        assertEquals(didl("http://c/e.mp3", "Sommerfuglen", null, "Barnas Supersommer", "http://10.0.0.5/c.jpg", null), r.didl)
        assertFalse(r.didl.contains("duration="))
        assertEquals(r.didl, spk.lastMeta)
    }

    @Test
    fun `the gate is asked right before SetAVTransportURI - closed, nothing sent, the mute put back, no result`() = runBlocking {
        // design 26b §5.1, qa-26b-step4b-code L1: the play job's token after the speaker check, the hush, the cap
        // and the queue clear - a step or a suspension in that window still sends no SetAVTransportURI
        val spk = FakeSpeaker("RINCON_X")
        var asked = 0
        val r = rig(spk).play(body.copy(startS = 120.0), gate = { asked++; spk.log.contains("clearqueue").not() })
        assertNull(r)
        assertEquals(1, asked)
        assertEquals("the hush and the clear went, the mute back, no uri, no Play", listOf("mute true", "mute false"), spk.commands)
        assertFalse(spk.log.contains("seturi"))
        val nrk = FakeSpeaker("RINCON_X")
        assertNull(
            rig(nrk).play(
                PlayBody("RINCON_X", Kind.NRK_PROGRAM, "https://radio.nrk.no/serie/x", "Kap. 3", "X", null, null, 0.0, series = "x", programId = "MKTT81008181"),
                gate = { false },
            ),
        )
        assertFalse(nrk.log.contains("seturi"))
        assertEquals("an open gate plays", "http://c/e.mp3", rig(FakeSpeaker("RINCON_X")).play(body, gate = { true })!!.uri)
    }

    @Test
    fun `a play arms the session, polls fast, and wakes the poller`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        t.play(body)
        assertTrue(t.session.armed)
        assertEquals(listOf("wake"), wakes)
        assertEquals(5_000L, t.session.cadenceMs(TransportState.PLAYING, t.session.snapshot, 0))
    }

    @Test
    fun `adopt - bookkeeping only, zero transport commands`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        assertEquals(emptyList<String>(), spk.log)
        assertTrue(t.session.armed)
        assertEquals("http://c/e.mp3", t.session.uri)
        assertEquals(listOf("wake"), wakes)
    }

    @Test
    fun `verbs - each sends one command, stamps the fast window and wakes the poller`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        wakes.clear()
        clock.advance(60_000)
        assertTrue(t.pause())
        assertEquals(5_000L, t.session.cadenceMs(TransportState.PLAYING, t.session.snapshot, 0))
        assertTrue(t.resume())
        assertTrue(t.seek(431.5))
        assertEquals(listOf("pause", "play", "seek 0:07:11"), spk.log)
        assertEquals(3, wakes.size)
        assertTrue(t.stop())
        assertFalse("stop disarms", t.session.armed)
        assertEquals("stop", spk.log.last())
    }

    @Test
    fun `verbs - a verb for a uid that is no longer the session's is dropped`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        assertFalse("no session yet", t.pause())
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        assertFalse(t.pause(ifUid = "RINCON_OLD"))
        assertFalse(t.stop(ifUid = "RINCON_OLD"))
        assertEquals(emptyList<String>(), spk.log)
        assertTrue(t.pause(ifUid = "RINCON_X"))
    }

    @Test
    fun `volume - clamped to 0-100 and written straight into the snapshot`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        wakes.clear()
        t.volume(140)
        assertEquals(listOf("volume 100"), spk.log)
        assertEquals(100, t.session.snapshot.volume)
        t.volume(-3)
        assertEquals(0, t.session.snapshot.volume)
        assertEquals("our own set needs no re-poll", emptyList<String>(), wakes)
        // a poll between aux reads keeps our own value, not a stale one
        t.poll(null) // the aux read at the session start
        t.volume(55)
        spk.vol = 20 // changed from the Sonos app; the aux hasn't read it yet
        t.poll(null)
        assertEquals(55, t.session.snapshot.volume)
    }

    @Test
    fun `a failing verb propagates and changes nothing`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("stop" to { SpeakerUnreachable("down") }) }
        val t = rig(spk)
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        try {
            t.stop()
            fail()
        } catch (_: SpeakerUnreachable) {
        }
        assertTrue("still armed: the Stop never landed", t.session.armed)
    }

    @Test
    fun `the stall retry - Stop, Play and a settled seek back, once`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply {
            position = PositionInfo("1", "http://c/e.mp3", "0:07:11", "0:24:13", "<x/>")
            transport = "PLAYING"
        }
        val t = rig(spk)
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        repeat(6) {
            t.poll(null)
            clock.advance(5_000) // the stall counts polls 5 s apart (design 53 §2.4)
        }
        val commands = spk.log.filter { !it.startsWith("get") }
        assertEquals(listOf("stop", "play", "seek 0:07:11"), commands)
        assertTrue(logs.contains("position frozen - one in-place retry"))
    }

    @Test
    fun `the stall retry from near 0 doesn't seek, and its failure is only logged`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply {
            position = PositionInfo("1", "http://c/e.mp3", "0:00:03", "0:24:13", "<x/>")
            failOn = mapOf("play" to { SpeakerUnreachable("down") })
        }
        val t = rig(spk)
        t.adopt("RINCON_X", Kind.URL, "http://c/e.mp3")
        repeat(5) {
            t.poll(null)
            clock.advance(5_000)
        }
        assertEquals(listOf("stop"), spk.log.filter { !it.startsWith("get") })
        assertTrue(logs.contains("stall retry failed (SpeakerUnreachable)"))
    }

    @Test
    fun `an unknown uid on the play path is the failed-play path's`() = runBlocking {
        val session = SonosSession({ clock.now })
        val t = SonosTransport(session, { uid, verify, _ -> assertTrue(verify); throw UnknownUid(uid) }, NrkService({ _, _ -> "" }), clock.clock)
        try {
            t.play(body)
            fail()
        } catch (e: UnknownUid) {
            assertEquals("RINCON_X", e.uid)
        }
        assertFalse(session.armed)
    }

    @Test
    fun `d53 a − on our coordinator is BecomeCoordinatorOfStandaloneGroup in the actor, to the session's uid only`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        t.play(body)!!
        assertTrue(t.leave("RINCON_X"))
        assertEquals(listOf("seturi", "play", "standalone"), spk.commands)
        assertEquals("the verb's fast cadence and a poll at once", "wake", wakes.last())
        assertFalse("a uid that is no longer the session's: nothing", t.leave("RINCON_OTHER01400"))
        assertEquals(3, spk.commands.size)
        // a joiner from outside: its cap through the same actor, our own sets by uid
        val loud = FakeSpeaker("RINCON_LOUD01400").apply { vol = 80 }
        val quiet = FakeSpeaker("RINCON_QUIET01400").apply { vol = 20 }
        val byUid = mapOf(spk.uid to spk, loud.uid to loud, quiet.uid to quiet)
        val s2 = SonosSession({ clock.now })
        val t2 = SonosTransport(s2, { u, _, _ -> byUid[u] ?: throw UnknownUid(u) }, NrkService({ _, _ -> "" }), clock.clock)
        assertEquals(mapOf(loud.uid to 40), t2.capMembers(listOf(loud.uid, quiet.uid, "RINCON_GONE01400"), 40))
        assertEquals(listOf("volume 40"), loud.commands)
        assertTrue(quiet.commands.isEmpty())
    }

    @Test
    fun `an nrk_program body without its series is a SonosException, inside the failed-play path (L1)`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        try {
            rig(spk).play(PlayBody("RINCON_X", Kind.NRK_PROGRAM, "https://radio.nrk.no/serie/x", "T", null, null, null, 0.0, programId = "MKTT1"))
            fail()
        } catch (e: SonosException) {
            assertEquals("nrk_program without a series", e.message)
        }
        assertEquals(emptyList<String>(), spk.commands)
    }

    // -- design 54: a share link into the speaker's own queue (vibb's spotify_sharelink; SoCo's ShareLink) --

    private val a = "1FakeTrackA00000000001"
    private val c = "1FakeTrackC00000000003"
    private val d = "1FakeTrackD00000000004"
    private val listed = listOf(a, c, a, d).map { "spotify:track:$it" }

    /** The fake playlist's queue as the speaker holds it: A, an episode our listing drops, C, A again, D. */
    private fun queueSpeaker() = FakeSpeaker("RINCON_X").apply {
        shareTracks = listOf(FakeSpeaker.qTrack(a), FakeSpeaker.qTrack("5FakeEpisode0000000009", "episode"), FakeSpeaker.qTrack(c), FakeSpeaker.qTrack(a), FakeSpeaker.qTrack(d))
    }

    private fun share(index: Int? = 0, startS: Double = 0.0, uris: List<String> = listed) =
        PlayBody("RINCON_X", Kind.SPOTIFY_SHARELINK, "spotify:playlist:0FakePlaylistKveld0001", null, "Kveldsmusikk", null, null, startS, trackIndex = index, listed = uris)

    @Test
    fun `d54 a transfer - hushed, capped, gated, NORMAL, cleared, added by 2311, the start's page, play_from_queue, the seek, the sleep timer`() = runBlocking {
        val spk = queueSpeaker().apply { vol = 60 }
        val t = rig(spk)
        // the start item is the second A (index 2): the occurrence nearest the guess first + 2 = 3 is position 4
        val r = t.playShare(share(index = 2, startS = 42.0), cap = 40, sleepTimer = "1:00:00")!!
        assertEquals(
            listOf(
                "getmute", "mute true", "getvolume", "volume 40", "playmode NORMAL", "clearqueue", "addqueue 2311", "getbrowse 0 100",
                "seturi", "seektrack 4", "play", "gettransport", "seek 0:00:42", "sleeptimer 1:00:00", "mute false",
            ),
            spk.log,
        )
        assertEquals("play_from_queue: the queue as the source, no metadata", queueUri("RINCON_X"), spk.lastUri)
        assertEquals("", spk.lastMeta)
        assertEquals(ShareLink.of("spotify:playlist:0FakePlaylistKveld0001")!!.enqueuedUri to ShareLink.of("spotify:playlist:0FakePlaylistKveld0001")!!.metadata(2311), spk.lastEnqueued)
        assertEquals(queueUri("RINCON_X"), r.uri)
        assertEquals(40, r.volume)
        assertTrue(r.sought)
        val start = r.share!!
        assertEquals(listOf(1, 5, 4, 2311), listOf(start.first, start.length, start.position, start.sn))
        assertTrue(start.found)
        assertTrue("the start's page holds the whole small queue", start.read.complete)
        assertEquals(Kind.SPOTIFY_SHARELINK, t.session.kind)
        assertEquals("spotify:playlist:0FakePlaylistKveld0001", t.session.shareTarget)
        assertTrue(t.session.armed)
        assertTrue("our sleep timer: ours to clear later", t.session.ownSleepLeftMs("RINCON_X")!! > 3_590_000)
        assertTrue("logged by kind, count and position - never an id (54-11)", logs.none { "Fake" in it || "spotify:" in it })
        assertTrue(logs.contains("share: playlist added 5 (first 1, queue 5) by service 2311"))
        assertFalse("never an id in the result's line", r.toString().contains("Fake"))
    }

    @Test
    fun `d54 a closed gate sends nothing more - before SetPlayMode and again before SetAVTransportURI`() = runBlocking {
        val first = queueSpeaker()
        assertNull(rig(first).playShare(share(), gate = { false }))
        assertTrue("nothing changed the room", first.log.none { it.startsWith("playmode") || it == "clearqueue" || it.startsWith("addqueue") })
        val second = queueSpeaker()
        var asked = 0
        assertNull(rig(second).playShare(share(startS = 30.0), gate = { ++asked == 1 }))
        assertEquals(2, asked)
        assertTrue("the add went out, the play didn't", second.log.contains("addqueue 2311") && second.log.none { it == "seturi" || it.startsWith("seektrack") || it == "play" })
        assertFalse("the mute put back", second.muted)
    }

    @Test
    fun `d54 3079 only after a UPnP fault, both faults or a 0-track add are ShareRefused, a network failure ends at once - no Seek`() = runBlocking {
        val us = queueSpeaker().apply { addFaults = mapOf(2311 to { UpnpFault(800, null) }) }
        val r = rig(us).playShare(share())!!
        assertEquals(3079, r.share!!.sn)
        assertEquals(listOf("addqueue 2311 refused", "addqueue 3079"), us.log.filter { it.startsWith("addqueue") })
        assertTrue(us.lastEnqueued!!.second.contains("SA_RINCON3079_X_#Svc3079-0-Token"))
        assertEquals(r.didl, us.lastEnqueued!!.second)
        for (spk in listOf(
            queueSpeaker().apply { addFaults = mapOf(2311 to { UpnpFault(800, null) }, 3079 to { UpnpFault(800, null) }) },
            queueSpeaker().apply { shareTracks = emptyList() },
            queueSpeaker().apply { addAnswer = AddResult(1, 3, 0) },
        )) {
            try {
                rig(spk).playShare(share(startS = 30.0))
                fail("refused")
            } catch (_: ShareRefused) {
            }
            assertTrue(spk.log.none { it.startsWith("seek") || it == "seturi" || it == "play" })
            assertFalse(spk.muted)
        }
        val gone = queueSpeaker().apply { addFaults = mapOf(2311 to { SpeakerUnreachable("timeout") }) }
        try {
            rig(gone).playShare(share())
            fail("unreachable")
        } catch (_: SpeakerUnreachable) {
        }
        assertEquals("no second try on a network failure", listOf("addqueue 2311 refused"), gone.log.filter { it.startsWith("addqueue") })
        try {
            rig(queueSpeaker()).playShare(share().copy(uri = "spotify:artist:3FakeArtist00000000001"))
            fail("an artist has no share type")
        } catch (_: ShareRefused) {
        }
    }

    @Test
    fun `d54 N4 a start item not in the queue plays the first added position at 0 - no seek - and an unparsable first is 1`() = runBlocking {
        val spk = queueSpeaker().apply { addAnswer = AddResult(null, 5, 5) }
        val r = rig(spk).playShare(share(index = 1, startS = 90.0, uris = listOf(listed[0], "spotify:track:1FakeTrackE00000000005")))!!
        assertTrue(spk.log.contains("seektrack 1"))
        assertTrue("the saved second belongs to another track", spk.log.none { it.startsWith("seek 0:") })
        assertEquals(listOf(1, 1), listOf(r.share!!.first, r.share!!.position))
        assertFalse(r.share!!.found)
        assertTrue(r.sought)
        assertTrue(logs.contains("share: the start item isn't in the queue - position 1, no seek"))
        assertFalse(spk.muted)
    }

    @Test
    fun `d54 the rest of the queue is its own job, off the hush - a queue edited while it is read is read again once`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { shareTracks = (1..150).map { FakeSpeaker.qTrack("1FakeTrackQ%011d".format(it)) } }
        val t = rig(spk)
        val r = t.playShare(share(index = 0, uris = listOf("spotify:track:1FakeTrackQ00000000001")))!!
        assertFalse("the start read its page only", r.share!!.read.complete)
        spk.log.clear()
        val read = t.readQueue("RINCON_X", r.share!!.read)!!
        assertEquals(listOf("getbrowse 100 100"), spk.log)
        assertTrue(read.complete)
        assertEquals(150, read.uris.size)
        assertNull("another uid: nothing", t.readQueue("RINCON_Y", r.share!!.read))
        // the Sonos app edits the queue between the start's page and the rest: read again from the top, once
        spk.updateId++
        spk.log.clear()
        val again = t.readQueue("RINCON_X", r.share!!.read)!!
        assertEquals(listOf("getbrowse 100 100", "getbrowse 0 100", "getbrowse 100 100"), spk.log)
        assertEquals(spk.updateId.toString(), again.updateId)
        // adopt's read: the container, then every page - reads only
        spk.log.clear()
        assertEquals(150, t.learnQueue("RINCON_X")!!.tracks.size)
        assertEquals(listOf("getqueuemeta", "getbrowse 0 100", "getbrowse 100 100"), spk.log)
    }

    @Test
    fun `d54 queuePlay - hushed as vibb's sonos_hush 5, the cap on a start only, no clear and no add, refused for another uid or kind`() = runBlocking {
        val spk = queueSpeaker().apply { vol = 60 }
        val t = rig(spk)
        t.playShare(share())!!
        spk.log.clear()
        val r = t.queuePlay(3, 30.0, "RINCON_X")!!
        assertEquals(listOf("getmute", "mute true", "seturi", "seektrack 3", "play", "gettransport", "seek 0:00:30", "mute false"), spk.log)
        assertTrue(r.sought)
        spk.log.clear()
        t.queuePlay(5, 0.0, "RINCON_X", cap = 40)!!
        assertEquals("a pick is a START: capped before Play", listOf("getvolume", "volume 40", "seturi", "seektrack 5", "play"), spk.log)
        spk.log.clear()
        assertNull(t.queuePlay(2, 0.0, "RINCON_Y"))
        assertNull(t.queuePlay(2, 0.0, "RINCON_X", gate = { false }))
        assertTrue(spk.log.isEmpty())
        t.play(body)!!
        assertNull("a url session has no queue to jump in", t.queuePlay(2, 0.0, "RINCON_X"))
    }

    @Test
    fun `d54 the park clears the queue for every kind, our sleep timer goes with our Stop or the park, only while it is ours`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val t = rig(spk)
        t.play(body)!!
        spk.log.clear()
        assertTrue(t.park())
        assertEquals(listOf("stop", "clearqueue", "seturi"), spk.log)
        assertEquals(queueUri("RINCON_X"), spk.lastUri)
        // a refused clear: the queue uri still goes out
        val stubborn = FakeSpeaker("RINCON_X").apply { failOn = mapOf("clearqueue" to { UpnpFault(804, null) }) }
        val t2 = rig(stubborn)
        t2.play(body.copy(startS = 0.0))
        stubborn.log.clear()
        assertTrue(t2.park())
        assertEquals(listOf("stop", "seturi"), stubborn.log)
        assertTrue(logs.contains("park: the queue wasn't cleared (UpnpFault)"))
        // ours: cleared by the Stop
        val q = queueSpeaker()
        val t3 = rig(q)
        t3.playShare(share(), sleepTimer = "1:00:00")!!
        clock.advance(60_000)
        q.sleepRemaining = "0:59:00"
        q.log.clear()
        assertTrue(t3.stop())
        assertEquals(listOf("stop", "getsleeptimer", "sleeptimer "), q.log)
        assertNull(q.sleepRemaining)
        // someone set another in the Sonos app afterwards: theirs, the park leaves it
        t3.playShare(share(), sleepTimer = "1:00:00")!!
        q.sleepRemaining = "0:20:00"
        q.log.clear()
        assertTrue(t3.park())
        assertEquals(listOf("stop", "clearqueue", "seturi", "getsleeptimer"), q.log)
        assertEquals("0:20:00", q.sleepRemaining)
        // none of ours: not even read
        q.log.clear()
        t3.playShare(share())!!
        q.log.clear()
        t3.stop()
        assertEquals(listOf("stop"), q.log)
    }
}
