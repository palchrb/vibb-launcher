package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source
import me.vibb.music.sonos.normUri
import me.vibb.music.sonos.pyUnquote
import me.vibb.music.storytel.Mint

/*
 * Storytel's rules on the phone, ported from palchrb/vibb `pi/vibb/storytel.py` (`TARGET_RE`,
 * `is_storytel`, `parse_target`, `series_target`, `book_target`, `sync`'s keep and download order,
 * `DISK_FLOOR`), `pi/vibb/content.py` (`expand_entries`, `cache_key_for`) and `pi/daemon.py`
 * (`_sonos_known_duration`, the adopt's `consumableId` match; MIT License, Copyright (c) 2026
 * palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 26b §3,
 * §4.3, §4.5, §5.3, §5.4, §5.7. Pure; StorytelRulesTest.
 *
 * A Storytel book is listed with `url: null` (a signed URL expires, so it is never listed): it is
 * minted per download attempt and per Sonos play, and it plays on the phone only from its copy. Its
 * marker `storytel:book:<cid>` stands in for the URL wherever one would be stored.
 */

/** `TARGET_RE`. */
private val TARGET = Regex("^storytel:(series|book):([A-Za-z0-9_-]+)$")

enum class StorytelKind { SERIES, BOOK }

/** A parsed Storytel target: a series or one standalone book, by Storytel's id. */
data class StorytelTarget(val kind: StorytelKind, val id: String)

/** `is_storytel`: anything Storytel-shaped, valid or not. */
fun isStorytel(target: String?): Boolean = target?.startsWith("storytel:") == true

/** `parse_target`: `storytel:series:<id>` or `storytel:book:<id>`, nothing else. */
fun storytelTarget(target: String?): StorytelTarget? {
    val m = TARGET.matchEntire(target ?: return null) ?: return null
    return StorytelTarget(if (m.groupValues[1] == "series") StorytelKind.SERIES else StorytelKind.BOOK, m.groupValues[2])
}

/** `series_target`. */
fun seriesTarget(seriesId: String): String = "storytel:series:$seriesId"

/** `book_target`: also the marker that stands in for a book's signed URL. */
fun bookTarget(consumableId: String): String = "storytel:book:$consumableId"

/** Can [item] of [entry] be downloaded? With a `url`, or when Storytel mints one (design 26b §3.3). */
fun fetchable(entry: Entry, item: PlayItem): Boolean = item.url != null || entry.source == Source.STORYTEL

/**
 * The books a Storytel entry keeps (vibb `sync`: `books[:count]`): the first [Entry.cache] of the
 * reading order ([readingOrder], the listing's order whatever the entry's play order), all for -1,
 * plus [bookmarkKey] and the book after it in play order (phase 1's rule for every entry); nothing at
 * depth 0 (the parser reads a Storytel 0 as -1, so only a hand-made entry gets here).
 */
fun storytelKeepSet(entry: Entry, readingOrder: List<String>, bookmarkKey: String?): Set<String> {
    val keep = linkedSetOf<String>()
    when {
        entry.cache == 0 -> return keep
        entry.cache < 0 -> keep += readingOrder
        else -> keep += readingOrder.take(entry.cache)
    }
    if (bookmarkKey != null && bookmarkKey in readingOrder) {
        keep += bookmarkKey
        val order = playOrder(entry, readingOrder)
        if (order.size > 1) keep += order[(order.indexOf(bookmarkKey) + 1) % order.size]
    }
    return keep
}

/**
 * The books to fetch, in order: kept and not on the phone, the bookmarked book first (the one the kid
 * plays next), then reading order (vibb `for b in books`), never newest first.
 */
fun storytelWanted(keep: Set<String>, readingOrder: List<String>, have: Set<String>, bookmarkKey: String?): List<String> {
    val wanted = readingOrder.filter { it in keep && it !in have }
    return if (bookmarkKey != null && bookmarkKey in wanted) listOf(bookmarkKey) + (wanted - bookmarkKey) else wanted
}

/**
 * Where [item] comes from, as the download rows and the retry rule name it: its url, or for a
 * Storytel book the marker `storytel:book:<cid>` (design 26b §3.3) - a URL minted per attempt must
 * never read as "a new url", or every attempt would discard the partial copy.
 */
fun sourceId(entry: Entry, item: PlayItem): String? = if (entry.source == Source.STORYTEL) bookTarget(item.key) else item.url

/** The item error of a mint that got no audio for the book (locked, not in this country, gone). */
const val NO_AUDIO = "no_audio"

/** Is a Storytel book's failure [code] permanent (QA M7)? Only `no_audio`: every attempt mints a fresh URL, so an `http_<code>` heals. */
fun storytelPermanentFailure(code: String?): Boolean = code == NO_AUDIO

/** A Storytel book's retry delay: a day for `no_audio`, else 2 min doubling, at most 6 h (phase 1's numbers). */
fun storytelRetryDelayMs(code: String?, attempts: Int): Long =
    if (storytelPermanentFailure(code)) RETRY_PERMANENT_MS else (RETRY_FIRST_MS shl (attempts - 1).coerceIn(0, 20)).coerceAtMost(RETRY_MAX_MS)

