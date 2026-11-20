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

/*
 * The library's sizes that grow with the screen (the user, 2026-11-20, the approved mockup: "noe som skalerer bra også
 * på større skjermer"): the now-playing bar and the category circles grow by the width alone, never by the height,
 * from the Jelly Star's sizes at a 320 dp screen (and narrower) to a large phone's at 411 dp (and wider).
 */

/** The screen width (dp) where the growing sizes start, and where they are at their largest. */
const val GROW_FROM_DP = 320
const val GROW_TO_DP = 411

/** How far a screen whose content is [contentDp] wide (inside the 16 dp sides) is from [GROW_FROM_DP] to [GROW_TO_DP]: 0..1. */
fun widthGrowth(contentDp: Float): Float =
    ((contentDp + 2 * SCREEN_SIDE_DP - GROW_FROM_DP) / (GROW_TO_DP - GROW_FROM_DP)).coerceIn(0f, 1f)

/** A size [from] at [GROW_FROM_DP], [to] at [GROW_TO_DP], whole dp (or sp) in between. */
private fun grown(from: Int, to: Int, growth: Float): Int = (from + (to - from) * growth).roundToInt()

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

/**
 * Below the Jelly Star (screen-size fix S2, 2026-11-14: on a 270 dp phone the compact row was 288 dp in 238 and "15 s
 * fram" lay over "Neste"): 256 dp, previous and next at the 48 dp touch target, the side slots as ever, play still the
 * largest. Five round buttons can't fit 238 dp at those sizes, so this row may also take up to [CONTROLS_BLEED_MAX_DP]
 * of each screen side ([controlBleed]).
 */
val CONTROLS_NARROW = ControlRow(side = 44, skip = 48, play = 56, gap = 4)

/** How far into each screen side (of [SCREEN_SIDE_DP]) the narrow row may reach (dp): 4 dp stay free at the edge. */
const val CONTROLS_BLEED_MAX_DP = 12

/**
 * v12's row where it fits, else v15's compact one, else the narrow one (with the sides' room), and on a screen narrower
 * still the narrow one scaled down to that room - never wider than the room, never overlapping.
 */
fun controlRow(availableDp: Float): ControlRow {
    val withSides = availableDp + 2 * CONTROLS_BLEED_MAX_DP
    return when {
        availableDp >= CONTROLS_ROOMY.width -> CONTROLS_ROOMY
        availableDp >= CONTROLS_COMPACT.width -> CONTROLS_COMPACT
        withSides >= CONTROLS_NARROW.width -> CONTROLS_NARROW
        else -> {
            val share = (withSides - 4 * CONTROLS_NARROW.gap).coerceAtLeast(0f) / CONTROLS_NARROW.sizes.sum()
            fun scaled(dp: Int) = (dp * share).toInt()
            ControlRow(scaled(CONTROLS_NARROW.side), scaled(CONTROLS_NARROW.skip), scaled(CONTROLS_NARROW.play), CONTROLS_NARROW.gap)
        }
    }
}

/** How far [row] reaches into each screen side beyond [availableDp] (dp, 0 when it fits): centred, the overflow halved. */
fun controlBleed(row: ControlRow, availableDp: Float): Float = ((row.width - availableDp) / 2).coerceIn(0f, CONTROLS_BLEED_MAX_DP.toFloat())

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

/**
 * The shrinking slot's own min in [FlexBar] (screen-size fix S7, 2026-11-14: "Telefonen" showed as "Telefo…" while Liste
 * and Sov had room to spare): its whole width ([ownDp]) when the others' mins ([othersDp]) leave that much of
 * [available] - the name shown whole is worth their extra -, else 0 as before (code QA L4: a name that is cut anyway,
 * a long Sonos room, never squeezes Liste and Sov to their bare content; it gives way at its weighted share).
 */
fun shrinkerMin(ownDp: Int, available: Int, othersDp: Int): Int = if (ownDp <= available - othersDp) ownDp else 0

/** Beside a cut output name, Liste and Sov each keep this much air beyond their content (dp), while the output keeps [BAR_OUTPUT_FLOOR_DP]. */
const val BAR_AIR_DP = 8

/** The output button's least room when Liste and Sov take their air: its glyph and padding (30 dp) and a readable start of the name. */
const val BAR_OUTPUT_FLOOR_DP = 72

