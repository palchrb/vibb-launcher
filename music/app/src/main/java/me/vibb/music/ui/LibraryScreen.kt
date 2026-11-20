package me.vibb.music.ui

import android.animation.ValueAnimator
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.AnimationSpec
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import me.vibb.music.R
import me.vibb.music.logic.FAVOURITES_CATEGORY
import me.vibb.music.logic.Hint
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.vectorResource
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
        Kind.MUSIC -> R.string.kind_music
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
                Kind.MUSIC -> R.string.hint_track
            },
            kind, hint.n, hint.of,
        )
        is Hint.NotStarted -> stringResource(R.string.hint_continues, kind)
        is Hint.FromStart -> stringResource(R.string.hint_from_start, kind)
        is Hint.Picks -> stringResource(R.string.hint_spotify_picks, kind)
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
    /** Under the "Favoritter" chip (design r12 §3.1): its one tile in the carousel's place; null: [entries]. */
    favourites: FavouritesUi?,
    tiles: List<Category>,
    /** "Favoritter" first after "Alle" (design 50 §1.5): while an entry here holds a favourite. */
    favouritesTile: Boolean,
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
    onPlayFavourites: () -> Unit,
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
        // The weighted slot takes what the header, the tiles and the bar leave; they always keep their own height
        // (design 34 P10 b, QA L1), so the tiles never slide under the bar.
        if (favourites != null) {
            FavouritesCarousel(favourites, now != null, onPlayFavourites, Modifier.weight(1f).fillMaxWidth())
        } else if (entries.isEmpty()) {
            EmptyState(
                Icons.headphones,
                stringResource(R.string.no_library),
                stringResource(R.string.no_library_body),
                Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            key(selected, entries.size, jumps) {
                Carousel(entries, startIndex.coerceIn(entries.indices), now != null, onIndex, onPlay, Modifier.weight(1f).fillMaxWidth())
            }
        }
        if (tiles.isNotEmpty() || favouritesTile) CategoryTiles(tiles, favouritesTile, selected, onCategory)
        if (now != null) NowPlayingBar(now, onOpenPlayer, onTogglePlay)
    }
}

/**
 * The carousel (21 §4.2 "Carousel motion"): a pager over virtual pages that follows the finger
 * and snaps to the nearest cover with a short spring; the neighbours peek 46 dp, their scale and
 * alpha follow the page offset; a tap on a neighbour turns to it; a tap on the cover plays the
 * entry it lands on; with animations off everything jumps.
 *
 * It fills the library's weighted slot (design 34 P10 b, QA L1): the cover is [libraryFit] of the slot's
 * measured height less the title and hint lines measured at the font scale, in whole pixels - at most
 * [carouselCoverMax] of the slot's width (2026-11-20: 68 % of the screen, 180 dp on a narrow phone), at least
 * [LIBRARY_COVER_MIN_DP] (64); below that the slot scrolls. Without the now-playing bar ([hasBar] false) the bar's room
 * ([nowBarRoomPx] at this width's [nowBarSize]) is kept out of the cover's, so the cover doesn't jump when the bar comes
 * or goes (qa-34-s11-code N2); the room the cover at its cap leaves lets the title block breathe ([libraryBreath], up
 * to two thirds of it), the rest centres.
 * The covers' shadow is soft: the centred one keeps v12's 12 dp, a neighbour has P2's 2 dp, in between it follows
 * the page offset (P10 c).
 */
