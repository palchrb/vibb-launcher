package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.ui.home.contactItemDp
import com.kidslauncher.mdm.ui.home.contactRow
import com.kidslauncher.mdm.ui.home.gridMetrics
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Emulator round 2026-10-14 N7: a word is never cut between letters - it gets a hyphen (Android's
 * patterns), a smaller size, or an ellipsis. The rule is checked against a model of Android's line
 * breaker *without* hyphenation (the case that cut "Mamm/a", "Chro/me" and "Ph/one"): greedy at
 * spaces, a word wider than the line split where it overflows - and Nunito's widths as an em share
 * per letter, the font scale linear (an upper bound, as `HomeEdgesTest` and `PinLockLayoutTest`).
 */
class WholeWordsTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    // ---- the model ----------------------------------------------------------------------------

    /** Nunito Bold/ExtraBold, roughly: wide m/w, narrow i/l/t/f/r/j, the rest 0.56 em. */
    private fun em(c: Char): Float = when (c) {
        'm', 'w', 'M', 'W' -> 0.88f
        'i', 'l', 'j', 'I', '\'' -> 0.3f
        't', 'f', 'r' -> 0.4f
        ' ' -> 0.25f
        '…' -> 0.9f
        else -> 0.56f
    }

    private fun width(s: String, sizeDp: Float) = s.sumOf { em(it).toDouble() }.toFloat() * sizeDp

    /** Line ends of a greedy breaker with no hyphenation: after a space or a hyphen when a word fits,
     * else mid-word. */
    private fun lineEnds(text: String, sizeDp: Float, lineDp: Float): List<Int> {
        val ends = mutableListOf<Int>()
        var start = 0
        while (start < text.length) {
            var end = start
            var lastSpace = -1
            while (end < text.length && width(text.substring(start, end + 1).trimEnd(), sizeDp) <= lineDp) {
                if (text[end] == ' ' || text[end] == '-') lastSpace = end // a break after a space or a hyphen
                end++
            }
            if (end >= text.length) {
                ends += text.length
                break
            }
            end = when {
                text[end] == ' ' -> end + 1
                lastSpace >= 0 -> lastSpace + 1
                else -> maxOf(end, start + 1) // a word wider than the line: cut where it overflows
            }
            ends += end
            start = end
        }
        return ends
    }

    private fun cuts(text: String, sizeDp: Float, lineDp: Float, maxLines: Int): Boolean {
        val ends = lineEnds(text, sizeDp, lineDp)
        val shown = minOf(maxLines, ends.size)
        return (0 until shown - 1).any { WholeWords.cutsWord(text, ends[it], hyphenated = false) }
    }

    /** What the view shows: the size and the text after the rule. */
    private fun shown(text: String, baseDp: Float, lineDp: Float, maxLines: Int = 2): Pair<Float, String> {
        val fit = WholeWords.fit(baseDp) { cuts(text, it, lineDp, maxLines) }
        val display = if (fit.ellipsized) WholeWords.ellipsizeWords(text) { width(it, fit.sizePx) <= lineDp } else text
        return fit.sizePx to display
    }

    private val labels = mapOf(
        "nb" to listOf("Telefonbok", "Innstillinger", "Meldinger", "Kalender", "Kamera", "Klokke", "Chrome", "Gmail", "YouTube", "Musikk", "Bilder", "Kalkulator", "Kart"),
        "en" to listOf("Phone book", "Settings", "Meldinger", "Chrome", "Gmail", "YouTube", "Calendar", "Camera", "Clock", "Calculator", "Messages", "Maps", "Photos"),
    )

    /** A contact name's line: its cell less the label's own sides (4 dp each, six-inch F3). */
    private val nameSides by lazy {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(File(main, "res/layout/item_kid_contact.xml"))
        val all = doc.getElementsByTagName("*")
        val name = (0 until all.length).map { all.item(it) as Element }.single { it.getAttributeNS(ns, "id") == "@+id/contact_name" }
        listOf("paddingStart", "paddingEnd").sumOf { name.getAttributeNS(ns, it).removeSuffix("dp").toDouble() }.toFloat()
    }

    private fun nameLine(cellDp: Float) = cellDp - nameSides

    private val names = listOf("Mamma", "Pappa", "Mormor", "Morfar", "Bestemor", "Bestefar", "Oline", "Emergency call", "Nødsamtale", "Kristoffer", "Anne-Marie")
    private val scales = listOf(1.0f, 1.3f, 2.0f)

    private fun assertNoCut(what: String, size: Float, display: String, lineDp: Float, maxLines: Int = 2) {
        assertFalse("$what at ${size}dp: '$display'", cuts(display, size, lineDp, maxLines))
    }

    // ---- the rule -----------------------------------------------------------------------------

    @Test
    fun `tile labels in nb and en, 3 and 4 columns, at 1_0 1_3 2_0 - a hyphen, a smaller size or an ellipsis, never a letter split`() {
        for (content in listOf(288f, 320f, 360f)) {
            for (cols in 3..4) {
                val m = gridMetrics(content, cols)
                val line = m.cellWidthDp - 4f // the label's 2 dp side paddings
                for ((lang, words) in labels) {
                    for (scale in scales) {
                        for (label in words) {
                            val base = m.labelSp * scale
                            val (size, display) = shown(label, base, line)
                            assertNoCut("$lang $cols cols $content dp ${scale}x '$label'", size, display, line)
                            assertTrue(size >= base * WholeWords.FLOOR_SHARE - 0.001f)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `at font scale 1_0 nothing changes`() {
        // 3 columns on the Jelly Star (4 columns are within a few dp in this coarse model).
        run {
            val m = gridMetrics(288f, 3)
            for (label in labels.values.flatten()) {
                assertEquals(label, m.labelSp to label, shown(label, m.labelSp, m.cellWidthDp - 4f))
            }
        }
        val item = nameLine(contactItemDp(contactRow(3, 288f), 3, 288f))
        for (name in names) assertEquals(name, 15f to name, shown(name, 15f, item))
    }

    @Test
    fun `contact names on Home and in the phone book keep whole - Mamma at 2_0 shrinks, it is never Mamm-a`() {
        for (content in listOf(288f, 320f, 360f)) {
            for (count in 1..5) {
                val row = contactRow(count, content)
                val item = nameLine(if (row.scrolls) row.itemDp.toFloat() else contactItemDp(row, count, content))
                for (scale in scales) {
                    for (name in names) {
                        val (size, display) = shown(name, 15f * scale, item)
                        assertNoCut("Home $count $content dp ${scale}x '$name'", size, display, item)
                    }
                }
            }
        }
        // The Jelly Star (288 dp, three contacts, 96 dp items, an 88 dp line) at 2.0: the short names stay whole, only
        // smaller.
        val item = nameLine(contactItemDp(contactRow(3, 288f), 3, 288f))
        for (name in listOf("Mamma", "Pappa", "Mormor", "Morfar")) {
            val (size, display) = shown(name, 30f, item)
            assertEquals(name, display)
            // The two the emulator showed cut ("Mamm/a", "Mormo/r") shrink.
            if (name == "Mamma" || name == "Mormor") assertTrue("$name shrinks at 2.0", size < 30f)
        }
        // The phone book: 14 sp names in a cell of its 3-column grid (16 dp sides), at most the label's 104 dp - 96 dp on
        // the Jelly Star, so an 88 dp line (code QA L-4 of launcher-glance-c).
        val bookLine = nameLine(minOf(104f, (320f - 32f) / 3))
        assertEquals(88f, bookLine, 0.01f)
        for (scale in scales) for (name in names) {
            val (size, display) = shown(name, 14f * scale, bookLine)
            assertNoCut("phone book ${scale}x '$name'", size, display, bookLine)
        }
    }

    @Test
    fun `the pure parts`() {
        assertTrue(WholeWords.cutsWord("Mamma", 4, hyphenated = false))
        assertFalse(WholeWords.cutsWord("Mamma", 4, hyphenated = true))
        assertFalse(WholeWords.cutsWord("Phone book", 6, hyphenated = false)) // after the space
        assertFalse(WholeWords.cutsWord("Anne-Marie", 5, hyphenated = false)) // after its own hyphen
        assertFalse(WholeWords.cutsWord("Mamma", 5, hyphenated = false)) // the text's end
        assertFalse(WholeWords.cutsWord("Mamma", 0, hyphenated = false))
        val sizes = WholeWords.sizes(40f)
        assertEquals(WholeWords.STEPS + 1, sizes.size)
        assertEquals(40f, sizes.first(), 0.001f)
        assertEquals(30f, sizes.last(), 0.001f)
        assertTrue(sizes.zipWithNext().all { (a, b) -> b < a })
        assertEquals(WholeWords.Fit(40f, false), WholeWords.fit(40f) { false })
        assertEquals(WholeWords.Fit(30f, true), WholeWords.fit(40f) { true })
        assertEquals(WholeWords.Fit(sizes[2], false), WholeWords.fit(40f) { it > sizes[2] })
        // Only the words that don't fit are shortened; spaces and short words stay.
        assertEquals("Ab Melding…", WholeWords.ellipsizeWords("Ab Meldinger") { it.length <= 8 })
        assertEquals("M… Ab", WholeWords.ellipsizeWords("Mamma Ab") { it.length <= 2 })
    }

    // ---- the wiring ---------------------------------------------------------------------------

    @Test
    fun `the tile label and the contact name are WholeWordTextViews, the tile keeps its hyphenation`() {
        fun label(path: String, id: String): Element {
            val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
                .parse(File(main, path))
            val all = doc.getElementsByTagName("*")
            return (0 until all.length).map { all.item(it) as Element }.single { it.getAttributeNS(ns, "id") == "@+id/$id" }
        }
        val tile = label("res/layout/item_kid_tile.xml", "tile_label")
        assertEquals("com.kidslauncher.mdm.ui.WholeWordTextView", tile.tagName)
        assertEquals("normal", tile.getAttributeNS(ns, "hyphenationFrequency"))
        assertEquals("2", tile.getAttributeNS(ns, "maxLines"))
        val name = label("res/layout/item_kid_contact.xml", "contact_name")
        assertEquals("com.kidslauncher.mdm.ui.WholeWordTextView", name.tagName)
        assertEquals("2", name.getAttributeNS(ns, "maxLines"))
        // The view feeds Android's own layout (break strategy, hyphenation, the shown lines, the
        // locale's break opportunities) to the pure parts below, keeps its state in FitState and
        // re-fits on a text change; it never changes the text itself.
        val code = File(main, "java/com/kidslauncher/mdm/ui/WholeWordText.kt").readText()
        for (needed in listOf(
            ".setBreakStrategy(breakStrategy)", ".setHyphenationFrequency(hyphenationFrequency)",
            "WholeWords.cutLines(", "WholeWords.shownLines(maxLines, lines)", "WholeWords.breakOpportunities(source, textLocale)",
            "transformationMethod = method", "if (textSize != fit.sizePx)", "if (!fitState.due(source, width)) return",
            "state?.sizeSet(adjusting, textSize)", "if (state?.textChanged(adjusting) == true) requestLayout()",
        )) assertTrue(needed, needed in code)
        // The view never sets its own text (only the BreakIterator gets one).
        assertEquals(1, Regex("""\bsetText\(""").findAll(code).count())
        assertTrue("BreakIterator.getLineInstance(locale).apply { setText(text) }" in code)
    }

    // ---- qa-34-emu-launcher-code M1, L1, L2: the view's own logic, pure ---------------------

    @Test
    fun `a recycled tile's new label is fitted again (M1)`() {
        val state = WholeWords.FitState(26f)
        assertTrue(state.due("Phone book", 60))
        assertFalse("same text, width and size: no new fit", state.due("Phone book", 60))
        // The holder is rebound to another app: the text changes, the view asks for a layout ...
        assertTrue(state.textChanged(adjusting = false))
        // ... and the new label is fitted, though width and size are the same.
        assertTrue(state.due("Chrome", 60))
        // Back to the first label: fitted again too (the key is the text).
        assertTrue(WholeWords.FitState(26f).run { due("Phone book", 60); due("Chrome", 60); due("Phone book", 60) })
        assertTrue(state.due("Chrome", 61))
        // The rule's own text change (the ellipsis' setText) asks for nothing and keeps the fit.
        assertFalse(state.textChanged(adjusting = true))
        assertFalse(state.due("Chrome", 61))
        assertEquals(WholeWords.fitKey("a", 1, 2f), WholeWords.fitKey("a", 1, 2f))
        assertFalse(WholeWords.fitKey("a", 1, 2f) == WholeWords.fitKey("b", 1, 2f))
    }

    @Test
    fun `only a caller's size moves the base, the rule's own shrink does not (L2)`() {
        val state = WholeWords.FitState(26f)
        state.due("Chrome", 60)
        state.sizeSet(adjusting = true, sizePx = 19.5f) // the rule shrinking
        assertEquals(26f, state.basePx, 0f)
        assertFalse(state.due("Chrome", 60))
        state.sizeSet(adjusting = false, sizePx = 24f) // the adapter's bind (labelSp)
        assertEquals(24f, state.basePx, 0f)
        assertTrue("a new base is fitted again", state.due("Chrome", 60))
    }

    @Test
    fun `the layout check - cut, hyphen, space, past the shown lines, a break opportunity (L1, L2)`() {
        val none: (Int) -> Boolean = { false }
        // "Chro|me": a cut.
        assertTrue(WholeWords.cutLines("Chrome", intArrayOf(4, 6), floatArrayOf(40f, 20f), floatArrayOf(40f, 20f), 3f, 2, none))
        // A drawn hyphen makes the line wider than its text: "Innstillin-|ger" is no cut.
        assertFalse(WholeWords.cutLines("Innstillinger", intArrayOf(10, 13), floatArrayOf(66f, 20f), floatArrayOf(60f, 20f), 3f, 2, none))
        // A width difference below the threshold is no hyphen.
        assertTrue(WholeWords.cutLines("Innstillinger", intArrayOf(10, 13), floatArrayOf(61f, 20f), floatArrayOf(60f, 20f), 3f, 2, none))
        // After a space: no cut (the text width includes the trailing space).
        assertFalse(WholeWords.cutLines("Phone book", intArrayOf(6, 10), floatArrayOf(40f, 30f), floatArrayOf(44f, 30f), 3f, 2, none))
        // A cut at the end of the last shown line is the ellipsis' (maxLines 2, three lines).
        assertFalse(WholeWords.cutLines("Ab Kalkulator", intArrayOf(3, 8, 13), floatArrayOf(20f, 50f, 20f), floatArrayOf(20f, 50f, 20f), 3f, 2, none))
        assertTrue(WholeWords.cutLines("Ab Kalkulator", intArrayOf(3, 8, 13), floatArrayOf(20f, 50f, 20f), floatArrayOf(20f, 50f, 20f), 3f, 3, none))
        // A line-break opportunity is never a cut (CJK, Thai).
        assertFalse(WholeWords.cutLines("Chrome", intArrayOf(4, 6), floatArrayOf(40f, 20f), floatArrayOf(40f, 20f), 3f, 2) { it == 4 })
        // shownLines: all, or maxLines when there are more.
        assertEquals(2, WholeWords.shownLines(2, 3))
        assertEquals(2, WholeWords.shownLines(2, 2))
        assertEquals(1, WholeWords.shownLines(2, 1))
        assertEquals(3, WholeWords.shownLines(-1, 3))
    }

    @Test
    fun `break opportunities - ideographs wrap, a Latin word does not (L1)`() {
        val zh = WholeWords.breakOpportunities("微信支付", Locale.CHINESE)
        assertTrue("between two ideographs", zh(2))
        assertTrue(zh(1) && zh(3))
        val ja = WholeWords.breakOpportunities("カレンダー", Locale.JAPANESE)
        assertTrue(ja(2))
        val en = WholeWords.breakOpportunities("Mamma", Locale.ENGLISH)
        assertFalse("inside a Latin word", en(4))
        val nb = WholeWords.breakOpportunities("Phone book", Locale.forLanguageTag("nb"))
        assertTrue("after the space", nb(6))
        assertFalse(nb(3))
        assertTrue(WholeWords.breakOpportunities("Anne-Marie", Locale.ENGLISH)(5))
        // So a two-line Chinese label is a wrap, never a cut that shrinks it.
        val text = "微信支付"
        assertFalse(WholeWords.cutLines(text, intArrayOf(2, 4), floatArrayOf(30f, 30f), floatArrayOf(30f, 30f), 3f, 2,
            WholeWords.breakOpportunities(text, Locale.CHINESE)))
        assertTrue(WholeWords.cutLines(text, intArrayOf(2, 4), floatArrayOf(30f, 30f), floatArrayOf(30f, 30f), 3f, 2) { false })
    }
}
