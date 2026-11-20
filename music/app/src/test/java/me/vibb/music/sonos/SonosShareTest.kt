package me.vibb.music.sonos

import kotlinx.coroutines.runBlocking
import me.vibb.music.logic.QueueRead
import me.vibb.music.logic.Repeat
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 54 round 54-a2: Spotify on a room through the speaker's own queue - the controller's share path (§2.2-§2.11,
 * R2), the look's verdict under a queue marker (QA-2, QA-4), the speaker's sleep timer to the edge, the park's retry.
 * vibb's `tests/sonos_spotify_queue.py` cases where they apply (MIT License, Copyright (c) 2026 palchrb).
 */
class SonosShareTest {
    private val a = "1FakeTrackA00000000001"
    private val c = "1FakeTrackC00000000003"
    private val d = "1FakeTrackD00000000004"
    private val target = "spotify:playlist:0FakePlaylistKveld0001"
    private val spEntry = SonosFakes.entry(id = 9, source = Source.SPOTIFY, target = target, name = "Kveldsmusikk")
    private fun track(key: String) = PlayItem(9, key, "Sang — Artist", null, false, 180_000)
    private val keys = listOf(a, c, d)
    private val spItems = keys.map(::track)

    private fun rig(queue: List<String> = keys) = ControllerRig().apply { spk.shareTracks = queue.map { FakeSpeaker.qTrack(it) } }

    private fun ControllerRig.startShare(index: Int = 0, startMs: Long = 0, list: List<PlayItem> = spItems) {
        c.load(spEntry, list, index, startMs, listed = keys)
        c.playTile()
        work.drain()
    }

    /** The speaker plays queue [position] ([id]) at [rel]. */
    private fun ControllerRig.at(position: Int, id: String, rel: String, transport: String = "PLAYING") {
        spk.position = PositionInfo(position.toString(), FakeSpeaker.qTrack(id), rel, "0:03:00", "<DIDL-Lite><item><dc:title>Ekstra</dc:title><dc:creator>Noen</dc:creator></item></DIDL-Lite>")
        spk.transport = transport
    }

    private fun count(r: ControllerRig, what: String) = r.spk.log.count { it.startsWith(what) }

    @Test
    fun `a transfer adds the link once, lands on its position and track, and a step is one jump - never an add (vibb 1, 3)`() {
        val r = rig()
        r.startShare(index = 1)
        assertEquals(1, count(r, "addqueue 2311"))
        assertTrue("NORMAL first (§2.7)", r.spk.log.contains("playmode NORMAL"))
        assertTrue("the start's position", r.spk.log.contains("seektrack 2"))
        assertEquals(queueUri(r.uid), r.host.played.last().second)
        assertEquals(target, r.host.shared.last().first)
        r.at(2, c, "0:00:01")
        r.poll()
        assertTrue(r.c.showsPlaying(r.now))
        r.c.stepTo(2, 0)
        r.work.drain()
        assertEquals("a step is a jump in the speaker's queue", 1, count(r, "addqueue"))
        assertTrue(r.spk.log.contains("seektrack 3"))
        assertEquals(2, r.c.index)
    }

    @Test
    fun `the speaker's own advance is followed as natural - done, no correction - and a press as pressed (§2_5)`() {
        val r = rig()
        r.startShare(index = 0)
        r.at(1, a, "0:02:58")
        r.poll()
        r.c.takeAuto()
        r.advance(2_500)
        r.at(2, c, "0:00:00")
        r.poll()
        assertEquals(1, r.c.index)
        assertTrue("the finished track is marked done", r.c.takeAuto())
        assertEquals("our next follows: nothing sent", 1, count(r, "seektrack"))
        r.advance(30_000)
        r.at(3, d, "0:00:02")
        r.poll()
        assertEquals(2, r.c.index)
        assertFalse("the speaker's button: no done mark", r.c.takeAuto())
        assertEquals("never corrected", 1, count(r, "seektrack"))
    }