@Composable
private fun Carousel(
    entries: List<EntryUi>,
    startIndex: Int,
    hasBar: Boolean,
    onIndex: (Int) -> Unit,
    onPlay: (EntryUi) -> Unit,
    modifier: Modifier,
) {
    val n = entries.size
    val pager = rememberPagerState(initialPage = carouselStartPage(n, startIndex)) { carouselPageCount(n) }
    val scope = rememberCoroutineScope()
    val snap = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    val currentOnIndex by rememberUpdatedState(onIndex)
    LaunchedEffect(pager, n) {
        snapshotFlow { pager.targetPage }.collect { currentOnIndex(carouselIndex(it, n)) }
    }
    val density = LocalDensity.current
    val lines = measuredLinesPx(21 to 800, 13 to 700)
    val scroll = rememberScrollState()
    BoxWithConstraints(modifier) {
        // the slot is always bounded (a weight); the guard keeps an unbounded caller from throwing
        val slot = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val fit = libraryFit(slot, lines, barReserve(hasBar, maxWidth.value), density.density, carouselCoverMax(maxWidth.value))
        // verticalScroll clips, so only below the floor: above it the centred cover's 12 dp shadow is never cut (N1)
        val scrolling = if (fit.scrolls) Modifier.verticalScroll(scroll) else Modifier
        val fill = if (constraints.hasBoundedHeight) Modifier.heightIn(min = maxHeight) else Modifier
        Column(
            Modifier.fillMaxWidth().then(scrolling).then(fill),
            verticalArrangement = Arrangement.spacedBy(CAROUSEL_GAP_DP.dp, Alignment.CenterVertically),
        ) {
            CarouselPager(entries, pager, fit.coverPx, scope, snap, onPlay)
            CarouselTitle(entries, pager.targetPage, fit.breath)
        }
    }
}

/**
 * The "Favoritter" chip's one tile (design r12 §3.1, the user's decision 1): the chip's heart in peach on the calm card
 * ground - never a mosaic of the hearted covers (it would change with every heart and decode four pictures for one
 * tile) - at the carousel's size, shadow and play mark; "Favoritter" and the count under it. One page: no swipe, and
 * the dots' room kept so nothing moves between the chips. Dimmed as any tile the phone can't play now (design 50 §1.4).
 */
@Composable
private fun FavouritesCarousel(fav: FavouritesUi, hasBar: Boolean, onPlay: () -> Unit, modifier: Modifier) {
    val density = LocalDensity.current
    val lines = measuredLinesPx(21 to 800, 13 to 700)
    val scroll = rememberScrollState()
    val name = stringResource(R.string.category_favourites)
    val label = stringResource(R.string.play_entry, name)
    BoxWithConstraints(modifier) {
        val slot = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE
        val fit = libraryFit(slot, lines, barReserve(hasBar, maxWidth.value), density.density, carouselCoverMax(maxWidth.value))
        val scrolling = if (fit.scrolls) Modifier.verticalScroll(scroll) else Modifier
        val fill = if (constraints.hasBoundedHeight) Modifier.heightIn(min = maxHeight) else Modifier
        val cover = fit.coverPx / density.density
        val shape = RoundedCornerShape(24.dp)
        Column(
            Modifier.fillMaxWidth().then(scrolling).then(fill),
            verticalArrangement = Arrangement.spacedBy(CAROUSEL_GAP_DP.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val box = with(density) { (fit.coverPx + dpToPx(CAROUSEL_BOX_EXTRA_DP, density.density)).toDp() }
            Box(Modifier.fillMaxWidth().height(box), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(cover.dp)
                        .graphicsLayer {
                            alpha = if (fav.dimmed) UNPLAYABLE_ALPHA else 1f
                            shadowElevation = COVER_ELEVATION_DP.dp.toPx()
                            this.shape = shape
                            clip = false
                        }
                        .clip(shape)
                        .background(Brush.linearGradient(listOf(Palette.card, androidx.compose.ui.graphics.lerp(Palette.card, Palette.bg, 0.8f))))
                        .button(label, onPlay),
                    contentAlignment = Alignment.Center,
                ) {
                    Glyph(ImageVector.vectorResource(R.drawable.player_glyph_favorite), carouselGlyph(cover).dp, Palette.peach)
                    if (!fav.dimmed) {
                        Box(Modifier.fillMaxSize().padding(10.dp)) {
                            Box(Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape).background(Palette.peach), contentAlignment = Alignment.Center) {
                                Glyph(Icons.play, 16.dp, Palette.bg)
                            }
                        }
                    }
                }
            }
            val breath = fit.breath
            Column(
                Modifier.fillMaxWidth().padding(top = breath.coverTitlePx.pxAsDp()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(CAROUSEL_TITLE_GAP_DP.dp),
            ) {
                Line(name, 21, 800, Palette.white)
                Line(
                    if (fav.needsWifi) stringResource(R.string.tile_needs_wifi) else pluralStringResource(R.plurals.favourites_count, fav.count, fav.count),
                    13, 700, Palette.sub, Modifier.padding(top = breath.titleHintPx.pxAsDp()),
                )
                // no progress line: its room (and its breath) stays reserved, as under every tile
                Spacer(Modifier.padding(top = breath.hintProgressPx.pxAsDp()).height(with(density) { progressRoomPx(density.density).toDp() }))
            }
            Spacer(Modifier.padding(top = breath.progressDotsPx.pxAsDp()).height(CAROUSEL_DOTS_DP.dp))
        }
    }
}

