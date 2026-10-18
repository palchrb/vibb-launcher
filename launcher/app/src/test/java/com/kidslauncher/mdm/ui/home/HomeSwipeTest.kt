package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Home's swipes from their own DOWN (qa-34-s4-code M1). */
class HomeSwipeTest {
    private fun swipe(
        down: Pair<Float, Float>,
        up: Pair<Float, Float>,
        start: SwipeStart,
        rowCanScroll: Boolean = false,
        gridCanScroll: Boolean = false,
        shown: Boolean = true,
        velocity: Float = 2_000f,
    ) = homeSwipe(down.first, down.second, up.first, up.second, velocity, velocity, start, gridCanScroll, rowCanScroll, shown)

    private val left = (600f to 300f) to (300f to 320f)
    private val up = (500f to 1_500f) to (520f to 900f)

    @Test
    fun `a left swipe on a contact stays the row's scroll while the row can scroll`() {
        assertEquals(HomeSwipe.NONE, swipe(left.first, left.second, SwipeStart.CONTACTS_ROW, rowCanScroll = true))
    }

    @Test
    fun `a left swipe on a contact or between contacts in a row that can't scroll opens kid Settings`() {
        assertEquals(HomeSwipe.KID_SETTINGS, swipe(left.first, left.second, SwipeStart.CONTACTS_ROW, rowCanScroll = false))
    }

    @Test
    fun `a left swipe from the grid or blank space opens kid Settings whatever the row can do`() {
        assertEquals(HomeSwipe.KID_SETTINGS, swipe(left.first, left.second, SwipeStart.GRID, rowCanScroll = true))
        assertEquals(HomeSwipe.KID_SETTINGS, swipe(left.first, left.second, SwipeStart.BLANK, rowCanScroll = true))
    }

    @Test
    fun `a swipe up opens the drawer only once the grid can't scroll further`() {
        assertEquals(HomeSwipe.DRAWER, swipe(up.first, up.second, SwipeStart.GRID))
        assertEquals(HomeSwipe.NONE, swipe(up.first, up.second, SwipeStart.GRID, gridCanScroll = true))
        assertEquals(HomeSwipe.DRAWER, swipe(up.first, up.second, SwipeStart.CONTACTS_ROW, rowCanScroll = true))
    }

    @Test
    fun `taps, short or slow moves, right and down swipes, cards and the night ground open nothing`() {
        assertEquals("tap", HomeSwipe.NONE, swipe(500f to 500f, 502f to 501f, SwipeStart.GRID))
        assertEquals("short", HomeSwipe.NONE, swipe(500f to 500f, 420f to 500f, SwipeStart.BLANK))
        assertEquals("slow", HomeSwipe.NONE, swipe(left.first, left.second, SwipeStart.BLANK, velocity = 50f))
        assertEquals("right", HomeSwipe.NONE, swipe(left.second, left.first, SwipeStart.BLANK))
        assertEquals("down", HomeSwipe.NONE, swipe(up.second, up.first, SwipeStart.GRID))
        assertEquals("card", HomeSwipe.NONE, swipe(left.first, left.second, SwipeStart.CARD))
        assertEquals("night", HomeSwipe.NONE, swipe(left.first, left.second, SwipeStart.BLANK, shown = false))
    }

    @Test
    fun `the previous touch doesn't matter - each swipe is decided from its own DOWN`() {
        // The same row state, two swipes one after the other: a grid swipe, then one on a contact,
        // then the grid again - each follows its own start, not the touch before it.
        assertEquals(HomeSwipe.KID_SETTINGS, swipe(900f to 1_300f, 300f to 1_310f, SwipeStart.GRID, rowCanScroll = true))
        assertEquals(HomeSwipe.NONE, swipe(900f to 300f, 300f to 310f, SwipeStart.CONTACTS_ROW, rowCanScroll = true))
        assertEquals(HomeSwipe.KID_SETTINGS, swipe(900f to 1_300f, 300f to 1_310f, SwipeStart.GRID, rowCanScroll = true))
    }
}
