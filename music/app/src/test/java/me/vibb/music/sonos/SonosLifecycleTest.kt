package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Sonos session across the app's lifecycle (design 26a §4.7, §6.1-§6.3): adopt at start (vibb
 * `_sonos_poller`'s reconciliation: re-attach, never re-play), the suspension's Stop and park with and
 * without the player, the full stop of a hand-off, and when the wake and Wi-Fi locks are held.
 */
class SonosLifecycleTest {
    private val uid = "RINCON_KID01400"
    private val ep = "https://podkast.example/ep2.mp3"
    private val choice = RendererChoice(RendererChoice.SONOS, uid, "Barnerom", entryId = 1, itemKey = "ep2", uri = ep)

    private fun live(
        uri: String? = ep,
        transport: TransportState = TransportState.PLAYING,
        reachable: Boolean = true,
        measured: Boolean = true,
        ours: Boolean = uri == ep,
        liveUid: String = uid,
    ) = Fields(
        armed = true, uid = liveUid, kind = Kind.URL, seq = 1, measuredAt = if (measured) 5_000L else null,
        reachable = reachable, transport = transport, relS = 431.0, durS = 1453.0, uri = uri, ours = ours,
    )

    // -- adopt at start (§4.7) --

    @Test
    fun `adopt decision - our stream measured just now, playing or paused, is adopted`() {
        assertTrue(ourLiveSession(choice, live()))
        assertTrue(ourLiveSession(choice, live(transport = TransportState.PAUSED_PLAYBACK)))
        assertTrue("normUri: a re-encoded or re-prefixed uri is ours", ourLiveSession(choice, live(uri = "x-rincon-mp3radio://podkast.example/ep%32.mp3")))
    }

    @Test
    fun `adopt decision - stale, foreign, empty, stopped or another room stays on the phone`() {
        assertFalse("no answer", ourLiveSession(choice, null))
        assertFalse("unreachable", ourLiveSession(choice, live(reachable = false)))
        assertFalse("nothing measured", ourLiveSession(choice, live(measured = false)))
        assertFalse("foreign", ourLiveSession(choice, live(uri = "x-sonosapi-stream:s1234")))
        assertFalse("empty", ourLiveSession(choice, live(uri = "", transport = TransportState.STOPPED)))
        assertFalse("stopped with our uri retained", ourLiveSession(choice, live(transport = TransportState.STOPPED)))
        assertFalse("another room", ourLiveSession(choice, live(liveUid = "RINCON_OTHER01400")))
        assertFalse("renderer.json says the phone", ourLiveSession(RendererChoice.local(choice), live()))
        assertFalse("no stored uri", ourLiveSession(choice.copy(uri = null), live()))
    }

    @Test
    fun `adopt index - by uri equality, an NRK series by its program's service uri`() {
        val entry = SonosFakes.entry()
        val items = (1..3).map { SonosFakes.item(key = "ep$it", url = "https://podkast.example/ep$it.mp3") }
        assertEquals(1, adoptIndex(entry, items, "https://podkast.example/ep%32.mp3"))
        assertNull("gone from the library", adoptIndex(entry, items, "https://podkast.example/ep9.mp3"))
        val serie = SonosFakes.entry(id = 7, source = Source.NRK, target = "https://radio.nrk.no/serie/barnas-supersommer")
        val programs = listOf("MKTT81008181", "MKTT81008281").map { SonosFakes.item(key = it, url = "https://nrk-od.example/$it/master.m3u8", hls = true, entryId = 7) }
        val built = NrkService.programUri("barnas-supersommer", "MKTT81008281", "14")
        assertEquals(1, adoptIndex(serie, programs, built))
        assertEquals(Kind.NRK_PROGRAM, kindOfUri(built))
        assertEquals(Kind.URL, kindOfUri(ep))
    }

