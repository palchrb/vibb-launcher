package me.vibb.music.ui

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import me.vibb.music.R
import me.vibb.music.logic.Hint
import me.vibb.music.logic.Kind
import me.vibb.music.logic.carouselIndex
import me.vibb.music.logic.carouselPageCount
import me.vibb.music.logic.carouselStartPage
import me.vibb.music.logic.CarouselTap
import me.vibb.music.logic.carouselTap
import me.vibb.music.model.Category
import kotlin.math.abs
import kotlin.math.sign

@Composable
fun kindText(kind: Kind): String = stringResource(
    when (kind) {
        Kind.OWN -> R.string.kind_own
        Kind.AUDIOBOOK -> R.string.kind_audiobook
        Kind.PODCAST -> R.string.kind_podcast
    },
)

@Composable
fun hintText(hint: Hint): String {
    val kind = kindText(hint.kind)
    return when (hint) {
        is Hint.Started -> stringResource(
            when (hint.kind) {
                Kind.OWN -> R.string.hint_track
                Kind.AUDIOBOOK -> R.string.hint_part
                Kind.PODCAST -> R.string.hint_episode
            },
            kind, hint.n, hint.of,
        )
        is Hint.NotStarted -> stringResource(R.string.hint_continues, kind)
        is Hint.FromStart -> stringResource(R.string.hint_from_start, kind)
    }
}

/** Animations are off when the animator duration scale is 0 ("Remove animations"). */
fun animationsOn(): Boolean = ValueAnimator.areAnimatorsEnabled()

/**
 * The library (design 21 §4.2, `Main.dc.html` v12 `onLibrary`): header, the carousel midway,
 * title + hint (+ progress), equal dots, category tiles, the now-playing bar.
 */
@Composable
fun LibraryScreen(
    entries: List<EntryUi>,
    tiles: List<Category>,
    selected: Long?,
    startIndex: Int,
    /** Changes when the carousel is turned from outside, so it re-centres on [startIndex]. */
    jumps: Int,
    newsCount: Int,
    now: NowView?,
    onBack: () -> Unit,
    onSearch: () -> Unit,
    onNews: () -> Unit,
    onCategory: (Long?) -> Unit,
    onIndex: (Int) -> Unit,
    onPlay: (EntryUi) -> Unit,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundButton(Icons.back, stringResource(R.string.back_home), onBack)
            Spacer(Modifier.weight(1f))
            RoundButton(Icons.search, stringResource(R.string.search), onSearch)
            Spacer(Modifier.width(8.dp))
            Box {
                RoundButton(Icons.bell, stringResource(R.string.news_count, newsCount), onNews)
                if (newsCount > 0) {
                    Box(
                        Modifier.align(Alignment.TopEnd).offset(x = 2.dp, y = (-2).dp).widthIn(min = 20.dp).height(20.dp)
                            .clip(RoundedCornerShape(10.dp)).background(Palette.peach).padding(horizontal = 5.dp),
                        contentAlignment = Alignment.Center,
                    ) { Line(newsCount.toString(), 12, 800, Palette.bg) }
                }
            }
        }
        Spacer(Modifier.weight(1f))
        if (entries.isEmpty()) {
            Line(stringResource(R.string.no_library), 15, 700, Palette.dim, Modifier.fillMaxWidth(), maxLines = 2, center = true)
        } else {
            key(selected, entries.size, jumps) {
                Carousel(entries, startIndex.coerceIn(entries.indices), onIndex, onPlay)
            }
        }
        Spacer(Modifier.weight(1f))
        if (tiles.isNotEmpty()) CategoryTiles(tiles, selected, onCategory)
        if (now != null) NowPlayingBar(now, onOpenPlayer, onTogglePlay)
    }
}

/**
 * The carousel (21 §4.2 "Carousel motion"): a pager over virtual pages that follows the finger
 * and snaps to the nearest cover with a short spring; the neighbours peek 46 dp, their scale and
 * alpha follow the page offset; a tap on a neighbour turns to it; a tap on the cover plays the
 * entry it lands on; with animations off everything jumps.
 */
