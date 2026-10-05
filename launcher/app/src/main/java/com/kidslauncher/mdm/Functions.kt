package com.kidslauncher.mdm

import android.app.Activity
import android.app.Service
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.net.toUri
import com.kidslauncher.mdm.apps.AbstractAppInfo.Companion.INVALID_USER
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.apps.DetailedAppInfo
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.ui.list.AbstractListActivity
import com.kidslauncher.mdm.ui.list.AppListActivity
import com.kidslauncher.mdm.ui.settings.SettingsActivity


const val LOG_TAG = "Launcher"

const val REQUEST_SET_DEFAULT_HOME = 42

fun isDefaultHomeScreen(context: Context): Boolean {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        return roleManager.isRoleHeld(RoleManager.ROLE_HOME)
    } else {
        val testIntent = Intent(Intent.ACTION_MAIN)
        testIntent.addCategory(Intent.CATEGORY_HOME)
        val defaultHome = testIntent.resolveActivity(context.packageManager)?.packageName
        return defaultHome == context.packageName
    }
}

fun setDefaultHomeScreen(context: Context, checkDefault: Boolean = false) {
    val isDefault = isDefaultHomeScreen(context)
    if (checkDefault && isDefault) {
        // Launcher is already the default home app
        return
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        && context is Activity
        && checkDefault // using role manager only works when µLauncher is not already the default.
    ) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        try {
            context.startActivityForResult(
                roleManager.createRequestRoleIntent(RoleManager.ROLE_HOME),
                REQUEST_SET_DEFAULT_HOME
            )
            return
        } catch (e: ActivityNotFoundException) {
            // There is always some broken ROM...
            Log.w(LOG_TAG, "Unable to set home screen using RoleManager. Using fallback.", e)
        }
    }

    val intent = Intent(Settings.ACTION_HOME_SETTINGS)
    try {
        context.startActivity(intent)
        return
    } catch (e: ActivityNotFoundException) {
        // There is always some broken ROM...
        Log.w(LOG_TAG, "Unable to set home screen using ACTION_HOME_SETTINGS.", e)
        Toast.makeText(context, R.string.alert_cant_choose_home_screen, Toast.LENGTH_LONG).show()
    }
}

fun openSettings(context: Context) {
    context.startActivity(Intent(context, SettingsActivity::class.java))
}

fun openAppsList(
    context: Context,
    hidden: Boolean = false,
    excludePinned: Boolean = false
) {
    val intent = Intent(context, AppListActivity::class.java)
    intent.putExtra(
        AbstractListActivity.KEY_HIDDEN_VISIBILITY,
        if (hidden) {
            AppFilter.Companion.AppSetVisibility.EXCLUSIVE
        } else {
            AppFilter.Companion.AppSetVisibility.HIDDEN
        }
    )
    intent.putExtra(
        AbstractListActivity.KEY_PINNED_VISIBILITY,
        if (excludePinned) {
            AppFilter.Companion.AppSetVisibility.HIDDEN
        } else {
            AppFilter.Companion.AppSetVisibility.VISIBLE
        }
    )

    context.startActivity(intent)
}

fun getUserFromId(userId: Int?, context: Context): UserHandle {
    /* TODO: this is an ugly hack.
        Use userManager#getUserForSerialNumber instead (breaking change to SharedPreferences!)
     */
    val userManager = context.getSystemService(Service.USER_SERVICE) as UserManager
    val profiles = userManager.userProfiles
    return profiles.firstOrNull { it.hashCode() == userId } ?: profiles[0]
}

fun openInBrowser(url: String, context: Context) {
    val intent = Intent(Intent.ACTION_VIEW, url.toUri())
    intent.putExtras(Bundle().apply { putBoolean("new_window", true) })
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, R.string.toast_activity_not_found_browser, Toast.LENGTH_LONG).show()
    }
}

/**
 * Load all apps.
 */
fun getApps(
    packageManager: PackageManager,
    context: Context
): MutableList<AbstractDetailedAppInfo> {
    var start = System.currentTimeMillis()
    val loadList = mutableListOf<AbstractDetailedAppInfo>()

    val launcherApps = context.getSystemService(Service.LAUNCHER_APPS_SERVICE) as LauncherApps
    val userManager = context.getSystemService(Service.USER_SERVICE) as UserManager

    // TODO: shortcuts - launcherApps.getShortcuts()
    val users = userManager.userProfiles
    for (user in users) {
        try {
            launcherApps.getActivityList(null, user).forEach {
                loadList.add(DetailedAppInfo(it))
            }
        } catch (e: Exception) {
            // getActivityList seems to be broken on some Android distributions.
            // DeadSystemException, BadParcelableException
            Log.w(LOG_TAG, "exception thrown while loading apps", e)
        }
    }

    // fallback option
    if (loadList.isEmpty()) {
        Log.w(LOG_TAG, "using fallback option to load packages")
        val i = Intent(Intent.ACTION_MAIN, null)
        i.addCategory(Intent.CATEGORY_LAUNCHER)

        val allApps = try {
            packageManager.queryIntentActivities(i, 0)
        } catch (e: Exception) {
            // DeadSystemException
            Log.w(LOG_TAG, "exception thrown while loading apps (fallback method)", e)
            listOf()
        }
        for (ri in allApps) {
            val app = AppInfo(ri.activityInfo.packageName, null, INVALID_USER)
            val detailedAppInfo = DetailedAppInfo(
                app,
                ri.loadLabel(packageManager),
                ri.activityInfo.loadIcon(packageManager)
            )
            loadList.add(detailedAppInfo)
        }
    }
    loadList.sortBy { it.getCustomLabel(context) }

    val end = System.currentTimeMillis()
    Log.i(LOG_TAG, "${loadList.size} apps loaded (${end - start}ms)")

    return loadList
}

fun getDeviceInfo(context: Context): String {
    return """
        ${context.getString(R.string.app_name)} version: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})
        Commit ${BuildConfig.GIT_COMMIT.take(8)}
        Android version: ${Build.VERSION.RELEASE} (sdk ${Build.VERSION.SDK_INT})
        Model: ${Build.MODEL}
        Device: ${Build.DEVICE}
        Brand: ${Build.BRAND}
        Manufacturer: ${Build.MANUFACTURER}
    """.trimIndent()
}

fun copyToClipboard(context: Context, text: String) {
    val clipboardManager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    val clipData = ClipData.newPlainText("Debug Info", text)
    clipboardManager.setPrimaryClip(clipData)
}

