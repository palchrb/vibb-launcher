package com.kidslauncher.mdm.ui.home

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.ShapeDrawable
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
 * that still gets the whole press and release on UP.
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
    private val pressLater = Runnable {
        pending = false
        moveTo(1f, PRESS_MS, null)
    }

    /** One touch event's action ([MotionEvent.getActionMasked]); the answer is always false. */
    fun onAction(action: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                cancelPending()
                if (host.tapDelayMs > 0) {
                    pending = true
                    host.postDelayed(pressLater, host.tapDelayMs)
                } else {
                    moveTo(1f, PRESS_MS, null)
                }
            }
            MotionEvent.ACTION_UP -> if (pending) {
                cancelPending()
                moveTo(1f, PRESS_MS) { moveTo(0f, RELEASE_MS, null) }
            } else {
                moveTo(0f, RELEASE_MS, null)
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelPending()
                moveTo(0f, RELEASE_MS, null)
            }
        }
        return false
    }

    private fun cancelPending() {
        if (pending) host.removeCallbacks(pressLater)
        pending = false
    }

    private fun moveTo(to: Float, durationMs: Long, then: (() -> Unit)?) {
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
    }
}

/**
 * The view side of design 34 P2: gentle depth on round icons and avatars, and the press.
 * Depth is drawn by the view (an oval outline, [PressMotion.ELEVATION_DP], black spot shadow), never
 * baked into the cached bitmaps, which the glance page and the time-rule screen share (QA M2).
 */
object PressFeedback {
    private val OVAL = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) {
            outline.setOval(0, 0, view.width, view.height)
        }
    }

    /** An oval outline and a small elevation; the parents must not clip it (`clipChildren=false`). */
    fun depth(target: View) {
        target.outlineProvider = OVAL
        target.elevation = KidAvatars.dp(target.context, PressMotion.ELEVATION_DP).toFloat()
        target.outlineSpotShadowColor = Color.BLACK
        target.outlineAmbientShadowColor = Color.BLACK
    }

    /**
     * The cell's ripple, masked to an oval of the icon's size where the icon sits ([iconTopPx] below
     * the cell's top, centred) - the click stays on the whole cell (>= 48 dp target).
     */
    fun ovalRipple(cell: View, iconPx: Int, iconTopPx: Int) {
        val mask = LayerDrawable(arrayOf(ShapeDrawable(OvalShape()))).apply {
            setLayerSize(0, iconPx, iconPx)
            setLayerGravity(0, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            setLayerInsetTop(0, iconTopPx)
        }
        val highlight = TypedValue().let {
            cell.context.theme.resolveAttribute(android.R.attr.colorControlHighlight, it, true)
            if (it.resourceId != 0) cell.context.getColor(it.resourceId) else it.data
        }
        cell.background = RippleDrawable(ColorStateList.valueOf(highlight), null, mask)
    }

    /**
     * A contact (`item_kid_contact`, Home's row or the phone book): depth on the avatar, the press
     * on it, the ripple masked to it. The avatar sits [CONTACT_AVATAR_TOP_DP] below the item's top in
     * both (4 dp padding + the frame's 6 dp for the badge). The call badge is lifted with it so it
     * stays drawn above the avatar (no shadow of its own); the missed-call badge gets that from
     * `KidBadge`.
     */
    fun contact(item: View, avatarPx: Int) {
        val avatar = item.findViewById<View>(R.id.contact_avatar)
        depth(avatar)
        item.findViewById<View>(R.id.contact_call_badge)?.apply {
            outlineProvider = null
            elevation = avatar.elevation
        }
        ovalRipple(item, avatarPx, KidAvatars.dp(item.context, CONTACT_AVATAR_TOP_DP))
        attach(item, avatar, scrolling = true)
    }

    private const val CONTACT_AVATAR_TOP_DP = 10f

    /** The grid's icon sits 4 dp below the cell's top (`tile_frame` is 4 dp taller, for the badge). */
    const val TILE_ICON_TOP_DP = 4f

    /**
     * Presses [target] while [cell] is touched. The listener returns what [PressMotion.onAction]
     * returns - always false - so it never takes an event from the click, the long-press or Home's
     * swipes. [scrolling]: the cell sits in a scrolling parent (the press waits the tap timeout).
     */
    fun attach(cell: View, target: View, scrolling: Boolean) {
        val shade = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.BLACK)
            alpha = 0
        }
        target.foreground = shade
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
            override val tapDelayMs get() = if (scrolling) ViewConfiguration.getTapTimeout().toLong() else 0L

            override fun jump(to: Float) {
                animator?.cancel()
                animator = null
                show(to)
            }

            override fun animate(to: Float, durationMs: Long, then: (() -> Unit)?) {
                animator?.cancel()
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
        cell.setOnTouchListener { _, event -> motion.onAction(event.actionMasked) }
    }
}
