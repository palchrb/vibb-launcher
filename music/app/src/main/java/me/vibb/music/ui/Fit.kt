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

/**
 * A category tile as wide as its one-line label needs ([labelDp], measured at the font scale) and never narrower than
 * [categoryTileWidth] - the row scrolls when the labels need more (design 34 step 11, emulator round 2026-10-14 N6: at
 * font scale 2.0 "Musikk" and "Podkaster" were cut to "Mu…" and "Po…"); at most the whole row.
 */
fun categoryTileWidthFor(availableDp: Float, labelDp: Float): Float =
    maxOf(categoryTileWidth(availableDp), kotlin.math.ceil(labelDp) + CATEGORY_LABEL_SLACK_DP).coerceAtMost(availableDp)

/** A label's measured width gets this much more (sub-pixel rounding, so it never ellipsizes by a hair). */
const val CATEGORY_LABEL_SLACK_DP = 2f

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
 * min(180, what is left), never under 64 - below that the slot scrolls, so nothing ever slides under the tiles or
 * the bar. Everything is in whole pixels, each fixed part rounded as Compose rounds a Dp (qa-34-s11-code L1: at
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

/** The library's cover in pixels and whether the slot scrolls. */
data class LibraryFit(val coverPx: Int, val scrolls: Boolean)

/**
 * The library's cover for a slot of [slotPx] holding the measured title and hint lines ([linesPx]): min(180 dp, what
 * is left once [reservePx] is also kept free), floor [LIBRARY_COVER_MIN_DP]. [reservePx] is the now-playing bar's room (and its gap)
 * while there is no bar, so the cover is the same with and without it (qa-34-s11-code N2: no 180/174 jump when the
 * bar comes or goes). The slot scrolls only when the content is taller than the slot itself.
 */
fun libraryFit(slotPx: Int, linesPx: Int, reservePx: Int, density: Float): LibraryFit {
    val room = slotPx - reservePx - linesPx - libraryFixedPx(density)
    val cover = room.coerceIn(dpToPx(LIBRARY_COVER_MIN_DP, density), dpToPx(CAROUSEL_COVER_DP, density))
    return LibraryFit(cover, librarySlotContentPx(cover, linesPx, density) > slotPx)
}

/** The slot's content in pixels with a cover of [coverPx]. */
fun librarySlotContentPx(coverPx: Int, linesPx: Int, density: Float): Int = coverPx + linesPx + libraryFixedPx(density)

/**
 * The now-playing bar's room in pixels, its gap above it included: 6 dp above and below the larger of the 44 dp
 * play button and its two lines ([linesPx], 13 + 11 sp, measured), and the column's 8 dp gap.
 */
fun nowBarRoomPx(linesPx: Int, density: Float): Int =
    2 * dpToPx(6, density) + maxOf(dpToPx(44, density), linesPx) + dpToPx(CAROUSEL_GAP_DP, density)

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

/** The side (px) a cover file of [sidePx] decodes at on a screen of [density]: what the player's 224 dp needs, never more than the file. */
fun coverDecodePx(sidePx: Int, density: Float): Int = minOf(sidePx, kotlin.math.ceil(PLAYER_ART_MAX_DP * density).toInt()).coerceAtLeast(1)

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
