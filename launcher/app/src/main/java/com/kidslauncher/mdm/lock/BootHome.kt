package com.kidslauncher.mdm.lock

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.SelfUpdate
import com.kidslauncher.mdm.server.bringHomeAtBoot
import com.kidslauncher.mdm.server.currentPolicyDecision

private const val LOG_TAG = "BootHome"

/**
 * Design 16 (A with QA #1/#6): before the CE unlock only direct-boot-aware HOME activities resolve,
 * so a stock launcher like Pixel's is Home at every boot and stays until it hands over by itself
 * (undocumented) - its drawer, wallpaper picker and Recents were open for 3-5 s. The first process
 * start of a boot (from [PinLockRuntime.init], after the lock's state is loaded) brings our Home to
 * the front with the typed HOME intent ([HomeFront]); Home roots lock task with the kiosk on and
 * shows the lock. The boot count is stored only after Home was started (CE prefs `boot_home`), so
 * a later start in the same boot (a crash) doesn't do it again. Main thread.
 */
object BootHome {
    private const val PREFS = "boot_home"
    private const val BOOT_COUNT = "boot_count"

    /** Whether Home was started. */
    fun startIfDue(context: Context, lockActive: Boolean): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = BootClock.bootCount()
        val stored = prefs.getInt(BOOT_COUNT, -1).takeIf { it >= 0 }
        // Cheap exit first: a restart within the same boot reads no policy.
        if (boot < 0 || boot == stored) return false
        val due = bringHomeAtBoot(
            bootCount = boot,
            storedBootCount = stored,
            appsManaged = currentPolicyDecision().policy?.allowlist != null,
            kioskOn = LauncherPreferences.mdm().kioskEnabled(),
            pinLockActive = lockActive,
            liveCall = OngoingCalls.hasLiveCall || VoipCalls.liveCall,
            telecomInCall = SelfUpdate.telecomInCall(app),
        )
        if (!due) {
            Log.i(LOG_TAG, "Not bringing Home at boot $boot (unmanaged or in a call)")
            return false
        }
        if (!HomeFront.bring(app, "boot $boot")) return false
        prefs.edit().putInt(BOOT_COUNT, boot).commit()
        return true
    }
}