/**
 * The player's bottom bar ([FlexBar]): each slot's width from its own content width ([own], px or dp alike), the one
 * [shrinker] being the output's name. While that name fits whole beside the others' content, [flexWidths] shares the
 * row by [weights] with every content kept (S7). A name that is cut anyway - a long Sonos room, "Stue og kjøkken" at
 * font 1.3 on the Jelly Star (2026-11-20) - gets all the rest once the others have taken their content and [air] each
 * (no air when that would leave the output under [outputFloor]): it shows as much of itself as the row allows, one line,
 * ellipsized, and never pushes the others. Not code QA L4's weighted share any more: the user asked for the rest.
 */
fun barWidths(available: Int, weights: List<Float>, own: List<Int>, shrinker: Int, air: Int, outputFloor: Int): List<Int> {
    if (shrinker !in own.indices) return flexWidths(available, weights, own)
    val others = own.filterIndexed { i, _ -> i != shrinker }.sum()
    if (own[shrinker] <= available - others) return flexWidths(available, weights, own)
    val withAir = others + air * (own.size - 1)
    val give = if (available - withAir >= outputFloor) air else 0
    val widths = own.mapIndexed { i, w -> if (i == shrinker) 0 else w + give }
    return widths.mapIndexed { i, w -> if (i == shrinker) (available - widths.sum()).coerceAtLeast(0) else w }
}

/** Five category tiles fill the row with four 4 dp gaps (more scroll), each wide enough for its 44 dp circle. */
fun categoryTileWidth(availableDp: Float): Float = (availableDp - 4 * 4) / 5

/**
 * A category tile as wide as its one-line label needs ([labelDp], measured at the font scale) and never narrower than
 * [categoryTileWidth] - the row scrolls when the labels need more (design 34 step 11, emulator round 2026-10-14 N6: at
 * font scale 2.0 "Musikk" and "Podkaster" were cut to "Mu…" and "Po…"); at most the whole row.
 */
fun categoryTileWidthFor(availableDp: Float, labelDp: Float): Float =
    maxOf(categoryTileWidth(availableDp), kotlin.math.ceil(labelDp) + CATEGORY_LABEL_SLACK_DP).coerceAtMost(availableDp)

/** A label's measured width gets this much more (sub-pixel rounding, so it never ellipsizes by a hair). */
const val CATEGORY_LABEL_SLACK_DP = 2f

/**
 * The category row's gap, circle and glyph (dp) and its label (sp): as ever, and compact when the row would not fit
 * otherwise. The usual circle and glyph grow with the width ([categoryCircle], 2026-11-20): 44/20 up to a 320 dp screen,
 * 48/22 from 411 dp.
 */
const val CATEGORY_LABEL_SP = 11
const val CATEGORY_LABEL_COMPACT_SP = 10
const val CATEGORY_GAP_DP = 4f
const val CATEGORY_GAP_COMPACT_DP = 2f
const val CATEGORY_CIRCLE_DP = 44f
const val CATEGORY_CIRCLE_MAX_DP = 48f
const val CATEGORY_CIRCLE_COMPACT_DP = 40f
const val CATEGORY_GLYPH_DP = 20f
const val CATEGORY_GLYPH_MAX_DP = 22f
const val CATEGORY_GLYPH_COMPACT_DP = 18f

/** The usual (not compact) row's circle and glyph (dp) in a row [availableDp] wide: grown with the width ([widthGrowth]). */
fun categoryCircle(availableDp: Float): Pair<Float, Float> {
    val g = widthGrowth(availableDp)
    return grown(CATEGORY_CIRCLE_DP.toInt(), CATEGORY_CIRCLE_MAX_DP.toInt(), g).toFloat() to
        grown(CATEGORY_GLYPH_DP.toInt(), CATEGORY_GLYPH_MAX_DP.toInt(), g).toFloat()
}

/** A category tile is never narrower than this (dp): the touch target. Its height is at least 64. */
const val CATEGORY_TILE_MIN_DP = 48f

/**
 * The category row laid out: each tile's width, the gap, the circle and its glyph (dp), the labels' size (sp), and
 * whether the row scrolls.
 */
data class CategoryRow(val widths: List<Float>, val gap: Float, val circle: Float, val glyph: Float, val labelSp: Int, val scrolls: Boolean) {
    val width: Float get() = widths.sum() + gap * (widths.size - 1).coerceAtLeast(0)
}

