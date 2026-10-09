package me.vibb.music.ui

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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import me.vibb.music.R
import me.vibb.music.data.Snapshot
import me.vibb.music.logic.SearchHit
import me.vibb.music.logic.categoryTiles
import me.vibb.music.logic.effectiveCategory
import me.vibb.music.logic.kindOf
import me.vibb.music.logic.listRows
import me.vibb.music.logic.offlineSummary
import me.vibb.music.logic.search
import me.vibb.music.logic.shownEntries
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sync.NewNotifier

/** The screens of `Main.dc.html` v12, drawn edge to edge with the system bars' insets applied once. */
@Composable
fun MusicRoot(vm: MusicViewModel, actions: Actions) {
    val snap by vm.snapshot.collectAsStateWithLifecycle()
    val hub by PlayerHub.state.collectAsStateWithLifecycle()
    val row by vm.nowRow.collectAsStateWithLifecycle()
    var message by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        PlayerHub.messages.collect {
            message = true
            delay(3_000)
            message = false
        }
    }
    Box(Modifier.fillMaxSize().background(Palette.bg).windowInsetsPadding(WindowInsets.safeDrawing)) {
        val s = snap ?: return@Box
        val now = nowView(s, hub, row)
        val screen = if (now == null && vm.screen in setOf(Screen.PLAYER, Screen.LIST)) Screen.LIBRARY else vm.screen
        BackHandler(enabled = vm.sheet != null || screen != Screen.LIBRARY) {
            when {
                vm.sheet != null -> vm.sheet = null
                screen == Screen.LIST -> vm.screen = Screen.PLAYER
                else -> vm.screen = Screen.LIBRARY
            }
        }
        val playEntry = { id: Long -> actions.playEntry(id); vm.screen = Screen.PLAYER }
        val playItem = { id: Long, key: String -> actions.playItem(id, key); vm.screen = Screen.PLAYER }
        when (screen) {
            Screen.LIBRARY -> Library(vm, s, now, actions, playEntry)
            Screen.PLAYER -> now?.let { n ->
                val order = listOrder(s, n)
                PlayerScreen(
                    n, order.indexOfFirst { it.key == n.item.key } + 1, order.size, livePosition(n), hub.sleepMinutes, hub.output,
                    onBack = { vm.screen = Screen.LIBRARY },
                    onSeek = actions::seekTo,
                    onShuffle = actions::toggleShuffle,
                    onPrevious = actions::previous,
                    onTogglePlay = actions::togglePlay,
                    onNext = actions::next,
                    onList = { vm.screen = Screen.LIST },
                    onSleep = { vm.sheet = SheetKind.SLEEP },
                    onOutput = { vm.sheet = SheetKind.OUTPUT },
                )
                when (vm.sheet) {
                    SheetKind.SLEEP -> SleepSheet(hub.sleepMinutes, { actions.sleep(it); vm.sheet = null }, { vm.sheet = null })
                    SheetKind.OUTPUT -> OutputSheet(hub.outputs, hub.output, { actions.output(it.id); vm.sheet = null }, { vm.sheet = null })
                    null -> Unit
                }
            }
            Screen.LIST -> now?.let { n ->
                val heard = s.positions[n.entry.key].orEmpty().filterValues { it.done }.keys
                val onPhone = s.onPhone[n.entry.id].orEmpty()
                ListScreen(
                    n.entry,
                    listRows(listOrder(s, n), n.item.key, heard, onPhone),
                    offlineSummary(s.visible(n.entry).map { it.key }, onPhone),
                    onBack = { vm.screen = Screen.PLAYER },
                    onPick = { playItem(n.entry.id, it.key) },
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
        if (message) {
            Box(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp, start = 16.dp, end = 16.dp)
                    .clip(RoundedCornerShape(16.dp)).background(Palette.selected).padding(horizontal = 14.dp, vertical = 10.dp),
            ) { Line(stringResource(R.string.not_downloaded), 14, 800, Palette.fg, maxLines = 2, center = true) }
        }
    }
}

@Composable
private fun Library(vm: MusicViewModel, s: Snapshot, now: NowView?, actions: Actions, playEntry: (Long) -> Unit) {
    val all = s.entries
    val selected = effectiveCategory(all, vm.category)
    val shown = shownEntries(all, selected)
    LibraryScreen(
        entries = shown.map { entryUi(s, it) },
        tiles = categoryTiles(s.library?.categories.orEmpty(), all),
        selected = selected,
        startIndex = vm.carouselIndex,
        newsCount = NewNotifier.count(s),
        now = now,
        onBack = actions::backHome,
        onSearch = vm::openSearch,
        onNews = { vm.screen = Screen.NEWS },
        onCategory = vm::pickCategory,
        onIndex = { vm.carouselIndex = it },
        onPlay = { playEntry(it.entry.id) },
        onOpenPlayer = { vm.screen = Screen.PLAYER },
        onTogglePlay = actions::togglePlay,
    )
}

private fun newsRows(s: Snapshot): List<NewsRowUi> = s.news.mapNotNull { n ->
    val entry = s.entries.firstOrNull { it.id == n.entryId } ?: return@mapNotNull null
    val item = s.visible(entry).firstOrNull { it.key == n.key } ?: return@mapNotNull null
    NewsRowUi(entry, s.library?.categories?.firstOrNull { it.id == entry.category }, item, kindOf(entry))
}

/** The position to show: live from the player (ticking while it plays), else the stored one. */
@Composable
private fun livePosition(now: NowView): Long {
    var pos by remember { mutableLongStateOf(now.storedPosMs) }
    LaunchedEffect(now.live, now.playing, now.item.key) {
        while (true) {
            pos = if (now.live) PlayerHub.positionMs() ?: now.storedPosMs else now.storedPosMs
            if (!now.live) break
            delay(if (now.playing) 500 else 1_000)
        }
    }
    return pos
}
