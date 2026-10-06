package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

private const val LOG_TAG = "ScreenTimeout"
const val SCREEN_TIMEOUT_MIN_SECONDS = 15
const val SCREEN_TIMEOUT_MAX_SECONDS = 600
const val SCREEN_TIMEOUT_DEFAULT_SECONDS = 60

/** What [ScreenTimeout.apply] does - see [screenTimeoutAction]. */
sealed interface ScreenTimeoutAction {
    /** Set `SCREEN_OFF_TIMEOUT` to [millis], keep it there, block the Settings control. */
    data class Enforce(val millis: Long) : ScreenTimeoutAction
    /** Stop enforcing and lift the Settings block; the current value stays. */
    data object Release : ScreenTimeoutAction
    /** No policy to go by (none applied yet, or the fallback plan): change nothing. */
    data object Keep : ScreenTimeoutAction
}

/**
 * Auto-lock (emulator run 2026-10-06): the parent's `screen_timeout_seconds`. Out-of-range values
 * are clamped to 15 s..10 min (never "never"), a non-positive one is the 1-minute default. An
 * older server (no key) and the offline override / pause (the standing rule: every restriction
 * is liftable) release it; without a policy nothing changes.
 */
fun screenTimeoutAction(policyPresent: Boolean, policySeconds: Int?, overrideActive: Boolean): ScreenTimeoutAction = when {
    !policyPresent -> ScreenTimeoutAction.Keep
    overrideActive || policySeconds == null -> ScreenTimeoutAction.Release
    policySeconds <= 0 -> ScreenTimeoutAction.Enforce(SCREEN_TIMEOUT_DEFAULT_SECONDS * 1000L)
    else -> ScreenTimeoutAction.Enforce(policySeconds.coerceIn(SCREEN_TIMEOUT_MIN_SECONDS, SCREEN_TIMEOUT_MAX_SECONDS) * 1000L)
}

/**
 * Applies the parent's screen timeout as device owner with
 * `DevicePolicyManager.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, ms)` -
 * SCREEN_OFF_TIMEOUT is on the platform's allowlist for device/profile owners (AOSP
 * `DevicePolicyManager.java`, `SystemSettingsWhitelist`; `DevicePolicyManagerService.setSystemSetting`
 * checks DO/PO and the allowlist and writes with a cleared calling identity), API 28+, unchanged
 * through 36. Kept enforced: `DISALLOW_CONFIG_SCREEN_TIMEOUT` hides the control in Settings (the
 * DO's own write isn't affected - it bypasses user restrictions), a [ContentObserver] puts the
 * value back whenever anything else changes it, and every [AppEnforcer.apply] re-checks it.
 * Handy's PIN lock locks on every screen-off, so this is the lock delay too. The effective
 * timeout can't exceed a `setMaximumTimeToLock` (we set none).
 */
object ScreenTimeout {
    @Volatile
    private var targetMs: Long? = null
    @Volatile
    private var observer: ContentObserver? = null
    private val worker = Executors.newSingleThreadExecutor()

    fun apply(context: Context, dpm: DevicePolicyManager, admin: ComponentName, action: ScreenTimeoutAction) {
        when (action) {
            ScreenTimeoutAction.Keep -> return
            ScreenTimeoutAction.Release -> {
                targetMs = null
                setRestriction(dpm, admin, false)
            }
            is ScreenTimeoutAction.Enforce -> {
                targetMs = action.millis
                ensureObserver(context.applicationContext)
                enforce(context, dpm, admin, action.millis)
                setRestriction(dpm, admin, true)
            }
        }
    }

    /** The value in force now, seconds - for the status report. */
    fun currentSeconds(context: Context): Int? = currentMs(context)?.let { (it / 1000L).toInt() }

    private fun currentMs(context: Context): Long? = try {
        Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
    } catch (e: Exception) {
        null
    }

    private fun enforce(context: Context, dpm: DevicePolicyManager, admin: ComponentName, millis: Long) {
        if (currentMs(context) == millis) return
        try {
            dpm.setSystemSetting(admin, Settings.System.SCREEN_OFF_TIMEOUT, millis.toString())
            Log.i(LOG_TAG, "Screen timeout set to ${millis / 1000} s")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the screen timeout", e)
        }
    }

    private fun setRestriction(dpm: DevicePolicyManager, admin: ComponentName, on: Boolean) {
        try {
            if (on) {
                dpm.addUserRestriction(admin, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT)
            } else {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_CONFIG_SCREEN_TIMEOUT)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't ${if (on) "set" else "clear"} DISALLOW_CONFIG_SCREEN_TIMEOUT", e)
        }
    }

    /** Once per process: puts the target back whenever the setting changes (off the main thread). */
    @Synchronized
    private fun ensureObserver(context: Context) {
        if (observer != null) return
        val created = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val target = targetMs ?: return
                worker.execute {
                    try {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return@execute
                        if (!dpm.isDeviceOwnerApp(context.packageName)) return@execute
                        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
                        if (targetMs == target) enforce(context, dpm, admin, target)
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Re-applying the screen timeout failed", e)
                    }
                }
            }
        }
        try {
            context.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, created,
            )
            observer = created
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the screen timeout", e)
        }
    }
}
