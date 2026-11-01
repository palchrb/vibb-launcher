package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.preferences.LauncherPreferences

/** How long one offline override lasts (it ends sooner at the next successful sync). */
internal const val OVERRIDE_DURATION_MS = 2 * 60 * 60 * 1000L

/**
 * The offline "unlock code" failsafe's window: the parent PIN typed on the time-lock screen lifted the app
 * restrictions and the time rules for up to two hours. Nothing starts one since 2026-11-01 (the user, 2026-10-26:
 * the parent lifts a rule from the PWA only - `LockActivity` has no code entry, `ParentPinEntry` no `override`);
 * an override opened before the update still runs out here, ended by the sync, the relock or its end.
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
}
