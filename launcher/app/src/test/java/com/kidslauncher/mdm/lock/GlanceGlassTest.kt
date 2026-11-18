package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.ui.home.contrastRatio
import com.kidslauncher.mdm.ui.home.relativeLuminance
import com.kidslauncher.mdm.ui.wallpaper.WallpaperFill
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** Frosted glass B on the glance's cards (2026-11-17, lock/GlanceGlass.kt and its glue lock/GlanceGlassCards.kt). */
class GlanceGlassTest {
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private val white = 0xFFFFFFFF.toInt()

    @Test
    fun `glass only over a decoded photo - colours, gradients, the night mark and an unread photo stay solid`() {
        assertTrue(glassOn(WallpaperFill.Image("a".repeat(64)), decoded = true))
        assertFalse(glassOn(WallpaperFill.Image("a".repeat(64)), decoded = false))
        assertFalse(glassOn(WallpaperFill.Solid(white), decoded = true))
        assertFalse(glassOn(WallpaperFill.Gradient(white, 0xFF000000.toInt()), decoded = true))
        assertFalse(glassOn(WallpaperFill.NightMark, decoded = true))
        assertFalse(glassOn(null, decoded = true))
    }

    @Test
    fun `the copy is small - one pixel per 4 dp`() {
        assertEquals(99 to 219, glassCopySize(1080, 2400, 2.75f))
        assertEquals(80 to 143, glassCopySize(480, 854, 1.5f))
        assertEquals(null, glassCopySize(0, 854, 1.5f))
    }

    @Test
    fun `the three boxes make the CSS blur's Gaussian`() {
        val sigma = glassSigmaCells()
        assertEquals(5.5, sigma, 0.0)
        val boxes = gaussBoxes(sigma)
        assertTrue(boxes.all { it % 2 == 1 })
        // Each box of width w adds (w² - 1) / 12 to the variance.
        val variance = boxes.sumOf { (it * it - 1) / 12.0 }
        assertTrue("$variance", abs(variance - sigma * sigma) / (sigma * sigma) < 0.1)
    }

    @Test
    fun `the blur keeps a flat picture flat to its edges and spreads a spot evenly`() {
        val flat = IntArray(20 * 30) { 0xFF336699.toInt() }
        assertTrue(glassBlur(flat, 20, 30, 5.5).all { it == 0xFF336699.toInt() })
        val w = 41
        val spot = IntArray(w * w) { 0xFF000000.toInt() }
        spot[20 * w + 20] = white
        val out = glassBlur(spot, w, w, 3.0)
        fun red(x: Int, y: Int) = (out[y * w + x] shr 16) and 0xFF
        assertEquals(red(15, 20), red(25, 20))
        assertEquals(red(20, 15), red(20, 25))
        assertTrue(red(20, 20) >= red(23, 20))
        assertEquals(0, red(0, 0))
    }

    @Test
    fun `saturate leaves greys alone and pushes colour out`() {
        val px = intArrayOf(0xFF808080.toInt(), 0xFF806040.toInt())
        glassSaturate(px, GLASS_SATURATION)
        assertEquals(0xFF808080.toInt(), px[0])
        assertTrue(((px[1] shr 16) and 0xFF) > 0x80)
        assertTrue((px[1] and 0xFF) < 0x40)
    }

    /** The colour resources the glass names, as the layouts use them. */
    @Test
    fun `the tint is kid_music_tile's and the inks are the cards' own`() {
        val colours = file("res/values/colors_kid.xml").readText()
        fun colour(name: String) = Regex("<color name=\"$name\">#([0-9A-Fa-f]{6,8})</color>").find(colours)!!.groupValues[1].uppercase()
        assertEquals("%06X".format(GLASS_TINT_RGB), colour("kid_music_tile"))
        assertEquals(
            listOf("FFFFFF", "C7FBE4DC", "7CC4F2"),
            listOf(colour("kid_ink"), colour("kid_core_dim"), colour("kid_rain")),
        )
        assertEquals(GLASS_INKS, listOf(0xFFFFFFFF.toInt(), 0xC7FBE4DC.toInt(), 0xFF7CC4F2.toInt()))
        assertEquals(Math.round(0.58 * 255).toInt(), GLASS_MIN_TINT)
    }

    @Test
    fun `the mockup's 58 percent over a dark picture, raised only as far as the inks need`() {
        assertEquals(GLASS_MIN_TINT, glassTintAlpha(0xFF101018.toInt()))
        assertEquals(GLASS_MIN_TINT, glassTintAlpha(0xFF404040.toInt()))
        // White text over a white blurred background (the brief's check): 58 % alone gives 4.2:1, so it is raised.
        val fixed = glassOver((GLASS_MIN_TINT shl 24) or GLASS_TINT_RGB, white)
        assertTrue(contrastRatio(1.0, relativeLuminance(fixed)) < 4.5)
        val alpha = glassTintAlpha(white)
        assertTrue(alpha > GLASS_MIN_TINT)
        val card = glassOver((alpha shl 24) or GLASS_TINT_RGB, white)
        assertTrue(contrastRatio(1.0, relativeLuminance(card)) >= 4.5)
        assertTrue(glassInksRead(card))
        // ... and only as far as needed: one step less fails.
        assertFalse(glassInksRead(glassOver(((alpha - 1) shl 24) or GLASS_TINT_RGB, white)))
        // At most today's solid tile, which every ink reads on.
        assertTrue(glassInksRead(0xFF000000.toInt() or GLASS_TINT_RGB))
        assertEquals(0xFF000000.toInt() or 0x2A2A2A, glassLightest(intArrayOf(0xFF000000.toInt(), 0xFF2A2A2A.toInt(), 0xFF100000.toInt())))
    }

    /** The glue: which cards, the ground passed from the lock, no cross-window blur anywhere. */
    @Test
    fun `the glance's cards take the glass from the lock's own ground`() {
        val panel = file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText()
        for (card in listOf(
            "glass.add(glance.lockMediaTile, R.drawable.bg_music_tile)",
            "glass.add(glance.lockMediaCompact, R.drawable.bg_glance_compact_tile)",
            "glass.add(glance.lockTimerTile, R.drawable.bg_music_tile)",
            "glass.add(glance.lockWeatherCard.root, R.drawable.bg_glance_compact_tile)",
            "glass.add(glance.lockRuleCard.root, R.drawable.bg_glance_compact_tile)",
            "glass.add(glance.lockGlanceTorch, R.drawable.bg_music_control)",
            "glass.add(view.root, R.drawable.bg_glance_row)",
            "guarded(\"glass\") { glass.sync() }",
            "glass.hold(button, lit)",
        )) {
            assertTrue(card, panel.contains(card))
        }
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("GlassGround(state.lock.fill, state.lockBitmap, state.lockScrimArgb)"))
        val lock = file("java/com/kidslauncher/mdm/lock").listFiles()!!.joinToString("\n") { it.readText() }
        assertFalse(lock.contains("FLAG_BLUR_BEHIND"))
        assertFalse(lock.contains("setBackgroundBlurRadius"))
    }
}
