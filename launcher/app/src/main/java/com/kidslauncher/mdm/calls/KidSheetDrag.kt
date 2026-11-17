package com.kidslauncher.mdm.calls

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Swipe down closes the kid's sheets (2026-11-17): the pure rules behind [KidSheetDragFrame], so the JVM tests run
 * them. Distances are pixels, velocities pixels per second, positive downwards; `density` is pixels per dp.
 */
internal object KidSheetDrag {

    /** What a touch that has moved (`dx`, `dy` from where it went down) is so far. */
    enum class Start {
        /** Still inside the start threshold: a tap, the sheet's buttons get it as before. */
        UNDECIDED,

        /** A vertical move downwards past the threshold: the sheet follows the finger, a button under it loses its press. */
        SHEET,

        /** Upwards, sideways, or on content that can still scroll up: the sheet stays where it is for this touch. */
        NOT_SHEET,
    }

    /**
     * The move a touch needs before it can be the sheet's (code QA M2): the system's touch [slop], but on a button
     * ([onChild]: a child took the touch) at least [BUTTON_SLOP_DP] and twice the slop - a hurried tap on 112 or Ring
     * that slips down stays a tap. Inside a list that scrolls ([inScroller]) it stays the system's slop: the list takes
     * a move at that slop itself, so a larger one would leave the sheet nothing at the list's top.
     */
    fun startSlop(slop: Float, density: Float, onChild: Boolean, inScroller: Boolean): Float =
        if (onChild && !inScroller) max(2f * slop, BUTTON_SLOP_DP * density) else slop

    /** Past [slop] the sheet takes a downward, mostly vertical move - unless the content under the finger still scrolls up. */
    fun start(dx: Float, dy: Float, slop: Float, contentScrolledDown: Boolean): Start = when {
        abs(dy) <= slop && abs(dx) <= slop -> Start.UNDECIDED
        dy <= 0f || abs(dx) > abs(dy) -> Start.NOT_SHEET
        contentScrolledDown -> Start.NOT_SHEET
        else -> Start.SHEET
    }

    /** The sheet's offset while dragging: never above its resting place (no move up, no rubber band). */
    fun offset(base: Float, moved: Float): Float = (base + moved).coerceAtLeast(0f)

    /** How far down a sheet must be before a fling closes it (code QA M2): [MIN_FLING_TRAVEL_DP], or an eighth of [height]. */
    fun minFlingTravel(height: Float, density: Float): Float = max(MIN_FLING_TRAVEL_DP * density, height / 8f)

    /**
     * On release after a drag: close on a fast fling down ([velocity] at least [FLING_DP_PER_S]) once the sheet is
     * [minFlingTravel] down, spring back on a fast fling up, else close when dragged past a third of its [height].
     */
    fun closes(offset: Float, height: Float, velocity: Float, density: Float): Boolean {
        if (offset <= 0f || height <= 0f) return false
        val fling = FLING_DP_PER_S * density
        return when {
            velocity >= fling && offset >= minFlingTravel(height, density) -> true
            velocity <= -fling -> false
            else -> offset > height / 3f
        }
    }

    /** What the sheet does when a touch ends. */
    enum class Release {
        /** Nothing: the sheet is at rest, or a spring-back already under way carries on. */
        STAY,
        SPRING_BACK,
        CLOSE,
    }

    /**
     * The release (code QA L4): a touch that never [dragged] never closes the sheet (a tap while it springs back leaves
     * the spring-back running, [settling]); a [cancelled] drag springs back; else [closes] decides.
     */
    fun release(
        dragged: Boolean,
        cancelled: Boolean,
        offset: Float,
        height: Float,
        velocity: Float,
        density: Float,
        settling: Boolean,
    ): Release = when {
        offset <= 0f -> Release.STAY
        !dragged -> if (settling) Release.STAY else Release.SPRING_BACK
        cancelled -> Release.SPRING_BACK
        closes(offset, height, velocity, density) -> Release.CLOSE
        else -> Release.SPRING_BACK
    }

    /** The dim behind, fading with the drag: [base] at rest, nothing with the sheet all the way down. */
    fun dim(base: Float, offset: Float, height: Float): Float =
        if (height <= 0f) base else base * (1f - (offset / height).coerceIn(0f, 1f))

    /**
     * Whether the window's dim moves from [shown] to [next] (code QA L6): each change is a window relayout, so only in
     * steps of [DIM_STEP], and always onto the ends (none, [base]).
     */
    fun dimChanges(shown: Float, next: Float, base: Float): Boolean =
        next != shown && (abs(next - shown) >= DIM_STEP || next == 0f || next == base)

    /** A settle: its length and whether it decelerates from the finger's speed or eases in and out. */
    data class Settle(val ms: Long, val decelerate: Boolean)

    /**
     * The settle from [from] to [to] (code QA L9). A decelerate curve starts at twice its average speed, so after a
     * release moving towards [to] fast enough it gets the length that starts it at the finger's [velocity]
     * ([MIN_MS]..[FULL_MS]). After a slow or opposite release it eases in and out: [FULL_MS] for the whole [height],
     * shorter for the rest of the way, never under [MIN_MS] - no jolt after a calm drag.
     */
    fun settle(from: Float, to: Float, height: Float, velocity: Float): Settle {
        val distance = abs(to - from)
        val share = if (height <= 0f) 0f else (distance / height).coerceIn(0f, 1f)
        val eased = Settle((FULL_MS * share).roundToLong().coerceIn(MIN_MS, FULL_MS), decelerate = false)
        val towards = if (to > from) velocity else -velocity
        if (distance <= 0f || towards <= 0f) return eased
        val matchedMs = 2f * distance * 1000f / towards
        return if (matchedMs <= FULL_MS) Settle(matchedMs.roundToLong().coerceAtLeast(MIN_MS), decelerate = true) else eased
    }

    const val FULL_MS = 200L
    const val MIN_MS = 100L

    /** A release at least this fast (dp per second) is a fling. */
    const val FLING_DP_PER_S = 1000f

    /** A fling closes only once the sheet is this far down (dp; or an eighth of its height). */
    const val MIN_FLING_TRAVEL_DP = 48f

    /** The least move (dp) before a touch on a button can be the sheet's. */
    const val BUTTON_SLOP_DP = 24f

    /** The dim's step between window updates. */
    const val DIM_STEP = 0.05f
}
