package me.vibb.music.logic

import me.vibb.music.model.Entry
import me.vibb.music.model.ListingItem
import me.vibb.music.model.PlayItem
import me.vibb.music.model.Source

/*
 * NY and Nytt (design 21 §4.5), ported from palchrb/vibb `pi/vibb/library.py` (`_mark_new_seen`,
 * `is_new`, `acknowledge_new`, `_new_prune`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. vibb keeps one "newest"
 * per show; the phone keeps the item keys it has seen, so every new item can join the Nytt tray.
 * Pure; NewItemsTest ports vibb's tests/new_badge.py.
 *
 * Design 29's functions (`newsBefore`, `newsAfter`, `entryNews`, `newEntryItem`) are not in vibb: a
 * new entry contributes one item. On the Pi a new show's first listing is acknowledged like any
 * other; here an entry key the phone never knew, added once a play has been stored, puts its first
 * playable item in Nytt (design 29 §0-§1). The port above them is unchanged; NewItemsTest's `d29`
 * tests are ours.
 */

/**
 * The keys of a listing that may join the Nytt tray when new: those that can play once fetched
 * ([fetchable]: a url, or a Storytel book, whose url is minted per download - design 26b §3.4, vibb
 * `_mark_new_seen(newest_book_id)`). A withdrawn episode without a url never does. The tray and the
 * notification show only visible items, so a new book appears there once it is on the phone.
 */
fun newsEligible(entry: Entry, items: List<ListingItem>): Set<String> =
    items.filter { it.url != null || entry.source == Source.STORYTEL }.map { it.key }.toSet()

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

/** An item started playing, by any path: it leaves the tray (PlaybackService runs it as one `deleteNews`, design 29 §1.6). */
fun playedFromTray(tray: List<NewsItem>, entryId: Long, key: String): List<NewsItem> =
    tray.filterNot { it.entryId == entryId && it.key == key }

/** Entries removed from the library take their tray items with them (`_new_prune`). */
fun pruneTray(tray: List<NewsItem>, entryIds: Set<Long>): List<NewsItem> = tray.filter { it.entryId in entryIds }

/** Does the entry show NY? While it has items in the tray. */
fun isNew(tray: List<NewsItem>, entryId: Long): Boolean = tray.any { it.entryId == entryId }

// --- Design 29: a new entry is news too (not vibb) ---

/** How long after a new entry's item joins the tray its further fresh keys are acknowledged, not added (§1.4). */
const val NEW_ENTRY_SETTLE_MS = 24 * 60 * 60 * 1000L

/**
 * One row of `known_entries`. [entryId]: the id the library last gave this key (null = none now).
 * [pending]: added after the baseline, item not given yet. [item]: the new-entry item given.
 * [newAt]: when it joined the tray.
 */
data class KnownEntry(val key: String, val entryId: Long?, val pending: Boolean, val item: String? = null, val newAt: Long? = null)

/**
 * One entry's keys as the news rules see them: listed oldest first (own files in their order),
 * [eligible] (null = all), [unplayable] (§1.1: `no_audio` books, `missing` own files without a copy).
 */
data class Listed(val entry: Entry, val oldestFirst: List<String>, val eligible: Set<String>?, val unplayable: Set<String>)

/**
 * [entry]'s [Listed] from the phone's items (a listing applied into Room, or own files in their order):
 * eligible as [newsEligible] has it (own files: all), unplayable a Storytel book whose item error
 * ([errors], item key -> code) is permanent and a [missing] own file without a copy ([onPhone]).
 */
fun listedOf(entry: Entry, items: List<PlayItem>, onPhone: Set<String>, missing: Set<String>, errors: Map<String, String>): Listed {
    val eligible = if (entry.source == Source.OWN) null else items.filter { it.url != null || entry.source == Source.STORYTEL }.map { it.key }.toSet()
    val noAudio = if (entry.source == Source.STORYTEL) errors.filterValues(::storytelPermanentFailure).keys else emptySet()
    return Listed(entry, items.map { it.key }, eligible, noAudio + (missing - onPhone))
}

/**
 * Phase B's [Listed]s ([newsAfter]'s `applied`): this run's applied listings in their order, then every
 * own entry, whose files are listed at every sync ("Nytt spor", a new own entry's item).
 */
fun newsApplied(appliedIds: List<Long>, entries: List<Entry>, current: Map<Long, Listed>): List<Listed> =
    appliedIds.mapNotNull { current[it] } + entries.filter { it.source == Source.OWN }.mapNotNull { current[it.id] }

/**
 * An item in the tray just failed for good (`no_audio`): a new entry's item may need its re-pick, so a
 * sync should run now rather than at the next nudge or the 6 h backstop (qa-29-code L4).
 */
fun repickWanted(tray: List<NewsItem>, entryId: Long, key: String, code: String): Boolean =
    storytelPermanentFailure(code) && tray.any { it.entryId == entryId && it.key == key }

/**
 * A new entry's item (§1.1): the first candidate (eligible, not unplayable) in reading order for
 * Storytel - the book the keep set holds and the plan fetches first, whatever `order` says - and in
 * play order otherwise (the library's effective order); null = none.
 */
fun newEntryItem(l: Listed): String? {
    val candidates = l.oldestFirst.filter { (l.eligible == null || it in l.eligible) && it !in l.unplayable }
    return if (l.entry.source == Source.STORYTEL) candidates.firstOrNull() else playOrder(l.entry, candidates).firstOrNull()
}

