package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 79 §5.3: the framing - cover, the clamp, the zoom cap, a small picture at 1x, the cut. */
class PhotoCropTest {
    private val frame = 288f

    @Test
    fun `a new framing covers the frame, centred`() {
        val crop = PhotoCrop.centered(1200, 800)
        assertEquals(1f, crop.zoom)
        assertEquals(800f, crop.visibleSide)
        assertEquals(PhotoCut(200, 0, 800), crop.cut())
        // The short side fills the frame.
        assertEquals(frame / 800, crop.scale(frame), 0.0001f)
        assertEquals(PhotoCut(0, 100, 600), PhotoCrop.centered(600, 800).cut())
    }

    @Test
    fun `the zoom cap - three times at most, a picture under 384 px never enlarged`() {
        assertEquals(3f, photoMaxZoom(1280, 1280))
        assertEquals(2f, photoMaxZoom(768, 1000))
        assertEquals(1f, photoMaxZoom(384, 900))
        assertEquals(1f, photoMaxZoom(200, 300))
        // A pinch can't pass the cap, nor go under cover.
        val big = PhotoCrop.centered(1280, 960).pinched(10f, frame / 2, frame / 2, frame)
        assertEquals(photoMaxZoom(1280, 960), big.zoom)
        assertEquals(1f, big.pinched(0.01f, frame / 2, frame / 2, frame).zoom)
        // A small picture stays at 1x, whatever the fingers do.
        assertEquals(1f, PhotoCrop.centered(300, 400).pinched(4f, 10f, 10f, frame).zoom)
    }

    @Test
    fun `the clamp keeps the frame on the picture after every move`() {
        val crop = PhotoCrop.centered(1200, 800)
        // Dragged far right: the picture's left edge stops at the frame's.
        assertEquals(PhotoCut(0, 0, 800), crop.dragged(5_000f, 0f, frame).cut())
        assertEquals(PhotoCut(400, 0, 800), crop.dragged(-5_000f, 0f, frame).cut())
        // Vertically there is nothing to move at cover.
        assertEquals(crop.cut(), crop.dragged(0f, 300f, frame).cut())
        // Zoomed in, every edge holds.
        val zoomed = crop.pinched(2f, frame / 2, frame / 2, frame)
        assertEquals(400f, zoomed.visibleSide, 0.01f)
        for ((dx, dy) in listOf(5_000f to 5_000f, -5_000f to -5_000f, 5_000f to -5_000f)) {
            val cut = zoomed.dragged(dx, dy, frame).cut()
            assertTrue(cut.left >= 0 && cut.top >= 0 && cut.left + cut.side <= 1200 && cut.top + cut.side <= 800)
        }
    }

    @Test
    fun `a pinch keeps the point under the fingers and a drag follows the finger`() {
        val crop = PhotoCrop.centered(2000, 2000)
        // At the frame's centre: the centre stays.
        val zoomed = crop.pinched(2f, frame / 2, frame / 2, frame)
        assertEquals(1000f, zoomed.centerX, 0.01f)
        assertEquals(1000f, zoomed.centerY, 0.01f)
        // Around a point left of centre: that point stays under the fingers.
        val focus = 72f
        val before = crop.centerX + (focus - frame / 2) / crop.scale(frame)
        val around = crop.pinched(2f, focus, frame / 2, frame)
        val after = around.centerX + (focus - frame / 2) / around.scale(frame)
        assertEquals(before, after, 0.01f)
        // A drag of 10 screen px moves the picture 10 px on screen: the centre goes the other way in picture px.
        val dragged = zoomed.dragged(10f, 0f, frame)
        assertEquals(zoomed.centerX - 10f / zoomed.scale(frame), dragged.centerX, 0.01f)
    }

    @Test
    fun `the cut is a whole square inside the picture`() {
        val odd = PhotoCrop(1001, 777, 1.37f, 9_999f, -50f).clamped()
        val cut = odd.cut()
        assertTrue(cut.side in 1..777)
        assertTrue(cut.left >= 0 && cut.left + cut.side <= 1001)
        assertTrue(cut.top >= 0 && cut.top + cut.side <= 777)
    }
}
