package me.vibb.music.sync

import kotlinx.coroutines.runBlocking
import me.vibb.music.Fixtures.done
import me.vibb.music.Fixtures.entry
import me.vibb.music.Fixtures.items
import me.vibb.music.Fixtures.library
import me.vibb.music.Fixtures.snapshot
import me.vibb.music.data.DownloadRow
import me.vibb.music.logic.DownloadState
import me.vibb.music.logic.NO_AUDIO
import me.vibb.music.logic.RETRY_FIRST_MS
import me.vibb.music.logic.RETRY_PERMANENT_MS
import me.vibb.music.logic.STORYTEL_FREE_FLOOR
import me.vibb.music.logic.Waiting
import me.vibb.music.logic.reportWaiting
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.storytel.Mint
import me.vibb.music.storytel.StorytelSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Storytel downloads (design 26b §3.3, §4.3, §4.5): the plan and [DownloadRun]'s decisions with a fake
 * minter and a fake fetch. Ports vibb `tests/storytel_sync.py` 1, 1b, 2, 3, 5 (MIT, Copyright (c) 2026
 * palchrb) and the spec's rows (M4, M7, L7): the source id in the rows, a partial kept across mints, the
 * login failures that stop Storytel for the run without item errors, the 416 re-mint, the floor.
 */
class StorytelDownloadTest {
    private val books = listOf("111", "222", "333")
    private val now = 1_800_000_000_000L
    private val gb = 1_000_000_000L

    private fun series(id: Long = 7, cache: Int = -1, order: String = "auto") =
        entry(id, Source.STORYTEL, target = "storytel:series:26175", cache = cache, order = order)

    /** The listing: three books, no url (design 26b §2.4). */
    private fun listed(e: Entry = series()) = items(e.id, books, gone = books.toSet())

    private class FetchFailed(val code: String) : IOException("fetch failed")

    /** A scripted world: mints answer from [mints] (per cid, in turn; default a fresh fake URL), fetches from [fetches]. */
    private inner class Io(
        val mints: MutableMap<String, ArrayDeque<Mint>> = mutableMapOf(),
        val fetches: MutableMap<String, ArrayDeque<String?>> = mutableMapOf(),
        var free: Long = 10 * gb,
    ) : DownloadIo {
        val rows = mutableMapOf<Pair<Long, String>, DownloadRow>()
        val errors = mutableMapOf<Pair<Long, String>, String>()
        val removed = mutableListOf<String>()
        val minted = mutableListOf<String>()
        val fetched = mutableListOf<Pair<String, String>>()

        /** Each cache key's partial copy now, in bytes (the test moves it between attempts). */
        val cached = mutableMapOf<String, Long>()
        private var signatures = 0

        override suspend fun row(entryId: Long, itemKey: String) = rows[entryId to itemKey]
        override suspend fun saveRow(row: DownloadRow) {
            rows[row.entryId to row.itemKey] = row
        }
        override suspend fun removeCopy(cacheKey: String, hls: Boolean) {
            removed += cacheKey
        }
        override suspend fun fetch(w: WantedDownload, url: String): Pair<Long, Int?> {
            fetched += w.item.key to url
            val code = fetches[w.item.key]?.removeFirstOrNull()
            if (code != null) throw FetchFailed(code)
            return 5_000_000L to null
        }
        override fun errorCode(e: Exception) = (e as? FetchFailed)?.code ?: "network"
        override suspend fun recordError(entryId: Long, itemKey: String, code: String) {
            errors[entryId to itemKey] = code
        }
        override suspend fun clearError(entryId: Long, itemKey: String) {
            errors.remove(entryId to itemKey)
        }
        override fun nowMs() = now
        override fun freeBytes() = free
        override fun cachedBytes(cacheKey: String, hls: Boolean) = cached[cacheKey] ?: 0L
        override suspend fun mint(cid: String): Mint {
            minted += cid
            return mints[cid]?.removeFirstOrNull()
                ?: Mint.Url("http://10.0.2.2:8730/cdn/$cid.wav?consumableId=$cid&token=FAKE-SIG-${++signatures}")
        }
    }

