package me.vibb.music.sonos

import me.vibb.music.playback.RendererState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The controller (design 26a §3.2, §4.2-§4.6, §4.8, §5.2) against a recording speaker: ports of
 * palchrb/vibb `tests/sonos_renderer.py` 2-4, `sonos_same_tile.py` 1-7, `sonos_grouped.py` 2-3,
 * `sonos_migrate.py` 1-4 and the non-Storytel checks of `storytel_sonos.py` (the bookmark hold, the
 * regression guard, the duration carry, the seek hold, playpause from STOPPED; MIT, Copyright (c) 2026
 * palchrb), plus the phone's own rules: the §3.2 state table, the hand-off, the step coalescer, the
 * natural advance and the sleep ramp.
 */
class SonosControllerTest {
    private val r = ControllerRig()

    /** Playing ep1 at [rel], measured just now. */
    private fun playing(rel: String = "0:07:11", transport: String = "PLAYING") {
        r.start()
        r.speaker(rel, transport)
        r.poll()
    }

    // -- start (sonos_renderer.py 2) --

    @Test
    fun `a start posts one body to our uid and seeds the snapshot - shown, never bookmarked`() {
        r.start()
        assertEquals(listOf("seturi", "play"), r.spk.commands)
        assertEquals(r.url(0), r.spk.lastUri)
        assertEquals(listOf("ep1" to r.url(0)), r.host.played)
        val spec = r.c.spec(r.now)
        assertEquals("a play in flight reads BUFFERING, playing", PlayPhase.BUFFERING, spec.phase)
        assertTrue(spec.playWhenReady)
        assertTrue("the seed shows playing at once", r.c.showsPlaying(r.now))
        r.c.bookmarkNow(force = true)
        assertTrue("the seed is not a measurement", r.store.writes.isEmpty())
    }

    @Test
    fun `fresh PLAYING - playing, position is rel plus one age term, the speaker's duration`() {
        playing("0:07:11")
        val spec = r.c.spec(r.now)
        assertEquals(PlayPhase.READY, spec.phase)
        assertTrue(spec.playWhenReady)
        assertTrue(r.c.showsPlaying(r.now))
        r.advance(2_000)
        assertEquals(433_000L, r.c.spec(r.now).positionAt(r.now))
        assertEquals(1_453_000L, spec.durationMs)
        assertNull(r.c.rendererState(r.now))
        val w = r.store.writes.single()
        assertEquals("ep1", w.itemKey)
        assertEquals(431_000L, w.posMs)
        assertEquals("the speaker measured it", 1_453_000L, w.durationMs)
    }

    @Test
    fun `stale - not playing, the position kept (never zeroed), UNREACHABLE`() {
        playing("0:07:11")
        r.advance(60_000)
        assertFalse(r.c.showsPlaying(r.now))
        assertEquals(RendererState.UNREACHABLE, r.c.rendererState(r.now))
        val spec = r.c.spec(r.now)
        assertEquals(PlayPhase.READY, spec.phase)
        assertFalse(spec.playWhenReady)
        assertEquals(431_000L, spec.positionAt(r.now))
    }

    // -- the state table (§3.2, QA H2) --

