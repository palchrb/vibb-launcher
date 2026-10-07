package com.kidslauncher.mdm.ui.list.apps

import android.app.Activity
import android.app.Service
import android.content.Context
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.snackbar.Snackbar
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.preferences.LauncherPreferences

private const val LOG_TAG = "AppContextMenu"
private const val MINIMALIST_APPS_LIMIT = 10

/**
 * Used by [com.kidslauncher.mdm.actions.AppAction] to let the user open an app's system
 * settings page after a launch attempt fails.
 */
fun AppInfo.openSettings(
    context: Context,
    sourceBounds: Rect? = null,
    opts: Bundle? = null
) {
    val launcherApps = context.getSystemService(Service.LAUNCHER_APPS_SERVICE) as LauncherApps
    this.getLauncherActivityInfo(context)?.let { app ->
        launcherApps.startAppDetailsActivity(app.componentName, app.user, sourceBounds, opts)
    }
}

fun AbstractAppInfo.toggleMinimalistApp(context: Context) {
    val apps: MutableSet<AbstractAppInfo> =
        LauncherPreferences.minimalist().apps() ?: mutableSetOf()

    if (apps.contains(this)) {
        apps.remove(this)
        Log.i(LOG_TAG, "Removing $this from minimalist app list.")
    } else {
        if (apps.size >= MINIMALIST_APPS_LIMIT) {
            Toast.makeText(context, R.string.toast_minimalist_limit_reached, Toast.LENGTH_LONG)
                .show()
            return
        }
        Log.i(LOG_TAG, "Adding $this to minimalist app list.")
        apps.add(this)
    }

    LauncherPreferences.minimalist().apps(apps)
}

/**
 * @param view: used to show a snackbar letting the user undo the action
 */
fun AbstractAppInfo.toggleHidden(view: View) {
    val hidden: MutableSet<AbstractAppInfo> =
        LauncherPreferences.apps().hidden() ?: mutableSetOf()
    if (hidden.contains(this)) {
        hidden.remove(this)
    } else {
        hidden.add(this)

        Snackbar.make(view, R.string.snackbar_app_hidden, Snackbar.LENGTH_LONG)
            .setAction(R.string.undo) {
                LauncherPreferences.apps().hidden(
                    LauncherPreferences.apps().hidden().minus(this)
                )
            }.show()
    }
    LauncherPreferences.apps().hidden(hidden)
}

/** Returns the dialog (Home closes it when the lock engages - design 16c). */
fun AbstractDetailedAppInfo.showRenameDialog(context: Context): AlertDialog =
    AlertDialog.Builder(context, R.style.AlertDialogCustom).apply {
        setTitle(context.getString(R.string.dialog_rename_title, getLabel()))
        setView(R.layout.dialog_rename_app)
        setNegativeButton(android.R.string.cancel) { d, _ -> d.cancel() }
        setPositiveButton(android.R.string.ok) { d, _ ->
            setCustomLabel(
                (d as? AlertDialog)
                    ?.findViewById<EditText>(R.id.dialog_rename_app_edit_text)
                    ?.text.toString()
            )
        }
    }.create().also { it.show() }.apply {
        val input = findViewById<EditText>(R.id.dialog_rename_app_edit_text)
        input?.setText(getCustomLabel(context))
        input?.hint = getLabel()
    }

/**
 * The long-press context menu shown for an app row, shared by the app drawer and the
 * home screen's minimal list. [onOverlay] gets a way to close the menu and, once opened, the
 * rename dialog: Home closes both when the PIN lock engages (design 16c, qa-16c-code #2).
 */
fun showAppContextMenu(
    activity: Activity,
    anchor: View,
    appInfo: AbstractDetailedAppInfo,
    onOverlay: (close: () -> Unit) -> Unit = {},
) {
    val popup = PopupMenu(activity, anchor)
    popup.inflate(R.menu.menu_app)
    // Home's grid shows every app (design 05), so "add to/remove from home screen" means nothing.
    popup.menu.findItem(R.id.app_menu_minimalist).isVisible = false

    // The kid renames only apps the parent hasn't named (design 14); their own name stays stored.
    val packageName = (appInfo.getRawInfo() as? com.kidslauncher.mdm.apps.AppInfo)?.packageName
    popup.menu.findItem(R.id.app_menu_rename).isVisible =
        com.kidslauncher.mdm.appdisplay.kidMayRename(com.kidslauncher.mdm.appdisplay.AppDisplay.entry(packageName))

    if (LauncherPreferences.apps().hidden()?.contains(appInfo.getRawInfo()) == true) {
        popup.menu.findItem(R.id.app_menu_hidden).setTitle(R.string.list_app_hidden_remove)
    }

    if (LauncherPreferences.minimalist().apps()?.contains(appInfo.getRawInfo()) == true) {
        popup.menu.findItem(R.id.app_menu_minimalist).setTitle(R.string.list_app_minimalist_remove)
    }

    popup.setOnMenuItemClickListener {
        when (it.itemId) {
            R.id.app_menu_hidden -> {
                appInfo.getRawInfo().toggleHidden(anchor); true
            }

            R.id.app_menu_minimalist -> {
                appInfo.getRawInfo().toggleMinimalistApp(activity); true
            }

            R.id.app_menu_rename -> {
                val dialog = appInfo.showRenameDialog(activity)
                onOverlay { dialog.dismiss() }
                true
            }

            else -> false
        }
    }

    popup.show()
    onOverlay { popup.dismiss() }
}

