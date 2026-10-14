package me.vibb.music.ui

import kotlin.math.roundToInt

/*
 * Sizes that must fit the narrowest phones - 320 dp, 288 dp inside the screens' 16 dp sides (design
 * 20 "Player controls fit a 320 dp screen", mockup v15). Compose's `size()` gives in to its
 * constraints, so a row wider than its room squeezes round buttons into ovals and drops the last
 * slot; these rules keep every row within its room. Pure; FitTest checks 320, 360 and 411 dp.
 */

/** The screens' side padding (dp). */
const val SCREEN_SIDE_DP = 16

/** The player's control row: [shuffle or an empty slot] previous, play, next [empty slot] (dp). */
data class ControlRow(val side: Int, val skip: Int, val play: Int, val gap: Int) {
    /** The buttons' sizes, left to right: the row is symmetric, so play sits exactly in the middle. */
    val sizes: List<Int> get() = listOf(side, skip, play, skip, side)
    val width: Int get() = sizes.sum() + 4 * gap
}

/** Mockup v12's row: 332 dp. */
val CONTROLS_ROOMY = ControlRow(side = 44, skip = 56, play = 76, gap = 14)

/** Mockup v15's compact row: exactly 288 dp, the Jelly Star's room. */
val CONTROLS_COMPACT = ControlRow(side = 44, skip = 52, play = 72, gap = 6)

/** v12's row where it fits, else v15's compact one. */
fun controlRow(availableDp: Float): ControlRow = if (availableDp >= CONTROLS_ROOMY.width) CONTROLS_ROOMY else CONTROLS_COMPACT

/**
 * Each button's left and right edge (dp) in a row of [availableDp], centred with [ControlRow.gap]
 * between them - as `Arrangement.spacedBy(gap, Alignment.CenterHorizontally)` places them.
 */
fun controlEdges(row: ControlRow, availableDp: Float): List<Pair<Float, Float>> {
    var x = (availableDp - row.width) / 2
    return row.sizes.map { size ->
        val edges = x to x + size
        x += size + row.gap
        edges
    }
}

/**
 * The player's bottom bar - Liste, Sov, "Spill av på" at weights 1 : 1 : 1.2, as the mockup's flex
 * row: each gets its share of [available] (px or dp, gaps already taken off) by [weights], but never
 * less than its [mins] (the mockup's `min-width: auto`: "Sov · 30 min" is never cut). The ones that
 * fit share what is left; a min of 0 (the output's name, `min-width: 0`) gives way and ellipsizes.
 * The widths add up to [available] unless the mins alone are wider.
 */
fun flexWidths(available: Int, weights: List<Float>, mins: List<Int>): List<Int> {
    val fixed = BooleanArray(weights.size)
    while (true) {
        val free = available - weights.indices.filter { fixed[it] }.sumOf { mins[it] }
        val open = weights.indices.filter { !fixed[it] }
        val total = open.sumOf { weights[it].toDouble() }
        val short = open.filter { total > 0 && free * weights[it] / total < mins[it] }
        if (short.isEmpty() || open.isEmpty()) {
            val widths = IntArray(weights.size) { mins[it] }
            var left = free.coerceAtLeast(0)
            for ((n, i) in open.withIndex()) {
                val share = if (n == open.lastIndex) left else (free.coerceAtLeast(0) * weights[i] / total).roundToInt()
                widths[i] = share.coerceAtLeast(0)
                left -= share
            }
            return widths.toList()
        }
        short.forEach { fixed[it] = true }
    }
}

/** Five category tiles fill the row with four 4 dp gaps (more scroll), each wide enough for its 44 dp circle. */
fun categoryTileWidth(availableDp: Float): Float = (availableDp - 4 * 4) / 5

/** The carousel's cover (at most 180 dp, [libraryCover]), centred: the room left on each side of it (dp). */
fun carouselSide(availableDp: Float, coverDp: Float = CAROUSEL_COVER_DP.toFloat()): Float = ((availableDp - coverDp) / 2).coerceAtLeast(0f)

/** The library's cover at its largest (mockup v12). */
const val CAROUSEL_COVER_DP = 180
const val CAROUSEL_SPACING_DP = 8

/**
 * How much of a neighbouring cover shows beside the centred one (dp): pages are a cover apart by 8 dp,
 * a neighbour is scaled to 0.733 about its centre and pulled in by as much as that took off its near
 * edge ([carouselPull], 24 dp at 180), so its near edge stays where the page starts - 46 dp on the Jelly Star.
 */
fun carouselPeek(availableDp: Float, coverDp: Float = CAROUSEL_COVER_DP.toFloat()): Float =
    availableDp - (carouselSide(availableDp, coverDp) + coverDp + CAROUSEL_SPACING_DP)

/** A library neighbour scaled to [NEIGHBOUR_SCALE] is pulled in by what that took off its near edge (dp). */
fun carouselPull(coverDp: Float): Float = coverDp * (1 - NEIGHBOUR_SCALE) / 2

/** The category glyph on a cover without art: 84 dp on the 180 dp cover, in proportion below it. */
fun carouselGlyph(coverDp: Float): Float = 84f * coverDp / CAROUSEL_COVER_DP

