package me.vibb.music.ui

import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design r8 §2-§3: the sheets and the player close on a drag down - the release rule and the nested-scroll takes. */
class DragDownTest {
    private val density = 2.75f // the Jelly Star's
    private val dp = { v: Float -> v * density }

    @Test
    fun `a release closes past a quarter of the height or on a downward fling, else it springs back`() {
        val sheet = dp(400f)
        assertTrue("past the share", dragCloses(dp(101f), 0f, sheet, density))
        assertFalse("a short slow drag", dragCloses(dp(40f), dp(40f), sheet, density))
        assertTrue("a short fast downward fling", dragCloses(dp(40f), dp(1_000f), sheet, density))
        assertFalse("an upward fling after a long drag", dragCloses(dp(300f), -dp(500f), sheet, density))
        assertTrue("a slow drift back up still closes past the share", dragCloses(dp(300f), -dp(100f), sheet, density))
        // a small sheet: the 56 dp floor, not its quarter
        val small = dp(160f)
        assertFalse(dragCloses(dp(50f), 0f, small, density))
        assertTrue(dragCloses(dp(56f), 0f, small, density))
    }

    @Test
    fun `a list inside scrolls first - the surface takes an upward delta only while moved, a finger's downward leftover only`() {
        assertEquals("moved: an upward delta goes to the surface", -dp(10f), preScrollTake(dp(30f), -dp(10f)), 0.01f)
        assertEquals("at most back to its place", -dp(30f), preScrollTake(dp(30f), -dp(50f)), 0.01f)
        assertEquals("at its place the list scrolls", 0f, preScrollTake(0f, -dp(10f)), 0f)
        assertEquals("a downward delta: the list first", 0f, preScrollTake(dp(30f), dp(10f)), 0f)
        assertEquals("the list at its top: the finger's leftover moves the surface", dp(12f), postScrollTake(dp(12f), NestedScrollSource.UserInput), 0f)
        assertEquals("a fling up to the top never pulls it", 0f, postScrollTake(dp(12f), NestedScrollSource.SideEffect), 0f)
        assertEquals(0f, postScrollTake(-dp(12f), NestedScrollSource.UserInput), 0f)
    }
}
