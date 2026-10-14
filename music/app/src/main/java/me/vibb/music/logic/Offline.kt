package me.vibb.music.logic

import me.vibb.music.model.Entry

/*
 * What stays on the phone (design 21 §4.5, 21b §6), ported from palchrb/vibb `pi/vibb/content.py`
 * (`sync`, `sync_feed`, `prune_cache`; MIT License, Copyright (c) 2026 palchrb), reimplemented in
 * Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Pure; OfflineTest ports vibb's
 * tests/offline_keep_all.py and cache_prune.py.
 *
 * Keep the newest N of the server's list (the last N, oldest first) or all for -1, plus the
 * bookmarked item and the next one in play order. Depth 0 ("Ingen") keeps nothing: everything
 * streams, the item in progress too (the user's decision of 2026-10-09, as vibb's cache 0). A kept
 * item whose `url` became null keeps its copy and plays it; items outside the keep set are pruned
 * whether or not their url is null (or a daily feed would grow without bound); at a depth above 0 the
 * bookmarked item is never deleted. Prune runs only on a parsed listing (QA #3): an entry whose
 * listing isn't there keeps what it has.
 *
 * Ours, not vibb's (design 30 §1.4-§1.8, §1.12: vibb caches no stream and has no storage limit): a
 * stream is written to the cache as it plays above depth 0 ([throughWrites]), a played item outside
 * the keep set stays as a played extra, evicted least recently played first when the cache is over
 * the parent's limit or free space under the floor ([extrasToEvict], [extrasFloor],
 * [storageLimitBytes]), and the helpers around it: [staleThrough], [sourceChanged], [keepPlayedAt],
 * [orphanResources], [heldItems].
 */

/**
 * One listed item as keep/prune sees it: whether it has a url (visibility, [visibleKeys]) and whether
 * it can be downloaded ([fetchable]: a url, or a Storytel book whose url is minted per attempt; design
 * 26b §3.3 - visibility is not fetchability). A Spotify item is never fetchable and its entry's
 * cache is always 0 (design 26c §4.2): the plan, the keep set and the prune never see one.
 */
data class KeepItem(val key: String, val hasUrl: Boolean, val fetchable: Boolean = hasUrl)

/**
 * The keys [entry] keeps: the newest [Entry.cache] of [oldestFirst], plus [bookmarkKey] and the
 * item after it in play order (wrapping, as the rotated queue does); nothing at depth 0.
 */
fun keepSet(entry: Entry, oldestFirst: List<String>, bookmarkKey: String?): Set<String> {
    val keep = linkedSetOf<String>()
    when {
        entry.cache == 0 -> return keep
        entry.cache < 0 -> keep += oldestFirst
        else -> keep += oldestFirst.takeLast(entry.cache)
    }
    if (bookmarkKey != null && bookmarkKey in oldestFirst) {
        keep += bookmarkKey
        val order = playOrder(entry, oldestFirst)
        if (order.size > 1) keep += order[(order.indexOf(bookmarkKey) + 1) % order.size]
    }
    return keep
}

/** Items to fetch: kept, fetchable, not on the phone yet. The bookmarked item first, then the
 * newest first, as vibb's `sync` downloads (`reversed(wanted)`). [items] is oldest first. (A
 * Storytel entry downloads in reading order instead: `storytelWanted`.) */
fun wantedDownloads(keep: Set<String>, items: List<KeepItem>, have: Set<String>, bookmarkKey: String?): List<String> {
    val fetchable = items.filter { it.fetchable }.map { it.key }.toSet()
    val wanted = items.asReversed().map { it.key }.filter { it in keep && it in fetchable && it !in have }
    // The item in progress first: it is the one the kid plays next.
    return if (bookmarkKey != null && bookmarkKey in wanted) listOf(bookmarkKey) + (wanted - bookmarkKey) else wanted
}

/** Copies to delete for one entry with a parsed listing: everything on the phone outside [keep]
 * (the bookmark is in it, except at depth 0). */
fun prunable(have: Set<String>, keep: Set<String>): Set<String> = have.filterTo(linkedSetOf()) { it !in keep }

/** A 4xx (not 408/429) says the url itself is wrong: tried again after a day, then daily. */
const val RETRY_PERMANENT_MS = 24 * 3_600_000L

/** Other failures wait 2 min, doubling, at most 6 h - per item, so one bad file never holds back the rest. */
const val RETRY_FIRST_MS = 2 * 60_000L
const val RETRY_MAX_MS = 6 * 3_600_000L

