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

    fun apply(root: View) {
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + bars.bottom)
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }
}
