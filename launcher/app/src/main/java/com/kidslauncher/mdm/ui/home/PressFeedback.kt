package com.kidslauncher.mdm.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.shapes.OvalShape
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.view.animation.DecelerateInterpolator
import com.kidslauncher.mdm.R

/**
 * Design 34 P2's press: the icon scales to [PRESSED_SCALE] and darkens [PRESSED_SHADE] while the
 * finger is down - in [PRESS_MS], back in [RELEASE_MS], decelerating, no overshoot. Pure: the view
 * side is a [Host]. [onAction] **always returns false** (it never consumes an event, not even a
 * DOWN), so the cell's click, long-press and Home's swipes see exactly the events they saw before
 * (QA L5). With "Remove animations" on ([Host.animatorsEnabled] false) nothing animates: the state
 * is set at once.
 *
 * In a scrolling parent the press waits [Host.tapDelayMs] (Android's own pressed-state delay), so
 * the start of a scroll - which ends in a CANCEL - doesn't twitch the icon; a tap shorter than
 * that still gets the whole press and release on UP. Like Android's pressed state, the press
 * follows the finger (qa-34-s2-code M1): a MOVE outside the cell (with the touch slop) releases
 * as a CANCEL does, and nothing more happens until the next DOWN - a lift outside never replays
 * the press.
 */
class PressMotion(private val host: Host) {
    interface Host {
        val animatorsEnabled: Boolean
        val tapDelayMs: Long

        /** 0 = rest, 1 = pressed, at once. */
        fun jump(to: Float)

        /** From where it is to [to] in [durationMs]; [then] runs when it got there (not when cancelled). */
        fun animate(to: Float, durationMs: Long, then: (() -> Unit)?)

        fun postDelayed(action: Runnable, delayMs: Long)
        fun removeCallbacks(action: Runnable)
    }

    private var pending = false

    /** The finger left the cell: everything up to the next DOWN is ignored. */
    private var left = false

    /** Where the last move was headed (0 = rest); a release to rest from rest is skipped (QA N1). */
    private var target = 0f

    private val pressLater = Runnable {
        pending = false
        moveTo(1f, PRESS_MS, null)
    }

    /**
     * One touch event's action ([MotionEvent.getActionMasked]) and whether it is inside the cell
     * (with the touch slop); the answer is always false.
     */
    fun onAction(action: Int, inside: Boolean = true): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                left = false
                cancelPending()
                // A new touch during a quick tap's press: back to rest first (QA N2).
                if (target != 0f) moveTo(0f, RELEASE_MS, null)
                if (host.tapDelayMs > 0) {
                    pending = true
                    host.postDelayed(pressLater, host.tapDelayMs)
                } else {
                    moveTo(1f, PRESS_MS, null)
                }
            }
            MotionEvent.ACTION_MOVE -> if (!left && !inside) {
                left = true
                release()
            }
            MotionEvent.ACTION_UP -> if (!left) {
                if (pending) {
                    cancelPending()
                    moveTo(1f, PRESS_MS) { moveTo(0f, RELEASE_MS, null) }
                } else {
                    release()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                left = false
                release()
            }
        }
        return false
    }

    /** Back to rest at once, nothing pending (a recycled or detached cell, QA L2). */
    fun reset() {
        cancelPending()
        left = false
        target = 0f
        host.jump(0f)
    }

    private fun release() {
        cancelPending()
        if (target != 0f) moveTo(0f, RELEASE_MS, null)
    }

    private fun cancelPending() {
        if (pending) host.removeCallbacks(pressLater)
        pending = false
    }

    private fun moveTo(to: Float, durationMs: Long, then: (() -> Unit)?) {
        target = to
        if (host.animatorsEnabled) {
            host.animate(to, durationMs, then)
        } else {
            // No animation at all: a quick tap ends at rest, as the UP asked.
            if (then != null) then() else host.jump(to)
        }
    }

    companion object {
        const val PRESSED_SCALE = 0.94f
        const val PRESSED_SHADE = 0.10f
        const val PRESS_MS = 90L
        const val RELEASE_MS = 140L
        const val ELEVATION_DP = 2f

        /** The press waits Android's tap timeout only in a scrolling parent. */
        fun tapDelay(scrolling: Boolean, tapTimeoutMs: Long): Long = if (scrolling) tapTimeoutMs else 0L

        /** Whether ([x], [y]) lies in a [width] x [height] view, widened by [slop] - Android's own test. */
        fun inside(x: Float, y: Float, width: Int, height: Int, slop: Float): Boolean =
            x >= -slop && y >= -slop && x < width + slop && y < height + slop
    }
}

