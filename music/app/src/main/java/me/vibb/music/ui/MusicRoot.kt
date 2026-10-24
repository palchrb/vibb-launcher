package me.vibb.music.ui

import android.os.SystemClock
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import me.vibb.music.R
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.SearchHit
import me.vibb.music.logic.categoryTiles
import me.vibb.music.logic.effectiveCategory
import me.vibb.music.logic.holdEndsAt
import me.vibb.music.logic.isSpotify
import me.vibb.music.logic.isSpotifyContext
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.listArtWindow
import me.vibb.music.logic.listRows
import me.vibb.music.logic.rowPictures
import me.vibb.music.logic.offlineSummary
import me.vibb.music.logic.requestStands
import me.vibb.music.logic.search
import me.vibb.music.logic.shownEntries
import me.vibb.music.logic.FAVOURITES_CATEGORY
import me.vibb.music.logic.canFavourite
import me.vibb.music.logic.skipWithin
import me.vibb.music.logic.sleepAtEndOffered
import me.vibb.music.playback.Output
import me.vibb.music.playback.OutputKind
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sync.NewNotifier

/** The screens of `Main.dc.html` v12, drawn edge to edge with the system bars' insets applied once. */
@Composable
fun MusicRoot(vm: MusicViewModel, actions: Actions) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val hub by PlayerHub.state.collectAsStateWithLifecycle()
    val row by vm.nowRow.collectAsStateWithLifecycle()
    val spotifyOutcome by vm.spotifyOutcome.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf<PlayerHub.Message?>(null) }
    LaunchedEffect(Unit) {
        PlayerHub.messages.collect {
            vm.switching = null // the player refused or skipped: show what it has
            if (it == PlayerHub.Message.STAYED) return@collect // the player's own line says why (§6l A, F3)
            message = it
            delay(3_000)
            message = null
        }
    }
    // design 52: a headset tapped on a room was routed - the room hands over to it (headsetTapPlan)
    LaunchedEffect(Unit) { vm.outputRequests.collect { actions.output(it) } }
    // design 50 §1.3: once the first snapshot and the stored row are in, the carousel opens on the last played tile
    LaunchedEffect(snap != null, row != null) {
        val s0 = snap ?: return@LaunchedEffect
        val r0 = row ?: return@LaunchedEffect
        vm.startOn(s0, r0)
    }
    Box(Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val s = snap ?: return@Box
        // What the player has, else - for a moment after a tap - what the kid asked for (logic/Switch.kt).
        val current = nowView(s, hub, row)
        val switching = vm.switching
        // While the service still has the tap's command waiting (a room switch, the look at a room) the hold is longer.
        val held = switching?.takeIf { requestStands(it.request, current?.ref, SystemClock.elapsedRealtime(), hub.waiting) }
        val now = held?.view ?: current
        LaunchedEffect(switching, held != null, hub.waiting) {
            if (switching == null) return@LaunchedEffect
            if (held != null) delay(holdEndsAt(switching.request, hub.waiting) - SystemClock.elapsedRealtime())
            vm.switching = null
        }
        // A Sonos room renders: its queue and its steps (design 26a §4.2, §4.5).
        val onRoom = hub.output.kind == OutputKind.SONOS
        val screen = if (now == null && vm.screen in setOf(Screen.PLAYER, Screen.LIST)) Screen.LIBRARY else vm.screen
        val backToLibrary = { vm.backToLibrary(s, now?.entry?.id, now?.favourites == true) }
        BackHandler(enabled = vm.sheet != null || screen != Screen.LIBRARY) {
            when {
                vm.sheet != null -> vm.sheet = null
                screen == Screen.LIST -> vm.screen = Screen.PLAYER
                screen == Screen.PLAYER -> backToLibrary()
                else -> vm.screen = Screen.LIBRARY
            }
        }
        val playEntry = { id: Long ->
            vm.switching = playSwitch(s, id, null, current, SystemClock.elapsedRealtime(), onRoom)
            actions.playEntry(id)
            vm.screen = Screen.PLAYER
        }
        // design r12 §3.1: the "Favoritter" tile - every heart in one queue
        val playFavourites = {
            vm.switching = favouritesSwitch(s, current, SystemClock.elapsedRealtime(), onRoom)
            actions.playFavourites()
            vm.screen = Screen.PLAYER
        }
        val playItem = { id: Long, key: String ->
            vm.switching = playSwitch(s, id, key, current, SystemClock.elapsedRealtime(), onRoom)
            actions.playItem(id, key)
            vm.screen = Screen.PLAYER
        }
        when (screen) {
            Screen.LIBRARY -> Library(vm, s, now, hub, spotifyOutcome, actions, playEntry, playFavourites)
            Screen.PLAYER -> now?.let { n ->
                val (_, of) = playerNumber(s, n)
                val position = livePosition(n)
                val skip = { forward: Boolean ->
                    // The item next/previous goes to shows at once (not during another switch: the player isn't there yet).
                    if (held == null) vm.switching = skipSwitch(s, n, forward, position, SystemClock.elapsedRealtime(), onRoom)
                    if (forward) actions.next() else actions.previous()
                }
                // A swipe of the episode carousel (design 27 §3.2): the picked episode shows at once and plays from its place.
                val skipTo = { target: Int, forward: Boolean ->
                    val item = n.queue[target]
                    swipeSwitch(s, n, target, current?.ref, held?.request, SystemClock.elapsedRealtime(), onRoom)?.let { vm.switching = it }
                    actions.skipTo(item.entryId, n.entryOf(item).key, item.key, forward)
                }
                PlayerScreen(
                    n, { item -> playerNumberOf(s, n, item) }, of, position, hub.sleepMinutes, hub.sleepAtEnd, hub.output, hub.rendererState,
                    onBack = backToLibrary,
                    onSeek = actions::seekTo,
                    onShuffle = actions::toggleShuffle,
                    onRepeat = actions::toggleRepeat,
                    onPrevious = { skip(false) },
                    onTogglePlay = actions::togglePlay,
                    onNext = { skip(true) },
                    // design 50 §1.1: from the live place (read now, not the last tick), clamped inside the item by its live duration
                    // each item's own entry's heart (a favourites queue's pages come from many, design r12 §3.2)
                    favourite = { item -> n.entryOf(item).let { e -> if (canFavourite(e, item.key)) s.favourite(e, item.key) else null } },
                    onFavourite = { item, on -> vm.setFavourite(n.entryOf(item), item.key, on) },
                    onSkipBy = { delta -> actions.seekTo(skipWithin(if (n.live) PlayerHub.positionMs() ?: position else position, delta, n.durationMs)) },
                    onSkipTo = skipTo,
                    onList = { vm.screen = Screen.LIST },
                    onSleep = { vm.sheet = SheetKind.SLEEP },
                    onOutput = { vm.sheet = SheetKind.OUTPUT },
                )
                when (vm.sheet) {
                    SheetKind.SLEEP -> SleepSheet(
                        hub.sleepMinutes, hub.sleepAtEnd, sleepAtEndOffered(n.durationMs),
                        onPick = { actions.sleep(it); vm.sheet = null },
                        // a second tap on "Til slutten" turns it off, as "Av" does
                        onAtEnd = { if (hub.sleepAtEnd) actions.sleep(0) else actions.sleepAtEnd(); vm.sheet = null },
                        onDismiss = { vm.sheet = null },
                    )
                    SheetKind.OUTPUT -> OutputSheet(
                        // design 53 §2.7: the rooms as groupSheet decides them, from the service's published state
                        outputSheetItems(
                            hub.outputs, vm.headsetRows(),
                            groupSheet(
                                hub.rooms, Output.sonosUid(hub.output.key), hub.roomLive, hub.playing,
                                hub.groupPending, hub.groupFailed, hub.roomVolumes, hub.groupVolume,
                            ),
                        ),
                        hub.rooms.scanning, hub.output,
                        onOpen = { actions.refreshRooms(); vm.outputSheetOpened() },
                        onClose = vm::outputSheetClosed,
                        onPick = { actions.output(it.key); vm.sheet = null },
                        // design 52: a headset only connects (or is made active); the sheet stays open on "Kobler til…"
                        onHeadset = { vm.headsetTapped(it, hub.output.key.takeIf { onRoom }) },
                        onRescan = actions::rescanRooms,
                        // design 53 §2.7, §2.12: "+", "−" and the sliders keep the sheet open
                        onJoin = { actions.roomJoin(it.uid) },
                        onLeave = actions::roomLeave,
                        onVolume = { uid, group, volume, done -> vm.roomVolume(uid, group, volume, done, actions::roomVolume) },
                        onDismiss = { vm.sheet = null },
                    )
                    null -> Unit
                }
            }
            Screen.LIST -> now?.takeIf { it.favourites }?.let { n ->
                // design r12 §3.2: the favourites queue as it plays - always pictured (plain, no number), each row's
                // entry named in its sub-line; a pick plays it inside the queue
                ListScreen(
                    n.entry,
                    n.category,
                    favouritesRows(s, n),
                    summary = null,
                    order = null,
                    pictures = true,
                    onFlip = {},
                    onVisible = { first, last -> vm.listShown(FAVOURITES_CATEGORY, listArtWindow(n.queue.map { it.artHash }, first, last)) },
                    onBack = { vm.screen = Screen.PLAYER },
                    onPick = { item ->
                        vm.switching = favouritePickSwitch(s, n, item, SystemClock.elapsedRealtime(), onRoom)
                        actions.playItem(item.entryId, item.key, inFavourites = n.entryOf(item).key)
                        vm.screen = Screen.PLAYER
                    },
                    onFavourite = { item, on -> vm.setFavourite(n.entryOf(item), item.key, on) },
                    title = stringResource(R.string.category_favourites),
                    entryOf = n::entryOf,
                    categoryOf = n::categoryOf,
                )
            } ?: now?.let { n ->
                val heard = s.positions[n.entry.key].orEmpty().filterValues { it.done }.keys
                val onPhone = s.onPhone[n.entry.id].orEmpty()
                val shown = listOrder(s, n)
                // design 39: a picture per row only where the pictures say something (never a Spotify context). Over
                // the rows shown, while the carousel reads the queue: the same rule, but on a room's speaker-playable
                // queue (or a stored queue that lost items) they may differ when the only other picture is on an item
                // out of the queue - the list shows every row, so it judges every row (qa-39-code N1)
                val pictures = !isSpotifyContext(n.entry) && rowPictures(shown, n.entry.cover)
                ListScreen(
                    n.entry,
                    n.category,
                    listRows(shown, n.item.key, heard, onPhone, s.favourites[n.entry.key].orEmpty().takeIf { !isSpotifyContext(n.entry) }),
                    // Spotify streams only: no "N av M lastet ned", no offline marks (design 26c §4.2)
                    if (isSpotify(n.entry)) null else offlineSummary(s.visible(n.entry).map { it.key }, onPhone),
                    order = listOrderUi(s, n, shown.size),
                    pictures = pictures,
                    onFlip = { vm.flip(n.entry, s.listed[n.entry.id].orEmpty().map { it.key }, actions::flipOrder) },
                    onVisible = { first, last -> vm.listShown(n.entry.id, listArtWindow(shown.map { it.artHash }, first, last)) },
                    onBack = { vm.screen = Screen.PLAYER },
                    onPick = { playItem(n.entry.id, it.key) },
                    onFavourite = { item, on -> vm.setFavourite(n.entry, item.key, on) },
                )
            }
            Screen.NEWS -> NewsScreen(
                newsRows(s),
                onBack = { vm.screen = Screen.LIBRARY },
                onClear = vm::clearNews,
                onPick = { playItem(it.entry.id, it.item.key) },
            )
            Screen.SEARCH -> SearchScreen(
                vm.query,
                search(vm.query, s.entries) { s.order(it) },
                categoryOf = { e -> s.library?.categories?.firstOrNull { it.id == e.category } },
                onQuery = { vm.query = it },
                onBack = { vm.screen = Screen.LIBRARY },
                onPick = { hit ->
                    when (hit) {
                        is SearchHit.EntryHit -> playEntry(hit.entry.id)
                        is SearchHit.ItemHit -> playItem(hit.entry.id, hit.item.key)
                    }
                },
            )
        }
        message?.let { m ->
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Palette.selected).padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                val text = when (m) {
                    PlayerHub.Message.CANT_PLAY -> R.string.cant_play
                    PlayerHub.Message.NOT_DOWNLOADED -> R.string.not_downloaded
                    PlayerHub.Message.CANT_PLAY_ON_SONOS -> R.string.cant_play_on_sonos
                    PlayerHub.Message.ROOM_NOT_ALLOWED -> R.string.room_not_allowed
                    PlayerHub.Message.HEADSET_NO_CONTACT -> R.string.headset_no_contact
                    PlayerHub.Message.STAYED -> null // never set (above)
                }
                text?.let { Line(stringResource(it), 14, 800, Palette.fg, maxLines = 2, center = true) }
            }
        }
    }
}

