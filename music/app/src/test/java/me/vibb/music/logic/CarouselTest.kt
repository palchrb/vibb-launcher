package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.model.Category
import me.vibb.music.ui.dotWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Ports of palchrb/vibb `tests/ui_carousel_slide.py` and `cat_carousel.py` (MIT, Copyright (c)
 * 2026 palchrb), for the phone's carousel (design 21 §4.2). The Pi's frame counting, GPIO polling
 * and volume/BT overlays (slide 1-2 frame counts, 5a-6) have no counterpart; what carries over is
 * the index logic: a press during a slide is never lost, a tap plays the landed cover, and with
 * animations off a flip still changes the index (the screen jumps). The category cases follow the
 * single-choice tiles of mockup v12.
 */
class CarouselTest {
    private val n = 3

    // ui_carousel_slide.py

    @Test
    fun `wrapping pages - any page maps to an entry, and the start page is mid-laps`() {
        val start = carouselStartPage(n, 0)
        assertEquals(0, carouselIndex(start, n))
        assertEquals(2, carouselIndex(start - 1, n))
        assertEquals(n * CAROUSEL_LAPS, carouselPageCount(n))
        assertEquals("one entry doesn't wrap", 1, carouselPageCount(1))
        assertEquals(0, carouselStartPage(1, 5))
    }

    @Test
    fun `1 with animations off a flip still changes the index`() {
        val start = carouselStartPage(n, 0)
        assertEquals(1, carouselIndex(neighbourTapTarget(start, +1, carouselPageCount(n)), n))
    }

    @Test
    fun `3 the mash rule - a tap caught mid-slide is never lost`() {
        val start = carouselStartPage(n, 0)
        // A slide to +1 is under way (target = start + 1); another tap on the right turns from the target.
        val second = neighbourTapTarget(start + 1, +1, carouselPageCount(n))
        assertEquals(2, carouselIndex(second, n))
    }

    @Test
    fun `4 a tap mid-slide plays the landed entry, never the departing one`() {
        val start = carouselStartPage(n, 0)
        assertEquals(1, coverTapIndex(start + 1, n))
    }

    @Test
    fun `a tap - the landing or sliding-in cover plays the landed entry, a neighbour turns from the target`() {
        val count = carouselPageCount(n)
        val start = carouselStartPage(n, 0)
        assertEquals(CarouselTap.Play(0), carouselTap(start, start, start, n, count))
        // Sliding from start to start+1: a tap on the cover still on screen plays where it lands.
        assertEquals(CarouselTap.Play(1), carouselTap(start, start + 1, start, n, count))
        assertEquals(CarouselTap.Play(1), carouselTap(start + 1, start + 1, start, n, count))
        // A tap on the right neighbour mid-slide turns one more, from the target.
        assertEquals(CarouselTap.TurnTo(start + 2), carouselTap(start + 2, start + 1, start, n, count))
        assertEquals(CarouselTap.TurnTo(start), carouselTap(start - 1, start + 1, start + 1, n, count))
    }

    // cat_carousel.py

    private val music = Category(1, "Musikk", "music_note", "rose", 10)
    private val empty = Category(2, "Tom", "star", "gold", 20)
    private val stories = Category(3, "Eventyr", "star", "gold", 30)
    private val entries = listOf(entry(1, category = 1), entry(2, category = 3), entry(3, category = 3))

    @Test
    fun `1 the tiles list only categories with entries`() {
        assertEquals(listOf(1L, 3L), categoryTiles(listOf(stories, empty, music), entries).map { it.id })
    }

    @Test
    fun `3 a category shows only its own entries, 4 and flips stay inside it`() {
        val shown = shownEntries(entries, 3)
        assertEquals(listOf(2L, 3L), shown.map { it.id })
        val start = carouselStartPage(shown.size, 0)
        assertEquals(3L, shown[carouselIndex(neighbourTapTarget(start, +1, carouselPageCount(shown.size)), shown.size)].id)
    }

    @Test
    fun `5 a re-tap or Alle shows everything again`() {
        assertEquals(3L, tapCategory(null, 3))
        assertNull(tapCategory(3, 3))
        assertNull(tapCategory(3, null))
        assertEquals(1L, tapCategory(3, 1))
        assertEquals(entries, shownEntries(entries, null))
    }

    @Test
    fun `7 a category that emptied out falls back to all`() {
        assertEquals(entries, shownEntries(entries, 2))
        assertNull(effectiveCategory(entries, 2))
        assertEquals(3L, effectiveCategory(entries, 3))
    }

    @Test
    fun `equal dots - a window of at most 20 that keeps the current one in view`() {
        assertEquals(0 until 7, dotWindow(7, 3))
        assertEquals(0 until 20, dotWindow(30, 2))
        assertEquals(10 until 30, dotWindow(30, 29))
        assertEquals(5 until 25, dotWindow(30, 15))
    }
}
