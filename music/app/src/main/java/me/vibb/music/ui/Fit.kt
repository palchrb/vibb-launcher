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

/** The carousel's 180 dp cover, centred: the room left on each side of it (dp). */
fun carouselSide(availableDp: Float): Float = ((availableDp - CAROUSEL_COVER_DP) / 2).coerceAtLeast(0f)

const val CAROUSEL_COVER_DP = 180
const val CAROUSEL_SPACING_DP = 8

/**
 * How much of a neighbouring cover shows beside the centred one (dp): pages are 180 dp apart by 8 dp,
 * a neighbour is scaled to 0.733 about its centre and pulled in by as much as that took off its near
 * edge (24 dp), so its near edge stays where the page starts - 46 dp on the Jelly Star.
 */
fun carouselPeek(availableDp: Float): Float = availableDp - (carouselSide(availableDp) + CAROUSEL_COVER_DP + CAROUSEL_SPACING_DP)
