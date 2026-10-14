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
    fun `the top fades 12 dp and the bottom 32 dp, only where the grid can scroll`() {
        assertEquals(12f, FADE_TOP_DP)
        assertEquals(32f, FADE_BOTTOM_DP)
        // The one length the view has is the longer edge, so neither strength is capped below its length.
        assertEquals(maxOf(FADE_TOP_DP, FADE_BOTTOM_DP), FADE_LENGTH_DP)
        for (density in densities) {
            val lengthPx = FADE_LENGTH_DP * density
            assertEquals(12f * density, lengthPx * fadeStrength(true, FADE_TOP_DP), 0.001f)
            assertEquals(32f * density, lengthPx * fadeStrength(true, FADE_BOTTOM_DP), 0.001f)
        }
        // At rest at the top, or at the end: no fade at that edge.
        assertEquals(0f, fadeStrength(false, FADE_TOP_DP))
        assertEquals(0f, fadeStrength(false, FADE_BOTTOM_DP))
        // Never more than View.draw's full length, never negative, nothing for a zero length.
        assertEquals(1f, fadeStrength(true, 64f))
        assertEquals(0f, fadeStrength(true, -4f))
        assertEquals(0f, fadeStrength(true, 12f, 0f))
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
    fun `the fading recycler scales each edge and fades over its padding`() {
        val src = file("java/com/kidslauncher/mdm/ui/home/FadingRecyclerView.kt").readText().replace(Regex("\\s+"), " ")
        assertTrue(src.contains("getTopFadingEdgeStrength(): Float = fadeStrength(canScrollVertically(-1), FADE_TOP_DP)"))
        assertTrue(src.contains("getBottomFadingEdgeStrength(): Float = fadeStrength(canScrollVertically(1), FADE_BOTTOM_DP)"))
        assertTrue(src.contains("isPaddingOffsetRequired(): Boolean = !clipToPadding"))
        assertTrue(src.contains("getTopPaddingOffset(): Int = if (clipToPadding) 0 else -paddingTop"))
        assertTrue(src.contains("getBottomPaddingOffset(): Int = if (clipToPadding) 0 else paddingBottom"))
        // Observes nothing and consumes nothing: Home's swipes and the item touch listener are untouched.
        assertFalse(src.contains("onTouchEvent"))
        assertFalse(src.contains("onInterceptTouchEvent"))
        assertFalse(src.contains("dispatchTouchEvent"))
    }

    // --- (b) labels ---

    @Test
    fun `grid labels wrap to two lines at every scale`() {
        assertEquals(2, GRID_LABEL_MAX_LINES)
        val tile = xml("res/layout/item_kid_tile.xml")
        val label = byId(tile, "tile_label")
        assertEquals("$GRID_LABEL_MAX_LINES", label.attr("maxLines"))
        assertEquals("end", label.attr("ellipsize"))
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
        // the row's 4 dp margin, the grid's 18 dp margin.
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
                }
            }
        }
        // The Jelly Star at 2.0 with three buttons, 3 columns: about 140 dp a row in about 297 dp.
        val m = gridMetrics(288f, 3)
        assertEquals(139.9f, tileHeightDp(m, 2.0f), 0.1f)
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
        assertEquals(144, contactItemPx(layout, 3, 288f, 1.5f))
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

    @Test
    fun `a row that fits never exceeds the content and leaves every contact its room`() {
        for (count in 1..8) {
            var width = 200f
            while (width <= 480f) {
                val layout = contactRow(count, width)
                val item = contactItemDp(layout, count, width)
                // Room for the avatar's call badge and focus ring on both sides, scrolling or not.
                assertTrue("count $count, width $width: $item", item + 0.001f >= contactRoomDp(layout.avatarDp))
                if (!layout.scrolls) {
                    assertTrue("count $count, width $width", count * item <= width + 0.001f)
                    for (density in densities) {
                        val px = contactItemPx(layout, count, width, density)
                        assertTrue("count $count, width $width, density $density", count * px <= width * density)
                        // Rounding down costs at most a pixel against the dp width.
                        assertTrue(px >= floor(item * density) - 1)
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
    }

    @Test
    fun `Home sizes the contacts by the row's width`() {
        val src = file("java/com/kidslauncher/mdm/ui/HomeActivity.kt").readText()
        assertTrue(src.contains("val itemPx = contactItemPx(layout, contacts.size, contentWidthDp(), resources.displayMetrics.density)"))
        assertTrue(src.contains("LinearLayout.LayoutParams(itemPx, LinearLayout.LayoutParams.WRAP_CONTENT)"))
        assertTrue(src.contains("maxWidth = itemPx"))
        assertFalse(src.contains("KidAvatars.dp(this, layout.itemDp.toFloat())"))
    }
}
