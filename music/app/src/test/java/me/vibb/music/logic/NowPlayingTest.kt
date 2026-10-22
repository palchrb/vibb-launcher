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
 *   the new item at once, a stale publish for another entry is ignored, and a tap shows "playing" at
 *   once (12). Cases 8-11 (hold the position at the resume target until mpv's seek lands) need no
 *   code: ExoPlayer reports the seek target at once. The Spotify cases (6, 7) come with phase 3, and
 *   12's time bound on "playing" isn't ported (Media3 reports buffering itself).
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
    fun `12 a tap shows playing at once, a pause wins, the end is not playing`() {
        assertTrue(showsPlaying(playWhenReady = true, ended = false))
        assertFalse(showsPlaying(playWhenReady = false, ended = false))
        assertFalse(showsPlaying(playWhenReady = true, ended = true))
    }

    // resume_overlap.py case 4

    @Test
    fun `the overlap - a clause, a beat for music (resume off), a run-up, and nothing when flapping`() {
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

    // design 50 §1.2: "Til slutten"

    @Test
    fun `d50 - Til slutten - offered only with a duration, the fade over the renderer's own fadeMs`() {
        assertTrue(sleepAtEndOffered(600_000))
        assertFalse("live, or a context before its first state", sleepAtEndOffered(null))
        assertFalse(sleepAtEndOffered(0))
        assertEquals(30_000L, itemRemaining(570_000, 600_000))
        assertEquals(0L, itemRemaining(610_000, 600_000))
        assertNull(itemRemaining(1_000, null))
        // the phone and Spotify fade over 10 s, a room over 3 s: each renderer passes its own
        assertEquals(1f, atEndGain(30_000, 10_000), 0f)
        assertEquals(0.5f, atEndGain(5_000, 10_000), 0f)
        assertEquals(0f, atEndGain(0, 10_000), 0f)
        assertEquals(0.5f, atEndGain(1_500, 3_000), 0f)
        assertEquals("unknown: full volume", 1f, atEndGain(null, 10_000), 0f)
        // the look: at the fade's start but at least every second (a seek is seen), in 250 ms steps inside it
        assertEquals(1_000L, atEndNextLookMs(60_000, 10_000))
        assertEquals(400L, atEndNextLookMs(10_400, 10_000))
        assertEquals(AT_END_STEP_MS, atEndNextLookMs(9_000, 10_000))
        assertEquals(AT_END_LOOK_MS, atEndNextLookMs(null, 10_000))
    }

    @Test
    fun `d50 - Til slutten survives a step, a new queue, its pause, the kid's pause and the minute timer end it`() {
        assertTrue("finish what plays: a manual step keeps it (the user's call)", sleepAtEndAfter(true, SleepEvent.STEP))
        for (e in SleepEvent.entries - SleepEvent.STEP) assertFalse(e.name, sleepAtEndAfter(true, e))
        assertTrue("code QA 8: a step onto a live item (no duration) ends it", SleepEvent.NO_DURATION in SleepEvent.entries)
        for (e in SleepEvent.entries) assertFalse("off stays off", sleepAtEndAfter(false, e))
    }

    @Test
    fun `d50 - each renderer pauses at the item's end by its own means`() {
        val local = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/playback/LocalRenderer.kt").readText()
        assertTrue("the phone: ExoPlayer pauses at the item's end", local.contains("exo.pauseAtEndOfMediaItems = done != null"))
        assertTrue(local.contains("reason == Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM"))
        val room = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/sonos/SonosRenderer.kt").readText()
        assertTrue("a room: advance() holds", room.contains("controller.holdAtEnd = { finishAtEnd() }"))
        val service = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        // code QA 1: only the kid's new pick ends it - start() is neutral for every other load
        fun body(signature: String) = service.substringAfter(signature).substringBefore("\n    }\n")
        assertTrue(body("private fun start(a: Active, plan: QueuePlan, play: Boolean = true, newChoice: Boolean = false) {").contains("if (newChoice) endSleepAtEnd(SleepEvent.NEW_QUEUE)"))
        assertEquals("the cover tap's and the list pick's start only", 2, Regex("start\\([^\\n]*newChoice = true\\)").findAll(service).count())
        assertTrue("a cover tap", body("private suspend fun playEntry(entryId: Long, favourites: Boolean) {").contains("start(a, p, newChoice = true)"))
        assertTrue("a list pick", body("private suspend fun playItem(entryId: Long, key: String) {").contains("start(a, p, newChoice = true)"))
        for ((path, signature) in listOf(
            "the stored queue's first play" to "private suspend fun resumeLast() {",
            "the restored queue's controls" to "private suspend fun restoreThen(",
            "the phone -> a room" to "private suspend fun toRoom(uid: String) {",
            "a room -> the phone" to "private suspend fun comeHome(",
            "a playback resumption" to "override fun onPlaybackResumption(",
        )) {
            val b = body(signature)
            assertTrue("$path loads a queue", b.contains("start(a, "))
            assertFalse("$path keeps Til slutten", b.contains("newChoice"))
        }
        assertTrue("so does the kid's own pause", service.contains("PauseCause.USER -> if (!selfPausing && (sleepEndsAt != null || sleepAtEnd)) setSleep(0)"))
        assertTrue("a renderer switch carries it", service.contains("if (sleepAtEnd) next.pauseAtItemEnd { sleepAtEndReached() }"))
    }
}
