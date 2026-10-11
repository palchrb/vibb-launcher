package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.kidslauncher.mdm.preferences.LauncherPreferences

private const val LOG_TAG = "OfflineOverride"

/** How long one offline override lasts (it ends sooner at the next successful sync). */
internal const val OVERRIDE_DURATION_MS = 2 * 60 * 60 * 1000L

/**
 * The offline "unlock code" failsafe: the parent PIN typed on the time-lock screen
 * ([com.kidslauncher.mdm.ui.LockActivity]) lifts the app restrictions and the time rules for up
 * to two hours, entirely without network. The PIN is checked - against the hash+salt cached from
 * the last successful policy sync ([PolicyResponse.overridePinHash]/[PolicyResponse.overridePinSalt]),
 * with the escalating lockout - by [ParentPin], which also calls [activate].
 */
object OfflineOverride {

    /** True while a locally-verified override is still within its time window - self-clears (and
     * returns false) once expired, so a stale flag can never linger past its own timeout even if
     * the device never manages to sync again. Checked by both [AppEnforcer] (to treat the policy
     * as fully open) and [MdmSyncWorker] (to force the lock decision to [LockReason.NONE]). */
    fun isActive(): Boolean {
        val mdm = LauncherPreferences.mdm()
        if (!mdm.offlineOverrideActive()) return false
        val start = WindowStart(
            mdm.offlineOverrideExpiresAt(),
            mdm.offlineOverrideElapsedStart(),
            mdm.offlineOverrideBoot(),
        )
        // Both the wall clock and elapsed time since boot must agree the window is still open,
        // so setting the clock back can't stretch it - see timedWindowActive.
        if (!BootClock.isActive(start, OVERRIDE_DURATION_MS)) {
            clear()
            return false
        }
        return true
    }

    /** Called once real server contact is restored (a successful policy fetch, not the
     * cached-fallback path) - the override's whole job is done the moment the device can hear
     * from the server again, so real policy should reassert immediately rather than waiting out
     * the rest of the time window. */
    fun clear() {
        val mdm = LauncherPreferences.mdm()
        mdm.offlineOverrideActive(false)
        mdm.offlineOverrideExpiresAt(0)
    }

    /** Lifts all restrictions for [OVERRIDE_DURATION_MS] from [start] - called only by [ParentPin.check]
     * after a correct PIN and its committed `unlocked` event (design 24 §2). Any thread. */
    fun activate(context: Context, start: WindowStart) {
        val mdm = LauncherPreferences.mdm()
        mdm.offlineOverrideExpiresAt(start.untilWallMs)
        mdm.offlineOverrideElapsedStart(start.elapsedStartMs)
        mdm.offlineOverrideBoot(start.bootCount)
        mdm.offlineOverrideActive(true)
        mdm.offlineOverrideUsedPendingReport(true)
        // The enforced policy, not `null`: the override releases the app restrictions and the
        // schedule by itself, while the call rules and the hardening switches stay as the policy
        // says. Off the main thread - apply() is synchronized and can wait for a running sync.
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppEnforcer.apply(appContext, currentPolicyDecision().policy)
                reevaluateLockReasonFromCache(appContext)
                // The rules come back when the override ends: that's the next boundary now.
                com.kidslauncher.mdm.timerules.TimeRuleAlarm.schedule(appContext)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Applying the offline override failed", e)
            }
        }
    }

}
