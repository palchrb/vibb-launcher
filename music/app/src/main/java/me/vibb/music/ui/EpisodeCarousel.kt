package me.vibb.music.ui

import android.os.SystemClock
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import me.vibb.music.R
import me.vibb.music.logic.CarouselTap
import me.vibb.music.logic.PagerFollow
import me.vibb.music.logic.SWITCH_HOLD_MS
import me.vibb.music.logic.carouselIndex
import me.vibb.music.logic.carouselPageCount
import me.vibb.music.logic.carouselStartPage
import me.vibb.music.logic.carouselTap
import me.vibb.music.logic.pagerFollow
import me.vibb.music.logic.swipeSkip
import kotlin.math.abs
import kotlin.math.sign

/**
 * The player's picture (design 27 §3): the Now Playing carousel when [NowView.carousel] holds and
 * the box has room for it ([playerCarousel]), else today's single cover. [onLanding] gets the queue
 * index of the page the carousel is landing on (null for the single cover), for the title and
 * "Episode N av M" under it.
 */
@Composable
fun PlayerArt(
    now: NowView,
    modifier: Modifier,
    onSkip: (index: Int, forward: Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onLanding: (Int?) -> Unit,
) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        if (playerCarousel(maxWidth.value, maxHeight.value, now.carousel)) {
            // A new queue (a cover tap, a pick, the shuffle toggle, an item dropped by a listing) starts
            // the pager over on the current item (QA M7 b).
            key(now.pagerKey) {
                EpisodeCarousel(now, maxWidth.value, playerArt(maxWidth.value, maxHeight.value, true), onSkip, onTogglePlay, onLanding)
            }
        } else {
            LaunchedEffect(Unit) { onLanding(null) }
            // Next and previous show their cover on the first frame (an own file's art differs per track).
            PrefetchCovers(listOf(now.index + 1, now.index - 1).mapNotNull { i -> now.queue.getOrNull(Math.floorMod(i, now.queue.size))?.artHash }.distinct())
            Cover(
                now.cover, now.category, 26.dp, 104.dp,
                Modifier.sizeIn(maxWidth = PLAYER_ART_MAX_DP.dp, maxHeight = PLAYER_ART_MAX_DP.dp).aspectRatio(1f).shadow(12.dp, RoundedCornerShape(26.dp)),
                fallback = now.fallback,
            )
        }
    }
}

/**
 * The carousel over the queue in its order (design 27 §3.2): virtual pages that wrap like previous
 * and next, the library carousel's motion (the finger followed, a no-bounce spring snap; neighbours
 * scaled to 0.733 and faded to 0.45, pulled in so their near edge stays put; everything jumps with
 * animations off; no gesture-exclusion rects, so the screen edges stay the system's back gesture).
 * Where it settles after a drag, a fling or a TalkBack scroll is one skip there ([swipeSkip]); a tap
 * on a neighbour turns to it (and so skips); a tap on the centre is play/pause. It follows the
 * player ([pagerFollow]) except while it moves or its own skip is pending.
 */
