package me.vibb.music.logic

import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.file
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.model.Source
import me.vibb.music.sync.downloadPlan
import me.vibb.music.sync.permanentFailure
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep/prune (design 21 §4.5, 21b §6/§8). Ports of palchrb/vibb `tests/offline_keep_all.py` and
 * `cache_prune.py` (MIT, Copyright (c) 2026 palchrb), and `offline_browse.py`'s rule that what is
 * on the phone plays without the network.
 */
class OfflineTest {
    private val keys = (1..20).map { "k$it" } // oldest first

    // tests/offline_keep_all.py

    @Test
    fun `keep 5 takes the newest five, keep all takes everything`() {
        assertEquals(keys.takeLast(5).toSet(), keepSet(entry(1, cache = 5), keys, null))
        assertEquals(keys.toSet(), keepSet(entry(1, cache = -1), keys, null))
        assertTrue(keepSet(entry(1, cache = 0), keys, null).isEmpty())
    }

    @Test
    fun `the plan downloads the newest N first, keep-all everything`() {
        val lib = library(listOf(entry(1, cache = 5), entry(2, cache = -1)))
        val snap = snapshot(lib, items(1, keys) + items(2, keys))
        val plan = downloadPlan(snap, emptyList())
        val one = plan.wanted.filter { it.entry.id == 1L }.map { it.item.key }
        assertEquals(listOf("k20", "k19", "k18", "k17", "k16"), one)
        assertEquals(20, plan.wanted.count { it.entry.id == 2L })
    }

    // The bookmark and the item after it (21b §6).

    @Test
    fun `the bookmarked item and the next in play order are kept, even at depth 0`() {
        // RSS plays newest first: after k3 comes k2.
        assertEquals(setOf("k3", "k2"), keepSet(entry(1, cache = 0), keys, "k3"))
        // An oldest-first entry: after k3 comes k4.
        assertEquals(setOf("k3", "k4"), keepSet(entry(1, cache = 0, order = "oldest_first"), keys, "k3"))
        // The oldest in a newest-first order wraps to the newest, as the rotated queue does.
        assertEquals(setOf("k1", "k20"), keepSet(entry(1, cache = 0), keys, "k1"))
    }

    @Test
    fun `the item in progress downloads first`() {
        val lib = library(listOf(entry(1, cache = 3)))
        val plan = downloadPlan(snapshot(lib, items(1, keys), bookmarks = mapOf("e1" to ("k5" to 60_000L))), emptyList())
        assertEquals(listOf("k5", "k20", "k19", "k18", "k4"), plan.wanted.map { it.item.key })
    }

    // tests/cache_prune.py

    @Test
    fun `1 entries still wanted keep their copies`() {
        val lib = library(listOf(entry(1, cache = 5)))
        val rows = keys.takeLast(5).map { done(1, it) }
        assertTrue(downloadPlan(snapshot(lib, items(1, keys)), rows).prune.isEmpty())
    }

    @Test
    fun `2 copies of removed entries and of items beyond the depth are pruned`() {
        val lib = library(listOf(entry(1, cache = 2)))
        val rows = listOf(done(1, "k20"), done(1, "k19"), done(1, "k18"), done(9, "x"), done(1, "k17", finished = false))
        val pruned = downloadPlan(snapshot(lib, items(1, keys)), rows).prune.map { it.entryId to it.itemKey }.toSet()
        assertEquals(setOf(1L to "k18", 9L to "x", 1L to "k17"), pruned)
    }

    @Test
    fun `3 an entry without a parsed listing keeps what it has (QA #3)`() {
        val lib = library(listOf(entry(1, cache = 1)))
        val rows = listOf(done(1, "k1"), done(1, "k2"))
        val plan = downloadPlan(snapshot(lib, emptyList(), listings = emptyMap()), rows)
        assertTrue(plan.prune.isEmpty())
        assertTrue(plan.wanted.isEmpty())
    }

    @Test
    fun `4 an empty library clears every copy, an unparsed one deletes nothing`() {
        val rows = listOf(done(1, "k1"), done(2, "k2"))
        assertEquals(2, downloadPlan(snapshot(library(emptyList())), rows).prune.size)
        assertTrue(downloadPlan(snapshot(null), rows).prune.isEmpty())
        assertEquals(setOf(2L), removedEntries(setOf(1, 2), setOf(1)))
        assertTrue(removedEntries(setOf(1, 2), null).isEmpty())
    }

