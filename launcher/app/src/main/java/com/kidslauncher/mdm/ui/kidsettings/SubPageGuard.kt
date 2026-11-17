package com.kidslauncher.mdm.ui.kidsettings

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.SharedPreferences
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.ui.LockActivity

/**
 * Design 111 §2.2: [subPageGate] for one of Kid Settings' four sub-pages, run and acted on. The page calls [start] first
 * thing in `onStart` (before any radio work: Location, a Wi-Fi scan, Bluetooth discovery) and returns when it is true,
 * [check] in `onResume`, and [stop] in `onStop`; while started the guard runs the gate itself on a `lockReason` change,
 * on a stored policy and after each apply. [beforeLeave] runs before the page goes (the sound picker's preview);
 * [stayed] after a policy or an apply the page stays for (Bluetooth's rows, Snarveier's list). Main thread.
 */
internal class SubPageGuard(
    private val activity: Activity,
    private val page: SubPage,
    private val beforeLeave: () -> Unit = {},
    private val stayed: () -> Unit = {},
) {
    private var watching = false

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            LauncherPreferences.mdm().keys().lockReason() -> check()
            LauncherPreferences.mdm().keys().kidModePolicy() -> if (!check()) stayed()
        }
    }

    private val appliedListener: () -> Unit = { if (!check()) stayed() }

    /** Watches, then the gate: true when the page left. */
    fun start(): Boolean {
        if (!watching) {
            watching = true
            LauncherPreferences.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefsListener)
            AppEnforcer.addAppliedListener(appliedListener)
        }
        return check()
    }

    fun stop() {
        if (!watching) return
        watching = false
        LauncherPreferences.getSharedPreferences().unregisterOnSharedPreferenceChangeListener(prefsListener)
        AppEnforcer.removeAppliedListener(appliedListener)
    }

    /** The gate now; true when the page left (or was already leaving). */
    fun check(): Boolean {
        if (activity.isFinishing) return true
        val owner = activity.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(activity.packageName) == true
        val cached = cachedPolicy()
        val gate = subPageGate(
            LauncherPreferences.mdm().lockReason(),
            controlsSection(owner, cached),
            page,
            mask = (cached as? CachedPolicy.Ok)?.policy?.quickControlsMask,
            soundWritable = page == SubPage.SOUND_PICKER && KidSounds.canWrite(activity),
        )
        when (gate) {
            SubPageGate.OPEN -> return false
            SubPageGate.REDIRECT -> {
                beforeLeave()
                LockActivity.start(activity)
                activity.finish()
            }
            SubPageGate.FINISH -> {
                beforeLeave()
                activity.finish()
            }
        }
        return true
    }
}
