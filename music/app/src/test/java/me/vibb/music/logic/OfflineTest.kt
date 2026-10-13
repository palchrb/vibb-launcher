package me.vibb.music.logic

import me.vibb.music.Fixtures
import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.DownloadRow
import me.vibb.music.data.Snapshot
import me.vibb.music.model.LibraryParser
import me.vibb.music.model.Source
import me.vibb.music.sync.downloadPlan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep/prune (design 21 §4.5, 21b §6/§8). Ports of palchrb/vibb `tests/offline_keep_all.py` and
 * `cache_prune.py` (MIT, Copyright (c) 2026 palchrb), and `offline_browse.py`'s rule that what is
 * on the phone plays without the network.
 */
class OfflineTest {
    private val keys = (1..20).map { "k$it" } // oldest first
    private val now = 1_800_000_000_000L

    private fun plan(snap: Snapshot, rows: List<DownloadRow> = emptyList(), at: Long = now) = downloadPlan(snap, rows, at)

    // tests/offline_keep_all.py

    @Test
    fun `keep 5 takes the newest five, keep all takes everything, 0 nothing`() {
        assertEquals(keys.takeLast(5).toSet(), keepSet(entry(1, cache = 5), keys, null))
        assertEquals(keys.toSet(), keepSet(entry(1, cache = -1), keys, null))
        assertTrue(keepSet(entry(1, cache = 0), keys, null).isEmpty())
    }

    @Test
    fun `the plan downloads the newest N first, keep-all everything`() {
        val lib = library(listOf(entry(1, cache = 5), entry(2, cache = -1)))
        val p = plan(snapshot(lib, items(1, keys) + items(2, keys)))
        assertEquals(listOf("k20", "k19", "k18", "k17", "k16"), p.wanted.filter { it.entry.id == 1L }.map { it.item.key })
        assertEquals(20, p.wanted.count { it.entry.id == 2L })
    }

    // The bookmark and the item after it (21b §6); depth 0 (the user's decision, as vibb's cache 0).

    @Test
    fun `the bookmarked item and the next in play order are kept`() {
        // RSS plays newest first: after k3 comes k2.
        assertEquals(setOf("k20", "k3", "k2"), keepSet(entry(1, cache = 1), keys, "k3"))
        // An oldest-first entry: after k3 comes k4.
        assertEquals(setOf("k20", "k3", "k4"), keepSet(entry(1, cache = 1, order = "oldest_first"), keys, "k3"))
        // The oldest in a newest-first order wraps to the newest, as the rotated queue does.
        assertEquals(setOf("k20", "k1"), keepSet(entry(1, cache = 1), keys, "k1"))
    }

    @Test
    fun `depth 0 downloads nothing and keeps nothing, the item in progress streams too`() {
        val lib = library(listOf(entry(1, cache = 0)))
        val rows = listOf(done(1, "k5"), done(1, "k4"))
        val p = plan(snapshot(lib, items(1, keys), bookmarks = mapOf("e1" to ("k5" to 60_000L))), rows)
        assertTrue(keepSet(entry(1, cache = 0), keys, "k5").isEmpty())
        assertTrue(p.wanted.isEmpty())
        assertEquals(setOf("k5", "k4"), p.prune.map { it.itemKey }.toSet())
    }

    @Test
    fun `the item in progress downloads first`() {
        val lib = library(listOf(entry(1, cache = 3)))
        val p = plan(snapshot(lib, items(1, keys), bookmarks = mapOf("e1" to ("k5" to 60_000L))))
        assertEquals(listOf("k5", "k20", "k19", "k18", "k4"), p.wanted.map { it.item.key })
    }

    // tests/cache_prune.py

    @Test
    fun `1 entries still wanted keep their copies`() {
        val lib = library(listOf(entry(1, cache = 5)))
        assertTrue(plan(snapshot(lib, items(1, keys)), keys.takeLast(5).map { done(1, it) }).prune.isEmpty())
    }

    @Test
    fun `2 copies of removed entries and of items beyond the depth are pruned`() {
        val lib = library(listOf(entry(1, cache = 2)))
        val rows = listOf(done(1, "k20"), done(1, "k19"), done(1, "k18"), done(9, "x"), done(1, "k17", finished = false))
        val pruned = plan(snapshot(lib, items(1, keys)), rows).prune.map { it.entryId to it.itemKey }.toSet()
        assertEquals(setOf(1L to "k18", 9L to "x", 1L to "k17"), pruned)
    }

    @Test
    fun `3 an entry without a parsed listing keeps what it has (QA #3)`() {
        val lib = library(listOf(entry(1, cache = 1)))
        val p = plan(snapshot(lib, emptyList(), listings = emptyMap()), listOf(done(1, "k1"), done(1, "k2")))
        assertTrue(p.prune.isEmpty())
        assertTrue(p.wanted.isEmpty())
    }

