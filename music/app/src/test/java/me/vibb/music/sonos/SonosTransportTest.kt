package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        return SonosTransport(session, { _, _ -> spk }, NrkService({ _, _ -> accounts }), clock.clock, { wakes.add("wake") }, { logs.add(it) })
    }

    private val body = PlayBody("RINCON_X", Kind.URL, "http://c/e.mp3", "Sommerfuglen", "Barnas Supersommer", null, 0.0, 0.0)

    @Test
    fun `hush 1 - a resume is muted across the Play to Seek window, then back`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val r = rig(spk).play(body.copy(startS = 120.0))
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
        rig(spk).play(body.copy(startS = 60.0))
        assertEquals("mute true", spk.commands.first())
        assertEquals("mute true", spk.commands.last())
        assertTrue(spk.muted)
    }

    @Test
    fun `hush 3 - a from-the-top play never touches mute`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        val r = rig(spk).play(body.copy(startS = 4.9))
        assertTrue(r.sought)
        assertFalse(spk.log.any { it.contains("mute") })
        assertEquals(listOf("seturi", "play"), spk.commands)
    }

    @Test
    fun `hush 4 - SetURI dies mid-flight - play throws but the mute is restored`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("seturi" to { SpeakerUnreachable("speaker went away") }) }
        try {
            rig(spk).play(body.copy(startS = 120.0))
            fail("play() should have thrown")
        } catch (_: SpeakerUnreachable) {
        }
        assertEquals(listOf("mute true", "mute false"), spk.commands)
        assertFalse(spk.muted)
    }

    @Test
    fun `hush - a speaker that can't read its mute is played unhushed`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("getmute" to { UpnpFault(402, null) }) }
        rig(spk).play(body.copy(startS = 120.0))
        assertEquals(listOf("seturi", "play", "seek 0:02:00"), spk.commands)
    }

    @Test
    fun `seek settled - 701 retried, then the degrade after three`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X")
        repeat(3) { spk.seekFaults.add(UpnpFault(701, null)) }
        val r = rig(spk).play(body.copy(startS = 427.0))
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
            assertTrue(rig(spk).play(body.copy(startS = 60.0)).sought)
            assertEquals(listOf("seek 0:01:00 refused", "seek 0:01:00"), spk.log.filter { it.startsWith("seek") })
        }
    }

    @Test
    fun `seek settled - any other fault or error degrades at once`() = runBlocking {
        for (e in listOf(UpnpFault(500, "Internal error"), SpeakerUnreachable("timeout"))) {
            val spk = FakeSpeaker("RINCON_X")
            spk.seekFaults.add(e)
            spk.seekFaults.add(UpnpFault(701, null))
            assertFalse(rig(spk).play(body.copy(startS = 60.0)).sought)
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
        )
        val built = "x-sonos-http:series%3abarnas-supersommer%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=7"
        assertEquals(built, r.uri)
        assertEquals(built, spk.lastUri)
        assertTrue(spk.lastMeta!!.contains("<upnp:class>object.item.audioItem.show</upnp:class>"))
        assertTrue(spk.lastMeta!!.contains(">SA_RINCON70919_X_#Svc70919-0-Token</desc>"))
        assertFalse("no queue clear for a service item", spk.log.contains("clearqueue"))
    }

    @Test
    fun `url - the queue clear's errors are ignored, the DIDL carries the body`() = runBlocking {
        val spk = FakeSpeaker("RINCON_X").apply { failOn = mapOf("clearqueue" to { UpnpFault(804, null) }) }
        val r = rig(spk).play(body.copy(durationS = 1453.0, art = "http://10.0.0.5/c.jpg"))
        assertEquals("http://c/e.mp3", r.uri)
        assertEquals(didl("http://c/e.mp3", "Sommerfuglen", null, "Barnas Supersommer", "http://10.0.0.5/c.jpg", 1453.0), r.didl)
        assertEquals(r.didl, spk.lastMeta)
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
        repeat(6) { t.poll(null) }
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
        repeat(5) { t.poll(null) }
        assertEquals(listOf("stop"), spk.log.filter { !it.startsWith("get") })
        assertTrue(logs.contains("stall retry failed (SpeakerUnreachable)"))
    }

    @Test
    fun `an unknown uid on the play path is the failed-play path's`() = runBlocking {
        val session = SonosSession({ clock.now })
        val t = SonosTransport(session, { uid, verify -> assertTrue(verify); throw UnknownUid(uid) }, NrkService({ _, _ -> "" }), clock.clock)
        try {
            t.play(body)
            fail()
        } catch (e: UnknownUid) {
            assertEquals("RINCON_X", e.uid)
        }
        assertFalse(session.armed)
    }
}
