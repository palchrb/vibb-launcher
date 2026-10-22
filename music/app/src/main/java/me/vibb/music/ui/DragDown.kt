package me.vibb.music.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Velocity

/*
 * A drag down that closes (design r8 §2, §3; ours, the user's ask of 2026-10-21, as Android's sheets do): the output
 * and sleep sheets, and the player back to the library. The surface follows the finger down, never above its place;
 * on release it closes past a share of its height or on a downward fling, else it springs back. A scrolling list
 * inside scrolls first (nested scroll). The rules are pure; DragDownTest.
 */

/** A release this far down a surface (its share of the height) closes it... */
const val DRAG_CLOSE_SHARE = 0.25f

/** ... but never less far than this (dp): a small sheet doesn't close on a twitch. */
const val DRAG_CLOSE_MIN_DP = 56

/** A downward fling faster than this (dp/s) closes it however short the drag; an upward one this fast never does. */
const val DRAG_CLOSE_FLING_DP = 400

/** Does a release [offsetPx] down a surface [extentPx] tall, moving at [velocityPxPerS] (down positive), close it? [density]: px per dp. */
fun dragCloses(offsetPx: Float, velocityPxPerS: Float, extentPx: Float, density: Float): Boolean {
    val fling = DRAG_CLOSE_FLING_DP * density
    return when {
        velocityPxPerS > fling -> true
        velocityPxPerS < -fling -> false
        else -> offsetPx >= maxOf(extentPx * DRAG_CLOSE_SHARE, DRAG_CLOSE_MIN_DP * density)
    }
}

/** The offset after a drag of [delta]: never above the surface's place. */
fun dragOffset(offset: Float, delta: Float): Float = (offset + delta).coerceAtLeast(0f)

/** Before a list inside scrolls: an upward [dy] goes to the moved surface first (at most back to its place). */
fun preScrollTake(offset: Float, dy: Float): Float = if (dy < 0 && offset > 0) maxOf(dy, -offset) else 0f

/** After a list inside scrolled: a finger's downward leftover (the list at its top) moves the surface; a fling's never. */
fun postScrollTake(dy: Float, source: NestedScrollSource): Float = if (dy > 0 && source == NestedScrollSource.UserInput) dy else 0f

/**
 * The surface follows a vertical drag down and [onClose]s on a release [dragCloses] says (no exit animation: the caller
 * takes it away), else springs back (snaps with animations off). The drag sits outside the moving layer, so its deltas
 * don't shift as the layer moves; horizontal gestures inside (a slider, a pager) keep theirs - who passes their own
 * touch slop first wins.
 */
@Composable
fun Modifier.dragDownToClose(onClose: () -> Unit): Modifier {
    val close by rememberUpdatedState(onClose)
    val density by rememberUpdatedState(LocalDensity.current.density)
    var offset by remember { mutableFloatStateOf(0f) }
    var extent by remember { mutableIntStateOf(0) }
    val drag = rememberDraggableState { delta -> offset = dragOffset(offset, delta) }
    val settle: suspend (Float) -> Unit = remember(drag) {
        { velocity ->
            if (dragCloses(offset, velocity, extent.toFloat(), density)) {
                close()
                offset = 0f
            } else {
                // a new drag (UserInput) cancels the spring
                drag.drag(MutatePriority.Default) {
                    if (animationsOn()) animate(offset, 0f, velocity) { value, _ -> dragBy(value - offset) } else dragBy(-offset)
                }
            }
        }
    }
    val connection = remember(drag) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val take = preScrollTake(offset, available.y)
                if (take != 0f) drag.dispatchRawDelta(take)
                return Offset(0f, take)
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val take = postScrollTake(available.y, source)
                if (take != 0f) drag.dispatchRawDelta(take)
                return Offset(0f, take)
            }

            // a release with the surface moved settles it; the list doesn't fling
            override suspend fun onPreFling(available: Velocity): Velocity {
                if (offset <= 0f) return Velocity.Zero
                settle(available.y)
                return available
            }
        }
    }
    return onSizeChanged { extent = it.height }
        .draggable(drag, Orientation.Vertical, onDragStopped = { settle(it) })
        .nestedScroll(connection)
        .graphicsLayer { translationY = offset }
}