/**
 * The view side of design 34 P2: gentle depth on round icons and avatars, and the press.
 * Depth is drawn by the view (an oval outline, [PressMotion.ELEVATION_DP], black spot shadow), never
 * baked into the cached bitmaps, which the glance page and the time-rule screen share (QA M2).
 * An icon whose own background is translucent (an adaptive icon without a monochrome layer whose
 * background layer isn't opaque) shows the shadow through as a faint grey disc - rare, accepted
 * (qa-34-s2-code N6).
 */
object PressFeedback {
    private val OVAL = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    /** The focus ring's outer edge sits this far outside the icon (a 2 dp ring drawn inside its
     * layer, qa-34-s2-code M2); Home's contacts leave room for it (`contactRoomDp`, design 34 P4). */
    internal const val FOCUS_RING_GAP_DP = 4f
    internal const val FOCUS_RING_STROKE_DP = 2f

    /** An oval outline and a small elevation; the parents must not clip it (`clipChildren=false`). */
    fun depth(target: View) {
        target.outlineProvider = OVAL
        target.elevation = KidAvatars.dp(target.context, PressMotion.ELEVATION_DP).toFloat()
        target.outlineSpotShadowColor = Color.BLACK
        target.outlineAmbientShadowColor = Color.BLACK
    }

