package com.kidslauncher.mdm.lock

import android.util.DisplayMetrics
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import kotlin.math.roundToInt

/**
 * The PIN lock handles `fontScale` itself (`configChanges`, qa-10-code #6: a recreation must not
 * cost a re-front or count for the crash guard), so its views keep the text sizes they were
 * inflated with until something recreates it (emulator round 2026-10-14 N8: "the lock doesn't
 * follow a font-scale change until it is recreated"). On a change [PinLockActivity] re-sizes every
 * text in place: each one's size back to the sp it was set in (at the old metrics, Android 14's
 * non-linear scale included - [TypedValue.deriveDimension]) and set again in sp at the new ones;
 * then the keys are sized and the glance page laid out again. No recreation, no lifecycle change.
 */
object FontScaleRefresh {
    /** A change the lock must follow: the font scale moved (a density change is not handled here). */
    fun changed(oldFontScale: Float, newFontScale: Float): Boolean = oldFontScale != newFontScale

    /** An sp size read back from pixels, to a tenth (13.0000001 must not drift over many changes). */
    fun roundSp(sp: Float): Float = (sp * 10f).roundToInt() / 10f

    /** A copy of [metrics] to read the old sizes with after the activity's resources moved on. */
    fun snapshot(metrics: DisplayMetrics): DisplayMetrics = DisplayMetrics().apply { setTo(metrics) }

    /** Every [TextView] under [root] but [skip] (the keypad's digits, sized by `sizeKeys`) back to its
     * sp at [old], set again at the current metrics. */
    fun rescale(root: View, old: DisplayMetrics, skip: Collection<View>) =
        walk(root, { v -> if (v is ViewGroup) List(v.childCount) { v.getChildAt(it) } else emptyList() }) { view ->
            if (view is TextView && view !in skip) {
                val sp = roundSp(TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, view.textSize, old))
                if (sp > 0f) view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
            }
        }

    /** Visits [root] and everything below it, depth first (pure, so the recursion is tested on a
     * fake tree - qa-34-emu-launcher-code L2). */
    fun <T> walk(root: T, children: (T) -> List<T>, visit: (T) -> Unit) {
        visit(root)
        for (child in children(root)) walk(child, children, visit)
    }
}