/**
 * The now-playing bar's room (px) kept out of the cover while there is no bar ([nowBarRoomPx], N2): the bar as it
 * would be at this width ([nowBarSize] of [widthDp], the slot's width - the bar's own), its lines measured at the font
 * scale. 0 while the bar shows: it has its own room then.
 */
@Composable
private fun barReserve(hasBar: Boolean, widthDp: Float): Int {
    val bar = nowBarSize(widthDp)
    val lines = measuredLinesPx(bar.titleSp to 800, bar.lineSp to 700)
    return if (hasBar) 0 else nowBarRoomPx(bar, lines, LocalDensity.current.density)
}

/**
 * Two one-line [Line]s' height together (px) at this font scale, measured as they are drawn: (sp, weight) each.
 * Keyed on the font scale too, though a font-scale change recreates the activity (no `configChanges`): harmless.
 */
@Composable
private fun measuredLinesPx(first: Pair<Int, Int>, second: Pair<Int, Int>): Int {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    return remember(measurer, density.density, density.fontScale, first, second) {
        listOf(first, second).sumOf { (size, weight) ->
            measurer.measure("Ag", text(size.sp, weight, Palette.white), maxLines = 1).size.height
        }
    }
}

@Composable
private fun CarouselPager(
    entries: List<EntryUi>,
    pager: PagerState,
    coverPx: Int,
    scope: CoroutineScope,
    snap: AnimationSpec<Float>,
    onPlay: (EntryUi) -> Unit,
) {
    val n = entries.size
    val density = LocalDensity.current
    val cover = coverPx / density.density
    val pull = with(density) { carouselPull(cover).dp.toPx() }
    val shape = RoundedCornerShape(24.dp)
    val box = with(density) { (coverPx + dpToPx(CAROUSEL_BOX_EXTRA_DP, density.density)).toDp() }
    BoxWithConstraints(Modifier.fillMaxWidth().height(box)) {
        val side = carouselSide(maxWidth.value, cover).dp
        HorizontalPager(
            state = pager,
            modifier = Modifier.fillMaxSize(),
            pageSize = PageSize.Fixed(cover.dp),
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
                e.entry.cover, e.category, 24.dp, carouselGlyph(cover).dp,
                Modifier.size(cover.dp)
                    .graphicsLayer {
                        val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                        val f = abs(offset).coerceIn(0f, 1f)
                        val scale = lerp(1f, 0.733f, f)
                        scaleX = scale
                        scaleY = scale
                        // design 50 §1.4: a tile the phone can't play right now at 60 %, times the neighbour's alpha
                        alpha = lerp(1f, 0.45f, f) * (if (e.unplayableNow) UNPLAYABLE_ALPHA else 1f)
                        // Pull the neighbour in so 46 dp of it shows beside the 180 dp cover (more beside a smaller one).
                        translationX = sign(offset) * pull * f
                        // The soft shadow (P10 c): 12 dp under the centred cover, P2's 2 dp under a neighbour.
                        shadowElevation = lerp(COVER_ELEVATION_DP, NEIGHBOUR_ELEVATION_DP, f).dp.toPx()
                        this.shape = shape
                        clip = false
                    }
                    .button(label) {
                        when (val tap = carouselTap(page, pager.targetPage, pager.currentPage, n, pager.pageCount)) {
                            is CarouselTap.Play -> onPlay(entries[tap.index])
                            is CarouselTap.TurnTo -> scope.launch {
                                if (animationsOn()) pager.animateScrollToPage(tap.page, animationSpec = snap) else pager.scrollToPage(tap.page)
                            }
                        }
                    },
            ) {
                CoverBadges(e, coverShowsKind(cover))
            }
        }
    }
}

/**
 * The title, the hint (+ progress) and the dots under the pager; the progress line's room is always kept. [breath] is
 * the room the 180 dp cover leaves, given to the gaps ([libraryBreath], 2026-11-20): the same on every page, so nothing
 * moves on a turn.
 */
