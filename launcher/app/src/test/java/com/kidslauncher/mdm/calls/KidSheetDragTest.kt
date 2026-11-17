package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.KidSheetDrag.Start
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Swipe down closes the kid's sheets (2026-11-17): tap or drag, close or spring back, the dim and the settle. */
class KidSheetDragTest {
    private val slop = 24f
    private val fling = 2750f // 1000 dp/s at density 2.75

    @Test
    fun `a move inside the slop stays a tap`() {
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(0f, 0f, slop, contentScrolledDown = false))
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(10f, 24f, slop, contentScrolledDown = false))
        assertEquals(Start.UNDECIDED, KidSheetDrag.start(-24f, -20f, slop, contentScrolledDown = false))
    }

    @Test
    fun `a downward vertical drag past the slop is the sheet's`() {
        assertEquals(Start.SHEET, KidSheetDrag.start(0f, 25f, slop, contentScrolledDown = false))
        assertEquals(Start.SHEET, KidSheetDrag.start(-20f, 30f, slop, contentScrolledDown = false))
    }

    @Test
    fun `upwards, sideways, or on content scrolled down, the sheet stays`() {
        assertEquals(Start.NOT_SHEET, KidSheetDrag.start(0f, -25f, slop, contentScrolledDown = false))
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
        assertFalse(KidSheetDrag.closes(offset = 299f, height = 900f, velocity = 0f, flingVelocity = fling))
        assertFalse(KidSheetDrag.closes(offset = 300f, height = 900f, velocity = 0f, flingVelocity = fling))
        assertTrue(KidSheetDrag.closes(offset = 301f, height = 900f, velocity = 0f, flingVelocity = fling))
        assertTrue(KidSheetDrag.closes(offset = 800f, height = 900f, velocity = 500f, flingVelocity = fling))
    }

    @Test
    fun `a fast fling down closes, a fast fling up springs back`() {
        assertTrue(KidSheetDrag.closes(offset = 40f, height = 900f, velocity = fling, flingVelocity = fling))
        assertFalse(KidSheetDrag.closes(offset = 40f, height = 900f, velocity = fling - 1f, flingVelocity = fling))
        assertFalse(KidSheetDrag.closes(offset = 700f, height = 900f, velocity = -fling, flingVelocity = fling))
    }

    @Test
    fun `a sheet at rest or of no height never closes`() {
        assertFalse(KidSheetDrag.closes(offset = 0f, height = 900f, velocity = 9000f, flingVelocity = fling))
        assertFalse(KidSheetDrag.closes(offset = 50f, height = 0f, velocity = 9000f, flingVelocity = fling))
    }

    @Test
    fun `the dim fades with the drag`() {
        assertEquals(0.5f, KidSheetDrag.dim(0.5f, 0f, 900f), 0f)
        assertEquals(0.25f, KidSheetDrag.dim(0.5f, 450f, 900f), 1e-6f)
        assertEquals(0f, KidSheetDrag.dim(0.5f, 900f, 900f), 0f)
        assertEquals(0f, KidSheetDrag.dim(0.5f, 2000f, 900f), 0f)
        assertEquals(0.5f, KidSheetDrag.dim(0.5f, 100f, 0f), 0f)
    }

    @Test
    fun `the settle is 200 ms for the whole height, shorter for less, never under 100 ms`() {
        assertEquals(200L, KidSheetDrag.settleMs(0f, 900f, 900f))
        assertEquals(150L, KidSheetDrag.settleMs(225f, 900f, 900f))
        assertEquals(100L, KidSheetDrag.settleMs(100f, 0f, 900f))
        assertEquals(100L, KidSheetDrag.settleMs(0f, 0f, 0f))
    }
}
