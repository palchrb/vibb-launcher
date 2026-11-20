package com.kidslauncher.mdm.ui.home

import android.app.WallpaperColors
import com.kidslauncher.mdm.ui.wallpaper.groundHintKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** PLAN "Home: contacts with a name pill, its colour from Android" (2026-11-19): Android's wallpaper hint to the pill's
 * and the ring's colours (mockups "lyst/mørkt/urolig bilde"). */
class NamePillTest {
    @Test
    fun `the hint bit is Android's`() {
        // A compile-time constant: inlined, so the JVM test never calls into android.jar.
        assertEquals(WallpaperColors.HINT_SUPPORTS_DARK_TEXT, HINT_SUPPORTS_DARK_TEXT_BIT)
    }

    @Test
    fun `the colour hints to supports-dark-text, none staying unknown`() {
        assertNull(supportsDarkText(null))
        assertEquals(false, supportsDarkText(0))
        assertEquals(true, supportsDarkText(HINT_SUPPORTS_DARK_TEXT_BIT))
        // Other hints (dark theme = 2, from the bitmap = 4) don't count.
        assertEquals(false, supportsDarkText(2 or 4))
        assertEquals(true, supportsDarkText(HINT_SUPPORTS_DARK_TEXT_BIT or 4))
    }

    @Test
    fun `a light wallpaper gets the dark pill and ring, white name`() {
        val style = namePillStyle(true)
        assertEquals(0xFF2A2630.toInt(), style.pill)
        assertEquals(0xFFFFFFFF.toInt(), style.text)
        assertEquals(0xFF2A2630.toInt(), style.ring)
    }

    @Test
    fun `any other wallpaper, and none read, gets the white pill and ring, dark name`() {
        for (hint in listOf(false, null)) {
            val style = namePillStyle(hint)
            assertEquals(0xFFFFFFFF.toInt(), style.pill)
            assertEquals(0xFF1B1B28.toInt(), style.text)
            assertEquals(0xF2FFFFFF.toInt(), style.ring)
        }
    }

    @Test
    fun `the name reads on its pill, and both versions have an outline and a shadow`() {
        for (style in listOf(NAME_PILL_ON_LIGHT, NAME_PILL_ON_DARK)) {
            assertTrue(contrastRatio(relativeLuminance(style.text), relativeLuminance(style.pill)) >= 7.0)
            // An outline, faint (a thin edge, not a frame), and a shadow, never full black.
            val outlineAlpha = style.outline ushr 24
            assertTrue(outlineAlpha in 0x20..0x60)
            val shadowAlpha = style.shadow ushr 24
            assertTrue(shadowAlpha in 0x40..0xC0)
        }
        // The dark pill's outline is light, the white pill's dark.
        assertEquals(0xFFFFFF, NAME_PILL_ON_LIGHT.outline and 0xFFFFFF)
        assertEquals(0x1B1B28, NAME_PILL_ON_DARK.outline and 0xFFFFFF)
    }

    /** Code QA M2: one line fully rounded, two lines a rounded rectangle - at font 1.0 and 2.0 alike. */
    @Test
    fun `the corner is half a one-line pill, so two lines are a rounded rectangle`() {
        // 14 sp Nunito at 2.625 density: about 50 px a line at 1.0, 100 at 2.0; 3 dp padding = 8 px.
        for (line in listOf(50, 100)) {
            val pad = 8
            val radius = namePillCornerPx(line, pad)
            val oneLine = line + 2 * pad
            val twoLines = 2 * line + 2 * pad
            assertEquals(oneLine / 2f, radius, 0.001f)
            // Two lines: the straight side is a whole line high - the curve stays within the padding's reach of the
            // corner, never across the middle of the text.
            assertTrue(twoLines - 2 * radius >= line)
        }
    }

    /** Code QA M1/L7: the ground and the pill's hint come in one state - a change of either restyles Home's contacts in
     * the same pass, and nothing else does. */
    @Test
    fun `a new hint or a new ink restyles the contacts, the same pair does not`() {
        val dark = com.kidslauncher.mdm.ui.wallpaper.inkForColours(listOf(0xFF14213D.toInt()))
        val light = com.kidslauncher.mdm.ui.wallpaper.inkForColours(listOf(0xFFF7F2EC.toInt()))
        assertEquals(false, callPartsRestyleDue(dark, false, dark, false))
        assertEquals(true, callPartsRestyleDue(dark, false, dark, true))
        assertEquals(true, callPartsRestyleDue(dark, false, light, false))
        assertEquals(true, callPartsRestyleDue(dark, false, light, true))
    }

    /** The hint's cache key: the drawn fill and the scrim; a photo not decoded is the placeholder, never the photo. */
    @Test
    fun `the ground's hint is keyed by what is drawn`() {
        val photo = com.kidslauncher.mdm.ui.wallpaper.WallpaperFill.Image("a".repeat(64))
        val navy = com.kidslauncher.mdm.ui.wallpaper.WallpaperFill.Solid(0xFF14213D.toInt())
        val key = ::groundHintKey
        assertEquals("image:${"a".repeat(64)}|26000000", key(photo, true, 0x26000000))
        assertEquals("placeholder|26000000", key(photo, false, 0x26000000))
        assert(key(photo, true, 0x26000000) != key(photo, true, 0x40FFFFFF))
        assertEquals("solid:ff14213d|00000000", key(navy, false, 0))
    }
}