/**
 * The category row in [availableDp] for labels of [labelsDp] at [CATEGORY_LABEL_SP] and [compactDp] at
 * [CATEGORY_LABEL_COMPACT_SP] (each measured at the font scale) - the Jelly Star, 2026-11-20: with "Favoritter" the
 * five tiles at a fifth each or their label's width were about 300 dp in 288, and "Podkast" was cut to "Podkas". Three
 * steps, the first that fits:
 * - as ever: each tile [categoryTileWidthFor] (a fifth of the row, or its label), 4 dp apart, the usual circles
 *   ([categoryCircle]: 44 dp up to a 320 dp screen, 48 from 411), 11 sp;
 * - compact: 2 dp apart, 40 dp circles, 10 sp labels, each tile at least its label (+ slack) and the 48 dp touch target,
 *   the rest of the row shared so the narrow tiles grow first (one floor width, [shareFloor]) - the row fills its width;
 * - neither fits (a large font): every tile at its 11 sp label's width (at least 48), 4 dp apart, the usual circles, and the
 *   row scrolls ([CategoryRow.scrolls]) - the screen hides a tile the edge cuts at rest and shows where more lie.
 * A label is never cut and never wraps: its tile is always at least as wide.
 */
fun categoryRow(availableDp: Float, labelsDp: List<Float>, compactDp: List<Float> = labelsDp): CategoryRow {
    val n = labelsDp.size
    val gaps = { gap: Float -> gap * (n - 1).coerceAtLeast(0) }
    val (circle, glyph) = categoryCircle(availableDp)
    val usual = labelsDp.map { categoryTileWidthFor(availableDp, it) }
    if (usual.sum() + gaps(CATEGORY_GAP_DP) <= availableDp + FIT_EPSILON) {
        return CategoryRow(usual, CATEGORY_GAP_DP, circle, glyph, CATEGORY_LABEL_SP, scrolls = false)
    }
    fun mins(labels: List<Float>) = labels.map { maxOf(CATEGORY_TILE_MIN_DP, kotlin.math.ceil(it) + CATEGORY_LABEL_SLACK_DP) }
    val compact = mins(compactDp)
    // a dp less than the row: each width is rounded to whole pixels on its own, so a full row never pokes out by one
    val room = availableDp - gaps(CATEGORY_GAP_COMPACT_DP) - CATEGORY_ROUNDING_DP
    if (compact.sum() <= room + FIT_EPSILON) {
        val floor = shareFloor(room, compact)
        return CategoryRow(
            compact.map { maxOf(it, floor) }, CATEGORY_GAP_COMPACT_DP, CATEGORY_CIRCLE_COMPACT_DP, CATEGORY_GLYPH_COMPACT_DP,
            CATEGORY_LABEL_COMPACT_SP, scrolls = false,
        )
    }
    return CategoryRow(mins(labelsDp), CATEGORY_GAP_DP, circle, glyph, CATEGORY_LABEL_SP, scrolls = true)
}

/** A scrolling row's tile fades out over its first this many dp cut by the edge, while the row moves. */
const val CATEGORY_CUT_FADE_DP = 12f

/**
 * A scrolling category row's tile ([startPx]..[endPx] in the row's viewport [viewStartPx]..[viewEndPx]) - its alpha: 1
 * while whole; cut by the edge, fading out over [fadePx] while the row moves and gone as it rests ([rest] 0 moving, 1
 * at rest, animated in between) - so a label is never shown cut at rest.
 */
fun categoryTileAlpha(startPx: Int, endPx: Int, viewStartPx: Int, viewEndPx: Int, fadePx: Float, rest: Float): Float {
    val cut = maxOf(0, viewStartPx - startPx) + maxOf(0, endPx - viewEndPx)
    if (cut <= 0) return 1f
    val moving = if (fadePx <= 0f) 0f else (1f - cut / fadePx).coerceIn(0f, 1f)
    return moving * (1f - rest.coerceIn(0f, 1f))
}

/** The compact row is filled to a dp short of its room (each tile rounds on its own). */
const val CATEGORY_ROUNDING_DP = 1f

/** Float sums of a row a hair over its room still fit. */
private const val FIT_EPSILON = 0.01f

/**
 * The one width [f] with sum(max(f, min)) == [room] (each tile at least its own min, the narrow ones raised to [f]);
 * the mins must fit [room].
 */
fun shareFloor(room: Float, mins: List<Float>): Float {
    if (mins.isEmpty()) return 0f
    val sorted = mins.sortedDescending()
    var fixed = 0f
    for ((i, m) in sorted.withIndex()) {
        val f = (room - fixed) / (sorted.size - i)
        if (f >= m) return f
        fixed += m
    }
    return sorted.last()
}

/** The carousel's cover ([libraryFit], v12's 180 dp by default), centred: the room left on each side of it (dp). */
fun carouselSide(availableDp: Float, coverDp: Float = CAROUSEL_COVER_DP.toFloat()): Float = ((availableDp - coverDp) / 2).coerceAtLeast(0f)

