package com.kidslauncher.mdm.calls

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout

/**
 * Swipe down closes the kid's sheets (2026-11-17): the frame [setUpKidSheet] puts around a sheet's content. The
 * content follows a finger dragging down, the dim behind fades with it; on release it closes ([Dialog.dismiss], so
 * every dismiss listener runs as for Lukk) past a third of its height or on a fast fling down once it is a little way
 * down, else springs back. The rules are [KidSheetDrag]'s.
 *
 * A move inside the start threshold stays a tap ([KidSheetDrag.startSlop]: larger on a button, so a hurried tap on 112
 * that slips stays a tap); a downward drag past it that starts on a button takes the touch (the button gets a cancel,
 * so no click). Upwards and sideways nothing moves. On content that can still scroll up (the emergency sheet's rows)
 * the drag is the content's; at its top the sheet's. A sheet that loses its window focus ("Bilde av …" opened over it
 * by the photo's long-press) lets go of the touch. Lukk, Back and tap-outside are untouched (Back is the dialog's own;
 * a tap above the sheet lies outside this frame). With animations off (the animator duration scale 0, "Remove
 * animations") it closes or settles at once. No accessibility node of its own, so TalkBack's order is the content's.
 */
@SuppressLint("ViewConstructor")
internal class KidSheetDragFrame(context: Context, private val dialog: Dialog) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val density = resources.displayMetrics.density

    // Read at the first drag, when the window has the theme's dim (code QA L7), not from a caller's order of calls.
    private var baseDim = Float.NaN
    private var shownDim = Float.NaN

    private var velocity: VelocityTracker? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var startSlop = 0f
    private var dragFromY = 0f
    private var dragBase = 0f
    private var state = KidSheetDrag.Start.UNDECIDED
    private var settle: ValueAnimator? = null
    private var closing = false

    private val sheet: View? get() = getChildAt(0)

    /**
     * The sheet's own dim ([setUpKidSheet]'s `ownScrim`, the brightness sheet): faded by its alpha instead of the
     * window's dim, which Android lets take the navigation bar's light buttons (code QA M3).
     */
    var scrim: View? = null

    /** With [scrim]: the sheet slides up from below on its first frame and the scrim fades in with it. */
    fun slideIn() {
        viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                viewTreeObserver.removeOnPreDrawListener(this)
                val view = sheet ?: return true
                moveTo(view.height.toFloat())
                settleTo(0f, 0f, null)
                return true
            }
        })
    }

    /** A tap on [scrim]: the sheet slides down and the dialog closes, as a drag past the line does. */
    fun slideClose() {
        if (!closing) close(0f)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        // A DOWN seen here may go on to a child; onTouchEvent sees it only when no child took it.
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) begin(ev, onChild = true) else track(ev)
        return state == KidSheetDrag.Start.SHEET
    }

    @SuppressLint("ClickableViewAccessibility") // no click here: Lukk is the accessible way to close
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) begin(ev, onChild = false) else track(ev)
        return true
    }

    /** One step of the gesture after its DOWN, from either path. */
    private fun track(ev: MotionEvent) {
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return
        when (ev.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(pointerId)
                if (index < 0) return
                velocity?.addMovement(ev)
                val x = ev.getX(index)
                val y = ev.getY(index)
                if (state == KidSheetDrag.Start.UNDECIDED) {
                    // A sheet without window focus has another over it: this touch is not the sheet's (and
                    // onWindowFocusChanged lets go of it when the focus leaves later).
                    state = if (!hasWindowFocus()) {
                        KidSheetDrag.Start.NOT_SHEET
                    } else {
                        KidSheetDrag.start(x - downX, y - downY, startSlop, anyAt(this, downX, downY) { it.canScrollVertically(-1) })
                    }
                    if (state == KidSheetDrag.Start.SHEET) {
                        // Only now does the touch catch a spring-back (code QA L3/L4): a tap leaves it running.
                        settle?.cancel()
                        settle = null
                        dragBase = sheet?.translationY ?: 0f
                        dragFromY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (state == KidSheetDrag.Start.SHEET) moveTo(KidSheetDrag.offset(dragBase, y - dragFromY))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(ev.actionIndex) == pointerId) {
                    // The UP goes into the tracker too (code QA M1): a finger held still before it lifts reads as still.
                    velocity?.addMovement(ev)
                    end(cancelled = false)
                }
            }
            MotionEvent.ACTION_UP -> {
                velocity?.addMovement(ev)
                end(cancelled = false)
            }
            MotionEvent.ACTION_CANCEL -> end(cancelled = true)
        }
    }

    private fun begin(ev: MotionEvent, onChild: Boolean) {
        velocity?.recycle()
        velocity = VelocityTracker.obtain().apply { addMovement(ev) }
        pointerId = ev.getPointerId(0)
        downX = ev.x
        downY = ev.y
        val inScroller = anyAt(this, downX, downY) { it.canScrollVertically(-1) || it.canScrollVertically(1) }
        startSlop = KidSheetDrag.startSlop(slop, density, onChild, inScroller)
        state = KidSheetDrag.Start.UNDECIDED
    }

    private fun end(cancelled: Boolean) {
        if (pointerId == MotionEvent.INVALID_POINTER_ID) return
        val dragged = state == KidSheetDrag.Start.SHEET
        val tracker = velocity
        var speed = 0f
        if (tracker != null && dragged && !cancelled) {
            tracker.computeCurrentVelocity(1000, maxFling)
            speed = tracker.getYVelocity(pointerId)
        }
        tracker?.recycle()
        velocity = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        state = KidSheetDrag.Start.UNDECIDED
        val view = sheet ?: return
        val release = KidSheetDrag.release(
            dragged, cancelled, view.translationY, view.height.toFloat(), speed, density, settling = settle != null,
        )
        when (release) {
            KidSheetDrag.Release.STAY -> Unit
            KidSheetDrag.Release.SPRING_BACK -> settleTo(0f, speed, null)
            KidSheetDrag.Release.CLOSE -> close(speed)
        }
    }

    /** "Bilde av …" over the card (code QA L5): the focus leaves after the touch began; the sheet lets go of it. */
    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        if (hasWindowFocus || pointerId == MotionEvent.INVALID_POINTER_ID) return
        if (state == KidSheetDrag.Start.SHEET) end(cancelled = true) else state = KidSheetDrag.Start.NOT_SHEET
    }

    private fun moveTo(offset: Float) {
        val view = sheet ?: return
        view.translationY = offset
        scrim?.let {
            it.alpha = KidSheetDrag.dim(1f, offset, view.height.toFloat())
            return
        }
        if (baseDim.isNaN()) {
            baseDim = dialog.window?.attributes?.dimAmount ?: return
            shownDim = baseDim
        }
        val dim = KidSheetDrag.dim(baseDim, offset, view.height.toFloat())
        // A window attribute each step is a relayout: only in steps, and always at the ends.
        if (KidSheetDrag.dimChanges(shownDim, dim, baseDim)) {
            shownDim = dim
            dialog.window?.setDimAmount(dim)
        }
    }

    private fun close(speed: Float) {
        closing = true
        val view = sheet
        if (view == null) {
            dialog.dismiss()
            return
        }
        settleTo(view.height.toFloat(), speed) {
            // The sheet is already off the screen: no exit animation after it.
            dialog.window?.setWindowAnimations(0)
            dialog.dismiss()
        }
    }

    private fun settleTo(target: Float, speed: Float, done: (() -> Unit)?) {
        val view = sheet ?: return
        val from = view.translationY
        settle?.cancel()
        settle = null
        if (!ValueAnimator.areAnimatorsEnabled() || from == target) {
            moveTo(target)
            done?.invoke()
            return
        }
        val plan = KidSheetDrag.settle(from, target, view.height.toFloat(), speed)
        settle = ValueAnimator.ofFloat(from, target).apply {
            duration = plan.ms
            interpolator = if (plan.decelerate) DecelerateInterpolator() else AccelerateDecelerateInterpolator()
            addUpdateListener { moveTo(it.animatedValue as Float) }
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(animation: Animator) {
                    cancelled = true
                }

                override fun onAnimationEnd(animation: Animator) {
                    if (settle === animation) settle = null
                    if (!cancelled) done?.invoke()
                }
            })
            start()
        }
    }

    override fun onDetachedFromWindow() {
        settle?.cancel()
        settle = null
        velocity?.recycle()
        velocity = null
        pointerId = MotionEvent.INVALID_POINTER_ID
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Whether a view under ([x], [y], in [group]'s coordinates) matches [test]. */
        fun anyAt(group: ViewGroup, x: Float, y: Float, test: (View) -> Boolean): Boolean {
            for (i in group.childCount - 1 downTo 0) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val cx = x + group.scrollX - child.left - child.translationX
                val cy = y + group.scrollY - child.top - child.translationY
                if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
                if (test(child)) return true
                if (child is ViewGroup && anyAt(child, cx, cy, test)) return true
            }
            return false
        }
    }
}
