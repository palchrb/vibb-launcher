package com.kidslauncher.mdm.ui.wallpaper

import com.kidslauncher.mdm.ui.home.contrastRatio
import com.kidslauncher.mdm.ui.home.relativeLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WallpaperInkTest {

    private fun inkLum(choice: InkChoice) = if (choice.darkInk) relativeLuminance(INK_DARK) else 1.0

    /** The contrast the labels get on the worst part of a background. */
    private fun worstContrast(choice: InkChoice, darkest: Double, lightest: Double): Double {
        val worst = if (choice.darkInk) darkest else lightest
        return contrastRatio(inkLum(choice), underScrim(worst, choice.scrimAlpha, towardsWhite = choice.darkInk))
    }

    @Test
    fun `navy gets white ink and no scrim`() {
        val ink = inkForFill(WallpaperFill.Solid(NAVY_ARGB))!!
        assertEquals(INK_LIGHT, ink.ink)
        assertEquals(0.0, ink.scrimAlpha, 0.0)
        assertFalse(ink.labelShadow)
        assertFalse(ink.darkInk)
    }

    @Test
    fun `a light ground gets dark ink`() {
        val ink = inkForFill(WallpaperFill.Solid(0xFFF4F1EA.toInt()))!!
        assertEquals(INK_DARK, ink.ink)
        assertEquals(0.0, ink.scrimAlpha, 0.0)
        // And white ink there would be far below 4.5:1.
        assertTrue(contrastRatio(1.0, relativeLuminance(0xFFF4F1EA.toInt())) < 1.2)
        assertTrue(contrastRatio(relativeLuminance(INK_DARK), relativeLuminance(0xFFF4F1EA.toInt())) >= 4.5)
    }

    @Test
    fun `every built-in reaches 4_5 to 1`() {
        val builtins = listOf(
            listOf(0xFF14213D), listOf(0xFF1E4D3A), listOf(0xFF4A2545), listOf(0xFF2B8A3E),
            listOf(0xFF1C7ED6, 0xFF14213D), listOf(0xFFF76707, 0xFF862E9C),
        ).map { stops -> stops.map { it.toInt() } }
        for (stops in builtins) {
            val ink = inkForColours(stops)
            val lums = stops.map(::relativeLuminance)
            assertTrue("$stops", worstContrast(ink, lums.min(), lums.max()) >= 4.5)
            assertFalse(ink.labelShadow)
        }
        // The sunset gradient: white ink judged on its lightest stop, so a scrim is needed.
        assertTrue(inkForColours(listOf(0xFFF76707.toInt(), 0xFF862E9C.toInt())).scrimAlpha > 0.0)
    }

    @Test
    fun `a bright photo gets a scrim, a dark one the minimum`() {
        val bright = inkForImage(LuminanceStats(0.85, 0.05))
        assertTrue(bright.darkInk)
        assertTrue(bright.scrimAlpha >= MIN_IMAGE_SCRIM)
        assertTrue(worstContrast(bright, 0.80, 0.90) >= 4.5)

        val dark = inkForImage(LuminanceStats(0.03, 0.02))
        assertFalse(dark.darkInk)
        assertEquals(MIN_IMAGE_SCRIM, dark.scrimAlpha, 1e-9)
        assertFalse(dark.labelShadow)
    }

    @Test
    fun `a busy mid-grey photo falls back to a label shadow`() {
        // Mean 0.4, std-dev 0.4 (black and white patches): white is judged on 0.8, dark ink on
        // 0 - neither reaches 4.5:1 within the 0.45 cap.
        val busy = inkForImage(LuminanceStats(0.4, 0.4))
        assertEquals(MAX_SCRIM, busy.scrimAlpha, 1e-9)
        assertTrue(busy.labelShadow)
        assertFalse(busy.darkInk)
    }

    @Test
    fun `a mid-grey photo with some spread still gets there with a scrim`() {
        val grey = inkForImage(LuminanceStats(0.2, 0.18))
        assertFalse(grey.labelShadow)
        assertTrue(worstContrast(grey, 0.02, 0.38) >= 4.5)
    }

    @Test
    fun `white ink on the light ground needs more than the cap`() {
        // The symmetric case QA asked for: if white were forced on #F4F1EA, 0.45 black isn't
        // enough - which is why the ink flips to dark instead.
        val lum = relativeLuminance(0xFFF4F1EA.toInt())
        assertTrue(contrastRatio(1.0, underScrim(lum, MAX_SCRIM, towardsWhite = false)) < 4.5)
        assertTrue(contrastRatio(relativeLuminance(INK_DARK), underScrim(lum, 0.0, towardsWhite = true)) >= 4.5)
    }

    @Test
    fun `luminance is averaged in linear light`() {
        val stats = luminanceStats(intArrayOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()))
        assertEquals(0.5, stats.mean, 1e-9)
        assertEquals(0.5, stats.sd, 1e-9)
        assertEquals(LuminanceStats(0.0, 0.0), luminanceStats(IntArray(0)))
        // sRGB mid-grey #777777 is about 0.18 in linear light, not 0.47.
        assertEquals(0.184, luminanceStats(intArrayOf(0xFF777777.toInt())).mean, 0.01)
    }

    @Test
    fun `scrim colour follows the ink`() {
        assertEquals(0x26000000, InkChoice(INK_LIGHT, 0.15, false).scrimArgb)
        assertEquals(0x26FFFFFF, InkChoice(INK_DARK, 0.15, false).scrimArgb)
        assertEquals(0xBF1B1B1F.toInt(), InkChoice(INK_DARK, 0.0, false).inkDim)
    }

    /** Design 25, "Lock background" (qa-25b-25f M3): every ink the lock draws on the wallpaper
     * reaches 4.5:1 - white, the dim date, "Foreldrekode" at 75 %, the red emergency link. */
    @Test
    fun `the lock's scrim gives every lock ink 4_5 to 1 on any background`() {
        fun ground(lum: Double, alpha: Double) = underScrim(lum, ((alpha * 255).toInt()) / 255.0, towardsWhite = false)
        val backgrounds = listOf(0xFFFFFFFF.toInt(), 0xFFFFF3B0.toInt(), 0xFF1C7ED6.toInt(), 0xFFF76707.toInt(),
            0xFF862E9C.toInt(), 0xFF14213D.toInt(), 0xFF0C0C14.toInt(), 0xFF000000.toInt())
        for (argb in backgrounds) {
            val alpha = lockScrimForColours(listOf(argb))
            assertTrue(alpha <= LOCK_MAX_SCRIM)
            for (ink in LOCK_INKS) {
                val c = inkContrastOn(ink, ground(relativeLuminance(argb), alpha))
                assertTrue("%08x on %08x at %.2f: %.2f".format(ink, argb, alpha, c), c >= LABEL_CONTRAST)
            }
        }
        // QA's table: Sky and Sunset gave the emergency link about 2:1 - not any more.
        for (stops in listOf(listOf(0xFF1C7ED6.toInt(), 0xFF14213D.toInt()), listOf(0xFFF76707.toInt(), 0xFF862E9C.toInt()))) {
            val alpha = lockScrimForColours(stops)
            val lightest = stops.maxOf(::relativeLuminance)
            assertTrue(inkContrastOn(0xFFFF8A8A.toInt(), ground(lightest, alpha)) >= LABEL_CONTRAST)
        }
        // Dark grounds need none; the cap reaches every ink even on white.
        assertEquals(0.0, lockScrimForColours(listOf(0xFF0C0C14.toInt())), 1e-9)
        for (ink in LOCK_INKS) assertTrue(inkContrastOn(ink, ground(1.0, LOCK_MAX_SCRIM)) >= LABEL_CONTRAST)
        // A photo: its light side, at least the photo minimum.
        assertEquals(MIN_IMAGE_SCRIM, lockScrimForImage(LuminanceStats(0.001, 0.0)), 1e-9)
        val busy = LuminanceStats(0.5, 0.3)
        for (ink in LOCK_INKS) assertTrue(inkContrastOn(ink, ground(0.8, lockScrimForImage(busy))) >= LABEL_CONTRAST)
        assertEquals(0, blackScrimArgb(0.0))
        assertEquals(0xCC000000.toInt(), blackScrimArgb(0.8))
        // An opaque ink's contrast is the plain ratio.
        assertEquals(contrastRatio(1.0, 0.2), inkContrastOn(0xFFFFFFFF.toInt(), 0.2), 1e-6)
    }

    /** The inks in [LOCK_INKS] are the lock's colours. */
    @Test
    fun `LOCK_INKS are the lock's text colours`() {
        val colours = listOf("src/main", "app/src/main").map { java.io.File(it, "res/values/colors_kid.xml") }.first { it.exists() }.readText()
        assertTrue(colours.contains("<color name=\"kid_core_dim\">#C7FBE4DC</color>"))
        assertTrue(colours.contains("<color name=\"kid_emergency_text\">#FF8A8A</color>"))
        assertTrue(colours.contains("<color name=\"kid_ink\">#FFFFFF</color>"))
        val keypad = listOf("src/main", "app/src/main").map { java.io.File(it, "res/layout/activity_pin_lock.xml") }.first { it.exists() }.readText()
        assertTrue(keypad.substringAfter("@+id/pin_parent_code").substringBefore("/>").contains("android:alpha=\"0.75\""))
    }

    /** qa-25b-25f L1: a second draw of the same ground never paints the photo at the scrim's alpha. */
    @Test
    fun `the photo is drawn with an opaque paint`() {
        val render = listOf("src/main", "app/src/main").map { java.io.File(it, "java/com/kidslauncher/mdm/ui/wallpaper/WallpaperRender.kt") }.first { it.exists() }.readText()
        val image = render.substringAfter("is WallpaperFill.Image ->").substringBefore("canvas.drawBitmap(")
        assertTrue(image.contains("paint.color = 0xFF000000.toInt()"))
    }
}
