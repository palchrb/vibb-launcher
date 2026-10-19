package me.vibb.music.ui

import me.vibb.music.data.NowPlayingRow
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.Hint
import me.vibb.music.logic.ItemRef
import me.vibb.music.logic.Kind
import me.vibb.music.logic.OrderWords
import me.vibb.music.logic.orderWords
import me.vibb.music.logic.PlayRequest
import me.vibb.music.logic.canShuffle
import me.vibb.music.logic.contextResumes
import me.vibb.music.logic.countedOrder
import me.vibb.music.logic.coverTapAwaitsBook
import me.vibb.music.logic.coverTapStart
import me.vibb.music.logic.hint
import me.vibb.music.logic.isNew
import me.vibb.music.logic.isSpotify
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.overallProgress
import me.vibb.music.logic.pickPlan
import me.vibb.music.logic.resumeFrom
import me.vibb.music.logic.seekGate
import me.vibb.music.logic.showsCarousel
import me.vibb.music.logic.skipTarget
import me.vibb.music.logic.spotifyTileLine
import me.vibb.music.logic.started
import me.vibb.music.logic.swipeVia
import me.vibb.music.model.Category
import me.vibb.music.model.Entry
import me.vibb.music.model.PlayItem
import me.vibb.music.playback.PlayerHub
import me.vibb.music.playback.RendererState
import me.vibb.music.sonos.PlayBodies
import me.vibb.music.spotify.rendererStateOf

/** A carousel cover. */
data class EntryUi(
    val entry: Entry,
    val category: Category?,
    val kind: Kind,
    val hint: Hint,
    /** The thin overall-progress line of a started resume entry; null = not shown. */
    val progress: Float?,
    val isNew: Boolean,
    /** In place of the hint (design 26c §5.8): a Spotify tile's line - the loaded one's state, else the stored outcome's. */
    val state: RendererState? = null,
)

/**
 * A cover of [entry]: its kind, hint, progress and NY - and for a Spotify entry the line that replaces the hint
 * ([spotifyTileState]: [loadedId]'s renderer [loadedState] from `PlayerHub.State`, the stored [spotifyOutcome]).
 */
fun entryUi(snap: Snapshot, entry: Entry, loadedId: Long? = null, loadedState: RendererState? = null, spotifyOutcome: String? = null): EntryUi {
    val visible = snap.order(entry).map { it.key }
    val order = countedOrder(entry, visible, snap.listed[entry.id].orEmpty().map { it.key })
    val bookmark = snap.bookmarks[entry.key]
    // a Spotify context (design 26c §5.9) has no list to show progress through: Spotify picks the tracks
    val showProgress = entry.resume && started(order, bookmark) && !isSpotifyContext(entry)
    val duration = bookmark?.let { b -> snap.item(entry.id, b.itemKey)?.durationMs }
    return EntryUi(
        entry,
        snap.library?.categories?.firstOrNull { it.id == entry.category },
        kindOf(entry),
        hint(entry, order, bookmark),
        if (showProgress) overallProgress(order, bookmark, duration) else null,
        isNew(snap.news.filter { n -> visible.contains(n.key) }, entry.id),
        spotifyTileState(entry, loadedId, loadedState, spotifyOutcome),
    )
}

/**
 * What a Spotify tile says in place of its hint (design 26c §5.8, QA M6): the loaded entry ([loadedId]) shows its
 * web player's line ([loadedState], `PlayerHub.State.rendererState` - "Trenger Wi-Fi", "Trenger internett",
 * "Spilles et annet sted" ...); every Spotify tile shows the stored outcome's account line ("Spør en voksen", "Ikke
 * tilgjengelig nå": `spotifyTileLine`) until a play succeeds; else null (the hint). No line names the source (step 8).
 * Never for another source, and never a room's line (`remote`).
 */