@Composable
private fun CarouselTitle(entries: List<EntryUi>, targetPage: Int, breath: LibraryBreath) {
    val n = entries.size
    val index = carouselIndex(targetPage, n)
    val current = entries[index]
    val density = LocalDensity.current
    // Keyed on the entry, so a position save (the progress line) doesn't fade the title (L13).
    Crossfade(
        current.entry.id, Modifier.padding(top = breath.coverTitlePx.pxAsDp()), animationSpec = tween(if (animationsOn()) 200 else 0), label = "title",
    ) { id ->
        val e = entries.firstOrNull { it.entry.id == id } ?: current
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(CAROUSEL_TITLE_GAP_DP.dp)) {
            Line(e.entry.name, 21, 800, Palette.white)
            // a Spotify tile's line replaces the hint (design 26c §5.8): the loaded one's state, the stored outcome; a
            // tile the network refuses says "Trenger Wi-Fi" there (design 50 §1.4) - one line, the account line first
            val line = e.state?.let { stringResource(rendererStateText(it)) }
                ?: if (e.needsWifi) stringResource(R.string.tile_needs_wifi) else hintText(e.hint)
            Line(line, 13, 700, Palette.sub, Modifier.padding(top = breath.titleHintPx.pxAsDp()))
            // without a progress line its 3 + 3 + 4 dp (and its breath) stay reserved, so the cover never resizes on a turn
            val progressBreath = Modifier.padding(top = breath.hintProgressPx.pxAsDp())
            if (e.progress != null) {
                Box(progressBreath.padding(top = CAROUSEL_PROGRESS_PAD_DP.dp).width(140.dp).height(CAROUSEL_PROGRESS_DP.dp).clip(RoundedCornerShape(2.dp)).background(Palette.track)) {
                    Box(Modifier.width(140.dp * e.progress).height(4.dp).background(Palette.peach))
                }
            } else {
                Spacer(progressBreath.height(with(density) { progressRoomPx(density.density).toDp() }))
            }
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = breath.progressDotsPx.pxAsDp()), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        for (i in dotWindow(n, index)) {
            Box(Modifier.size(CAROUSEL_DOTS_DP.dp).clip(CircleShape).background(if (i == index) Palette.peach else Palette.dot))
        }
    }
}

/** Whole pixels as a Dp; Compose rounds each padding back to exactly those pixels on its own ([libraryBreath]). */
@Composable
private fun Int.pxAsDp(): Dp = with(LocalDensity.current) { this@pxAsDp.toDp() }

/** The centred cover's shadow (v12) and a neighbour's (design 34 P2's quiet depth), dp. */
private const val COVER_ELEVATION_DP = 12f
private const val NEIGHBOUR_ELEVATION_DP = 2f

/** A dimmed tile's cover (design 50 §1.4). */
const val UNPLAYABLE_ALPHA = 0.6f

/**
 * The cover's badges: the kind chip ([kind]: not on a small cover, N6), NY, and the play mark - on every tile, "tap
 * to play" (design 46 §1.4, the user's call, not vibb's started-only mark), never on a dimmed one (design 50 §1.4).
 */
@Composable
private fun CoverBadges(e: EntryUi, kind: Boolean) {
    Box(Modifier.fillMaxSize().padding(10.dp)) {
        if (kind) {
            Box(Modifier.align(Alignment.BottomStart).clip(RoundedCornerShape(11.dp)).background(Palette.chip).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Line(kindText(e.kind), 11, 800, Palette.fg)
            }
        }
        if (e.isNew) {
            Box(Modifier.align(Alignment.TopStart).clip(RoundedCornerShape(11.dp)).background(Palette.peach).padding(horizontal = 9.dp, vertical = 3.dp)) {
                Line(stringResource(R.string.new_badge), 11, 800, Palette.bg)
            }
        }
        if (showsPlayMark(e)) {
            Box(Modifier.align(Alignment.TopEnd).size(32.dp).clip(CircleShape).background(Palette.peach), contentAlignment = Alignment.Center) {
                Glyph(Icons.play, 16.dp, Palette.bg)
            }
        }
    }
}

