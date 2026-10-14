package com.kidslauncher.mdm.ui.home

import com.kidslauncher.mdm.appdisplay.AppGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 34 P1 (QA L9): the six calm tones and the rule that picks one for an app. */
class IconTonesTest {
    private val ground = 0xFF0C0C14.toInt()

    private fun lum(argb: Int) = relativeLuminance(argb)

    private fun swatch(rgb: Long, population: Int = 100) = ToneSwatch(rgb.toInt(), population)

    @Test
    fun `six tones, white on each at least 5_3 to 1, each at least 3_5 to 1 on the night`() {
        assertEquals(6, ICON_TONES.size)
        assertEquals(6, ICON_TONES.toSet().size)
        for (tone in ICON_TONES) {
            val label = "%08X".format(tone)
            assertEquals(label, 0xFF, tone ushr 24)
            assertTrue(label, contrastRatio(1.0, lum(tone)) >= 5.3)
            assertTrue(label, contrastRatio(lum(tone), lum(ground)) >= 3.5)
            // No amber, no green, no red (call, hang-up and emergency).
            val (h, s, _) = hsl(tone)
            if (s > 0.2f) {
                assertFalse("$label amber", h in 20f..70f)
                assertFalse("$label green", h in 75f..165f)
                assertFalse("$label red", h < 15f)
            }
        }
    }

    @Test
    fun `the parent's colours are tones`() {
        // QA M3: design 14's keys stay, their tiles are the tones (peach rose, plum plum, sky blue,
        // sunset indigo, night teal, grey slate).
        val expected = mapOf(
            "peach" to TONE_ROSE, "plum" to TONE_PLUM, "sky" to TONE_BLUE,
            "sunset" to TONE_INDIGO, "night" to TONE_TEAL, "grey" to TONE_SLATE,
        )
        assertEquals(expected, AppGlyphs.COLORS.mapValues { it.value.tile })
    }

    @Test
    fun `every output is one of the six`() {
        val inputs = listOf(
            emptyList(),
            listOf(swatch(0xFFFFD43B)),
            listOf(swatch(0xFF0DBD8B)),
            listOf(swatch(0xFF7950F2)),
            listOf(swatch(0xFFF76707)),
            listOf(swatch(0xFFFFFFFF)),
            listOf(swatch(0xFF000000)),
            listOf(swatch(0xFF868E96)),
            listOf(swatch(0xFF4285F4), swatch(0xFFEA4335), swatch(0xFFFBBC05), swatch(0xFF34A853)),
            listOf(swatch(0xFF1C7ED6, 0)),
        )
        for (swatches in inputs) {
            for (pkg in listOf(null, "", "org.example.a", "com.whatsapp", "io.element.android.x")) {
                assertTrue("$swatches $pkg", iconTone(swatches, pkg) in ICON_TONES)
            }
        }
        for (hue in 0 until 360 step 5) assertTrue("$hue", nearestTone(hue.toFloat()) in ICON_TONES)
    }

    @Test
    fun `Google's four colours never land between 30 and 60 degrees`() {
        val google = listOf(0xFF4285F4, 0xFFEA4335, 0xFFFBBC05, 0xFF34A853)
        val mixes = google.map { listOf(swatch(it)) } +
            listOf(google.map { swatch(it) }) +
            listOf(listOf(swatch(0xFFFBBC05, 300), swatch(0xFF34A853, 100)))
        for (swatches in mixes) {
            for (pkg in listOf("com.android.chrome", "com.google.android.apps.maps", "org.example.unknown")) {
                val (h, _, _) = hsl(iconTone(swatches, pkg))
                assertFalse("$swatches $pkg -> $h", h in 30f..60f)
            }
        }
    }

    @Test
    fun `one dominant hue picks the nearest tone`() {
        assertEquals(TONE_BLUE, iconTone(listOf(swatch(0xFF1C7ED6)), "org.example.a"))
        assertEquals(TONE_TEAL, iconTone(listOf(swatch(0xFF0DBD8B)), "org.example.a"))
        assertEquals(TONE_PLUM, iconTone(listOf(swatch(0xFF9C36B5)), "org.example.a"))
        assertEquals(TONE_INDIGO, iconTone(listOf(swatch(0xFF5C5FD6)), "org.example.a"))
        assertEquals(TONE_ROSE, iconTone(listOf(swatch(0xFFE03131)), "org.example.a"))
        // Yellow (the old mustard) never stays yellow: the nearest tone is rose.
        assertEquals(TONE_ROSE, iconTone(listOf(swatch(0xFFFFD43B)), "org.example.a"))
        // A white glyph on a blue disc: the white is no colour of its own.
        assertEquals(TONE_BLUE, iconTone(listOf(swatch(0xFF1C7ED6, 70), swatch(0xFFFFFFFF, 30)), "org.example.a"))
        // A small second colour doesn't make it multi-colour.
        assertEquals(TONE_BLUE, iconTone(listOf(swatch(0xFF1C7ED6, 90), swatch(0xFFE03131, 10)), "org.example.a"))
        // Slate is never a hue's tone.
        for (hue in 0 until 360) assertTrue("$hue", nearestTone(hue.toFloat()) != TONE_SLATE)
    }

    @Test
    fun `multi-colour and grey icons get a tone by the package, stable`() {
        val four = listOf(swatch(0xFF4285F4), swatch(0xFFEA4335), swatch(0xFFFBBC05), swatch(0xFF34A853))
        val grey = listOf(swatch(0xFF868E96), swatch(0xFFFFFFFF), swatch(0xFF212529))
        for (pkg in listOf("org.example.a", "org.example.b", "com.whatsapp")) {
            assertEquals(pkg, hashedTone(pkg), iconTone(four, pkg))
            assertEquals(pkg, hashedTone(pkg), iconTone(grey, pkg))
            assertEquals(pkg, iconTone(four, pkg), iconTone(four.reversed(), pkg))
        }
        // The JVM's String.hashCode is specified: the same package keeps its tone across runs.
        assertEquals(ICON_TONES[Math.floorMod("org.example.a".hashCode(), 6)], hashedTone("org.example.a"))
        // Different packages spread over the tones.
        assertTrue((0 until 60).map { hashedTone("org.example.app$it") }.toSet().size >= 5)
        assertEquals(TONE_SLATE, hashedTone(null))
        assertEquals(TONE_SLATE, iconTone(emptyList(), null))
    }

    @Test
    fun `the known multi-colour apps have their fixed tone`() {
        val four = listOf(swatch(0xFF4285F4), swatch(0xFFEA4335), swatch(0xFFFBBC05), swatch(0xFF34A853))
        // Mockup 01: Drive teal, Photos plum, Gmail rose, Calendar blue, Maps indigo.
        assertEquals(TONE_TEAL, iconTone(four, "com.google.android.apps.docs"))
        assertEquals(TONE_PLUM, iconTone(four, "com.google.android.apps.photos"))
        assertEquals(TONE_ROSE, iconTone(four, "com.google.android.gm"))
        assertEquals(TONE_BLUE, iconTone(four, "com.google.android.calendar"))
        assertEquals(TONE_INDIGO, iconTone(listOf(swatch(0xFF34A853)), "com.google.android.apps.maps"))
        assertTrue(FIXED_TONES.values.all { it in ICON_TONES })
    }

    @Test
    fun `hue distance wraps`() {
        assertEquals(10f, hueDistance(355f, 5f), 0.001f)
        assertEquals(180f, hueDistance(0f, 180f), 0.001f)
        assertEquals(0f, hueDistance(720f, 0f), 0.001f)
    }
}