    /**
     * The cell's ripple, masked to an oval of the icon's size where the icon sits ([iconTopPx] below
     * the cell's top, centred) - the click stays on the whole cell (>= 48 dp target) - and its focus
     * ring: the ripple lies under the opaque, elevated icon, so keyboard/D-pad focus gets a static
     * 2 dp peach ring 4 dp outside the icon, drawn as the cell's foreground (above the icon) only
     * while focused. Built once per icon size (QA L1): a rebind with the same size keeps them.
     */
    fun cellBackground(cell: View, iconPx: Int, iconTopPx: Int) {
        val key = iconPx to iconTopPx
        if (cell.getTag(R.id.press_cell_size) == key) return
        cell.setTag(R.id.press_cell_size, key)
        val context = cell.context
        val mask = LayerDrawable(arrayOf(ShapeDrawable(OvalShape()))).apply {
            setLayerSize(0, iconPx, iconPx)
            setLayerGravity(0, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setLayerInsetTop(0, iconTopPx)
        }
        val highlight = TypedValue().let {
            context.theme.resolveAttribute(android.R.attr.colorControlHighlight, it, true)
            if (it.resourceId != 0) context.getColor(it.resourceId) else it.data
        }
        cell.background = RippleDrawable(ColorStateList.valueOf(highlight), null, mask)

        val gap = KidAvatars.dp(context, FOCUS_RING_GAP_DP)
        val ring = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.TRANSPARENT)
            setStroke(KidAvatars.dp(context, FOCUS_RING_STROKE_DP), context.getColor(R.color.kid_accent))
        }
        val placed = LayerDrawable(arrayOf(ring)).apply {
            setLayerSize(0, iconPx + 2 * gap, iconPx + 2 * gap)
            setLayerGravity(0, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setLayerInsetTop(0, maxOf(0, iconTopPx - gap))
        }
        cell.foreground = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), placed)
        }
    }

    /**
     * A contact (`item_kid_contact`, Home's row or the phone book): depth on the avatar, the press
     * on it, the ripple and focus ring around it. The avatar sits [CONTACT_AVATAR_TOP_DP] below the
     * item's top in both (4 dp padding + the frame's 6 dp for the badge). The call badge is lifted
     * with it so it stays drawn above the avatar (no shadow of its own); the missed-call badge gets
     * that from `KidBadge`.
     */
    fun contact(item: View, avatarPx: Int) {
        val avatar = item.findViewById<View>(R.id.contact_avatar)
        depth(avatar)
        item.findViewById<View>(R.id.contact_call_badge)?.apply {
            outlineProvider = null
            elevation = avatar.elevation
        }
        cellBackground(item, avatarPx, KidAvatars.dp(item.context, CONTACT_AVATAR_TOP_DP))
        attach(item, avatar, scrolling = true)
    }

    private const val CONTACT_AVATAR_TOP_DP = 10f

    /** The grid's icon sits 4 dp below the cell's top (`tile_frame` is 4 dp taller, for the badge). */
    const val TILE_ICON_TOP_DP = 4f

    /** Back to rest at once (a recycled cell, QA L2); nothing when [attach] never ran on it. */
    fun reset(cell: View) {
        (cell.getTag(R.id.press_motion) as? PressMotion)?.reset()
    }

    /**
     * Presses [target] while [cell] is touched. The listener returns what [PressMotion.onAction]
     * returns - always false - so it never takes an event from the click, the long-press or Home's
     * swipes; the click still goes through `onTouchEvent` -> `performClick`, and accessibility
     * clicks don't come through touch at all (hence the lint suppression). [scrolling]: the cell
     * sits in a scrolling parent (the press waits the tap timeout). Detaching the cell resets it.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun attach(cell: View, target: View, scrolling: Boolean) {
        val shade = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
            alpha = 0
        }
        target.foreground = shade
        val slop = ViewConfiguration.get(cell.context).scaledTouchSlop.toFloat()
        var animator: ValueAnimator? = null
        var fraction = 0f
        fun show(f: Float) {
            fraction = f
            val scale = 1f - (1f - PressMotion.PRESSED_SCALE) * f
            target.scaleX = scale
            target.scaleY = scale
            shade.alpha = (255 * PressMotion.PRESSED_SHADE * f).toInt()
        }
        val motion = PressMotion(object : PressMotion.Host {
            override val animatorsEnabled get() = ValueAnimator.areAnimatorsEnabled()
            override val tapDelayMs get() = PressMotion.tapDelay(scrolling, ViewConfiguration.getTapTimeout().toLong())

            override fun jump(to: Float) {
                animator?.cancel()
                animator = null
                show(to)
            }

            override fun animate(to: Float, durationMs: Long, then: (() -> Unit)?) {
                animator?.cancel()
                animator = null
                if (fraction == to) {
                    then?.invoke()
                    return
                }
                animator = ValueAnimator.ofFloat(fraction, to).apply {
                    duration = durationMs
                    interpolator = DecelerateInterpolator()
                    addUpdateListener { show(it.animatedValue as Float) }
                    var cancelled = false
                    addListener(object : AnimatorListenerAdapter() {
                        override fun onAnimationCancel(animation: Animator) {
                            cancelled = true
                        }

                        override fun onAnimationEnd(animation: Animator) {
                            if (!cancelled) then?.invoke()
                        }
                    })
                    start()
                }
            }

            override fun postDelayed(action: Runnable, delayMs: Long) {
                cell.postDelayed(action, delayMs)
            }

            override fun removeCallbacks(action: Runnable) {
                cell.removeCallbacks(action)
            }
        })
        cell.setTag(R.id.press_motion, motion)
        cell.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) = motion.reset()
        })
        // Pinned by PressFeedbackTest: keep it one expression that returns onAction's answer.
        cell.setOnTouchListener { v, event -> motion.onAction(event.actionMasked, PressMotion.inside(event.x, event.y, v.width, v.height, slop)) }
    }
}
