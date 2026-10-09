package me.vibb.music.logic

import me.vibb.music.model.Entry

/*
 * What stays on the phone (design 21 §4.5, 21b §6), ported from palchrb/vibb `pi/vibb/content.py`
 * (`sync`, `sync_feed`, `prune_cache`; MIT License, Copyright (c) 2026 palchrb), reimplemented in
 * Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Pure; OfflineTest ports vibb's
 * tests/offline_keep_all.py and cache_prune.py.
 *
 * Keep the newest N of the server's list (the last N, oldest first), all for -1, none for 0, plus
 * the bookmarked item and the next one in play order. A kept item whose `url` became null keeps its
 * copy and plays it; items outside the keep set are pruned whether or not their url is null (or a
 * daily feed would grow without bound); the bookmarked item is never deleted. Prune runs only on a
 * parsed listing (QA #3): an entry whose listing isn't there keeps what it has.
 */

/** One listed item as keep/prune sees it. */
data class KeepItem(val key: String, val hasUrl: Boolean)

/**
 * The keys [entry] keeps: the newest [Entry.cache] of [oldestFirst], plus [bookmarkKey] and the
 * item after it in play order (wrapping, as the rotated queue does).
 */
fun keepSet(entry: Entry, oldestFirst: List<String>, bookmarkKey: String?): Set<String> {
    val keep = linkedSetOf<String>()
    when {
        entry.cache < 0 -> keep += oldestFirst
        entry.cache > 0 -> keep += oldestFirst.takeLast(entry.cache)
    }
    if (bookmarkKey != null && bookmarkKey in oldestFirst) {
        keep += bookmarkKey
        val order = playOrder(entry, oldestFirst)
        if (order.size > 1) keep += order[(order.indexOf(bookmarkKey) + 1) % order.size]
    }
    return keep
}

/** Items to fetch: kept, with a url, not on the phone yet. The bookmarked item first, then the
 * newest first, as vibb's `sync` downloads (`reversed(wanted)`). [items] is oldest first. */
fun wantedDownloads(keep: Set<String>, items: List<KeepItem>, have: Set<String>, bookmarkKey: String?): List<String> {
    val urls = items.filter { it.hasUrl }.map { it.key }.toSet()
    val wanted = items.asReversed().map { it.key }.filter { it in keep && it in urls && it !in have }
    // The item in progress first: it is the one the kid plays next.
    return if (bookmarkKey != null && bookmarkKey in wanted) listOf(bookmarkKey) + (wanted - bookmarkKey) else wanted
}

/** Copies to delete for one entry with a parsed listing: everything on the phone outside [keep]. */
fun prunable(have: Set<String>, keep: Set<String>, bookmarkKey: String?): Set<String> =
    have.filterTo(linkedSetOf()) { it !in keep && it != bookmarkKey }

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
