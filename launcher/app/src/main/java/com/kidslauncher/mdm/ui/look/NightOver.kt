package com.kidslauncher.mdm.ui.look

import android.app.Activity
import android.content.Context
import android.content.res.Configuration
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatDelegate
import com.kidslauncher.mdm.R

/**
 * The context a night surface is built from when it may open over a page (design 78 §2: the contact card, the "Bilde
 * av …" sheet, the emergency menu and its 112 confirms stay night in both looks). On a page in Lys the activity's own
 * context resolves the day: its theme's `?android:attr/textColor*` and ripples are page tokens, and a page token in a
 * shared drawable is the day's. This one is the activity's (its window, its token, its theme copied) with the night
 * bit forced, and the page colours laid on again so every page token in the copied theme resolves at night - today's
 * values (KidPaletteTest). Over a screen that is not a page it is the activity itself: those are night already.
 */
fun nightOver(activity: Activity): Context {
    if (activity !is KidPageActivity) return activity
    val night = Configuration().apply { uiMode = Configuration.UI_MODE_NIGHT_YES }
    return ContextThemeWrapper(activity, R.style.PageColors).apply { applyOverrideConfiguration(night) }
}

/**
 * Shows [builder]'s dialog (built on [nightOver]) at night. An AppCompat dialog takes the AppCompat night mode of the
 * activity it finds behind its context - a page's, the kid's look - and applies it to its own context's resources
 * already in its constructor, which would turn [nightOver]'s back to the page's look. Its own mode set to night before
 * it is shown puts them back before anything is inflated. Over a screen that is not a page the two modes are the same
 * and nothing moves.
 */
fun showNight(builder: AlertDialog.Builder): AlertDialog = builder.create().also {
    it.delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
    it.show()
}
