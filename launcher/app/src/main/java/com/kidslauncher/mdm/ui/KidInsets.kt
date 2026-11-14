package com.kidslauncher.mdm.ui

import android.view.View
import android.view.WindowInsets

/**
 * The system-bar insets of our screens, applied once (fix round 2026-10-06). Replaces
 * `android:fitsSystemWindows`, which threw the root's own XML padding away (the phone book's 16 dp
 * sides, the call screen's paddings) and made it easy to stack a fixed top margin on the inset.
 * The root keeps its XML padding and gets the status bar (and a top cutout) on top, the
 * navigation bar at the bottom and side insets left/right. Rule: a screen's XML top padding is
 * at most 8 dp ([MAX_TOP_EXTRA_DP], checked by KidHeaderLayoutTest) - the inset does the rest.
 */
object KidInsets {
    const val MAX_TOP_EXTRA_DP = 8

    /** [ime]: the bottom takes the keyboard when it is taller than the navigation bar (a page with a text field: the
     * window is edge to edge, so `adjustResize` alone does nothing on API 35+, design 79 QA L3). [extraBottom]: more
     * room at the bottom for these insets, in px (the glance's handle above a 3-button bar, screen-size fix S5). */
    fun apply(root: View, ime: Boolean = false, extraBottom: ((WindowInsets) -> Int)? = null) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val keyboard = if (ime) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
            val extra = extraBottom?.invoke(insets) ?: 0
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, keyboard) + extra)
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

    /**
     * The navigation bar's buttons at the bottom (px): its tappable inset, which only 3-button navigation has - with
     * gesture navigation the bar is a gesture area and this is 0 (screen-size fix S5).
     */
    fun buttonBarPx(insets: WindowInsets): Int = insets.getInsets(WindowInsets.Type.tappableElement()).bottom
}