    @Test
    fun `adopt sends no transport command - kept, the session runs on - not kept, it is disarmed`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = ep)
        val kept = runBlocking { r.transport.adoptLive(uid, Kind.URL, ep, 1_500) { ourLiveSession(choice, it) } }
        assertNotNull(kept)
        assertTrue(kept!!.ours)
        assertTrue(r.session.armed)
        assertEquals("only reads", emptyList<String>(), r.spk.commands)
        r.speaker("0:07:11", uri = "x-sonosapi-stream:s1234")
        val foreign = runBlocking { r.transport.adoptLive(uid, Kind.URL, ep, 1_500) { ourLiveSession(choice, it) } }
        assertFalse(foreign!!.ours)
        assertFalse(r.session.armed)
        r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("down") })
        assertNull(runBlocking { r.transport.adoptLive(uid, Kind.URL, ep, 1_500) { true } })
        assertFalse(r.session.armed)
        assertEquals(emptyList<String>(), r.spk.commands)
    }

    @Test
    fun `adopted - the queue at the speaker's item, playing on with nothing sent, the listing's duration never deciding done`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = r.url(1), dur = "0:00:00")
        val f = runBlocking { r.transport.adoptLive(uid, Kind.URL, r.url(1), 1_500) { true } }!!
        r.c.adopt(r.entry, r.items, 1, r.url(1), f)
        assertEquals(1, r.c.index)
        assertTrue(r.c.session)
        assertTrue(r.c.showsPlaying(r.now))
        assertEquals("the listing's", 1_453_000L, r.c.spec(r.now).durationMs)
        assertEquals("nothing sent", emptyList<String>(), r.spk.commands)
        r.advance(15_000)
        r.speaker("0:07:26", uri = r.url(1), dur = "0:00:00")
        r.poll()
        val w = r.store.writes.last()
        assertEquals("ep2", w.itemKey)
        assertEquals(446_000L, w.posMs)
        assertNull("a listing length never marks it heard", w.durationMs)
        assertEquals(emptyList<String>(), r.spk.commands)
    }

    @Test
    fun `adopted paused - stays paused, the next play is one resume`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", transport = "PAUSED_PLAYBACK", uri = r.url(0))
        val f = runBlocking { r.transport.adoptLive(uid, Kind.URL, r.url(0), 1_500) { true } }!!
        r.c.adopt(r.entry, r.items, 0, r.url(0), f)
        assertFalse(r.c.showsPlaying(r.now))
        assertFalse(r.c.spec(r.now).playWhenReady)
        r.c.playTile()
        r.work.drain()
        assertEquals("the same tile: one resume, no transfer", listOf("play"), r.spk.commands)
    }

    // -- the suspension (§6.1) --

    @Test
    fun `suspension without the player - bookmark, Stop, the queue uri, disarmed`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", transport = "PAUSED_PLAYBACK", uri = ep)
        var bookmarked: Fields? = null
        var commandsAtBookmark = -1
        val stopped = runBlocking {
            suspensionStop(r.transport, choice, 1_500) {
                bookmarked = it
                commandsAtBookmark = r.spk.commands.size
            }
        }
        assertTrue(stopped)
        assertEquals(listOf("stop", "seturi"), r.spk.commands)
        assertEquals("the bookmark first", 0, commandsAtBookmark)
        assertEquals(431.0, bookmarked!!.relS!!, 0.0)
        assertEquals("x-rincon-queue:$uid#0", r.spk.lastUri)
        assertEquals("", r.spk.lastMeta)
        assertFalse(r.session.armed)
    }

    @Test
    fun `suspension without the player - a foreign, stopped or unreachable speaker is not touched`() {
        for ((what, setUp) in listOf<Pair<String, (ControllerRig) -> Unit>>(
            "foreign" to { r -> r.speaker("0:01:00", uri = "x-sonosapi-stream:s1234") },
            "stopped" to { r -> r.speaker("0:00:00", transport = "STOPPED", uri = ep) },
            "unreachable" to { r -> r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("down") }) },
        )) {
            val r = ControllerRig(uid)
            setUp(r)
            var bookmarked = false
            assertFalse(what, runBlocking { suspensionStop(r.transport, choice, 1_500) { bookmarked = true } })
            assertFalse(what, bookmarked)
            assertEquals(what, emptyList<String>(), r.spk.commands)
        }
        assertFalse("renderer.json says the phone", runBlocking { suspensionStop(ControllerRig(uid).transport, RendererChoice.local(choice), 1_500) { } })
    }

    @Test
    fun `suspension without the player - a failed bookmark write still stops, a refused queue uri still stopped`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = ep)
        r.spk.failOn = mapOf("seturi" to { UpnpFault(714, null) })
        assertTrue(runBlocking { suspensionStop(r.transport, choice, 1_500) { throw IllegalStateException("disk full") } })
        assertEquals(listOf("stop"), r.spk.commands)
        assertFalse(r.session.armed)
    }

    @Test
    fun `the standalone bookmark is the measured second through vibb's guards`() {
        val f = live()
        assertEquals(BookmarkWrite("ep2", 431_000L, 1_453_000L), suspensionBookmark("ep2", f, 0.0, 1_000_000))
        assertNull("the speaker restarted the track", suspensionBookmark("ep2", f.copy(relS = 5.0), 3120.0, 1_000_000))
        assertNull("STOPPED reads 0:00", suspensionBookmark("ep2", f.copy(transport = TransportState.STOPPED), 0.0, 1_000_000))
        assertNull("no item", suspensionBookmark(null, f, 0.0, 1_000_000))
        assertEquals("no age term while PLAYING", 431_000L, suspensionBookmark("ep2", f, 0.0, 9_999_999)!!.posMs)
    }

    @Test
    fun `suspension with the player - the live bookmark, then Stop and the queue uri, ours playing or paused only`() {
        val r = ControllerRig(uid)
        r.start()
        r.speaker("0:07:11")
        r.poll()
        r.advance(4_000)
        r.speaker("0:07:15", transport = "PAUSED_PLAYBACK")
        var done = false
        r.c.handOff(park = true) { done = true }
        r.work.drain()
        assertTrue(done)
        assertEquals(listOf("seturi", "play", "stop", "seturi"), r.spk.commands)
        assertEquals(queueUri(uid), r.spk.lastUri)
        val w = r.store.writes.last()
        assertEquals("the live classify's second", 435_000L, w.posMs)
        assertEquals("written before the stop", r.spk.commands.indexOf("stop"), w.commandsBefore)
        assertTrue(r.c.closed)
    }

    @Test
    fun `suspension with the player - a session that stood down is still parked, someone else's speaker never`() {
        val r = ControllerRig(uid)
        r.start()
        r.speaker("0:07:11", transport = "PAUSED_PLAYBACK")
        r.advance(9_000)
        r.poll()
        r.c.onPoll(Tick.Disarmed, r.session.snapshot.copy(armed = false))
        r.session.disarm() // the poller's own stand-down after 600 s
        r.c.handOff(park = true) { }
        r.work.drain()
        assertEquals("a paused speaker ten minutes on is ours to stop", listOf("seturi", "play", "stop", "seturi"), r.spk.commands)

        val other = ControllerRig(uid)
        other.start()
        other.speaker("0:01:00", uri = "x-sonosapi-stream:s1234")
        other.advance(9_000)
        other.poll()
        other.c.handOff(park = true) { }
        other.work.drain()
        assertEquals("taken over: never stopped", listOf("seturi", "play"), other.spk.commands)
    }

    // -- the full stop (§6.2) --

    @Test
    fun `the service going - the live bookmark, then one ours-gated Stop and no park`() {
        val r = ControllerRig(uid)
        r.start()
        r.speaker("0:07:11")
        r.poll()
        r.advance(3_000)
        r.speaker("0:07:14")
        r.c.handOff { }
        r.work.drain()
        assertEquals(listOf("seturi", "play", "stop"), r.spk.commands)
        assertEquals(434_000L, r.store.writes.last().posMs)
    }

    // -- the wake and Wi-Fi locks (§6.3) --

    @Test
    fun `locks - held from the play while it plays, through the verb window after a pause, then released`() {
        val r = ControllerRig(uid)
        r.start()
        assertTrue("a play in flight", r.c.armedPlaying(r.c.spec(r.now), r.now))
        r.speaker("0:07:11")
        r.poll()
        val playing = r.c.spec(r.now)
        assertTrue(r.c.armedPlaying(playing, r.now))
        r.c.shown(playing)
        assertEquals(listOf(true), r.host.locks)
        r.advance(60_000)
        r.speaker("0:08:11")
        r.poll()
        assertTrue("playing past the verb window", r.c.armedPlaying(r.c.spec(r.now), r.now))
        r.c.pause()
        r.work.drain()
        val paused = r.c.spec(r.now)
        assertFalse(paused.playWhenReady)
        assertTrue("the pause's confirming polls", r.c.armedPlaying(paused, r.now))
        r.advance(SonosSession.VERB_FAST_MS)
        r.speaker("0:08:11", transport = "PAUSED_PLAYBACK")
        r.poll()
        val later = r.c.spec(r.now)
        assertFalse("paused, the window over", r.c.armedPlaying(later, r.now))
        r.c.shown(later)
        assertEquals(listOf(true, false), r.host.locks)
    }

    @Test
    fun `locks - a transient failure keeps them, disarm, a hand-off and close release them`() {
        val r = ControllerRig(uid)
        r.start()
        r.speaker("0:07:11")
        r.poll()
        r.c.shown(r.c.spec(r.now))
        r.advance(SonosSession.VERB_FAST_MS)
        r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("blip") })
        r.poll()
        assertTrue("one failed poll is a transient (§3.2)", r.c.armedPlaying(r.c.spec(r.now), r.now))
        r.spk.failOn = emptyMap()
        r.c.shown(r.c.spec(r.now))
        r.c.onPoll(Tick.Disarmed, r.session.snapshot.copy(armed = false))
        assertFalse("disarmed", r.c.armedPlaying(r.c.spec(r.now), r.now))

        val h = ControllerRig(uid)
        h.start()
        h.c.shown(h.c.spec(h.now))
        h.c.handOff { }
        h.work.drain()
        assertEquals("the switch home releases at once", listOf(true, false), h.host.locks)

        val c = ControllerRig(uid)
        c.start()
        c.c.shown(c.c.spec(c.now))
        c.c.close()
        assertEquals(listOf(true, false), c.host.locks)
        assertFalse(c.c.armedPlaying(c.c.spec(c.now), c.now))
    }

    // -- the parent's volume cap (§5.3) --

    @Test
    fun `volume cap - our own sets never above it, an up press at the cap sends nothing`() {
        val r = ControllerRig(uid)
        r.capPct = 60
        r.start()
        r.speaker("0:07:11")
        r.poll()
        r.c.setVolume(80)
        r.work.drain()
        assertEquals("the slider clamped", "volume 60", r.spk.commands.last())
        val sent = r.spk.commands.size
        r.c.adjustVolume(3)
        r.work.drain()
        assertEquals("at the cap: nothing", sent, r.spk.commands.size)
        r.c.adjustVolume(-3)
        r.c.adjustVolume(-3)
        r.c.adjustVolume(3)
        r.work.drain()
        assertEquals(listOf("volume 57", "volume 54", "volume 57"), r.spk.commands.takeLast(3))
        r.capPct = null
        r.c.setVolume(80)
        r.work.drain()
        assertEquals("the cap lifted (the override, the pause): read live", "volume 80", r.spk.commands.last())
    }

    @Test
    fun `volume cap - a louder speaker set from elsewhere is left alone, an up press never pulls it down`() {
        val r = ControllerRig(uid)
        r.capPct = 60
        r.spk.vol = 90 // from the Sonos app
        r.start()
        r.speaker("0:07:11")
        r.poll()
        r.advance(15_000)
        r.poll()
        assertEquals(90, r.c.deviceVolume())
        assertFalse("no poll fights it", r.spk.commands.any { it.startsWith("volume") })
        r.c.adjustVolume(3)
        r.work.drain()
        assertFalse("up: nothing", r.spk.commands.any { it.startsWith("volume") })
        r.c.adjustVolume(-3)
        r.work.drain()
        assertEquals("a press down from 90 is our own set: at most the cap", "volume 60", r.spk.commands.last())
    }

    @Test
    fun `volume target - 0 to 100, at most a cap of 1 to 99`() {
        assertEquals(60, volumeTarget(80, 60))
        assertEquals(40, volumeTarget(40, 60))
        assertEquals(100, volumeTarget(150, null))
        assertEquals(0, volumeTarget(-5, 60))
        assertEquals("0 or 100 is no cap", 80, volumeTarget(80, 0))
        assertEquals(80, volumeTarget(80, 100))
    }
}
