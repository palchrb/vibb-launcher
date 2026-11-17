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
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Swipe down closes the kid's sheets (2026-11-17): the frame [setUpKidSheet] puts around a sheet's content. The
 * content follows a finger dragging down, the dim behind fades with it; on release it closes ([Dialog.dismiss], so
 * every dismiss listener runs as for Lukk) past a third of its height or on a fast fling down, else springs back.
 * The rules are [KidSheetDrag]'s.
 *
 * A move inside the touch slop stays a tap for the sheet's buttons; a downward drag that starts on a button takes the
 * touch (the button gets a cancel, so no click). Upwards and sideways nothing moves. On content that can still scroll
 * up (the emergency sheet's rows) the drag is the content's; at its top the sheet's. Lukk, Back and tap-outside are
 * untouched (Back is the dialog's own; a tap above the sheet lies outside this frame). With animations off (the
 * animator duration scale 0, "Remove animations") it closes or settles at once. No accessibility node of its own,
 * so TalkBack's order is the content's.
 */
@SuppressLint("ViewConstructor")
internal class KidSheetDragFrame(context: Context, private val dialog: Dialog) : FrameLayout(context) {

    private val slop = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val maxFling = ViewConfiguration.get(context).scaledMaximumFlingVelocity.toFloat()
    private val flingVelocity = KidSheetDrag.FLING_DP_PER_S * resources.displayMetrics.density
    private val baseDim = dialog.window?.attributes?.dimAmount ?: 0f
    private var shownDim = baseDim

    private var velocity: VelocityTracker? = null
    private var pointerId = MotionEvent.INVALID_POINTER_ID
    private var downX = 0f
    private var downY = 0f
    private var dragFromY = 0f
    private var dragBase = 0f
    private var state = KidSheetDrag.Start.UNDECIDED
    private var settle: ValueAnimator? = null
    private var closing = false

    private val sheet: View? get() = getChildAt(0)

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        track(ev)
        return state == KidSheetDrag.Start.SHEET
    }

    @SuppressLint("ClickableViewAccessibility") // no click here: Lukk is the accessible way to close
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (closing) return true
        track(ev)
        return true
    }

    /** One step of the gesture, from either path (the DOWN may come through both: [begin] is idempotent). */
    private fun track(ev: MotionEvent) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> begin(ev)
            MotionEvent.ACTION_MOVE -> {
                val index = ev.findPointerIndex(pointerId)
                if (index < 0) return
                velocity?.addMovement(ev)
                val x = ev.getX(index)
                val y = ev.getY(index)
                if (state == KidSheetDrag.Start.UNDECIDED) {
                    // A sheet that lost its window focus has another over it ("Bilde av …" from a long-press on the
                    // card's photo): this touch is not the sheet's.
                    state = if (!hasWindowFocus()) {
                        KidSheetDrag.Start.NOT_SHEET
                    } else {
                        KidSheetDrag.start(x - downX, y - downY, slop, scrolledDownAt(this, downX, downY))
                    }
                    if (state == KidSheetDrag.Start.SHEET) {
                        dragFromY = y
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                if (state == KidSheetDrag.Start.SHEET) moveTo(KidSheetDrag.offset(dragBase, y - dragFromY))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (ev.getPointerId(ev.actionIndex) == pointerId) end(cancelled = false)
            }
            MotionEvent.ACTION_UP -> end(cancelled = false)
            MotionEvent.ACTION_CANCEL -> end(cancelled = true)
        }
    }

    private fun begin(ev: MotionEvent) {
        velocity?.recycle()
        velocity = VelocityTracker.obtain().apply { addMovement(ev) }
        pointerId = ev.getPointerId(0)
        downX = ev.x
        downY = ev.y
        state = KidSheetDrag.Start.UNDECIDED
        // A touch while it springs back catches the sheet where it is.
        settle?.cancel()
        settle = null
        dragBase = sheet?.translationY ?: 0f
    }

    private fun end(cancelled: Boolean) {
        // Already ended (the active finger lifted before the others).
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
        val offset = view.translationY
        // A touch that never dragged leaves the sheet where it rests (it is at 0 unless a settle was caught).
        if (!dragged && offset == 0f) return
        if (!cancelled && KidSheetDrag.closes(offset, view.height.toFloat(), speed, flingVelocity)) close() else settleTo(0f, null)
    }

    private fun moveTo(offset: Float) {
        val view = sheet ?: return
        view.translationY = offset
        val dim = KidSheetDrag.dim(baseDim, offset, view.height.toFloat())
        // A window attribute each step is a relayout: only on a visible change, and always at the ends.
        if (abs(dim - shownDim) >= 0.02f || (dim == 0f || dim == baseDim) && dim != shownDim) {
            shownDim = dim
            dialog.window?.setDimAmount(dim)
        }
    }

    private fun close() {
        closing = true
        val view = sheet
        if (view == null) {
            dialog.dismiss()
            return
        }
        settleTo(view.height.toFloat()) {
            // The sheet is already off the screen: no exit animation after it.
            dialog.window?.setWindowAnimations(0)
            dialog.dismiss()
        }
    }

    private fun settleTo(target: Float, done: (() -> Unit)?) {
        val view = sheet ?: return
        val from = view.translationY
        settle?.cancel()
        if (!ValueAnimator.areAnimatorsEnabled() || from == target) {
            moveTo(target)
            done?.invoke()
            return
        }
        settle = ValueAnimator.ofFloat(from, target).apply {
            duration = KidSheetDrag.settleMs(from, target, view.height.toFloat())
            interpolator = DecelerateInterpolator()
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
        super.onDetachedFromWindow()
    }

    private companion object {
        /** Whether a view under ([x], [y], in [group]'s coordinates) can still scroll up: then the drag is its own. */
        fun scrolledDownAt(group: ViewGroup, x: Float, y: Float): Boolean {
            for (i in group.childCount - 1 downTo 0) {
                val child = group.getChildAt(i)
                if (child.visibility != View.VISIBLE) continue
                val cx = x + group.scrollX - child.left - child.translationX
                val cy = y + group.scrollY - child.top - child.translationY
                if (cx < 0 || cy < 0 || cx >= child.width || cy >= child.height) continue
                if (child.canScrollVertically(-1)) return true
                if (child is ViewGroup && scrolledDownAt(child, cx, cy)) return true
            }
            return false
        }
    }
}