/*
 * The library's fit (design 34 P10 b, QA L1): the carousel sits in the weighted slot between the header and the
 * category tiles + now-playing bar, which always keep their own height. The cover is sized from the slot as measured
 * (a BoxWithConstraints), less the title and hint lines as measured at the font scale, less the fixed rest:
 * min(180, what is left), never under 120 - below that the slot scrolls, so nothing ever slides under the tiles or
 * the bar.
 */

/** The smallest library cover; below it the slot scrolls (QA L1). */
const val LIBRARY_COVER_MIN_DP = 120

/** The pager's box is the cover plus this (its shadow's margin, v12's 184 for 180). */
const val CAROUSEL_BOX_EXTRA_DP = 4

/** The gap between the pager, the title block and the dots. */
const val CAROUSEL_GAP_DP = 8

/** The dots' height. */
const val CAROUSEL_DOTS_DP = 8

/**
 * The title block's fixed part (dp): the 3 dp between the title and the hint, and the progress line's 3 + 3 + 4 -
 * always reserved, so the cover keeps its size when the carousel turns to an entry with or without progress.
 */
const val CAROUSEL_TITLE_FIXED_DP = 3 + 3 + 3 + 4

/** Everything in the slot but the cover and the two text lines (dp): 41. */
const val LIBRARY_SLOT_FIXED_DP = CAROUSEL_BOX_EXTRA_DP + CAROUSEL_GAP_DP + CAROUSEL_TITLE_FIXED_DP + CAROUSEL_GAP_DP + CAROUSEL_DOTS_DP

/** What the slot [slotDp] leaves the cover once the title and hint lines ([linesDp], measured) and the fixed rest are in. */
private fun libraryRoom(slotDp: Float, linesDp: Float): Float = slotDp - linesDp - LIBRARY_SLOT_FIXED_DP

/** The library's cover (dp): min(180, the room the slot leaves), floor 120. */
fun libraryCover(slotDp: Float, linesDp: Float): Float =
    libraryRoom(slotDp, linesDp).coerceIn(LIBRARY_COVER_MIN_DP.toFloat(), CAROUSEL_COVER_DP.toFloat())

/** Below the floor the slot scrolls rather than overlap. */
fun libraryScrolls(slotDp: Float, linesDp: Float): Boolean = libraryRoom(slotDp, linesDp) < LIBRARY_COVER_MIN_DP

/** The slot's content (dp) with a cover of [coverDp]: what scrolls when it is taller than the slot. */
fun librarySlotContent(coverDp: Float, linesDp: Float): Float = coverDp + linesDp + LIBRARY_SLOT_FIXED_DP

// The player's art (design 27 §3.3): today's single cover, or the episode carousel in its place.

/** The player's cover: at most 224 dp, as big as its box allows. */
const val PLAYER_ART_MAX_DP = 224

/** The carousel's pages are this far apart, and each neighbour peeks at least this much. */
const val PLAYER_PAGE_GAP_DP = 8
const val PLAYER_PEEK_MIN_DP = 36

/** Below this the carousel gives way to the single cover (a large font or display size, QA L2 b). */
const val PLAYER_ART_MIN_DP = 96

/** A neighbour's scale beside the centred page (the library carousel's). */
const val NEIGHBOUR_SCALE = 0.733f

/**
 * The centred picture's side (dp) in a box [widthDp] wide (inside the screen's sides) and [roomDp]
 * tall: min(224, the room) as today; with the [carousel] also no wider than leaves both neighbours
 * their 8 dp gap and 36 dp peek.
 */
fun playerArt(widthDp: Float, roomDp: Float, carousel: Boolean): Float {
    val single = minOf(PLAYER_ART_MAX_DP.toFloat(), roomDp)
    return if (carousel) minOf(single, widthDp - 2 * (PLAYER_PAGE_GAP_DP + PLAYER_PEEK_MIN_DP)) else single
}

/** Does the carousel show in that box (it has to, and its picture isn't too small)? */
fun playerCarousel(widthDp: Float, roomDp: Float, carousel: Boolean): Boolean =
    carousel && playerArt(widthDp, roomDp, true) >= PLAYER_ART_MIN_DP

/** How much of each neighbour shows beside the centred picture [artDp] (dp): the pager's content padding less the gap. */
fun playerPeek(widthDp: Float, artDp: Float): Float = (widthDp - artDp) / 2 - PLAYER_PAGE_GAP_DP

/** A neighbour scaled to [NEIGHBOUR_SCALE] is pulled in by what that took off its near edge, so its near edge stays put (QA L2 a). */
fun playerPull(artDp: Float): Float = artDp * (1 - NEIGHBOUR_SCALE) / 2

/** The side (px) a cover file of [sidePx] decodes at on a screen of [density]: what the player's 224 dp needs, never more than the file. */
fun coverDecodePx(sidePx: Int, density: Float): Int = minOf(sidePx, kotlin.math.ceil(PLAYER_ART_MAX_DP * density).toInt()).coerceAtLeast(1)