    @Test
    fun `a natural boundary off our order is corrected after the mode read, a mode we didn't set is followed (§2_6, 54-5)`() {
        val r = rig()
        // our order: A, D, C (a flip or shuffle); the speaker's queue: A, C, D
        r.startShare(index = 0, list = listOf(track(a), track(d), track(c)))
        r.at(1, a, "0:02:58")
        r.poll()
        r.advance(2_500)
        r.at(2, c, "0:00:00")
        r.poll()
        assertTrue("GetTransportSettings without a socket", r.spk.log.contains("getplaymode"))
        assertTrue("our next (D) at position 3", r.spk.log.contains("seektrack 3"))
        assertEquals(1, r.c.index)

        val shuffled = rig()
        shuffled.startShare(index = 0, list = listOf(track(a), track(d), track(c)))
        shuffled.spk.playModeValue = "SHUFFLE_NOREPEAT"
        shuffled.at(1, a, "0:02:58")
        shuffled.poll()
        shuffled.advance(2_500)
        shuffled.at(2, c, "0:00:00")
        shuffled.poll()
        assertEquals("a shuffle from the Sonos app is followed", 1, count(shuffled, "seektrack"))
        assertEquals(2, shuffled.c.index)
    }

    @Test
    fun `the belt pauses once per track while playing isn't allowed (§2_9, 54-2)`() {
        val r = rig()
        r.startShare()
        r.at(1, a, "0:00:10")
        r.poll()
        r.allowed = false
        r.advance(5_000)
        r.at(1, a, "0:00:15")
        r.poll()
        r.advance(5_000)
        r.at(1, a, "0:00:20")
        r.poll()
        assertEquals("one Pause", 1, r.spk.commands.count { it == "pause" })
    }

    @Test
    fun `the park stops and clears an unlisted track of our queue - ours by the prefix (54-1)`() {
        val x = "1FakeTrackX00000000009"
        val r = rig(queue = keys + x)
        r.startShare()
        r.at(1, a, "0:00:10")
        r.poll()
        r.advance(60_000)
        r.at(4, x, "0:00:10")
        r.poll()
        assertNotNull("the unmapped look", r.c.unmappedShown)
        assertEquals("the index stays", 0, r.c.index)
        val writes = r.store.writes.size
        r.advance(5_000)
        r.poll()
        assertEquals("an unmapped track writes no bookmark", writes, r.store.writes.size)
        var done = false
        r.c.handOff(park = true) { done = true }
        r.work.drain()
        assertTrue(done)
        assertTrue(r.spk.commands.contains("stop"))
        assertTrue("the queue cleared", r.spk.log.contains("clearqueue"))
        assertFalse(r.c.parkNoAnswer)
    }

    @Test
    fun `the speaker refusing the link is a lost start that asks a grown-up (§2_13)`() {
        val r = rig()
        r.spk.addFaults = mapOf(2311 to { UpnpFault(800, "refused") }, 3079 to { UpnpFault(800, "refused") })
        r.startShare()
        assertTrue(r.c.takeLost() is ShareRefused)
        assertFalse(r.spk.log.any { it.startsWith("seektrack") })
    }

    @Test
    fun `QA-7 a start near a track's end lands on the next track the map names, then follows it as natural`() {
        val r = rig()
        r.startShare(index = 0, startMs = 179_000)
        r.c.takeAuto()
        // the first poll after the answer already reads the next position
        r.advance(1_500)
        r.at(2, c, "0:00:01")
        r.poll()
        assertEquals(1, r.c.index)
        assertTrue(r.c.takeAuto())
        assertEquals(1, count(r, "addqueue"))
    }

    @Test
    fun `an untrusted map makes the next press a transfer (vibb 5)`() {
        val r = rig()
        r.startShare()
        r.at(1, a, "0:00:10")
        r.poll()
        // the Sonos app edited the queue: position 2 plays A again, our map says C
        r.advance(60_000)
        r.at(2, a, "0:00:05")
        r.poll()
        r.c.stepTo(1, 0)
        r.work.drain()
        assertEquals("the heal is a fresh transfer", 2, count(r, "addqueue"))
    }