    private fun Io.run(wanted: List<WantedDownload>): DownloadRun = DownloadRun(this).also { r -> runBlocking { wanted.forEach { r.fetchOne(it) } } }

    private fun wanted(e: Entry = series(), keys: List<String> = books) = keys.map { k -> WantedDownload(e, PlayItem(e.id, k, "Title $k", null, false, 600_000)) }

    // The plan (§3.3)

    @Test
    fun `a series is wanted in reading order with the bookmark's book first, and counted over the keep set`() {
        val e = series()
        val plan = downloadPlan(snapshot(library(listOf(e)), listed(e), bookmarks = mapOf("e7" to ("333" to 60_000L))), emptyList(), now)
        assertEquals(listOf("333", "111", "222"), plan.wanted.map { it.item.key })
        assertEquals(DownloadState(7, "0123456789abcdef", 0, 3, null), plan.states.single())
        val newestFirst = series(order = "newest_first")
        assertEquals("reading order whatever the play order", books, downloadPlan(snapshot(library(listOf(newestFirst)), listed(newestFirst)), emptyList(), now).wanted.map { it.item.key })
    }

    @Test
    fun `3 a re-sync skips the books on the phone, and the first N stops after N`() {
        val e = series(cache = 1)
        val plan = downloadPlan(snapshot(library(listOf(e)), listed(e)), emptyList(), now)
        assertEquals(listOf("111"), plan.wanted.map { it.item.key })
        val all = series()
        val have = listOf(done(7, "111", url = "storytel:book:111"), done(7, "333", url = "storytel:book:333"))
        val again = downloadPlan(snapshot(library(listOf(all)), listed(all), downloads = have), have, now)
        assertEquals(listOf("222"), again.wanted.map { it.item.key })
        assertEquals(DownloadState(7, "0123456789abcdef", 2, 3, null), again.states.single())
    }

    @Test
    fun `a failed book waits by Storytel's classes, keyed by its source id`() {
        val e = series()
        fun failedRow(code: String) = DownloadRow(7, "111", "e7:111", "storytel:book:111", false, false, 0, null, "storytel:book:111", code, now, 1)
        val cdn403 = downloadPlan(snapshot(library(listOf(e)), listed(e)), listOf(failedRow("http_403")), now)
        assertEquals("the others go on", listOf("222", "333"), cdn403.wanted.map { it.item.key })
        assertEquals("a CDN 403 (an expired token) heals on the next mint: 2 min", now + RETRY_FIRST_MS, cdn403.nextRetryAt)
        assertEquals(listOf("111", "222", "333"), downloadPlan(snapshot(library(listOf(e)), listed(e)), listOf(failedRow("http_403")), now + RETRY_FIRST_MS).wanted.map { it.item.key })
        assertEquals("no_audio waits a day", now + RETRY_PERMANENT_MS, downloadPlan(snapshot(library(listOf(e)), listed(e)), listOf(failedRow(NO_AUDIO)), now).nextRetryAt)
    }

    // The run (§4.3)

    @Test
    fun `1 and 2 every book attempted, one mint per attempt, the row names the book and never the URL`() {
        val io = Io()
        io.run(wanted())
        assertEquals("one mint per book", books, io.minted)
        assertEquals(books.map { it to "http://10.0.2.2:8730/cdn/$it.wav?consumableId=$it&token=FAKE-SIG-${books.indexOf(it) + 1}" }, io.fetched)
        for (k in books) {
            val row = io.rows.getValue(7L to k)
            assertTrue(row.done)
            assertEquals("storytel:book:$k", row.url)
            assertEquals("e7:$k", row.cacheKey)
        }
        assertTrue("no minted URL in a row", io.rows.values.none { it.url.contains("token=") || it.failedUrl?.contains("token=") == true })
        assertTrue(io.errors.isEmpty())
    }