/**
 * Alle + the categories with entries here; single choice. The row fits its tiles to its width ([categoryRow],
 * 2026-11-20: five tiles cut "Podkast" to "Podkas" on the Jelly Star): each as wide as its label needs at the font
 * scale (N6), a fifth of the row where that fits, else closer together with smaller circles. When even that can't hold
 * them (a large font) the row scrolls, snapping to a tile's start; a tile the edge cuts is hidden at rest and a quiet
 * chevron in the screen's side says more lie that way - a label is never shown cut.
 */
@Composable
private fun CategoryTiles(tiles: List<Category>, favouritesTile: Boolean, selected: Long?, onCategory: (Long?) -> Unit) {
    val label = stringResource(R.string.categories)
    val all = stringResource(R.string.category_all)
    val favourites = stringResource(R.string.category_favourites)
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    // each label as the tile draws it (ExtraBold, one line; 11 sp and the compact 10), its natural width (dp) at this font scale - measured
    // once per set of names and font scale, not at every composition of the row (qa-26c-emu-music-code N2)
    val names = listOf(all) + listOfNotNull(favourites.takeIf { favouritesTile }) + tiles.map { it.name }
    val labelDp = remember(measurer, density.density, density.fontScale, names) {
        listOf(CATEGORY_LABEL_SP, CATEGORY_LABEL_COMPACT_SP).associateWith { sp ->
            names.map { name -> measurer.measure(name, text(sp.sp, 800, Palette.white), maxLines = 1).size.width / density.density }
        }
    }
    val list = rememberLazyListState()
    val snapping = rememberSnapFlingBehavior(list, SnapPosition.Start)
    val plain = ScrollableDefaults.flingBehavior()
    // a tile the edge cuts fades out while the row moves and is gone once it rests (never a cut label at rest)
    val rest by animateFloatAsState(if (list.isScrollInProgress) 0f else 1f, tween(if (animationsOn()) 150 else 0), label = "rest")
    BoxWithConstraints(Modifier.fillMaxWidth().semantics { contentDescription = label }) {
        val row = categoryRow(maxWidth.value, labelDp.getValue(CATEGORY_LABEL_SP), labelDp.getValue(CATEGORY_LABEL_COMPACT_SP))
        val look = TileLook(row.circle.dp, row.glyph.dp, row.labelSp)
        val fadePx = with(density) { CATEGORY_CUT_FADE_DP.dp.toPx() }
        fun Modifier.whole(index: Int): Modifier = if (!row.scrolls) this else graphicsLayer {
            val view = list.layoutInfo
            val tile = view.visibleItemsInfo.firstOrNull { it.index == index }
            alpha = if (tile == null) 1f else categoryTileAlpha(tile.offset, tile.offset + tile.size, view.viewportStartOffset, view.viewportEndOffset, fadePx, rest)
        }
        LazyRow(
            state = list,
            horizontalArrangement = Arrangement.spacedBy(row.gap.dp),
            userScrollEnabled = row.scrolls,
            flingBehavior = if (row.scrolls) snapping else plain,
        ) {
            item { CategoryTile(null, selected == null, row.widths[0].dp, look, Modifier.whole(0)) { onCategory(null) } }
            // design 50 §1.5: the sentinel id, first after Alle
            if (favouritesTile) {
                item {
                    CategoryTile(null, selected == FAVOURITES_CATEGORY, row.widths[1].dp, look, Modifier.whole(1), favourites = true) {
                        onCategory(FAVOURITES_CATEGORY)
                    }
                }
            }
            val first = if (favouritesTile) 2 else 1
            itemsIndexed(tiles, key = { _, c -> c.id }) { i, c ->
                CategoryTile(c, selected == c.id, row.widths[first + i].dp, look, Modifier.whole(first + i)) { onCategory(c.id) }
            }
        }
        if (row.scrolls) {
            // in the screen's 16 dp side, level with the circles: quiet, never over a tile
            val y = (row.circle - CATEGORY_CHEVRON_DP) / 2
            if (list.canScrollBackward) {
                Glyph(Icons.back, CATEGORY_CHEVRON_DP.dp, Palette.dim, Modifier.align(Alignment.TopStart).offset(x = (-CATEGORY_CHEVRON_OUT_DP).dp, y = y.dp))
            }
            if (list.canScrollForward) {
                Glyph(Icons.forward, CATEGORY_CHEVRON_DP.dp, Palette.dim, Modifier.align(Alignment.TopEnd).offset(x = CATEGORY_CHEVRON_OUT_DP.dp, y = y.dp))
            }
        }
    }
}