@Composable
private fun Carousel(entries: List<EntryUi>, startIndex: Int, onIndex: (Int) -> Unit, onPlay: (EntryUi) -> Unit) {
    val n = entries.size
    val pager = rememberPagerState(initialPage = carouselStartPage(n, startIndex)) { carouselPageCount(n) }
    val scope = rememberCoroutineScope()
    val snap = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    val currentOnIndex by rememberUpdatedState(onIndex)
    LaunchedEffect(pager, n) {
        snapshotFlow { pager.targetPage }.collect { currentOnIndex(carouselIndex(it, n)) }
    }
    val pull = with(LocalDensity.current) { 24.dp.toPx() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(184.dp)) {
        val side = carouselSide(maxWidth.value).dp
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSize = PageSize.Fixed(CAROUSEL_COVER_DP.dp),
            contentPadding = PaddingValues(horizontal = side),
            pageSpacing = CAROUSEL_SPACING_DP.dp,
            beyondViewportPageCount = 1,
            userScrollEnabled = n > 1,
            flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = snap),
            verticalAlignment = Alignment.CenterVertically,
            key = { it },
        ) { page ->
            val e = entries[carouselIndex(page, n)]
            val label = if (page == pager.targetPage) {
                stringResource(R.string.play_entry, e.entry.name)
            } else {
                stringResource(if (page < pager.targetPage) R.string.previous_entry else R.string.next_entry, e.entry.name)
            }
            Cover(
                e.entry.cover, e.category, 24.dp, 84.dp,
                Modifier.size(180.dp)
                    .graphicsLayer {
                        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        val f = abs(offset).coerceIn(0f, 1f)
                        val scale = lerp(1f, 0.733f, f)
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.45f, f)
                        // Pull the neighbour in so 46 dp of it shows beside the 180 dp cover.
                        translationX = sign(offset) * pull * f
                    }
                    .shadow(if (page == pager.currentPage) 12.dp else 0.dp, RoundedCornerShape(24.dp))
                    .button(label) {
                        when (val tap = carouselTap(page, pager.targetPage, pager.currentPage, n, pager.pageCount)) {
                            is CarouselTap.Play -> onPlay(entries[tap.index])
                            is CarouselTap.TurnTo -> scope.launch {
                                if (animationsOn()) pager.animateScrollToPage(tap.page, animationSpec = snap) else pager.scrollToPage(tap.page)
                            }
                        }
                    },
            ) {
                CoverBadges(e)
            }
        }
    }
    val index = carouselIndex(pager.targetPage, n)
    val current = entries[index]
    // Keyed on the entry, so a position save (the progress line) doesn't fade the title (L13).
    Crossfade(current.entry.id, animationSpec = tween(if (animationsOn()) 200 else 0), label = "title") { id ->
        val e = entries.firstOrNull { it.entry.id == id } ?: current
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Line(e.entry.name, 21, 800, Palette.white)
            Line(hintText(e.hint), 13, 700, Palette.sub)
            if (e.progress != null) {
                Box(Modifier.padding(top = 3.dp).width(140.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Palette.track)) {
                    Box(Modifier.width(140.dp * e.progress).height(4.dp).background(Palette.peach))
                }
            }
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        for (i in dotWindow(n, index)) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(if (i == index) Palette.peach else Palette.dot))
        }
    }
}

@Composable
private fun CoverBadges(e: EntryUi) {
    Box(Modifier.fillMaxSize().padding(10.dp)) {
        Box(Modifier.align(Alignment.BottomStart).clip(RoundedCornerShape(11.dp)).background(Palette.chip).padding(horizontal = 9.dp, vertical = 3.dp)) {
            Line(kindText(e.kind), 11, 800, Palette.fg)
        }
        if (e.isNew) {
            Box(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(11.dp)).background(Palette.peach).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Line(stringResource(R.string.new_badge), 11, 800, Palette.bg)
            }
        }
        if (e.progress != null) {
            Box(Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape).background(Palette.peach), contentAlignment = Alignment.Center) {
                Glyph(Icons.play, 16.dp, Palette.bg)
            }
        }
    }
}

/** Alle + the categories with entries here; single choice; more than four scroll. */
@Composable
private fun CategoryTiles(tiles: List<Category>, selected: Long?, onCategory: (Long?) -> Unit) {
    val label = stringResource(R.string.categories)
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        val tileWidth = categoryTileWidth(maxWidth.value).dp
        LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            item { CategoryTile(null, selected == null, tileWidth) { onCategory(null) } }
            items(tiles, key = { it.id }) { c -> CategoryTile(c, selected == c.id, tileWidth) { onCategory(c.id) } }
        }
    }
}

@Composable
private fun CategoryTile(category: Category?, on: Boolean, width: Dp, onClick: () -> Unit) {
    val label = category?.name ?: stringResource(R.string.category_all)
    val bg = when {
        !on -> Palette.card
        category == null -> Palette.peach
        else -> categoryColor(category)
    }
    val fg = when {
        !on -> Palette.tileFg
        category == null -> Palette.bg
        else -> Palette.white
    }
    Column(
        Modifier.width(width).heightIn(min = 64.dp).semantics { selected = on }.button(label, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(bg)
                .border(if (on) 3.dp else 1.dp, if (on) Palette.fg else Palette.ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (category == null) {
                Glyph(Icons.grid, 20.dp, fg)
            } else {
                Image(painterResource(categoryGlyph(category)), null, Modifier.size(20.dp), colorFilter = ColorFilter.tint(fg))
            }
        }
        Line(label, 11, 800, if (on) Palette.white else Palette.dim)
    }
}

/** The now-playing bar: what plays (or played, paused after a restart) and play/pause. */
@Composable
fun NowPlayingBar(now: NowView, onOpen: () -> Unit, onTogglePlay: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Palette.card).padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier.weight(1f).button(stringResource(R.string.open_player), onOpen),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Cover(now.cover, now.category, 10.dp, 0.dp, Modifier.size(36.dp))
            Column(Modifier.weight(1f)) {
                Line(now.itemTitle, 13, 800, Palette.white)
                Line(now.entry.name, 11, 700, Palette.sub)
            }
        }
        RoundButton(
            if (now.playing) Icons.pause else Icons.play,
            stringResource(if (now.playing) R.string.pause else R.string.play),
            onTogglePlay, 44.dp, 18.dp, Palette.peach, Palette.bg,
        )
    }
}
