package com.kidslauncher.mdm.ui.home

import android.app.WallpaperColors
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
}