    // 21b §8 "Music app (step 2)": withdrawn copies, the bookmark, hidden null items.

    @Test
    fun `a kept copy whose url became null stays and plays, offline too`() {
        val e = entry(1, cache = 3)
        val snap = snapshot(library(listOf(e)), items(1, keys, gone = setOf("k20")), downloads = listOf(done(1, "k20")))
        val plan = downloadPlan(snap, listOf(done(1, "k20")))
        assertTrue(plan.prune.isEmpty())
        assertTrue(snap.visible(e).any { it.key == "k20" })
        assertEquals(Playability.LOCAL, playability(snap.hasCopy(1, "k20"), false, null, false))
    }

    @Test
    fun `a withdrawn copy outside the keep set is pruned, the bookmark never`() {
        val e = entry(1, cache = 1)
        // Kept: the newest (k20), the bookmark (k4) and the item after it (k3, newest first).
        val rows = listOf(done(1, "k2"), done(1, "k4"))
        val snap = snapshot(library(listOf(e)), items(1, keys, gone = setOf("k2", "k4")), downloads = rows, bookmarks = mapOf("e1" to ("k4" to 90_000L)))
        val pruned = downloadPlan(snap, rows).prune.map { it.itemKey }
        assertEquals(listOf("k2"), pruned)
        assertTrue(prunable(setOf("k4"), emptySet(), "k4").isEmpty())
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
        val snap = snapshot(library(listOf(entry(1, items = null))), emptyList(), listings = emptyMap())
        assertTrue(snap.entries.isEmpty())
    }

    @Test
    fun `own files play from their copies only, missing ones included`() {
        val e = entry(3, source = Source.OWN, cache = -1)
        val lib = library(listOf(e), listOf(file(1, 3), file(2, 3, missing = true), file(4, 3)))
        val snap = snapshot(lib, ownCopies = setOf(1, 2))
        assertEquals(listOf("f1", "f2"), snap.visible(e).map { it.key })
        assertTrue("own files never go through the download plan", downloadPlan(snap, emptyList()).wanted.isEmpty())
    }

    // 21b §6: downloads retry with backoff, and at once when the url changes.

    @Test
    fun `a 4xx waits for a new url, a 5xx or the network retries`() {
        val lib = library(listOf(entry(1, cache = 1)))
        val failed = done(1, "k20", finished = false, failedUrl = "https://cdn.example.org/k20.mp3")
        assertTrue(downloadPlan(snapshot(lib, items(1, keys)), listOf(failed)).wanted.isEmpty())
        val renewed = failed.copy(failedUrl = "https://old.example.org/k20.mp3")
        assertEquals(listOf("k20"), downloadPlan(snapshot(lib, items(1, keys)), listOf(renewed)).wanted.map { it.item.key })
        assertTrue(permanentFailure("http_404"))
        assertTrue(permanentFailure("http_410"))
        assertTrue(!permanentFailure("http_503") && !permanentFailure("http_429") && !permanentFailure("network") && !permanentFailure("bad_media"))
    }

    @Test
    fun `the report counts have and want over the keep set`() {
        val lib = library(listOf(entry(1, cache = 3)))
        val snap = snapshot(lib, items(1, keys, gone = setOf("k19")))
        val state = downloadPlan(snap, listOf(done(1, "k20"))).states.single()
        assertEquals(DownloadState(1, "0123456789abcdef", 1, 2, null), state)
    }

    @Test
    fun `the list header says all or how many`() {
        assertEquals(OfflineSummary.All, offlineSummary(listOf("a", "b"), setOf("a", "b")))
        assertEquals(OfflineSummary.Some(1, 2), offlineSummary(listOf("a", "b"), setOf("a")))
    }

    // tests/offline_browse.py: what is on the phone plays at once, without the network.

    @Test
    fun `offline, a copy plays and a stream waits for the network`() {
        assertEquals(Playability.LOCAL, playability(hasCopy = true, hasUrl = true, caps = null, mobileData = false))
        assertEquals(Playability.NEEDS_NETWORK, playability(hasCopy = false, hasUrl = true, caps = null, mobileData = false))
        val wifi = NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
        assertEquals(Playability.STREAM, playability(hasCopy = false, hasUrl = true, caps = wifi, mobileData = false))
    }
}
