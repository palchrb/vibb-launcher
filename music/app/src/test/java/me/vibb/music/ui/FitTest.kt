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

    // The library's fit (design 34 P10 b, QA L1): the cover from the measured slot, floor 120, then scroll.

    @Test
    fun `the library cover - min(180, the room the slot leaves), floor 120, and below it the slot scrolls`() {
        assertEquals("41 dp besides the cover and the two lines", 41, LIBRARY_SLOT_FIXED_DP)
        assertEquals("plenty of room: v12's 180", 180f, libraryCover(400f, 46f), 0.001f)
        assertEquals("exactly 180", 180f, libraryCover(180f + 46f + 41f, 46f), 0.001f)
        assertEquals("a little less: the cover gives way", 150f, libraryCover(150f + 46f + 41f, 46f), 0.001f)
        assertEquals("exactly the floor", 120f, libraryCover(120f + 46f + 41f, 46f), 0.001f)
        assertTrue(!libraryScrolls(120f + 46f + 41f, 46f))
        assertEquals("below the floor: 120", 120f, libraryCover(100f, 46f), 0.001f)
        assertTrue("...and the slot scrolls", libraryScrolls(119.9f + 46f + 41f, 46f))
        for (slot in listOf(150f, 200f, 207f, 220f, 267f, 300f)) {
            val cover = libraryCover(slot, 46f)
            val content = librarySlotContent(cover, 46f)
            if (libraryScrolls(slot, 46f)) {
                assertTrue("$slot: taller than the slot only when it scrolls", content > slot)
            } else {
                assertTrue("$slot: the content fits the slot ($content)", content <= slot + 0.001f)
            }
        }
    }

    /** Nunito's line (hhea ascender 1011 + descender 353 per 1000): what Compose gives a one-line [Line] of [sp]. */
    private fun line(sp: Float, scale: Float) = 1.364f * sp * scale

    /**
     * The library at 320 x 569 dp (the Jelly Star), modelled as LibraryScreen lays it out: the status bar (24 dp) and the
     * navigation bar (gesture 24 dp, three buttons 48 dp) off, the 10 + 12 dp padding, the 52 dp header, 8 dp between the
     * children; the category tiles (the 44 dp circle, 4 dp, the 11 sp label; at least 64) and the bar (6 + 6 dp around
     * the larger of the 44 dp button and its 13 + 11 sp lines) keep their own height, the weighted slot gets the rest.
     * Text grows linearly with the font scale here (Android 14's curve grows large text less): an upper bound.
     */
    private fun slot(scale: Float, bar: Boolean, nav: Float): Float {
        val tiles = maxOf(64f, 44f + 4f + line(11f, scale))
        val nowBar = 12f + maxOf(44f, line(13f, scale) + line(11f, scale))
        val children = if (bar) 4 else 3
        return 569f - 24f - nav - 22f - 52f - 8f * (children - 1) - tiles - (if (bar) nowBar else 0f)
    }

    private fun lines(scale: Float) = line(21f, scale) + line(13f, scale)

    @Test
    fun `the library on the Jelly Star at font scale 1_0, 1_3 and 2_0 - the tiles never under the bar`() {
        for (nav in listOf(24f, 48f)) {
            for (bar in listOf(true, false)) {
                for (scale in listOf(1.0f, 1.3f, 2.0f)) {
                    val slot = slot(scale, bar, nav)
                    val cover = libraryCover(slot, lines(scale))
                    val content = librarySlotContent(cover, lines(scale))
                    val what = "nav $nav, bar $bar, scale $scale: slot $slot, cover $cover"
                    assertTrue(what, cover in LIBRARY_COVER_MIN_DP.toFloat()..CAROUSEL_COVER_DP.toFloat())
                    // the slot never overlaps: it fits, or it scrolls within its own height (the tiles and bar stay below it)
                    assertTrue(what, content <= slot + 0.001f || libraryScrolls(slot, lines(scale)))
                }
            }
        }
        // what the screen shows: v12's cover at 1.0, a little less at 1.3, the floor and a short scroll at 2.0 only
        assertEquals(180f, libraryCover(slot(1.0f, true, 48f), lines(1.0f)), 0.001f)
        assertTrue(libraryCover(slot(1.3f, true, 48f), lines(1.3f)) >= 170f)
        assertTrue(!libraryScrolls(slot(1.3f, true, 48f), lines(1.3f)))
        assertTrue("2.0 with gestures: no scroll", !libraryScrolls(slot(2.0f, true, 24f), lines(2.0f)))
        assertTrue("2.0, three buttons and the bar: the floor, scrolling", libraryScrolls(slot(2.0f, true, 48f), lines(2.0f)))
        val over = librarySlotContent(120f, lines(2.0f)) - slot(2.0f, true, 48f)
        assertTrue("a short scroll ($over dp)", over in 0f..20f)
    }

    @Test
    fun `the carousel at the floor - the neighbours still peek, the pull and glyph follow the cover`() {
        for (screen in widths) {
            val room = available(screen)
            for (cover in listOf(120f, 150f, 180f)) {
                assertTrue("$screen/$cover: the cover fits", carouselSide(room, cover) >= 0f && cover + 2 * carouselSide(room, cover) <= room + 0.001f)
                assertTrue("$screen/$cover: the neighbours peek", carouselPeek(room, cover) >= 46f - 0.001f)
            }
        }
        assertEquals("v12's 24 dp pull", 24f, carouselPull(180f), 0.05f)
        assertEquals(84f, carouselGlyph(180f), 0.001f)
        assertEquals(56f, carouselGlyph(120f), 0.001f)
        assertEquals("the default is v12's 180", carouselSide(288f, 180f), carouselSide(288f), 0.001f)
    }

    @Test
    fun `LibraryScreen sizes the carousel from its weighted slot`() {
        val library = moduleFile("src/main/java/me/vibb/music/ui/LibraryScreen.kt").readText()
        val screen = library.substringAfter("fun LibraryScreen(").substringBefore("\n}\n")
        assertTrue("the carousel fills the weighted slot", screen.contains("Carousel(entries, startIndex.coerceIn(entries.indices), onIndex, onPlay, Modifier.weight(1f).fillMaxWidth())"))
        assertTrue("so does the empty state", screen.contains("Modifier.weight(1f).fillMaxWidth(),"))
        assertEquals("no weighted spacers around the carousel, only the header's", 1, Regex("Spacer\\(Modifier.weight").findAll(screen).count())
        val tail = screen.substringAfter("if (tiles.isNotEmpty())")
        assertTrue("the tiles and the bar come after the slot, unweighted", tail.contains("CategoryTiles(tiles, selected, onCategory)") && tail.contains("NowPlayingBar(now, onOpenPlayer, onTogglePlay)") && !tail.contains("weight("))
        val carousel = library.substringAfter("private fun Carousel(").substringBefore("\n}\n")
        assertTrue(carousel.contains("BoxWithConstraints(modifier)"))
        assertTrue(carousel.contains("val cover = libraryCover(slot, lines)"))
        assertTrue("scrolls below the floor", carousel.contains(".verticalScroll(rememberScrollState(), enabled = libraryScrolls(slot, lines)).heightIn(min = maxHeight)"))
        assertTrue(carousel.contains("Arrangement.spacedBy(CAROUSEL_GAP_DP.dp, Alignment.CenterVertically)"))
        assertTrue("the lines are measured", library.contains("measurer.measure(\"Ag\", text(21.sp, 800, Palette.white), maxLines = 1)") && library.contains("measurer.measure(\"Ag\", text(13.sp, 700, Palette.sub), maxLines = 1)"))
        val pager = library.substringAfter("private fun CarouselPager(").substringBefore("\n}\n")
        for (use in listOf("height((cover + CAROUSEL_BOX_EXTRA_DP).dp)", "carouselSide(maxWidth.value, cover)", "PageSize.Fixed(cover.dp)", "Modifier.size(cover.dp)", "carouselGlyph(cover).dp", "carouselPull(cover)")) {
            assertTrue(use, pager.contains(use))
        }
        assertTrue("no fixed cover sizes left", !library.contains("180.dp") && !library.contains("184.dp") && !library.contains("CAROUSEL_COVER_DP.dp"))
        val title = library.substringAfter("private fun CarouselTitle(").substringBefore("\n}\n")
        assertTrue("the progress line's 3 + 3 + 4 dp are always kept", title.contains("Spacer(Modifier.height(7.dp))") && title.contains("Modifier.padding(top = 3.dp).width(140.dp).height(4.dp)"))
        assertTrue(title.contains("verticalArrangement = Arrangement.spacedBy(3.dp)"))
        assertEquals(13, CAROUSEL_TITLE_FIXED_DP)
    }

    @Test
    fun `soft shadows - 12 dp under the centred cover, P2's 2 dp under a neighbour and the bar (P10 c)`() {
        val library = moduleFile("src/main/java/me/vibb/music/ui/LibraryScreen.kt").readText()
        assertTrue(library.contains("shadowElevation = lerp(COVER_ELEVATION_DP, NEIGHBOUR_ELEVATION_DP, f).dp.toPx()"))
        assertTrue(library.contains("private const val COVER_ELEVATION_DP = 12f") && library.contains("private const val NEIGHBOUR_ELEVATION_DP = 2f"))
        assertEquals(2, BAR_ELEVATION_DP)
        val bar = library.substringAfter("fun NowPlayingBar(").substringBefore("\n}\n")
        assertTrue(bar.contains(".shadow(BAR_ELEVATION_DP.dp, RoundedCornerShape(16.dp)).background(Palette.card)"))
    }
}
