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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val LOG_TAG = "CameraLock"
/** How long after a release its unsuspend callbacks count as the camera lock's own. */
private const val RELEASE_CALLBACK_MS = 10_000L

/**
 * The glue of the camera lock (CameraLockPlan.kt): while handy's PIN lock is LOCKED the allowed
 * camera apps are suspended, at the unlock unsuspended. One serial background thread (lock and
 * unlock stay in order; never the main thread, never `AppEnforcer`'s lock - a screen-off must not
 * wait for a running apply; an `apply()` it asks for runs on its own coroutine). Each pass reads
 * whether the lock is LOCKED when it runs, not when it was queued (qa-11b-code #2b). The record
 * is written before suspending and [held] is set before too, so `apply()` - which reads [held]
 * after a package's current state - never unsuspends one of ours while LOCKED; at a release
 * [held] is emptied before unsuspending, so a concurrent `apply()` never suspends one again
 * without a record (#2a).
 */
object CameraLock {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "camera-lock").apply { isDaemon = true } }
    private val applyScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The packages the camera lock suspended (`apply()` keeps them suspended). */
    @Volatile
    var held: Set<String> = emptySet()
        private set

    /** Packages released moments ago: their unsuspend callbacks don't reload the app list. */
    @Volatile
    private var released: Pair<Set<String>, Long> = emptySet<String>() to 0L

    /** Packages whose suspend/unsuspend is only the camera lock's (Application's LauncherApps
     * callback skips the app reload for them - Home never dropped them, see [suspendedForLists]). */
    fun ownChange(packages: Collection<String>?): Boolean {
        val (recent, at) = released
        val recentStill = if (android.os.SystemClock.elapsedRealtime() - at < RELEASE_CALLBACK_MS) recent else emptySet()
        return cameraLockOnlyChange(packages, held + recentStill)
    }

    /** Whether the app grid/time-rule screen count [packageName] as suspended. */
    fun suspendedForLists(packageName: String, platformSuspended: Boolean): Boolean =
        com.kidslauncher.mdm.lock.suspendedForLists(packageName, platformSuspended, held, AppEnforcer.lastPlanSuspend)

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

    /** LOCKED began or ended, or the process started (PinLockRuntime). Any thread. The pass reads
     * [PinLockRuntime.mode] when it runs, so a queued pass can't re-engage after an unlock. */
    fun onLockChanged(context: Context) {
        val app = context.applicationContext
        executor.execute {
            val locked = PinLockRuntime.chromeLocked
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
        if (PinLockRuntime.chromeLocked) onLockChanged(context)
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
                // Record before act - on disk for the next process, in memory for apply(). No
                // record, no suspension (qa-11b-code #2c): a suspension nobody recorded would only
                // be undone by the next apply().
                val before = held
                held = step.record
                val committed = prefs(app).edit().clear().putInt(CameraLockKeys.VERSION, CAMERA_LOCK_V1)
                    .putStringSet(CameraLockKeys.SUSPENDED, step.record).commit()
                if (!committed) {
                    held = before
                    Log.w(LOG_TAG, "Camera lock record not written - not suspending ${step.packages}")
                    return
                }
                val refused = dpm.setPackagesSuspended(admin, step.packages.toTypedArray(), true).orEmpty().toSet()
                Log.i(LOG_TAG, "Locked: camera apps suspended ${step.packages - refused}, refused $refused")
            }
            is CameraLockStep.Release -> {
                // Out of [held] first (#2a), then unsuspend, then clear the record.
                released = step.packages to android.os.SystemClock.elapsedRealtime()
                held = emptySet()
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
                Log.i(LOG_TAG, "Unlocked: camera apps unsuspended ${step.packages - refused}, refused $refused")
                val stillSuspended = step.packages.filterTo(mutableSetOf()) { pkg ->
                    try {
                        pm.isPackageSuspended(pkg)
                    } catch (e: Exception) {
                        false
                    }
                }
                // No plan in this process yet (process start), or one is still suspended though
                // enforcement doesn't want it: apply() settles it - off this thread.
                if (applyAfterRelease(stillSuspended, AppEnforcer.lastPlanSuspend)) requestApply(app)
            }
            CameraLockStep.Nothing -> {
                if (!locked && held.isNotEmpty()) held = emptySet()
            }
        }
    }

    /** The camera handlers on this phone ([cameraHandlers]: the system handlers of the camera
     * actions, of third-party apps only the gesture's default) as [cameraLockTargets] keeps them. */
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
        fun gestureDefault(action: String): String? = try {
            pm.resolveActivity(Intent(action), PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong()))
                ?.activityInfo?.packageName
        } catch (e: Exception) {
            null
        }
        val system = (
            handlers(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, true) +
                handlers(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE, true) +
                handlers(MediaStore.INTENT_ACTION_VIDEO_CAMERA, true) +
                handlers(MediaStore.ACTION_IMAGE_CAPTURE, true)
            ).toSet()
        val defaults = listOf(
            gestureDefault(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA_SECURE),
            gestureDefault(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA),
        )
        val cameras = cameraHandlers(system, defaults)
        (cameras - system).takeIf { it.isNotEmpty() }?.let {
            Log.i(LOG_TAG, "Third-party camera the gesture opens, suspended while locked: $it")
        }
        val defaultDialer = try {
            app.getSystemService(TelecomManager::class.java)?.defaultDialerPackage
        } catch (e: Exception) {
            null
        }
        val protected = setOfNotNull(systemDialerPackage(app), defaultDialer) + inputMethodPackages(app) +
            runCatching { AppEnforcer.resolvePinLockHelpers(app) }.getOrDefault(emptySet()) +
            runCatching { AppEnforcer.resolveLockTaskHelpers(app) }.getOrDefault(emptySet())
        return cameraLockTargets(cameras, controllable, app.packageName, protected, AppEnforcer.systemRecentsPackage(app))
    }

    /** On its own coroutine, never the camera thread (`apply()` waits for AppEnforcer's lock). */
    private fun requestApply(app: Context) {
        applyScope.launch {
            try {
                AppEnforcer.apply(app, currentPolicyDecision().policy)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Apply after the camera release failed", e)
            }
        }
    }
}
