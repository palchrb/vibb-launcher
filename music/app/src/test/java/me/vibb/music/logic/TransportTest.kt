package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Ports of palchrb/vibb `tests/mpv_prev.py`, `mpv_next_wrap.py` and `prev_long_form.py` (MIT,
 * Copyright (c) 2026 palchrb). The player wraps the rotated queue both ways, and previous never
 * restarts long-form audio. The Pi's "a live mpv session always owns the transport" (next_wrap 4,
 * 5) is the media session here: KidPlayer routes every controller's previous/next through these.
 */
class TransportTest {
    private val goTo = { i: Int -> TransportAction.GoTo(i) }

    // tests/mpv_prev.py

    @Test
    fun `prev more than 5 s in restarts the episode`() {
        assertEquals(TransportAction.Restart, previousAction(0, 36, 120_000, 1_800_000 - 1))
    }

    @Test
    fun `prev near the start at the rotated slot 0 wraps to the previous episode`() {
        assertEquals(goTo(35), previousAction(0, 36, 2_000, 1_000_000))
    }

    @Test
    fun `prev mid-playlist steps back`() {
        assertEquals(goTo(6), previousAction(7, 36, 2_000, 1_000_000))
    }

    @Test
    fun `prev in a single-item queue doesn't wrap`() {
        assertEquals(TransportAction.Stay, previousAction(0, 1, 2_000, 1_000_000))
    }

    @Test
    fun `next is untouched by prev's rules`() {
        assertEquals(goTo(1), nextAction(0, 36))
    }

    // tests/mpv_next_wrap.py

    @Test
    fun `next at the last slot wraps to the first episode`() {
        assertEquals(goTo(0), nextAction(2, 3))
    }

    @Test
    fun `next mid-playlist is plain next`() {
        assertEquals(goTo(1), nextAction(0, 3))
    }

    @Test
    fun `next in a single-item queue doesn't wrap`() {
        assertEquals(TransportAction.Stay, nextAction(0, 1))
    }

    // tests/prev_long_form.py

    @Test
    fun `1 under the limit prev still restarts`() {
        assertEquals(TransportAction.Restart, previousAction(3, 8, 1_200_000, PREV_RESTART_MAX_MS - 1_000))
    }

    @Test
    fun `2 six hours into an eleven-hour book prev steps back and never seeks`() {
        assertEquals(goTo(2), previousAction(3, 8, 21_600_000, 39_600_000))
    }

    @Test
    fun `3 browsing back through a long series never restarts, at any depth`() {
        for (pos in listOf(6_000L, 60_000L, 3_600_000L, 39_000_000L)) {
            assertEquals("pos $pos", goTo(2), previousAction(3, 8, pos, 39_600_000))
        }
    }

    @Test
    fun `4 the slot-0 wrap still works on long files`() {
        assertEquals(goTo(7), previousAction(0, 8, 21_600_000, 39_600_000))
    }

    @Test
    fun `5 an unknown duration behaves as before`() {
        assertEquals(TransportAction.Restart, previousAction(3, 8, 600_000, null))
    }

    @Test
    fun `6 the limit is 30 minutes`() {
        assertEquals(30 * 60_000L, PREV_RESTART_MAX_MS)
    }

    // design 50 §1.1: the skip pair

    @Test
    fun `d50 - 15 s back and 30 s on stay inside the item`() {
        assertEquals(45_000L, skipWithin(60_000, -SKIP_BACK_MS, 600_000))
        assertEquals("never below 0", 0L, skipWithin(10_000, -SKIP_BACK_MS, 600_000))
        assertEquals(90_000L, skipWithin(60_000, SKIP_FORWARD_MS, 600_000))
        assertEquals("never across into the next item", 599_000L, skipWithin(580_000, SKIP_FORWARD_MS, 600_000))
        assertEquals("already that close: forward stays put", 599_500L, skipWithin(599_500, SKIP_FORWARD_MS, 600_000))
        assertEquals("back still goes back from there", 584_500L, skipWithin(599_500, -SKIP_BACK_MS, 600_000))
    }

    @Test
    fun `d50 - a context item has no listing duration - the live one clamps, unknown forward does nothing`() {
        // the player passes the live state's duration (a Spotify context's synthetic item has none listed)
        assertEquals(179_000L, skipWithin(170_000, SKIP_FORWARD_MS, 180_000))
        assertEquals("no duration yet: forward stays", 20_000L, skipWithin(20_000, SKIP_FORWARD_MS, null))
        assertEquals("back goes back", 5_000L, skipWithin(20_000, -SKIP_BACK_MS, null))
        assertEquals(0L, skipWithin(20_000, -SKIP_BACK_MS * 2, 0))
    }
}
