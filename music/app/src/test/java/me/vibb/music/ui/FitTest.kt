package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The screens at 320 dp (the Jelly Star), 360 and 411 dp wide (design 20 "Player controls fit a
 * 320 dp screen", mockup v15): round buttons that stay round, play exactly in the middle, nothing
 * clipped or squeezed. Text widths are Nunito ExtraBold at 14 sp, measured from the font's advances.
 */
class FitTest {
    private val widths = listOf(320f, 360f, 411f)
    private fun available(screen: Float) = screen - 2 * SCREEN_SIDE_DP

    @Test
    fun `the control row fits, every button round, play exactly centred`() {
        for (screen in widths) {
            val room = available(screen)
            val row = controlRow(room)
            assertTrue("$screen: ${row.width} in $room", row.width <= room)
            val edges = controlEdges(row, room)
            assertTrue("$screen: nothing clipped on the left", edges.first().first >= 0f)
            assertTrue("$screen: nothing clipped on the right", edges.last().second <= room)
            val play = edges[2]
            assertEquals("$screen: play centred", room / 2, (play.first + play.second) / 2, 0.001f)
            assertEquals("previous and next alike", row.sizes[1], row.sizes[3])
            for (i in 0 until edges.size - 1) assertEquals("$screen: the gap", row.gap.toFloat(), edges[i + 1].first - edges[i].second, 0.001f)
        }
        assertEquals("the Jelly Star: v15's compact row, exactly its room", CONTROLS_COMPACT, controlRow(288f))
        assertEquals(288, CONTROLS_COMPACT.width)
        assertEquals(listOf(44, 52, 72, 52, 44), CONTROLS_COMPACT.sizes)
        assertEquals(6, CONTROLS_COMPACT.gap)
        assertEquals("360 dp still lacks v12's 332", CONTROLS_COMPACT, controlRow(328f))
        assertEquals("411 dp: v12's row", CONTROLS_ROOMY, controlRow(379f))
        assertEquals(332, CONTROLS_ROOMY.width)
    }

    @Test
    fun `the player uses the rule and round buttons never give in`() {
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue(player.contains("val row = controlRow(maxWidth.value)"))
        assertTrue(player.contains("Spacer(Modifier.requiredSize(row.side.dp))"))
        val controls = player.substringAfter("val row = controlRow(maxWidth.value)").substringBefore("FlexBar(")
        assertEquals("no fixed control sizes left", 0, Regex("RoundButton\\([^)]*\\b(44|56|76)\\.dp").findAll(controls).count())
        val widgets = moduleFile("src/main/java/me/vibb/music/ui/Widgets.kt").readText()
        assertTrue(widgets.contains("modifier.requiredSize(size).clip(CircleShape)"))
    }

    // The bottom bar: Liste and Sov never cut, the output's name gives way (the mockup's flex).

    private val icon = 2 * 2 + 20 + 6 // the bar button's padding, glyph and gap (dp)
    private fun bar(text: Float) = (icon + text).toInt() + 1

    @Test
    fun `Liste and Sov are never cut, the output takes what is left`() {
        val liste = 32.0f
        val sov = 24.7f
        val sov30 = 81.4f
        val sleep30 = 94.2f
        for (screen in widths) {
            val room = available(screen).toInt() - 2 * 8
            for (sleep in listOf(sov, sov30, sleep30)) {
                val w = flexWidths(room, listOf(1f, 1f, 1.2f), listOf(bar(liste), bar(sleep), 0))
                assertEquals("$screen: the row is filled", room, w.sum())
                assertTrue("$screen: Liste whole", w[0] >= bar(liste))
                assertTrue("$screen: Sov whole ($sleep)", w[1] >= bar(sleep))
                assertTrue("$screen: the output keeps room", w[2] > icon)
            }
        }
        // On the Jelly Star with the sleep timer off, the phone's own name shows whole in nb and en.
        val jelly = flexWidths(288 - 16, listOf(1f, 1f, 1.2f), listOf(bar(liste), bar(sov), 0))
        assertTrue("Telefonen", jelly[2] >= bar(66.8f))
        assertTrue("The phone", jelly[2] >= bar(69.9f))
    }

    @Test
    fun `flex widths - shares by weight, mins kept, a zero min gives way`() {
        assertEquals(listOf(100, 100, 120), flexWidths(320, listOf(1f, 1f, 1.2f), listOf(0, 0, 0)))
        assertEquals("a wide middle keeps its min, the others share the rest", listOf(91, 120, 109), flexWidths(320, listOf(1f, 1f, 1.2f), listOf(80, 120, 0)))
        val tight = flexWidths(200, listOf(1f, 1f, 1.2f), listOf(80, 120, 0))
        assertEquals("mins wider than the room: they stay, the shrinker gets nothing", listOf(80, 120, 0), tight)
    }

    @Test
    fun `category tiles and the carousel fit`() {
        for (screen in widths) {
            val room = available(screen)
            assertTrue("$screen: a tile holds its 44 dp circle with room", categoryTileWidth(room) >= 52f)
            assertEquals("$screen: five tiles and four gaps fill the row", room, 5 * categoryTileWidth(room) + 4 * 4, 0.001f)
            assertTrue("$screen: the cover fits", carouselSide(room) >= 0f && CAROUSEL_COVER_DP + 2 * carouselSide(room) <= room + 0.001f)
            assertTrue("$screen: the neighbours peek", carouselPeek(room) >= 46f - 0.001f)
        }
        assertEquals("the mockup's 46 dp on the Jelly Star", 46f, carouselPeek(288f), 0.001f)
    }

    // The player's art (design 27 §3.3).

    @Test
    fun `the episode carousel - the row fills the width, the picture fits its room, neighbours peek`() {
        // (width inside the sides, the box's height) -> the picture: the spec's table.
        val cases = listOf(Triple(288f, 154f, 154f), Triple(288f, 240f, 200f), Triple(328f, 300f, 224f), Triple(379f, 300f, 224f), Triple(288f, 120f, 120f))
        for ((width, room, expected) in cases) {
            val a = playerArt(width, room, carousel = true)
            assertEquals("$width x $room", expected, a, 0.001f)
            assertTrue("$width x $room: within its room", a <= room)
            val peek = playerPeek(width, a)
            assertEquals("$width x $room: two peeks, two gaps and the picture are the row", width, 2 * peek + 2 * PLAYER_PAGE_GAP_DP + a, 0.001f)
            assertTrue("$width x $room: a neighbour peeks at least 36 dp", peek >= PLAYER_PEEK_MIN_DP - 0.001f)
            assertTrue(playerCarousel(width, room, true))
        }
        assertEquals("the Jelly Star: 59 dp each side", 59f, playerPeek(288f, 154f), 0.001f)
        assertEquals("the mockup at 320 x 568: exactly 36", 36f, playerPeek(288f, 200f), 0.001f)
        assertEquals("the pull keeps a neighbour's near edge", 20.6f, playerPull(154f), 0.1f)
        assertEquals(29.9f, playerPull(224f), 0.1f)
        assertEquals("without the carousel: today's cover", 224f, playerArt(288f, 300f, carousel = false), 0.001f)
        assertEquals(154f, playerArt(288f, 154f, carousel = false), 0.001f)
        // A large font or display size: below 96 dp the single cover comes back (QA L2 b).
        assertTrue(playerCarousel(288f, 96f, true))
        assertTrue(!playerCarousel(288f, 95f, true))
        assertTrue(!playerCarousel(288f, 300f, false))
    }

    @Test
    fun `the player's art replaces only the cover box`() {
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue(player.contains("PlayerArt(now, Modifier.weight(1f).fillMaxWidth(), onSkipTo, onTogglePlay)"))
        assertEquals("one picture, drawn by PlayerArt", 1, Regex("PlayerArt\\(").findAll(player).count())
        assertTrue("no cover of its own", !player.contains("Cover("))
    }

    @Test
    fun `covers decode at the player's size, never above the file (code QA L12)`() {
        assertEquals("the Jelly Star (density 1.5)", 336, coverDecodePx(512, 1.5f))
        assertEquals("a 2.625 phone: the whole file", 512, coverDecodePx(512, 2.625f))
        assertEquals(300, coverDecodePx(300, 3f))
    }
}