    @Test
    fun `1b a book Storytel gives no audio gets no_audio and the next one downloads`() {
        val io = Io(mints = mutableMapOf("222" to ArrayDeque(listOf(Mint.NoAudio(403)))))
        val run = io.run(wanted())
        assertEquals("the locked flag isn't trusted: every book is minted", books, io.minted)
        assertEquals(NO_AUDIO, io.errors[7L to "222"])
        assertEquals(listOf("111", "333"), io.fetched.map { it.first })
        val row = io.rows.getValue(7L to "222")
        assertFalse(row.done)
        assertEquals("storytel:book:222", row.failedUrl)
        assertEquals(NO_AUDIO, row.failedCode)
        assertEquals(1, row.attempts)
        assertNull(run.storytelStopped)
    }

    @Test
    fun `the asset call's other answers - Failed is http_status, Unreachable is network, and the run goes on`() {
        val io = Io(mints = mutableMapOf("111" to ArrayDeque(listOf(Mint.Failed(503))), "222" to ArrayDeque(listOf(Mint.Unreachable("unreachable")))))
        val run = io.run(wanted())
        assertEquals("http_503", io.errors[7L to "111"])
        assertEquals("network", io.errors[7L to "222"])
        assertEquals(listOf("333"), io.fetched.map { it.first })
        assertNull(run.storytelStopped)
    }

    @Test
    fun `a session failure stops every Storytel book of the run with no item error - the other entries go on`() {
        val back = mapOf<Mint, Long?>(
            Mint.NotConfigured to null,
            Mint.Refused(now + 2_400_000) to now + 2_400_000,
            Mint.LoginFailed("unreachable", now + 120_000) to now + 120_000,
            Mint.LoginFailed("busy", now + 30_000) to now + 30_000,
            Mint.LoginFailed("key", now + 600_000) to now + 600_000,
        )
        for ((failure, comesBack) in back) {
            val io = Io(mints = mutableMapOf("111" to ArrayDeque(listOf(failure))))
            val rss = entry(1)
            val episode = PlayItem(1, "k1", "Episode", "https://cdn.example.org/k1.mp3", false, 600_000)
            val run = io.run(wanted() + WantedDownload(rss, episode))
            assertEquals("$failure: one mint, then nothing for Storytel", listOf("111"), io.minted)
            assertEquals("$failure", StorytelStop.LOGIN, run.storytelStopped)
            assertTrue("$failure: no item error (the report's waiting says it)", io.errors.isEmpty())
            assertTrue("$failure: no row either", io.rows.keys.none { it.first == 7L })
            assertEquals("the RSS episode still downloads", listOf("k1" to "https://cdn.example.org/k1.mp3"), io.fetched)
            assertEquals("https://cdn.example.org/k1.mp3", io.rows.getValue(1L to "k1").url)
            // design 58 §5.3 (QA 58-2): the worker comes back at the session's time, not at the 6 h backstop; Storytel
            // off waits for a new generation (a sync)
            assertEquals("$failure", comesBack, run.storytelBackAt)
        }
    }

    @Test
    fun `a 401 a fresh JWT got too - that book's http_401, and the rest wait for the next run (L4)`() {
        val io = Io(mints = mutableMapOf("111" to ArrayDeque(listOf(Mint.Failed(401)))))
        val run = io.run(wanted())
        assertEquals(listOf("111"), io.minted)
        assertEquals("http_401", io.errors[7L to "111"])
        assertEquals(StorytelStop.LOGIN, run.storytelStopped)
        assertTrue(io.fetched.isEmpty())
        assertEquals("design 58 §5.3 (QA N-1): a 401 stop comes back in 2 minutes", now + StorytelSession.COME_BACK_MS, run.storytelBackAt)
        assertEquals(120_000L, StorytelSession.COME_BACK_MS)
        val other = Io(mints = mutableMapOf("111" to ArrayDeque(listOf(Mint.Failed(503)))))
        assertNull("another failure is the item's own retry", other.run(wanted()).storytelBackAt)
    }

    @Test
    fun `5 below the 1_5 GB floor no Storytel book starts - nothing minted, no error - and the report says storage`() {
        val io = Io(free = STORYTEL_FREE_FLOOR - 1)
        val run = io.run(wanted())
        assertEquals(StorytelStop.STORAGE, run.storytelStopped)
        assertTrue(io.minted.isEmpty())
        assertTrue(io.fetched.isEmpty())
        assertTrue(io.errors.isEmpty())
        val states = reportWaiting(listOf(DownloadState(7, "v", 0, 3, null)), setOf(7L), null, "ok", STORYTEL_FREE_FLOOR - 1)
        assertEquals(Waiting.STORAGE, states.single().waiting)
    }

