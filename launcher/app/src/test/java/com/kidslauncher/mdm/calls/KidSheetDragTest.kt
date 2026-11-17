package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.KidSheetDrag.Release
import com.kidslauncher.mdm.calls.KidSheetDrag.Settle
import com.kidslauncher.mdm.calls.KidSheetDrag.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe down closes the kid's sheets (2026-11-17): tap or drag, close or spring back, the dim and the settle. */
class KidSheetDragTest {
    private val density = 2f
    private val slop = 8f * density // the system's 8 dp
    private val fling = KidSheetDrag.FLING_DP_PER_S * density
    private val height = 900f

    @Test
    fun `the thresholds are pinned`() {
        assertEquals(1000f, KidSheetDrag.FLING_DP_PER_S)
        assertEquals(48f, KidSheetDrag.MIN_FLING_TRAVEL_DP)
        assertEquals(24f, KidSheetDrag.BUTTON_SLOP_DP)
        assertEquals(0.05f, KidSheetDrag.DIM_STEP)
        assertEquals(200L, KidSheetDrag.FULL_MS)
        assertEquals(100L, KidSheetDrag.MIN_MS)
    }

    @Test
    fun `on a button the start needs more than the slop, inside a scrolling list or off a button the slop`() {
        assertEquals(48f, KidSheetDrag.startSlop(slop, density, onChild = true, inScroller = false))
        assertEquals(slop, KidSheetDrag.startSlop(slop, density, onChild = false, inScroller = false))
        assertEquals(slop, KidSheetDrag.startSlop(slop, density, onChild = true, inScroller = true))
        // Twice a large slop wins over 24 dp.
        assertEquals(60f, KidSheetDrag.startSlop(30f, 1f, onChild = true, inScroller = false))
    }