    @Test
    fun `a pick of an item the queue doesn't hold is refused and nothing changes (§2_3)`() {
        val r = rig(queue = listOf(a, c))
        r.startShare()
        r.at(1, a, "0:00:10")
        r.poll()
        val sent = r.spk.commands.size
        r.c.load(spEntry, spItems, 2, 0, listed = keys)
        r.c.playTile()
        r.work.drain()
        assertTrue(r.c.takeRefused() is NotInQueue)
        assertEquals(sent, r.spk.commands.size)
    }

    @Test
    fun `adopt sends no transport command, reads the map and takes the index from Track (§2_9, vibb adopt)`() {
        val r = rig()
        r.spk.queue += keys.map { FakeSpeaker.qTrack(it) }
        r.at(2, c, "0:01:00")
        val live = runBlocking { r.transport.adoptLive(r.uid, Kind.SPOTIFY_SHARELINK, queueUri(r.uid), 1_500) { queueUri(r.uid) } }!!
        assertTrue(live.ours)
        // adoptIndex's first guess is wrong on purpose: Track says position 2
        r.c.adopt(spEntry, spItems, 0, queueUri(r.uid), live, listed = keys)
        r.work.drain()
        assertTrue("zero transport commands", r.spk.commands.isEmpty())
        assertTrue("the map read", r.spk.log.any { it.startsWith("getbrowse") })
        assertEquals(1, r.c.index)
        assertEquals(Kind.SPOTIFY_SHARELINK, r.session.kind)
    }

    @Test
    fun `the speaker's sleep timer runs to the edge at the transfer and is re-set at a boundary only past 30 s (§2_9 item 4)`() {
        val r = rig()
        r.suspendAtMs = r.wallMs + 180_000
        r.startShare()
        assertTrue(r.spk.log.contains("sleeptimer 0:03:00"))
        r.at(1, a, "0:02:58")
        r.poll()
        r.advance(2_500)
        r.wallMs += 2_500
        r.at(2, c, "0:00:00")
        r.poll()
        assertEquals("within 30 s: nothing sent", 1, count(r, "sleeptimer"))
        r.suspendAtMs += 600_000
        r.c.edgeChanged()
        r.work.drain()
        assertEquals("the edge moved: set again", 2, count(r, "sleeptimer"))
        r.suspendAtMs = 0
        r.spk.sleepRemaining = "0:12:30"
        r.c.edgeChanged()
        r.work.drain()
        assertTrue("no edge: our own cleared", r.spk.log.contains("sleeptimer "))
    }

    @Test
    fun `repeat all past our last item with a queue going on wraps to our first by a jump (§2_7, 54-8)`() {
        val x = "1FakeTrackX00000000009"
        val r = rig(queue = keys + x)
        r.c.repeat = Repeat.ALL
        r.startShare(index = 2)
        r.at(3, d, "0:02:59")
        r.poll()
        assertTrue("the at-end timer armed", r.work.delays.any { it in 0..600 })
        assertTrue("our first item at position 1", r.spk.log.contains("seektrack 1"))
    }

    // -- the look under a queue marker (QA-2, QA-4) --

    private val marker = queueUri("RINCON_KID01400")
    private val choice = RendererChoice(RendererChoice.SONOS, "RINCON_KID01400", "Barnerom", 9, a, marker, shareTarget = target, queueUpdateId = "40")

    private fun look(queue: List<String>, playing: String, position: Int, updateId: Int, ch: RendererChoice = choice): Fields? {
        val r = rig()
        r.spk.queue += queue.map { FakeSpeaker.qTrack(it) }
        r.spk.updateId = updateId
        r.at(position, playing, "0:01:00")
        val base = shareBaselineOf(ch, spEntry, keys)
        return runBlocking { r.transport.adoptLive(r.uid, kindOfUri(marker), marker, 1_500, aux = false, share = base) { null } }
    }