    @Test
    fun `the floor is checked before each book - the run stops when a book filled the space`() {
        var free = 10 * gb
        val inner = Io()
        val counting = object : DownloadIo by inner {
            override fun freeBytes(): Long = free.also { free -= 9 * gb }
        }
        val run = DownloadRun(counting)
        runBlocking { wanted().forEach { run.fetchOne(it) } }
        assertEquals(listOf("111"), inner.minted)
        assertEquals(StorytelStop.STORAGE, run.storytelStopped)
    }

    @Test
    fun `a dropped transfer keeps its partial, and the next attempt mints again and resumes the same bytes`() {
        val io = Io(fetches = mutableMapOf("111" to ArrayDeque(listOf("network"))))
        io.run(wanted(keys = listOf("111")))
        val partial = io.rows.getValue(7L to "111")
        assertFalse(partial.done)
        assertEquals("network", partial.failedCode)
        // the next run (a new job): a new token, the same source - the partial is not dropped
        io.run(wanted(keys = listOf("111")))
        assertEquals(listOf("111", "111"), io.minted)
        assertEquals(2, io.fetched.map { it.second }.distinct().size)
        assertTrue("never dropped: a re-mint is no new source", io.removed.isEmpty())
        assertTrue(io.rows.getValue(7L to "111").done)
        assertNull(io.errors[7L to "111"])
    }

    @Test
    fun `http_416 drops the partial and fetches once from 0 with a freshly minted URL (L7)`() {
        val io = Io(fetches = mutableMapOf("111" to ArrayDeque(listOf("http_416"))))
        io.run(wanted(keys = listOf("111")))
        assertEquals(listOf("e7:111"), io.removed)
        assertEquals("two mints", listOf("111", "111"), io.minted)
        assertEquals(listOf("FAKE-SIG-1", "FAKE-SIG-2"), io.fetched.map { it.second.substringAfter("token=") })
        assertTrue(io.rows.getValue(7L to "111").done)
    }

    @Test
    fun `http_416 whose re-mint fails records the mint's answer`() {
        val io = Io(
            mints = mutableMapOf("111" to ArrayDeque(listOf(Mint.Url("http://10.0.2.2:8730/cdn/111.wav?consumableId=111&token=FAKE-SIG-9"), Mint.NoAudio(404)))),
            fetches = mutableMapOf("111" to ArrayDeque(listOf("http_416"))),
        )
        io.run(wanted(keys = listOf("111")))
        assertEquals(NO_AUDIO, io.errors[7L to "111"])
        assertEquals(1, io.fetched.size)
    }

    @Test
    fun `a CDN 403 (an expired token) is a transient http_403 counted per source`() {
        val io = Io(fetches = mutableMapOf("111" to ArrayDeque(listOf("http_403", "http_403"))))
        io.run(wanted(keys = listOf("111")))
        io.run(wanted(keys = listOf("111")))
        val row = io.rows.getValue(7L to "111")
        assertEquals("http_403", row.failedCode)
        assertEquals("the same source and code count up, whatever the token", 2, row.attempts)
    }

