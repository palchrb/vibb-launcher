package com.kidslauncher.mdm.calls

import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * Swipe down closes the kid's sheets (2026-11-17): the pure rules behind [KidSheetDragFrame], so the JVM tests run
 * them. Distances are pixels, velocities pixels per second, positive downwards.
 */
internal object KidSheetDrag {

    /** What a touch that has moved ([dx], [dy] from where it went down) is so far. */
    enum class Start {
        /** Still inside the touch slop: a tap, the sheet's buttons get it as before. */
        UNDECIDED,

        /** A vertical move downwards past the slop: the sheet follows the finger, a button under it loses its press. */
        SHEET,

        /** Upwards, sideways, or on content that can still scroll up: the sheet stays where it is for this touch. */
        NOT_SHEET,
    }

    /** Past the slop the sheet takes a downward, mostly vertical move - unless the content under the finger still scrolls up. */
    fun start(dx: Float, dy: Float, slop: Float, contentScrolledDown: Boolean): Start = when {
        abs(dy) <= slop && abs(dx) <= slop -> Start.UNDECIDED
        dy <= 0f || abs(dx) > abs(dy) -> Start.NOT_SHEET
        contentScrolledDown -> Start.NOT_SHEET
        else -> Start.SHEET
    }

    /** The sheet's offset while dragging: never above its resting place (no move up, no rubber band). */
    fun offset(base: Float, moved: Float): Float = (base + moved).coerceAtLeast(0f)

    /**
     * On release: close on a fast fling down ([velocity] at least [flingVelocity]), spring back on a fast fling up,
     * else close when dragged past a third of the sheet's [height].
     */
    fun closes(offset: Float, height: Float, velocity: Float, flingVelocity: Float): Boolean = when {
        offset <= 0f || height <= 0f -> false
        velocity >= flingVelocity -> true
        velocity <= -flingVelocity -> false
        else -> offset > height / 3f
    }

    /** The dim behind, fading with the drag: [base] at rest, nothing with the sheet all the way down. */
    fun dim(base: Float, offset: Float, height: Float): Float =
        if (height <= 0f) base else base * (1f - (offset / height).coerceIn(0f, 1f))

    /** The settle's length: [FULL_MS] for the whole height, shorter for the rest of the way, never under [MIN_MS]. */
    fun settleMs(from: Float, to: Float, height: Float): Long {
        if (height <= 0f) return MIN_MS
        val share = (abs(to - from) / height).coerceIn(0f, 1f)
        return (FULL_MS * share).roundToLong().coerceAtLeast(MIN_MS)
    }

    const val FULL_MS = 200L
    const val MIN_MS = 100L

    /** A release at least this fast (dp per second) is a fling. */
    const val FLING_DP_PER_S = 1000f
}
