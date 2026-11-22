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

/** Design 34 P4: Home's fading edges, one-line labels at one shared size (2026-11-20), and a contacts row that never runs past the screen. */
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
        // Home's top stays empty: no new view above the banner, the call card and the contacts - design 98 §4.2's one
        // "Flymodus er på" line under the banner (gone unless airplane mode is on) and design 102's "Wi-Fi-en vil at du
        // logger på" (gone unless the Wi-Fi wants a sign-in) the only additions. Under the grid only the move bar (gone
        // unless a tile moves by the buttons, 2026-11-22).
        val container = home.documentElement
        val firstIds = (0 until container.childNodes.length).map { container.childNodes.item(it) }
            .filterIsInstance<Element>().map { it.attr("id") }
        assertEquals(
            listOf("@+id/home_window_banner", "@+id/home_airplane_line", "@+id/home_portal_line", "@+id/home_call_card", "@+id/home_contacts_scroll", "@+id/home_music_card", "@+id/home_grid", "@+id/home_move_bar"),
            firstIds,
        )
        assertEquals("gone", byId(home, "home_move_bar").attr("visibility"))
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
    fun `grid labels are one line at every scale, whole words, ending in an ellipsis only at the floor`() {
        assertEquals(1, GRID_LABEL_MAX_LINES)
        val tile = xml("res/layout/item_kid_tile.xml")
        val label = byId(tile, "tile_label")
        // A plain TextView: the shared size does the shrinking, not WholeWordTextView's own fit.
        assertEquals("TextView", label.tagName)
        assertEquals("$GRID_LABEL_MAX_LINES", label.attr("maxLines"))
        assertEquals("end", label.attr("ellipsize"))
        // Never hyphenated ("Innstil-…" on one line would be a broken word).
        assertEquals("none", label.attr("hyphenationFrequency"))
        assertEquals("", label.attr("singleLine"))
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
        // Every label at the grid's one size, measured in the label's own font, its line the cell less the 2 dp sides.
        assertTrue(adapter.contains("label.setTextSize(TypedValue.COMPLEX_UNIT_PX, sharedPx)"))
        assertTrue(adapter.contains("ResourcesCompat.getFont(activity, R.font.nunito_bold)"))
        assertEquals("@font/nunito_bold", label.attr("fontFamily"))
        assertEquals(listOf("2dp", "2dp"), listOf(label.attr("paddingStart"), label.attr("paddingEnd")))
        assertTrue(adapter.contains("(metrics.cellWidthDp - 4f) * display.density - 2f"))
        assertTrue(adapter.contains("labelPx != this.labelPx"))
    }

    /** Nunito Bold, roughly (as `WholeWordsTest`): wide m/w, narrow i/l/t/f/r/j, the rest 0.56 em. */
    private fun em(c: Char): Float = when (c) {
        'm', 'w', 'M', 'W' -> 0.88f
        'i', 'l', 'j', 'I', '\'' -> 0.3f
        't', 'f', 'r' -> 0.4f
        ' ' -> 0.25f
        else -> 0.56f
    }

    private fun width(s: String, size: Float) = s.sumOf { em(it).toDouble() }.toFloat() * size

    private val labels = mapOf(
        "nb" to listOf("Telefonbok", "Innstillinger", "Meldinger", "Kalender", "Kamera", "Klokke", "Chrome", "Gmail", "YouTube", "Musikk", "Bilder", "Kalkulator", "Kart", "Dagen"),
        "en" to listOf("Phone book", "Settings", "Messages", "Chrome", "Gmail", "YouTube", "Calendar", "Camera", "Clock", "Calculator", "Maps", "Photos", "Today"),
    )

    @Test
    fun `one shared size - the smallest any label needs, down to the floor, never larger than the label's own`() {
        for (density in densities) {
            for (content in listOf(240f, 288f, 360f)) {
                for (cols in 3..4) {
                    val m = gridMetrics(content, cols)
                    val line = (m.cellWidthDp - 4f) * density - 2f
                    for (scale in scales) {
                        val base = m.labelSp * scale * density
                        val floor = gridLabelFloorPx(base, density)
                        for ((lang, words) in labels) {
                            val what = "$lang $cols cols $content dp ${scale}x density $density"
                            val size = sharedLabelPx(words, base, floor, line, ::width)
                            assertTrue(what, size in floor..base)
                            // The largest such size: either the base, or the floor, or some label only just fits.
                            if (size > floor) assertTrue(what, words.all { width(it, size) <= line })
                            if (size < base && size > floor) {
                                assertTrue(what, words.any { width(it, size + 0.5f) > line })
                            }
                            // The order of the labels never matters.
                            assertEquals(what, size, sharedLabelPx(words.reversed(), base, floor, line, ::width), 0.001f)
                        }
                    }
                }
            }
        }
    }

    /**
     * The bundled Nunito Bold (`res/font/nunito_bold.ttf`, the labels' font): a label's advance width with kerning and
     * fractional metrics, as Android's `measureText` lays it out unhinted. Through java.awt by reflection - the unit
     * tests compile against android.jar, which has no java.awt; the JVM that runs them has it. Widths are linear in the
     * size, so one measure at 1000 units gives the em width.
     */
    private val nunitoEm: (String) -> Float by lazy {
        val fontClass = Class.forName("java.awt.Font")
        val textAttribute = Class.forName("java.awt.font.TextAttribute")
        val base = fontClass.getMethod("createFont", Int::class.javaPrimitiveType, File::class.java)
            .invoke(null, 0 /* TRUETYPE_FONT */, file("res/font/nunito_bold.ttf"))
        val kerned = fontClass.getMethod("deriveFont", Map::class.java)
            .invoke(base, mapOf(textAttribute.getField("KERNING").get(null) to textAttribute.getField("KERNING_ON").get(null)))
        val font = fontClass.getMethod("deriveFont", Float::class.javaPrimitiveType).invoke(kerned, 1000f)
        val transform = Class.forName("java.awt.geom.AffineTransform")
        val frcClass = Class.forName("java.awt.font.FontRenderContext")
        val frc = frcClass.getConstructor(transform, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            .newInstance(null, true, true)
        val bounds = fontClass.getMethod("getStringBounds", String::class.java, frcClass)
        val cache = HashMap<String, Float>()
        val measure: (String) -> Float = { text ->
            cache.getOrPut(text) {
                val rect = bounds.invoke(font, text, frc)
                (rect.javaClass.getMethod("getWidth").invoke(rect) as Double).toFloat() / 1000f
            }
        }
        measure
    }

    /** A label's width at [size] (any unit), in the real font. */
    private fun nunitoWidth(text: String, size: Float): Float = nunitoEm(text) * size

    /** Home's built-in tiles (`calls_phone_book`, `messages_title`, `day_title`, `kid_settings_title`) in nb and en,
     * and the system apps' usual names (the coordinator's list, 2026-11-20). */
    private val builtIn = mapOf(
        "nb" to listOf("Telefonbok", "Meldinger", "Kalender", "Innstillinger", "Dagen", "Kamera", "Klokke", "Kalkulator"),
        "en" to listOf("Phone book", "Messages", "Calendar", "Settings", "Today", "Camera", "Clock", "Calculator"),
    )
    private val otherApps = listOf("Organic Maps", "YouTube", "Chrome", "Foto", "Spotify", "Vibb Music")

    /** The Jelly Star: 320 dp wide (288 dp content), density 1.5; the label's line as the adapter computes it. */
    private val jellyDensity = 1.5f
    private fun jellyLinePx(cols: Int) = (gridMetrics(288f, cols).cellWidthDp - 4f) * jellyDensity - 2f

    /**
     * Code QA M1 of jelly-round-2, measured (printed for the report): each label's em width in Nunito Bold, and in 3 and
     * 4 columns at font 1.0/1.15/1.3 (linear; Android 14's table is within a few percent at these sizes) the size it
     * fits whole at, as a share of the label's own size, against the floor.
     */
    @Test
    fun `the real font on the Jelly Star - which labels fit whole at which size, 3 and 4 columns`() {
        val out = StringBuilder("label | em | cols | font | base dp | fits at dp | share | floor dp\n")
        for (label in builtIn.values.flatten() + otherApps) {
            for (cols in 3..4) {
                val m = gridMetrics(288f, cols)
                val line = jellyLinePx(cols) / jellyDensity
                for (scale in listOf(1.0f, 1.15f, 1.3f)) {
                    val base = m.labelSp * scale
                    val fits = minOf(base, line / nunitoEm(label))
                    val floor = gridLabelFloorPx(base * jellyDensity, jellyDensity) / jellyDensity
                    out.append(
                        "$label | ${"%.3f".format(nunitoEm(label))} | $cols | $scale | ${"%.2f".format(base)} | " +
                            "${"%.2f".format(fits)} | ${"%.3f".format(fits / base)} | ${"%.2f".format(floor)}\n",
                    )
                }
            }
        }
        println(out)
        // The longest built-in label is nb's Settings.
        assertEquals("Innstillinger", builtIn.values.flatten().maxBy(nunitoEm))
    }

    /** The user, 2026-11-20 ("mindre tekst på hjem skjermen på appene"): a normal grid never shrinks or ellipsizes. */
    @Test
    fun `the Jelly Star in Nunito Bold - every built-in label whole at its own size in 3 columns, above the floor in 4`() {
        for (scale in listOf(1.0f, 1.15f, 1.3f)) {
            for ((lang, words) in builtIn) {
                // 3 columns: at the label's own size, nothing shrinks.
                run {
                    val basePx = gridMetrics(288f, 3).labelSp * scale * jellyDensity
                    val size = sharedLabelPx(words, basePx, gridLabelFloorPx(basePx, jellyDensity), jellyLinePx(3), ::nunitoWidth)
                    assertEquals("3 columns, $lang at $scale", basePx, size, 0.001f)
                }
                // 4 columns: maybe a little smaller, but whole - never the floor's ellipsis.
                run {
                    val basePx = gridMetrics(288f, 4).labelSp * scale * jellyDensity
                    val floorPx = gridLabelFloorPx(basePx, jellyDensity)
                    val size = sharedLabelPx(words, basePx, floorPx, jellyLinePx(4), ::nunitoWidth)
                    for (word in words) assertTrue("4 columns, $lang $word at $scale: $size px", nunitoWidth(word, size) <= jellyLinePx(4))
                    assertTrue("4 columns, $lang at $scale: $size px over the floor $floorPx", size >= floorPx)
                }
            }
        }
        // The sizes chosen for it: 11.5 sp at the mockup's width, a floor of 70 % or 10.5 dp.
        assertEquals(11.5f, gridMetrics(288f, 3).labelSp, 0.001f)
        assertEquals(0.7f, GRID_LABEL_FLOOR_SHARE)
        assertEquals(10.5f, GRID_LABEL_MIN_DP)
    }

    @Test
    fun `the Jelly Star at font 1_3 in the em model - every nb and en label whole at its own size`() {
        // 320 dp wide (288 dp content), 240 dpi (density 1.5), font 1.3 (the real phone): 3 columns.
        val density = 1.5f
        val m = gridMetrics(288f, 3)
        val line = (m.cellWidthDp - 4f) * density - 2f
        for (scale in listOf(1.0f, 1.3f)) {
            val base = m.labelSp * scale * density
            for ((lang, words) in labels) {
                assertEquals("$lang at $scale", base, sharedLabelPx(words, base, gridLabelFloorPx(base, density), line, ::width), 0.001f)
            }
        }
    }

    @Test
    fun `a label too wide at the floor sets the floor for all, and the floor is 70 percent or 10_5 dp`() {
        val density = 2f
        // The floor: 70 % of the base, at least 10.5 dp - unless the base itself is smaller.
        assertEquals(26f * 0.7f * density, gridLabelFloorPx(26f * density, density), 0.001f)
        assertEquals(10.5f * density, gridLabelFloorPx(13f * density, density), 0.001f)
        assertEquals(10f * density, gridLabelFloorPx(10f * density, density), 0.001f)
        val line = 80f * density
        val base = 17f * density
        val floor = gridLabelFloorPx(base, density)
        val size = sharedLabelPx(listOf("Kart", "Microsoft Authenticator", "Klokke"), base, floor, line, ::width)
        assertEquals(floor, size, 0.001f)
        // Nothing to fit: the base.
        assertEquals(base, sharedLabelPx(emptyList(), base, floor, line, ::width), 0.001f)
        // Widths that aren't linear (hinting): the step down still lands on a size that fits.
        val lumpy = { s: String, size: Float -> width(s, size) + if (size > 30f) 6f else 0f }
        val fitted = sharedLabelPx(listOf("Innstillinger"), base, floor, line, lumpy)
        assertTrue(lumpy("Innstillinger", fitted) <= line || fitted == floor)
    }

    @Test
    fun `a one-line row grows with the font scale and a whole row still shows at 2_0 on the Jelly Star`() {
        // Home's column at 320x569 dp: status bar 24 dp, navigation 24 (gestures) or 48 (three buttons),
        // three contacts (76 dp, frame +6, item padding 4 + 4, name 6 dp below, two lines of 15 sp),
        // the row's 4 dp margin, the grid's 18 dp margin; without and with the music card.
        for (cols in 3..4) {
            for (width in listOf(240f, 288f, 360f)) {
                val m = gridMetrics(width, cols)
                var last = 0f
                for (scale in scales) {
                    val one = tileHeightDp(m, scale)
                    assertEquals(tileHeightDp(m, scale, 1), one, 0.001f)
                    assertTrue("rows grow with the scale", one > last)
                    last = one
                    // Never more than one line (2026-11-20).
                    assertEquals(one, tileHeightDp(m, scale, 5), 0.001f)
                    // The bottom fade is shorter than a tile: it never hides a whole row.
                    assertTrue(FADE_BOTTOM_DP < one)
                }
            }
            val m = gridMetrics(288f, cols)
            for (scale in scales) {
                // 0: the Jelly Star itself, keys below the panel and no bar in the window (2026-11-20).
                for (nav in listOf(0f, 24f, 48f)) {
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
        // The Jelly Star at 2.0, 3 columns: about 100 dp a row (140 dp while labels took two lines at 13 sp).
        val m = gridMetrics(288f, 3)
        assertEquals(100.4f, tileHeightDp(m, 2.0f), 0.1f)
        // At 1.3, the real phone's: the rows Home showed in the layout round (87 dp with one line, 109 with two) - now
        // every row is the one-line height, so four rows and three 14 dp gaps need 30-46 dp less than before.
        assertTrue(tileHeightDp(m, 1.3f) < 93f)
    }

    // --- (b2) rows that only just overflow (the Jelly Star's layout, 2026-11-20) ---

    @Test
    fun `rows that only just overflow get smaller gaps, down to 6 dp - else 14 dp`() {
        assertEquals(6f, GRID_MIN_ROW_GAP_DP)
        val rows = listOf(87f, 87f, 109f, 87f) // 370 dp: row 3 with a two-line label
        // Fits with 14 dp (370 + 42 = 412): unchanged.
        assertEquals(14f, rowGapToFit(rows, 420f), 0.001f)
        assertEquals(14f, rowGapToFit(rows, 412f), 0.001f)
        // Only just overflows: the even gap that fits exactly.
        assertEquals(25f / 3, rowGapToFit(rows, 395f), 0.001f)
        assertEquals(6f, rowGapToFit(rows, 388f), 0.001f)
        // Overflows by more than three gaps can give: 14 dp, the grid scrolls and fades as before.
        assertEquals(14f, rowGapToFit(rows, 387f), 0.001f)
        assertEquals(14f, rowGapToFit(rows, 300f), 0.001f)
        // One row, or none: nothing to squeeze.
        assertEquals(14f, rowGapToFit(listOf(200f), 100f), 0.001f)
        assertEquals(14f, rowGapToFit(emptyList(), 0f), 0.001f)
        // Five rows give 32 dp; six 40.
        assertEquals(10f, rowGapToFit(List(5) { 87f }, 475f), 0.001f)
        assertEquals(14f, rowGapToFit(List(6) { 87f }, 400f), 0.001f)
        // In px, truncated as Home does: a squeezed gap is between the floor and 14 dp and the rows always fit with it;
        // the full gap is kept exactly when they fit at 14 dp or overflow even at the floor. (That a second layout
        // keeps the gap - the rows' heights don't depend on it - is GridLayoutManager's, not this rule's.)
        for (density in densities) {
            val px = rows.map { floor(it * density) }
            val full = floor(14 * density)
            val least = floor(6 * density)
            for (contentDp in 380..430) {
                val content = floor(contentDp * density)
                val gap = rowGapToFit(px, content, full, least).toInt()
                val label = "density $density, $contentDp dp, gap $gap"
                if (gap < full.toInt()) {
                    assertTrue(label, gap >= least.toInt())
                    assertTrue(label, px.sum() + 3 * gap <= content)
                } else {
                    assertTrue(label, px.sum() + 3 * full <= content || px.sum() + 3 * least > content)
                }
            }
        }
    }

    @Test
    fun `Home fits the rows before the frame, only after the grid's own layout`() {
        // Code QA L1, L2: a pre-draw listener that drops the frame when the gap changed (never one frame with the old
        // gap), run only when the grid's layout manager completed a layout (not on every layout of Home's window).
        val src = file("java/com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        assertTrue(src.contains("binding.homeGrid.viewTreeObserver.addOnPreDrawListener { fitGridRowsBeforeDraw() }"))
        assertFalse(src.contains("addOnGlobalLayoutListener { fitGridRows"))
        val manager = src.substringAfter("gridLayout = object : GridLayoutManager(").substringBefore("\n        }\n")
        assertTrue(manager, manager.contains("override fun onLayoutCompleted(") && manager.contains("gridFitDue = true"))
        val before = src.substringAfter("private fun fitGridRowsBeforeDraw(): Boolean {").substringBefore("\n    }\n")
        assertTrue(before, before.contains("if (!gridFitDue || !contentShown) return true"))
        assertTrue(before, before.contains("gridFitDue = false"))
        assertTrue(before, before.contains("return !fitGridRows()"))
        val fit = src.substringAfter("private fun fitGridRows(): Boolean {").substringBefore("\n    }\n")
        assertTrue(fit, fit.contains("if (fitted == gridRowGapPx) return false"))
        assertTrue(fit, fit.contains("grid.invalidateItemDecorations()"))
    }

    @Test
    fun `the Jelly Star with keys - the emulator's measured rows, one contact and ten tiles`() {
        // Measured on jellykeys36 (480x854 at 240 dpi, no bar in the window; docs/testing/emulator.md §3), px at 1.5:
        // the rows (frame to label), "Organic Maps" on two lines in row 3, and the grid's content (its height less the
        // 24 px bottom padding). 14 dp = 21 px, 6 dp = 9 px.
        fun gap(rows: List<Int>, content: Int) = rowGapToFit(rows.map { it.toFloat() }, content.toFloat(), 21f, 9f).toInt()
        // A screen 26 px taller (wm size 480x880) at font 1.15: 19 px over, the gaps give it - 14 px, every row whole.
        val taller = listOf(135, 135, 166, 135)
        assertEquals(14, gap(taller, 615))
        assertTrue(taller.sum() + 3 * gap(taller, 615) <= 615)
        // The phone's own height: at 1.15 45 px over and at 1.3 69 px over - more than the gaps' 36 px, so 14 dp, the
        // grid scrolls and fades as before (the gear's label under the edge).
        assertEquals(21, gap(listOf(135, 135, 166, 135), 589))
        assertEquals(21, gap(listOf(139, 139, 174, 139), 585))
    }

    // --- (c) the unread pill (the call badge's place until design 106) ---

    @Test
    fun `the third contact's unread pill is inside the screen on the Jelly Star`() {
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
        val badgeRight = centre + layout.avatarDp / 2f + UNREAD_BADGE_OVERHANG_DP
        assertEquals(282f, badgeRight, 0.001f)
        assertTrue(badgeRight <= 288f)
    }

    @Test
    fun `the badge's overhang is the layout's`() {
        val contact = xml("res/layout/item_kid_contact.xml")
        val badge = byId(contact, "contact_unread_badge")
        assertEquals("bottom|end", badge.attr("layout_gravity"))
        val margin = badge.attr("layout_marginEnd").removeSuffix("dp").toFloat()
        // KidAvatars.sizeContact makes the frame 12 dp wider than the avatar, centred.
        val sizing = file("java/com/kidslauncher/mdm/ui/home/KidAvatars.kt").readText()
        assertTrue(sizing.contains("width = dp(context, avatarDp + 12f)"))
        assertEquals(UNREAD_BADGE_OVERHANG_DP, 12f / 2 - margin, 0.001f)
        // Names wrap to two lines at every scale too.
        assertEquals("2", byId(contact, "contact_name").attr("maxLines"))
        // The focus ring's reach is P2's.
        assertEquals(PressFeedback.FOCUS_RING_GAP_DP, FOCUS_RING_OUT_DP)
    }

    /** Six-inch F3: at font 1.3 "Bestemor" and "Kristoffer" on two lines touched and read as one word. Since the name
     * pill (2026-11-19) the text keeps 8 dp inside its pill, so two neighbours' words are at least 16 dp apart, each
     * on its own outlined pill. */
    @Test
    fun `neighbouring contact names are at least 8 dp apart - each keeps 8 dp inside its pill`() {
        val name = byId(xml("res/layout/item_kid_contact.xml"), "contact_name")
        assertEquals(listOf("8dp", "8dp"), listOf(name.attr("paddingStart"), name.attr("paddingEnd")))
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
                // Room for the avatar's unread pill and focus ring on both sides, scrolling or not.
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
