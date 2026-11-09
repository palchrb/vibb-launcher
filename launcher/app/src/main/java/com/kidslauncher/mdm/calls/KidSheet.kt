package com.kidslauncher.mdm.calls

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets

/**
 * The bottom sheets' window ([ContactSheet], [EmergencySheet]; style `KidSheetDialog`, not floating): full width at
 * the bottom, edge to edge. A floating dialog window stops above the navigation bar, so the dim showed as a black
 * strip under the sheet (2026-11-09, gesture and 3-button navigation alike). Here the window runs to the screen's
 * bottom edge and the sheet's own surface (`bg_kid_sheet`) fills it; [content]'s XML bottom padding gets the
 * bar's inset on top, so the last row never sits under the gesture handle or the buttons. Call it after
 * `setContentView` and before `show()`.
 */
internal fun Dialog.setUpKidSheet(content: View) {
    val bottom = content.paddingBottom
    content.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + bars.bottom)
        insets
    }
    window?.apply {
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}