    @Test
    fun `4 an empty library clears every copy, an unparsed one deletes nothing`() {
        val rows = listOf(done(1, "k1"), done(2, "k2"))
        assertEquals(2, plan(snapshot(library(emptyList())), rows).prune.size)
        assertTrue(plan(snapshot(null), rows).prune.isEmpty())
        assertEquals(setOf(2L), removedEntries(setOf(1, 2), setOf(1)))
        assertTrue(removedEntries(setOf(1, 2), null).isEmpty())
    }

    // qa-21-step2-code H1: what the app can't read is hidden, never removed.

    @Test
    fun `an entry the app can't read keeps its copies, only one the library no longer names loses them`() {
        val text = Fixtures.resource("music_library.json").replace("\"cache\": 3", "\"cache\": 200")
        val lib = LibraryParser.parse(text)!!
        assertNull("hidden", lib.entry(2))
        assertTrue("but named", 2L in lib.entryIds)
        val rows = listOf(done(2, "k1"), done(7, "x"))
        assertEquals(listOf(7L), plan(snapshot(lib), rows).prune.map { it.entryId })
    }

    // 21b §8 "Music app (step 2)": withdrawn copies, the bookmark, hidden null items.

    @Test
    fun `a kept copy whose url became null stays and plays, offline too`() {
        val e = entry(1, cache = 3)
        val snap = snapshot(library(listOf(e)), items(1, keys, gone = setOf("k20")), downloads = listOf(done(1, "k20")))
        assertTrue(plan(snap, listOf(done(1, "k20"))).prune.isEmpty())
        assertTrue(snap.visible(e).any { it.key == "k20" })
        assertEquals(Playability.LOCAL, playability(snap.hasCopy(1, "k20"), false, null, false))
    }

    @Test
    fun `a withdrawn copy outside the keep set is pruned, the bookmark never (above depth 0)`() {
        val e = entry(1, cache = 1)
        // Kept: the newest (k20), the bookmark (k4) and the item after it (k3, newest first).
        val rows = listOf(done(1, "k2"), done(1, "k4"))
        val snap = snapshot(library(listOf(e)), items(1, keys, gone = setOf("k2", "k4")), downloads = rows, bookmarks = mapOf("e1" to ("k4" to 90_000L)))
        assertEquals(listOf("k2"), plan(snap, rows).prune.map { it.itemKey })
        assertEquals(setOf("x"), prunable(setOf("k4", "x"), setOf("k4")))
    }

    @Test
    fun `a url-null item without a copy is hidden, an entry with nothing to play too`() {
        val a = entry(1)
        val b = entry(2)
        val snap = snapshot(library(listOf(a, b)), items(1, listOf("x", "y"), gone = setOf("x")) + items(2, listOf("z"), gone = setOf("z")))
        assertEquals(listOf("y"), snap.visible(a).map { it.key })
        assertEquals(listOf(1L), snap.entries.map { it.id })
    }

    @Test
    fun `an NRK or RSS entry never listed is hidden`() {
        assertTrue(snapshot(library(listOf(entry(1, items = null))), emptyList(), listings = emptyMap()).entries.isEmpty())
    }

    @Test
    fun `own files play from their copies only, missing ones included`() {
        val e = entry(3, source = Source.OWN, cache = -1)
        val snap = snapshot(library(listOf(e), listOf(file(1, 3), file(2, 3, missing = true), file(4, 3))), ownCopies = setOf(1, 2))
        assertEquals(listOf("f1", "f2"), snap.visible(e).map { it.key })
        assertTrue("own files never go through the download plan", plan(snap).wanted.isEmpty())
    }

    // qa-21-step2-code M1/M4: per-item retries; a new url at once.

    @Test
    fun `a 4xx is retried after a day (then daily), a new url at once`() {
        val lib = library(listOf(entry(1, cache = 1)))
        val url = "https://cdn.example.org/k20.mp3"
        val failed = done(1, "k20", finished = false).copy(failedUrl = url, failedCode = "http_403", failedAt = now, attempts = 1)
        val p = plan(snapshot(lib, items(1, keys)), listOf(failed))
        assertTrue(p.wanted.isEmpty())
        assertEquals(now + RETRY_PERMANENT_MS, p.nextRetryAt)
        assertEquals(listOf("k20"), plan(snapshot(lib, items(1, keys)), listOf(failed), now + RETRY_PERMANENT_MS).wanted.map { it.item.key })
        val renewed = failed.copy(failedUrl = "https://old.example.org/k20.mp3")
        assertEquals(listOf("k20"), plan(snapshot(lib, items(1, keys)), listOf(renewed)).wanted.map { it.item.key })
    }

