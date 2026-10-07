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
 *
 * Design 16c: when the system already started our Home in this process ([homeShown] - the stock
 * launcher's hand-over), a second start finds Home resumed and gives no new resume, so the lock
 * waited for its 1 s fallback: the boot is marked done instead and the lock goes up at once
 * ([bootHomeAction]).
 */
object BootHome {
    private const val PREFS = "boot_home"
    private const val BOOT_COUNT = "boot_count"

    /** Whether Home was started (then Home's resume shows the lock). */
    fun startIfDue(context: Context, lockActive: Boolean, homeShown: Boolean): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = BootClock.bootCount()
        val stored = prefs.getInt(BOOT_COUNT, -1).takeIf { it >= 0 }
        val firstStart = boot >= 0 && boot != stored
        val action = bootHomeAction(firstStart, homeShown) {
            val kioskOn = LauncherPreferences.mdm().kioskEnabled()
            bringHomeAtBoot(
                bootCount = boot,
                storedBootCount = stored,
                // The gate is an OR: the cached policy (a JSON decode on the main thread, before the
                // lock is up) is read only when neither the kiosk nor the lock decides it (16c).
                appsManaged = !kioskOn && !lockActive && currentPolicyDecision().policy?.allowlist != null,
                kioskOn = kioskOn,
                pinLockActive = lockActive,
                liveCall = OngoingCalls.hasLiveCall || VoipCalls.liveCall,
                telecomInCall = SelfUpdate.telecomInCall(app),
            )
        }
        when (action) {
            BootHomeAction.NONE -> {
                if (firstStart) Log.i(LOG_TAG, "Not bringing Home at boot $boot (unmanaged or in a call)")
                return false
            }
            BootHomeAction.MARK_DONE -> {
                Log.i(LOG_TAG, "Home already up at boot $boot - no second start")
                prefs.edit().putInt(BOOT_COUNT, boot).apply()
                return false
            }
            BootHomeAction.START -> {
                if (!HomeFront.bring(app, "boot $boot")) return false
                // apply(), not commit(): no disk wait on the main thread before the lock is up
                // (16c); a crash before the write only means one more Home start.
                prefs.edit().putInt(BOOT_COUNT, boot).apply()
                return true
            }
        }
    }
}