/**
 * The library's cover at its largest on a narrow phone (mockup v12), and the least its cap ever is: wider phones raise
 * the cap with the width ([carouselCoverMax], 2026-11-20).
 */
const val CAROUSEL_COVER_DP = 180
const val CAROUSEL_SPACING_DP = 8

/** The share of the screen's width the library's cover may take (the approved mockup: 280 dp on a 411 dp phone). */
const val CAROUSEL_COVER_SHARE = 0.68f

/** Each neighbour still peeks at least this much beside a cover the width raised (dp; the player's on a 320 dp phone). */
const val CAROUSEL_PEEK_MIN_DP = 24

/**
 * The library's cover at its largest in a slot [contentDp] wide (inside the screen's sides), height aside (the user,
 * 2026-11-20: on a 411 dp phone the 180 dp cover left about 200 dp empty above and below it): [CAROUSEL_COVER_SHARE] of
 * the screen's width, no more than leaves each neighbour [CAROUSEL_PEEK_MIN_DP] beside its 8 dp gap, never above the
 * player's cover on this screen ([playerArtMax]: the one decoded bitmap, [coverDecodePx]) - and never under v12's
 * [CAROUSEL_COVER_DP], so a phone narrower than about 265 dp keeps today's cover. 217.6 dp on the Jelly Star (320),
 * 279.5 on a 411 dp phone. The height that is there still limits it ([libraryFit]).
 */
fun carouselCoverMax(contentDp: Float): Float {
    val screen = contentDp + 2 * SCREEN_SIDE_DP
    val byWidth = minOf(screen * CAROUSEL_COVER_SHARE, contentDp - 2 * (CAROUSEL_SPACING_DP + CAROUSEL_PEEK_MIN_DP), playerArtMax(contentDp))
    return maxOf(CAROUSEL_COVER_DP.toFloat(), byWidth)
}

/**
 * How much of a neighbouring cover shows beside the centred one (dp): pages are a cover apart by 8 dp,
 * a neighbour is scaled to 0.733 about its centre and pulled in by as much as that took off its near
 * edge ([carouselPull], 24 dp at 180), so its near edge stays where the page starts - 46 dp beside a 180 dp cover on
 * the Jelly Star, at least [CAROUSEL_PEEK_MIN_DP] beside [carouselCoverMax].
 */
fun carouselPeek(availableDp: Float, coverDp: Float = CAROUSEL_COVER_DP.toFloat()): Float =
    availableDp - (carouselSide(availableDp, coverDp) + coverDp + CAROUSEL_SPACING_DP)

/** A library neighbour scaled to [NEIGHBOUR_SCALE] is pulled in by what that took off its near edge (dp). */
fun carouselPull(coverDp: Float): Float = coverDp * (1 - NEIGHBOUR_SCALE) / 2

/** The category glyph on a cover without art: 84 dp on the 180 dp cover, in proportion below and above it. */
fun carouselGlyph(coverDp: Float): Float = 84f * coverDp / CAROUSEL_COVER_DP

/*
 * The library's fit (design 34 P10 b, QA L1): the carousel sits in the weighted slot between the header and the
 * category tiles + now-playing bar, which always keep their own height. The cover is sized from the slot as measured
 * (a BoxWithConstraints), less the title and hint lines as measured at the font scale, less the fixed rest and a little
 * air ([LIBRARY_AIR_DP]): min([carouselCoverMax] of the width, what is left), never under 64 - below that the slot
 * scrolls, so nothing ever slides under the tiles or the bar. Everything is in whole pixels, each fixed part rounded as Compose rounds a Dp (qa-34-s11-code L1: at
 * density 1.5 the 3 dp gaps are 4.5 px each, drawn as 5), so the content is never a pixel taller than the slot.
 */

/**
 * The smallest library cover; below it the slot scrolls (QA L1). 64 dp, not step 11's 120 (emulator round 2026-10-14
 * N6): at font scale 2.0 Android 16 grows the status bar to about 90 dp, so on a 320x569 dp screen with the
 * now-playing bar the slot held a 120 dp cover only by scrolling, and the hint line was cut at the slot's edge. A
 * cover gives way before the words do; it is still well above a 48 dp target, its neighbours peek beside it.
 */
const val LIBRARY_COVER_MIN_DP = 64

/** Below this cover the kind chip on it ("Musikk") is left out: the hint line under the title says the kind (N6). */
const val COVER_KIND_CHIP_MIN_DP = 120

/** Does a cover of [coverDp] carry the kind chip ([COVER_KIND_CHIP_MIN_DP])? */
fun coverShowsKind(coverDp: Float): Boolean = coverDp >= COVER_KIND_CHIP_MIN_DP