    @Test
    fun `a hurried tap on a button that slips down stays a tap`() {
        val onButton = KidSheetDrag.startSlop(slop, density, onChild = true, inScroller = false)
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(2f, 40f, onButton, contentScrolledDown = false))
        assertEquals(Start.SHEET, KidSheetDrag.start(2f, 49f, onButton, contentScrolledDown = false))
    }

    @Test
    fun `a move inside the slop stays a tap`() {
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(0f, 0f, slop, contentScrolledDown = false))
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(10f, 16f, slop, contentScrolledDown = false))
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(-16f, -12f, slop, contentScrolledDown = false))
    }

    @Test
    fun `a downward vertical drag past the slop is the sheet's`() {
        assertEquals(Start.SHEET, KidSheetDrag.start(0f, 17f, slop, contentScrolledDown = false))
        assertEquals(Start.SHEET, KidSheetDrag.start(-20f, 30f, slop, contentScrolledDown = false))
    }

    @Test
    fun `upwards, sideways, or on content scrolled down, the sheet stays`() {
        assertEquals(Start.NOT_SHEET, KidSheetDrag.start(0f, -17f, slop, contentScrolledDown = false))
        assertEquals(Start.NOT_SHEET, KidSheetDrag.start(40f, 30f, slop, contentScrolledDown = false))
        assertEquals(Start.NOT_SHEET, KidSheetDrag.start(30f, 5f, slop, contentScrolledDown = false))
        assertEquals(Start.NOT_SHEET, KidSheetDrag.start(0f, 40f, slop, contentScrolledDown = true))
    }

    @Test
    fun `the sheet never goes above its resting place`() {
        assertEquals(0f, KidSheetDrag.offset(0f, -50f))
        assertEquals(30f, KidSheetDrag.offset(0f, 30f))
        assertEquals(60f, KidSheetDrag.offset(40f, 20f))
        assertEquals(0f, KidSheetDrag.offset(40f, -60f))
    }

    @Test
    fun `release closes past a third of the height, else springs back`() {
        assertFalse(KidSheetDrag.closes(offset = 299f, height = height, velocity = 0f, density = density))
        assertFalse(KidSheetDrag.closes(offset = 300f, height = height, velocity = 0f, density = density))
        assertTrue(KidSheetDrag.closes(offset = 301f, height = height, velocity = 0f, density = density))
        assertTrue(KidSheetDrag.closes(offset = 800f, height = height, velocity = 500f, density = density))
    }

    @Test
    fun `a fast fling down closes once the sheet is a little way down, a fast fling up springs back`() {
        // The travel: 48 dp (96 px here), or an eighth of a tall sheet.
        assertEquals(96f, KidSheetDrag.minFlingTravel(400f, density))
        assertEquals(112.5f, KidSheetDrag.minFlingTravel(height, density))
        assertTrue(KidSheetDrag.closes(offset = 113f, height = height, velocity = fling, density = density))
        assertFalse(KidSheetDrag.closes(offset = 112f, height = height, velocity = 9 * fling, density = density))
        assertTrue(KidSheetDrag.closes(offset = 96f, height = 400f, velocity = fling, density = density))
        assertFalse(KidSheetDrag.closes(offset = 95f, height = 400f, velocity = fling, density = density))
        assertFalse(KidSheetDrag.closes(offset = 40f, height = height, velocity = fling, density = density))
        assertFalse(KidSheetDrag.closes(offset = 200f, height = height, velocity = fling - 1f, density = density))
        assertFalse(KidSheetDrag.closes(offset = 700f, height = height, velocity = -fling, density = density))
    }

    @Test
    fun `a sheet at rest or of no height never closes`() {
        assertFalse(KidSheetDrag.closes(offset = 0f, height = height, velocity = 9000f, density = density))
        assertFalse(KidSheetDrag.closes(offset = 50f, height = 0f, velocity = 9000f, density = density))
    }

    @Test
    fun `the release - a tap never closes, a cancel springs back, a drag decides`() {
        fun release(dragged: Boolean, cancelled: Boolean, offset: Float, velocity: Float, settling: Boolean) =
            KidSheetDrag.release(dragged, cancelled, offset, height, velocity, density, settling)
        // A tap with the sheet at rest, or a drag back to rest.
        assertEquals(Release.STAY, release(dragged = false, cancelled = false, offset = 0f, velocity = 0f, settling = false))
        assertEquals(Release.STAY, release(dragged = true, cancelled = false, offset = 0f, velocity = 9000f, settling = false))
        // A tap that lands on a spring-back above a third: the spring-back carries on, no close (code QA L4).
        assertEquals(Release.STAY, release(dragged = false, cancelled = false, offset = 700f, velocity = 0f, settling = true))
        assertEquals(Release.SPRING_BACK, release(dragged = false, cancelled = false, offset = 700f, velocity = 0f, settling = false))
        // A cancel (the lock, a system gesture, the focus leaving) never closes.
        assertEquals(Release.SPRING_BACK, release(dragged = true, cancelled = true, offset = 700f, velocity = 9 * fling, settling = false))
        assertEquals(Release.CLOSE, release(dragged = true, cancelled = false, offset = 301f, velocity = 0f, settling = false))
        assertEquals(Release.SPRING_BACK, release(dragged = true, cancelled = false, offset = 299f, velocity = 0f, settling = false))
        assertEquals(Release.CLOSE, release(dragged = true, cancelled = false, offset = 120f, velocity = fling, settling = false))
    }

    @Test
    fun `the dim fades with the drag`() {
        assertEquals(0.5f, KidSheetDrag.dim(0.5f, 0f, height), 0f)
        assertEquals(0.25f, KidSheetDrag.dim(0.5f, 450f, height), 1e-6f)
        assertEquals(0f, KidSheetDrag.dim(0.5f, height, height), 0f)
        assertEquals(0f, KidSheetDrag.dim(0.5f, 2000f, height), 0f)
        assertEquals(0.5f, KidSheetDrag.dim(0.5f, 100f, 0f), 0f)
    }

    @Test
    fun `the dim moves in steps, and always onto the ends`() {
        assertFalse(KidSheetDrag.dimChanges(0.5f, 0.47f, 0.5f))
        assertTrue(KidSheetDrag.dimChanges(0.5f, 0.44f, 0.5f))
        assertTrue(KidSheetDrag.dimChanges(0.02f, 0f, 0.5f))
        assertTrue(KidSheetDrag.dimChanges(0.48f, 0.5f, 0.5f))
        assertFalse(KidSheetDrag.dimChanges(0.5f, 0.5f, 0.5f))
        assertFalse(KidSheetDrag.dimChanges(0f, 0f, 0.5f))
    }

    @Test
    fun `a slow or opposite release eases over up to 200 ms by distance, never under 100 ms`() {
        assertEquals(Settle(200L, decelerate = false), KidSheetDrag.settle(0f, height, height, 0f))
        assertEquals(Settle(150L, decelerate = false), KidSheetDrag.settle(225f, height, height, 0f))
        assertEquals(Settle(100L, decelerate = false), KidSheetDrag.settle(100f, 0f, height, 0f))
        assertEquals(Settle(100L, decelerate = false), KidSheetDrag.settle(0f, 0f, 0f, 0f))
        // Moving away from the target (a fling up into a close is not possible, a fling down into a spring-back is).
        assertEquals(Settle(100L, decelerate = false), KidSheetDrag.settle(100f, 0f, height, 5000f))
        // Too slow to start a 200 ms decelerate at the finger's speed: eased, no jolt.
        assertEquals(Settle(133L, decelerate = false), KidSheetDrag.settle(300f, height, height, 1000f))
    }

    @Test
    fun `a fast release decelerates from the finger's speed`() {
        // 600 px at 6000 px/s: 2 * 600 / 6000 = 200 ms.
        assertEquals(Settle(200L, decelerate = true), KidSheetDrag.settle(300f, height, height, 6000f))
        assertEquals(Settle(150L, decelerate = true), KidSheetDrag.settle(300f, height, height, 8000f))
        assertEquals(Settle(100L, decelerate = true), KidSheetDrag.settle(300f, height, height, 30000f))
        // A spring-back after a fling up.
        assertEquals(Settle(100L, decelerate = true), KidSheetDrag.settle(200f, 0f, height, -4000f))
    }
}
