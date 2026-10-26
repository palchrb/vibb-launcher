package com.kidslauncher.mdm.lock

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.launch
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

    /** F3C-2: the declined start's `lockActive`, while it waits for the call's end (main thread); `null` = none. */
    private var waitingForCall: Boolean? = null

    private val main = Handler(Looper.getMainLooper())

    /** Any call of ours, a VoIP call, or Telecom's (unknown counts as one) - the boot Home's and the boot cover's
     * "never over a call" (F3C-1). Readable before the first unlock (our in-call service is direct-boot-aware). */
    fun callLive(context: Context): Boolean =
        OngoingCalls.hasLiveCall || VoipCalls.liveCall || SelfUpdate.telecomInCall(context.applicationContext) != false

    /** Whether Home was started (then Home's resume shows the lock). */
    fun startIfDue(context: Context, lockActive: Boolean, homeShown: Boolean): Boolean {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val boot = BootClock.bootCount()
        val stored = prefs.getInt(BOOT_COUNT, -1).takeIf { it >= 0 }
        val firstStart = boot >= 0 && boot != stored
        var inCall = false
        val action = bootHomeAction(firstStart, homeShown) {
            val kioskOn = LauncherPreferences.mdm().kioskEnabled()
            val liveCall = OngoingCalls.hasLiveCall || VoipCalls.liveCall
            val telecomInCall = SelfUpdate.telecomInCall(app)
            inCall = liveCall || telecomInCall != false
            bringHomeAtBoot(
                bootCount = boot,
                storedBootCount = stored,
                // The gate is an OR: the cached policy (a JSON decode on the main thread, before the
                // lock is up) is read only when neither the kiosk nor the lock decides it (16c).
                appsManaged = !kioskOn && !lockActive && currentPolicyDecision().policy?.allowlist != null,
                kioskOn = kioskOn,
                pinLockActive = lockActive,
                liveCall = liveCall,
                telecomInCall = telecomInCall,
            )
        }
        waitingForCall = if (bootHomeWaitsForCall(action, firstStart, inCall)) lockActive else null
        when (action) {
            BootHomeAction.NONE -> {
                if (firstStart) Log.i(LOG_TAG, "Not bringing Home at boot $boot (${if (inCall) "in a call - again at its end" else "unmanaged"})")
                return false
            }
            BootHomeAction.MARK_DONE -> {
                Log.i(LOG_TAG, "Home already up at boot $boot - no second start")
                prefs.edit().putInt(BOOT_COUNT, boot).apply()
                return false
            }
            BootHomeAction.START -> {
                if (!HomeFront.bring(app, "boot $boot", arrival = com.kidslauncher.mdm.ui.home.HomeArrival.BOOT)) return false
                // F3 timing: the real gap's end on our side - since the unlock reached this process.
                com.kidslauncher.mdm.Application.instance?.unlockedAtElapsed?.let {
                    Log.i(LOG_TAG, "Boot Home started ${android.os.SystemClock.elapsedRealtime() - it} ms after the unlock reached this process")
                }
                // apply(), not commit(): no disk wait on the main thread before the lock is up
                // (16c); a crash before the write only means one more Home start.
                prefs.edit().putInt(BOOT_COUNT, boot).apply()
                return true
            }
        }
    }

    /**
     * F3C-2: a call changed (our calls, a VoIP phase, a screen-on - Telecom's own calls have no listener). While the
     * boot's Home start waits for a call's end and none is live now, it runs [CALL_END_HOME_RETRY_MS] later, after the
     * call screen's "Ended", if still due then: the boot cover's take-over ([BootCover.takeOver] - android mode left it
     * up under the call) and the typed Home start in one main-thread pass. The boot count is stored only after a start,
     * so the start is still due; a Home that came up meanwhile is MARK_DONE. Main thread.
     */
    fun callMayHaveEnded(context: Context) {
        // Design 68 §3.2: a camera denial that waited for this call is written now (the apply's own rule decides).
        if (com.kidslauncher.mdm.server.deferredCameraApplyDue(com.kidslauncher.mdm.server.AppEnforcer.cameraDeferred) {
                com.kidslauncher.mdm.server.WindowRelock.liveCall(context)
            }
        ) {
            com.kidslauncher.mdm.server.AppEnforcer.cameraDeferred = false
            val app = context.applicationContext
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                try {
                    com.kidslauncher.mdm.server.AppEnforcer.apply(app, currentPolicyDecision())
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Apply after the call failed: ${e.javaClass.simpleName}")
                }
            }
        }
        if (waitingForCall == null || callLive(context)) return
        val app = context.applicationContext
        main.removeCallbacksAndMessages(RETRY_TOKEN)
        main.postAtTime({
            val lockActive = waitingForCall ?: return@postAtTime
            if (callLive(app)) return@postAtTime
            waitingForCall = null
            try {
                BootCover.takeOver(app)
                startIfDue(app, lockActive, PinLockRuntime.homeShown)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Boot Home after the call failed: ${e.javaClass.simpleName}")
            }
        }, RETRY_TOKEN, android.os.SystemClock.uptimeMillis() + CALL_END_HOME_RETRY_MS)
    }

    private val RETRY_TOKEN = Any()
}