    @Test
    fun `a slow but progressing download starts its count again - only failures that get nowhere double the wait`() {
        // The main session's review of step 3, Low 1: a 300 MB book over a weak Wi-Fi fails again and again,
        // each attempt further on; it must not back off towards 6 h while it progresses.
        val e = series()
        val io = Io(fetches = mutableMapOf("111" to ArrayDeque(listOf("network", "network", "network", "network"))))
        io.cached["e7:111"] = 40_000_000L
        io.run(wanted(keys = listOf("111")))
        var row = io.rows.getValue(7L to "111")
        assertEquals(1, row.attempts)
        assertEquals("the partial's size at the failure", 40_000_000L, row.bytes)
        io.cached["e7:111"] = 90_000_000L
        io.run(wanted(keys = listOf("111")))
        row = io.rows.getValue(7L to "111")
        assertEquals("it grew: the first failure again", 1, row.attempts)
        assertEquals(90_000_000L, row.bytes)
        assertEquals("so the plan tries it after 2 min", now + RETRY_FIRST_MS, downloadPlan(snapshot(library(listOf(e)), listed(e)), io.rows.values.toList(), now).nextRetryAt)
        // no progress: counted up, the wait doubles
        io.run(wanted(keys = listOf("111")))
        row = io.rows.getValue(7L to "111")
        assertEquals(2, row.attempts)
        assertEquals(now + 2 * RETRY_FIRST_MS, downloadPlan(snapshot(library(listOf(e)), listed(e)), io.rows.values.toList(), now).nextRetryAt)
        // a mint that fails gets no further either: counted (a different code starts at 1, then counts up)
        io.mints["111"] = ArrayDeque(listOf(Mint.Failed(503), Mint.Failed(503)))
        io.run(wanted(keys = listOf("111")))
        io.run(wanted(keys = listOf("111")))
        row = io.rows.getValue(7L to "111")
        assertEquals("http_503", row.failedCode)
        assertEquals(2, row.attempts)
        assertEquals("the partial is kept, its size too", 90_000_000L, row.bytes)
        assertEquals("the failed mints fetched nothing", listOf("network"), io.fetches.getValue("111").toList())
    }

    @Test
    fun `a host that drips a few KB and resets keeps backing off - growth counts from 1 MiB or 1 percent`() {
        // qa-26b-merge-code Low 2: any growth reset the count, so a trickle kept a book (and its mint) at 2 min.
        val e = series()
        val io = Io(fetches = mutableMapOf("111" to ArrayDeque(List(6) { "network" })))
        io.cached["e7:111"] = 40_000_000L
        io.run(wanted(keys = listOf("111")))
        for (step in 1..3) {
            io.cached["e7:111"] = 40_000_000L + step * 10_000L
            io.run(wanted(keys = listOf("111")))
        }
        var row = io.rows.getValue(7L to "111")
        assertEquals("three 10 KB drips: counted up", 4, row.attempts)
        assertEquals(now + 8 * RETRY_FIRST_MS, downloadPlan(snapshot(library(listOf(e)), listed(e)), io.rows.values.toList(), now).nextRetryAt)
        // a real resume (past 1 MiB, and past 1 % of the 40 MB partial) starts the count again
        io.cached["e7:111"] = 40_030_000L + 400_001L
        io.run(wanted(keys = listOf("111")))
        assertEquals("400 KB on 40 MB is under 1 MiB", 5, io.rows.getValue(7L to "111").attempts)
        io.cached["e7:111"] = 40_430_001L + (1L shl 20)
        io.run(wanted(keys = listOf("111")))
        row = io.rows.getValue(7L to "111")
        assertEquals(1, row.attempts)
        assertEquals(now + RETRY_FIRST_MS, downloadPlan(snapshot(library(listOf(e)), listed(e)), io.rows.values.toList(), now).nextRetryAt)
        // the floor itself: 1 MiB, or 1 % of a big partial
        assertFalse(grewEnough(0, 10_000))
        assertTrue(grewEnough(0, 1L shl 20))
        assertFalse("2 MB on a 300 MB partial is under 1 %", grewEnough(300_000_000L, 302_000_000L))
        assertTrue(grewEnough(300_000_000L, 303_000_000L))
        assertFalse("a dropped partial (smaller) never counts as growth", grewEnough(90_000_000L, 0))
    }

    @Test
    fun `NRK and RSS keep phase 1's rules - a partial from another url is dropped, the 416 refetches from the same url`() {
        val rss = entry(1)
        val item = PlayItem(1, "k1", "Episode", "https://cdn.example.org/new.mp3", false, 600_000)
        val io = Io(fetches = mutableMapOf("k1" to ArrayDeque(listOf("http_416"))))
        io.rows[1L to "k1"] = DownloadRow(1, "k1", "e1:k1", "https://cdn.example.org/old.mp3", false, false, 100, null, null)
        io.run(listOf(WantedDownload(rss, item)))
        assertEquals(listOf("e1:k1", "e1:k1"), io.removed)
        assertTrue("nothing minted for a feed", io.minted.isEmpty())
        assertEquals(listOf("k1" to "https://cdn.example.org/new.mp3", "k1" to "https://cdn.example.org/new.mp3"), io.fetched)
        assertTrue(io.rows.getValue(1L to "k1").done)
    }