/** [entry]'s retry delay: Storytel's classes for a Storytel book, phase 1's ([retryDelayMs]) for every other item. */
fun retryDelayFor(entry: Entry, code: String?, attempts: Int): Long =
    if (entry.source == Source.STORYTEL) storytelRetryDelayMs(code, attempts) else retryDelayMs(code, attempts)

/** The item error a download attempt reports for [mint] (design 26b §4.3), null when it is none of the book's. */
fun mintItemError(mint: Mint): String? = when (mint) {
    is Mint.NoAudio -> NO_AUDIO
    is Mint.Failed -> itemErrorCode(mint.status, badMedia = false)
    is Mint.Unreachable -> "network"
    is Mint.Url, Mint.NotConfigured, is Mint.Refused, is Mint.LoginFailed -> null
}

/** Does [mint] stop every Storytel book of the run (vibb `sync` stops on a login failure)? No item error then. */
fun mintStopsRun(mint: Mint): Boolean = mint is Mint.NotConfigured || mint is Mint.Refused || mint is Mint.LoginFailed

/** `DISK_FLOOR`: no Storytel book starts below this much free space (vibb's own floor, Storytel's only). */
const val STORYTEL_FREE_FLOOR = 1_500_000_000L

/** May a Storytel book start with [freeBytes] free? */
fun storytelRoom(freeBytes: Long): Boolean = freeBytes >= STORYTEL_FREE_FLOOR

/**
 * What a Storytel entry with books still wanted waits for (design 26b §4.5, QA M4): `login` while the
 * account isn't `ok` (no login, or refused and waiting out the cooldown), `storage` below the floor,
 * else [shared] (the rule every entry gets; an unreachable login is a network matter and reads so).
 */
fun storytelWaiting(account: String, freeBytes: Long, shared: Waiting?): Waiting? = when {
    account != "ok" -> Waiting.LOGIN
    !storytelRoom(freeBytes) -> Waiting.STORAGE
    else -> shared
}

/**
 * The `consumableId` query parameter of [uri] (a signed CDN URL), as Python's `parse_qs` reads it:
 * parsed and exact, never a substring - the token can hold another book's digits (vibb
 * `storytel_sonos.py` 7, 14). Null when absent or blank.
 */
fun consumableIdOf(uri: String?): String? {
    val query = uri?.substringAfter('?', "")?.substringBefore('#').orEmpty()
    if (query.isEmpty()) return null
    for (pair in query.split('&')) {
        val eq = pair.indexOf('=')
        if (eq < 0) continue
        if (formDecode(pair.substring(0, eq)) != "consumableId") continue
        val value = formDecode(pair.substring(eq + 1))
        if (value.isNotEmpty()) return value
    }
    return null
}

/** `unquote_plus`. */
private fun formDecode(s: String): String = pyUnquote(s.replace('+', ' '))

/**
 * Is the speaker's [live] uri the stream stored as [stored] (design 26b §5.3)? A Storytel marker
 * matches by the live uri's `consumableId`; anything else is 1b's [normUri] equality.
 */
fun sameStream(stored: String?, live: String?): Boolean {
    if (stored.isNullOrEmpty() || live.isNullOrEmpty()) return false
    val book = storytelTarget(stored)?.takeIf { it.kind == StorytelKind.BOOK }
    return if (book != null) consumableIdOf(live) == book.id else normUri(stored) == normUri(live)
}

/**
 * What `renderer.json` stores for a play of [itemKey] of [entry] sent as [uri] (design 26b §5.3, QA
 * M1): for Storytel the marker, never the signed URL (null when there is no book to name); for
 * everything else the uri as 1b stores it.
 */
fun storedUri(entry: Entry, itemKey: String?, uri: String?): String? =
    if (entry.source == Source.STORYTEL) (itemKey ?: consumableIdOf(uri))?.let(::bookTarget) else uri

/** [uri] for a log line (design 26b §5.7): scheme, host and path; the query (a token), the fragment and any userinfo cut. */
fun redactUri(uri: String?): String {
    val s = uri.orEmpty().substringBefore('?').substringBefore('#')
    val scheme = s.indexOf("://")
    if (scheme < 0) return s
    val start = scheme + 3
    val end = s.indexOf('/', start).let { if (it < 0) s.length else it }
    val at = s.lastIndexOf('@', end - 1)
    return if (at >= start) s.substring(0, start) + s.substring(at + 1) else s
}

/**
 * vibb `_sonos_known_duration`: the length (ms) of the book [uri] plays, from [queue] by the uri's
 * `consumableId` ("authoritative first", design 26b §5.4) - exact, so one book's length never lands
 * on another. Null for anything but a Storytel entry, an unknown book or no uri.
 */
fun durationByConsumableId(entry: Entry, uri: String?, queue: List<PlayItem>): Long? {
    if (entry.source != Source.STORYTEL) return null
    val want = consumableIdOf(uri) ?: return null
    return queue.firstOrNull { it.key == want }?.durationMs
}
