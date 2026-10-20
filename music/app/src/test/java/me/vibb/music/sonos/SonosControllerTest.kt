package me.vibb.music.sonos

import me.vibb.music.logic.Repeat
import me.vibb.music.model.Source
import me.vibb.music.playback.RendererState
import me.vibb.music.storytel.Mint
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
 * natural advance and the sleep ramp; and Storytel on a room (design 26b §5.1-§5.4, step 4b): vibb
 * `tests/storytel_sonos.py` 3, 4, 5, 10, 13 and 14 with a fake minter, and the job's token (QA M6).
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
        assertEquals("someone else's position is never shown", 0L, r.c.spec(r.now).positionAt(r.now))
        assertNull("nor its duration", r.c.spec(r.now).durationMs)
        assertEquals(before, r.store.writes.size)
        val commands = r.spk.commands.size
        r.c.pause()
        r.work.drain()
        assertEquals("pause is ours-gated", commands, r.spk.commands.size)
    }

    @Test
    fun `grouped 2 - grouped away is not shadowed by taken over, and the parent's position is never painted`() {
        playing()
        r.advance(5_000)
        r.speaker("0:20:34", uri = "x-rincon:RINCON_PARENT01400", dur = "1:06:40")
        r.poll()
        assertEquals(RendererState.GROUPED_AWAY, r.c.rendererState(r.now))
        assertFalse(r.c.showsPlaying(r.now))
        val spec = r.c.spec(r.now)
        assertEquals("the member's RelTime is the PARENT's (vibb: position None)", 0L, spec.positionAt(r.now))
        assertFalse(spec.moving)
        assertNull("nor the parent's duration", spec.durationMs)
        assertEquals(PlayPhase.READY, spec.phase)
        assertFalse(spec.playWhenReady)
        r.advance(60_000) // no poll since: stale - the last ours values, never zeroed (§3.3)
        assertEquals(RendererState.UNREACHABLE, r.c.rendererState(r.now))
        assertEquals(431_000L, r.c.spec(r.now).positionAt(r.now))
        assertEquals(1_453_000L, r.c.spec(r.now).durationMs)
        r.speaker("0:07:30") // ours again (the household ungrouped it)
        r.poll()
        assertEquals(450_000L, r.c.spec(r.now).positionAt(r.now))
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
        r.advance(9_000) // past our own play's 8 s hold
        r.poll()
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

    /**
     * vibb's `steady()`: our stream measured just now, nothing settling, the press would be trusted -
     * then each check below breaks exactly one thing, so its guard is the only reason for the full start.
     */
    private fun steady(transport: String = "PLAYING") {
        r.start()
        r.advance(9_000) // past our own play's 8 s hold
        r.speaker("0:07:20", transport)
        r.poll()
        assertEquals(listOf("seturi", "play"), r.spk.commands)
        assertTrue("steady: fresh and ours", r.c.snap!!.fresh(r.now) && r.c.snap!!.ours)
    }

    private fun seturis() = r.spk.commands.count { it == "seturi" }

    @Test
    fun `same tile 3b - the shown item isn't the speaker's (a skip while paused) - the full start is the heal`() {
        steady("PAUSED_PLAYBACK")
        r.c.stepTo(1, 0)
        r.work.drain()
        assertEquals("the skip only moved where the next play starts", 1, seturis())
        r.c.playTile()
        r.work.drain()
        assertEquals("a full start of the shown item, not a resume of the old one", 2, seturis())
        assertEquals(r.url(1), r.spk.lastUri)
    }

    @Test
    fun `same tile 4 - a press while our own play or verb settles falls through to the full start`() {
        steady("PAUSED_PLAYBACK")
        r.c.playPause() // our resume: optTr PLAYING, held against the next polls
        r.work.drain()
        r.advance(2_000)
        r.poll() // the speaker still says PAUSED: our own guess, not its truth
        r.c.playTile()
        r.work.drain()
        assertEquals("the optimistic hold is no steady state", 2, seturis())
        val q = ControllerRig()
        q.start()
        q.advance(9_000)
        q.speaker("0:07:20")
        q.poll()
        q.c.stepTo(1, 0) // a step's play in flight (pending)
        q.c.playTile()
        q.work.drain()
        assertEquals("mid-jump the snapshot may describe the OLD track", 3, q.spk.commands.count { it == "seturi" })
    }

    @Test
    fun `same tile 5 - a stale snapshot falls through to the full start`() {
        steady()
        r.c.playTile()
        r.work.drain()
        assertEquals("steady PLAYING: nothing", 1, seturis())
        r.advance(60_000) // no poll since: stale
        r.c.playTile()
        r.work.drain()
        assertEquals("stale 'playing' may be long over", 2, seturis())
    }

    @Test
    fun `same tile 6 - an explicit pick or a start from the top always starts`() {
        steady()
        r.c.load(r.entry, r.items, 2, 0) // a tap on an episode in the list
        r.c.playTile()
        r.work.drain()
        assertEquals(2, seturis())
        assertEquals(r.url(2), r.spk.lastUri)
        r.advance(9_000)
        r.speaker("0:03:00", uri = r.url(2))
        r.poll()
        r.c.load(r.entry, r.items, 2, 0) // "from the start" on the episode that plays
        r.c.playTile()
        r.work.drain()
        assertEquals(3, seturis())
    }

    @Test
    fun `same tile 7 - grouped away, the press reclaims OUR uid, never the coordinator`() {
        steady()
        r.advance(5_000)
        r.speaker("0:20:34", uri = "x-rincon:RINCON_PARENT01400")
        r.poll()
        r.resolvedUids.clear()
        r.c.playTile()
        r.work.drain()
        assertEquals("the reclaim", 2, seturis())
        assertEquals(r.uid, r.resolvedUids.first())
        assertTrue(r.resolvedUids.none { it == "RINCON_PARENT01400" })
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
        assertEquals("ep2 went out, superseded: only what landed last is ours", listOf(r.url(0), r.url(2)), r.host.played.map { it.second })
        assertEquals(3, r.spk.commands.count { it == "seturi" })
        r.c.stepTo(0, 0)
        r.c.stepTo(1, 0)
        r.c.stepTo(2, 0)
        r.work.drain()
        assertEquals("the first want and the latest", 5, r.spk.commands.count { it == "seturi" })
        assertEquals(listOf(r.url(2)), r.host.played.drop(2).map { it.second })
        assertEquals(2, r.c.index)
        assertFalse("never a seek: steps start at 0", r.spk.commands.any { it.startsWith("seek") })
    }

    @Test
    fun `d39 - a flip while ep2 plays reorders the queue, sends the speaker nothing, and next goes the shown way`() {
        r.start(index = 1)
        r.speaker("0:03:00")
        r.poll()
        val sent = r.spk.commands.toList()
        // the service's flippedQueue: oldest first ep1 ep2 ep3 -> newest first from ep2: ep2 ep1 ep3
        r.c.reorder(1, listOf(r.items[0], r.items[2]))
        assertEquals(listOf("ep2", "ep1", "ep3"), r.c.items.map { it.key })
        assertEquals(0, r.c.index)
        assertEquals("the speaker plays on: nothing sent", sent, r.spk.commands)
        assertTrue(r.c.showsPlaying(r.now))
        r.c.stepTo(1, 0)
        r.work.drain()
        assertEquals("next is ep1, the shown direction", r.url(0), r.host.played.last().second)
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
    fun `d50 - Til slutten holds at the next item at 0 when the episode runs off its end, once`() {
        playing("0:23:59")
        var held = 0
        r.c.holdAtEnd = { held++ }
        val plays = r.host.played.size
        r.advance(5_000)
        r.speaker("0:24:13", transport = "STOPPED")
        r.poll()
        r.work.drain()
        assertEquals("the speaker held at the end: nothing more played", plays, r.host.played.size)
        assertEquals("the next item waits at 0, as for a play that isn't allowed", 1, r.c.index)
        assertEquals(1, held)
        assertNull("run once, then off", r.c.holdAtEnd)
        // the next play starts that item at 0
        r.c.playTile()
        r.work.drain()
        assertEquals(r.url(1), r.spk.lastUri)
    }

    @Test
    fun `d49 - repeat all wraps to the first item at the queue's end, repeat one plays the item again - no Stop, no end`() {
        r.c.repeat = Repeat.ALL
        playing("0:23:59")
        r.c.stepTo(2, 0)
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:24:00", uri = r.url(2))
        r.poll()
        r.advance(5_000)
        r.c.takeAuto()
        r.speaker("0:24:13", transport = "STOPPED", uri = r.url(2))
        r.poll()
        r.work.drain()
        assertEquals(0, r.c.index)
        assertEquals("a new URI set + Play of the first", r.url(0), r.host.played.last().second)
        assertTrue("the wrap is the natural advance (the last one done)", r.c.takeAuto())
        assertFalse(r.c.takeEnded())
        assertFalse(r.spk.commands.contains("stop"))
        r.c.repeat = Repeat.ONE
        val plays = r.host.played.size
        r.advance(5_000)
        r.speaker("0:24:00", uri = r.url(0))
        r.poll()
        r.advance(5_000)
        r.speaker("0:24:13", transport = "STOPPED", uri = r.url(0))
        r.poll()
        r.work.drain()
        assertEquals(0, r.c.index)
        assertEquals("the same item again", plays + 1, r.host.played.size)
        assertEquals(r.url(0), r.host.played.last().second)
        assertFalse("the same item: no item change, no done mark", r.c.takeAuto())
        assertFalse(r.c.takeEnded())
    }

    @Test
    fun `d50 - Til slutten beats repeat one - held at the same item at 0, nothing played again`() {
        r.c.repeat = Repeat.ONE
        playing("0:23:59")
        var held = 0
        r.c.holdAtEnd = { held++ }
        val plays = r.host.played.size
        r.advance(5_000)
        r.speaker("0:24:13", transport = "STOPPED")
        r.poll()
        r.work.drain()
        assertEquals("no replay", plays, r.host.played.size)
        assertEquals(0, r.c.index)
        assertEquals(1, held)
        assertFalse(r.c.takeEnded())
    }

    @Test
    fun `d50 - Til slutten beats repeat all - held at the first item at 0 after the last, no wrap played`() {
        r.c.repeat = Repeat.ALL
        playing("0:23:59")
        r.c.stepTo(2, 0)
        r.work.drain()
        r.advance(5_000)
        r.speaker("0:24:00", uri = r.url(2))
        r.poll()
        var held = 0
        r.c.holdAtEnd = { held++ }
        val plays = r.host.played.size
        r.advance(5_000)
        r.speaker("0:24:13", transport = "STOPPED", uri = r.url(2))
        r.poll()
        r.work.drain()
        assertEquals("no wrap played", plays, r.host.played.size)
        assertEquals(0, r.c.index)
        assertEquals(1, held)
        assertFalse(r.c.takeEnded())
        assertFalse(r.spk.commands.contains("stop"))
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

    private fun hinted(uid: String = "RINCON_KITCHEN01400", snapUid: String = r.uid, measuredAt: Long = r.now, uri: String = r.url(0)): Fields =
        r.session.snapshot.copy(
            uid = snapUid, transport = TransportState.STOPPED, uri = "", ours = false, measuredAt = measuredAt,
            streamMoved = Moved(uid, "Kjøkken", uri),
        )

    @Test
    fun `migration follow - adopt first with the hint's uri, then renderer json, the volume base reset, later verbs to the new uid`() {
        playing()
        r.c.setVolume(40)
        r.work.drain()
        assertEquals(40, r.c.volOpt)
        // vibb's HINT: the uri the other coordinator plays, not necessarily the one we set (a signed CDN url)
        val hintUri = "https://cdn.example/signed/ep1.mp3?tok=abc"
        assertTrue(hintUri != r.session.uri)
        r.host.onFollowed = {
            assertEquals("adopted before the identity write", "RINCON_KITCHEN01400", r.session.uid)
            assertEquals("adopted with the hint's uri", hintUri, r.session.uri)
        }
        r.c.onPoll(Tick.Next(5_000), hinted(uri = hintUri))
        r.work.drain()
        assertEquals(listOf("RINCON_KITCHEN01400" to "Kjøkken"), r.host.followed)
        assertEquals("RINCON_KITCHEN01400", r.c.uid)
        assertNull("new speaker, new volume world", r.c.volOpt)
        assertEquals("zero transport commands", listOf("seturi", "play", "volume 40"), r.spk.commands)
        r.c.onPoll(Tick.Next(5_000), r.session.snapshot.copy(uid = "RINCON_KITCHEN01400", ours = true, transport = TransportState.PLAYING, uri = hintUri, measuredAt = r.now, relS = 450.0))
        assertEquals("the bookmark follows the hint's uri", 450_000L, r.store.writes.last().posMs)
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

    // -- volume (§5.3; the cap: SonosLifecycleTest) and the sleep ramp (§5.2) --

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

    // -- the cap at a session's start (§5.3, correction 2026-10-12) --

    @Test
    fun `a start brings a louder speaker down to the cap once, before Play`() {
        r.capPct = 40
        r.spk.vol = 70 // left loud by the last listener
        r.start()
        assertEquals(listOf("volume 40", "seturi", "play"), r.spk.commands)
        assertTrue("read before it was set", r.spk.log.indexOf("getvolume") < r.spk.log.indexOf("volume 40"))
        assertEquals("the base of the next press", 40, r.c.volOpt)
        assertEquals(40, r.c.deviceVolume())
        assertEquals(40, r.session.snapshot.volume)
        r.c.adjustVolume(-3)
        r.work.drain()
        assertEquals("volume 37", r.spk.commands.last())
    }

    @Test
    fun `a start from a bookmark caps inside the hush`() {
        r.capPct = 40
        r.spk.vol = 70
        r.start(startMs = 720_000)
        assertEquals(listOf("mute true", "volume 40", "seturi", "play", "seek 0:12:00", "mute false"), r.spk.commands)
    }

    @Test
    fun `no cap, or a speaker at or below it - the start reads and sets nothing more`() {
        r.spk.vol = 90
        r.start()
        assertEquals("no cap: not even read", listOf("clearqueue", "seturi", "play"), r.spk.log)
        assertNull(r.c.volOpt)
        val q = ControllerRig()
        q.capPct = 40
        q.spk.vol = 40
        q.start()
        assertEquals(listOf("getvolume", "clearqueue", "seturi", "play"), q.spk.log)
        assertNull(q.c.volOpt)
    }

    @Test
    fun `the start cap is once per session - a step or an advance never pulls a louder speaker down`() {
        r.capPct = 40
        r.start()
        r.spk.vol = 80 // from the Sonos app while ours plays
        r.speaker("0:07:11")
        r.poll()
        assertEquals("the louder volume shows", 80, r.c.deviceVolume())
        val reads = r.spk.log.count { it == "getvolume" }
        r.c.stepTo(1, 0)
        r.work.drain()
        assertEquals("the step's play went out", 2, r.spk.commands.count { it == "play" })
        assertEquals("the step reads no volume", reads, r.spk.log.count { it == "getvolume" })
        assertFalse(r.spk.commands.any { it.startsWith("volume") })
    }

    @Test
    fun `a speaker that won't say its volume still plays`() {
        r.capPct = 40
        r.spk.failOn = mapOf("getvolume" to { SpeakerUnreachable("slow") })
        r.start()
        assertEquals(listOf("seturi", "play"), r.spk.commands)
        assertTrue(r.c.showsPlaying(r.now))
    }

    @Test
    fun `M1 - a cap lowered during the session pulls our own louder set down once`() {
        r.start()
        r.speaker("0:07:11")
        r.poll()
        r.c.setVolume(70) // no cap in force (the override): our own set
        r.work.drain()
        r.capPct = 40 // the override ended: the restrictions broadcast
        r.c.capChanged()
        r.work.drain()
        assertEquals("volume 40", r.spk.commands.last())
        assertEquals(40, r.c.volOpt)
        val sent = r.spk.commands.size
        r.c.capChanged()
        r.work.drain()
        assertEquals("once", sent, r.spk.commands.size)
        r.capPct = null
        r.c.capChanged()
        r.work.drain()
        assertEquals("a lifted cap sends nothing", sent, r.spk.commands.size)
    }

    @Test
    fun `M1 - a louder volume from the Sonos app, ours at or below the cap, or no session - the cap edge sends nothing`() {
        r.start()
        r.spk.vol = 80 // from the Sonos app
        r.speaker("0:07:11")
        r.poll()
        r.capPct = 40
        r.c.capChanged()
        r.work.drain()
        assertFalse("vibb never fights an external change", r.spk.commands.any { it.startsWith("volume") })
        r.c.setVolume(30)
        r.work.drain()
        r.capPct = 35
        r.c.capChanged()
        r.work.drain()
        assertEquals(1, r.spk.commands.count { it.startsWith("volume") })
        r.c.setVolume(35)
        r.c.stop()
        r.work.drain()
        r.capPct = 20
        r.c.capChanged()
        r.work.drain()
        assertEquals("no session: nothing", "stop", r.spk.commands.last())
    }

    @Test
    fun `M2 - a start caps every room of the group, inside the hush, an unknown or silent member never blocks it`() {
        val shared = mutableListOf<String>()
        val coord = FakeSpeaker("RINCON_KJOKKEN01400").apply { vol = 70; this.shared = shared }
        val stua = FakeSpeaker("RINCON_STUA01400").apply { vol = 80; this.shared = shared }
        val bad = FakeSpeaker("RINCON_BAD01400").apply { vol = 30; this.shared = shared }
        val sleepy = FakeSpeaker("RINCON_SLEEPY01400").apply { failOn = mapOf("getvolume" to { SpeakerUnreachable("slow") }); this.shared = shared }
        val byUid = listOf(coord, stua, bad, sleepy).associateBy { it.uid }
        val clock = FakeClock()
        val session = SonosSession({ clock.now })
        val transport = SonosTransport(session, { u, _, _ -> byUid[u] ?: throw UnknownUid(u) }, NrkService({ _, _ -> SonosFakes.fixture("accounts.xml") }), clock.clock)
        val work = FakeWork()
        val members = listOf(coord.uid, stua.uid, "RINCON_GONE01400", bad.uid, sleepy.uid)
        val c = SonosController(transport, work, FakeStore(coord), { clock.now }, coord.uid, "Kjøkken + Stua", FakeHost(), sleep = clock.clock.sleep, cap = { 40 }, members = { members })
        c.load(r.entry, r.items, 0, 720_000)
        c.playTile()
        work.drain()
        assertEquals(
            listOf(
                "${coord.uid} getmute", "${coord.uid} mute true", "${coord.uid} getvolume", "${coord.uid} volume 40",
                "${stua.uid} getvolume", "${stua.uid} volume 40", "${bad.uid} getvolume", "${coord.uid} clearqueue", "${coord.uid} seturi",
                "${coord.uid} play", "${coord.uid} gettransport", "${coord.uid} mute false",
            ),
            shared,
        )
        assertEquals("the coordinator's is the base of the next press", 40, c.volOpt)
        // a step inside the session caps no one
        shared.clear()
        c.stepTo(1, 0)
        work.drain()
        assertTrue(shared.none { it.endsWith("getvolume") || it.contains(" volume ") })
    }

    @Test
    fun `L1 - a ramp's leftover put back below the cap is the base of the next press`() {
        val f = ControllerRig()
        f.spk.vol = 80
        val c = SonosController(f.transport, f.work, f.store, { f.clock.now }, f.uid, "Barnerom", f.host, sleep = f.clock.clock.sleep, restoreVolume = 30, cap = { 50 })
        c.load(f.entry, f.items, 0, 0)
        c.playTile()
        f.work.drain()
        assertEquals(listOf("volume 50", "seturi", "play", "volume 30"), f.spk.commands)
        assertEquals("what the speaker holds", 30, c.volOpt)
        assertEquals(30, c.deviceVolume())
        c.adjustVolume(-3)
        f.work.drain()
        assertEquals("volume 27", f.spk.commands.last())
    }

    @Test
    fun `L1 - a key press while the start is in flight stands over the start's cap`() {
        r.capPct = 40
        r.spk.vol = 70
        r.c.load(r.entry, r.items, 0, 0)
        r.c.playTile()
        r.c.setVolume(20) // the slider, before the play's result came back
        r.work.drain()
        assertEquals(listOf("volume 40", "seturi", "play", "volume 20"), r.spk.commands)
        assertEquals(20, r.c.volOpt)
    }

    @Test
    fun `a ramp's leftover volume is put back no louder than the cap`() {
        val f = ControllerRig()
        val c = SonosController(f.transport, f.work, f.store, { f.clock.now }, f.uid, "Barnerom", f.host, sleep = f.clock.clock.sleep, restoreVolume = 70, cap = { 50 })
        c.load(f.entry, f.items, 0, 0)
        c.playTile()
        f.work.drain()
        assertEquals(listOf("seturi", "play", "volume 50"), f.spk.commands)
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
    fun `the sleep ramp cancelled puts the volume back, never pauses and never reports the timer's end (M3)`() {
        playing()
        var done = false
        r.c.fadeOutAndPause { done = true }
        r.c.cancelFade()
        r.work.drain()
        assertEquals(listOf("volume 30"), r.spk.commands.drop(2))
        assertFalse("a new timer set during the ramp survives it", done)
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

    // -- the instant switch (logic/Switch.kt, the merge of main 2026-10-12) --

    @Test
    fun `a step on a playing room shows the target at once, from 0 - the screens' skip switch counts on both`() {
        playing()
        val changes = r.host.changes
        r.c.stepTo(1, 42_000) // the phone's resume place for ep2
        // before the speaker has answered anything: the index the service publishes is the target
        assertEquals(1, r.c.index)
        assertTrue("the service is told at once", r.host.changes > changes)
        r.work.drain()
        assertEquals(r.url(1), r.spk.lastUri)
        assertFalse("a step on a playing room starts from 0, not the resume place", r.spk.commands.any { it.startsWith("seek") })
        assertEquals(0L, r.c.spec(r.now).positionAt(r.now))
    }

    // -- the fix round (qa-26a-step3a-code) --

    @Test
    fun `H1 - a skip on a paused speaker only moves where the next play starts`() {
        playing("0:07:11", transport = "PAUSED_PLAYBACK")
        r.advance(9_000)
        r.poll()
        val before = r.spk.commands.size
        r.c.stepTo(1, 42_000)
        r.work.drain()
        assertEquals("nothing went to the speaker", before, r.spk.commands.size)
        assertEquals(1, r.c.index)
        assertEquals(42_000L, r.c.spec(r.now).positionAt(r.now))
        assertFalse(r.c.spec(r.now).playWhenReady)
        r.c.playPause()
        r.work.drain()
        assertEquals("the play starts the picked item at its place", r.url(1), r.spk.lastUri)
        assertTrue("seek 0:00:42" in r.spk.commands)
    }

    @Test
    fun `H1 - while nothing may play, neither a skip nor a start reaches the speaker`() {
        playing()
        r.allowed = false
        val before = r.spk.commands.size
        r.c.stepTo(1, 0)
        r.c.playTile()
        r.work.drain()
        assertEquals(before, r.spk.commands.size)
        val fresh = ControllerRig().apply { allowed = false }
        fresh.start()
        assertTrue(fresh.spk.commands.isEmpty())
    }

    @Test
    fun `H2a - a failed start while our session plays is refused - the renderer stays, the index back, no Lost`() {
        playing("0:07:11")
        r.spk.failOn = mapOf("seturi" to { UpnpFault(714, null) })
        r.c.load(r.entry, r.items, 2, 0)
        r.c.playTile()
        r.work.drain()
        assertNull(r.c.takeLost())
        assertTrue(r.c.takeRefused())
        assertEquals("back on what the speaker plays", 0, r.c.index)
        assertTrue(r.c.session)
        assertFalse(r.spk.commands.contains("stop"))
        r.advance(5_000)
        r.spk.failOn = emptyMap()
        r.speaker("0:07:16", uri = r.url(0))
        r.poll()
        assertTrue(r.c.showsPlaying(r.now))
    }

    @Test
    fun `H2b - a tap superseded while its play is in flight fails quietly, the newer play owns the session`() {
        r.c.load(r.entry, r.items, 0, 0)
        r.c.playTile()
        r.c.load(r.entry, r.items, 1, 0)
        r.c.playTile()
        r.work.failNext = SpeakerUnreachable("the first play")
        r.work.drain()
        assertNull(r.c.takeLost())
        assertTrue(r.c.session)
        assertEquals(1, r.c.index)
        assertEquals(listOf("ep2" to r.url(1)), r.host.played)
    }

    @Test
    fun `H2c - an older play's result never blinks the index back nor reaches renderer json`() {
        r.start(drain = false)
        r.c.stepTo(2, 0)
        assertEquals(2, r.c.index)
        r.work.jobs.removeFirst()() // the first play lands
        assertEquals("no blink back to ep1", 2, r.c.index)
        assertTrue(r.host.played.isEmpty())
        r.work.drain()
        assertEquals(listOf("ep3" to r.url(2)), r.host.played)
        assertEquals(2, r.c.index)
    }

    @Test
    fun `M1 - an abandoned hand-off bookmarks the last poll and queues an ours-gated Stop`() {
        playing("0:07:11")
        r.advance(3_000)
        r.c.abandon()
        assertEquals(434_000L, r.store.writes.last().posMs)
        assertTrue(r.c.closed)
        r.work.drain()
        assertEquals("stop", r.spk.commands.last())
    }

    @Test
    fun `L2 - a refused same-tile resume falls through to the full start`() {
        playing("0:07:11", transport = "PAUSED_PLAYBACK")
        r.advance(9_000)
        r.poll()
        r.work.failNext = UpnpFault(701, null)
        r.c.playTile()
        r.work.drain()
        assertEquals("the transfer is the heal", 2, r.spk.commands.count { it == "seturi" })
    }

    @Test
    fun `L3 - play after a pause while a play is in flight - the last intent wins`() {
        r.start(drain = false)
        r.c.pause()
        r.c.playPause()
        r.work.drain()
        assertEquals(listOf("seturi", "play", "pause", "play"), r.spk.commands)
        assertTrue(r.c.spec(r.now).playWhenReady)
    }

    @Test
    fun `L4 - the bookmark's age term is at most 5 s`() {
        playing("0:01:40")
        r.advance(12_000)
        r.c.pause()
        assertEquals(105_000L, r.store.writes.last().posMs)
    }

    @Test
    fun `L5 - a pause pressed just before the switch comes home paused even when the live read fails`() {
        playing("0:07:11")
        r.c.pause()
        r.spk.failOn = mapOf("getposition" to { SpeakerUnreachable("gone") })
        var wasPlaying: Boolean? = null
        r.c.handOff { wasPlaying = it }
        r.work.drain()
        assertEquals(false, wasPlaying)
    }

    @Test
    fun `L6 - TRANSITIONING reads BUFFERING holding playWhenReady`() {
        playing()
        r.c.shown(r.c.spec(r.now))
        r.advance(5_000)
        r.speaker("0:07:16", transport = "TRANSITIONING")
        r.poll()
        val spec = r.c.spec(r.now)
        assertEquals(PlayPhase.BUFFERING, spec.phase)
        assertTrue(spec.playWhenReady)
    }

    @Test
    fun `L6 - a disarmed session reads IDLE and a play starts it again from the bookmark`() {
        playing("0:07:11")
        r.c.onPoll(Tick.Disarmed, r.session.snapshot.copy(armed = false))
        assertEquals(PlayPhase.IDLE, r.c.spec(r.now).phase)
        assertFalse(r.c.ownsSpeaker)
        r.c.playPause()
        r.work.drain()
        assertEquals(2, r.spk.commands.count { it == "seturi" })
        assertTrue("from the bookmark", "seek 0:07:11" in r.spk.commands)
    }

    @Test
    fun `L6 - the player's playlist - unique uids, an index inside them, empty only in IDLE or ENDED`() {
        val spec = SonosSpec(PlayPhase.READY, true, 5, 0, 0, false, null)
        val plan = playlistPlan(listOf("1/a", "1/a", ""), 3, spec)
        assertEquals(3, plan.uids.toSet().size)
        assertEquals(2, plan.index)
        assertEquals(PlayPhase.READY, plan.phase)
        val mismatch = playlistPlan(listOf("1/a"), 2, spec)
        assertTrue(mismatch.uids.isEmpty())
        assertEquals(PlayPhase.IDLE, mismatch.phase)
        assertEquals(PlayPhase.ENDED, playlistPlan(emptyList(), 0, spec.copy(phase = PlayPhase.ENDED)).phase)
    }

    @Test
    fun `L1 - a cut ramp's volume survives a switch home and belongs to its room`() {
        val f = java.io.File.createTempFile("renderer", ".json").apply { deleteOnExit() }
        RendererChoice.write(f, RendererChoice(RendererChoice.SONOS, "RINCON_A", "Stua", volume = 30, volumeUid = "RINCON_A"))
        RendererChoice.write(f, RendererChoice.local(RendererChoice.read(f)))
        val back = RendererChoice.read(f)
        assertFalse(back.sonos)
        assertEquals(30, back.volume)
        assertEquals("RINCON_A", back.volumeUid)
        assertEquals(RendererChoice.LOCAL_CHOICE, RendererChoice.local(RendererChoice(volume = 30)))
    }

    // -- Storytel on a room (design 26b §5.1-§5.4; vibb tests/storytel_sonos.py; QA M6) --

    private val series = SonosFakes.entry(id = 9, source = Source.STORYTEL, target = "storytel:series:26175", name = "Kokosbananas")

    /** Three books, none on the phone (a room plays them all), each with its own shelf length. */
    private val books = listOf("111" to 721_893L, "222" to 700_000L, "333" to 650_000L)
        .map { (cid, ms) -> SonosFakes.item(key = cid, url = null, durationMs = ms, entryId = 9) }

    private fun startBook(index: Int = 0, startMs: Long = 0, drain: Boolean = true) {
        r.c.load(series, books, index, startMs)
        r.c.playTile()
        if (drain) r.work.drain()
    }

    /** The speaker plays what it was last handed (the minted URL), at [rel]; it reports no length for a signed URL. */
    private fun bookPolled(rel: String, transport: String = "PLAYING", dur: String = "0:00:00") {
        r.speaker(rel, transport, uri = r.spk.lastUri, dur = dur)
        r.poll()
    }

    private fun setUris() = r.spk.commands.count { it == "seturi" }

    @Test
    fun `storytel_sonos 3 - one mint per play, 6 s for the asset call, 10 s in all, its URL to the speaker with the listing's length`() {
        startBook()
        assertEquals(listOf(FakeMinter.Call("111", 6_000, 10_000)), r.minter.calls)
        assertEquals(listOf("seturi", "play"), r.spk.commands)
        val minted = FakeMinter.signed("111", 1)
        assertEquals("the signed URL, minted in the job right before SetAVTransportURI", minted, r.spk.lastUri)
        val meta = r.spk.lastMeta!!
        assertTrue("the shelf's length in the DIDL (vibb 9)", meta.contains("duration=\"${hms(721.893)}\""))
        assertFalse("no art to the speaker (design 26a §9)", meta.contains("albumArtURI"))
        assertEquals("the host gets the minted URL (renderer.json stores the marker, RendererChoice.played)", listOf("111" to minted), r.host.played)
        assertTrue(r.host.unavailable.isEmpty())
        // the seed and the polls: ours by the minted URL; the length by consumableId (§5.4), never deciding done
        assertEquals(721_893L, r.c.spec(r.now).durationMs)
        r.advance(5_000)
        bookPolled("0:01:00")
        assertTrue(r.c.showsPlaying(r.now))
        assertEquals(721_893L, r.c.spec(r.now).durationMs)
        val w = r.store.writes.last()
        assertEquals("111", w.itemKey)
        assertEquals(60_000L, w.posMs)
        assertNull("a listing length never marks a book heard (vibb 19)", w.durationMs)
    }

    @Test
    fun `storytel_sonos 4 - a failed mint never raises, hands no uri over, sends nothing and touches no volume - the failed play`() {
        val cases = listOf(
            Mint.Unreachable("timeout") to "network",
            Mint.Failed(503) to "http_503",
            Mint.NoAudio(403) to "no_audio",
            Mint.Refused(0) to "login_refused",
            Mint.LoginFailed("no jwt") to "login_failed",
            Mint.NotConfigured to "storytel_off",
        )
        for ((mint, code) in cases) {
            val rig = ControllerRig()
            rig.capPct = 40
            rig.spk.vol = 80
            rig.minter.answer = { mint }
            rig.c.load(series, books, 0, 60_000)
            rig.c.playTile()
            rig.work.drain()
            assertEquals("$mint: nothing reaches the speaker, not even GetVolume or the mute", emptyList<String>(), rig.spk.log)
            assertEquals(code, listOf("111" to code), rig.host.unavailable)
            assertTrue(rig.host.played.isEmpty())
            assertNotNull("a first start is lost: \"Kan ikke spilles på Sonos\", the phone, paused", rig.c.takeLost())
            // the controller (and the actor) live on: the next play mints again and goes out
            rig.minter.answer = { cid -> Mint.Url(FakeMinter.signed(cid, rig.minter.calls.size)) }
            rig.c.load(series, books, 0, 0)
            rig.c.playTile()
            rig.work.drain()
            assertEquals(listOf("volume 40", "seturi", "play"), rig.spk.commands)
        }
        // a minter that throws anyway is an unreachable mint, never an exception out of the job
        val rig = ControllerRig()
        rig.minter.answer = { throw IllegalStateException("boom") }
        rig.c.load(series, books, 0, 0)
        rig.c.playTile()
        rig.work.drain()
        assertEquals(listOf("111" to "network"), rig.host.unavailable)
        assertNotNull(rig.c.takeLost())
    }

    @Test
    fun `a minted URL a speaker mustn't fetch - a private literal unless the debug switch - is a failed play`() {
        val fake = "http://10.0.2.2:8730/cdn/111.wav?consumableId=111&token=FAKE-SIG-1"
        r.minter.answer = { Mint.Url(fake) }
        startBook()
        assertTrue(r.spk.commands.isEmpty())
        assertEquals(listOf("111" to "address"), r.host.unavailable)
        assertNotNull(r.c.takeLost())
        r.privateMint = true // design 26b §4.6 (d): the emulator's fake Storytel
        startBook()
        assertEquals(fake, r.spk.lastUri)
    }

    @Test
    fun `storytel_sonos 5 - a URL for every play the controller sends, never for a resume, a seek or the stall retry`() {
        startBook(startMs = 60_000) // the hush and the settled seek belong to the play: still one mint
        assertEquals(1, r.minter.calls.size)
        assertTrue(r.spk.commands.containsAll(listOf("mute true", "seturi", "play", "seek 0:01:00")))
        bookPolled("0:01:00")
        r.c.pause()
        r.work.drain()
        bookPolled("0:01:02", transport = "PAUSED_PLAYBACK")
        r.c.playPause()
        r.work.drain()
        assertEquals("pause and resume mint nothing", 1, r.minter.calls.size)
        assertEquals("play", r.spk.commands.last())
        r.c.seek(120_000)
        r.work.drain()
        assertEquals("a seek mints nothing", 1, r.minter.calls.size)
        // the stall retry: Stop, Play on the speaker's own uri (vibb `_stall_bookkeeping`)
        repeat(5) { bookPolled("0:02:00") }
        assertTrue(r.spk.commands.contains("stop"))
        assertEquals("the stall retry mints nothing", 1, r.minter.calls.size)
        // a step mints; a natural advance mints
        r.c.stepTo(1, 0)
        r.work.drain()
        assertEquals(listOf("111", "222"), r.minter.calls.map { it.cid })
        assertEquals(FakeMinter.signed("222", 2), r.spk.lastUri)
        bookPolled("0:11:30", dur = "0:11:40")
        r.advance(5_000)
        bookPolled("0:11:39", transport = "STOPPED", dur = "0:11:40")
        assertEquals(listOf("111", "222", "333"), r.minter.calls.map { it.cid })
        // a stopped room plays from the bookmark with a new URL (vibb 16)
        bookPolled("0:00:30")
        r.advance(5_000)
        bookPolled("0:00:31", transport = "STOPPED")
        r.c.playPause()
        r.work.drain()
        assertEquals(listOf("111", "222", "333", "333"), r.minter.calls.map { it.cid })
        assertEquals("one SetAVTransportURI per mint", r.minter.calls.size, setUris())
    }

    @Test
    fun `M6 - a step pressed again during a mint sends one SetAVTransportURI, for the latest want`() {
        startBook()
        bookPolled("0:01:00")
        r.c.stepTo(1, 0)
        r.minter.during = { r.c.stepTo(2, 0) } // pressed while the mint takes its seconds
        r.work.drain()
        assertEquals("222's mint ran, its play dropped by the token", listOf("111", "222", "333"), r.minter.calls.map { it.cid })
        assertEquals("the start and one step", 2, setUris())
        assertEquals(FakeMinter.signed("333", 3), r.spk.lastUri)
        assertEquals(2, r.c.index)
        assertEquals(listOf("111", "333"), r.host.played.map { it.first })
    }

    @Test
    fun `M6 - a job superseded while it queued never mints`() {
        r.c.load(series, books, 0, 0)
        r.c.playTile() // queued behind whatever the actor holds
        r.c.stepTo(1, 0) // a newer play before it ran
        r.work.drain()
        assertEquals(listOf("222"), r.minter.calls.map { it.cid })
        assertEquals(listOf("seturi", "play"), r.spk.commands)
        assertEquals(FakeMinter.signed("222", 1), r.spk.lastUri)
    }

    @Test
    fun `M6 - a release or a suspension during a mint sends no Play`() {
        r.c.load(series, books, 0, 0)
        r.c.playTile()
        r.minter.during = { r.c.close() } // the renderer released (a hand-off, onDestroy)
        r.work.drain()
        assertEquals(1, r.minter.calls.size)
        assertEquals(emptyList<String>(), r.spk.log)
        val rig = ControllerRig()
        rig.c.load(series, books, 1, 90_000)
        rig.c.playTile()
        rig.minter.during = { rig.allowed = false } // the launcher suspended the app (a time rule)
        rig.work.drain()
        assertEquals(emptyList<String>(), rig.spk.log)
        assertNull("dropped, not a failed play", rig.c.takeLost())
        assertTrue(rig.host.unavailable.isEmpty())
        assertEquals("the book waits at its place for the next play", 90_000L, rig.c.spec(rig.now).positionAt(rig.now))
        assertEquals(1, rig.c.spec(rig.now).index)
        rig.allowed = true
        rig.c.playTile()
        rig.work.drain()
        assertEquals("a new play mints again", listOf("222", "222"), rig.minter.calls.map { it.cid })
        assertTrue(rig.spk.commands.containsAll(listOf("seturi", "play", "seek 0:01:30")))
    }

    @Test
    fun `a hand-off queued behind a mint waits for it - the play goes out, then the bookmark and Stop`() {
        // The room's actor is serial and never cancels a job (qa-26b-merge-code Low 1, Low 6): every job behind a
        // mint - a poll, a pause, a volume key, a hand-off - waits up to MINT_BUDGET_MS; HANDOFF_MS covers it.
        var wasPlaying: Boolean? = null
        r.c.load(series, books, 0, 0)
        r.c.playTile()
        r.minter.during = { r.c.handOff { wasPlaying = it } }
        r.speaker("0:00:01", uri = FakeMinter.signed("111", 1)) // what the hand-off's live look will read
        r.work.drain()
        assertEquals(listOf("seturi", "play", "stop"), r.spk.commands)
        assertEquals(true, wasPlaying)
        assertTrue(r.c.closed)
        assertTrue(SonosController.MINT_BUDGET_MS + 10_000 < me.vibb.music.playback.PlaybackService.HANDOFF_MS)
        assertTrue("the drain outlasts a mint and a hushed play", SonosController.MINT_BUDGET_MS + 10_000 <= SonosActor.DRAIN_MS)
    }

    @Test
    fun `storytel_sonos 14 and 10 - the length by consumableId first, never another book's, a zero never erasing it`() {
        startBook()
        bookPolled("0:01:00")
        assertEquals(721_893L, r.c.spec(r.now).durationMs)
        // a real length from the speaker wins (vibb: the lookup only fills a falsy one)
        bookPolled("0:01:05", dur = "0:12:30")
        assertEquals(750_000L, r.c.spec(r.now).durationMs)
        assertEquals("the speaker measured it", 750_000L, r.store.writes.last().durationMs)
        // the next zero: the lookup again (flagged), never erasing
        bookPolled("0:01:10")
        assertEquals(721_893L, r.c.spec(r.now).durationMs)
        assertNull(r.store.writes.last().durationMs)
        // a book started from the speaker itself (vibb's other case): not ours, but its length is known by its
        // consumableId - 222's, though the token holds 111's digits - and never shown under our title
        r.speaker("0:00:20", uri = "https://cdn.example.invalid/mp3/222/abook.mp3?consumableId=222&token=FAKE-SIG-111", dur = "0:00:00")
        r.poll()
        assertEquals(700.0, r.c.snap!!.durS!!, 0.0)
        assertNull("someone else's stream shows no length", r.c.spec(r.now).durationMs)
    }

    @Test
    fun `storytel_sonos 13 and 7 - adopt by consumableId restores the book's length from the queue, never another book's`() {
        // the speaker holds a URL minted by the previous process: book 222, whose token holds 111's digits
        val live = "https://cdn.example.invalid/mp3/222/abook.mp3?consumableId=222&token=abc111xyz"
        val marker = "storytel:book:222"
        val choice = RendererChoice(RendererChoice.SONOS, r.uid, "Barnerom", entryId = 9, itemKey = "222", uri = marker)
        r.speaker("1:10:00", uri = live, dur = "0:00:00")
        val f = kotlinx.coroutines.runBlocking {
            r.transport.adoptLive(r.uid, kindOfUri(marker), marker, 1_500) { if (ourLiveSession(choice, it)) it.uri else null }
        }!!
        assertTrue("ours by consumableId", f.ours)
        assertEquals("the session goes on with the live signed URL (§5.3)", live, r.session.uri)
        val idx = adoptIndex(series, books, live)!!
        assertEquals(1, idx)
        r.c.adopt(series, books, idx, live, f)
        assertEquals("the queue row's length, not 111's", 700_000L, r.c.spec(r.now).durationMs)
        assertEquals("nothing sent", emptyList<String>(), r.spk.commands)
        assertTrue(r.minter.calls.isEmpty())
        r.advance(15_000)
        r.speaker("1:10:15", uri = live, dur = "0:00:00")
        r.poll()
        val w = r.store.writes.last()
        assertEquals("222", w.itemKey)
        assertEquals(4_215_000L, w.posMs)
        assertNull(w.durationMs)
    }

    @Test
    fun `migration follow of a book - the hint's signed URL goes to renderer json's writer, which stores the marker`() {
        startBook()
        bookPolled("0:01:00")
        val hint = "https://cdn.example.invalid/mp3/111/abook.mp3?consumableId=111&token=FAKE-SIG-9"
        r.c.onPoll(Tick.Next(5_000), hinted(uri = hint))
        r.work.drain()
        assertEquals(listOf("111" to hint), r.host.followedUris)
        assertTrue("an adopt and its follow mint nothing", r.minter.calls.size == 1)
        val written = RendererChoice(RendererChoice.SONOS, r.uid, "Barnerom", 9, "111", "storytel:book:111").followed("RINCON_KITCHEN01400", "Kjøkken", series, "111", hint)
        assertEquals("storytel:book:111", written.uri)
    }

    @Test
    fun `L1 - the token is asked again right before SetAVTransportURI - a step during the start's cap sends no superseded uri`() {
        // the window between the mint and SetAVTransportURI holds the speaker check, the hush, the cap (members
        // included) and the queue clear: a step pressed there is the kid's latest want, the older play sends no uri
        r.capPct = 40
        var stepped = false
        r.spk.failOn = mapOf(
            "getvolume" to {
                if (!stepped) {
                    stepped = true
                    r.c.stepTo(1, 0)
                }
                SpeakerUnreachable("slow")
            },
        )
        r.c.load(series, books, 0, 0)
        r.c.playTile()
        r.work.drain()
        assertTrue(stepped)
        assertEquals("only the step's book reached the speaker", 1, setUris())
        assertEquals(FakeMinter.signed("222", 2), r.spk.lastUri)
        assertEquals(listOf("222"), r.host.played.map { it.first })
        assertNull(r.c.takeLost())
    }

    @Test
    fun `L2 - an RSS play queued behind a slow mint is gated too - a suspension during the mint sends nothing`() {
        r.c.load(series, books, 0, 0)
        r.c.playTile()
        r.minter.during = {
            // the kid taps an RSS entry while the book mints, then the launcher suspends the app
            r.c.load(r.entry, r.items, 1, 0)
            r.c.playTile()
            r.allowed = false
        }
        r.work.drain()
        assertEquals(listOf("111"), r.minter.calls.map { it.cid })
        assertEquals("neither play reached the speaker", emptyList<String>(), r.spk.log)
        assertNull("dropped, not lost", r.c.takeLost())
        assertEquals("the RSS episode waits at its place", 1, r.c.spec(r.now).index)
        assertEquals(PlayPhase.IDLE, r.c.spec(r.now).phase)
        r.allowed = true
        r.c.playTile()
        r.work.drain()
        assertEquals(r.url(1), r.spk.lastUri)
        // and a release drains a queued url play without sending it
        val rig = ControllerRig()
        rig.c.load(rig.entry, rig.items, 0, 0)
        rig.c.playTile()
        rig.c.close()
        rig.work.drain()
        assertEquals(emptyList<String>(), rig.spk.log)
    }

    @Test
    fun `L3 - a no_audio mint is recorded under the entry the play was sent for, whatever plays meanwhile`() {
        r.minter.answer = { Mint.NoAudio(403) }
        r.c.load(series, books, 1, 0)
        r.c.playTile()
        r.minter.during = { r.c.load(r.entry, r.items, 0, 0) } // another entry tapped while the book minted
        r.work.drain()
        assertEquals(listOf("222" to "no_audio"), r.host.unavailable)
        assertEquals("the series, never the RSS entry now loaded", listOf(9L), r.host.unavailableEntries)
    }
}
