package com.kidslauncher.mdm.ui

import android.app.Dialog
import android.view.View
import androidx.activity.ComponentActivity
import java.util.WeakHashMap

/**
 * The round top-left back button of our full pages (phone book, kid Settings) - all of its
 * behaviour in one place, so dropping it in favour of system Back alone is the one-line change
 * [SHOW_ON_PAGES]. System Back always works on these pages (the button only forwards to it); the
 * lock screens and a call screen with a call keep Back off by design.
 */
object KidBackButton {
    /** User decision pending (fix round 2026-10-06): `false` hides the buttons everywhere. */
    const val SHOW_ON_PAGES = true

    private val buttons = WeakHashMap<ComponentActivity, View>()

    fun bind(activity: ComponentActivity, button: View) {
        buttons[activity] = button
        button.visibility = if (SHOW_ON_PAGES) View.VISIBLE else View.GONE
        button.setOnClickListener { activity.onBackPressedDispatcher.onBackPressed() }
    }

    /** A sheet over the page: the page's back arrow goes while it is up (it looked like the
     * sheet's own, misplaced - fix round 2026-10-06); Back and Close close the sheet. */
    fun hideWhile(activity: android.app.Activity, dialog: Dialog) {
        val button = (activity as? ComponentActivity)?.let { buttons[it] } ?: return
        if (button.visibility != View.VISIBLE) return
        button.visibility = View.INVISIBLE
        dialog.window?.decorView?.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                if (SHOW_ON_PAGES) button.visibility = View.VISIBLE
            }
        })
    }
}
