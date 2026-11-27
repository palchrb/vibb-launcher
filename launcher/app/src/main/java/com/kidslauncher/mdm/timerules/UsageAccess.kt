package com.kidslauncher.mdm.timerules

import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log

/**
 * Design 139 §4.1 "The access": Android's "App usage data" (`GET_USAGE_STATS`) for Vibb One, read the way the usage
 * service decides it [d57] - the app-op allowed, or left at its default with `PACKAGE_USAGE_STATS` granted. A device
 * owner can't grant it to itself; the parent turns it on during a pause (design 114's setup step `usage_access`).
 * Without it `queryEvents` returns nothing and throws nothing, so the fold reads this before and after every query and
 * never takes an empty read for "nothing in front" (D6).
 */
object UsageAccess {
    private const val LOG_TAG = "UsageAccess"

    @Volatile
    private var watching: AppOpsManager.OnOpChangedListener? = null

    /** Granted, or `null` when it can't be read (counts as not granted, D6). `unsafeCheckOpNoThrow` is deprecated at
     * compileSdk 36 and fine on 34-37 (as `PlatformRefusals.exemptFromSuspension`). */
    @Suppress("DEPRECATION")
    fun granted(context: Context): Boolean? = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        ops?.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)?.let { mode ->
            usageAccessGranted(
                mode,
                context.checkSelfPermission(android.Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED,
            )
        }
    } catch (e: Exception) {
        null
    }

    /** A change of the op (the parent's switch, a revoke) re-checks the groups and asks for a sync (§4.1). Once per
     * process. */
    fun watch(context: Context) {
        if (watching != null) return
        val app = context.applicationContext
        val main = Handler(Looper.getMainLooper())
        val listener = AppOpsManager.OnOpChangedListener { op, pkg ->
            if (op != AppOpsManager.OPSTR_GET_USAGE_STATS || pkg != app.packageName) return@OnOpChangedListener
            main.post {
                try {
                    TimeRulesRuntime.recheck(app)
                    com.kidslauncher.mdm.push.SyncRunner.request(app, "usage_access")
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Re-check after the access changed failed: ${e.javaClass.simpleName}")
                }
            }
        }
        try {
            app.getSystemService(AppOpsManager::class.java)
                ?.startWatchingMode(AppOpsManager.OPSTR_GET_USAGE_STATS, app.packageName, listener)
            watching = listener
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Can't watch the usage access: ${e.javaClass.simpleName}")
        }
    }
}

/** The usage service's own rule [d57]: [mode] allowed, or default with the permission [permissionGranted]. */
fun usageAccessGranted(mode: Int, permissionGranted: Boolean): Boolean =
    mode == AppOpsManager.MODE_ALLOWED || (mode == AppOpsManager.MODE_DEFAULT && permissionGranted)
