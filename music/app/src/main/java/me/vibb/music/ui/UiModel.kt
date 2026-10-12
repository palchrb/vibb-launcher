package me.vibb.music.ui

import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.Hint
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.Kind
import me.vibb.music.logic.PlayRequest
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.coverTapStart
import me.vibb.music.logic.hint
import me.vibb.music.logic.isNew
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.overallProgress
import me.vibb.music.logic.pickPlan
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.seekGate
import me.vibb.music.logic.showsCarousel
import me.vibb.music.logic.skipTarget
import me.vibb.music.logic.started
import me.vibb.music.logic.swipeVia
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
    val order = snap.order(entry).map { it.key }
    val bookmark = snap.bookmarks[entry.key]
    val showProgress = entry.resume && started(order, bookmark)
    val duration = bookmark?.let { b -> snap.item(entry.id, b.itemKey)?.durationMs }
    return EntryUi(
        entry,
        snap.library?.categories?.firstOrNull { it.id == entry.category },
        kindOf(entry),
        hint(entry, order, bookmark),
        if (showProgress) overallProgress(order, bookmark, duration) else null,
        isNew(snap.news.filter { n -> order.contains(n.key) }, entry.id),
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
    /** False while a tap's item isn't known yet (shuffle picks it): the entry's cover, no item title. */
    val itemKnown: Boolean = true,
) {
    val item: PlayItem get() = queue[index]
    val canShuffle: Boolean get() = canShuffle(entry)
    val ref: ItemRef get() = ItemRef(entry.id, item.key)

    /** The cover hash shown: the item's own art, else the entry's ([Cover] falls back to [fallback]
     * while the item's picture isn't on the phone). */
    val cover: String? get() = if (itemKnown) item.artHash ?: entry.cover else entry.cover
    val fallback: String? get() = entry.cover
    val itemTitle: String get() = if (itemKnown) item.title else ""

    /** The Now Playing carousel (design 27 §3.1) in place of the single cover. */
    val carousel: Boolean by lazy { itemKnown && showsCarousel(queue, entry.cover) }

    /** The carousel's pager starts over when this changes: another entry or another key list (QA M7 b). */
    val pagerKey: Pair<Long, List<String>> get() = entry.id to queue.map { it.key }
}

/** A tap the player hasn't shown yet ([requestStands]) and what the screens show meanwhile. */
data class Switch(val request: PlayRequest, val view: NowView)

/** A tap on [entryId] (a cover, or [itemKey] from Nytt, search or the list) while [current] shows: what it starts. */
fun playSwitch(snap: Snapshot, entryId: Long, itemKey: String?, current: NowView?, atMs: Long): Switch? {
    val entry = snap.entries.firstOrNull { it.id == entryId } ?: return null
    val order = snap.order(entry)
    val keys = order.map { it.key }
    val shuffle = canShuffle(entry) && entry.key in snap.shuffle
    val (key, pos) = if (itemKey != null) {
        val plan = pickPlan(keys, itemKey, entry.resume, snap.positions[entry.key]?.get(itemKey), null) ?: return null
        itemKey to plan.startPosMs
    } else {
        coverTapStart(keys, entry.resume, snap.bookmarks[entry.key], shuffle) ?: return null
    }
    if (key != null && key !in keys) return null
    // Shuffled, the picked item starts a new queue; else the play order, at the item.
    val queue = if (shuffle && key != null) order.filter { it.key == key } + order.filter { it.key != key } else order
    val index = if (key == null) 0 else queue.indexOfFirst { it.key == key }
    val view = NowView(
        entry, category(snap, entry), queue, index, live = false, playing = true, storedPosMs = pos,
        durationMs = if (key == null) null else queue[index].durationMs, shuffled = shuffle, itemKnown = key != null,
    )
    return Switch(PlayRequest(entryId, itemKey, current?.ref, atMs), view)
}

/** Next ([forward]) or previous on [now], [posMs] into its item: the item it goes to, when the screens can know it. */
fun skipSwitch(snap: Snapshot, now: NowView, forward: Boolean, posMs: Long, atMs: Long): Switch? {
    val target = skipTarget(forward, now.index, now.queue.size, posMs, now.durationMs) ?: return null
    return toIndex(snap, now, target, PlayRequest(now.entry.id, now.queue[target].key, now.ref, atMs))
}

/**
 * A swipe of the Now Playing carousel to queue index [target] of [now] (design 27 §3.2): the item
 * shows at once with its saved place (title and seek bar follow the picture), until the player is
 * there. [current]: what the player shows; [earlier]: a swipe's request that still stands (quick
 * swipes: the player passes through its target first).
 */
fun swipeSwitch(snap: Snapshot, now: NowView, target: Int, current: ItemRef?, earlier: PlayRequest?, atMs: Long): Switch? {
    val item = now.queue.getOrNull(target) ?: return null
    if (target == now.index && now.itemKnown) return null
    return toIndex(snap, now, target, PlayRequest(now.entry.id, item.key, current, atMs, swipeVia(earlier)))
}

private fun toIndex(snap: Snapshot, now: NowView, target: Int, request: PlayRequest): Switch {
    val item = now.queue[target]
    val pos = if (now.entry.resume) seekGate(resumeFrom(snap.positions[now.entry.key]?.get(item.key))) else 0
    val view = now.copy(index = target, live = false, storedPosMs = pos, durationMs = item.durationMs, itemKnown = true)
    return Switch(request, view)
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

/** Equal dots: at most [max] shown, a window that keeps the current one in view. */
fun dotWindow(n: Int, current: Int, max: Int = 20): IntRange {
    if (n <= max) return 0 until n
    val start = (current - max / 2).coerceIn(0, n - max)
    return start until start + max
}
