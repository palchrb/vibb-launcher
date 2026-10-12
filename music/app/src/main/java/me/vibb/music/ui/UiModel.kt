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
import me.vibb.music.logic.skipTarget
import me.vibb.music.logic.started
import me.vibb.music.model.Category
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sonos.PlayBodies

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

    /** The cover hash shown: the item's own art, else the entry's. */
    val cover: String? get() = if (itemKnown) item.artHash ?: entry.cover else entry.cover
    val itemTitle: String get() = if (itemKnown) item.title else ""
}

/** A tap the player hasn't shown yet ([requestStands]) and what the screens show meanwhile. */
data class Switch(val request: PlayRequest, val view: NowView)

/**
 * A tap on [entryId] (a cover, or [itemKey] from Nytt, search or the list) while [current] shows: what it starts.
 * [onRoom]: a Sonos room renders (design 26a §4.2): its queue holds only what a speaker can play
 * (`PlayBodies.speakerPlayable`) and a start the speaker can't play moves on to the next it can, from 0
 * (`PlayBodies.speakerPlan`, as the service builds it); nothing a speaker can play is no switch (the
 * service says "Kan ikke spilles på Sonos").
 */
fun playSwitch(snap: Snapshot, entryId: Long, itemKey: String?, current: NowView?, atMs: Long, onRoom: Boolean = false): Switch? {
    val entry = snap.entries.firstOrNull { it.id == entryId } ?: return null
    val all = snap.order(entry)
    val order = if (onRoom) all.filter { PlayBodies.speakerPlayable(entry, it) } else all
    if (order.isEmpty()) return null
    val keys = order.map { it.key }
    val shuffle = canShuffle(entry) && entry.key in snap.shuffle
    var (key, pos) = if (itemKey != null) {
        val allKeys = all.map { it.key }
        val plan = pickPlan(allKeys, itemKey, entry.resume, snap.positions[entry.key]?.get(itemKey), null) ?: return null
        itemKey to plan.startPosMs
    } else {
        coverTapStart(all.map { it.key }, entry.resume, snap.bookmarks[entry.key], shuffle) ?: return null
    }
    if (key != null && key !in keys) {
        // On a room: the next item after it (in play order, wrapping) a speaker can play, from 0.
        val from = all.indexOfFirst { it.key == key }
        key = (1..all.size).map { all[(from + it) % all.size].key }.first { it in keys }
        pos = 0
    }
    // Shuffled, the picked item starts a new queue; else the play order, at the item.
    val queue = if (shuffle && key != null) order.filter { it.key == key } + order.filter { it.key != key } else order
    val index = if (key == null) 0 else queue.indexOfFirst { it.key == key }
    val view = NowView(
        entry, category(snap, entry), queue, index, live = false, playing = true, storedPosMs = pos,
        durationMs = if (key == null) null else queue[index].durationMs, shuffled = shuffle, itemKnown = key != null,
    )
    return Switch(PlayRequest(entryId, itemKey?.let { key }, current?.ref, atMs), view)
}

/**
 * Next ([forward]) or previous on [now], [posMs] into its item: the item it goes to, when the screens can know it.
 * The target is counted in [now]'s queue - the renderer's (a room's holds only what a speaker can play), the
 * service's `skipNext`/`skipPrevious` count the same. [onRoom]: a step on a playing room starts the item from 0
 * (`SonosController.stepTo`, vibb's `sonos_step`; design 26a 3a open question (a)); paused it starts where the
 * phone would.
 */
fun skipSwitch(snap: Snapshot, now: NowView, forward: Boolean, posMs: Long, atMs: Long, onRoom: Boolean = false): Switch? {
    val target = skipTarget(forward, now.index, now.queue.size, posMs, now.durationMs) ?: return null
    val item = now.queue[target]
    val pos = if (onRoom && now.playing) 0 else if (now.entry.resume) seekGate(resumeFrom(snap.positions[now.entry.key]?.get(item.key))) else 0
    val view = now.copy(index = target, live = false, storedPosMs = pos, durationMs = item.durationMs, itemKnown = true)
    return Switch(PlayRequest(now.entry.id, item.key, now.ref, atMs), view)
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
 * The player's "N av M" (1-based N, M): the item in the list's order - the same for what the player
 * holds and for a tap's switch ([playSwitch], [skipSwitch]), on the phone or a room (a room's queue
 * leaves out what a speaker can't play, the play order doesn't).
 */
fun playerNumber(snap: Snapshot, now: NowView): Pair<Int, Int> {
    val order = listOrder(snap, now)
    return order.indexOfFirst { it.key == now.item.key } + 1 to order.size
}

/** Equal dots: at most [max] shown, a window that keeps the current one in view. */
fun dotWindow(n: Int, current: Int, max: Int = 20): IntRange {
    if (n <= max) return 0 until n
    val start = (current - max / 2).coerceIn(0, n - max)
    return start until start + max
}