@Composable
private fun EpisodeCarousel(
    now: NowView,
    widthDp: Float,
    artDp: Float,
    onSkip: (index: Int, forward: Boolean) -> Unit,
    onTogglePlay: () -> Unit,
    onLanding: (Int?) -> Unit,
) {
    val n = now.queue.size
    val pager = rememberPagerState(initialPage = carouselStartPage(n, now.index)) { carouselPageCount(n) }
    val scope = rememberCoroutineScope()
    val snap = spring<Float>(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 700f)
    val shown by rememberUpdatedState(now.index)
    val skip by rememberUpdatedState(onSkip)
    val landing by rememberUpdatedState(onLanding)
    // The page this carousel scrolls to itself (following the player): its settle is no swipe.
    val own = remember { intArrayOf(-1) }
    // The index shown when it asked for a skip, and when: the pager isn't pulled back while that shows.
    val hold = remember { longArrayOf(-1, 0) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(pager, n) {
        snapshotFlow { pager.targetPage }.collect { landing(carouselIndex(it, n)) }
    }
    LaunchedEffect(pager, n) {
        var from = pager.settledPage
        snapshotFlow { Triple(pager.isScrollInProgress, pager.settledPage, shown to tick) }.collect { (scrolling, settled, state) ->
            val shownIndex = state.first
            if (scrolling) return@collect
            if (settled != from) {
                val start = from
                from = settled
                val ours = own[0] == settled
                own[0] = -1
                val s = if (ours) null else swipeSkip(start, settled, n, shownIndex)
                if (s != null) {
                    hold[0] = shownIndex.toLong()
                    hold[1] = SystemClock.elapsedRealtime()
                    skip(s.index, s.forward)
                    scope.launch {
                        delay(SWITCH_HOLD_MS)
                        tick++
                    }
                    return@collect
                }
            }
            // Once something else shows (the target, or wherever the player went), the hold is over.
            if (shownIndex.toLong() != hold[0]) hold[0] = -1
            val holding = hold[0].toInt().takeIf { it >= 0 && SystemClock.elapsedRealtime() - hold[1] < SWITCH_HOLD_MS }
            when (val f = pagerFollow(pager.currentPage, shownIndex, n, scrolling = false, holdFrom = holding)) {
                is PagerFollow.Animate -> {
                    own[0] = f.page
                    scope.launch { if (animationsOn()) pager.animateScrollToPage(f.page, animationSpec = snap) else pager.scrollToPage(f.page) }
                }
                is PagerFollow.Jump -> {
                    own[0] = f.page
                    scope.launch { pager.scrollToPage(f.page) }
                }
                PagerFollow.Stay -> Unit
            }
        }
    }

    val pull = with(LocalDensity.current) { playerPull(artDp).dp.toPx() }
    val title = { i: Int -> now.queue[carouselIndex(i, n)].title }
    HorizontalPager(
        state = pager,
        modifier = Modifier.fillMaxWidth().height(artDp.dp),
        pageSize = PageSize.Fixed(artDp.dp),
        contentPadding = PaddingValues(horizontal = ((widthDp - artDp) / 2).coerceAtLeast(0f).dp),
        pageSpacing = PLAYER_PAGE_GAP_DP.dp,
        beyondViewportPageCount = 1,
        flingBehavior = PagerDefaults.flingBehavior(state = pager, snapAnimationSpec = snap),
        verticalAlignment = Alignment.CenterVertically,
        key = { it },
    ) { page ->
        val item = now.queue[carouselIndex(page, n)]
        val label = when {
            page == pager.targetPage -> stringResource(if (now.playing) R.string.pause_item else R.string.play_item, title(page))
            page < pager.targetPage -> stringResource(R.string.previous_entry, title(page))
            else -> stringResource(R.string.next_entry, title(page))
        }
        Cover(
            item.artHash ?: now.entry.cover, now.category, 26.dp, 104.dp,
            Modifier.size(artDp.dp)
                .graphicsLayer {
                    val offset = (pager.currentPage - page) + pager.currentPageOffsetFraction
                    val f = abs(offset).coerceIn(0f, 1f)
                    val scale = lerp(1f, NEIGHBOUR_SCALE, f)
                    scaleX = scale
                    scaleY = scale
                    // Beyond the neighbours nothing shows: a short box would show a whole page there (QA L2 b).
                    alpha = if (abs(offset) <= 1f) lerp(1f, 0.45f, f) else lerp(0.45f, 0f, (abs(offset) - 1f).coerceIn(0f, 1f))
                    translationX = sign(offset) * pull * f
                }
                .shadow(if (page == pager.currentPage) 12.dp else 0.dp, RoundedCornerShape(26.dp))
                .button(label) {
                    when (val tap = carouselTap(page, pager.targetPage, pager.currentPage, n, pager.pageCount)) {
                        is CarouselTap.Play -> onTogglePlay()
                        is CarouselTap.TurnTo -> scope.launch {
                            if (animationsOn()) pager.animateScrollToPage(tap.page, animationSpec = snap) else pager.scrollToPage(tap.page)
                        }
                    }
                },
            fallback = now.entry.cover,
        )
    }
}
