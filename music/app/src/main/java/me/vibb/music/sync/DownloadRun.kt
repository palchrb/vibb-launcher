package me.vibb.music.sync

import kotlinx.coroutines.CancellationException
import me.vibb.music.data.DownloadRow
import me.vibb.music.logic.itemCacheKey
import me.vibb.music.logic.mintItemError
import me.vibb.music.logic.mintStopsRun
import me.vibb.music.logic.sourceId
import me.vibb.music.logic.storytelRoom
import me.vibb.music.model.Source
import me.vibb.music.storytel.Mint

/*
 * One download run's decisions (design 21 §4.5, 21b §6; design 26b §4.3 for Storytel), the Storytel
 * part ported from palchrb/vibb `pi/vibb/storytel.py` (`sync`'s download loop: one book at a time,
 * the floor before each book, a fresh URL every attempt, a genuine failure skips only that book, a
 * login failure stops the run; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under
 * the GPL-3.0 (or later) - see NOTICE.md. Android-free: the worker gives it [DownloadIo];
 * StorytelDownloadTest drives it with fakes.
 */

/** What a Storytel run stopped for (design 26b §4.3): the login (no item error) or the free-space floor. */
enum class StorytelStop { LOGIN, STORAGE }

/** The run's side of the world: rows, the cache, the fetch, the item errors, the clocks, the mint. */
interface DownloadIo {
    suspend fun row(entryId: Long, itemKey: String): DownloadRow?

    suspend fun saveRow(row: DownloadRow)

    /** Drop an item's (partial) audio from the cache. */
    fun removeCopy(cacheKey: String, hls: Boolean)

    /** Fetch [w] from [url] into the cache: (bytes cached, the HLS variant fetched). Throws on failure. */
    suspend fun fetch(w: WantedDownload, url: String): Pair<Long, Int?>

    /** The `item_errors` code of a failed fetch. */
    fun errorCode(e: Exception): String

    suspend fun recordError(entryId: Long, itemKey: String, code: String)

    suspend fun clearError(entryId: Long, itemKey: String)

    fun nowMs(): Long

    /** Free bytes where the cache lives (noBackupFilesDir). */
    fun freeBytes(): Long

    /** The bytes of an item's (partial) copy in the cache now. */
    fun cachedBytes(cacheKey: String, hls: Boolean): Long

    /** A fresh CDN URL for Storytel book [cid]: the account's one mint, off the main thread (design 26b §4.2). */
    suspend fun mint(cid: String): Mint
}

/**
 * The decisions of one run of the download worker, item by item ([fetchOne]). An NRK/RSS item is
 * fetched from its url; a partial copy fetched from another url is dropped first (qa-21-step2-code L7),
 * an `http_416` drops the partial and fetches once more from 0. A Storytel book (design 26b §4.3):
 * 1. below the 1.5 GB floor no Storytel book starts this run ([StorytelStop.STORAGE]);
 * 2. a URL minted for this attempt (vibb: single-use, short-lived; the path is stable). A login
 *    failure (`NotConfigured`, `Refused`, `LoginFailed`, or a 401 a fresh JWT got too) stops every
 *    Storytel book of the run ([StorytelStop.LOGIN]) - with no item error, but the 401's `http_401`
 *    on its book; `NoAudio` is `no_audio` for that book, `Failed` `http_<status>`, `Unreachable`
 *    `network`, and the next book goes on;
 * 3. the row names the book by its source id (`storytel:book:<cid>`), so a partial copy survives a
 *    re-mint and the next attempt resumes with Range from the cached bytes against the new token;
 * 4. `http_416` drops the partial and fetches once from 0 with a freshly minted URL (L7).
 * A failure is stored on the row (source id, code, time, attempts in a row) for `mayRetry`; a not-done
 * row's `bytes` hold the partial copy's size at its last failure, and a failure after the copy grew by
 * at least [GROWTH_FLOOR_BYTES] or 1 % ([grewEnough]) counts as the first again (main session's review of step 3,
 * Low 1: a slow but progressing download - a 300 MB book over a weak Wi-Fi - must not back off towards 6 h
 * while it keeps getting further; only failures that get nowhere double the wait). The floor keeps a host
 * that drips a few KB and resets from holding an item - and for Storytel a mint - at the 2-minute wait
 * (qa-26b-merge-code Low 2).
 */
class DownloadRun(private val io: DownloadIo) {
    /** Set once Storytel books stopped for this run; the run's other items go on. */
    var storytelStopped: StorytelStop? = null
        private set

    /** When a refused login's cooldown ends (wall clock), so the worker can come back then. */
    var storytelRefusedUntil: Long? = null
        private set

