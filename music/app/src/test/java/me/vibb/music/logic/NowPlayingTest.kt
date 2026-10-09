package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.NowPlayingRow
import me.vibb.music.playback.PlayerHub
import me.vibb.music.ui.nowView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ports of palchrb/vibb `tests/now_playing.py`, `resume_overlap.py` and `session_window.py` (MIT,
 * Copyright (c) 2026 palchrb), as far as they concern the phone:
 * - now_playing: the title comes from our own item (never a url or file name), a track change shows
 *   the new item at once, a stale publish for another entry is ignored, the position holds at the
 *   resume target until the seek lands (cases 8-11), and a tap shows "playing" at once (12). The
 *   Spotify cases (6, 7) come with phase 3.
 * - resume_overlap case 4: speech gets a clause, music a beat, a long outage a run-up, a flapping
 *   output nothing; the Pi-specific argv and mpv plumbing (1-3, 5-7) have no counterpart.
 * - session_window: the phone has no window (design 20: always continue after a restart, paused),
 *   so what carries over is "uncertainty continues" (any stamp, also one from the future) and "the
 *   first tap ends the old session"; the RTC, settings and boot-thread cases are the Pi's.
 */
class NowPlayingTest {
    private val e = entry(1, cache = 5)
    private val snap = snapshot(library(listOf(e)), items(1, listOf("e1", "e2", "e3")))
    private fun row(index: Int, keys: String = "e3\ne2\ne1", at: Long = 0) = NowPlayingRow(0, 1, keys, index, 90_000, 600_000, false, at)

    // now_playing.py

    @Test
    fun `1-3 the title is the item's, and a track change shows the new one at once`() {
        val live = PlayerHub.State(live = true, entryId = 1, keys = listOf("e3", "e2", "e1"), index = 0, playing = true)
        assertEquals("Title e3", nowView(snap, live, null)!!.item.title)
        assertEquals("Title e2", nowView(snap, live.copy(index = 1), null)!!.item.title)
        assertTrue(nowView(snap, live, null)!!.item.title.none { it == '/' })
    }

    @Test
    fun `5 a stored queue of an entry no longer here is ignored`() {
        assertNull(nowView(snap, PlayerHub.State(), row(0).copy(entryId = 99)))
        assertNull(nowView(snap, PlayerHub.State(), row(0, keys = "gone")))
    }

    @Test
    fun `8 while seeking to the resume target the position holds there`() {
        for (live in listOf(0L, 1_000L, 42_000L)) assertEquals(90_000L, settledPosition(live, 90_000, 1_000, false))
    }

    @Test
    fun `9 it follows the player once the seek lands`() {
        assertEquals(90_000L, settledPosition(90_000, 90_000, 2_000, false))
        assertEquals(93_000L, settledPosition(93_000, 90_000, 5_000, false))
    }

    @Test
    fun `10 a fresh start never holds`() {
        assertEquals(2_000L, settledPosition(2_000, 0, 500, false))
        assertEquals(2_000L, settledPosition(2_000, RESUME_MIN_MS, 500, false))
    }

    @Test
    fun `11 the hold is bounded, and a seek by the kid wins`() {
        assertEquals(3_000L, settledPosition(3_000, 90_000, POSITION_SETTLE_MAX_MS + 1, false))
        assertEquals(3_000L, settledPosition(3_000, 90_000, 1_000, true))
    }

    @Test
    fun `12 a tap shows playing at once, a pause wins, the end is not playing`() {
        assertTrue(showsPlaying(playWhenReady = true, ended = false))
        assertFalse(showsPlaying(playWhenReady = false, ended = false))
        assertFalse(showsPlaying(playWhenReady = true, ended = true))
    }

    // resume_overlap.py case 4

    @Test
    fun `the overlap - a clause, a beat, a run-up, and nothing when flapping`() {
        assertEquals(RESUME_OVERLAP_SPEECH_MS, resumeOverlapMs(music = false, goneMs = 5_000, recentFaults = 1))
        assertEquals(RESUME_OVERLAP_MUSIC_MS, resumeOverlapMs(music = true, goneMs = 5_000, recentFaults = 1))
        assertEquals(RESUME_OVERLAP_LONG_MS, resumeOverlapMs(music = false, goneMs = 600_000, recentFaults = 1))
        assertEquals(0L, resumeOverlapMs(music = false, goneMs = 5_000, recentFaults = 3))
        assertEquals("never below 0", 0L, overlapStart(1_000, RESUME_OVERLAP_SPEECH_MS))
        assertEquals(57_000L, overlapStart(60_000, RESUME_OVERLAP_SPEECH_MS))
    }

    // session_window.py, the phone's part

    @Test
    fun `a restart brings the queue back paused, whatever the stamp says`() {
        for (stamp in listOf(0L, System.currentTimeMillis() - 99 * 3_600_000L, System.currentTimeMillis() + 5 * 3_600_000L)) {
            val view = nowView(snap, PlayerHub.State(), row(1, at = stamp))!!
            assertEquals("e2", view.item.key)
            assertEquals(90_000L, view.storedPosMs)
            assertFalse("nothing ever auto-plays", view.playing)
        }
    }

    @Test
    fun `the restored queue continues where it stopped, and items gone since are skipped`() {
        assertEquals(listOf("e3", "e2", "e1") to 1, restoredQueue(listOf("e3", "e2", "e1"), 1, setOf("e1", "e2", "e3")))
        assertEquals(listOf("e3", "e1") to 1, restoredQueue(listOf("e3", "e2", "e1"), 1, setOf("e1", "e3")))
        assertNull(restoredQueue(listOf("e3"), 0, emptySet()))
    }

    @Test
    fun `the first tap ends the old session - a resume-off entry starts at track 1`() {
        val plan = coverTapPlan(listOf("t1", "t2", "t3"), resume = false, bookmark = Bookmark("t2", 50_000), shuffle = false)!!
        assertEquals(QueuePlan(listOf("t1", "t2", "t3"), 0, 0), plan)
    }

    @Test
    fun `the sleep timer fades out over its last 10 s`() {
        assertEquals(1f, sleepVolume(60_000))
        assertEquals(0.5f, sleepVolume(SLEEP_FADE_MS / 2))
        assertEquals(0f, sleepVolume(0))
    }
}