    @Test
    fun `failed polls - the first three read BUFFERING holding playWhenReady, never IDLE, then READY paused`() {
        playing()
        r.c.shown(r.c.spec(r.now))
        r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("gone") })
        for (n in 1..3) {
            r.advance(5_000)
            r.poll()
            val spec = r.c.spec(r.now)
            assertEquals("failure $n", PlayPhase.BUFFERING, spec.phase)
            assertTrue("failure $n holds playWhenReady", spec.playWhenReady)
            r.c.shown(spec)
        }
        r.advance(5_000)
        r.poll()
        val spec = r.c.spec(r.now)
        assertEquals(PlayPhase.READY, spec.phase)
        assertFalse(spec.playWhenReady)
        assertEquals("the last position held", 431_000L, spec.positionAt(r.now))
    }

    @Test
    fun `before a start IDLE at the planned place, the queue's end ENDED`() {
        r.c.load(r.entry, r.items, 1, 42_000)
        val spec = r.c.spec(r.now)
        assertEquals(PlayPhase.IDLE, spec.phase)
        assertEquals(1, spec.index)
        assertEquals(42_000L, spec.positionAt(r.now))
        assertTrue("nothing went to the speaker", r.spk.log.isEmpty())
    }

    // -- not ours (sonos_renderer.py 3b, sonos_grouped.py 2-3) --

    @Test
    fun `a foreign uri is TAKEN_OVER - not playing, its position never shown, no bookmark, no verbs`() {
        playing("0:07:11")
        val before = r.store.writes.size
        r.advance(5_000)
        r.speaker("0:31:00", uri = "x-sonos-spotify:guest")
        r.poll()
        assertEquals(RendererState.TAKEN_OVER, r.c.rendererState(r.now))
        assertFalse(r.c.showsPlaying(r.now))
        assertEquals("the last OURS position", 431_000L, r.c.spec(r.now).positionAt(r.now))
        assertEquals(before, r.store.writes.size)
        val commands = r.spk.commands.size
        r.c.pause()
        r.work.drain()
        assertEquals("pause is ours-gated", commands, r.spk.commands.size)
    }

    @Test
    fun `grouped away is not shadowed by taken over`() {
        playing()
        r.advance(5_000)
        r.speaker("0:20:34", uri = "x-rincon:RINCON_PARENT01400")
        r.poll()
        assertEquals(RendererState.GROUPED_AWAY, r.c.rendererState(r.now))
        assertEquals(431_000L, r.c.spec(r.now).positionAt(r.now))
    }

    @Test
    fun `lost session - STOPPED with an empty uri`() {
        playing()
        r.advance(5_000)
        r.speaker("0:00:00", transport = "STOPPED", uri = "")
        r.poll()
        assertEquals(RendererState.LOST_SESSION, r.c.rendererState(r.now))
    }

    // -- the hand-off (sonos_renderer.py 4, §4.6) --

    @Test
    fun `switching away bookmarks the live position before exactly one stop, and says it was playing`() {
        playing("0:07:11")
        r.advance(4_000)
        r.speaker("0:11:52")
        var wasPlaying: Boolean? = null
        r.c.handOff { wasPlaying = it }
        r.work.drain()
        assertEquals(true, wasPlaying)
        val w = r.store.writes.last()
        assertEquals("the live classify's second", 712_000L, w.posMs)
        assertTrue(w.force)
        assertEquals("written before the stop", r.spk.commands.indexOf("stop"), w.commandsBefore)
        assertEquals(1, r.spk.commands.count { it == "stop" })
        assertTrue(r.c.closed)
        r.c.bookmarkNow(force = true)
        r.c.pause()
        r.work.drain()
        assertEquals("nothing after the hand-off", 1, r.spk.commands.count { it == "stop" })
    }

    @Test
    fun `a paused speaker comes home paused`() {
        playing("0:07:11", transport = "PAUSED_PLAYBACK")
        var wasPlaying: Boolean? = null
        r.c.handOff { wasPlaying = it }
        r.work.drain()
        assertEquals(false, wasPlaying)
    }

    @Test
    fun `a grouped-away speaker is never stopped by the hand-off`() {
        playing()
        r.speaker("0:20:34", uri = "x-rincon:RINCON_PARENT01400")
        r.c.handOff { }
        r.work.drain()
        assertFalse(r.spk.commands.contains("stop"))
    }

    // -- same tile (sonos_same_tile.py) --

    @Test
    fun `same tile - PLAYING sends nothing, PAUSED one resume`() {
        playing()
        r.advance(9_000)
        r.speaker("0:07:20")
        r.poll()
        val before = r.spk.commands.size
        r.c.playTile()
        r.work.drain()
        assertEquals("a press on the playing tile commands nothing", before, r.spk.commands.size)
        r.advance(5_000)
        r.speaker("0:07:25", transport = "PAUSED_PLAYBACK")
        r.poll()
        r.c.playTile()
        r.work.drain()
        assertEquals(listOf("play"), r.spk.commands.drop(before))
        assertTrue("the flip is held", r.c.showsPlaying(r.now))
    }

    @Test
    fun `same tile - a press in flight, a stale snapshot or grouped away is a full start to OUR uid`() {
        r.start()
        r.speaker("0:00:01")
        r.c.playTile() // the play is still settling (pending/optTr): yesterday's truth, a full start
        r.work.drain()
        assertEquals(2, r.spk.commands.count { it == "seturi" })
        r.advance(60_000)
        r.c.playTile()
        r.work.drain()
        assertEquals("stale", 3, r.spk.commands.count { it == "seturi" })
        r.advance(9_000)
        r.speaker("0:20:34", uri = "x-rincon:RINCON_PARENT01400")
        r.poll()
        r.advance(9_000)
        r.poll()
        r.resolvedUids.clear()
        r.c.playTile()
        r.work.drain()
        assertEquals("the reclaim", 4, r.spk.commands.count { it == "seturi" })
        assertEquals(r.uid, r.resolvedUids.first())
    }

    // -- playpause (§4.5) --

    @Test
    fun `playpause - STOPPED starts from the bookmark, PAUSED resumes, PLAYING does nothing`() {
        playing("0:07:11")
        r.advance(9_000)
        r.speaker("0:07:20", transport = "STOPPED")
        r.poll()
        r.c.playPause()
        r.work.drain()
        assertEquals("a STOPPED speaker is started, never blindly resumed", 2, r.spk.commands.count { it == "seturi" })
        assertTrue("from the bookmark", "seek 0:07:11" in r.spk.commands)
        r.advance(9_000)
        r.speaker("0:07:30", transport = "PAUSED_PLAYBACK")
        r.poll()
        val before = r.spk.commands.size
        r.c.playPause()
        r.work.drain()
        assertEquals(listOf("play"), r.spk.commands.drop(before))
        r.advance(9_000)
        r.speaker("0:07:40")
        r.poll()
        val after = r.spk.commands.size
        r.c.playPause()
        r.work.drain()
        assertEquals(after, r.spk.commands.size)
    }

    @Test
    fun `pause - bookmark first, then the verb, the extrapolated position frozen for the screen only`() {
        playing("0:01:40")
        r.advance(4_000)
        r.c.pause()
        assertEquals("bookmarked before the pause", 104_000L, r.store.writes.last().posMs)
        r.work.drain()
        assertEquals("pause", r.spk.commands.last())
        r.advance(3_000)
        val spec = r.c.spec(r.now)
        assertFalse(spec.playWhenReady)
        assertEquals("frozen, not snapped back to the 100 s measurement", 104_000L, spec.positionAt(r.now))
        assertEquals("the snapshot stays measured", 100.0, r.c.snap!!.relS!!, 0.0)
    }

    @Test
    fun `a pause pressed while the play is in flight is kept`() {
        r.start(drain = false)
        r.c.pause()
        r.work.drain()
        assertEquals(listOf("seturi", "play", "pause"), r.spk.commands)
        assertFalse(r.c.spec(r.now).playWhenReady)
    }

    // -- the bookmark (storytel_sonos.py's hold and guard, §4.4) --

    @Test
    fun `the refused-seek hold keeps 3120 over 39 and releases after 120 s of listening`() {
        r.store.saved["ep1"] = 3_120_000
        repeat(3) { r.spk.seekFaults.addLast(UpnpFault(701, null)) }
        r.c.load(r.entry, r.items, 0, 3_120_000)
        r.c.playTile()
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:00:39")
        r.poll()
        assertTrue("held", r.store.writes.isEmpty())
        assertEquals(3_120_000L, r.store.saved["ep1"])
        r.advance(121_000)
        r.speaker("0:02:40")
        r.poll()
        assertEquals("real listening now", 160_000L, r.store.writes.single().posMs)
    }

    @Test
    fun `the regression guard keeps a long place against a restart nobody asked for`() {
        r.store.saved["ep1"] = 3_120_000
        r.c.load(r.entry, r.items, 0, 3_120_000)
        r.c.playTile()
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:00:05")
        r.poll()
        assertTrue(r.store.writes.isEmpty())
        r.c.seek(5_000)
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:00:05")
        r.poll()
        assertEquals("a seek is the user's", 5_000L, r.store.writes.single().posMs)
    }

    @Test
    fun `a deliberate restart is written - next at 0 over a saved place`() {
        playing("0:07:11")
        r.store.saved["ep2"] = 2_400_000
        r.c.stepTo(1, 0)
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:00:05", uri = r.url(1))
        r.poll()
        assertEquals("ep2", r.store.writes.last().itemKey)
        assertEquals(5_000L, r.store.writes.last().posMs)
    }

    @Test
    fun `the duration - the listing's when the speaker says 0, never deciding done - carried only for the same uri`() {
        playing("0:07:11")
        r.advance(5_000)
        r.speaker("0:07:16", dur = "0:00:00")
        r.poll()
        assertEquals(1_453_000L, r.c.spec(r.now).durationMs)
        assertNull("a listing length never decides done", r.store.writes.last().durationMs)
    }

    @Test
    fun `duration carry - falsy never erases, a new uri never inherits`() {
        val noLength = r.items.map { it.copy(durationMs = null) }
        r.c.load(r.entry, noLength, 0, 0)
        r.c.playTile()
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:07:11", dur = "0:20:00")
        r.poll()
        r.advance(5_000)
        r.speaker("0:07:16", dur = "0:00:00")
        r.poll()
        assertEquals(1_200.0, r.c.snap!!.durS!!, 0.0)
        assertEquals("the speaker's own, carried", 1_200_000L, r.store.writes.last().durationMs)
        r.advance(5_000)
        r.speaker("0:00:16", dur = "0:00:00", uri = "x-sonos-spotify:guest")
        r.poll()
        assertEquals("not carried onto another uri", 0.0, r.c.snap!!.durS ?: 0.0, 0.0)
    }

    // -- seek (§4.5) --

    @Test
    fun `seek - clamped short of the end, held for the screen, never in the snapshot nor the store`() {
        playing("0:07:11")
        val writes = r.store.writes.size
        r.c.seek(99_999_000)
        assertEquals("1453 - 5", 1_448_000L, r.c.spec(r.now).positionAt(r.now))
        r.work.drain()
        assertEquals("seek 0:24:08", r.spk.commands.last())
        assertEquals(431.0, r.c.snap!!.relS!!, 0.0)
        assertEquals(writes, r.store.writes.size)
        r.advance(1_000)
        r.speaker("0:07:12")
        r.poll()
        assertEquals("still held while the speaker isn't there", 1_448_000L, r.c.spec(r.now).positionAt(r.now))
        assertEquals("the bookmark is the measurement", 432_000L, r.store.writes.last().posMs)
        r.advance(1_000)
        r.speaker("0:24:05")
        r.poll()
        assertEquals("landed within 6 s: the measurement shows", 1_445_000L, r.c.spec(r.now).positionAt(r.now))
    }

    @Test
    fun `seek - the hold expires after 12 s, and a mash sends the first and the latest`() {
        playing("0:07:11")
        r.c.seek(600_000)
        r.c.seek(700_000)
        r.c.seek(800_000)
        r.work.drain()
        assertEquals(listOf("seek 0:10:00", "seek 0:13:20"), r.spk.commands.filter { it.startsWith("seek") })
        r.advance(13_000)
        r.speaker("0:07:24")
        r.poll()
        assertEquals(444_000L, r.c.spec(r.now).positionAt(r.now))
    }

    @Test
    fun `seek - no duration is refused (live)`() {
        r.c.load(r.entry, r.items.map { it.copy(durationMs = null) }, 0, 0)
        r.c.playTile()
        r.work.drain()
        r.speaker("0:07:11", dur = "0:00:00")
        r.poll()
        r.c.seek(30_000)
        r.work.drain()
        assertFalse(r.spk.commands.any { it.startsWith("seek") })
    }

    // -- next / previous (§4.5) --

    @Test
    fun `a step mash flips the index at once and plays the first and the latest want only`() {
        playing()
        r.c.stepTo(1, 0)
        r.c.stepTo(2, 0)
        assertEquals("optimistic", 2, r.c.index)
        r.work.drain()
        assertEquals(listOf(r.url(0), r.url(1), r.url(2)), r.host.played.map { it.second })
        r.c.stepTo(0, 0)
        r.c.stepTo(1, 0)
        r.c.stepTo(2, 0)
        r.work.drain()
        assertEquals(listOf(r.url(0), r.url(2)), r.host.played.drop(3).map { it.second })
        assertEquals(2, r.c.index)
        assertFalse("never a seek: steps start at 0", r.spk.commands.any { it.startsWith("seek") })
    }

    @Test
    fun `a failed step is dropped and the worker lives on`() {
        playing()
        r.work.failNext = SpeakerUnreachable("burp")
        r.c.stepTo(1, 0)
        r.work.drain()
        assertEquals("back to what the speaker plays", 0, r.c.index)
        assertNull("no revert to the phone for a step", r.c.takeLost())
        r.c.stepTo(2, 0)
        r.work.drain()
        assertEquals(r.url(2), r.host.played.last().second)
    }

    @Test
    fun `a failed start is lost - the service goes back to the phone`() {
        r.spk.failOn = mapOf("seturi" to { UpnpFault(714, null) })
        r.start()
        assertNotNull(r.c.takeLost())
        assertFalse(r.c.session)
        assertEquals(PlayPhase.IDLE, r.c.spec(r.now).phase)
    }

    // -- the natural advance (§4.3) --

    @Test
    fun `an episode that runs off its end plays the next at 0, automatic - the last one stops the queue`() {
        playing("0:23:59")
        r.c.takeAuto()
        r.advance(5_000)
        r.speaker("0:24:13", transport = "STOPPED")
        r.poll()
        assertEquals(1, r.c.index)
        assertTrue(r.c.takeAuto())
        assertEquals(r.url(1), r.host.played.last().second)
        assertFalse(r.spk.commands.last().startsWith("seek"))
        // ep3 runs out: the queue's end
        r.c.stepTo(2, 0)
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:24:00", uri = r.url(2))
        r.poll()
        r.advance(5_000)
        r.speaker("0:24:13", transport = "STOPPED", uri = r.url(2))
        r.poll()
        assertEquals("stop", r.spk.commands.last())
        assertTrue(r.c.takeEnded())
        assertEquals(PlayPhase.ENDED, r.c.spec(r.now).phase)
    }

    @Test
    fun `a stop far from the end is a human and stays stopped`() {
        playing("0:10:00")
        r.advance(5_000)
        r.speaker("0:10:05", transport = "STOPPED")
        r.poll()
        assertEquals(0, r.c.index)
        assertEquals(1, r.host.played.size)
    }

    // -- migration follow (sonos_migrate.py, §4.8) --

    private fun hinted(uid: String = "RINCON_KITCHEN01400", snapUid: String = r.uid, measuredAt: Long = r.now): Fields =
        r.session.snapshot.copy(
            uid = snapUid, transport = TransportState.STOPPED, uri = "", ours = false, measuredAt = measuredAt,
            streamMoved = Moved(uid, "Kjøkken", r.url(0)),
        )

    @Test
    fun `migration follow - adopt first, then renderer json, later verbs to the new uid`() {
        playing()
        r.host.onFollowed = { assertEquals("adopted before the identity write", "RINCON_KITCHEN01400", r.session.uid) }
        r.c.onPoll(Tick.Next(5_000), hinted())
        r.work.drain()
        assertEquals(listOf("RINCON_KITCHEN01400" to "Kjøkken"), r.host.followed)
        assertEquals("RINCON_KITCHEN01400", r.c.uid)
        assertEquals("zero transport commands", listOf("seturi", "play"), r.spk.commands)
        r.c.onPoll(Tick.Next(5_000), r.session.snapshot.copy(uid = "RINCON_KITCHEN01400", ours = true, transport = TransportState.PLAYING, uri = r.url(0), measuredAt = r.now, relS = 50.0))
        r.resolvedUids.clear()
        r.c.pause()
        r.work.drain()
        assertEquals("RINCON_KITCHEN01400", r.resolvedUids.single())
    }

    @Test
    fun `migration follow - same uid, another session, stale or absent hints are no-ops, a failed adopt changes nothing`() {
        playing()
        r.c.onPoll(Tick.Next(5_000), hinted(uid = r.uid))
        r.c.onPoll(Tick.Next(5_000), hinted(snapUid = "RINCON_SOMEONE01400"))
        r.c.onPoll(Tick.Next(5_000), hinted(measuredAt = r.now - 120_000))
        r.c.onPoll(Tick.Next(5_000), hinted().copy(streamMoved = null))
        r.work.drain()
        assertTrue(r.host.followed.isEmpty())
        r.work.failNext = SonosException("adopt failed")
        r.c.onPoll(Tick.Next(5_000), hinted())
        r.work.drain()
        assertTrue(r.host.followed.isEmpty())
        assertEquals(r.uid, r.c.uid)
        r.c.onPoll(Tick.Next(5_000), hinted())
        r.work.drain()
        assertEquals("retried next tick", 1, r.host.followed.size)
    }

    @Test
    fun `a tick that throws never stops the next one`() {
        playing()
        r.store.throwOnSave = IllegalStateException("disk full")
        r.advance(5_000)
        r.speaker("0:07:16")
        r.poll()
        assertTrue(r.host.logs.any { it.startsWith("sonos poll tick failed") })
        r.store.throwOnSave = null
        r.advance(5_000)
        r.speaker("0:07:21")
        r.poll()
        assertEquals(441_000L, r.store.writes.last().posMs)
    }

    // -- volume (§5.3; the cap is 3b) and the sleep ramp (§5.2) --

    @Test
    fun `volume - clamped 0 to 100, a press from our own last set, else the last read`() {
        playing()
        r.c.adjustVolume(3)
        r.work.drain()
        assertEquals("from the read 30", "volume 33", r.spk.commands.last())
        r.c.setVolume(150)
        r.work.drain()
        assertEquals("volume 100", r.spk.commands.last())
        r.c.setVolume(40)
        r.c.adjustVolume(3)
        r.c.adjustVolume(3)
        r.work.drain()
        assertEquals("a burst builds on our own sets", "volume 46", r.spk.commands.last())
        r.c.setVolume(-5)
        r.work.drain()
        assertEquals("volume 0", r.spk.commands.last())
        assertEquals(0, r.c.deviceVolume())
    }

    @Test
    fun `the sleep ramp - three steps 1 s apart, pause, the volume back`() {
        playing()
        var done = false
        r.c.fadeOutAndPause { done = true }
        r.work.drain()
        assertTrue(done)
        assertEquals(listOf("volume 20", "volume 10", "volume 0", "pause", "volume 30"), r.spk.commands.drop(2))
        assertEquals(listOf(1_000L, 1_000L, 1_000L), r.clock.sleeps.takeLast(3))
        assertEquals(listOf(30, null), r.host.ramps)
        assertFalse(r.c.spec(r.now).playWhenReady)
    }

    @Test
    fun `the sleep ramp cancelled puts the volume back and never pauses`() {
        playing()
        r.c.fadeOutAndPause { }
        r.c.cancelFade()
        r.work.drain()
        assertEquals(listOf("volume 30"), r.spk.commands.drop(2))
    }

    @Test
    fun `the player offers the GET commands and the remote volume`() {
        val commands = SONOS_COMMANDS.toSet()
        for (c in listOf(
            androidx.media3.common.Player.COMMAND_GET_CURRENT_MEDIA_ITEM,
            androidx.media3.common.Player.COMMAND_GET_TIMELINE,
            androidx.media3.common.Player.COMMAND_GET_METADATA,
            androidx.media3.common.Player.COMMAND_SET_DEVICE_VOLUME_WITH_FLAGS,
            androidx.media3.common.Player.COMMAND_ADJUST_DEVICE_VOLUME_WITH_FLAGS,
            androidx.media3.common.Player.COMMAND_PLAY_PAUSE,
            androidx.media3.common.Player.COMMAND_SEEK_TO_MEDIA_ITEM,
        )) {
            assertTrue("$c", c in commands)
        }
        assertFalse("no playlist edits from outside", androidx.media3.common.Player.COMMAND_SET_MEDIA_ITEM in commands)
    }
}
