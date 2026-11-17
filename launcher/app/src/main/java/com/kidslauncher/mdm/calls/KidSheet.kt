package com.kidslauncher.mdm.calls

import android.app.Dialog
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout

/**
 * The bottom sheets' window ([ContactSheet], [EmergencySheet], [KidPhotoSheet]; style `KidSheetDialog`, not
 * floating): full width at the bottom, edge to edge. A floating dialog window stops above the navigation bar, so the
 * dim showed as a black strip under the sheet (2026-11-09, gesture and 3-button navigation alike). Here the window
 * runs to the screen's bottom edge and the sheet's own surface (`bg_kid_sheet`) fills it; [content]'s XML bottom
 * padding gets the bar's inset on top, so the last row never sits under the gesture handle or the buttons.
 *
 * Swipe down closes the sheet (2026-11-17): [content] goes into a [KidSheetDragFrame], which lets it follow a finger
 * dragging down and closes it through [Dialog.dismiss], as Lukk does. Call it after `setContentView(content)` and
 * before `show()`.
 */
internal fun Dialog.setUpKidSheet(content: View) {
    val bottom = content.paddingBottom
    content.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + bars.bottom)
        insets
    }
    val frame = KidSheetDragFrame(context, this)
    (content.parent as? ViewGroup)?.removeView(content)
    frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    setContentView(frame)
    window?.apply {
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}
