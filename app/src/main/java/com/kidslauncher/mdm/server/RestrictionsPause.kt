package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.preferences.LauncherPreferences

/** How long one "pause all restrictions" lasts. Re-entering the PIN starts a new window. */
const val RESTRICTIONS_PAUSE_DURATION_MS = 2 * 60 * 60 * 1000L

/**
 * Pure check behind [RestrictionsPause.isActive]. A window that ends more than
 * [RESTRICTIONS_PAUSE_DURATION_MS] in the future can only come from the clock having been set
 * back, so it counts as expired rather than as a longer pause. A pause saved by an older build
 * (flag on, no end time) has `pausedUntil == 0` and is expired too.
 */
fun pauseActive(paused: Boolean, pausedUntil: Long, now: Long): Boolean =
    paused && now < pausedUntil && pausedUntil - now <= RESTRICTIONS_PAUSE_DURATION_MS

/**
 * The Settings "pause all restrictions" kill-switch: an escape hatch for a broken policy or
 * launcher update. Upstream's switch was open to anyone in Settings when no PIN was configured,
 * never expired and was never reported. Here it can only be turned on after entering the device's
 * offline-override PIN (so it's unavailable while the server has no PIN set), it ends by itself
 * after [RESTRICTIONS_PAUSE_DURATION_MS], and every status report says whether it's on.
 * [AppEnforcer] and the lock decision treat it exactly like the offline override.
 */
object RestrictionsPause {

    /** Self-clears once expired, so a stale flag can't linger. */
    fun isActive(): Boolean {
        val mdm = LauncherPreferences.mdm()
        if (!mdm.restrictionsPaused()) return false
        if (pauseActive(true, mdm.restrictionsPausedUntil(), System.currentTimeMillis())) return true
        clear()
        return false
    }

    /** Call only after [OfflineOverride.verifyPin] succeeded. */
    fun start() {
        val mdm = LauncherPreferences.mdm()
        mdm.restrictionsPausedUntil(System.currentTimeMillis() + RESTRICTIONS_PAUSE_DURATION_MS)
        mdm.restrictionsPaused(true)
    }

    fun clear() {
        val mdm = LauncherPreferences.mdm()
        mdm.restrictionsPaused(false)
        mdm.restrictionsPausedUntil(0)
    }
}
