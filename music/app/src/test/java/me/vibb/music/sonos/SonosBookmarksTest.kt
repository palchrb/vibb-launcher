package me.vibb.music.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `_sonos_bookmark_now`'s rules (design 26a §4.4): port of the bookmark checks of palchrb/vibb
 * `tests/storytel_sonos.py` (the hold, the regression guard's table, the speaker-measured duration;
 * MIT, Copyright (c) 2026 palchrb).
 */
class SonosBookmarksTest {
    private val now = 10_000_000L
    private val longAgo = now - 1_000_000

    private fun input(rel: Double?, transport: TransportState = TransportState.PAUSED_PLAYBACK, ours: Boolean = true, dur: Double? = 3_600.0, fromListing: Boolean = false, age: Long = 0) =
        BookmarkInput("ep1", ours, transport, rel, dur, fromListing, age)

    private fun written(rel: Double, prev: Double, seekAt: Long = longAgo): Boolean =
        SonosBookmarks.decide(input(rel), null, prev, seekAt, now).write != null

    @Test
    fun `the regression guard's table`() {
        assertEquals("3120 -> 5 kept (the speaker restarted the track)", false, written(5.0, 3_120.0))
        assertEquals("3120 -> 2400 written (a human seeks back to 40 min, not to the top)", true, written(2_400.0, 3_120.0))
        assertEquals("10800 -> 9000 written", true, written(9_000.0, 10_800.0))
        assertEquals("seeked to 5 written", true, written(5.0, 3_120.0, seekAt = now - 10_000))
        assertEquals("200 -> 5 written (nothing substantial to lose)", true, written(5.0, 200.0))
        assertEquals("a deliberate restart 179 s ago is still the user's", true, written(5.0, 3_120.0, seekAt = now - 179_000))
        assertEquals("181 s ago it is not", false, written(5.0, 3_120.0, seekAt = now - 181_000))
    }

    @Test
    fun `the refused-seek hold - bounded by 120 s of listening`() {
        val hold = BmHold("ep1", 3_120.0, now)
        val held = SonosBookmarks.decide(input(39.0), hold, 3_120.0, longAgo, now + 60_000)
        assertNull(held.write)
        assertEquals(hold, held.hold)
        val released = SonosBookmarks.decide(input(160.0), hold, 3_120.0, longAgo, now + 121_000)
        assertEquals(160_000L, released.write!!.posMs)
        assertNull(released.hold)
        val passed = SonosBookmarks.decide(input(3_125.0), hold, 3_120.0, longAgo, now + 5_000)
        assertNotNull("past the hold: written, hold gone", passed.write)
        assertNull(passed.hold)
        val other = SonosBookmarks.decide(input(39.0).copy(itemKey = "ep2"), hold, 0.0, longAgo, now)
        assertNotNull("another item isn't held", other.write)
    }

    @Test
    fun `measured only - not ours, nothing measured or STOPPED write nothing - PLAYING adds the one age term`() {
        assertNull(SonosBookmarks.decide(input(100.0, ours = false), null, 0.0, longAgo, now).write)
        assertNull(SonosBookmarks.decide(input(null), null, 0.0, longAgo, now).write)
        assertNull("after a Stop the transport reads 0:00", SonosBookmarks.decide(input(0.0, TransportState.STOPPED), null, 0.0, longAgo, now).write)
        assertEquals(103_500L, SonosBookmarks.decide(input(100.0, TransportState.PLAYING, age = 3_500), null, 0.0, longAgo, now).write!!.posMs)
        assertEquals(100_000L, SonosBookmarks.decide(input(100.0, TransportState.PAUSED_PLAYBACK, age = 3_500), null, 0.0, longAgo, now).write!!.posMs)
    }

    @Test
    fun `the duration only when the speaker measured it`() {
        assertEquals(3_600_000L, SonosBookmarks.decide(input(100.0), null, 0.0, longAgo, now).write!!.durationMs)
        assertNull(SonosBookmarks.decide(input(100.0, fromListing = true), null, 0.0, longAgo, now).write!!.durationMs)
        assertNull("0 is no length", SonosBookmarks.decide(input(100.0, dur = 0.0), null, 0.0, longAgo, now).write!!.durationMs)
    }
}