    // Design 30 §1.6: a stream's partial, completed by the job.

    @Test
    fun `a changed source at a resume drops the partial and fetches from 0`() {
        // The length guard's synthetic 416 (MediaCache.LengthGuard: another total length at a resume) takes
        // the job's existing http_416 path: the stream's partial goes, and the item comes once more from 0.
        val rss = entry(1)
        val url = "https://cdn.example.org/k1.mp3"
        val item = PlayItem(1, "k1", "Episode", url, false, 600_000)
        val io = Io(fetches = mutableMapOf("k1" to ArrayDeque(listOf("http_416"))))
        io.rows[1L to "k1"] = DownloadRow(1, "k1", "e1:k1", url, false, false, 0, null, null, playedAt = now - 60_000)
        io.run(listOf(WantedDownload(rss, item)))
        assertEquals("the partial is the same source: kept until the 416, then dropped once", listOf("e1:k1"), io.removed)
        assertEquals(listOf("k1" to url, "k1" to url), io.fetched)
        assertTrue(io.rows.getValue(1L to "k1").done)
        assertNull(io.errors[1L to "k1"])
    }

    @Test
    fun `a partial from the same url with a new query is kept`() {
        // A podcast host's new tracking query, a re-resolved token (L5): the same source, so the stream's
        // bytes stay and the job fetches only what is missing (CacheWriter skips the cached ranges).
        val rss = entry(1)
        val item = PlayItem(1, "k1", "Episode", "https://cdn.example.org/k1.mp3?sid=2", false, 600_000)
        val io = Io(fetches = mutableMapOf("k1" to ArrayDeque(listOf("network"))))
        io.cached["e1:k1"] = 2_500_000
        io.rows[1L to "k1"] = DownloadRow(
            1, "k1", "e1:k1", "HTTPS://cdn.example.org/k1.mp3?sid=1", false, false, 2_000_000, null,
            "https://cdn.example.org/k1.mp3?sid=2", "network", now - 600_000, 1,
        )
        io.run(listOf(WantedDownload(rss, item)))
        assertTrue(io.removed.isEmpty())
        val row = io.rows.getValue(1L to "k1")
        assertEquals("the partial's size rode along (it grew by less than 1 MiB): the count goes on", 2, row.attempts)
        assertEquals(2_500_000L, row.bytes)
        assertEquals("the row names the source it fetches now", "https://cdn.example.org/k1.mp3?sid=2", row.url)
        // Another path is another file: dropped as before.
        val other = Io()
        other.rows[1L to "k1"] = DownloadRow(1, "k1", "e1:k1", "https://cdn.example.org/k1-old.mp3?sid=1", false, false, 0, null, null)
        other.run(listOf(WantedDownload(rss, item)))
        assertEquals(listOf("e1:k1"), other.removed)
    }

    // The report (§4.5, M4)

    @Test
    fun `the report - login while the account isn't ok, storage below the floor, else the shared rule`() {
        val states = listOf(DownloadState(7, "v", 1, 3, null), DownloadState(1, "v", 0, 2, null), DownloadState(8, "v", 2, 2, null))
        val storytel = setOf(7L, 8L)
        assertEquals(listOf(Waiting.LOGIN, Waiting.WIFI, null), reportWaiting(states, storytel, Waiting.WIFI, "none", 10 * gb).map { it.waiting })
        assertEquals(listOf(Waiting.LOGIN, null, null), reportWaiting(states, storytel, null, "refused", 10 * gb).map { it.waiting })
        assertEquals("between 0.5 and 1.5 GB", listOf(Waiting.STORAGE, null, null), reportWaiting(states, storytel, null, "ok", gb).map { it.waiting })
        assertEquals("an unreachable login reads as the shared rule says", listOf(Waiting.WIFI, Waiting.WIFI, null), reportWaiting(states, storytel, Waiting.WIFI, "ok", 10 * gb).map { it.waiting })
    }
}
