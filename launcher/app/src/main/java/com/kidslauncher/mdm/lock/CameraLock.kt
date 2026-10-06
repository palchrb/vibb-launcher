package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.provider.MediaStore
import android.telecom.TelecomManager
import android.util.Log
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.controllablePackages
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.inputMethodPackages
import com.kidslauncher.mdm.server.systemDialerPackage
import java.util.concurrent.Executors

private const val LOG_TAG = "CameraLock"

/**
 * The glue of the camera lock (CameraLockPlan.kt): while handy's PIN lock is LOCKED the allowed
 * camera apps are suspended, at the unlock unsuspended. One serial background thread (lock and
 * unlock stay in order; never the main thread, never `AppEnforcer`'s lock - a screen-off must not
 * wait for a running apply). The record is written before suspending and [held] is set before
 * too, so `apply()` - which reads [held] after a package's current state - never unsuspends one
 * of ours while LOCKED.
 */
object CameraLock {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-lock").apply { isDaemon = true } }

    /** The packages the camera lock suspended (`apply()` keeps them suspended). */
    @Volatile
    var held: Set<String> = emptySet()
        private set

    /** The targets resolved by the last `apply()` (so a screen-off needs no PackageManager work). */
    @Volatile
    private var targets: Set<String>? = null

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(CAMERA_LOCK_PREFS, Context.MODE_PRIVATE)

    private fun record(context: Context): Set<String> = try {
        decodeCameraLockRecord(prefs(context).all)
    } catch (e: Exception) {
        emptySet()
    }

    /** Application.initRest, before PinLockRuntime.init: what an earlier process left suspended. */
    fun init(context: Context) {
        held = record(context)
    }

    /** LOCKED began or ended, or the process started (PinLockRuntime). Any thread. */
    fun onLockChanged(context: Context, locked: Boolean) {
        val app = context.applicationContext
        executor.execute {
            try {
                sync(app, locked)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Camera lock ${if (locked) "engage" else "release"} failed", e)
            }
        }
    }

    /** From `AppEnforcer.apply()` (background): the targets for this phone now; while LOCKED a
     * newly allowed camera is suspended at once. */
    fun refreshTargets(context: Context, controllable: Set<String>) {
        targets = resolveTargets(context.applicationContext, controllable)
        if (PinLockRuntime.chromeLocked) onLockChanged(context, true)
    }

    private fun sync(app: Context, locked: Boolean) {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return
        val recorded = record(app)
        if (!dpm.isDeviceOwnerApp(app.packageName)) {
            // No longer device owner: nothing to suspend, and the record can't be acted on.
            if (recorded.isNotEmpty()) prefs(app).edit().clear().commit()
            held = emptySet()
            return
        }
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        val pm = app.packageManager
        val wanted = if (locked) targets ?: resolveTargets(app, controllablePackages(pm).toSet()).also { targets = it } else emptySet()
        val suspendedNow = (wanted + recorded).filterTo(mutableSetOf()) { pkg ->
            try {
                pm.isPackageSuspended(pkg)
            } catch (e: Exception) {
                false
            }
        }
        val enforcement = AppEnforcer.lastPlanSuspend
        when (val step = cameraLockStep(locked, recorded, wanted, suspendedNow, enforcement)) {
            is CameraLockStep.Suspend -> {
                // Record before act - in memory for apply(), on disk for the next process.
                held = step.record
                prefs(app).edit().clear().putInt(CameraLockKeys.VERSION, CAMERA_LOCK_V1)
                    .putStringSet(CameraLockKeys.SUSPENDED, step.record).commit()
                val refused = dpm.setPackagesSuspended(admin, step.packages.toTypedArray(), true).orEmpty().toSet()
                Log.i(LOG_TAG, "Locked: camera apps suspended ${step.packages - refused}, refused $refused")
            }
            is CameraLockStep.Release -> {
                val refused = if (step.packages.isEmpty()) {
                    emptySet()
                } else {
                    try {
                        dpm.setPackagesSuspended(admin, step.packages.toTypedArray(), false).orEmpty().toSet()
                    } catch (e: Exception) {
                        step.packages
                    }
                }
                prefs(app).edit().clear().commit()
                held = emptySet()
                Log.i(LOG_TAG, "Unlocked: camera apps unsuspended ${step.packages - refused}, refused $refused")
                // No plan in this process yet (process start): the next apply decides the rest.
                if (enforcement == null) requestApply(app)
            }
            CameraLockStep.Nothing -> {
                if (!locked && held.isNotEmpty()) held = emptySet()
            }
        }
    }

    /** The camera handlers on this phone (the gesture's still-image/video actions; for
     * `ACTION_IMAGE_CAPTURE` only system apps - a chat app answering it must not go dark while
     * locked) as [cameraLockTargets] keeps them. */
    private fun resolveTargets(app: Context, controllable: Set<String>): Set<String> {
        val pm = app.packageManager
        fun handlers(action: String, systemOnly: Boolean): List<String> = try {
            pm.queryIntentActivities(Intent(action), PackageManager.ResolveInfoFlags.of(0))
                .mapNotNull { it.activityInfo }
                .filter { !systemOnly || (it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM != 0 }
                .map { it.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        val cameras = (
            handlers(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, false) +
                handlers(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE, false) +
                handlers(MediaStore.INTENT_ACTION_VIDEO_CAMERA, false) +
                handlers(MediaStore.ACTION_IMAGE_CAPTURE, true)
            ).toSet()
        val defaultDialer = try {
            app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (e: Exception) {
            null
        }
        val protected = setOfNotNull(systemDialerPackage(app), defaultDialer) + inputMethodPackages(app) +
            runCatching { AppEnforcer.resolvePinLockHelpers(app) }.getOrDefault(emptySet()) +
            runCatching { AppEnforcer.resolveLockTaskHelpers(app) }.getOrDefault(emptySet())
        return cameraLockTargets(cameras, controllable, app.packageName, protected)
    }

    private fun requestApply(app: Context) {
        try {
            AppEnforcer.apply(app, currentPolicyDecision().policy)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Apply after the camera release failed", e)
        }
    }
}