/** The pager's box is the cover plus this (its shadow's margin, v12's 184 for 180). */
const val CAROUSEL_BOX_EXTRA_DP = 4

/** The gap between the pager, the title block and the dots. */
const val CAROUSEL_GAP_DP = 8

/** The dots' height. */
const val CAROUSEL_DOTS_DP = 8

/** The title block's gaps (title, hint, progress line) and the progress line: 3 dp above the 4 dp line. */
const val CAROUSEL_TITLE_GAP_DP = 3
const val CAROUSEL_PROGRESS_PAD_DP = 3
const val CAROUSEL_PROGRESS_DP = 4

/** A dp value in pixels as Compose's `Dp.roundToPx` gives it. */
fun dpToPx(dp: Int, density: Float): Int = (dp * density).roundToInt()

/**
 * Everything in the slot but the cover and the two text lines, in pixels as drawn: the pager box's extra, two gaps,
 * the title block's two 3 dp gaps and the progress line's room (the line, or the spacer of the same height that keeps
 * its room - always reserved, so the cover keeps its size when the carousel turns), the dots. 41 dp at density 2.
 */
fun libraryFixedPx(density: Float): Int {
    fun px(dp: Int) = dpToPx(dp, density)
    return px(CAROUSEL_BOX_EXTRA_DP) + 2 * px(CAROUSEL_GAP_DP) + 2 * px(CAROUSEL_TITLE_GAP_DP) +
        progressRoomPx(density) + px(CAROUSEL_DOTS_DP)
}

/** The progress line's room: its 3 dp padding and 4 dp line, each rounded, as the reserved spacer is drawn too. */
fun progressRoomPx(density: Float): Int = dpToPx(CAROUSEL_PROGRESS_PAD_DP, density) + dpToPx(CAROUSEL_PROGRESS_DP, density)

/** The library's cover in pixels, whether the slot scrolls, and the title block's extra room ([libraryBreath]). */
data class LibraryFit(val coverPx: Int, val scrolls: Boolean, val breath: LibraryBreath = LibraryBreath.NONE)

/**
 * What the library's title block gets on top of its base gaps (px), from the room the cover leaves once it is at its
 * largest ([carouselCoverMax]; 180 dp until 2026-11-20's growing cap) (the Jelly Star, 2026-11-20: "under karusell bildene ble litt for komprimert" - the title, the hint, the
 * progress line and the dot sat 3 dp apart while 40-90 dp stood empty above the cover and under the dot): between the
 * cover and the title, the title and the hint, the hint and the progress line, the progress line and the dots.
 */
data class LibraryBreath(val coverTitlePx: Int, val titleHintPx: Int, val hintProgressPx: Int, val progressDotsPx: Int) {
    val totalPx: Int get() = coverTitlePx + titleHintPx + hintProgressPx + progressDotsPx

    companion object {
        val NONE = LibraryBreath(0, 0, 0, 0)
    }
}

/**
 * The most each of the title block's gaps grows (dp), in [LibraryBreath]'s order: the cover's gap 8 -> 16, the title's
 * 3 -> 6, the progress line's 3 + 3 -> 13, the dots' 8 -> 16. Capped, so on a tall phone the block stays one block and
 * the rest of the room centres as before.
 */
val LIBRARY_BREATH_MAX_DP = listOf(8, 3, 7, 8)

/** The share of the cover's leftover room the title block may take (the rest centres above the cover and under the dots). */
const val LIBRARY_BREATH_SHARE = 2f / 3

/**
 * The title block's extra room from [sparePx], the slot's room the cover at its largest leaves: at most
 * [LIBRARY_BREATH_SHARE] of it, shared among the gaps in proportion to [LIBRARY_BREATH_MAX_DP], each in whole pixels
 * (floored, so the content never outgrows the slot) and never above its own cap. No spare (a cover below its largest:
 * the room goes to the cover), no extra.
 */
fun libraryBreath(sparePx: Int, density: Float): LibraryBreath {
    if (sparePx <= 0) return LibraryBreath.NONE
    val caps = LIBRARY_BREATH_MAX_DP.map { dpToPx(it, density) }
    val all = caps.sum()
    val budget = minOf(all.toFloat(), sparePx * LIBRARY_BREATH_SHARE)
    val each = caps.map { (it * budget / all).toInt().coerceAtMost(it) }
    return LibraryBreath(each[0], each[1], each[2], each[3])
}

