package me.vibb.music.logic

/*
 * NY and Nytt (design 21 §4.5), ported from palchrb/vibb `pi/vibb/library.py` (`_mark_new_seen`,
 * `is_new`, `acknowledge_new`, `_new_prune`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. vibb keeps one "newest"
 * per show; the phone keeps the item keys it has seen, so every new item can join the Nytt tray.
 * Pure; NewItemsTest ports vibb's tests/new_badge.py.
 */

/** The Nytt tray holds at most this many items (newest first). */
const val NEWS_MAX = 30

/** One item in the Nytt tray. [seq]: higher = newer. */
data class NewsItem(val entryId: Long, val key: String, val seq: Long)

/**
 * A listing (or an own entry's files) seen again: (the keys that are new, the seen set after).
 * [seen] null = the entry's first listing, which acknowledges everything (existing content is not
 * new); an empty listing (a failed read) changes nothing. [listed] is oldest first, so the new keys
 * come oldest first too.
 */
fun markNewSeen(seen: Set<String>?, listed: List<String>): Pair<List<String>, Set<String>?> {
    if (listed.isEmpty()) return emptyList<String>() to seen
    if (seen == null) return emptyList<String>() to listed.toSet()
    val fresh = listed.filter { it !in seen }
    return fresh to (if (fresh.isEmpty()) seen else seen + fresh)
}

/** The tray with [fresh] (oldest first) of [entryId] added newest first, capped at [NEWS_MAX]. */
fun addToTray(tray: List<NewsItem>, entryId: Long, fresh: List<String>): List<NewsItem> {
    if (fresh.isEmpty()) return tray
    var seq = (tray.maxOfOrNull { it.seq } ?: 0L)
    val existing = tray.map { it.entryId to it.key }.toSet()
    val added = fresh.filter { (entryId to it) !in existing }.map { NewsItem(entryId, it, ++seq) }
    return (tray + added).sortedByDescending { it.seq }.take(NEWS_MAX)
}

/** An item started playing, by any path: it leaves the tray. */
fun playedFromTray(tray: List<NewsItem>, entryId: Long, key: String): List<NewsItem> =
    tray.filterNot { it.entryId == entryId && it.key == key }

/** Entries removed from the library take their tray items with them (`_new_prune`). */
fun pruneTray(tray: List<NewsItem>, entryIds: Set<Long>): List<NewsItem> = tray.filter { it.entryId in entryIds }

/** Does the entry show NY? While it has items in the tray. */
fun isNew(tray: List<NewsItem>, entryId: Long): Boolean = tray.any { it.entryId == entryId }
