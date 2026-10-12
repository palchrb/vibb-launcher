package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.model.ListingItem
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of palchrb/vibb `tests/new_badge.py` (MIT, Copyright (c) 2026 palchrb), per item as design
 * 21 §4.5 has it: never new on first sight, a later new item joins Nytt and lights NY, playing it
 * clears it, entries are independent, a blank read changes nothing, and a removed entry takes its
 * items along. Playing never touches the resume target (Nytt is separate from bookmarks).
 */
class NewItemsTest {
    @Test
    fun `1 the first listing never badges existing content`() {
        val (fresh, seen) = markNewSeen(null, listOf("ep-4", "ep-5"))
        assertTrue(fresh.isEmpty())
        assertEquals(setOf("ep-4", "ep-5"), seen)
    }

    @Test
    fun `2 an unchanged listing stays quiet`() {
        val (fresh, seen) = markNewSeen(setOf("ep-4", "ep-5"), listOf("ep-4", "ep-5"))
        assertTrue(fresh.isEmpty())
        assertEquals(setOf("ep-4", "ep-5"), seen)
    }

    @Test
    fun `3 a new episode lights the badge`() {
        val (fresh, seen) = markNewSeen(setOf("ep-4", "ep-5"), listOf("ep-4", "ep-5", "ep-6"))
        assertEquals(listOf("ep-6"), fresh)
        val tray = addToTray(emptyList(), 1, fresh)
        assertTrue(isNew(tray, 1))
        assertEquals(setOf("ep-4", "ep-5", "ep-6"), seen)
    }

    @Test
    fun `4 playing the new item clears it`() {
        val tray = addToTray(emptyList(), 1, listOf("ep-6"))
        assertFalse(isNew(playedFromTray(tray, 1, "ep-6"), 1))
    }

    @Test
    fun `5 entries are independent and a blank read is a no-op`() {
        var tray = addToTray(emptyList(), 2, listOf("f-2"))
        assertEquals(emptyList<String>() to setOf("f-1"), markNewSeen(setOf("f-1"), emptyList()))
        assertEquals("a blank first read stays unseen", emptyList<String>() to null, markNewSeen(null, emptyList()))
        tray = playedFromTray(tray, 1, "ep-6")
        assertTrue(isNew(tray, 2))
        assertFalse(isNew(tray, 1))
    }

    @Test
    fun `6 the tray is newest first and holds at most 30`() {
        var tray = addToTray(emptyList(), 1, (1..20).map { "a$it" })
        tray = addToTray(tray, 2, (1..20).map { "b$it" })
        assertEquals(NEWS_MAX, tray.size)
        assertEquals("b20", tray.first().key)
        assertTrue("the oldest went", tray.none { it.key == "a1" })
        assertEquals("an item already there isn't added twice", tray, addToTray(tray, 2, listOf("b20")))
    }

    /** Design 26b §3.4, vibb `tests/storytel_expand.py` 8: the newest book is the NY trigger. */
    @Test
    fun `8 a new Storytel book joins the tray though listed without a url, a withdrawn episode never`() {
        val series = entry(7, Source.STORYTEL, target = "storytel:series:26175")
        val books = listOf("111", "222", "333").map { ListingItem(it, "Bok $it", null, false, 700_000, null) }
        assertEquals(setOf("111", "222", "333"), newsEligible(series, books))
        val (first, seen) = markNewSeen(null, books.take(2).map { it.key })
        assertTrue("the first listing acknowledges every book", first.isEmpty())
        val (fresh, _) = markNewSeen(seen, books.map { it.key })
        val news = fresh.filter { it in newsEligible(series, books) }
        assertEquals(listOf("333"), news)
        assertTrue(isNew(addToTray(emptyList(), 7, news), 7))
        val feed = listOf(ListingItem("ep-1", "Ep", null, false, null, null), ListingItem("ep-2", "Ep", "https://cdn.example.org/2.mp3", false, null, null))
        assertEquals(setOf("ep-2"), newsEligible(entry(1), feed))
    }

    @Test
    fun `7 removing an entry prunes its items`() {
        val tray = addToTray(addToTray(emptyList(), 1, listOf("x")), 2, listOf("y"))
        assertEquals(listOf(1L), pruneTray(tray, setOf(1L)).map { it.entryId })
    }
}