/** A category tile's circle, glyph and label size ([categoryRow]: smaller in a compact row). */
private data class TileLook(val circle: Dp, val glyph: Dp, val labelSp: Int)

/** The scrolling row's chevron (dp) and how far into the screen's 16 dp side it sits (its far edge 2 dp from the screen's). */
private const val CATEGORY_CHEVRON_DP = 12f
private const val CATEGORY_CHEVRON_OUT_DP = 14f

@Composable
private fun CategoryTile(
    category: Category?,
    on: Boolean,
    width: Dp,
    look: TileLook,
    modifier: Modifier,
    favourites: Boolean = false,
    onClick: () -> Unit,
) {
    val label = category?.name ?: stringResource(if (favourites) R.string.category_favourites else R.string.category_all)
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
        modifier.width(width).heightIn(min = 64.dp).semantics { selected = on }.button(label, onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            Modifier.size(look.circle).clip(CircleShape).background(bg)
                .border(if (on) 3.dp else 1.dp, if (on) Palette.fg else Palette.ring, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (favourites) {
                Glyph(ImageVector.vectorResource(R.drawable.player_glyph_favorite), look.glyph, fg)
            } else if (category == null) {
                Glyph(Icons.grid, look.glyph, fg)
            } else {
                Image(painterResource(categoryGlyph(category)), null, Modifier.size(look.glyph), colorFilter = ColorFilter.tint(fg))
            }
        }
        Line(label, look.labelSp, 800, if (on) Palette.white else Palette.dim)
    }
}

/**
 * The now-playing bar: what plays (or played, paused after a restart) and play/pause, and a thin peach line along its
 * bottom edge for how far the item has come (2026-11-20, the approved mockup; the player's own position, live while it
 * plays, [livePosition]). Its sizes grow with the width ([nowBarSize]: 72 dp tall on the Jelly Star, 80 on a 411 dp
 * phone); [nowBarRoomPx] is its room, kept out of the cover while it is away.
 */
@Composable
fun NowPlayingBar(now: NowView, onOpen: () -> Unit, onTogglePlay: () -> Unit) {
    val progress = nowBarProgress(livePosition(now), now.durationMs)
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val bar = nowBarSize(maxWidth.value)
        // P2's quiet depth (design 34 P10 c): 2 dp of soft shadow lifts the bar off the night ground; the shadow clips
        // to its shape, so the progress line follows the bar's rounded corners
        Box(Modifier.fillMaxWidth().shadow(BAR_ELEVATION_DP.dp, RoundedCornerShape(bar.radius.dp)).background(Palette.card)) {
            Row(
                // the cover's padding, giving way to lines taller than the cover down to NOW_BAR_PAD_MIN_DP ([nowBarRoomPx])
                Modifier.fillMaxWidth().heightIn(min = bar.height.dp).padding(horizontal = bar.padH.dp, vertical = NOW_BAR_PAD_MIN_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(bar.gap.dp),
            ) {
                Row(
                    Modifier.weight(1f).button(stringResource(R.string.open_player), onOpen),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(bar.gap.dp),
                ) {
                    Cover(now.cover, now.category, bar.coverRadius.dp, 0.dp, Modifier.size(bar.cover.dp), fallback = now.fallback)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(NOW_BAR_LINE_GAP_DP.dp)) {
                        Line(now.itemTitle, bar.titleSp, 800, Palette.white)
                        Line(now.entry.name, bar.lineSp, 700, Palette.sub)
                    }
                }
                RoundButton(
                    if (now.playing) Icons.pause else Icons.play,
                    stringResource(if (now.playing) R.string.pause else R.string.play),
                    onTogglePlay, bar.play.dp, bar.playGlyph.dp, Palette.peach, Palette.bg,
                )
            }
            if (progress != null) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth(progress).height(NOW_BAR_PROGRESS_DP.dp).background(Palette.peach))
            }
        }
    }
}

/** The now-playing bar's shadow (dp): P2's. */
const val BAR_ELEVATION_DP = 2
