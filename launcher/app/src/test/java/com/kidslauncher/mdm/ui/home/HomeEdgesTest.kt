package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.floor

/** Design 34 P4: Home's fading edges, two-line labels, and a contacts row that never runs past the screen. */
class HomeEdgesTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val scales = listOf(1.0f, 1.3f, 2.0f)
    private val densities = listOf(1.5f, 2f, 2.625f, 3f)

    private fun file(path: String): File = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private fun xml(path: String): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file(path))

    private fun byId(doc: Document, id: String): Element {
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as Element }.single { it.getAttributeNS(ns, "id") == "@+id/$id" }
    }

    private fun Element.attr(name: String): String = getAttributeNS(ns, name)

    // --- (a) fading edges ---

    @Test
    fun `the top fades up to 12 dp and the bottom up to 32 dp, ramping with the distance left`() {
        assertEquals(12f, FADE_TOP_DP)
        assertEquals(32f, FADE_BOTTOM_DP)
        // The one length the view has is the longer edge, so neither strength is capped below its length.
        assertEquals(maxOf(FADE_TOP_DP, FADE_BOTTOM_DP), FADE_LENGTH_DP)
        for (density in densities) {
            val lengthPx = FADE_LENGTH_DP * density
            for ((edgeDp, name) in listOf(FADE_TOP_DP to "top", FADE_BOTTOM_DP to "bottom")) {
                val edgePx = edgeDp * density
                fun drawnPx(remaining: Int) = lengthPx * fadeStrength(remaining, edgePx, lengthPx)
                // Full from the edge's length on: 12 dp at the top, 32 dp at the bottom.
                assertEquals(name, edgePx, drawnPx(edgePx.toInt() + 1), 0.001f)
                assertEquals(name, edgePx, drawnPx(10_000), 0.001f)
                // Half way at half the edge, nothing at the end: it ramps, it doesn't snap (qa-34-s4-code L1).
                assertEquals(name, edgePx / 2, drawnPx((edgePx / 2).toInt()), density)
                assertEquals(0f, drawnPx(0))
                assertEquals(0f, drawnPx(-5))
                // One pixel in, the fade is about a pixel, not the whole edge.
                assertTrue(name, drawnPx(1) <= 1.001f)
                // Monotonic over the ramp.
                var last = 0f
                for (r in 0..(edgePx.toInt() + 4)) {
                    val d = drawnPx(r)
                    assertTrue(d >= last)
                    last = d
                }
            }
        }
        // Never more than View.draw's full length; nothing for a zero edge or length.
        assertEquals(1f, fadeStrength(1000, 96f, 48f))
        assertEquals(0f, fadeStrength(10, 0f, 48f))
        assertEquals(0f, fadeStrength(10, 18f, 0f))
        // The remaining distance: at least a pixel while the grid can scroll, nothing when it can't.
        assertEquals(1, remainingScrollPx(true, 0))
        assertEquals(1, remainingScrollPx(true, -3))
        assertEquals(40, remainingScrollPx(true, 40))
        assertEquals(0, remainingScrollPx(false, 40))
    }

    @Test
    fun `the grid is the fading recycler with the one length in the layout`() {
        val home = xml("res/layout/activity_home.xml")
        val grid = byId(home, "home_grid")
        assertEquals("com.kidslauncher.mdm.ui.home.FadingRecyclerView", grid.tagName)
        assertEquals("vertical", grid.attr("requiresFadingEdge"))
        assertEquals("${FADE_LENGTH_DP.toInt()}dp", grid.attr("fadingEdgeLength"))
        // The last row may scroll into the padding; the fade covers the padding too (below).
        assertEquals("false", grid.attr("clipToPadding"))
        val row = byId(home, "home_contacts_scroll")
        assertEquals("horizontal", row.attr("requiresFadingEdge"))
        assertEquals("${CONTACTS_FADE_DP.toInt()}dp", row.attr("fadingEdgeLength"))
        // Home's top stays empty: no new view above the banner, the call card and the contacts.
        val container = home.documentElement
        val firstIds = (0 until container.childNodes.length).map { container.childNodes.item(it) }
            .filterIsInstance<Element>().map { it.attr("id") }
        assertEquals(
            listOf("@+id/home_window_banner", "@+id/home_call_card", "@+id/home_contacts_scroll", "@+id/home_music_card", "@+id/home_grid"),
            firstIds,
        )
    }

    @Test
    fun `the fading recycler only draws - its body is the seven overrides`() {
        val raw = file("java/com/kidslauncher/mdm/ui/home/FadingRecyclerView.kt").readText()
        val code = raw.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), " ").replace(Regex("//[^\n]*"), " ")
        val body = code.substringAfter("class FadingRecyclerView").substringAfter(") : RecyclerView(context, attrs, defStyleAttr) {")
        // Every member is one of the seven overrides (qa-34-s4-code L3): no init block, no property,
        // no other function - so no touch listener or touch method can come in.
        val funs = Regex("\\bfun\\s+(\\w+)").findAll(body).map { it.groupValues[1] }.toList()
        assertEquals("each declared once", funs.size, funs.toSet().size)
        assertEquals(
            setOf(
                "getTopFadingEdgeStrength", "getBottomFadingEdgeStrength", "isPaddingOffsetRequired",
                "getTopPaddingOffset", "getBottomPaddingOffset", "getLeftPaddingOffset", "getRightPaddingOffset",
            ),
            funs.toSet(),
        )
        assertEquals(funs.size, Regex("\\boverride\\s+fun\\b").findAll(body).count())
        for (banned in listOf(Regex("\\binit\\b"), Regex("\\b(val|var)\\s+\\w+\\s*[:=]"), Regex("Listener"), Regex("\\bsetOn"), Regex("\\baddOn"), Regex("Touch"), Regex("\\bobject\\b"))) {
            assertFalse(banned.pattern, banned.containsMatchIn(body))
        }
        val flat = code.replace(Regex("\\s+"), " ")
        // Each edge ramps with what is left to scroll, at its own length.
        assertTrue(flat.contains("remainingScrollPx(canScrollVertically(-1), computeVerticalScrollOffset())"))
        assertTrue(flat.contains("FADE_TOP_DP * resources.displayMetrics.density"))
        assertTrue(flat.contains("canScrollVertically(1), computeVerticalScrollRange() - computeVerticalScrollExtent() - computeVerticalScrollOffset()"))
        assertTrue(flat.contains("FADE_BOTTOM_DP * resources.displayMetrics.density"))
        // The fade covers the padding the grid draws into.
        assertTrue(flat.contains("isPaddingOffsetRequired(): Boolean = !clipToPadding"))
        assertTrue(flat.contains("getTopPaddingOffset(): Int = if (clipToPadding) 0 else -paddingTop"))
        assertTrue(flat.contains("getBottomPaddingOffset(): Int = if (clipToPadding) 0 else paddingBottom"))
    }

    // --- (b) labels ---

    @Test
    fun `grid labels wrap to two lines at every scale`() {
        assertEquals(2, GRID_LABEL_MAX_LINES)
        val tile = xml("res/layout/item_kid_tile.xml")
        val label = byId(tile, "tile_label")
        assertEquals("$GRID_LABEL_MAX_LINES", label.attr("maxLines"))
        assertEquals("end", label.attr("ellipsize"))
        // A single long word ("Innstillinger" at 1.3/2.0) is hyphenated, not cut between letters (N2).
        assertEquals("normal", label.attr("hyphenationFrequency"))
        assertEquals("wrap_content", label.attr("layout_height"))
        assertEquals("wrap_content", tile.documentElement.attr("layout_height"))
        // The model's fixed parts are the layout's: the label's 5 dp margin.
        assertEquals("${TILE_LABEL_GAP_DP.toInt()}dp", label.attr("layout_marginTop"))
        // No code path sets a line count by scale (QA L6: "no row height to compute").
        val adapter = file("java/com/kidslauncher/mdm/ui/home/HomeGridAdapter.kt").readText()
        for (banned in listOf("maxLines", "setLines", "setSingleLine", "isSingleLine", "fontScale")) {
            assertFalse(banned, adapter.contains(banned))
        }
        assertTrue(adapter.contains("height = iconPx + KidAvatars.dp(context, 4f)")) // TILE_BADGE_ROOM_DP
        assertEquals(4f, TILE_BADGE_ROOM_DP)
    }

    @Test
    fun `a two-line row grows with the font scale and a whole row still shows at 2_0 on the Jelly Star`() {
        // Home's column at 320x569 dp: status bar 24 dp, navigation 24 (gestures) or 48 (three buttons),
        // three contacts (76 dp, frame +6, item padding 4 + 4, name 6 dp below, two lines of 15 sp),
        // the row's 4 dp margin, the grid's 18 dp margin; without and with the music card.
        for (cols in 3..4) {
            for (width in listOf(240f, 288f, 360f)) {
                val m = gridMetrics(width, cols)
                var last = 0f
                for (scale in scales) {
                    val two = tileHeightDp(m, scale)
                    val one = tileHeightDp(m, scale, 1)
                    assertTrue(two > one)
                    assertTrue("rows grow with the scale", two > last)
                    last = two
                    // Never more than two lines.
                    assertEquals(two, tileHeightDp(m, scale, 5), 0.001f)
                    // The bottom fade is shorter than a tile: it never hides a whole row.
                    assertTrue(FADE_BOTTOM_DP < one)
                }
            }
            val m = gridMetrics(288f, cols)
            for (scale in scales) {
                for (nav in listOf(24f, 48f)) {
                    val contacts = 4f + 4f + (76f + 6f) + 6f + 2 * 15f * scale * NUNITO_LINE_EM + 4f
                    val grid = 569f - 24f - nav - contacts - 18f
                    val row = tileHeightDp(m, scale)
                    assertTrue("cols $cols, scale $scale, nav $nav: grid $grid, row $row", grid >= row + FADE_BOTTOM_DP)
                    // With the music card under the contacts (qa-34-s4-code N5): 12 dp margin, at least
                    // 72 dp, else 2 x 10 dp padding, two 12 sp lines without font padding and a 15 sp
                    // title with it (counted at 1.6 em). A whole row still shows; at 2.0 the bottom
                    // fade may then reach into that row's labels while more rows are below.
                    val card = 12f + maxOf(72f, 20f + 2 * 12f * scale * NUNITO_LINE_EM + 15f * scale * 1.6f)
                    assertTrue("music: cols $cols, scale $scale, nav $nav", grid - card >= row)
                    if (scale <= 1.3f) assertTrue("music: cols $cols, scale $scale, nav $nav", grid - card >= row + FADE_BOTTOM_DP)
                }
            }
        }
        // The Jelly Star at 2.0 with three buttons, 3 columns: about 140 dp a row in about 297 dp.
        val m = gridMetrics(288f, 3)
        assertEquals(139.9f, tileHeightDp(m, 2.0f), 0.1f)
    }

    // --- the fold: never between an icon and its label (PLAN "Home's fold at four rows") ---

    private data class Screen(val widthDp: Float, val heightDp: Float, val statusDp: Float, val density: Float)

    private val jellyStar = Screen(320f, 569f, 24f, 1.5f)
    private val sixInch = Screen(411f, 914f, 48f, 2.625f)

    /** The grid's height: the screen less the bars, the contacts row (one-line names, as the
     * screenshot's) with its 4 dp margin, and the grid's 18 dp margin. */
    private fun gridHeightDp(s: Screen, navDp: Float, scale: Float): Float =
        s.heightDp - s.statusDp - navDp - (4f + 4f + (76f + 6f) + 6f + 15f * scale * NUNITO_LINE_EM + 4f) - 18f

    /** The grid's rows as Android lays them out from the top, in px: [lines] is each row's label line count. */
    private fun rowsOf(s: Screen, cols: Int, scale: Float, lines: List<Int>, rowCount: Int): List<GridRow> {
        val m = gridMetrics(s.widthDp - 32f, cols)
        fun px(dp: Float) = (dp * s.density).toInt()
        var top = 0f
        return (0 until rowCount).map { i ->
            val tile = tileHeightDp(m, scale, lines.getOrElse(i) { lines.last() })
            val icon = top + TILE_BADGE_ROOM_DP
            val row = GridRow(
                px(icon + m.iconDp / 2f),
                px(icon + m.iconDp + TILE_LABEL_GAP_DP + m.labelSp * scale * NUNITO_LINE_EM),
                px(top + tile),
            )
            top += tile + GRID_ROW_GAP_DP
            row
        }
    }

    private class Fold(val heightPx: Int, val rows: List<GridRow>, val density: Float) {
        val fadePx = (FADE_BOTTOM_DP * density).toInt()
        val basePx = (GRID_BOTTOM_PADDING_DP * density).toInt()
        val shown = gridFoldPaddingPx(rows, true, heightPx, fadePx, basePx)
        /** The grid's drawn bottom edge: the view's own, or the clip. */
        val edgePx = if (shown > 0) heightPx - shown else heightPx

        /** Where [edge] lands in row [i] (the rule restated: a label is legible above the middle of the fade that
         * is drawn there - shorter for the last row when little is left to scroll). */
        fun cut(edge: Int, clipped: Boolean): String {
            for ((i, row) in rows.withIndex()) {
                val fade = if (!clipped && i == rows.lastIndex) (row.bottomPx + basePx - edge).coerceIn(0, fadePx) else fadePx
                if (2 * row.labelLinePx <= 2 * edge - fade) continue
                return if (row.iconMidPx >= edge) "row ${i + 1} at most half its icon" else "row ${i + 1} icon without its label"
            }
            return "every label legible"
        }
    }

    private fun fold(s: Screen, navDp: Float, scale: Float, lines: List<Int>, tiles: Int, cols: Int = 3): Fold {
        val rows = rowsOf(s, cols, scale, lines, (tiles + cols - 1) / cols)
        return Fold((gridHeightDp(s, navDp, scale) * s.density).toInt(), rows, s.density)
    }

    /**
     * The measured cases (the model above: the Jelly Star 320x569 dp at 240 dpi, the six-inch AVD 411x914 at 420,
     * three contacts, no music card; gestures 24 dp, three buttons 48 dp). Before: where the grid's own edge landed.
     * After: the clip, in dp.
     */
    @Test
    fun `the fold lands in a gap or through an icon on the Jelly Star and the six-inch`() {
        data class Case(val name: String, val s: Screen, val nav: Float, val scale: Float, val lines: List<Int>, val tiles: Int, val before: String, val clipDp: Float, val after: String)
        val cases = listOf(
            // The user's screenshot: "Innstillinger"'s icon whole at the bottom, its label under the fade.
            Case("Jelly Star 1.0 gestures", jellyStar, 24f, 1.0f, listOf(1), 11, "row 4 icon without its label", 46.3f, "row 4 at most half its icon"),
            Case("Jelly Star 1.0 three buttons", jellyStar, 48f, 1.0f, listOf(1), 11, "row 4 icon without its label", 22.3f, "row 4 at most half its icon"),
            // At 1.3 "Innstillinger" hyphenates: the last row has two lines.
            Case("Jelly Star 1.3 gestures", jellyStar, 24f, 1.3f, listOf(1, 1, 1, 2), 11, "row 4 icon without its label", 24.2f, "row 4 at most half its icon"),
            // The edge 0.2 dp under row 4's icon middle - inside a pixel: nothing moves.
            Case("Jelly Star 1.3 three buttons", jellyStar, 48f, 1.3f, listOf(1, 1, 1, 2), 11, "row 4 at most half its icon", 0f, "row 4 at most half its icon"),
            // Every label on two lines: the edge is in the gap above row 4, row 3's first line legible - nothing moves.
            Case("Jelly Star 1.3 gestures, two lines", jellyStar, 24f, 1.3f, listOf(2), 11, "row 4 at most half its icon", 0f, "row 4 at most half its icon"),
            Case("six-inch 1.0 gestures", sixInch, 24f, 1.0f, listOf(1), 11, "every label legible", 0f, "every label legible"),
            Case("six-inch 1.0 three buttons", sixInch, 48f, 1.0f, listOf(1), 11, "every label legible", 0f, "every label legible"),
            Case("six-inch 1.3 gestures, 20 tiles", sixInch, 24f, 1.3f, listOf(2), 20, "row 5 icon without its label", 46.6f, "row 5 at most half its icon"),
            Case("six-inch 1.3 three buttons, 20 tiles", sixInch, 48f, 1.3f, listOf(2), 20, "row 5 icon without its label", 22.6f, "row 5 at most half its icon"),
        )
        for (c in cases) {
            val f = fold(c.s, c.nav, c.scale, c.lines, c.tiles)
            assertEquals(c.name, c.before, f.cut(f.heightPx, clipped = false))
            assertEquals(c.name, c.clipDp, f.shown / c.s.density, 1f)
            assertEquals(c.name, c.after, f.cut(f.edgePx, clipped = f.shown > 0))
        }
        // (a) - a fourth row that fits by tightening the label's gap and the badge room - doesn't: on the Jelly Star at
        // 1.0 with gestures four rows need 22.4 dp more than the grid has, 62 % of those 4 x 9 dp, and 3-button
        // navigation takes 24 dp more.
        val m = gridMetrics(288f, 3)
        val four = 4 * tileHeightDp(m, 1.0f, 1) + 3 * GRID_ROW_GAP_DP + GRID_BOTTOM_PADDING_DP
        assertEquals(22.4f, four - gridHeightDp(jellyStar, 24f, 1.0f), 0.1f)
        assertTrue(four - gridHeightDp(jellyStar, 48f, 1.0f) > 4 * (TILE_BADGE_ROOM_DP + TILE_LABEL_GAP_DP))
    }

    @Test
    fun `on every screen the edge leaves a label legible or cuts through the icon - and moves only when it must`() {
        for (s in listOf(jellyStar, sixInch, Screen(270f, 584f, 24f, 2f), Screen(411f, 914f, 48f, 1.5f))) {
            for (cols in 3..4) for (scale in scales) for (nav in listOf(0f, 24f, 48f)) for (music in listOf(0f, 84f, 110f)) {
                for (lines in listOf(listOf(1), listOf(2), listOf(1, 2), listOf(2, 1, 1, 2))) for (tiles in 1..30) {
                    val rows = rowsOf(s, cols, scale, lines, (tiles + cols - 1) / cols)
                    val f = Fold(((gridHeightDp(s, nav, scale) - music) * s.density).toInt(), rows, s.density)
                    val name = "$s cols $cols scale $scale nav $nav music $music lines $lines tiles $tiles"
                    val before = f.cut(f.heightPx, clipped = false)
                    // Only a fold between an icon and its label moves anything, and then to a clip of at least 16 dp.
                    if (!before.endsWith("without its label")) assertEquals(name, 0, f.shown) else assertTrue(name, f.shown >= f.basePx)
                    assertFalse(name, f.cut(f.edgePx, clipped = f.shown > 0).endsWith("without its label"))
                    // A grid that fits never clips.
                    if (rows.last().bottomPx + f.basePx <= f.heightPx) assertEquals(name, 0, f.shown)
                }
            }
        }
        // The last row whole with less than its half fade left to scroll stays as it is.
        val rows = listOf(GridRow(45, 100, 120), GridRow(160, 200, 215))
        assertEquals(0, gridFoldPaddingPx(rows, lastShown = true, heightPx = 220, fadePx = 48, paddingPx = 24))
        // Not known to be the last row: the whole fade is drawn - the label under it, the clip at the icon's middle.
        assertEquals(60, gridFoldPaddingPx(rows, lastShown = false, heightPx = 220, fadePx = 48, paddingPx = 24))
        assertEquals(0, gridFoldPaddingPx(emptyList(), lastShown = true, heightPx = 220, fadePx = 48, paddingPx = 24))
    }

    @Test
    fun `Home feeds the fold from the laid-out rows after every layout`() {
        val grid = byId(xml("res/layout/activity_home.xml"), "home_grid")
        assertEquals("${GRID_BOTTOM_PADDING_DP.toInt()}dp", grid.attr("paddingBottom"))
        val home = file("java/com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        // qa-home-fold-code Q-1/Q-2: asked for after each layout change and each scroll arriving at the top, run at
        // the grid's next pre-draw (never inside the layout pass), the frame's draw cancelled when the fold moved.
        assertTrue(home.contains("binding.homeGrid.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> foldBeforeDraw() }"))
        assertTrue(home.contains("if (!recyclerView.canScrollVertically(-1)) foldBeforeDraw()"))
        val before = home.substringAfter("private fun foldBeforeDraw()").substringBefore("\n    }\n")
        assertTrue(before.contains("observer.addOnPreDrawListener(") && before.contains("return !foldGrid(grid)"))
        assertEquals(1, Regex("""\bfoldGrid\(""").findAll(home).count())
        val glue = file("java/com/kidslauncher/mdm/ui/home/HomeGridAdapter.kt").readText().substringAfter("fun foldGrid(")
        // Only at the grid's top, from the view's own height, and the clip only with a fold to move.
        assertTrue(glue.contains("if (grid.canScrollVertically(-1)) return false"))
        assertTrue(glue.contains("rows.lastKey() == (count - 1) / span, grid.height,"))
        assertTrue(glue.contains("grid.clipToPadding = fold > 0"))
        // F8 (qa-home-fold-code Q-3): no fold keeps the layout's own padding, never 0.
        assertTrue(glue.contains("val bottom = if (fold > 0) fold else padding"))
        assertTrue(glue.contains("if (grid.clipToPadding == fold > 0 && grid.paddingBottom == bottom) return false"))
        assertEquals(1, Regex("gridFoldPaddingPx\\(").findAll(glue).count())
    }

    // --- (c) the call badge ---

    @Test
    fun `the third contact's call badge is inside the screen on the Jelly Star`() {
        val layout = contactRow(3, 288f)
        assertEquals(ContactRowLayout(76, 28, false), layout)
        // Before: three items of avatar + gap (104 dp) = 312 dp in 288 dp - the row scrolled by 24 dp
        // and the third badge was cut off at the edge.
        assertTrue(3 * layout.itemDp > 288)
        // Now each item is its share: 96 dp, 144 px at 1.5, 432 px = the 288 dp content exactly.
        assertEquals(96f, contactItemDp(layout, 3, 288f), 0.001f)
        assertEquals(144, contactItemPx(layout, 3, 432, 1.5f))
        // The third avatar's centre is at 240 dp, its right edge at 278, the badge's at 282 <= 288.
        val centre = 2 * 96f + 48f
        val badgeRight = centre + layout.avatarDp / 2f + CALL_BADGE_OVERHANG_DP
        assertEquals(282f, badgeRight, 0.001f)
        assertTrue(badgeRight <= 288f)
    }

    @Test
    fun `the badge's overhang is the layout's`() {
        val contact = xml("res/layout/item_kid_contact.xml")
        val badge = byId(contact, "contact_call_badge")
        assertEquals("bottom|end", badge.attr("layout_gravity"))
        val margin = badge.attr("layout_marginEnd").removeSuffix("dp").toFloat()
        // KidAvatars.sizeContact makes the frame 12 dp wider than the avatar, centred.
        val sizing = file("java/com/kidslauncher/mdm/ui/home/KidAvatars.kt").readText()
        assertTrue(sizing.contains("width = dp(context, avatarDp + 12f)"))
        assertEquals(CALL_BADGE_OVERHANG_DP, 12f / 2 - margin, 0.001f)
        // Names wrap to two lines at every scale too.
        assertEquals("2", byId(contact, "contact_name").attr("maxLines"))
        // The focus ring's reach is P2's.
        assertEquals(PressFeedback.FOCUS_RING_GAP_DP, FOCUS_RING_OUT_DP)
    }

    /** Six-inch F3: at font 1.3 "Bestemor" and "Kristoffer" on two lines touched and read as one word. */
    @Test
    fun `neighbouring contact names are at least 8 dp apart - each keeps 4 dp inside its own cell`() {
        val name = byId(xml("res/layout/item_kid_contact.xml"), "contact_name")
        assertEquals(listOf("4dp", "4dp"), listOf(name.attr("paddingStart"), name.attr("paddingEnd")))
        assertEquals("", name.attr("layout_marginStart") + name.attr("layout_marginEnd") + name.attr("padding"))
        // The label is never wider than its cell (its padding inside that width), and the cells sit side by side.
        val home = file("java/com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        assertTrue(home.contains("maxWidth = itemPx"))
        assertTrue(home.contains("item.layoutParams = LinearLayout.LayoutParams(itemPx, LinearLayout.LayoutParams.WRAP_CONTENT)"))
    }

    @Test
    fun `a row that fits never exceeds the content and leaves every contact its room`() {
        for (count in 1..8) {
            var width = 1f
            while (width <= 480f) {
                val layout = contactRow(count, width)
                val item = contactItemDp(layout, count, width)
                // Room for the avatar's call badge and focus ring on both sides, scrolling or not.
                assertTrue("count $count, width $width: $item", item + 0.001f >= contactRoomDp(layout.avatarDp))
                if (!layout.scrolls) {
                    assertTrue("count $count, width $width", count * item <= width + 0.001f)
                    for (density in densities) {
                        // The row's real width may be up to a pixel either side of the dp estimate.
                        for (slack in -1..1) {
                            val contentPx = floor(width * density).toInt() + slack
                            val px = contactItemPx(layout, count, contentPx, density)
                            assertTrue("count $count, width $width, density $density", count * px <= contentPx)
                            // Rounding down costs at most a pixel or two against the dp width.
                            assertTrue(px >= floor(item * density) - 2)
                        }
                    }
                } else {
                    assertEquals(layout.itemDp.toFloat(), item, 0.001f)
                }
                width += 1f
            }
        }
        // Wider screens keep the mockup's spacing (an item of avatar + gap).
        assertEquals(104f, contactItemDp(contactRow(3, 328f), 3, 328f), 0.001f)
        assertEquals(80f, contactItemDp(contactRow(4, 360f), 4, 360f), 0.001f)
        assertEquals(0f + 76 + 28, contactItemDp(contactRow(0, 288f), 0, 288f), 0.001f)
        // Both room checks bite at tiny widths (qa-34-s4-code N1): one contact in 80 dp is 64 dp
        // centred (76 + 8 doesn't fit), in 70 dp it scrolls (64 + 8 doesn't fit either).
        assertEquals(ContactRowLayout(64, 16, false), contactRow(1, 80f))
        assertTrue(contactRow(1, 70f).scrolls)
        assertEquals(ContactRowLayout(76, 28, false), contactRow(1, 84f))
    }

    @Test
    fun `the row's width is the measured one, with a pixel of slack before layout`() {
        // Laid out: what was measured, as it is.
        assertEquals(431, contactsContentPx(431, 320, 1.5f))
        // Before the first layout: (screenWidthDp - 32) dp in px, less a pixel (the window manager may round up).
        assertEquals(431, contactsContentPx(0, 320, 1.5f))
        assertEquals(993, contactsContentPx(0, 411, 2.625f))
        assertEquals(0, contactsContentPx(0, 10, 2f))
        // Three contacts in a Jelly Star row measured at 432 px never scroll.
        val layout = contactRow(3, 288f)
        assertTrue(3 * contactItemPx(layout, 3, contactsContentPx(432, 320, 1.5f), 1.5f) <= 432)
    }

    @Test
    fun `Home sizes the contacts by the row's width`() {
        val src = file("java/com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        val body = src.substringAfter("private fun renderContacts(").substringBefore("\n    private fun ")
        val flat = body.replace(Regex("\\s+"), " ")
        // Whole lines, anchored at the end, so `itemPx + 40` or another width fails (qa-34-s4-code L3).
        fun line(pattern: String) = Regex("^\\s*$pattern\\s*$", RegexOption.MULTILINE)
        assertTrue(line("val itemPx = contactItemPx\\(layout, contacts\\.size, contentPx, density\\)").containsMatchIn(body))
        assertTrue(line("item\\.layoutParams = LinearLayout\\.LayoutParams\\(itemPx, LinearLayout\\.LayoutParams\\.WRAP_CONTENT\\)").containsMatchIn(body))
        assertTrue(line("maxWidth = itemPx").containsMatchIn(body))
        assertEquals(1, Regex("maxWidth\\s*=").findAll(body).count())
        assertEquals(1, Regex("layoutParams\\s*=").findAll(body).count())
        // The width is measured from Home's column (L2).
        assertTrue(flat.contains("val contentPx = contactsContentPx( binding.root.width - binding.root.paddingLeft - binding.root.paddingRight, resources.configuration.screenWidthDp, density, )"))
        assertFalse(src.contains("KidAvatars.dp(this, layout.itemDp.toFloat())"))
    }
}