    @Test
    fun `QA-2 roomVerdict under a queue marker is the share classify - never sameStream(marker, TrackURI)`() {
        val x = "1FakeTrackX00000000009"
        assertEquals("our listed track", RoomVerdict.OURS_LIVE, roomVerdict(choice, look(keys, c, 2, 40)))
        assertEquals("an unlisted track of our queue, the version as stored", RoomVerdict.OURS_LIVE, roomVerdict(choice, look(keys + x, x, 4, 40)))
        assertEquals("a queue someone replaced: moved UpdateID and a track in neither list", RoomVerdict.NOT_OURS, roomVerdict(choice, look(listOf(x), x, 1, 77)))
        assertEquals("no baseline stored: vibb's prefix rule", RoomVerdict.OURS_LIVE, roomVerdict(choice.copy(shareTarget = null), look(listOf(x), x, 1, 77, choice.copy(shareTarget = null))))
        assertEquals(RoomVerdict.OURS_STOPPED, roomVerdict(choice, look(keys, c, 2, 40)?.copy(transport = TransportState.STOPPED)))
    }

    @Test
    fun `adoptIndex finds a Spotify queue track by its decoded uri, and QA-4 adopts an unlisted one unmapped at the stored item`() {
        assertEquals(1, adoptIndex(spEntry, spItems, FakeSpeaker.qTrack(c)))
        assertNull(adoptIndex(spEntry, spItems, FakeSpeaker.qTrack("1FakeTrackX00000000009")))
        val plan = adoptPlan(spEntry, spItems, null, false, FakeSpeaker.qTrack("1FakeTrackX00000000009"), storedItem = d)!!
        assertEquals(2, plan.index)
        assertFalse(plan.mapped)
        assertEquals(0, adoptPlan(spEntry, spItems, null, false, FakeSpeaker.qTrack("1FakeTrackX00000000009"), storedItem = "gone")!!.index)
        assertNull("another entry's uri is no adopt", adoptPlan(SonosFakes.entry(), listOf(SonosFakes.item()), null, false, FakeSpeaker.qTrack(c)))
        val base = shareBaselineOf(choice, spEntry, keys)!!
        assertEquals(keys.map { "spotify:track:$it" }, base.listed)
        assertEquals("40", base.updateId)
        assertNull("not a marker", shareBaselineOf(choice.copy(uri = "https://podkast.example/ep1.mp3"), spEntry, keys))
    }

    @Test
    fun `the park's retry tries again only while suspended, within two hours, until a definite verdict (§2_9 item 3)`() {
        val edge = 1_000_000L
        assertTrue(SonosParkRetry.retryAgain(RoomVerdict.NO_ANSWER, suspended = true, nowMs = edge + 60_000, edgeMs = edge))
        assertFalse("a definite verdict ends it", SonosParkRetry.retryAgain(RoomVerdict.OURS_LIVE, true, edge + 60_000, edge))
        assertFalse("no longer suspended", SonosParkRetry.retryAgain(RoomVerdict.NO_ANSWER, false, edge + 60_000, edge))
        assertFalse("past two hours", SonosParkRetry.retryAgain(RoomVerdict.NO_ANSWER, true, edge + SonosParkRetry.RETRY_FOR_MS, edge))
    }

    // -- the transport (R2) --

    @Test
    fun `a jump queued while the read ran looks its position up in the actor, none is NotInQueue (N6)`() = runBlocking {
        val r = rig()
        r.spk.queue += keys.map { FakeSpeaker.qTrack(it) }
        r.session.beginPlay(r.uid, Kind.SPOTIFY_SHARELINK)
        r.session.shareSet(target, keys.map { "spotify:track:$it" })
        r.session.shareRead(QueueRead(3, mapOf(1 to "spotify:track:$a", 2 to "spotify:track:$c", 3 to "spotify:track:$d"), "40"))
        r.session.played(queueUri(r.uid))
        val res = r.transport.queuePlay(null, 0.0, r.uid, listed = keys.map { "spotify:track:$it" }, index = 2)!!
        assertEquals(3, res.share!!.position)
        assertTrue(r.spk.log.contains("seektrack 3"))
        try {
            r.transport.queuePlay(null, 0.0, r.uid, listed = listOf("spotify:track:1FakeTrackX00000000009"), index = 0)
            throw AssertionError("no slot")
        } catch (_: NotInQueue) {
        }
    }
}
