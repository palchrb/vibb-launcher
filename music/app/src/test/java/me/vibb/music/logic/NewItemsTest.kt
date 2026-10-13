package me.vibb.music.logic

import me.vibb.music.Fixtures
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.model.Entry
import me.vibb.music.model.LibraryParser
import me.vibb.music.model.ListingItem
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.model.toPlayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Port of palchrb/vibb `tests/new_badge.py` (MIT, Copyright (c) 2026 palchrb), per item as design
 * 21 §4.5 has it: never new on first sight, a later new item joins Nytt and lights NY, playing it
 * clears it, entries are independent, a blank read changes nothing, and a removed entry takes its
 * items along. Playing never touches the resume target (Nytt is separate from bookmarks).
 *
 * The `d29` tests are not vibb ports: design 29's new entries (one item per entry the phone never
 * knew, after the baseline), its baseline, reused ids and the 24 h settle. The run-level ones go
 * through [sync], which calls the pure functions in `Sync.run`'s order over several syncs.
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

    // --- Design 29: a new entry is news too (not vibb) ---

    private val day = NEW_ENTRY_SETTLE_MS
    private val hour = 60 * 60 * 1000L

    private fun keys(prefix: String, n: Int) = (1..n).map { "$prefix$it" }

    private fun listed(e: Entry, keys: List<String>, eligible: Set<String>? = keys.toSet(), unplayable: Set<String> = emptySet()) =
        Listed(e, keys, if (e.source == Source.OWN) null else eligible, unplayable)

    /** The phone's news rows across syncs, as Room keeps them. */
    private data class Phone(
        val known: List<KnownEntry> = emptyList(),
        val closed: Boolean = false,
        val seen: Map<Long, Set<String>> = emptyMap(),
        val tray: List<NewsItem> = emptyList(),
        /** The applied listings (own entries too), by entry id. */
        val listings: Map<Long, Listed> = emptyMap(),
    )

    /**
     * One sync in `Sync.run`'s order: [newsBefore] over the library's keys, the resets (listing, seen,
     * tray), the listings arriving this sync applied (own entries are passed every sync), [newsAfter],
     * then the prune of what the library no longer names. [unreadable]: entries named but not usable.
     */
    private fun sync(
        state: Phone, library: List<Entry>, played: Boolean, listings: List<Listed> = emptyList(), now: Long = 0,
        unreadable: Map<Long, String> = emptyMap(),
    ): Phone {
        val named = library.associate { it.id to it.key } + unreadable
        val before = newsBefore(state.known, state.closed, named, played)
        val seen = state.seen - before.reset
        val tray = state.tray.filterNot { it.entryId in before.reset }
        val applied = listings.filter { it.entry in library }
        val current = (state.listings - before.reset + applied.associateBy { it.entry.id }).filterKeys { it in named }
        val ids = applied.map { it.entry.id }.toSet()
        val after = newsAfter(before.known, tray, seen.filterKeys { it in ids }, applied, current, now, open = !before.closed)
        val seenAfter = seen + after.seenAdded.mapValues { (id, add) -> seen[id].orEmpty() + add }
        return Phone(after.known, before.closed, seenAfter.filterKeys { it in named }, pruneTray(after.tray, named.keys), current)
    }

    private val a = entry(1, key = "a")
    private val b = entry(2, key = "b")
    private val c = entry(3, key = "c")

    /** A used phone: a and b known and listed, the baseline closed. */
    private fun used(): Phone =
        sync(Phone(), listOf(a, b), played = true, listOf(listed(a, keys("a", 10)), listed(b, keys("b", 10))))

    @Test
    fun `d29 1 the first library that names an entry is the baseline - nothing is pending`() {
        for (played in listOf(false, true)) {
            val r = newsBefore(emptyList(), closed = false, named = mapOf(1L to "a", 2L to "b"), played = played)
            assertEquals(listOf(KnownEntry("a", 1, false), KnownEntry("b", 2, false)), r.known)
            assertEquals("closed by a stored play", played, r.closed)
            assertTrue(r.reset.isEmpty())
        }
    }

    @Test
    fun `d29 2 a library naming no entry neither takes nor moves the baseline`() {
        val known = listOf(KnownEntry("a", 1, false), KnownEntry("b", 2, true))
        assertEquals(Before(known, false, emptySet()), newsBefore(known, false, emptyMap(), played = true))
        assertEquals(Before(known, true, emptySet()), newsBefore(known, true, emptyMap(), played = false))
        assertEquals(Before(emptyList(), false, emptySet()), newsBefore(emptyList(), false, emptyMap(), played = true))
    }

    @Test
    fun `d29 3 ticks over several syncs before the first play are all baseline`() {
        var known = emptyList<KnownEntry>()
        var closed = false
        for (n in 1L..20L) {
            val r = newsBefore(known, closed, (1L..n).associateWith { "k$it" }, played = false)
            known = r.known
            closed = r.closed
        }
        assertEquals(20, known.size)
        assertTrue("none pending", known.none { it.pending })
        assertFalse("still open", closed)
    }

    @Test
    fun `d29 4 after the first play a key the phone never knew is pending, once`() {
        val open = newsBefore(emptyList(), false, mapOf(1L to "a"), played = false)
        val closing = newsBefore(open.known, open.closed, mapOf(1L to "a"), played = true)
        assertTrue(closing.closed)
        assertTrue(closing.known.none { it.pending })
        val added = newsBefore(closing.known, closing.closed, mapOf(1L to "a", 2L to "b"), played = true)
        assertEquals(listOf(KnownEntry("a", 1, false), KnownEntry("b", 2, true)), added.known)
        val again = newsBefore(added.known, added.closed, mapOf(1L to "a", 2L to "b"), played = true)
        assertEquals("one row, pending until its item is given", added.known, again.known)
    }

    @Test
    fun `d29 5 the baseline stays closed once a play was stored, even with no rows left`() {
        // clearState and the bookmark delete can empty both tables: the flag is sticky.
        val r = newsBefore(listOf(KnownEntry("a", 1, false)), closed = true, named = mapOf(1L to "a", 2L to "b"), played = false)
        assertTrue(r.closed)
        assertEquals(KnownEntry("b", 2, true), r.known.last())
    }

    @Test
    fun `d29 6 an upgrade with positions takes the baseline from its first library`() {
        val first = newsBefore(emptyList(), false, mapOf(1L to "a", 2L to "b", 3L to "c"), played = true)
        assertTrue(first.known.none { it.pending })
        assertTrue(first.closed)
        val next = newsBefore(first.known, first.closed, mapOf(1L to "a", 2L to "b", 3L to "c", 4L to "d"), played = true)
        assertEquals(listOf("d"), next.known.filter { it.pending }.map { it.key })
    }

    @Test
    fun `d29 7 a key taken off and ticked again is not pending - the set only grows, a pending one stays pending`() {
        val known = listOf(KnownEntry("a", 1, false), KnownEntry("b", 2, true))
        val off = newsBefore(known, true, mapOf(3L to "c"), played = true)
        assertEquals(listOf(KnownEntry("a", null, false), KnownEntry("b", null, true), KnownEntry("c", 3, true)), off.known)
        val back = newsBefore(off.known, off.closed, mapOf(1L to "a", 2L to "b", 3L to "c"), played = true)
        assertEquals(listOf(KnownEntry("a", 1, false), KnownEntry("b", 2, true), KnownEntry("c", 3, true)), back.known)
    }

    @Test
    fun `d29 8 an id that now carries another key is reset, and a key under a new id moves`() {
        val known = listOf(KnownEntry("a", 1, false), KnownEntry("b", 2, false))
        val r = newsBefore(known, true, mapOf(1L to "c", 3L to "b", 4L to "d"), played = true)
        assertEquals("id 1 was a's, now c's", setOf(1L), r.reset)
        assertEquals(
            "b moves to 3, a drops id 1",
            listOf(KnownEntry("a", null, false), KnownEntry("b", 3, false), KnownEntry("c", 1, true), KnownEntry("d", 4, true)),
            r.known,
        )
        // A library naming an id for the first time resets nothing; a key under its own id neither.
        assertTrue(newsBefore(r.known, true, mapOf(1L to "c", 3L to "b", 4L to "d", 5L to "a"), played = true).reset.isEmpty())
    }

    @Test
    fun `d29 9 a new podcast's item is its newest playable episode`() {
        val pod = entry(1)
        val eps = keys("ep", 5)
        assertEquals("ep5", newEntryItem(listed(pod, eps)))
        assertEquals("the withdrawn newest is skipped", "ep4", newEntryItem(listed(pod, eps, eligible = eps.toSet() - "ep5")))
        assertEquals("ep1", newEntryItem(listed(entry(1, order = "oldest_first"), eps)))
    }

    @Test
    fun `d29 10 an NRK serie and an own entry give their first item - the effective order counts`() {
        val serie = entry(1, Source.NRK, target = "https://radio.nrk.no/serie/abels-taarn")
        val eps = keys("ep", 6)
        assertEquals("ep1", newEntryItem(listed(serie, eps)))
        assertEquals("a long serie's newest end", "ep6", newEntryItem(listed(serie.copy(order = "newest_first"), eps)))
        assertEquals("f3", newEntryItem(listed(entry(2, Source.OWN), listOf("f3", "f4", "f9"))))
    }

    @Test
    fun `d29 11 a Storytel series gives book 1 in reading order whatever its order`() {
        val books = listOf("111", "222", "333")
        for (order in listOf("auto", "oldest_first", "newest_first")) {
            val series = entry(5, Source.STORYTEL, target = "storytel:series:26175", order = order)
            assertEquals(order, "111", newEntryItem(listed(series, books)))
        }
        val reversed = entry(5, Source.STORYTEL, target = "storytel:series:26175", order = "newest_first")
        assertEquals("play order starts with the newest", "333", playOrder(reversed, books).first())
    }

    @Test
    fun `d29 12 an item that can't play is skipped - no_audio, a missing own file without a copy - and none gives null`() {
        val series = entry(5, Source.STORYTEL, target = "storytel:series:26175")
        val books = listOf("111", "222", "333").map { PlayItem(5, it, "Bok $it", null, false, null) }
        val locked = listedOf(series, books, emptySet(), emptySet(), mapOf("111" to NO_AUDIO, "222" to "http_503"))
        assertEquals("listed without a url, still eligible", setOf("111", "222", "333"), locked.eligible)
        assertEquals("only no_audio is permanent", setOf("111"), locked.unplayable)
        assertEquals("222", newEntryItem(locked))
        assertNull(newEntryItem(listedOf(series, books, emptySet(), emptySet(), books.associate { it.key to NO_AUDIO })))

        val own = entry(3, Source.OWN)
        val files = listOf(file(1, 3, missing = true), file(2, 3, missing = true), file(4, 3)).map { it.toPlayItem() }
        val missing = listedOf(own, files, emptySet(), setOf("f1", "f2"), emptyMap())
        assertNull("every file is eligible", missing.eligible)
        assertEquals("f4", newEntryItem(missing))
        assertEquals("a missing file with a copy plays", "f1", newEntryItem(listedOf(own, files, setOf("f1"), setOf("f1", "f2"), emptyMap())))
        assertNull(newEntryItem(listedOf(own, files, emptySet(), setOf("f1", "f2", "f4"), emptyMap())))
        // An episode's item error never makes it unplayable here: it streams, or its download retries.
        val episode = PlayItem(1, "ep1", "Ep", "https://cdn.example.org/1.mp3", false, null)
        assertTrue(listedOf(entry(1), listOf(episode), emptySet(), emptySet(), mapOf("ep1" to NO_AUDIO)).unplayable.isEmpty())
    }

    @Test
    fun `d29 13 a pending entry's first listing adds exactly one item, a known entry's none`() {
        val eps = keys("ep", 38)
        assertEquals(EntryNews(listOf("ep38"), eps.toSet(), "ep38"), entryNews(listed(entry(1), eps), null, pending = true, settling = false))
        assertEquals(EntryNews(emptyList(), eps.toSet(), null), entryNews(listed(entry(1), eps), null, pending = false, settling = false))
    }

    @Test
    fun `d29 14 an empty listing, or one with no candidate, keeps the mark - the next with a candidate gives the item`() {
        val d = entry(4, key = "d")
        assertEquals(EntryNews(emptyList(), null, null), entryNews(listed(d, emptyList()), null, pending = true, settling = false))
        val known = listOf(KnownEntry("d", 4, true))
        val empty = newsAfter(known, emptyList(), emptyMap(), listOf(listed(d, emptyList())), emptyMap(), 0, open = false)
        assertEquals(After(known, emptyList(), emptyMap()), empty)
        val withdrawn = newsAfter(known, emptyList(), emptyMap(), listOf(listed(d, listOf("ep1", "ep2"), eligible = emptySet())), emptyMap(), 0, open = false)
        assertTrue(withdrawn.tray.isEmpty())
        assertEquals("the mark is kept", known, withdrawn.known)
        assertEquals("the listing is acknowledged", mapOf(4L to setOf("ep1", "ep2")), withdrawn.seenAdded)
        val next = newsAfter(withdrawn.known, emptyList(), withdrawn.seenAdded, listOf(listed(d, listOf("ep1", "ep2", "ep3"), eligible = setOf("ep3"))), emptyMap(), 7, open = false)
        assertEquals(listOf(NewsItem(4, "ep3", 1)), next.tray)
        assertEquals(listOf(KnownEntry("d", 4, false, "ep3", 7)), next.known)
    }

    @Test
    fun `d29 15 a pending entry with a seen set still gives one item and acknowledges the rest`() {
        val r = entryNews(listed(entry(1), keys("b", 50)), keys("a", 80).toSet(), pending = true, settling = false)
        assertEquals(listOf("b50"), r.news)
        assertEquals(keys("a", 80).toSet() + keys("b", 50), r.seen)
    }

    @Test
    fun `d29 16 the 24 h window - fresh keys at NEW_ENTRY_SETTLE_MS - 1 are acknowledged, at NEW_ENTRY_SETTLE_MS they join, and a clock before newAt counts as today`() {
        val known = listOf(KnownEntry("a", 1, false, "a1", newAt = 1000))
        fun run(now: Long) = newsAfter(known, emptyList(), mapOf(1L to setOf("a1")), listOf(listed(a, listOf("a1", "a2"))), emptyMap(), now, open = false)
        assertTrue(run(1000 + day - 1).tray.isEmpty())
        assertEquals("acknowledged", mapOf(1L to setOf("a2")), run(1000 + day - 1).seenAdded)
        assertEquals(listOf("a2"), run(1000 + day).tray.map { it.key })
        assertEquals("a clock before newAt", listOf("a2"), run(999).tray.map { it.key })
    }

    @Test
    fun `d29 17 an item that turns no_audio is replaced in its slot by the next candidate, or leaves when none is left`() {
        val series = entry(5, Source.STORYTEL, key = "s", target = "storytel:series:26175")
        val books = listOf("111", "222", "333")
        val first = newsAfter(listOf(KnownEntry("s", 5, true)), listOf(NewsItem(1, "ep9", 4)), emptyMap(), listOf(listed(series, books)), emptyMap(), 0, open = false)
        assertEquals(listOf(NewsItem(5, "111", 5), NewsItem(1, "ep9", 4)), first.tray)
        val locked = newsAfter(first.known, first.tray, emptyMap(), emptyList(), mapOf(5L to listed(series, books, unplayable = setOf("111"))), 10, open = false)
        assertEquals("the same slot", listOf(NewsItem(5, "222", 5), NewsItem(1, "ep9", 4)), locked.tray)
        assertEquals(KnownEntry("s", 5, false, "222", 0), locked.known.single())
        val none = newsAfter(locked.known, locked.tray, emptyMap(), emptyList(), mapOf(5L to listed(series, books, unplayable = books.toSet())), 20, open = false)
        assertEquals(listOf(NewsItem(1, "ep9", 4)), none.tray)
        assertEquals("nothing without a parsed listing", locked.tray, newsAfter(locked.known, locked.tray, emptyMap(), emptyList(), emptyMap(), 30, open = false).tray)
    }

    @Test
    fun `d29 18 a fresh install whose listings arrive over three syncs adds nothing`() {
        var p = sync(Phone(), listOf(a), played = false, listOf(listed(a, keys("a", 10))))
        p = sync(p, listOf(a, b), played = false, listOf(listed(b, keys("b", 10))))
        p = sync(p, listOf(a, b, c), played = false, listOf(listed(c, keys("c", 10))))
        assertTrue(p.tray.isEmpty())
        assertFalse(p.closed)
        assertTrue(p.known.none { it.pending })
        assertEquals(setOf(1L, 2L, 3L), p.seen.keys)
    }

    @Test
    fun `d29 19 setup ticks one at a time, then a play, then a new show - one item`() {
        var p = Phone()
        val shows = (1L..6L).map { entry(it, key = "s$it") }
        for (n in 1..6) p = sync(p, shows.take(n), played = false, listOf(listed(shows[n - 1], keys("s$n-", 20))))
        assertTrue("setup is never news", p.tray.isEmpty())
        p = sync(p, shows, played = true)
        assertTrue(p.closed)
        val d = entry(7, key = "new")
        p = sync(p, shows + d, played = true, listOf(listed(d, keys("n", 38))), now = 5)
        assertEquals(listOf(NewsItem(7, "n38", 1)), p.tray)
        assertEquals(KnownEntry("new", 7, false, "n38", 5), p.known.last())
    }

    @Test
    fun `d29 20 a server switch with the same shows under new ids adds nothing`() {
        val start = sync(used(), listOf(a, b), played = true, listOf(listed(a, keys("a", 11))))
        assertEquals("a known show's new episode", listOf("a11"), start.tray.map { it.key })
        val a5 = a.copy(id = 5)
        val b6 = b.copy(id = 6)
        val moved = sync(start, listOf(a5, b6), played = true, listOf(listed(a5, keys("a", 12)), listed(b6, keys("b", 10))))
        assertTrue(moved.tray.isEmpty())
        assertTrue(moved.known.none { it.pending })
        // The ids swapped: both are reset, and neither show's back catalogue is new.
        val a2 = a.copy(id = 2)
        val b1 = b.copy(id = 1)
        val swapped = sync(start, listOf(a2, b1), played = true, listOf(listed(a2, keys("a", 12)), listed(b1, keys("b", 12))))
        assertTrue(swapped.tray.isEmpty())
        assertEquals(mapOf(2L to keys("a", 12).toSet(), 1L to keys("b", 12).toSet()), swapped.seen)
    }

    @Test
    fun `d29 21 a reused id - podcast A's 80 seen keys don't make the new show B's back catalogue new - B gives one item`() {
        val podA = entry(7, key = "pod-a")
        var p = sync(Phone(), listOf(podA), played = true, listOf(listed(podA, keys("a", 80))))
        val podB = entry(7, key = "pod-b")
        p = sync(p, listOf(podB), played = true, listOf(listed(podB, keys("b", 60))))
        assertEquals(listOf(NewsItem(7, "b60", 1)), p.tray)
        assertEquals("A's seen set went with the reset", keys("b", 60).toSet(), p.seen[7])
    }

    @Test
    fun `d29 22 nothing ticked, then a re-tick and one new show - one item`() {
        var p = sync(used(), emptyList(), played = true)
        assertTrue("nothing ticked empties the app", p.seen.isEmpty() && p.listings.isEmpty())
        val d = entry(4, key = "d")
        p = sync(p, listOf(a, b, d), played = true, listOf(listed(a, keys("a", 12)), listed(b, keys("b", 10)), listed(d, keys("d", 5))))
        assertEquals(listOf(NewsItem(4, "d5", 1)), p.tray)
    }

    @Test
    fun `d29 23 a pending entry whose first listing fails gives its item when the listing arrives at the next sync`() {
        val d = entry(4, key = "d")
        var p = sync(used(), listOf(a, b, d), played = true) // bad_listing: nothing applied
        assertTrue(p.tray.isEmpty())
        assertTrue(p.known.single { it.key == "d" }.pending)
        p = sync(p, listOf(a, b, d), played = true, listOf(listed(d, keys("d", 5))))
        assertEquals(listOf(NewsItem(4, "d5", 1)), p.tray)
    }

    @Test
    fun `d29 24 an own entry uploaded in two batches an hour apart is one item, a batch 25 h later counts each file`() {
        val own = entry(3, Source.OWN, key = "own-1")
        val t0 = 10 * day
        var p = sync(used(), listOf(a, b, own), played = true, listOf(listed(own, emptyList())), now = t0 - hour)
        assertTrue("created before its files: still pending", p.known.single { it.key == "own-1" }.pending)
        p = sync(p, listOf(a, b, own), played = true, listOf(listed(own, keys("f", 7))), now = t0)
        assertEquals(listOf(NewsItem(3, "f1", 1)), p.tray)
        p = sync(p, listOf(a, b, own), played = true, listOf(listed(own, keys("f", 12))), now = t0 + hour)
        assertEquals(listOf(NewsItem(3, "f1", 1)), p.tray)
        p = sync(p, listOf(a, b, own), played = true, listOf(listed(own, keys("f", 15))), now = t0 + 25 * hour)
        assertEquals(listOf("f15", "f14", "f13", "f1"), p.tray.map { it.key })
    }

    @Test
    fun `d29 25 the new entry's item is newest in the tray, leaves when played and goes with its entry`() {
        var p = sync(used(), listOf(a, b), played = true, listOf(listed(a, keys("a", 12))))
        val d = entry(4, key = "d")
        p = sync(p, listOf(a, b, d), played = true, listOf(listed(d, keys("d", 3))))
        assertEquals(listOf(NewsItem(4, "d3", 3), NewsItem(1, "a12", 2), NewsItem(1, "a11", 1)), p.tray)
        assertTrue(isNew(p.tray, 4))
        assertFalse(isNew(playedFromTray(p.tray, 4, "d3"), 4))
        p = sync(p, listOf(a, b), played = true)
        assertFalse("removed with its entry", isNew(p.tray, 4))
        p = sync(p, listOf(a, b, d), played = true, listOf(listed(d, keys("d", 3))))
        assertFalse("ticked again: the same key is not new", isNew(p.tray, 4))
    }

    @Test
    fun `d29 26 two entries applied in one run never take one mark twice`() {
        val d = entry(4, key = "d")
        val known = listOf(KnownEntry("d", 4, true))
        val twice = newsAfter(known, emptyList(), emptyMap(), listOf(listed(d, listOf("x1")), listed(d, listOf("x1", "x2"))), emptyMap(), 0, open = false)
        assertEquals("the second is an ordinary listing, inside the window", listOf("x1"), twice.tray.map { it.key })
        assertEquals(mapOf(4L to setOf("x1", "x2")), twice.seenAdded)
        assertEquals("x1", twice.known.single().item)
        // A row that holds another id is not this entry's mark (keys are unique on the server: a defence).
        val other = newsAfter(known, emptyList(), emptyMap(), listOf(listed(d.copy(id = 9), listOf("y1")), listed(d, listOf("x1"))), emptyMap(), 0, open = false)
        assertEquals(listOf(NewsItem(4, "x1", 1)), other.tray)
    }

    @Test
    fun `d29 27 keyById names every entry's id and key, an unreadable one too`() {
        val spotify = """{"id": 9, "key": "sp9", "name": "Spotify", "source": "spotify", "target": "spotify:playlist:x"},"""
        val lib = LibraryParser.parse(Fixtures.resource("music_library.json").replaceFirst("\"entries\": [", "\"entries\": [$spotify"))!!
        assertNull(lib.entry(9))
        assertEquals(
            mapOf(9L to "sp9", 1L to "abels_taarn", 2L to "40201d4c16fc", 3L to "own-5c0ffee1d0e5", 5L to "7ac843e9f5d4", 6L to "400584733efd", 7L to "4f94c81c7059"),
            lib.keyById,
        )
        assertEquals(lib.entryIds, lib.keyById.keys)
    }

    @Test
    fun `d29 28 the schema is exported - version 3 adds known_entries and news_baseline by an auto migration`() {
        val v3 = moduleFile("schemas/me.vibb.music.data.MusicDb/3.json").readText()
        assertTrue(v3.contains("\"version\": 3"))
        val table = "CREATE TABLE IF NOT EXISTS `\${TABLE_NAME}`"
        assertTrue(v3.contains("\"tableName\": \"known_entries\",\n        \"createSql\": \"$table (`entryKey` TEXT NOT NULL, `entryId` INTEGER, `pending` INTEGER NOT NULL, `item` TEXT, `newAt` INTEGER, PRIMARY KEY(`entryKey`))\""))
        assertTrue(v3.contains("\"tableName\": \"news_baseline\",\n        \"createSql\": \"$table (`id` INTEGER NOT NULL, `closed` INTEGER NOT NULL, PRIMARY KEY(`id`))\""))
        val v2 = moduleFile("schemas/me.vibb.music.data.MusicDb/2.json").readText()
        assertFalse("new tables only", v2.contains("known_entries"))
        val db = moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 2, to = 3),"))
        assertTrue(db.contains("version = 3,"))
    }

    /** qa-29-code H1 (its probe T1): a phone set up over days, or on the shelf, never fills the tray before a play. */
    @Test
    fun `d29 29 while the baseline is open a known entry's later keys are acknowledged, not added`() {
        val own = entry(3, Source.OWN, key = "own-1")
        var p = sync(Phone(), listOf(a, own), played = false, listOf(listed(a, keys("a", 40)), listed(own, keys("f", 7))))
        p = sync(p, listOf(a, own), played = false, listOf(listed(a, keys("a", 100)), listed(own, keys("f", 12))), now = day)
        assertFalse(p.closed)
        assertTrue("no flood before the first play", p.tray.isEmpty())
        assertEquals(keys("a", 100).toSet(), p.seen[1])
        // After the first play a new key counts again (the closing sync's own listings already count).
        p = sync(p, listOf(a, own), played = true, listOf(listed(a, keys("a", 101)), listed(own, keys("f", 12))), now = 2 * day)
        assertEquals(listOf(NewsItem(1, "a101", 1)), p.tray)
    }

    /** qa-29-code L1 (its probe T2): only an item that can no longer play is re-picked. */
    @Test
    fun `d29 30 a new podcast's item stays put when a newer episode arrives`() {
        val d = entry(4, key = "d")
        var p = sync(used(), listOf(a, b, d), played = true, listOf(listed(d, keys("d", 3))), now = 0)
        assertEquals(listOf(NewsItem(4, "d3", 1)), p.tray)
        p = sync(p, listOf(a, b, d), played = true, listOf(listed(d, keys("d", 4))), now = hour)
        assertEquals("d4 is inside the window, d3 keeps its slot", listOf(NewsItem(4, "d3", 1)), p.tray)
        p = sync(p, listOf(a, b, d), played = true, now = 2 * hour)
        assertEquals(listOf(NewsItem(4, "d3", 1)), p.tray)
        assertEquals("d3", p.known.single { it.key == "d" }.item)
    }

    /** qa-29-code M1 (B): phase B sees every own entry at every sync, after this run's listings. */
    @Test
    fun `d29 31 phase B's entries are this run's listings, then every own entry`() {
        val own = entry(3, Source.OWN, key = "own-1")
        val current = mapOf(1L to listed(a, keys("a", 2)), 2L to listed(b, keys("b", 2)), 3L to listed(own, keys("f", 2)))
        assertEquals(listOf(2L, 3L), newsApplied(listOf(2L), listOf(a, b, own), current).map { it.entry.id })
        assertEquals("an id without a parsed listing is skipped", listOf(3L), newsApplied(listOf(9L), listOf(a, b, own), current).map { it.entry.id })
        assertTrue(newsApplied(emptyList(), listOf(a, own), emptyMap()).isEmpty())
    }

    /** qa-29-code L4: a tray item that fails for good asks for the sync that re-picks it. */
    @Test
    fun `d29 32 a permanent failure of a tray item asks for a sync, any other failure or item doesn't`() {
        val tray = listOf(NewsItem(5, "111", 1))
        assertTrue(repickWanted(tray, 5, "111", NO_AUDIO))
        assertFalse(repickWanted(tray, 5, "111", "http_503"))
        assertFalse(repickWanted(tray, 5, "222", NO_AUDIO))
        assertFalse(repickWanted(tray, 6, "111", NO_AUDIO))
    }
}
