package me.vibb.music.logic

import me.vibb.music.playback.PlaybackService
import me.vibb.music.sonos.SonosTail
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
    fun `while the service still has the command waiting, the hold is longer - bounded`() {
        // A Sonos room switch's hand-off or the look at a recorded room: the play runs seconds later.
        val tap = PlayRequest(2, null, a1, 1_000)
        assertTrue(requestStands(tap, a1, 1_000 + SWITCH_HOLD_MS, waiting = true))
        assertTrue(requestStands(tap, a1, 1_000 + SWITCH_WAIT_MAX_MS - 1, waiting = true))
        assertFalse(requestStands(tap, a1, 1_000 + SWITCH_WAIT_MAX_MS, waiting = true))
        // the command ran (refused, or silently nothing): the plain hold again
        assertFalse(requestStands(tap, a1, 1_000 + SWITCH_HOLD_MS, waiting = false))
        // the player getting there ends it, waiting or not
        assertFalse(requestStands(tap, b1, 1_500, waiting = true))
        assertEquals(1_000 + SWITCH_HOLD_MS, holdEndsAt(tap, false))
        assertEquals(1_000 + SWITCH_WAIT_MAX_MS, holdEndsAt(tap, true))
        // a room switch's hand-off, which can queue behind a Storytel play's mint (design 26b §5.1: 25 s)
        assertTrue(SWITCH_WAIT_MAX_MS > PlaybackService.HANDOFF_MS)
        assertEquals(25_000L, PlaybackService.HANDOFF_MS)
        assertTrue("the tail is the hand-off's budget", SonosTail.MAX_MS > PlaybackService.HANDOFF_MS)
        // a fresh start: the destroyed service's tail, then the adopt's look (PlaybackService.ADOPT_MS, 4 s), then the load
        assertTrue(SWITCH_WAIT_MAX_MS >= SonosTail.MAX_MS + PlaybackService.ADOPT_MS + 4_000)
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

    @Test
    fun `quick swipes on a room - passing through holds while the swipe still waits, then the plain bound`() {
        // The merge of design 27 into design 26a: a swipe (SKIP_TO) is in Commands.MOVES, so behind a
        // room switch's hand-off it holds up to SWITCH_WAIT_MAX_MS, and the player passing through the
        // first swipe's target doesn't end the second one meanwhile.
        val first = PlayRequest(2, "b1", a1, 1_000)
        val second = PlayRequest(2, "b2", a1, 1_200, swipeVia(first))
        assertTrue(requestStands(second, b1, 1_200 + SWITCH_HOLD_MS, waiting = true))
        assertTrue(requestStands(second, a1, 1_200 + SWITCH_WAIT_MAX_MS - 1, waiting = true))
        assertFalse(requestStands(second, b1, 1_200 + SWITCH_WAIT_MAX_MS, waiting = true))
        assertFalse(requestStands(second, b1, 1_200 + SWITCH_HOLD_MS, waiting = false))
        assertFalse("there, waiting or not", requestStands(second, b2, 1_300, waiting = true))
        assertFalse("before its own tap: never", requestStands(second, a1, 1_100, waiting = true))
    }
}
