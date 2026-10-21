package com.kidslauncher.mdm.lock

import android.Manifest
import android.app.AppOpsManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.util.Log

/*
 * Design 57 §3.1, the prep release (§7.4.1): whether the parent has given Vibb One Usage access
 * (the GET_USAGE_STATS app-op). The gating release lets a VoIP call over the PIN lock only with it,
 * on Android 15+; this release only reads and reports it, so the parent can grant it on each phone
 * before that. The manifest declares PACKAGE_USAGE_STATS - without it Android resets a grant at
 * once (design 57 §1, measured). This file reads only the app-op mode and the permission; it never
 * touches UsageStatsManager or a usage event (`UsageAccessTest` scans for that).
 */

private const val LOG_TAG = "UsageAccess"

/** The status capability: this launcher reports `lock_state.usage_access_granted`. */
const val VOIP_USAGE_CAPABILITY = "voip_usage_v1"

/** The first API level whose usage-events query filters by package in the service
 * (`UsageEventsQuery.Builder.setPackageNames`, design 57 Q-C). */
const val VOIP_OVER_LOCK_MIN_SDK = 35

/** Mirrors `UsageStatsService.hasQueryPermission` (QA 57-16): the op ALLOWED, or DEFAULT with the
 * PACKAGE_USAGE_STATS permission granted (`adb shell pm grant ...` works too). */
fun usageAccessGranted(opMode: Int, permissionGranted: Boolean): Boolean =
    opMode == AppOpsManager.MODE_ALLOWED || (opMode == AppOpsManager.MODE_DEFAULT && permissionGranted)

/** `lock_state.voip_over_lock_unsupported`: `true` only below API 35, else left out (`null`). */
fun voipOverLockUnsupported(sdkInt: Int): Boolean? = if (sdkInt < VOIP_OVER_LOCK_MIN_SDK) true else null

object UsageAccess {
    /** The last read; `null` = not read yet or unreadable. Memory only. */
    @Volatile
    var granted: Boolean? = null
        private set

    private var watching = false

    /** Reads the op now (a binder call - never on the main thread in a hot path); `null` when it
     * throws. */
    fun read(context: Context): Boolean? = try {
        val app = context.applicationContext
        val ops = app.getSystemService(AppOpsManager::class.java)
        if (ops == null) {
            null
        } else {
            val mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), app.packageName)
            usageAccessGranted(
                mode,
                app.checkSelfPermission(Manifest.permission.PACKAGE_USAGE_STATS) == PackageManager.PERMISSION_GRANTED,
            )
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read usage access: ${e.javaClass.simpleName}")
        null
    }

    /** Reads, keeps it in [granted] and returns it. */
    fun refresh(context: Context): Boolean? = read(context).also { granted = it }

    /** `lock_state`'s two fields for the status report (read fresh). */
    fun report(context: Context): Pair<Boolean?, Boolean?> =
        refresh(context) to voipOverLockUnsupported(Build.VERSION.SDK_INT)

    /**
     * From `Application.initRest`: the first read in the background, and Android's mode watcher,
     * registered once - a change (a grant in Settings or with adb, a revoke) asks for a sync, so the
     * parent's page follows within seconds. Everything caught.
     */
    fun init(context: Context) {
        val app = context.applicationContext
        synchronized(this) {
            if (watching) return
            watching = true
        }
        try {
            Thread({ refresh(app) }, "usage-access").start()
            app.getSystemService(AppOpsManager::class.java)?.startWatchingMode(
                AppOpsManager.OPSTR_GET_USAGE_STATS,
                app.packageName,
            ) { _, packageName ->
                // A binder thread of ours: read here, never on the main thread.
                if (packageName != app.packageName) return@startWatchingMode
                try {
                    val before = granted
                    if (refresh(app) != before) {
                        Log.i(LOG_TAG, "Usage access changed: $granted")
                        com.kidslauncher.mdm.push.SyncRunner.request(app, "usage_access")
                    }
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Usage access change not handled: ${e.javaClass.simpleName}")
                }
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch usage access: ${e.javaClass.simpleName}")
        }
    }
}