/** What one entry's listing gives: the keys that join the tray (oldest first), the seen set after, the new-entry item used. */
data class EntryNews(val news: List<String>, val seen: Set<String>?, val item: String?)

/**
 * One entry's listing seen. An empty listing (a failed read) changes nothing. [pending]: the whole
 * listing is acknowledged and [newEntryItem] joins (the mark is used only when there is one). Else
 * [markNewSeen]; fresh eligible keys join, unless [settling] (inside the 24 h window), then they are
 * only acknowledged.
 */
fun entryNews(l: Listed, seen: Set<String>?, pending: Boolean, settling: Boolean): EntryNews {
    if (l.oldestFirst.isEmpty()) return EntryNews(emptyList(), seen, null)
    if (pending) {
        val item = newEntryItem(l)
        return EntryNews(listOfNotNull(item), seen.orEmpty() + l.oldestFirst, item)
    }
    val (fresh, after) = markNewSeen(seen, l.oldestFirst)
    val news = if (settling) emptyList() else fresh.filter { l.eligible == null || it in l.eligible }
    return EntryNews(news, after, null)
}

/** [newsBefore]'s result: the rows after, the sticky closed flag after, the ids to reset. */
data class Before(val known: List<KnownEntry>, val closed: Boolean, val reset: Set<Long>)

/**
 * Before the listings are applied (Sync phase A). [named]: `Library.keyById`, every entry the document
 * names. [played]: a position or bookmark row exists. A library naming nothing changes nothing.
 * - The baseline (§1.3): while it is open (not [closed] before this run) every named key becomes
 *   known, none pending; it closes for good once [played]. After that, an unknown key is pending.
 * - Reused ids (§1.4): an id the library now gives another key than the one recorded for it is
 *   reset (as if its entry were removed); the key's row takes the id and any other row drops it.
 */
fun newsBefore(known: List<KnownEntry>, closed: Boolean, named: Map<Long, String>, played: Boolean): Before {
    if (named.isEmpty()) return Before(known, closed, emptySet())
    val heldBy = known.filter { it.entryId != null }.associate { it.entryId!! to it.key }
    val reset = named.filter { (id, key) -> heldBy[id].let { it != null && it != key } }.keys
    val idOf = named.entries.associate { (id, key) -> key to id }
    val rows = known.map { it.copy(entryId = idOf[it.key]) }
    val have = known.map { it.key }.toSet()
    val added = named.entries.filter { it.value !in have }.distinctBy { it.value }
        .map { (id, key) -> KnownEntry(key, id, pending = closed) }
    return Before(rows + added, closed || played, reset)
}

/** [newsAfter]'s result: the rows, the tray and the seen keys to insert per entry. */
data class After(val known: List<KnownEntry>, val tray: List<NewsItem>, val seenAdded: Map<Long, Set<String>>)

/**
 * After the listings are applied (Sync phase B), inside one transaction. [applied]: entries whose
 * listing was applied this run, plus every own entry. [current]: every library entry with a parsed
 * listing (for the re-pick). [seen]: the seen sets of [applied]. An entry's mark and window are its
 * key's row, when that row holds the entry's id; a used mark leaves the run's pending set at once.
 * [open]: the baseline is still open after phase A (nothing played yet): every listing is only
 * acknowledged, so a known entry's later keys are setup too (qa-29-code H1).
 * The re-pick (§1.1): a given item still in the tray that can no longer play is replaced in its slot
 * (its `seq`) by the next candidate, or leaves the tray when there is none.
 */
fun newsAfter(
    known: List<KnownEntry>, tray: List<NewsItem>, seen: Map<Long, Set<String>>,
    applied: List<Listed>, current: Map<Long, Listed>, now: Long, open: Boolean,
): After {
    val rows = known.associateBy { it.key }.toMutableMap()
    var nextTray = tray
    val seenNow = seen.toMutableMap()
    val seenAdded = mutableMapOf<Long, Set<String>>()
    val used = mutableSetOf<String>()
    for (l in applied) {
        val id = l.entry.id
        val row = rows[l.entry.key]?.takeIf { it.entryId == id }
        val pending = row != null && row.pending && l.entry.key !in used
        val settling = open || row?.newAt?.let { now - it in 0L until NEW_ENTRY_SETTLE_MS } == true
        val before = seenNow[id]
        val r = entryNews(l, before, pending, settling)
        r.seen?.let { after ->
            val add = after - before.orEmpty()
            if (add.isNotEmpty()) {
                seenNow[id] = after
                seenAdded[id] = seenAdded[id].orEmpty() + add
            }
        }
        nextTray = addToTray(nextTray, id, r.news)
        if (pending && r.item != null) {
            used += l.entry.key
            rows[l.entry.key] = row!!.copy(pending = false, item = r.item, newAt = now)
        }
    }
    for (row in rows.values.toList()) {
        val id = row.entryId ?: continue
        val item = row.item ?: continue
        val l = current[id] ?: continue
        val slot = nextTray.firstOrNull { it.entryId == id && it.key == item } ?: continue
        if (item !in l.unplayable) continue
        val next = newEntryItem(l)
        nextTray = nextTray.filterNot { it == slot }
        if (next != null) {
            if (nextTray.none { it.entryId == id && it.key == next }) nextTray = (nextTray + slot.copy(key = next)).sortedByDescending { it.seq }
            rows[row.key] = row.copy(item = next)
        }
    }
    return After(known.map { rows.getValue(it.key) }, nextTray, seenAdded)
}
