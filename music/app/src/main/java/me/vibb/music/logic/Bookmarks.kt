package me.vibb.music.logic

/*
 * Resume positions, ported from palchrb/vibb `pi/vibb/bookmarks.py` (`RESUME_MIN_S`, `save_state`,
 * `episode_pos`, `clear_state`, `rotate_to_bookmark`; MIT License, Copyright (c) 2026 palchrb),
 * reimplemented in Kotlin under the GPL-3.0 (or later) - see NOTICE.md. Design 21 §4.4. Pure;
 * BookmarksTest ports vibb's tests/episode_resume.py.
 *
 * Every item keeps its own position (per entry key + item key); an item played to within
 * [RESUME_MIN_MS] of its end is stored as done (its duration), which reads as 0, so a re-tap starts
 * it fresh while the fact that it was heard survives. The entry's bookmark is the item playing last.
 */

/** Don't bother resuming the first seconds; an item this close to its end is done. */
const val RESUME_MIN_MS = 20_000L

/** A stored position. [done]: heard to the end ([posMs] is then its duration). */
data class ItemPosition(val posMs: Long, val done: Boolean)

/** An entry's bookmark: the item played last and where. */
data class Bookmark(val itemKey: String, val posMs: Long)

/** What `save_state` stores for an item at [posMs] of [durationMs]. */
fun recordPosition(posMs: Long, durationMs: Long?): ItemPosition =
    if (durationMs != null && durationMs > 0 && posMs > durationMs - RESUME_MIN_MS) {
        ItemPosition(durationMs, true)
    } else {
        ItemPosition(posMs.coerceAtLeast(0), false)
    }

/** `episode_pos`: the remembered position of one item, 0 when unknown or done. */
fun resumeFrom(position: ItemPosition?): Long = if (position == null || position.done) 0 else position.posMs

/** Only the seek is gated: a position within the first [RESUME_MIN_MS] starts at 0. */
fun seekGate(posMs: Long): Long = if (posMs > RESUME_MIN_MS) posMs else 0

/**
 * `rotate_to_bookmark`: ([queue] rotated so the bookmarked item comes first, start position).
 * The bookmarked item always plays first, even seconds into it - finishing item N moves the
 * bookmark onto N+1 at ~0 s, and not rotating sent the replay back to the top ("why is it playing
 * episode 1 again?"). An unknown bookmark doesn't rotate.
 */
fun rotateToBookmark(queue: List<String>, bookmark: Bookmark?): Pair<List<String>, Long> {
    val idx = bookmark?.let { queue.indexOf(it.itemKey) } ?: -1
    if (idx < 0) return queue to 0L
    return (queue.subList(idx, queue.size) + queue.subList(0, idx)) to seekGate(bookmark!!.posMs)
}

/** What one save stores (`save_state`): the item's position, and the entry's bookmark on that item. */
data class Save(val position: ItemPosition, val bookmark: Bookmark)

fun save(itemKey: String, posMs: Long, durationMs: Long?): Save = Save(recordPosition(posMs, durationMs), Bookmark(itemKey, posMs))

/**
 * An item that played to its end by itself: its done mark, from the best duration known (the
 * player's, else the listing's); null when neither is known (a live stream).
 */
fun finished(playerDurationMs: Long?, listedDurationMs: Long?): ItemPosition? =
    (playerDurationMs?.takeIf { it > 0 } ?: listedDurationMs?.takeIf { it > 0 })?.let { recordPosition(it, it) }

/**
 * `clear_state` at a queue's natural end: the bookmark goes, and with it every partial position;
 * the done marks stay (a second run through a finished series starts each episode fresh).
 */
fun clearState(positions: Map<String, ItemPosition>): Map<String, ItemPosition> = positions.filterValues { it.done }
