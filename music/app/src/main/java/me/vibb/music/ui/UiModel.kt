package me.vibb.music.ui

import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.Hint
import me.vibb.music.logic.Kind
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.countedOrder
import me.vibb.music.logic.hint
import me.vibb.music.logic.isNew
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.overallProgress
import me.vibb.music.logic.started
import me.vibb.music.model.Category
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.PlayerHub

/** A carousel cover. */
data class EntryUi(
    val entry: Entry,
    val category: Category?,
    val kind: Kind,
    val hint: Hint,
    /** The thin overall-progress line of a started resume entry; null = not shown. */
    val progress: Float?,
    val isNew: Boolean,
)

fun entryUi(snap: Snapshot, entry: Entry): EntryUi {
    val visible = snap.order(entry).map { it.key }
    val order = countedOrder(entry, visible, snap.listed[entry.id].orEmpty().map { it.key })
    val bookmark = snap.bookmarks[entry.key]
    val showProgress = entry.resume && started(order, bookmark)
    val duration = bookmark?.let { b -> snap.item(entry.id, b.itemKey)?.durationMs }
    return EntryUi(
        entry,
        snap.library?.categories?.firstOrNull { it.id == entry.category },
        kindOf(entry),
        hint(entry, order, bookmark),
        if (showProgress) overallProgress(order, bookmark, duration) else null,
        isNew(snap.news.filter { n -> visible.contains(n.key) }, entry.id),
    )
}

/** What is playing (live from the player) or was playing (the stored queue, shown paused). */
data class NowView(
    val entry: Entry,
    val category: Category?,
    /** The queue in its order (the list shows it, shuffled or not). */
    val queue: List<PlayItem>,
    val index: Int,
    val live: Boolean,
    val playing: Boolean,
    /** The stored position (not live). */
    val storedPosMs: Long,
    val durationMs: Long?,
    val shuffled: Boolean,
) {
    val item: PlayItem get() = queue[index]
    val canShuffle: Boolean get() = canShuffle(entry)
}

/** The now-playing view from the player's state, else from the stored row; null when there is none. */
fun nowView(snap: Snapshot, hub: PlayerHub.State, row: NowPlayingRow?): NowView? {
    val entries = snap.library?.entries.orEmpty()
    if (hub.live && hub.entryId != null) {
        val entry = entries.firstOrNull { it.id == hub.entryId } ?: return null
        val queue = hub.keys.mapNotNull { snap.item(entry.id, it) }
        val index = queue.indexOfFirst { it.key == hub.keys.getOrNull(hub.index) }
        if (index < 0) return null
        return NowView(entry, category(snap, entry), queue, index, true, hub.playing, 0, hub.durationMs ?: queue[index].durationMs, hub.shuffled)
    }
    row ?: return null
    val entry = snap.entries.firstOrNull { it.id == row.entryId } ?: return null
    val stored = row.keys.split('\n').filter { it.isNotEmpty() }
    val visible = snap.order(entry).associateBy { it.key }
    val queue = stored.mapNotNull { visible[it] }
    val index = queue.indexOfFirst { it.key == stored.getOrNull(row.index) }
    if (index < 0) return null
    return NowView(entry, category(snap, entry), queue, index, false, false, row.posMs, row.durationMs ?: queue[index].durationMs, row.shuffled)
}

private fun category(snap: Snapshot, entry: Entry) = snap.library?.categories?.firstOrNull { it.id == entry.category }

/** The list's rows come in the queue's order when shuffled, else in the entry's play order. */
fun listOrder(snap: Snapshot, now: NowView): List<PlayItem> = if (now.shuffled) now.queue else snap.order(now.entry)

/**
 * The player's "N av M" (1-based N, M): the item in the list's order, or for Storytel in the series'
 * reading order ("Del 3 av 5" with only books 1 and 3 on the phone, design 26b §3.5, QA M5).
 */
fun playerNumber(snap: Snapshot, now: NowView): Pair<Int, Int> {
    val order = countedOrder(
        now.entry,
        listOrder(snap, now).map { it.key },
        snap.listed[now.entry.id].orEmpty().map { it.key },
    )
    return order.indexOf(now.item.key) + 1 to order.size
}

/** Equal dots: at most [max] shown, a window that keeps the current one in view. */
fun dotWindow(n: Int, current: Int, max: Int = 20): IntRange {
    if (n <= max) return 0 until n
    val start = (current - max / 2).coerceIn(0, n - max)
    return start until start + max
}
