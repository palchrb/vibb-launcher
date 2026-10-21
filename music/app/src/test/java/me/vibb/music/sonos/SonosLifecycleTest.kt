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
        val kept = runBlocking { r.transport.adoptLive(uid, Kind.URL, ep, 1_500) { if (ourLiveSession(choice, it)) ep else null } }
        assertNotNull(kept)
        assertTrue(kept!!.ours)
        assertTrue(r.session.armed)
        assertEquals("only reads", emptyList<String>(), r.spk.commands)
        r.speaker("0:07:11", uri = "x-sonosapi-stream:s1234")
        val foreign = runBlocking { r.transport.adoptLive(uid, Kind.URL, ep, 1_500) { if (ourLiveSession(choice, it)) ep else null } }
        assertFalse(foreign!!.ours)
        assertFalse(r.session.armed)
        r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("down") })
        assertNull(runBlocking { r.transport.adoptLive(uid, Kind.URL, ep, 1_500) { ep } })
        assertFalse(r.session.armed)
        assertEquals(emptyList<String>(), r.spk.commands)
    }

    @Test
    fun `adopted - the queue at the speaker's item, playing on with nothing sent, the listing's duration never deciding done`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = r.url(1), dur = "0:00:00")
        val f = runBlocking { r.transport.adoptLive(uid, Kind.URL, r.url(1), 1_500) { r.url(1) } }!!
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
        val f = runBlocking { r.transport.adoptLive(uid, Kind.URL, r.url(0), 1_500) { r.url(0) } }!!
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
        val verdict = runBlocking {
            suspensionStop(r.transport, choice, 1_500) {
                bookmarked = it
                commandsAtBookmark = r.spk.commands.size
            }
        }
        assertEquals(RoomVerdict.OURS_LIVE, verdict)
        assertEquals(listOf("stop", "seturi"), r.spk.commands)
        assertEquals("the bookmark first", 0, commandsAtBookmark)
        assertEquals(431.0, bookmarked!!.relS!!, 0.0)
        assertEquals("x-rincon-queue:$uid#0", r.spk.lastUri)
        assertEquals("", r.spk.lastMeta)
        assertFalse(r.session.armed)
    }

    @Test
    fun `suspension without the player - a foreign, empty or unreachable speaker is not touched`() {
        for ((what, setUp, expected) in listOf<Triple<String, (ControllerRig) -> Unit, RoomVerdict>>(
            Triple("foreign", { r -> r.speaker("0:01:00", uri = "x-sonosapi-stream:s1234") }, RoomVerdict.NOT_OURS),
            Triple("empty", { r -> r.speaker("0:00:00", transport = "STOPPED", uri = "") }, RoomVerdict.NOT_OURS),
            Triple("unreachable", { r -> r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("down") }) }, RoomVerdict.NO_ANSWER),
        )) {
            val r = ControllerRig(uid)
            setUp(r)
            var bookmarked = false
            assertEquals(what, expected, runBlocking { suspensionStop(r.transport, choice, 1_500) { bookmarked = true } })
            assertFalse(what, bookmarked)
            assertEquals(what, emptyList<String>(), r.spk.commands)
        }
        assertEquals("renderer.json says the phone", RoomVerdict.NOT_OURS, runBlocking { suspensionStop(ControllerRig(uid).transport, RendererChoice.local(choice), 1_500) { } })
    }

    /**
     * The bedtime looks against a speaker whose first [slow] looks get no answer: each spends its
     * whole budget ([spend]; a refused connection spends none). [budgets] = what each resolve was given.
     */
    private inner class Looks(val slow: Int, val spend: Boolean = true) {
        val clock = FakeClock()
        val spk = FakeSpeaker(uid).apply {
            position = PositionInfo("1", ep, "0:07:11", "0:24:13", "<DIDL-Lite/>")
            transport = "PLAYING"
        }
        val budgets = mutableListOf<Long?>()
        val transport = SonosTransport(
            SonosSession({ clock.now }),
            { _, _, budget ->
                budgets.add(budget)
                if (budgets.size <= slow) {
                    if (spend) clock.advance(budget ?: 0)
                    throw SpeakerUnreachable("time budget spent")
                }
                spk
            },
            NrkService({ _, _ -> SonosFakes.fixture("accounts.xml") }),
            clock.clock,
        )

        fun stop(stopMs: Long? = null, bookmark: suspend (Fields) -> Unit = {}): RoomVerdict =
            runBlocking { suspensionStop(transport, choice, 1_500, lookMs = 6_000, clock = clock.clock, stopMs = stopMs, bookmark = bookmark) }
    }

    @Test
    fun `bedtime - a slow speaker's first look is retried within the budget, and it still gets its Stop`() {
        val l = Looks(slow = 1)
        var bookmarked: Fields? = null
        assertEquals(RoomVerdict.OURS_LIVE, l.stop(bookmark = { bookmarked = it }))
        assertEquals("the first look 1.5 s, the next everything left", listOf<Long?>(1_500, 6_000 - 1_500 - 250), l.budgets.take(2))
        assertEquals(431.0, bookmarked!!.relS!!, 0.0)
        assertEquals(listOf("stop", "seturi"), l.spk.commands)
        assertEquals(queueUri(uid), l.spk.lastUri)
        assertTrue("within the looks' budget", l.clock.now - 1_000_000L <= 6_000)
    }

    @Test
    fun `bedtime - the looks read no group and no volume, their budget is the Stop's`() {
        val l = Looks(slow = 0)
        assertEquals(RoomVerdict.OURS_LIVE, l.stop())
        // design 54 §2.9: the park clears the queue (every kind) between the Stop and the queue uri
        assertEquals(listOf("getposition", "gettransport", "stop", "clearqueue", "seturi"), l.spk.log)
    }

    @Test
    fun `bedtime - no answer in the whole budget leaves the room recorded and the speaker untouched`() {
        val slow = Looks(slow = 99)
        assertEquals(RoomVerdict.NO_ANSWER, slow.stop())
        assertEquals("1.5 s, then the rest: no third look without time for it", 2, slow.budgets.size)
        assertTrue(slow.clock.now - 1_000_000L <= 6_000 + 250)
        assertEquals(emptyList<String>(), slow.spk.log)
        val refused = Looks(slow = 99, spend = false)
        assertEquals(RoomVerdict.NO_ANSWER, refused.stop())
        assertEquals("at most three looks", 3, refused.budgets.size)
        assertEquals(listOf(250L, 250L), refused.clock.sleeps)
    }

    @Test
    fun `bedtime - the Stop and the park get what is left of their time, at least half a second (qa L2)`() {
        val l = Looks(slow = 1)
        assertEquals(RoomVerdict.OURS_LIVE, l.stop(stopMs = 8_000))
        assertEquals("1.5 s spent, 0.25 s paused: the park's budget is the rest of 8 s", listOf<Long?>(1_500, 4_250, 6_250), l.budgets)
        val late = Looks(slow = 1)
        assertEquals(RoomVerdict.OURS_LIVE, late.stop(stopMs = 1_000))
        assertEquals("a late answer still gets its Stop a chance", 500L, late.budgets.last())
        assertEquals(listOf("stop", "seturi"), late.spk.commands)
    }

    @Test
    fun `bedtime - a Stop that fails keeps the room recorded for the next look`() {
        val l = Looks(slow = 0)
        l.spk.failOn = mapOf("stop" to { SpeakerUnreachable("time budget spent") })
        var bookmarked = false
        assertEquals(RoomVerdict.NO_ANSWER, l.stop(stopMs = 8_000) { bookmarked = true })
        assertTrue("the place was written first", bookmarked)
    }

    @Test
    fun `bedtime - a definite first answer is not looked at again`() {
        val l = Looks(slow = 0)
        l.spk.position = PositionInfo("1", "x-sonosapi-stream:s1234", "0:01:00", "0:00:00", "")
        assertEquals(RoomVerdict.NOT_OURS, l.stop())
        assertEquals(listOf<Long?>(1_500), l.budgets)
        assertEquals(emptyList<String>(), l.spk.commands)
    }

    @Test
    fun `suspension without the player - our uri on a stopped speaker is parked, with no bookmark (L2)`() {
        // a destroyed service's tail sent its plain Stop: the speaker's Play button would replay ours from 0:00
        val r = ControllerRig(uid)
        r.speaker("0:00:00", transport = "STOPPED", uri = ep)
        var bookmarked = false
        assertEquals(RoomVerdict.OURS_STOPPED, runBlocking { suspensionStop(r.transport, choice, 1_500) { bookmarked = true } })
        assertFalse(bookmarked)
        assertEquals(listOf("stop", "seturi"), r.spk.commands)
        assertEquals(queueUri(uid), r.spk.lastUri)
    }

    @Test
    fun `no built uri stored yet - the speaker playing an item of the stored entry is ours (M1)`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = r.url(2))
        val bare = choice.copy(uri = null)
        val ofEntry: (String) -> Boolean = { u -> adoptIndex(r.entry, r.items, u) != null }
        var bookmarked: Fields? = null
        assertEquals(RoomVerdict.OURS_LIVE, runBlocking { suspensionStop(r.transport, bare, 1_500, ofEntry) { bookmarked = it } })
        assertTrue("the bookmark sees it as ours", bookmarked!!.ours)
        assertEquals(listOf("stop", "seturi"), r.spk.commands)
        val other = ControllerRig(uid)
        other.speaker("0:07:11", uri = "https://elsewhere.example/x.mp3")
        assertEquals(RoomVerdict.NOT_OURS, runBlocking { suspensionStop(other.transport, bare, 1_500, ofEntry) { } })
        assertEquals(emptyList<String>(), other.spk.commands)
    }

    @Test
    fun `adopt with no built uri stored goes on with the speaker's own, ours from then on`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = r.url(1))
        val f = runBlocking { r.transport.adoptLive(uid, Kind.URL, "", 1_500) { it.uri } }!!
        assertTrue(f.ours)
        assertTrue(r.session.armed)
        assertEquals(r.url(1), r.session.uri)
        assertEquals("only reads", emptyList<String>(), r.spk.commands)
    }

    @Test
    fun `the room's verdict - renderer json goes to the phone only on a definite answer (M1)`() {
        assertEquals(RoomVerdict.OURS_LIVE, roomVerdict(choice, live()))
        assertEquals(RoomVerdict.OURS_LIVE, roomVerdict(choice, live(transport = TransportState.PAUSED_PLAYBACK)))
        assertEquals(RoomVerdict.OURS_STOPPED, roomVerdict(choice, live(transport = TransportState.STOPPED)))
        assertEquals(RoomVerdict.NOT_OURS, roomVerdict(choice, live(uri = "x-sonosapi-stream:s1234")))
        assertEquals(RoomVerdict.NOT_OURS, roomVerdict(choice, live(uri = "", transport = TransportState.STOPPED)))
        for (none in listOf(null, live(reachable = false), live(measured = false), live(liveUid = "RINCON_OTHER01400"))) {
            assertEquals("no answer keeps the room recorded", RoomVerdict.NO_ANSWER, roomVerdict(choice, none))
        }
        assertEquals(RoomVerdict.NOT_OURS, roomVerdict(RendererChoice.local(choice), live()))
        assertEquals("no stored uri, nothing of the entry", RoomVerdict.NOT_OURS, roomVerdict(choice.copy(uri = null), live()))
        assertEquals(RoomVerdict.OURS_LIVE, roomVerdict(choice.copy(uri = null), live()) { it == ep })
    }

    @Test
    fun `adopt plan - the stored order and shuffle, the speaker-playable filter, the index by uri, nothing when the item is gone`() {
        val entry = SonosFakes.entry()
        val order = listOf(
            SonosFakes.item(key = "ep1", url = "https://podkast.example/ep1.mp3"),
            SonosFakes.item(key = "own", url = null, fileId = 7),
            SonosFakes.item(key = "ep2", url = "https://podkast.example/ep2.mp3"),
            SonosFakes.item(key = "ep3", url = "https://podkast.example/ep3.mp3"),
        )
        val stored = adoptPlan(entry, order, listOf("ep3", "own", "ep1", "ep2"), true, "https://podkast.example/ep1.mp3")!!
        assertEquals(listOf("ep3", "ep1", "ep2"), stored.keys)
        assertTrue(stored.shuffled)
        assertEquals(1, stored.index)
        val plain = adoptPlan(entry, order, null, true, "https://podkast.example/ep2.mp3")!!
        assertEquals("the entry's play order, own files out", listOf("ep1", "ep2", "ep3"), plain.keys)
        assertFalse("no stored queue, no shuffle", plain.shuffled)
        assertEquals(1, plain.index)
        assertNull("the item left the library", adoptPlan(entry, order, null, false, "https://podkast.example/ep9.mp3"))
        assertNull("a stored key gone, its item not in the order", adoptPlan(entry, order.filter { it.key != "ep2" }, listOf("ep1", "ep2"), false, "https://podkast.example/ep2.mp3"))
    }

    @Test
    fun `suspension without the player - a failed bookmark write still stops, a refused queue uri still stopped`() {
        val r = ControllerRig(uid)
        r.speaker("0:07:11", uri = ep)
        r.spk.failOn = mapOf("seturi" to { UpnpFault(714, null) })
        assertEquals(RoomVerdict.OURS_LIVE, runBlocking { suspensionStop(r.transport, choice, 1_500) { throw IllegalStateException("disk full") } })
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
        r.c.shown(playing)
        assertEquals("re-asserted on every refresh (the holder renews only on these, L3)", listOf(true, true), r.host.locks)
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
        r.c.shown(later)
        assertEquals("false once", listOf(true, true, false), r.host.locks)
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
    fun `volume cap - a louder speaker set from elsewhere during the session is left alone, an up press never pulls it down`() {
        val r = ControllerRig(uid)
        r.capPct = 60
        r.start() // 30: under the cap, the start sets nothing (the start's own clamp: SonosControllerTest)
        r.spk.vol = 90 // from the Sonos app, once our session plays
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

    // -- Storytel (design 26b §5.3; vibb tests/storytel_sonos.py 7, adopt 4527-4570) --

    private val series = SonosFakes.entry(id = 9, source = Source.STORYTEL, target = "storytel:series:113290", name = "Harry")
    private val books = listOf("331854", "331855", "331767").map { SonosFakes.item(key = it, url = null, durationMs = 33_666_000, entryId = 9) }

    /** vibb's ambiguous token: book 331854's URL whose token also holds 331767's digits. */
    private val signed = "https://fastly-ng.storytel.net/mp3encoder-128/uuid?consumableId=331854&isbn=9788&token=aBc331767xyz"
    private val marker = "storytel:book:331854"
    private val bookChoice = RendererChoice(RendererChoice.SONOS, uid, "Barnerom", entryId = 9, itemKey = "331854", uri = marker)

    @Test
    fun `storytel_sonos 7 - the stored marker is ours by the consumableId parameter, never by a substring of the token`() {
        assertEquals(RoomVerdict.OURS_LIVE, roomVerdict(bookChoice, live(uri = signed)))
        assertEquals(RoomVerdict.OURS_LIVE, roomVerdict(bookChoice, live(uri = signed, transport = TransportState.PAUSED_PLAYBACK)))
        assertEquals("our book on a stopped speaker", RoomVerdict.OURS_STOPPED, roomVerdict(bookChoice, live(uri = signed, transport = TransportState.STOPPED)))
        val other = bookChoice.copy(itemKey = "331767", uri = "storytel:book:331767")
        assertEquals("331767 only in the token: not ours", RoomVerdict.NOT_OURS, roomVerdict(other, live(uri = signed)))
        assertEquals("a live URI without consumableId is not ours (§5.3)", RoomVerdict.NOT_OURS, roomVerdict(bookChoice, live(uri = "https://cdn.example.invalid/331854.mp3?token=331854")))
        assertEquals(0, adoptIndex(series, books, signed))
        assertNull(adoptIndex(series, books, "https://cdn.example.invalid/331854.mp3?token=331854"))
        assertNull("gone from the list", adoptIndex(series, books.drop(1), signed))
        // with no uri stored yet, the entry's book by its consumableId (the built-uri match)
        val unstored = bookChoice.copy(uri = null)
        assertEquals(RoomVerdict.OURS_LIVE, roomVerdict(unstored, live(uri = signed)) { adoptIndex(series, books, it) != null })
        assertEquals(AdoptPlan(books.map { it.key }, false, 0), adoptPlan(series, books, null, false, signed))
    }

    @Test
    fun `a Storytel adopt takes the live signed URL for the session - zero commands - and renderer json keeps the marker (M1)`() {
        val r = ControllerRig(uid)
        r.speaker("1:10:00", uri = signed, dur = "0:00:00")
        val f = runBlocking { r.transport.adoptLive(uid, kindOfUri(marker), marker, 1_500) { if (ourLiveSession(bookChoice, it)) it.uri else null } }!!
        assertTrue(f.ours)
        assertEquals("the polls after the adopt are ours by normUri", signed, r.session.uri)
        assertTrue(r.session.armed)
        assertEquals(emptyList<String>(), r.spk.commands)
        r.c.adopt(series, books, 0, signed, f)
        r.advance(15_000)
        r.speaker("1:10:15", uri = signed, dur = "0:00:00")
        r.poll()
        assertTrue("still ours, playing", r.c.showsPlaying(r.now))
        assertEquals(4_215_000L, r.store.writes.last().posMs)
        // the look's rewrite (PlaybackService.lookAtRoom: RendererChoice.adopted), both branches, then the file
        val dir = kotlin.io.path.createTempDirectory("renderer").toFile()
        try {
            val file = java.io.File(dir, "renderer.json")
            for (stored in listOf(bookChoice, bookChoice.copy(uri = null))) {
                val adopted = stored.adopted(series, "331854", signed)
                assertEquals(marker, adopted.uri)
                RendererChoice.write(file, adopted)
                assertFalse("no token on disk", file.readText().contains("token"))
                assertEquals(marker, RendererChoice.read(file).uri)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `every renderer json writer stores a Storytel book's marker, and 1b's built uri for everything else`() {
        val rss = SonosFakes.entry()
        val c = RendererChoice(RendererChoice.SONOS, uid, "Barnerom")
        assertEquals(marker, c.played(series, "331854", signed).uri)
        assertEquals(9L, c.played(series, "331854", signed).entryId)
        assertEquals(ep, c.played(rss, "ep2", ep).uri)
        assertEquals("a follow's hint", marker, bookChoice.followed("RINCON_KITCHEN01400", "Kjøkken", series, "331854", signed).uri)
        assertEquals("no item key: named by the URL's consumableId", marker, bookChoice.followed("RINCON_KITCHEN01400", "Kjøkken", series, null, signed).uri)
        assertEquals("no entry known: kept", marker, bookChoice.followed("RINCON_KITCHEN01400", "Kjøkken", null, null, signed).uri)
        assertEquals("RINCON_KITCHEN01400", bookChoice.followed("RINCON_KITCHEN01400", "Kjøkken", series, null, signed).uid)
        assertEquals("1b: the stored built uri kept", ep, choice.adopted(rss, "ep2", "x-rincon-mp3radio://podkast.example/ep2.mp3").uri)
        assertEquals("1b: none stored, the live one", ep, choice.copy(uri = null).adopted(rss, "ep2", ep).uri)
    }

    @Test
    fun `the standalone suspension stops our book by its marker - bookmark first, Stop, the queue uri`() {
        val r = ControllerRig(uid)
        r.speaker("1:10:00", transport = "PAUSED_PLAYBACK", uri = signed)
        var bookmarked: Fields? = null
        val verdict = runBlocking { suspensionStop(r.transport, bookChoice, 1_500) { bookmarked = it } }
        assertEquals(RoomVerdict.OURS_LIVE, verdict)
        assertEquals(listOf("stop", "seturi"), r.spk.commands)
        assertEquals(4_200.0, bookmarked!!.relS!!, 0.0)
        assertEquals(queueUri(uid), r.spk.lastUri)
        // another book of the series is someone else's stream: left alone
        val r2 = ControllerRig(uid)
        r2.speaker("1:10:00", uri = "https://cdn.example.invalid/x?consumableId=331855&token=FAKE-SIG-1")
        assertEquals(RoomVerdict.NOT_OURS, runBlocking { suspensionStop(r2.transport, bookChoice, 1_500) { error("no bookmark") } })
        assertEquals(emptyList<String>(), r2.spk.commands)
    }
}