@Composable
private fun Library(
    vm: MusicViewModel,
    s: Snapshot,
    now: NowView?,
    hub: PlayerHub.State,
    spotifyOutcome: String?,
    actions: Actions,
    playEntry: (Long) -> Unit,
    playFavourites: () -> Unit,
) {
    val all = s.entries
    // design r12 §3.1: "Favoritter" shows while a heart is listed - one tile, every heart of every entry
    val order = s.favouritesOrder()
    val favourites = order.map { it.entryKey }.toSet()
    val selected = effectiveCategory(all, vm.category, favourites)
    val shown = shownEntries(all, selected, favourites)
    // design 50 §1.4: the network as the library (re)appears, at each change and with each snapshot
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(Unit) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { vm.bumpNet() } }
    val onRoom = hub.output.kind == OutputKind.SONOS
    val net = remember(vm.netVersion, s, onRoom) { vm.netNow(onRoom) }
    val inFavourites = selected == FAVOURITES_CATEGORY
    LibraryScreen(
        // a Spotify tile's line: the loaded entry's web player state, else the stored outcome (design 26c §5.8)
        entries = if (inFavourites) emptyList() else shown.map { entryUi(s, it, hub.entryId, hub.rendererState, spotifyOutcome, net) },
        favourites = if (inFavourites) favouritesUi(s, order, net) else null,
        tiles = categoryTiles(s.library?.categories.orEmpty(), all),
        favouritesTile = order.isNotEmpty(),
        selected = selected,
        startIndex = vm.carouselIndex,
        jumps = vm.carouselJumps,
        newsCount = NewNotifier.count(s),
        now = now,
        onBack = actions::backHome,
        onSearch = vm::openSearch,
        onNews = { vm.screen = Screen.NEWS },
        // "Favoritter" filters like every category tile: no tile starts sound (design 50 §1.5, code QA 4)
        onCategory = vm::pickCategory,
        onIndex = vm::turned,
        onPlay = { playEntry(it.entry.id) },
        onPlayFavourites = playFavourites,
        onOpenPlayer = { vm.screen = Screen.PLAYER },
        onTogglePlay = actions::togglePlay,
    )
}

private fun newsRows(s: Snapshot): List<NewsRowUi> = s.news.mapNotNull { n ->
    val entry = s.entries.firstOrNull { it.id == n.entryId } ?: return@mapNotNull null
    val item = s.visible(entry).firstOrNull { it.key == n.key } ?: return@mapNotNull null
    NewsRowUi(entry, s.library?.categories?.firstOrNull { it.id == entry.category }, item, kindOf(entry))
}

/** The position to show: live from the player (ticking while it plays and the screen is up), else the stored one. */
@Composable
private fun livePosition(now: NowView): Long {
    var pos by remember { mutableLongStateOf(now.storedPosMs) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(now.live, now.playing, now.item.key, now.storedPosMs) {
        pos = if (now.live) PlayerHub.positionMs() ?: now.storedPosMs else now.storedPosMs
        if (!now.live) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                pos = PlayerHub.positionMs() ?: now.storedPosMs
                delay(if (now.playing) 500 else 1_000)
            }
        }
    }
    return pos
}
