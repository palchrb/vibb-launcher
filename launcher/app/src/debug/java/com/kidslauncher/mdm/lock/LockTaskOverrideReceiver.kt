package com.kidslauncher.mdm.lock

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver

/**
 * Debug build only (src/debug, docs/testing/emulator.md §6e): sets or resets the lock-task override
 * for the design 16d experiments, then has [LockTaskChrome] apply the chrome at once and reports
 * the features and packages now in force (logcat `LockTaskDebug`, and the broadcast's result data).
 * Exported for `adb shell am broadcast -n ...` but guarded by DUMP (shell has it, apps don't):
 *
 * `--ei clear_features <mask> --ei add_features <mask> --es extra_lock_task_packages <a,b>` sets the
 * whole override (a missing extra = nothing); `--ez reset true` (any `reset` extra) clears it.
 */
class LockTaskOverrideReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val override = if (intent.hasExtra(EXTRA_RESET)) {
            null
        } else {
            lockTaskOverride(
                intent.getIntExtra(EXTRA_CLEAR, 0),
                intent.getIntExtra(EXTRA_ADD, 0),
                intent.getStringExtra(EXTRA_PACKAGES),
            )
        }
        val stored = LockTaskDebug.store(app, override)
        val pending = goAsync()
        // Off the main thread: the chrome pass may resolve helpers and makes DPM calls.
        Thread {
            val result = try {
                LockTaskChrome.refresh(app)
                inForce(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Chrome pass failed", e)
                "chrome pass failed: ${e.javaClass.simpleName}"
            }
            val summary = "override=${override ?: "none (reset)"} stored=$stored -> $result"
            Log.i(LOG_TAG, summary)
            pending.resultData = summary
            pending.finish()
        }.start()
    }

    /** What the platform holds now (read back, not what was asked for). */
    private fun inForce(context: Context): String {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) return "not device owner: nothing applied"
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        return "features ${describeLockTaskFeatures(dpm.getLockTaskFeatures(admin))}, " +
            "packages ${dpm.getLockTaskPackages(admin).sorted()}, locked=${PinLockRuntime.chromeLocked}, kiosk=${LockTaskChrome.kioskOn}"
    }

    companion object {
        private const val LOG_TAG = "LockTaskDebug"
        const val EXTRA_CLEAR = "clear_features"
        const val EXTRA_ADD = "add_features"
        const val EXTRA_PACKAGES = "extra_lock_task_packages"
        const val EXTRA_RESET = "reset"
    }
}
