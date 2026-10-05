package com.kidslauncher.mdm.actions

import android.app.AlertDialog
import android.app.Service
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Rect
import android.util.Log
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractAppInfo.Companion.INVALID_USER
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.apps.DetailedAppInfo
import com.kidslauncher.mdm.ui.list.apps.openSettings

class AppAction(val app: AppInfo) {

    fun invoke(context: Context, rect: Rect? = null): Boolean {
        val packageName = app.packageName
        if (app.user != INVALID_USER) {
            val launcherApps =
                context.getSystemService(Service.LAUNCHER_APPS_SERVICE) as LauncherApps
            app.getLauncherActivityInfo(context)?.let { app ->
                Log.i("Launcher", "Starting ${this.app}")
                try {
                    launcherApps.startMainActivity(app.componentName, app.user, rect, null)
                } catch (e: SecurityException) {
                    Log.i("Launcher", "Unable to start ${this.app}: ${e.message}")
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_cant_launch_app),
                        Toast.LENGTH_LONG
                    ).show()
                    return false
                }
                return true
            }
        }

        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addCategory(Intent.CATEGORY_LAUNCHER)
            try {
                context.startActivity(it)
            } catch (_: ActivityNotFoundException) {
                return false
            }
            return true
        }

        /* check if app is installed */
        if (isAvailable(context)) {
            AlertDialog.Builder(
                context,
                R.style.AlertDialogCustom
            )
                .setTitle(context.getString(R.string.alert_cant_open_title))
                .setMessage(context.getString(R.string.alert_cant_open_message))
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    app.openSettings(context)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .setIcon(android.R.drawable.ic_dialog_info)
                .show()
            return true
        }
        return false
    }

    private fun isAvailable(context: Context): Boolean {
        // check if app is installed
        return DetailedAppInfo.fromAppInfo(app, context) != null
    }
}