package com.kidslauncher.mdm.lock

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.currentPolicyDecision

/**
 * Debug build only (src/debug): design 59c's plain Android lock prototype on or off. Exported for
 * `adb shell am broadcast -n $P/com.kidslauncher.mdm.lock.PlainLockDebugReceiver --ez on true|false`, guarded by DUMP
 * (shell has it, apps don't). Stores the switch ([PlainLockDebug], survives restarts), runs a full apply (the keyguard
 * features), `--es mode redact|cancel` picks the variant (stored), sets the lock wallpaper (`--ez wallpaper false` skips it) and refreshes the curated rows. Without `on` it
 * only reports. Logcat `PlainLock` and the broadcast's result data say what is in force.
 */
class PlainLockDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val on = if (intent.hasExtra(EXTRA_ON)) intent.getBooleanExtra(EXTRA_ON, false) else null
        val wallpaper = intent.getBooleanExtra(EXTRA_WALLPAPER, true)
        val stored = on?.let { PlainLockDebug.store(app, it) }
        intent.getStringExtra(EXTRA_MODE)?.takeIf { it == "redact" || it == "cancel" }?.let { PlainLockDebug.storeMode(app, it) }
        val pending = goAsync()
        Thread {
            val result = try {
                if (on != null) {
                    AppEnforcer.apply(app, currentPolicyDecision())
                    if (wallpaper) PlainLockDebug.setLockWallpaper(app, on)
                }
                val dpm = app.getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
                val features = dpm.getKeyguardDisabledFeatures(admin)
                val km = app.getSystemService(KeyguardManager::class.java)
                "plain=${PlainLockDebug.enabled(app)} variant=${PlainLockDebug.mode(app)} faceOn=${AndroidLock.faceOn} mode=${PinLockRuntime.mode} " +
                    "features=0x${Integer.toHexString(features)} secure=${km.isDeviceSecure} ${KeyguardCover.describe()}"
            } catch (e: Exception) {
                Log.w(PlainLockDebug.LOG_TAG, "Apply failed", e)
                "apply failed: ${e.javaClass.simpleName}"
            }
            Handler(Looper.getMainLooper()).post { PlainLockDebug.refresh() }
            val summary = result + if (stored == false) " (the switch couldn't be stored)" else ""
            Log.i(PlainLockDebug.LOG_TAG, summary)
            pending.resultData = summary
            pending.finish()
        }.start()
    }

    companion object {
        const val EXTRA_ON = "on"
        const val EXTRA_WALLPAPER = "wallpaper"
        const val EXTRA_MODE = "mode"
    }
}