/**
 * The air kept above the cover and under the dots when the cover is limited by the height (dp, each side; 2026-11-20,
 * the approved mockup): on the Jelly Star the cover then sits about 14 dp under the header's buttons and the dots 12
 * dp over the category circles, never pressed against them. It gives way at the floor before the slot scrolls.
 */
const val LIBRARY_AIR_DP = 4

/**
 * The library's cover for a slot of [slotPx] holding the measured title and hint lines ([linesPx]): min([capDp] - the
 * width's [carouselCoverMax] -, what is left once [reservePx] and the air above and below ([LIBRARY_AIR_DP]) are also
 * kept free), floor [LIBRARY_COVER_MIN_DP]. [reservePx] is the now-playing bar's room (and its gap) while there is no
 * bar, so the cover is the same with and without it (qa-34-s11-code N2: no 180/174 jump when the bar comes or goes).
 * The slot scrolls only when the content is taller than the slot itself. What the cover at its cap leaves gives the
 * title block its [LibraryBreath]; the rest centres, as much above the cover as under the dots.
 */
fun libraryFit(slotPx: Int, linesPx: Int, reservePx: Int, density: Float, capDp: Float = CAROUSEL_COVER_DP.toFloat()): LibraryFit {
    val room = slotPx - reservePx - linesPx - libraryFixedPx(density) - 2 * dpToPx(LIBRARY_AIR_DP, density)
    // the cap in whole pixels, floored: never a pixel above the width's own limit
    val cap = maxOf((capDp * density).toInt(), dpToPx(LIBRARY_COVER_MIN_DP, density))
    val cover = room.coerceIn(dpToPx(LIBRARY_COVER_MIN_DP, density), cap)
    // the room left once the cover is at its largest: some of it lets the title block breathe (2026-11-20); [reservePx]
    // is already out of it, so the gaps too are the same with and without the bar
    return LibraryFit(cover, librarySlotContentPx(cover, linesPx, density) > slotPx, libraryBreath(room - cover, density))
}

/** The slot's content in pixels with a cover of [coverPx]. */
fun librarySlotContentPx(coverPx: Int, linesPx: Int, density: Float): Int = coverPx + linesPx + libraryFixedPx(density)

/**
 * The now-playing bar's sizes (dp, the lines in sp; the user, 2026-11-20, the approved mockup: "gjøre den spiller nå
 * baren her litt høyere"): its cover and that cover's corner, the play button and its glyph, the bar's corner, its
 * side and top/bottom padding, the gap between its parts, the item's title and the entry's line. 72 dp tall on the
 * Jelly Star, 80 on a 411 dp phone ([nowBarSize]).
 */
data class NowBarSize(
    val cover: Int,
    val coverRadius: Int,
    val play: Int,
    val playGlyph: Int,
    val radius: Int,
    val padH: Int,
    val padV: Int,
    val gap: Int,
    val titleSp: Int,
    val lineSp: Int,
) {
    /** The bar's height (dp) while its lines fit beside the cover: the cover and its padding. */
    val height: Int get() = cover + 2 * padV
}

/** The bar's two lines are this far apart (dp), and its progress line along the bottom edge is this tall. */
const val NOW_BAR_LINE_GAP_DP = 2
const val NOW_BAR_PROGRESS_DP = 3

/**
 * Lines taller than the bar's cover (a large font) keep this padding above and below (dp; the old bar's 6): the bar grows
 * with them from [NowBarSize.height], its padding giving way first, so at font scale 2.0 it takes little more room than
 * the old one did (N6: the library's hint and dots still fit the emulator's 320 x 569 dp screen with a 91 dp status bar).
 */
const val NOW_BAR_PAD_MIN_DP = 6

/**
 * The now-playing bar in a column [contentDp] wide, grown with the width ([widthGrowth]): up to a 320 dp screen a 52 dp
 * cover (corner 12), a 48 dp play button (glyph 18), corner 20, 10 dp padding, 12 dp gaps, 15 + 12 sp lines - 72 dp
 * tall; from 411 dp 56 (14), 52 (20), 22, 12, 14, 16 + 13 sp - 80 dp. The play button is never under the 48 dp touch
 * target, and the bar's open-player area is as tall as its cover.
 */
fun nowBarSize(contentDp: Float): NowBarSize {
    val g = widthGrowth(contentDp)
    return NowBarSize(
        cover = grown(52, 56, g), coverRadius = grown(12, 14, g), play = grown(48, 52, g), playGlyph = grown(18, 20, g),
        radius = grown(20, 22, g), padH = grown(10, 12, g), padV = grown(10, 12, g), gap = grown(12, 14, g),
        titleSp = grown(15, 16, g), lineSp = grown(12, 13, g),
    )
}