    @Test
    fun `other failures wait 2 min, doubling, at most 6 h - and never hold the other items back`() {
        assertEquals(2 * 60_000L, retryDelayMs("http_503", 1))
        assertEquals(8 * 60_000L, retryDelayMs("network", 3))
        assertEquals(RETRY_MAX_MS, retryDelayMs("bad_media", 30))
        assertTrue(permanentFailure("http_404") && permanentFailure("http_410"))
        assertFalse(permanentFailure("http_503") || permanentFailure("http_429") || permanentFailure("http_408") || permanentFailure("network"))
        val lib = library(listOf(entry(1, cache = 3)))
        val failed = done(1, "k20", finished = false).copy(failedUrl = "https://cdn.example.org/k20.mp3", failedCode = "http_503", failedAt = now, attempts = 1)
        assertEquals(listOf("k19", "k18"), plan(snapshot(lib, items(1, keys)), listOf(failed)).wanted.map { it.item.key })
        assertTrue(mayRetry(null, null, null, 0, "u", now))
        assertFalse(mayRetry("u", "network", now, 1, "u", now + 60_000))
        assertTrue(mayRetry("u", "network", now, 1, "u", now + 120_000))
    }

    @Test
    fun `the report counts have and want over the keep set`() {
        val lib = library(listOf(entry(1, cache = 3)))
        val state = plan(snapshot(lib, items(1, keys, gone = setOf("k19"))), listOf(done(1, "k20"))).states.single()
        assertEquals(DownloadState(1, "0123456789abcdef", 1, 2, null), state)
    }

    @Test
    fun `the list header says all or how many`() {
        assertEquals(OfflineSummary.All, offlineSummary(listOf("a", "b"), setOf("a", "b")))
        assertEquals(OfflineSummary.Some(1, 2), offlineSummary(listOf("a", "b"), setOf("a")))
    }

    // Storytel (design 26b §3.3; vibb tests/storytel_expand.py 3/4, storytel_routes.py 5).

    private val books = listOf("111", "222", "333")
    private fun series(id: Long = 7, cache: Int = -1) = entry(id, Source.STORYTEL, target = "storytel:series:26175", cache = cache)
    private fun book(entry: Long, key: String) = done(entry, key, url = "storytel:book:$key")

    @Test
    fun `fetchable is not visibility - a Storytel book is wanted though listed without a url, a withdrawn episode never`() {
        val items = listOf(KeepItem("a", hasUrl = false, fetchable = true), KeepItem("b", hasUrl = false), KeepItem("c", hasUrl = true))
        assertEquals(listOf("c", "a"), wantedDownloads(setOf("a", "b", "c"), items, emptySet(), null))
        assertEquals("visibility keeps url or copy", listOf("c"), visibleKeys(items, emptySet()))
        assertTrue(KeepItem("d", hasUrl = true).fetchable)
        assertFalse(KeepItem("d", hasUrl = false).fetchable)
    }

    @Test
    fun `3 Storytel copies are kept while the book is listed, pruned when the list drops it`() {
        val e = series()
        val rows = books.map { book(7, it) }
        val listed = snapshot(library(listOf(e)), items(7, books, gone = books.toSet()), downloads = rows)
        assertTrue(plan(listed, rows).prune.isEmpty())
        assertTrue(plan(listed, rows).wanted.isEmpty())
        val left = snapshot(library(listOf(e)), items(7, listOf("111", "333"), gone = books.toSet()), downloads = rows)
        assertEquals("a book that left the shelf leaves the phone", listOf("222"), plan(left, rows).prune.map { it.itemKey })
    }

    @Test
    fun `4 and routes 5 the library drops the entry (switch off, login cleared) - every book is pruned`() {
        val rows = books.map { book(7, it) } + done(1, "k20")
        val snap = snapshot(library(listOf(entry(1))), items(1, keys))
        assertEquals(books.map { 7L to it }.toSet(), plan(snap, rows).prune.map { it.entryId to it.itemKey }.toSet())
    }

    @Test
    fun `the first N books keep their copies, the bookmark and the next too, and a finished book beyond them goes`() {
        val e = series(cache = 1)
        val rows = books.map { book(7, it) }
        val snap = snapshot(library(listOf(e)), items(7, books, gone = books.toSet()), downloads = rows, bookmarks = mapOf("e7" to ("222" to 60_000L)))
        // kept: 111 (the first), 222 (the bookmark), 333 (the next); with the bookmark on 111 only 111 and 222
        assertTrue(plan(snap, rows).prune.isEmpty())
        val early = snapshot(library(listOf(e)), items(7, books, gone = books.toSet()), downloads = rows, bookmarks = mapOf("e7" to ("111" to 60_000L)))
        assertEquals(listOf("333"), plan(early, rows).prune.map { it.itemKey })
    }

    // tests/offline_browse.py: what is on the phone plays at once, without the network.

    @Test
    fun `offline, a copy plays and a stream waits for the network`() {
        assertEquals(Playability.LOCAL, playability(hasCopy = true, hasUrl = true, caps = null, streamMobileData = false))
        assertEquals(Playability.NEEDS_NETWORK, playability(hasCopy = false, hasUrl = true, caps = null, streamMobileData = false))
        val wifi = NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
        assertEquals(Playability.STREAM, playability(hasCopy = false, hasUrl = true, caps = wifi, streamMobileData = false))
    }
}
