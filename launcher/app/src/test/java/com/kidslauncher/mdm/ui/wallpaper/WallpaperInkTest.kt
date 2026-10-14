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

    /** The server's built-ins (vibb_night, navy, forest, plum, and since design 34 P8 / migration
     * 0076 the softened green "Fjord", sky "Evening sky" and sunset "Dusk" - all gradients now). */
    private val builtins = listOf(
        listOf(0xFF0C0C14), listOf(0xFF14213D), listOf(0xFF1E4D3A), listOf(0xFF4A2545),
        listOf(0xFF2A4A4F, 0xFF101820), listOf(0xFF34406A, 0xFF14141F), listOf(0xFF5A3A4A, 0xFF1A1420),
    ).map { stops -> stops.map { it.toInt() } }

    @Test
    fun `every built-in reaches 4_5 to 1`() {
        for (stops in builtins) {
            val ink = inkForColours(stops)
            val lums = stops.map(::relativeLuminance)
            assertTrue("$stops", worstContrast(ink, lums.min(), lums.max()) >= 4.5)
            assertFalse(ink.labelShadow)
        }
        // A bright gradient (the old sunset, #F76707 -> #862E9C): white ink judged on its
        // lightest stop, so a scrim is needed.
        assertTrue(inkForColours(listOf(0xFFF76707.toInt(), 0xFF862E9C.toInt())).scrimAlpha > 0.0)
    }

    /** Design 34 P8 (qa-34-design M1): the three softened built-ins take white ink with no scrim
     * and no shadow, 9.59-10.1:1 on their lightest stop (Fjord the lowest). */
    @Test
    fun `the softened built-ins take white ink with no scrim`() {
        for (stops in builtins.takeLast(3)) {
            val ink = inkForColours(stops)
            assertEquals("$stops", INK_LIGHT, ink.ink)
            assertFalse(ink.darkInk)
            assertEquals(0.0, ink.scrimAlpha, 0.0)
            assertFalse(ink.labelShadow)
            val lightest = stops.maxOf(::relativeLuminance)
            assertTrue("$stops", contrastRatio(1.0, lightest) >= 9.5)
        }
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
            0xFF862E9C.toInt(), 0xFF14213D.toInt(), 0xFF0C0C14.toInt(), 0xFF000000.toInt()) +
            builtins.flatten()
        for (argb in backgrounds) {
            val alpha = lockScrimForColours(listOf(argb))
            assertTrue(alpha <= LOCK_MAX_SCRIM)
            for (ink in LOCK_INKS) {
                val c = inkContrastOn(ink, ground(relativeLuminance(argb), alpha))
                assertTrue("%08x on %08x at %.2f: %.2f".format(ink, argb, alpha, c), c >= LABEL_CONTRAST)
            }
        }
        // QA's table: the old Sky and Sunset (bright gradients, softened by design 34 P8) gave the
        // emergency link about 2:1 - not any more. Every built-in of today too.
        for (stops in listOf(listOf(0xFF1C7ED6.toInt(), 0xFF14213D.toInt()), listOf(0xFFF76707.toInt(), 0xFF862E9C.toInt())) + builtins) {
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

    // qa-34-s10-code M1: white ink is judged on the deepest kid surface - the ground under the
    // scrim (as drawn, 8-bit) plus Kid Settings' card and the sound choice's track, white 8 % each.

    /** [argb] over [ground] per channel, 8-bit, as Android draws it. */
    private fun over(argb: Int, ground: Int): Int {
        val a = ((argb ushr 24) and 0xFF) / 255.0
        fun ch(shift: Int) = Math.round(a * ((argb shr shift) and 0xFF) + (1 - a) * ((ground shr shift) and 0xFF)).toInt()
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private val whiteLayer = 0x14FFFFFF

    /** White ink's contrast on [colour] under [ink]'s scrim, then [layers] white 8 % layers. */
    private fun whiteOn(colour: Int, ink: InkChoice, layers: Int): Double {
        var ground = over(ink.scrimArgb, colour)
        repeat(layers) { ground = over(whiteLayer, ground) }
        return contrastRatio(1.0, relativeLuminance(ground))
    }

    @Test
    fun `the kid surfaces are the card's and the track's white 8 percent`() {
        assertEquals(0x14 / 255.0, KID_SURFACE_ALPHA, 1e-12)
        assertEquals(2, KID_SURFACE_LAYERS)
        for (path in listOf("res/drawable/bg_kid_card.xml", "res/drawable/bg_kid_segment_track.xml")) {
            val xml = listOf("src/main", "app/src/main").map { java.io.File(it, path) }.first { it.exists() }.readText()
            assertTrue(path, xml.contains("android:color=\"#14FFFFFF\""))
        }
    }

    @Test
    fun `white ink reaches 4_5 to 1 on the card and the track over mid-tone grounds`() {
        // A parent's bright colours and the white/dark edge greys (luminance ~0.18-0.20).
        val grounds = listOf(0xFF1C7ED6, 0xFF7048E8, 0xFFC92A2A, 0xFF777777, 0xFF7A7A7A, 0xFF7C7C7C, 0xFF747474, 0xFF5C5C5C)
            .map { it.toInt() }
        for (colour in grounds) {
            val ink = inkForColours(listOf(colour))
            assertFalse(ink.darkInk)
            assertFalse(ink.labelShadow)
            for (layers in 0..2) {
                val ratio = whiteOn(colour, ink, layers)
                assertTrue("${Integer.toHexString(colour)} with $layers layers: $ratio", ratio >= 4.5)
            }
        }
        // #1C7ED6 needed 0.05 for the bare ground; on card + track it takes about 0.24.
        val blue = inkForColours(listOf(0xFF1C7ED6.toInt()))
        assertTrue("${blue.scrimAlpha}", blue.scrimAlpha in 0.2..0.3)
    }

    @Test
    fun `a photo whose light side is mid-tone reaches 4_5 to 1 on card and track`() {
        val photo = inkForImage(LuminanceStats(0.12, 0.08))
        assertFalse(photo.darkInk)
        assertFalse(photo.labelShadow)
        assertTrue(photo.scrimAlpha > MIN_IMAGE_SCRIM)
        assertTrue(contrastRatio(1.0, underKidSurfaces(0.20, photo.scrimAlpha)) >= 4.5)
        // A grey of that luminance, drawn per channel.
        val grey = (1..255).last { relativeLuminance((0xFF shl 24) or (it shl 16) or (it shl 8) or it) <= 0.20 }
        val argb = (0xFF shl 24) or (grey shl 16) or (grey shl 8) or grey
        for (layers in 0..2) assertTrue(whiteOn(argb, photo, layers) >= 4.5)
    }

    @Test
    fun `the built-ins keep no scrim and dark ink is unchanged`() {
        for (stops in builtins) {
            val ink = inkForColours(stops)
            assertEquals("$stops", 0.0, ink.scrimAlpha, 0.0)
            assertFalse(ink.labelShadow)
        }
        // Light grounds: dark ink, no scrim; the white layers only help it.
        for (colour in listOf(0xFFFFFFFF.toInt(), 0xFFFFF3B0.toInt(), 0xFFF4F1EA.toInt())) {
            val ink = inkForColours(listOf(colour))
            assertTrue(ink.darkInk)
            assertEquals(0.0, ink.scrimAlpha, 0.0)
        }
    }

    @Test
    fun `the scrim is judged at the alpha it is drawn with`() {
        // No truncation gap: across the whole white-ink range the 8-bit scrim actually drawn
        // still gives the bare ground and both surfaces 4.5:1.
        var lum = 0.0
        while (lum <= 0.41) {
            val ink = inkFor(lum, lum)
            if (!ink.darkInk && !ink.labelShadow) {
                val drawn = drawnScrimAlpha(ink.scrimAlpha)
                assertEquals(((ink.scrimArgb ushr 24) and 0xFF) / 255.0, drawn, 1e-12)
                assertTrue("$lum", contrastRatio(1.0, underScrim(lum, drawn, towardsWhite = false)) >= 4.5)
                assertTrue("$lum", contrastRatio(1.0, underKidSurfaces(lum, ink.scrimAlpha)) >= 4.5)
            }
            lum += 0.005
        }
        assertEquals(0.0, drawnScrimAlpha(0.0), 0.0)
        assertEquals(38 / 255.0, drawnScrimAlpha(0.15), 1e-12)
    }
}
