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

    private fun plan(snap: Snapshot, rows: List<DownloadRow> = emptyList(), at: Long = now, held: Set<ItemRef> = emptySet()) = downloadPlan(snap, rows, at, held)

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
        val rows = listOf(done(2, "k1"), done(99, "x"))
        assertEquals(listOf(99L), plan(snapshot(lib), rows).prune.map { it.entryId })
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

    // Design 30 step 3b (ours, not vibb's): the stream written as it plays, played extras, the parent's limit.

    private val gib = 1L shl 30
    private val gb = 1_000_000_000L
    private val mb = 1_000_000L

    private fun played(entryId: Long, key: String, at: Long, finished: Boolean = true) = done(entryId, key, finished = finished).copy(playedAt = at)

    private fun extra(key: String, bytes: Long, at: Long, entryId: Long = 1) = Extra(entryId, key, "e$entryId:$key", bytes, at)

    @Test
    fun `the sink writes only for NRK and RSS above depth 0, for an entry still there, above the free floor`() {
        assertEquals(1L shl 30, EXTRAS_FREE_FLOOR)
        assertTrue(throughWrites(through = true, depth = 5, freeBytes = 2 * gib))
        assertTrue("keep all, the floor exactly", throughWrites(true, -1, EXTRAS_FREE_FLOOR))
        assertFalse("an own file or a Storytel book (the tag's static part)", throughWrites(false, 5, 2 * gib))
        assertFalse("Ingen keeps nothing, so nothing is written", throughWrites(true, 0, 2 * gib))
        assertFalse("the entry left the library", throughWrites(true, null, 2 * gib))
        assertFalse("under the floor", throughWrites(true, 5, EXTRAS_FREE_FLOOR - 1))
    }

    @Test
    fun `a stale partial - bytes with no row, or a row from another source`() {
        val url = "https://cdn.example.org/k1.mp3?t=2"
        assertTrue("bytes and no row: an orphan", staleThrough(false, null, url, 1000))
        assertTrue("a row from another file", staleThrough(false, "https://cdn.example.org/other.mp3", url, 1000))
        assertFalse("the same source with another query", staleThrough(false, "https://cdn.example.org/k1.mp3?t=1", url, 1000))
        assertFalse("no bytes: nothing to mix with", staleThrough(false, null, url, 0))
        assertFalse("a done copy plays as it is", staleThrough(true, "https://cdn.example.org/other.mp3", url, 1000))
        assertFalse("no url: it can't stream", staleThrough(false, null, null, 1000))
    }

    @Test
    fun `a different total length at a resume is a changed source - unknown lengths are not`() {
        assertTrue(sourceChanged(1000, 206, "bytes 500-1199/1200", null, 500))
        assertFalse(sourceChanged(1000, 206, "bytes 500-999/1000", 500, 500))
        assertTrue("a 200 that ignored the Range: its Content-Length is the total", sourceChanged(1000, 200, null, 1200, 500))
        assertFalse(sourceChanged(1000, 200, null, 1000, 500))
        assertFalse("from 0 there is nothing to mix with", sourceChanged(1000, 200, null, 1200, 0))
        assertFalse("an unknown total", sourceChanged(1000, 206, "bytes 500-999/*", null, 500))
        assertFalse("no Content-Range", sourceChanged(1000, 206, null, 500, 500))
        assertFalse("a 206's Content-Length is the range's, not the total", sourceChanged(1000, 206, "bytes 500-999/1000", 500, 500))
        assertFalse("no stored length", sourceChanged(null, 206, "bytes 500-1199/1200", null, 500))
        assertFalse("another status", sourceChanged(1000, 416, "bytes */1200", null, 500))
    }

    @Test
    fun `the played time survives the job's row`() {
        assertEquals(5L, keepPlayedAt(5, null))
        assertEquals(9L, keepPlayedAt(5, 9))
        assertEquals(9L, keepPlayedAt(9, 5))
        assertEquals(7L, keepPlayedAt(null, 7))
        assertNull(keepPlayedAt(null, null))
        // The DAO: every whole-row write goes through it in one transaction; markPlayed only sets it, the
        // sink's row is an INSERT OR IGNORE (it never overwrites a partial download's row).
        val db = Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("@Transaction\n    suspend fun upsertDownload(row: DownloadRow) {\n        replaceDownload(row.copy(playedAt = keepPlayedAt(download(row.entryId, row.itemKey)?.playedAt, row.playedAt)))"))
        assertTrue(db.contains("@Query(\"UPDATE downloads SET playedAt = :at WHERE entryId = :entryId AND itemKey = :itemKey\")\n    suspend fun markPlayed("))
        assertTrue(db.contains("@Insert(onConflict = OnConflictStrategy.IGNORE)\n    fun insertThrough(row: DownloadRow): Long"))
        val writers = Fixtures.moduleFile("src/main/java").walk().filter { it.isFile && it.extension == "kt" && it.name != "MusicDb.kt" }
            .filter { it.readText().contains("replaceDownload(") }.map { it.name }.toList()
        assertTrue("whole rows go through upsertDownload only: $writers", writers.isEmpty())
    }

    @Test
    fun `the schema is exported - version 4 adds downloads playedAt by an auto migration`() {
        val v4 = Fixtures.moduleFile("schemas/me.vibb.music.data.MusicDb/4.json").readText()
        assertTrue(v4.contains("\"version\": 4"))
        assertTrue(v4.contains("`attempts` INTEGER NOT NULL, `playedAt` INTEGER, PRIMARY KEY(`entryId`, `itemKey`))"))
        val db = Fixtures.moduleFile("src/main/java/me/vibb/music/data/MusicDb.kt").readText()
        assertTrue(db.contains("AutoMigration(from = 3, to = 4),"))
        assertTrue("designs 39, 49/50 and r8 moved it on", db.contains("version = 7,"))
    }

    @Test
    fun `over the limit the least recently played extras go until under it`() {
        val extras = listOf(extra("a", 300, at = 3), extra("b", 300, at = 1), extra("c", 300, at = 2))
        val gone = extrasToEvict(extras, emptySet(), totalBytes = 2000, limitBytes = 1500, freeBytes = 10 * gib, floorBytes = EXTRAS_FREE_FLOOR)
        assertEquals("b first (played longest ago), then c: 1400 is under 1500", listOf("b", "c"), gone.map { it.itemKey })
        assertTrue("at the limit nothing goes", extrasToEvict(extras, emptySet(), 1500, 1500, 10 * gib, EXTRAS_FREE_FLOOR).isEmpty())
        // A stable order on a tie: entry id, then item key.
        val tie = listOf(extra("z", 100, at = 1, entryId = 2), extra("y", 100, at = 1, entryId = 1), extra("x", 100, at = 1, entryId = 2))
        assertEquals(listOf("y", "x", "z"), extrasToEvict(tie, emptySet(), 1000, 0, 10 * gib, 0).map { it.itemKey })
        assertEquals("decimal GB", 2 * gb, storageLimitBytes(2))
        assertNull("no limit", storageLimitBytes(null))
    }

    @Test
    fun `under the free floor extras go until it holds, or none is left`() {
        val extras = listOf(extra("a", 400 * mb, at = 1), extra("b", 400 * mb, at = 2), extra("c", 400 * mb, at = 3))
        val free = EXTRAS_FREE_FLOOR - 500 * mb
        assertEquals(listOf("a", "b"), extrasToEvict(extras, emptySet(), 1, 2 * gb, free, EXTRAS_FREE_FLOOR).map { it.itemKey })
        assertEquals("all of them, and the floor still doesn't hold", 3, extrasToEvict(extras, emptySet(), 1, 2 * gb, 0, EXTRAS_FREE_FLOOR).size)
        // Both rules at once: on until the cache is under the limit and the floor holds.
        assertEquals(listOf("a", "b", "c"), extrasToEvict(extras, emptySet(), 2 * gb + 1100 * mb, 2 * gb, free, EXTRAS_FREE_FLOOR).map { it.itemKey })
    }

    @Test
    fun `no limit - only the floor evicts`() {
        val extras = listOf(extra("a", gb, at = 1), extra("b", gb, at = 2))
        assertTrue("a 50 GB cache with room keeps its extras", extrasToEvict(extras, emptySet(), 50 * gb, null, 10 * gib, EXTRAS_FREE_FLOOR).isEmpty())
        assertEquals(listOf("a"), extrasToEvict(extras, emptySet(), 50 * gb, null, EXTRAS_FREE_FLOOR - 1, EXTRAS_FREE_FLOOR).map { it.itemKey })
    }

    @Test
    fun `the keep set and the held items are never evicted, even over the limit`() {
        val e = entry(1, cache = 2)
        // The keep set: the newest two (k20, k19), the bookmark (k5) and its next (k4, newest first) - all played,
        // and played longest ago, so they would be the first to go if they were extras (qa-30-s3b-code M1).
        val kept = listOf(played(1, "k20", at = 1), played(1, "k19", at = 2), played(1, "k5", at = 3), played(1, "k4", at = 4, finished = false))
        val rows = kept + listOf(played(1, "k10", at = 50), played(1, "k9", at = 60, finished = false))
        val snap = snapshot(library(listOf(e)), items(1, keys), downloads = rows, bookmarks = mapOf("e1" to ("k5" to 60_000L)))
        val p = plan(snap, rows)
        assertEquals(listOf("k10", "k9"), p.extras.map { it.itemKey })
        assertTrue("the keep set is never pruned either", p.prune.isEmpty())
        val extras = p.extras.map { Extra(it.entryId, it.itemKey, it.cacheKey, gb, it.playedAt!!) }
        // The kept items alone are 4 GB of a 1 GB limit: every extra that isn't held goes, nothing else.
        val gone = extrasToEvict(extras, setOf(ItemRef(1, "k9")), totalBytes = 6 * gb, limitBytes = gb, freeBytes = 10 * gib, floorBytes = EXTRAS_FREE_FLOOR)
        assertEquals(listOf("k10"), gone.map { it.itemKey })
    }

    @Test
    fun `the Storytel floor only while a book is wanted and the login is ok`() {
        assertEquals(STORYTEL_FREE_FLOOR, extrasFloor(wantsStorytel = true, loginOk = true))
        assertEquals("no login: the books can't download", EXTRAS_FREE_FLOOR, extrasFloor(wantsStorytel = true, loginOk = false))
        assertEquals(EXTRAS_FREE_FLOOR, extrasFloor(wantsStorytel = false, loginOk = true))
        val e = series()
        assertTrue(plan(snapshot(library(listOf(e)), items(7, books, gone = books.toSet()))).wantsStorytel)
        val all = books.map { book(7, it) }
        assertFalse("every book on the phone", plan(snapshot(library(listOf(e)), items(7, books, gone = books.toSet()), downloads = all), all).wantsStorytel)
        assertFalse(plan(snapshot(library(listOf(entry(1))), items(1, keys))).wantsStorytel)
    }

    @Test
    fun `orphans - keys of no row and of no row's HLS prefix, never a held one`() {
        val n1 = hlsKeyPrefix("e2:n1")
        val keys = setOf("e1:a", "e1:gone", "${n1}master.m3u8", "${n1}v1/seg1.ts", "hls:e2:n9/seg1.ts", "e3:held", "hls:e3:h2/seg1.ts")
        val orphans = orphanResources(keys, rowCacheKeys = setOf("e1:a", "e2:n1"), heldCacheKeys = setOf("e3:held", "e3:h2"))
        assertEquals(setOf("e1:gone", "hls:e2:n9/seg1.ts"), orphans)
        assertTrue(orphanResources(emptySet(), setOf("e1:a"), emptySet()).isEmpty())
    }

    // The plan (design 30 §1.6, §1.8)

    @Test
    fun `a played item outside the keep set is an extra, an unplayed one is pruned`() {
        val e = entry(1, cache = 2)
        val rows = listOf(
            played(1, "k20", at = 1), played(1, "k19", at = 2), // the keep set, played: never an extra (M1)
            played(1, "k10", at = now), done(1, "k9"),
            played(1, "k8", at = now, finished = false), done(1, "k7", finished = false),
        )
        val p = plan(snapshot(library(listOf(e)), items(1, keys), downloads = rows), rows)
        assertEquals("a played copy left behind by newer episodes, and a partial stream", listOf("k10", "k8"), p.extras.map { it.itemKey })
        assertEquals(setOf("k9", "k7"), p.prune.map { it.itemKey }.toSet())
        assertTrue("an extra is never fetched", p.wanted.isEmpty())
        assertEquals("have and want count the keep set only", DownloadState(1, "0123456789abcdef", 2, 2, null), p.states.single())
    }

    @Test
    fun `depth 0 has no extras - every row is pruned but the held ones`() {
        val e = entry(1, cache = 0)
        val rows = listOf(played(1, "k20", at = now), played(1, "k19", at = now, finished = false), done(1, "k5"))
        val p = plan(snapshot(library(listOf(e)), items(1, keys), downloads = rows), rows, held = setOf(ItemRef(1, "k19")))
        assertTrue(p.extras.isEmpty())
        assertEquals(setOf("k20", "k5"), p.prune.map { it.itemKey }.toSet())
        assertEquals("once the player lets go", setOf("k20", "k19", "k5"), plan(snapshot(library(listOf(e)), items(1, keys)), rows).prune.map { it.itemKey }.toSet())
    }

    @Test
    fun `the held set is the current item and the next - neither pruned, the progressive ones not wanted`() {
        assertEquals(setOf(ItemRef(1, "k3"), ItemRef(1, "k2")), heldItems(1, listOf("k4", "k3", "k2"), 1, 2))
        assertEquals("the last item has no next", setOf(ItemRef(1, "k2")), heldItems(1, listOf("k4", "k3", "k2"), 2, -1))
        assertTrue("nothing loaded", heldItems(1, emptyList(), 0, -1).isEmpty())
        // Shuffled: k3 (the bookmark, half streamed) plays, k12 (outside the keep set, preloaded) is next.
        val e = entry(1, cache = 2)
        val bookmarks = mapOf("e1" to ("k3" to 60_000L))
        val snap = snapshot(library(listOf(e)), items(1, keys), bookmarks = bookmarks)
        val rows = listOf(played(1, "k3", at = now, finished = false), done(1, "k2", finished = false), done(1, "k12", finished = false))
        val held = heldItems(1, listOf("k3", "k12"), 0, 1)
        val p = plan(snap, rows, held = held)
        assertTrue("held: never pruned", p.prune.isEmpty())
        assertEquals("k3 is busy: the downloader would wait on the player's lock", listOf("k20", "k19", "k2"), p.wanted.map { it.item.key })
        assertEquals("let go, k12 goes", listOf("k12"), plan(snap, rows).prune.map { it.itemKey })
        // HLS isn't held back: a segment's lock lasts one segment load.
        val hls = snapshot(library(listOf(e)), items(1, keys, hls = true), bookmarks = bookmarks)
        assertEquals(listOf("k3", "k20", "k19", "k2"), plan(hls, rows, held = held).wanted.map { it.item.key })
    }

    /** qa-30-s3b-code L5: ExoPlayer loads up to 50 s ahead, so with short items more than the next is open. */
    @Test
    fun `short items - the held set reaches every item that starts within the window ahead`() {
        val long = { _: Int -> 30 * 60_000L }
        assertEquals("long items: the current and the next", listOf(3, 4), heldAhead(3, 4, 10, remainingMs = 20 * 60_000L, durationMs = long))
        assertEquals("the current item's end is near: still the next only", listOf(3, 4), heldAhead(3, 4, 10, 10_000L, long))
        // 20 s clips, 10 s left of the current: the next starts in 10 s, then 30, 50, 70; 90 s is past the window.
        val clip = { _: Int -> 20_000L }
        assertEquals(listOf(3, 4, 5, 6, 7), heldAhead(3, 4, 10, 10_000L, clip))
        assertEquals("the queue's end", listOf(8, 9), heldAhead(8, 9, 10, 10_000L, clip))
        assertEquals("the last item", listOf(9), heldAhead(9, -1, 10, 10_000L, clip))
        assertEquals("an unknown length ends the window", listOf(3, 4, 5), heldAhead(3, 4, 10, 10_000L) { if (it == 5) null else 20_000L })
        assertEquals("an unknown position: the current and the next", listOf(3, 4), heldAhead(3, 4, 10, null, clip))
        assertTrue("nothing loaded", heldAhead(-1, -1, 0, null, clip).isEmpty())
        assertEquals(setOf(ItemRef(1, "a"), ItemRef(1, "c")), heldItems(1, listOf("a", "b", "c"), listOf(0, 2, 7)))
        assertEquals(75_000L, HELD_AHEAD_MS)
    }

    @Test
    fun `an item the listing dropped is pruned even when played`() {
        val rows = listOf(played(1, "old", at = now), played(1, "old2", at = now, finished = false))
        val p = plan(snapshot(library(listOf(entry(1, cache = 2))), items(1, keys)), rows)
        assertEquals(setOf("old", "old2"), p.prune.map { it.itemKey }.toSet())
        assertTrue(p.extras.isEmpty())
    }

    @Test
    fun `a removed entry takes its extras`() {
        val rows = listOf(played(9, "x", at = now), played(1, "k10", at = now))
        val snap = snapshot(library(listOf(entry(1, cache = 2))), items(1, keys))
        val p = plan(snap, rows)
        assertEquals(listOf(9L to "x"), p.prune.map { it.entryId to it.itemKey })
        assertEquals(listOf("k10"), p.extras.map { it.itemKey })
        assertTrue("held, it waits until the player lets go", plan(snap, rows, held = setOf(ItemRef(9, "x"))).prune.isEmpty())
    }

    @Test
    fun `an entry without a listing keeps its extras`() {
        val rows = listOf(played(1, "k10", at = now), done(1, "k1"))
        val p = plan(snapshot(library(listOf(entry(1, cache = 2))), emptyList(), listings = emptyMap()), rows)
        assertTrue(p.prune.isEmpty())
        assertTrue("nothing to evict from it either", p.extras.isEmpty())
    }

    @Test
    fun `a Storytel book is never an extra`() {
        val e = series(cache = 1)
        val rows = books.map { book(7, it).copy(playedAt = now) }
        val p = plan(snapshot(library(listOf(e)), items(7, books, gone = books.toSet()), downloads = rows), rows)
        assertTrue(p.extras.isEmpty())
        assertEquals(listOf("222", "333"), p.prune.map { it.itemKey })
    }

    @Test
    fun `a settled stream counts as have and isn't wanted`() {
        val e = entry(1, cache = 2)
        // k19 streamed to its end and settled: a done row like a download's, with its played time.
        val settled = played(1, "k19", at = now)
        val snap = snapshot(library(listOf(e)), items(1, keys, gone = setOf("k19")), downloads = listOf(settled))
        val p = plan(snap, listOf(settled))
        assertEquals(listOf("k20"), p.wanted.map { it.item.key })
        assertEquals(DownloadState(1, "0123456789abcdef", 1, 2, null), p.states.single())
        assertTrue("withdrawn since, it stays visible: it is on the phone", snap.visible(e).any { it.key == "k19" })
        assertEquals(Playability.LOCAL, playability(snap.hasCopy(1, "k19"), false, null, false))
    }
}