    /** Where one attempt's URL came from. */
    private sealed interface From {
        class Url(val url: String) : From
        class Failed(val code: String) : From
        /** The login failed: Storytel stops for the run; [code] for the book that got it (a 401), else none. */
        class Stop(val code: String?) : From
    }

    /** Fetch [w] into the cache, or record why not. */
    suspend fun fetchOne(w: WantedDownload) {
        val storytel = w.entry.source == Source.STORYTEL
        if (storytel) {
            if (storytelStopped != null) return
            if (!storytelRoom(io.freeBytes())) {
                storytelStopped = StorytelStop.STORAGE
                return
            }
        }
        val source = sourceId(w.entry, w.item) ?: return
        var from = urlFor(w)
        if (from !is From.Url) return failed(w, source, from)
        val cacheKey = itemCacheKey(w.entry.key, w.item.key)
        val old = io.row(w.entry.id, w.item.key)
        // A partial copy from another source may be another file: start over (qa-21-step2-code L7). A
        // Storytel book's source never changes with a new mint, so its partial stays (design 26b §4.3).
        if (old != null && !old.done && old.url != source) io.removeCopy(old.cacheKey, old.hls)
        // The partial's size at the last failure rides along (fail() compares it); a dropped one is 0.
        val lastBytes = old?.takeIf { !it.done && it.url == source }?.bytes ?: 0
        io.saveRow(DownloadRow(w.entry.id, w.item.key, cacheKey, source, w.item.hls, false, lastBytes, null, old?.failedUrl, old?.failedCode, old?.failedAt, old?.attempts ?: 0))
        var code = fetchFrom(w, from.url, source)
        if (code == "http_416") {
            // The file changed under a partial copy: drop it and fetch from the start, once - for a
            // Storytel book with a fresh URL (its token is single-use, vibb storytel.py 799-801).
            io.removeCopy(cacheKey, w.item.hls)
            from = if (storytel) urlFor(w) else from
            if (from !is From.Url) return failed(w, source, from)
            code = fetchFrom(w, from.url, source)
        }
        if (code == null) {
            io.clearError(w.entry.id, w.item.key)
            return
        }
        fail(w, source, code)
    }

    /** The URL of this attempt: the item's own, or for a Storytel book a fresh mint. */
    private suspend fun urlFor(w: WantedDownload): From {
        if (w.entry.source != Source.STORYTEL) return w.item.url?.let { From.Url(it) } ?: From.Failed("network")
        val mint = io.mint(w.item.key)
        if (mint is Mint.Url) return From.Url(mint.url)
        if (mint is Mint.Refused) storytelRefusedUntil = mint.untilWallMs
        val code = mintItemError(mint)
        return if (mintStopsRun(mint) || code == null) From.Stop(code) else From.Failed(code)
    }

    private suspend fun failed(w: WantedDownload, source: String, from: From) {
        when (from) {
            is From.Url -> Unit
            is From.Failed -> fail(w, source, from.code)
            is From.Stop -> {
                storytelStopped = StorytelStop.LOGIN
                from.code?.let { fail(w, source, it) }
            }
        }
    }

    /** One fetch: null when the item is on the phone now (its row done), else the failure's code. */
    private suspend fun fetchFrom(w: WantedDownload, url: String, source: String): String? = try {
        val (bytes, variant) = io.fetch(w, url)
        io.saveRow(DownloadRow(w.entry.id, w.item.key, itemCacheKey(w.entry.key, w.item.key), source, w.item.hls, true, bytes, variant, null))
        null
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        io.errorCode(e)
    }

    /**
     * The item error, and the row's failure (`mayRetry`: the same source and code count up - unless the
     * partial copy grew since the last failure, which starts the count again; Low 1).
     */
    private suspend fun fail(w: WantedDownload, source: String, code: String) {
        io.recordError(w.entry.id, w.item.key, code)
        val row = io.row(w.entry.id, w.item.key)
            ?: DownloadRow(w.entry.id, w.item.key, itemCacheKey(w.entry.key, w.item.key), source, w.item.hls, false, 0, null, null)
        val cached = io.cachedBytes(row.cacheKey, row.hls)
        val grew = grewEnough(row.bytes, cached)
        val attempts = if (row.failedUrl == source && row.failedCode == code && !grew) row.attempts + 1 else 1
        io.saveRow(row.copy(bytes = cached, failedUrl = source, failedCode = code, failedAt = io.nowMs(), attempts = attempts))
    }
}

/** The least a partial must grow between two failures to start their count again: 1 MiB or 1 % of it. */
const val GROWTH_FLOOR_BYTES = 1L shl 20

/** Did a partial copy of [before] bytes at the last failure grow enough by [now] ([GROWTH_FLOOR_BYTES], or 1 %)? */
fun grewEnough(before: Long, now: Long): Boolean = now - before >= maxOf(GROWTH_FLOOR_BYTES, before / 100)
