package com.kidslauncher.mdm.calls

import android.app.Dialog
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.core.view.WindowCompat
import com.kidslauncher.mdm.R

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
 *
 * [ownScrim] (the brightness sheet, a light sheet in Lys; code QA M3, 2026-11-19): the window covers the whole screen
 * and draws its own dim - a scrim under the sheet, as the time-rule screen's sheet does - instead of the window's
 * `DIM_BEHIND`. Android clears the light-bar flag of a dimming window, so 3-button navigation's buttons stayed white on
 * Lys's white sheet; without the window dim the caller's light-bar choice holds. A tap on the scrim slides the sheet
 * down and closes it; the scrim fades with the sheet's drag; Back fades the window out.
 */
internal fun Dialog.setUpKidSheet(content: View, ownScrim: Boolean = false) {
    val bottom = content.paddingBottom
    content.setOnApplyWindowInsetsListener { view, insets ->
        val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottom + bars.bottom)
        insets
    }
    val frame = KidSheetDragFrame(context, this)
    (content.parent as? ViewGroup)?.removeView(content)
    frame.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    if (ownScrim) {
        val scrim = View(context).apply {
            // The window dim's amount (`KidSheetDialog`'s 0.5) as a colour.
            setBackgroundColor(Color.argb(128, 0, 0, 0))
            alpha = 0f
            // Back is the accessible way out (TalkBack), as on the other sheets.
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setOnClickListener { frame.slideClose() }
        }
        frame.scrim = scrim
        val root = FrameLayout(context)
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        setContentView(root)
        window?.apply {
            WindowCompat.setDecorFitsSystemWindows(this, false)
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setWindowAnimations(R.style.KidSheetScrimAnimation)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        frame.slideIn()
        return
    }
    setContentView(frame)
    window?.apply {
        // Android 15+ lays an app targeting 35+ out edge to edge; Android 14 fitted the window above the navigation
        // bar and painted the bar black under the sheet (code QA M2, 2026-11-19).
        WindowCompat.setDecorFitsSystemWindows(this, false)
        setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        setGravity(Gravity.BOTTOM)
    }
}
