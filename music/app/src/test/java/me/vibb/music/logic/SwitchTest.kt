package me.vibb.music.logic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The screens show what the kid tapped until the player gets there (the old podcast's cover flashed). */
class SwitchTest {
    private val a1 = ItemRef(1, "a1")
    private val b1 = ItemRef(2, "b1")
    private val b2 = ItemRef(2, "b2")

    @Test
    fun `a tap on B shows B while the player still plays A`() {
        val tap = PlayRequest(2, null, a1, 1_000)
        assertTrue(requestStands(tap, a1, 1_000))
        assertTrue(requestStands(tap, a1, 3_999))
    }

    @Test
    fun `the player reaching B ends the request`() {
        assertFalse(requestStands(PlayRequest(2, null, a1, 1_000), b1, 1_100))
        assertFalse(requestStands(PlayRequest(2, "b2", a1, 1_000), b2, 1_100))
    }

    @Test
    fun `the player moving elsewhere ends it too`() {
        // An item it couldn't play was skipped: the player is right, not the request.
        assertFalse(requestStands(PlayRequest(2, "b2", a1, 1_000), b1, 1_100))
    }

    @Test
    fun `a request the player never takes ends after the hold`() {
        val tap = PlayRequest(2, null, a1, 1_000)
        assertFalse(requestStands(tap, a1, 1_000 + SWITCH_HOLD_MS))
        assertFalse(requestStands(tap, a1, 999)) // the clock never runs back
    }

    @Test
    fun `a tap on what is already loaded is no switch`() {
        assertFalse(requestStands(PlayRequest(1, null, a1, 1_000), a1, 1_000))
        assertFalse(requestStands(PlayRequest(1, "a1", a1, 1_000), a1, 1_000))
        // Another item of the same entry is.
        assertTrue(requestStands(PlayRequest(1, "a2", a1, 1_000), a1, 1_000))
    }

    @Test
    fun `from nothing playing, the request shows until the player has something`() {
        val tap = PlayRequest(2, null, null, 1_000)
        assertTrue(requestStands(tap, null, 1_500))
        assertFalse(requestStands(tap, b1, 1_600))
        assertFalse(requestStands(null, a1, 1_000))
    }

    @Test
    fun `a cover tap starts the bookmark, the first item, or an item shuffle picks`() {
        val order = listOf("a", "b", "c")
        assertEquals("b" to 60_000L, coverTapStart(order, true, Bookmark("b", 60_000), false))
        assertEquals("a" to 0L, coverTapStart(order, true, null, false))
        assertEquals("a" to 0L, coverTapStart(order, false, Bookmark("b", 60_000), false))
        assertEquals(null to 0L, coverTapStart(order, false, null, true))
        assertNull(coverTapStart(emptyList(), false, null, false))
    }

    @Test
    fun `next and previous targets, never a restart or a guess near it`() {
        assertEquals(1, skipTarget(true, 0, 3, 0, null))
        assertEquals(0, skipTarget(true, 2, 3, 0, null))
        assertEquals(2, skipTarget(false, 0, 3, 0, null))
        assertEquals(0, skipTarget(false, 1, 3, 3_000, null))
        assertNull(skipTarget(false, 1, 3, 4_500, null)) // the player may be past 5 s: it may restart
        assertNull(skipTarget(false, 1, 3, 60_000, null)) // a restart
        assertEquals(0, skipTarget(false, 1, 3, 60_000, PREV_RESTART_MAX_MS + 1)) // long-form steps back
        assertNull(skipTarget(true, 0, 1, 0, null))
    }

    @Test
    fun `quick swipes - the player passing through the first target doesn't end the second`() {
        val first = PlayRequest(2, "b1", a1, 1_000)
        val second = PlayRequest(2, "b2", a1, 1_200, swipeVia(first))
        assertTrue(requestStands(second, a1, 1_300))
        assertTrue("on its way through b1", requestStands(second, b1, 1_400))
        assertFalse("there", requestStands(second, b2, 1_500))
        assertFalse("elsewhere: the player is right", requestStands(second, ItemRef(2, "b3"), 1_500))
        assertEquals(emptySet<ItemRef>(), swipeVia(null))
    }
}
