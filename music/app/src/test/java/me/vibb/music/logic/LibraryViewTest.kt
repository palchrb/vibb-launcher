package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.NewsRow
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.sync.NewNotifier
import me.vibb.music.ui.entryUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library screen's words and rows (design 21 §4.2). */
class LibraryViewTest {
    private val order = listOf("e5", "e4", "e3", "e2", "e1")

    @Test
    fun `hints - started, not started, from the start`() {
        val podcast = entry(1)
        assertEquals(Hint.Started(Kind.PODCAST, 3, 5), hint(podcast, order, Bookmark("e3", 0)))
        assertEquals(Hint.Started(Kind.PODCAST, 1, 5), hint(podcast, order, Bookmark("e5", 10_000)))
        assertEquals(Hint.NotStarted(Kind.PODCAST), hint(podcast, order, null))
        assertEquals(Hint.NotStarted(Kind.PODCAST), hint(podcast, order, Bookmark("e5", 0)))
        assertEquals(Hint.FromStart(Kind.OWN), hint(entry(2, Source.OWN), order, Bookmark("e3", 0)))
    }

    @Test
    fun `the overall progress counts items before plus the fraction of this one`() {
        assertEquals(0.5f, overallProgress(order, Bookmark("e3", 300_000), 600_000), 0.001f)
        assertEquals(0f, overallProgress(order, null, 600_000), 0f)
    }

    @Test
    fun `NY shows while the entry has visible items in Nytt`() {
        val e = entry(1)
        val snap = snapshot(library(listOf(e)), items(1, listOf("x", "y"), gone = setOf("x")), news = listOf(NewsRow(1, "y", 1)))
        assertTrue(entryUi(snap, e).isNew)
        val hidden = snapshot(library(listOf(e)), items(1, listOf("x", "y"), gone = setOf("x")), news = listOf(NewsRow(1, "x", 1)))
        assertFalse(entryUi(hidden, e).isNew)
        assertEquals("the bell and the notification count only what can play", 0, NewNotifier.count(hidden))
        assertEquals(1, NewNotifier.count(snap))
    }

    @Test
    fun `search - entries and items, at most 12, nothing found`() {
        val a = entry(1).copy(name = "Abels tårn")
        val b = entry(2).copy(name = "Bilturen")
        val itemsOf = { e: me.vibb.music.model.Entry ->
            (1..20).map { PlayItem(e.id, "k$it", "${e.name} del $it", "https://x/$it", false, null) }
        }
        val hits = search("abel", listOf(a, b), itemsOf)
        assertEquals(SEARCH_MAX, hits.size)
        assertTrue(hits.first() is SearchHit.EntryHit)
        assertTrue(hits.drop(1).all { it is SearchHit.ItemHit && it.entry == a })
        assertEquals(listOf(a, b), search("", listOf(a, b), itemsOf).map { it.entry })
        assertTrue(search("zzz", listOf(a, b), itemsOf).isEmpty())
        assertEquals(1, search("  BILTUREN ", listOf(a, b)) { emptyList() }.size)
    }

    @Test
    fun `list rows - numbers, the playing one, heard ones and offline marks`() {
        val shown = listOf("a", "b", "c").map { PlayItem(1, it, it, "u", false, 60_000) }
        val rows = listRows(shown, "b", setOf("a", "b"), setOf("c"))
        assertEquals(listOf(1, 2, 3), rows.map { it.number })
        assertTrue(rows[0].heard)
        assertTrue(rows[1].playing)
        assertFalse("the playing one shows its state, not ✓", rows[1].heard)
        assertTrue(rows[2].offline)
    }

    @Test
    fun `durations`() {
        assertEquals("14:10", formatDuration(850_000))
        assertEquals("1:02:03", formatDuration(3_723_000))
        assertEquals("0:00", formatDuration(0))
        assertEquals("", formatDuration(null))
    }

    @Test
    fun `an entry's category and kind`() {
        val e = entry(1, category = 4)
        val lib = library(listOf(e), categories = listOf(me.vibb.music.model.Category(4, "Podkast", "mic", "teal", 40)))
        val ui = entryUi(snapshot(lib, items(1, listOf("x"))), e)
        assertEquals("Podkast", ui.category?.name)
        assertEquals(Kind.PODCAST, ui.kind)
        assertNull("not started: no progress line", ui.progress)
    }
}