/** Is [code] the url's own fault (an HTTP 4xx other than 408 and 429)? */
fun permanentFailure(code: String?): Boolean {
    val status = code?.removePrefix("http_")?.toIntOrNull() ?: return false
    return status in 400..499 && status != 408 && status != 429
}

/** When an item that failed [attempts] times in a row, last with [code], may be tried again (from the failure). */
fun retryDelayMs(code: String?, attempts: Int): Long =
    if (permanentFailure(code)) RETRY_PERMANENT_MS else (RETRY_FIRST_MS shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(RETRY_MAX_MS)

/**
 * May an item be fetched from [url] at [nowMs]? Always when it never failed or its url changed
 * since (21b §6: "at once when the url changes"); else after [delayMs] from its last failure - phase
 * 1's [retryDelayMs], or for a Storytel book `storytelRetryDelayMs` (design 26b §3.3: an `http_403` from
 * an expired token heals on the next mint; pass `retryDelayFor(entry, ...)`).
 */
fun mayRetry(
    failedUrl: String?,
    failedCode: String?,
    failedAt: Long?,
    attempts: Int,
    url: String,
    nowMs: Long,
    delayMs: (String?, Int) -> Long = ::retryDelayMs,
): Boolean = failedUrl == null || failedUrl != url || failedAt == null || nowMs >= failedAt + delayMs(failedCode, attempts)

/**
 * Which entries' copies to drop entirely (`prune_cache`): every entry on the phone that the parsed
 * library no longer has. An empty library clears them all; an unparsed one ([libraryIds] null)
 * deletes nothing.
 */
fun removedEntries(onPhone: Set<Long>, libraryIds: Set<Long>?): Set<Long> =
    if (libraryIds == null) emptySet() else onPhone.filterTo(linkedSetOf()) { it !in libraryIds }

/** The list header: all of [visible] on the phone, or how many. */
sealed interface OfflineSummary {
    data object All : OfflineSummary
    data class Some(val have: Int, val of: Int) : OfflineSummary
}

fun offlineSummary(visible: Collection<String>, onPhone: Set<String>): OfflineSummary {
    val have = visible.count { it in onPhone }
    return if (have == visible.size) OfflineSummary.All else OfflineSummary.Some(have, visible.size)
}

/**
 * The items shown and played (21b §6): a `url: null` item without a copy is hidden; everything
 * else stays (a withdrawn episode with a copy keeps playing).
 */
fun visibleKeys(items: List<KeepItem>, onPhone: Set<String>): List<String> =
    items.filter { it.hasUrl || it.key in onPhone }.map { it.key }

// --- Design 30 step 3b: the stream written as it plays, played extras, the parent's limit (ours) ---

/** Free space a stream's write and the played extras leave where the cache lives (design 30 §1.4, §1.8). */
const val EXTRAS_FREE_FLOOR = 1L shl 30

/** The parent's storage limit in bytes (decimal GB, design 30 §1.8); null = no limit. */
fun storageLimitBytes(gb: Int?): Long? = gb?.let { it * 1_000_000_000L }

/**
 * Does a stream write the cache at this open (design 30 §1.4)? Only for an NRK or RSS item ([through],
 * the static part: never an own file or a Storytel book), whose entry is still in the library ([depth]
 * null = gone) above depth 0 - "Ingen" keeps nothing, so nothing is written - with at least
 * [EXTRAS_FREE_FLOOR] free now.
 */
fun throughWrites(through: Boolean, depth: Int?, freeBytes: Long): Boolean =
    through && depth != null && depth != 0 && freeBytes >= EXTRAS_FREE_FLOOR

/**
 * Bytes a new queue must not stream through (design 30 §1.6): an item without a done copy whose cache
 * holds bytes ([cachedBytes]) with no row (an orphan) or with a row from another source than [itemUrl]
 * ([sameSource]). It plays uncached; its row goes. An item without a url can't stream: never.
 */
fun staleThrough(done: Boolean, rowSource: String?, itemUrl: String?, cachedBytes: Long): Boolean = when {
    done || cachedBytes <= 0 || itemUrl == null -> false
    rowSource == null -> true
    else -> !sameSource(rowSource, itemUrl)
}

/**
 * Did the source change under a partial copy (design 30 §1.6, the length guard)? A response to a request
 * at [position] > 0 whose total length - `Content-Range`'s after the `/` for a 206, the [contentLength]
 * of a 200 - differs from the copy's [storedLength]. Unknown on either side is no change.
 */
fun sourceChanged(storedLength: Long?, status: Int, contentRange: String?, contentLength: Long?, position: Long): Boolean {
    if (position <= 0 || storedLength == null || storedLength < 0) return false
    val total = when (status) {
        206 -> contentRange?.substringAfterLast('/', "")?.trim()?.toLongOrNull()
        200 -> contentLength
        else -> null
    } ?: return false
    return total >= 0 && total != storedLength
}

/** A download row's played time across a rewrite (design 30 §1.5): the later one that is set. */
fun keepPlayedAt(stored: Long?, incoming: Long?): Long? = listOfNotNull(stored, incoming).maxOrNull()

/** The items the phone's player may have a period open for (design 30 §1.6): [current] and [next] of [keys]. */
fun heldItems(entryId: Long, keys: List<String>, current: Int, next: Int): Set<ItemRef> = heldItems(entryId, keys, listOf(current, next))

/** The items at [indices] of [keys] (-1 or out of range: none). */
fun heldItems(entryId: Long, keys: List<String>, indices: List<Int>): Set<ItemRef> =
    indices.mapNotNull { keys.getOrNull(it) }.mapTo(linkedSetOf()) { ItemRef(entryId, it) }

/**
 * How far ahead of the playing position the held set reaches (qa-30-s3b-code L5): ExoPlayer loads up to 50 s
 * ahead (`DefaultLoadControl`), and the set is computed again at least every 15 s while playing (the save
 * tick), so 75 s covers every period it can have opened since.
 */
const val HELD_AHEAD_MS = 75_000L

/**
 * The queue indices the phone's player may have a period open for: [current], [next], and every item after
 * [next] that starts within [HELD_AHEAD_MS] of the position - with short items (news clips, trailers)
 * ExoPlayer opens more than the next. [remainingMs]: what is left of the current item; [durationMs] of an
 * index. An unknown length ends the window there (the current item and the next stay held regardless).
 */
fun heldAhead(current: Int, next: Int, count: Int, remainingMs: Long?, durationMs: (Int) -> Long?): List<Int> {
    if (current < 0) return emptyList()
    if (next !in 0 until count) return listOf(current)
    val held = mutableListOf(current, next)
    var startsIn = (remainingMs ?: return held) + (durationMs(next) ?: return held)
    var i = next + 1
    while (i < count && i != current && startsIn < HELD_AHEAD_MS) {
        held += i
        startsIn += durationMs(i) ?: break
        i++
    }
    return held
}

/** A played extra as eviction sees it: its cache key, its bytes now and when the phone last started it. */
data class Extra(val entryId: Long, val itemKey: String, val cacheKey: String, val bytes: Long, val playedAt: Long)

/**
 * The extras to evict (design 30 §1.8): the ones not [held], least recently played first, while the
 * cache ([totalBytes]) is over [limitBytes] (null = no limit) or [freeBytes] is under [floorBytes] -
 * each one lowers the first and raises the second by its bytes. Stops when both hold or none is left.
 * The keep set is never an extra, so it never goes.
 */
fun extrasToEvict(extras: List<Extra>, held: Set<ItemRef>, totalBytes: Long, limitBytes: Long?, freeBytes: Long, floorBytes: Long): List<Extra> {
    val candidates = extras.filter { ItemRef(it.entryId, it.itemKey) !in held }
        .sortedWith(compareBy<Extra>({ it.playedAt }, { it.entryId }, { it.itemKey }))
    var total = totalBytes
    var free = freeBytes
    val evict = mutableListOf<Extra>()
    for (extra in candidates) {
        if ((limitBytes == null || total <= limitBytes) && free >= floorBytes) break
        evict += extra
        total -= extra.bytes
        free += extra.bytes
    }
    return evict
}

/** The free space eviction keeps (design 30 §1.8, L2): Storytel's while a book is wanted and the login works. */
fun extrasFloor(wantsStorytel: Boolean, loginOk: Boolean): Long =
    if (wantsStorytel && loginOk) STORYTEL_FREE_FLOOR else EXTRAS_FREE_FLOOR

/**
 * Cache resources no download row owns (design 30 §1.8, M1's backstop): neither a row's cache key nor
 * under a row's [hlsKeyPrefix] - and not an item the player holds ([heldCacheKeys]).
 */
fun orphanResources(resourceKeys: Set<String>, rowCacheKeys: Set<String>, heldCacheKeys: Set<String>): Set<String> =
    resourceKeys.filterTo(linkedSetOf()) { itemOfResource(it, rowCacheKeys) == null && itemOfResource(it, heldCacheKeys) == null }
