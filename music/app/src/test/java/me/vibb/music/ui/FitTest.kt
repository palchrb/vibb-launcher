package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `d50 - the skip pair takes the side slots - the row's sizes at 320 dp, the number fixed inside the glyph's ring`() {
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        val controls = player.substringAfter("val row = controlRow(maxWidth.value)").substringBefore("FlexBar(")
        assertEquals("both side slots, at the row's side size", 2, Regex("SkipButton\\([^\\n]*row\\.side\\.dp\\)").findAll(controls).count())
        assertTrue(controls.contains("val skips = now.slots == ControlSlots.SKIP"))
        // the row is the same five slots as before: v15's 44/52/72/52/44 in exactly 288 dp
        assertEquals(288, controlRow(288f).width)
        assertEquals(44, controlRow(288f).side)
        // "15"/"30": Nunito ExtraBold at a fixed 7 dp is 8.4 dp wide; the replay/forward_media ring inside the 20 dp
        // glyph is 15 dp across less its 1.7 dp stroke on each side - 11.7 dp; its centre sits 0.83 dp below the glyph's
        assertEquals(20, SKIP_GLYPH_DP)
        assertTrue(8.4f * SKIP_NUMBER_DP / 7 <= 20f * (720 - 2 * 80) / 960)
        assertEquals(1, SKIP_NUMBER_DROP_DP)
        val button = player.substringAfter("private fun SkipButton(").substringBefore("\n}\n")
        assertTrue("dp, never the font scale's sp", button.contains("SKIP_NUMBER_DP.dp.toSp()"))
    }

    @Test
    fun `d50 - Sov · til slutten is never cut at 320 dp, and Til slutten is the sheet's full-width row`() {
        val liste = 32.0f
        // Nunito ExtraBold 14 sp from the font's advances, as the others
        val atEnd = mapOf("Sov · til slutten" to 101.3f, "Sleep · at the end" to 116.5f)
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            val line = Regex("""<string name="sleep_at_end_on">([^<]*)</string>""").find(moduleFile(path).readText())!!.groupValues[1]
            assertTrue("$path: $line is measured", line in atEnd)
        }
        for (screen in widths) {
            val room = available(screen).toInt() - 2 * 8
            for ((line, width) in atEnd) {
                val w = flexWidths(room, listOf(1f, 1f, 1.2f), listOf(bar(liste), bar(width), 0))
                assertTrue("$screen: $line whole", w[1] >= bar(width))
                assertTrue("$screen: Liste whole", w[0] >= bar(liste))
                assertTrue("$screen: the output keeps its glyph", w[2] > icon)
            }
        }
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue(player.contains("SleepOption(stringResource(R.string.sleep_at_end), atEnd, Modifier.fillMaxWidth(), onAtEnd)"))
        assertTrue("hidden without a duration", player.contains("if (offerAtEnd || atEnd)"))
    }

    @Test
    fun `d49 - the row with both side buttons (shuffle and repeat) fits 320 dp at the side slots' size`() {
        val row = controlRow(available(320f))
        assertEquals("the Jelly Star: exactly its 288 dp", 288, row.width)
        assertEquals("both side slots are buttons of the same size", row.sizes.first(), row.sizes.last())
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        val controls = player.substringAfter("val row = controlRow(maxWidth.value)").substringBefore("FlexBar(")
        assertTrue(controls.contains("Icons.shuffle, stringResource(R.string.shuffle), onShuffle, row.side.dp, 20.dp,"))
        assertTrue(controls.contains("row.side.dp, 20.dp, if (on) Palette.peach else Palette.card"))
        assertTrue("repeat on the right, after next", controls.indexOf("onRepeat") > controls.indexOf("onNext"))
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

    @Test
    fun `a Sonos room in the bar - a long name or a three-member group gives way, Liste and Sov stay whole`() {
        // design 26a §5.1: the room's label is the output's name; Nunito ExtraBold 14 sp as above:
        // "Kjøkken + Stua + Bad" 142.5 dp, "Barnerommet i andre etasje" 184.7 dp
        val labels = listOf(142.5f, 184.7f)
        val liste = 32.0f
        val sov30 = 81.4f
        for (screen in widths) {
            val room = available(screen).toInt() - 2 * 8
            for (label in labels) {
                val w = flexWidths(room, listOf(1f, 1f, 1.2f), listOf(bar(liste), bar(sov30), 0))
                assertEquals("$screen: the row is filled", room, w.sum())
                assertTrue("$screen: Liste whole", w[0] >= bar(liste))
                assertTrue("$screen: Sov whole", w[1] >= bar(sov30))
                val text = w[2] - icon
                assertTrue("$screen: the room keeps its glyph and a readable start of its name ($text dp)", text >= 40)
                if (bar(label) > w[2]) assertTrue("$screen: cut with an ellipsis, never pushing", text < label)
            }
        }
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue("the output's slot shrinks", player.contains("FlexBar(listOf(1f, 1f, 1.2f), shrinks = listOf(false, false, true), gap = 8.dp)"))
        assertTrue("a room's name is its label", player.contains("OutputKind.SONOS -> output.name ?: stringResource(R.string.output_sonos)"))
        val bar = player.substringAfter("private fun BarButton(").substringBefore("\n}\n")
        assertTrue("one line, ellipsized (Line's default)", bar.contains("Line(label, 14, 800, fg)"))
        val widgets = moduleFile("src/main/java/me/vibb/music/ui/Widgets.kt").readText()
        assertTrue(widgets.contains("maxLines: Int = 1") && widgets.contains("overflow = TextOverflow.Ellipsis"))
    }

    @Test
    fun `the player's state line under the entry name fits one line at 320 dp in nb and en`() {
        // the header: the 44 dp back button, the title column with 44 dp at its end (PlayerScreen); Nunito Bold 12 sp
        val longest = mapOf(
            "Får ikke kontakt med høyttaleren" to 184.8f, "Noen andre bruker høyttaleren" to 172.2f,
            "Someone else is using the speaker" to 191.8f, "Can't reach the speaker" to 131.4f, "Spilles i en annen gruppe" to 141.4f,
        ) + spotifyLines
        for (screen in widths) {
            val room = available(screen) - 44 - 44
            for ((line, width) in longest) assertTrue("$screen: $line ($width in $room)", width <= room)
        }
        val player = moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt").readText()
        assertTrue(player.contains("Column(Modifier.weight(1f).padding(end = 44.dp), horizontalAlignment = Alignment.CenterHorizontally) {"))
        assertTrue(player.contains("rendererState?.let { Line(stringResource(rendererStateText(it)), 12, 700, Palette.sub, Modifier.fillMaxWidth(), center = true) }"))
    }

    /**
     * The web player's seven lines in both languages (design 26c §5.8; qa-26c-step5b-code L1; step 8's wording, which names
     * no source), Nunito Bold 12 sp from the font's advances (as the Sonos lines above): the player's state line, and on the
     * tiles (13 sp in 288 dp) they fit too.
     */
    private val spotifyLines = mapOf(
        "Trenger Wi-Fi" to 78.8f, "Trenger internett" to 96.0f, "Ikke tilgjengelig nå" to 106.1f,
        "Spør en voksen" to 85.5f, "Virker ikke på telefonen" to 133.2f, "Spilles et annet sted" to 114.6f,
        "Stoppet - trykk spill" to 111.8f,
        "Needs Wi-Fi" to 69.7f, "Needs the internet" to 103.7f, "Not available right now" to 131.5f,
        "Ask a grown-up" to 89.8f, "Doesn't work on this phone" to 153.4f, "Playing somewhere else" to 135.8f,
        "Stopped - press play" to 116.2f,
    )

    @Test
    fun `every web player line is measured - the strings are the map's, and each fits the tile too`() {
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            val text = moduleFile(path).readText()
            val lines = Regex("""<string name="renderer_spotify_[a-z_]+"[^>]*>([^<]*)</string>""").findAll(text).map { it.groupValues[1].replace("\\'", "'") }.toList()
            assertEquals(path, 7, lines.size)
            for (line in lines) assertTrue("$path: $line is measured", line in spotifyLines)
        }
        for (screen in widths) {
            for ((line, width) in spotifyLines) assertTrue("$screen: $line on a tile", width * 13 / 12 <= available(screen))
        }
    }

    /**
     * A Spotify context's hint (design 26c §5.9, step 6; step 8's wording), "Musikk · rekkefølgen velges for deg" / "Music ·
     * the order is picked for you" (a context is an artist or a playlist: the kind is music), from the font's advances as
     * above: Nunito Bold at 12 sp scaled to the tile's 13 sp, and Nunito ExtraBold 12 sp in the list's header line - one
     * line each at 320 dp.
     */
    @Test
    fun `a Spotify context's hint fits the tile and the list's header`() {
        val bold12 = mapOf("Musikk · rekkefølgen velges for deg" to 198.0f, "Music · the order is picked for you" to 187.0f)
        val extraBold12 = mapOf("Musikk · rekkefølgen velges for deg" to 203.0f, "Music · the order is picked for you" to 191.6f)
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            val text = moduleFile(path).readText()
            val kind = Regex("""<string name="kind_music">([^<]*)</string>""").find(text)!!.groupValues[1]
            val line = Regex("""<string name="hint_spotify_picks">([^<]*)</string>""").find(text)!!.groupValues[1].replace("%1\$s", kind)
            assertTrue("$path: $line is measured", line in bold12 && line in extraBold12)
        }
        for (screen in widths) {
            for ((line, width) in bold12) assertTrue("$screen: $line on a tile", width * 13 / 12 <= available(screen))
            for ((line, width) in extraBold12) assertTrue("$screen: $line in the list", width <= available(screen))
        }
        val library = moduleFile("src/main/java/me/vibb/music/ui/LibraryScreen.kt").readText()
        assertTrue(library.contains("is Hint.Picks -> stringResource(R.string.hint_spotify_picks, kind)"))
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

    // The library's fit (design 34 P10 b, QA L1; qa-34-s11-code L1, N2): the cover from the measured slot in whole
    // pixels, floor LIBRARY_COVER_MIN_DP (64 since N6), then scroll.

    /** Compose's `Dp.roundToPx`, written out here so the model below doesn't lean on the code under test. */
    private fun px(dp: Int, d: Float) = Math.round(dp * d)

    /** A one-line Nunito [Line] of [sp] in pixels (hhea 1011 + 353 per 1000; the layout's height is rounded up). */
    private fun linePx(sp: Int, scale: Float, d: Float) = kotlin.math.ceil(1.364 * sp * scale * d).toInt()

    /** The slot's content as Compose draws it: every fixed part rounded on its own (the pager box, the gaps, the
     * title block's 3 dp gaps, the progress room as padding + line, the dots). */
    private fun drawnContent(coverPx: Int, scale: Float, d: Float): Int =
        (coverPx + px(4, d)) + px(8, d) +
            (linePx(21, scale, d) + px(3, d) + linePx(13, scale, d) + px(3, d) + px(3, d) + px(4, d)) +
            px(8, d) + px(8, d)

    private val densities = listOf(1.5f, 2f, 2.625f, 3f)

    @Test
    fun `L1 - in whole pixels the content never overflows the slot, at every density`() {
        for (d in densities) {
            for (scale in listOf(1.0f, 1.3f, 2.0f)) {
                val lines = linePx(21, scale, d) + linePx(13, scale, d)
                for (slot in px(150, d)..px(420, d)) {
                    val fit = libraryFit(slot, lines, 0, d)
                    val content = drawnContent(fit.coverPx, scale, d)
                    val what = "density $d, scale $scale, slot $slot px: cover ${fit.coverPx}, content $content"
                    if (fit.scrolls) {
                        assertEquals("$what: scrolls only at the floor", px(LIBRARY_COVER_MIN_DP, d), fit.coverPx)
                        assertTrue(what, content > slot)
                    } else {
                        assertTrue("$what: not a pixel cut", content <= slot)
                        assertTrue("$what: and the cover takes the room", fit.coverPx == px(CAROUSEL_COVER_DP, d) || content == slot)
                    }
                }
            }
        }
        // the review's case: at 1.5 the fixed rest is 63 px (41 dp would be 61.5)
        assertEquals(63, libraryFixedPx(1.5f))
        assertEquals(82, libraryFixedPx(2f))
        assertEquals(123, libraryFixedPx(3f))
    }

    /**
     * The library at 320 x 569 dp (the Jelly Star), as LibraryScreen lays it out, in pixels: the status bar (24 dp) and
     * the navigation bar (gesture 24 dp, three buttons 48 dp) off, the 10 + 12 dp padding, the 52 dp header, 8 dp between
     * the children; the category tiles (the 44 dp circle, 4 dp, the 11 sp label; at least 64) and the bar (6 + 6 dp
     * around the larger of the 44 dp button and its 13 + 11 sp lines) keep their own height, the weighted slot gets the
     * rest. Text grows linearly with the font scale here (Android 14's curve grows large text less): an upper bound.
     */
    private fun slotPx(scale: Float, bar: Boolean, nav: Int, d: Float): Int {
        val tiles = maxOf(px(64, d), px(44, d) + px(4, d) + linePx(11, scale, d))
        val nowBar = 2 * px(6, d) + maxOf(px(44, d), linePx(13, scale, d) + linePx(11, scale, d))
        val children = if (bar) 4 else 3
        return Math.round(569 * d) - px(24, d) - px(nav, d) - px(10, d) - px(12, d) - px(52, d) - (children - 1) * px(8, d) -
            tiles - (if (bar) nowBar else 0)
    }

    private fun fitOnJelly(scale: Float, bar: Boolean, nav: Int, d: Float = 1.5f): LibraryFit {
        val lines = linePx(21, scale, d) + linePx(13, scale, d)
        val reserve = if (bar) 0 else nowBarRoomPx(linePx(13, scale, d) + linePx(11, scale, d), d)
        return libraryFit(slotPx(scale, bar, nav, d), lines, reserve, d)
    }

    @Test
    fun `the library on the Jelly Star at font scale 1_0, 1_3 and 2_0`() {
        // v12's cover at 1.0, a little less at 1.3; at 2.0 with a 24 dp status bar the cover gives way (N6: no scroll)
        assertEquals(270, fitOnJelly(1.0f, true, 48).coverPx)
        val at13 = fitOnJelly(1.3f, true, 48)
        assertTrue("1.3: ${at13.coverPx} px", at13.coverPx in 250 until 270 && !at13.scrolls)
        assertTrue("2.0 with gestures: no scroll", !fitOnJelly(2.0f, true, 24).scrolls)
        val at20 = fitOnJelly(2.0f, true, 48)
        assertTrue("2.0 with three buttons: ${at20.coverPx} px, no scroll", !at20.scrolls && at20.coverPx in px(LIBRARY_COVER_MIN_DP, 1.5f) until px(120, 1.5f))
    }

    // --- N6 (emulator round 2026-10-14): the library at font scale 2.0 with the bar, as the emulator drew it ---

    /** Android 14+'s nonlinear font scaling at 2.0 (`FontScaleConverterTable`): an sp in dp, linear between the table's points. */
    private fun nonlinear20(sp: Int): Float {
        val from = floatArrayOf(8f, 10f, 12f, 14f, 18f, 20f, 24f, 30f, 100f)
        val to = floatArrayOf(16f, 20f, 24f, 26f, 30f, 34f, 36f, 38f, 100f)
        val i = from.indexOfLast { it <= sp }
        if (from[i] == sp.toFloat()) return to[i]
        return to[i] + (to[i + 1] - to[i]) * (sp - from[i]) / (from[i + 1] - from[i])
    }

    /** A one-line Nunito [Line] of [sp] at font scale 2.0 as Android 16 draws it (the nonlinear curve), in pixels. */
    private fun linePx20(sp: Int, d: Float) = kotlin.math.ceil(1.364 * nonlinear20(sp) * d).toInt()

    /**
     * The emulator's screen (`wm density 240`, 320 x 569 dp, Android 16) at font scale 2.0 with the bar, as
     * `d34-music-full-bar-2.0.png` shows it: the status bar grows with the font scale to 136 px (91 dp, not the model's
     * 24 dp), the category tiles are 118 px and the bar 116 px; [nav] the navigation bar (gestures 24 dp, three buttons 48).
     */
    private fun emulatorSlot20(nav: Int, d: Float = 1.5f): Int {
        val tiles = maxOf(px(64, d), px(44, d) + px(4, d) + linePx20(11, d))
        val nowBar = 2 * px(6, d) + maxOf(px(44, d), linePx20(13, d) + linePx20(11, d))
        return Math.round(569 * d) - 136 - px(nav, d) - px(10, d) - px(12, d) - px(52, d) - 3 * px(8, d) - tiles - nowBar
    }

    @Test
    fun `N6 - at font scale 2_0 with the bar the hint and the dots fit - the cover gives way before the words`() {
        val d = 1.5f
        val slot = emulatorSlot20(nav = 24)
        assertEquals("the emulator's slot: the header's bottom to the tiles' top (241..545 px, less a rounding)", 301, slot)
        val lines = linePx20(21, d) + linePx20(13, d)
        // step 11's floor of 120 dp needed 65 px more than the slot: the hint cut in half, the dots gone (the report)
        val old = librarySlotContentPx(px(120, d), lines, d) - slot
        assertTrue("the old floor overflowed by $old px", old in 50..80)
        val fit = libraryFit(slot, lines, 0, d)
        assertFalse("no scroll needed: every line shows", fit.scrolls)
        assertTrue("the cover gives way: ${fit.coverPx} px", fit.coverPx in px(LIBRARY_COVER_MIN_DP, d) until px(120, d))
        assertTrue("drawn as Compose rounds it, not a pixel cut", drawnContentAt20(fit.coverPx, d) <= slot)
        // the linear model (an upper bound of the text) still fits, at the floor
        val linear = libraryFit(slot - 2, linePx(21, 2f, d) + linePx(13, 2f, d), 0, d)
        assertFalse(linear.scrolls)
        // three-button navigation leaves less: the floor, and the slot scrolls (never a clip without a scroll)
        val three = libraryFit(emulatorSlot20(nav = 48), lines, 0, d)
        assertTrue(three.scrolls)
        assertEquals(px(LIBRARY_COVER_MIN_DP, d), three.coverPx)
        // without the bar the cover is the same (N2), the bar's room kept free
        val reserve = nowBarRoomPx(linePx20(13, d) + linePx20(11, d), d)
        val without = libraryFit(slot + reserve, lines, reserve, d)
        assertEquals(fit.coverPx, without.coverPx)
    }

    /** [drawnContent] with the nonlinear 2.0 lines. */
    private fun drawnContentAt20(coverPx: Int, d: Float): Int =
        (coverPx + px(4, d)) + px(8, d) +
            (linePx20(21, d) + px(3, d) + linePx20(13, d) + px(3, d) + px(3, d) + px(4, d)) +
            px(8, d) + px(8, d)

    @Test
    fun `N6 - a category tile is as wide as its label, never cut to Mu…`() {
        val room = 288f
        // labels at 11 sp ExtraBold, 0.6 em a character (an upper bound of Nunito's), at font scale 1.0 and 2.0 (22 dp)
        fun label(text: String, dpPerSp: Float) = text.length * 0.6f * 11 * dpPerSp
        for (scale in listOf(1f, nonlinear20(11) / 11)) {
            for (text in listOf("Alle", "Musikk", "Podkaster", "Lydbøker")) {
                val w = categoryTileWidthFor(room, label(text, scale))
                assertTrue("$scale $text: the label fits", w >= label(text, scale))
                assertTrue("$scale $text: never narrower than a fifth", w >= categoryTileWidth(room) - 0.001f)
            }
        }
        assertEquals("a short label keeps the fifth", categoryTileWidth(room), categoryTileWidthFor(room, 20f), 0.001f)
        assertEquals("never wider than the row", room, categoryTileWidthFor(room, 1_000f), 0.001f)
        // the screen measures each label at the font scale and gives its tile that width
        val tiles = library.substringAfter("private fun CategoryTiles(").substringBefore("private fun CategoryTile(")
        assertTrue(tiles.contains("categoryTileWidthFor(room, labelDp[name] ?: 0f)"))
        assertTrue("measured once per names and font scale (N2)", tiles.contains("remember(measurer, density.density, density.fontScale, names)"))
        assertTrue(tiles.contains("measurer.measure(name, text(CATEGORY_LABEL_SP.sp, 800, Palette.white), maxLines = 1)"))
        assertTrue(library.contains("Line(label, CATEGORY_LABEL_SP, 800,"))
    }

    @Test
    fun `N6 - a cover below 120 dp drops its kind chip, the hint line says the kind`() {
        assertTrue(coverShowsKind(120f) && coverShowsKind(180f))
        assertFalse(coverShowsKind(119f))
        assertFalse(coverShowsKind(LIBRARY_COVER_MIN_DP.toFloat()))
        assertTrue(library.contains("CoverBadges(e, coverShowsKind(cover))"))
        assertTrue(body(library, "private fun CoverBadges(").contains("if (kind) {"))
    }

    @Test
    fun `N2 - the cover is the same with and without the bar`() {
        for (d in densities) {
            for (nav in listOf(24, 48)) {
                for (scale in listOf(1.0f, 1.3f, 2.0f)) {
                    val with = fitOnJelly(scale, true, nav, d)
                    val without = fitOnJelly(scale, false, nav, d)
                    assertEquals("density $d, nav $nav, scale $scale", with.coverPx, without.coverPx)
                    assertTrue("without the bar the slot never needs more scroll", !without.scrolls || with.scrolls)
                }
            }
        }
        assertEquals("the bar's room at 1.5: 9 + 66 + 9 px and the 12 px gap", 96, nowBarRoomPx(40, 1.5f))
    }

    @Test
    fun `the carousel at the floor - the neighbours still peek, the pull and glyph follow the cover`() {
        for (screen in widths) {
            val room = available(screen)
            for (cover in listOf(LIBRARY_COVER_MIN_DP.toFloat(), 120f, 150f, 180f)) {
                assertTrue("$screen/$cover: the cover fits", carouselSide(room, cover) >= 0f && cover + 2 * carouselSide(room, cover) <= room + 0.001f)
                assertTrue("$screen/$cover: the neighbours peek", carouselPeek(room, cover) >= 46f - 0.001f)
            }
        }
        assertEquals("v12's 24 dp pull", 24f, carouselPull(180f), 0.05f)
        assertEquals(84f, carouselGlyph(180f), 0.001f)
        assertEquals(56f, carouselGlyph(120f), 0.001f)
        assertEquals("the default is v12's 180", carouselSide(288f, 180f), carouselSide(288f), 0.001f)
    }

    /** A source file with every run of whitespace as one space (qa-34-s11-code L2: checks pin meaning, not layout). */
    private fun flat(path: String) = moduleFile(path).readText().replace(Regex("\\s+"), " ")

    private val library get() = flat("src/main/java/me/vibb/music/ui/LibraryScreen.kt")

    private fun body(text: String, start: String) = text.substringAfter(start).substringBefore(" } /**")

    @Test
    fun `LibraryScreen - one weighted slot, the cover from libraryFit, scroll only below the floor`() {
        val screen = library.substringAfter("fun LibraryScreen(").substringBefore("private fun Carousel(")
        assertEquals("only the header's weighted spacer", 1, Regex("Spacer\\(Modifier\\.weight").findAll(screen).count())
        assertTrue("the carousel is the slot", Regex("Carousel\\([^;]*Modifier\\.weight\\(1f\\)").containsMatchIn(screen.substringBefore("if (tiles")))
        assertTrue("the tiles and the bar are unweighted", !screen.substringAfter("if (tiles.isNotEmpty())").substringBefore("/**").contains("weight("))
        val carousel = body(library, "private fun Carousel(")
        assertTrue(carousel.contains("libraryFit("))
        assertTrue("the scroll exists only below the floor", carousel.contains("if (fit.scrolls) Modifier.verticalScroll("))
        assertEquals("one verticalScroll", 1, Regex("verticalScroll\\(").findAll(carousel).count())
        assertTrue("the progress room is always kept", body(library, "private fun CarouselTitle(").contains("progressRoomPx("))
        assertTrue("no fixed cover sizes left", !library.contains("180.dp") && !library.contains("184.dp"))
    }

    /**
     * Design 39 §4: the list's line - the summary and the order words - Nunito ExtraBold 12 sp from the font's advances
     * (as above), the glyph and its gap (22 dp) before it. The longest pair at 320 dp: whole at font scale 1.0 in both
     * languages; at 1.3 (text grows linearly here, an upper bound) the English line drops its words.
     */
    @Test
    fun `d39 - the list's line keeps the order words where they fit, else drops them`() {
        val longest = mapOf("40 av 40 lastet ned · fra siste bok" to 189.5f, "40 of 40 downloaded · from the last book" to 236.6f)
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            val text = moduleFile(path).readText()
            fun s(name: String) = Regex("""<string name="$name">([^<]*)</string>""").find(text)!!.groupValues[1]
            val line = s("summary_with_order").replace("%1\$s", s("some_downloaded").replace("%1\$d", "40").replace("%2\$d", "40"))
                .replace("%2\$s", s("order_from_last_book"))
            assertTrue("$path: $line is measured", line in longest)
        }
        val jelly = available(320f)
        for ((line, width) in longest) assertTrue("$line at 1.0", orderWordsFit(width, glyph = true, availableDp = jelly))
        assertFalse("the English line at 1.3 drops its words", orderWordsFit(236.6f * 1.3f, glyph = true, availableDp = jelly))
        assertTrue("the Norwegian line at 1.3 keeps them", orderWordsFit(189.5f * 1.3f, glyph = true, availableDp = jelly))
        assertFalse("the glyph counts", orderWordsFit(280f, glyph = true, availableDp = jelly))
        assertTrue(orderWordsFit(280f, glyph = false, availableDp = jelly))
        val list = flat("src/main/java/me/vibb/music/ui/ListScreens.kt")
        assertTrue("the screen measures the line at the font scale", list.contains("if (orderWordsFit(px / density.density, glyph, room)) full else summary"))
    }

    @Test
    fun `d39 - a row's picture decodes small - inSampleSize keeps at least the slot`() {
        assertEquals(8, thumbSampleSize(512, 60))
        assertEquals(4, thumbSampleSize(512, 120))
        assertEquals(8, thumbSampleSize(512, 64))
        assertEquals(4, thumbSampleSize(512, 65))
        assertEquals("never above the file", 1, thumbSampleSize(100, 120))
        assertEquals(1, thumbSampleSize(512, 0))
        val widgets = flat("src/main/java/me/vibb/music/ui/Widgets.kt")
        assertTrue("inSampleSize, not inScaled", widgets.contains("inSampleSize = thumbSampleSize(minOf(bounds.outWidth, bounds.outHeight), px)"))
        assertTrue("its own slot, keyed on hash and size", widgets.contains("val key = hash?.let { if (decodePx == null) it else \"\$it@\$decodePx\" }"))
    }

    @Test
    fun `soft shadows - 12 dp under the centred cover, P2's 2 dp under a neighbour and the bar (P10 c)`() {
        assertTrue("centred 12, neighbour 2", library.contains("lerp(COVER_ELEVATION_DP, NEIGHBOUR_ELEVATION_DP, f)"))
        assertTrue(library.contains("COVER_ELEVATION_DP = 12f") && library.contains("NEIGHBOUR_ELEVATION_DP = 2f"))
        assertEquals(2, BAR_ELEVATION_DP)
        assertTrue(body(library, "fun NowPlayingBar(").contains("shadow(BAR_ELEVATION_DP.dp"))
    }
}