/**
 * The now-playing bar's room in pixels, its gap above it included: [NowBarSize.height], or - when its two lines
 * ([linesPx], measured at [NowBarSize.titleSp] and [NowBarSize.lineSp], and their 2 dp gap) need more - the lines with
 * [NOW_BAR_PAD_MIN_DP] above and below; and the column's 8 dp gap. As the bar is drawn: a `heightIn(min = height)` around
 * the 6 dp padding. The progress line lies inside the bar's bottom edge: no room of its own.
 */
fun nowBarRoomPx(bar: NowBarSize, linesPx: Int, density: Float): Int {
    fun px(dp: Int) = dpToPx(dp, density)
    val content = maxOf(px(bar.cover), px(bar.play), linesPx + px(NOW_BAR_LINE_GAP_DP))
    return maxOf(px(bar.height), content + 2 * px(NOW_BAR_PAD_MIN_DP)) + px(CAROUSEL_GAP_DP)
}

/** The bar's progress line: how far [positionMs] is into the item ([durationMs]), 0..1; null without a known length. */
fun nowBarProgress(positionMs: Long, durationMs: Long?): Float? {
    if (durationMs == null || durationMs <= 0) return null
    return (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}

// The player's art (design 27 §3.3): today's single cover, or the episode carousel in its place.

/**
 * The player's cover on a 320 dp phone and below: at most 224 dp, as big as its box allows. Wider phones grow it with
 * the width ([playerArtMax], screen-size finding S10, 2026-11-14), up to [PLAYER_ART_MAX_DP].
 */
const val PLAYER_ART_BASE_DP = 224

/** The player's cover at its largest (a 411 dp phone reaches 291 dp, its width's own limit; this caps wider screens). */
const val PLAYER_ART_MAX_DP = 320

/** The carousel's pages are this far apart, and each neighbour peeks at least this much. */
const val PLAYER_PAGE_GAP_DP = 8
const val PLAYER_PEEK_MIN_DP = 36

/** Below this the carousel gives way to the single cover (a large font or display size, QA L2 b). */
const val PLAYER_ART_MIN_DP = 96

/** A neighbour's scale beside the centred page (the library carousel's). */
const val NEIGHBOUR_SCALE = 0.733f

/**
 * The largest the player's cover gets in a box [widthDp] wide (inside the screen's sides), height aside (S10): as wide
 * as leaves the carousel's neighbours their 8 dp gap and 36 dp peek - the Jelly Star's look, 36 dp each side at the
 * mockup's 200 dp -, never under today's [PLAYER_ART_BASE_DP] (so a 320 dp phone and below keep today's cover) nor
 * over [PLAYER_ART_MAX_DP].
 */
fun playerArtMax(widthDp: Float): Float =
    (widthDp - 2 * (PLAYER_PAGE_GAP_DP + PLAYER_PEEK_MIN_DP)).coerceIn(PLAYER_ART_BASE_DP.toFloat(), PLAYER_ART_MAX_DP.toFloat())

/**
 * The centred picture's side (dp) in a box [widthDp] wide (inside the screen's sides) and [roomDp]
 * tall: min([playerArtMax], the room, the width); with the [carousel] also no wider than leaves both neighbours
 * their 8 dp gap and 36 dp peek.
 */
fun playerArt(widthDp: Float, roomDp: Float, carousel: Boolean): Float {
    val single = minOf(playerArtMax(widthDp), roomDp, widthDp)
    return if (carousel) minOf(single, widthDp - 2 * (PLAYER_PAGE_GAP_DP + PLAYER_PEEK_MIN_DP)) else single
}

/** The category glyph on a player cover without art: 104 dp up to [PLAYER_ART_BASE_DP], in proportion above it (S10). */
fun playerGlyph(artDp: Float): Float = 104f * maxOf(artDp, PLAYER_ART_BASE_DP.toFloat()) / PLAYER_ART_BASE_DP

/** Does the carousel show in that box (it has to, and its picture isn't too small)? */
fun playerCarousel(widthDp: Float, roomDp: Float, carousel: Boolean): Boolean =
    carousel && playerArt(widthDp, roomDp, true) >= PLAYER_ART_MIN_DP

/** How much of each neighbour shows beside the centred picture [artDp] (dp): the pager's content padding less the gap. */
fun playerPeek(widthDp: Float, artDp: Float): Float = (widthDp - artDp) / 2 - PLAYER_PAGE_GAP_DP

/** A neighbour scaled to [NEIGHBOUR_SCALE] is pulled in by what that took off its near edge, so its near edge stays put (QA L2 a). */
fun playerPull(artDp: Float): Float = artDp * (1 - NEIGHBOUR_SCALE) / 2

/**
 * A list row's picture (design 39 §2.3): the `inSampleSize` that decodes a file of [sidePx] for a slot of [targetPx] -
 * the largest power of two that keeps at least [targetPx] (512 px: 8 for 60 px, 4 for 120 px), so a row never
 * decodes the whole 512 px first.
 */
fun thumbSampleSize(sidePx: Int, targetPx: Int): Int {
    var sample = 1
    while (targetPx > 0 && sidePx / (sample * 2) >= targetPx) sample *= 2
    return sample
}

/** The episode list's sub-line glyph and its gap (dp): 16 + 6. */
const val LIST_LINE_GLYPH_DP = 22f

/**
 * Do the order words stay on the list's line (design 39 §4)? The joined line ([lineDp], measured at the font scale)
 * plus the glyph when it has one, within [availableDp]; else the line drops them - the button's look and its state
 * description carry the order.
 */
fun orderWordsFit(lineDp: Float, glyph: Boolean, availableDp: Float): Boolean =
    lineDp + (if (glyph) LIST_LINE_GLYPH_DP else 0f) <= availableDp

/**
 * The side (px) a cover file of [sidePx] decodes at on a screen of [density] and [screenWidthDp]: what the player's
 * largest cover on that screen needs ([playerArtMax] inside the sides: 224 dp on a 320 dp phone, S10), never more than
 * the file.
 */
fun coverDecodePx(sidePx: Int, density: Float, screenWidthDp: Int): Int {
    val art = playerArtMax((screenWidthDp - 2 * SCREEN_SIDE_DP).toFloat())
    return minOf(sidePx, kotlin.math.ceil(art * density).toInt()).coerceAtLeast(1)
}

// -- "Spill av på" with grouping (design 53 §2.7, §2.12): the sheet's row geometry, dp --

/** A sheet row's side padding, its glyph disc and the gap between its parts (`SheetRow`). */
const val SHEET_ROW_PAD_DP = 14
const val SHEET_DISC_DP = 34
const val SHEET_ROW_GAP_DP = 12

/** A row's end padding when it carries a 48 dp button (the sketch's 6). */
const val SHEET_BUTTON_END_DP = 6

/** The round "+" and "−" (the sketch's 48 dp, the touch target). */
const val GROUP_BUTTON_DP = 48

/** The group block's member lines start under the title: the disc and its gap (the sketch's 38 dp, in its own geometry). */
const val GROUP_INDENT_DP = SHEET_DISC_DP + SHEET_ROW_GAP_DP

/** The "Begge"/"Alle" label's width, the sliders' gap and a member slider's glyph. */
const val GROUP_VOLUME_LABEL_DP = 44
const val GROUP_SLIDER_GAP_DP = 10
const val GROUP_SLIDER_GLYPH_DP = 18

/** The room a "+" row leaves its title and line in a sheet [availableDp] wide (the sheet's content width). */
fun joinRowTextDp(availableDp: Float): Float =
    availableDp - SHEET_ROW_PAD_DP - SHEET_DISC_DP - SHEET_ROW_GAP_DP - SHEET_ROW_GAP_DP - GROUP_BUTTON_DP - SHEET_BUTTON_END_DP

/** The room a member's name (and its failure line) has beside its "−" in the group block. */
fun memberNameDp(availableDp: Float): Float =
    availableDp - SHEET_ROW_PAD_DP - GROUP_INDENT_DP - SHEET_ROW_GAP_DP - GROUP_BUTTON_DP - SHEET_BUTTON_END_DP

/** A member's own slider's track. */
fun memberSliderDp(availableDp: Float): Float =
    availableDp - SHEET_ROW_PAD_DP - GROUP_INDENT_DP - GROUP_SLIDER_GLYPH_DP - GROUP_SLIDER_GAP_DP - SHEET_ROW_PAD_DP

/** The "Begge"/"Alle" slider's track. */
fun groupSliderDp(availableDp: Float): Float =
    availableDp - SHEET_ROW_PAD_DP - GROUP_INDENT_DP - GROUP_VOLUME_LABEL_DP - GROUP_SLIDER_GAP_DP - SHEET_ROW_PAD_DP

/** The group block's title and line beside the selected row's 22 dp check. */
fun groupTitleDp(availableDp: Float): Float = availableDp - 2 * SHEET_ROW_PAD_DP - SHEET_DISC_DP - 2 * SHEET_ROW_GAP_DP - 22