fun spotifyTileState(entry: Entry, loadedId: Long?, loadedState: RendererState?, spotifyOutcome: String?): RendererState? {
    if (!isSpotify(entry)) return null
    if (entry.id == loadedId && loadedState != null && !loadedState.remote) return loadedState
    return spotifyTileLine(spotifyOutcome)?.let { rendererStateOf(it) }
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

/**
 * A tap on [entryId] (a cover, or [itemKey] from Nytt, search or the list) while [current] shows: what it starts.
 * [onRoom]: a Sonos room renders (design 26a §4.2): its queue holds only what a speaker can play
 * (`PlayBodies.speakerPlayable`) and a start the speaker can't play moves on to the next it can, from 0 (`PlayBodies.speakerPlan`, as the service builds it); nothing a speaker can play is no switch (the
 * service says "Kan ikke spilles på Sonos").
 * The entry and its order are read in the renderer's view, `snap.forRenderer(onRoom)` (design 26b §3.2, §5.6): on a
 * room every Storytel book (the speaker fetches it from the CDN), on the phone only the books with a copy - what the
 * service's queue for that renderer holds. Until step 4b the speaker filter drops every Storytel book (no url), so a
 * Storytel tap on a room is no switch, as the service's "Kan ikke spilles på Sonos"; 4b's filter lets them through
 * and the view then counts the whole series, as the room's queue does.
 */
fun playSwitch(snap: Snapshot, entryId: Long, itemKey: String?, current: NowView?, atMs: Long, onRoom: Boolean = false): Switch? {
    val shown = snap.forRenderer(onRoom)
    val entry = shown.entries.firstOrNull { it.id == entryId } ?: return null
    val all = shown.order(entry)
    // the phone waits for the bookmark's book (design 26b §5.6, qa-26b-step4b-code M1): the service starts nothing
    if (itemKey == null && coverTapAwaitsBook(entry, snap.bookmarks[entry.key], all.map { it.key }, shown.listed[entry.id].orEmpty().map { it.key })) return null
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
    // an artist context starts where Spotify starts it (design 26c §12, qa-26c-step6-code L2): its place shows 0
    if (!contextResumes(entry)) pos = 0
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
    // a Spotify context (design 26c §5.9): next and previous are the web player's, and only Spotify knows where they go
    if (isSpotifyContext(now.entry)) return null
    val target = skipTarget(forward, now.index, now.queue.size, posMs, now.durationMs) ?: return null
    return toIndex(snap, now, target, PlayRequest(now.entry.id, now.queue[target].key, now.ref, atMs), onRoom)
}

/**
 * A swipe of the Now Playing carousel to queue index [target] of [now] (design 27 §3.2): the item
 * shows at once with its saved place (title and seek bar follow the picture), until the player is
 * there. [current]: what the player shows; [earlier]: a swipe's request that still stands (quick
 * swipes: the player passes through its target first). [onRoom]: as [skipSwitch] - the pages are the
 * room's queue (what a speaker can play), and a swipe on a playing room starts the item from 0
 * (the service's `SKIP_TO` -> `goTo` -> `SonosController.stepTo`; design 26a, the merge of design 27).
 */
fun swipeSwitch(snap: Snapshot, now: NowView, target: Int, current: ItemRef?, earlier: PlayRequest?, atMs: Long, onRoom: Boolean = false): Switch? {
    val item = now.queue.getOrNull(target) ?: return null
    if (target == now.index && now.itemKnown) return null
    return toIndex(snap, now, target, PlayRequest(now.entry.id, item.key, current, atMs, swipeVia(earlier)), onRoom)
}

private fun toIndex(snap: Snapshot, now: NowView, target: Int, request: PlayRequest, onRoom: Boolean): Switch {
    val item = now.queue[target]
    val pos = if (onRoom && now.playing) 0 else if (now.entry.resume) seekGate(resumeFrom(snap.positions[now.entry.key]?.get(item.key))) else 0
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
    // an artist context's stored place is shown as 0: its play starts where Spotify starts it (L2)
    val pos = if (contextResumes(entry)) row.posMs else 0
    return NowView(entry, category(snap, entry), queue, index, false, false, pos, row.durationMs ?: queue[index].durationMs, row.shuffled)
}

private fun category(snap: Snapshot, entry: Entry) = snap.library?.categories?.firstOrNull { it.id == entry.category }

/** The list's rows come in the queue's order when shuffled, else in the entry's play order. */
fun listOrder(snap: Snapshot, now: NowView): List<PlayItem> = if (now.shuffled) now.queue else snap.order(now.entry)

/** The list's order button and words (design 39 §4): [flipped] the button peach; [words] the shown order's. */
data class OrderUi(val flipped: Boolean, val shownNewestFirst: Boolean, val words: OrderWords)

/** The list's order button, or null: never for a Spotify context (no list), while shuffled, or below two [rows]. */
fun listOrderUi(snap: Snapshot, now: NowView, rows: Int): OrderUi? {
    if (isSpotifyContext(now.entry) || now.shuffled || rows < 2) return null
    val newest = snap.shownNewestFirst(now.entry)
    return OrderUi(snap.flipped(now.entry), newest, orderWords(now.entry, newest))
}

/**
 * The player's "N av M" (1-based N, M): the item in the list's order, or for Storytel in the series'
 * reading order ("Del 3 av 5" with only books 1 and 3 on the phone, design 26b §3.5, QA M5) - the same
 * for what the player holds and for a tap's switch ([playSwitch], [skipSwitch]), on the phone or a room
 * (a room's queue leaves out what a speaker can't play, the play order doesn't).
 */
fun playerNumber(snap: Snapshot, now: NowView): Pair<Int, Int> = playerNumberOf(snap, now, now.item) to countedKeys(snap, now).size

/**
 * [item]'s N in [playerNumber]'s count (the same order): the carousel's landing page shows its own
 * "N av M" (design 27 §3.2) - on a room too, where the pages are the speaker-playable queue but the
 * count is the play order's, and for Storytel the series' reading order (a swipe to book 3 of 5 says
 * "3 av 5" on the landing page as the player does once it is there).
 */
fun playerNumberOf(snap: Snapshot, now: NowView, item: PlayItem): Int = countedKeys(snap, now).indexOf(item.key) + 1

/**
 * The keys [playerNumber] and [playerNumberOf] count in: the list's order, or for Storytel the series'
 * reading order ([countedOrder], design 26b §3.5, QA M5). One function, so the carousel and the player
 * can't disagree (qa-26a-merge27-code, "The Storytel seam").
 */
private fun countedKeys(snap: Snapshot, now: NowView): List<String> = countedOrder(
    now.entry,
    listOrder(snap, now).map { it.key },
    snap.listed[now.entry.id].orEmpty().map { it.key },
)

/**
 * Whether the player shows "Spor N av M" (design 26c §4.3, §5.9): never for a Spotify context - nothing is listed,
 * Spotify picks the tracks; its one synthetic item would always read "1 av 1".
 */
fun showsNumber(now: NowView): Boolean = !isSpotifyContext(now.entry)

/** Equal dots: at most [max] shown, a window that keeps the current one in view. */
fun dotWindow(n: Int, current: Int, max: Int = 20): IntRange {
    if (n <= max) return 0 until n
    val start = (current - max / 2).coerceIn(0, n - max)
    return start until start + max
}
