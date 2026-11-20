package com.kidslauncher.mdm.lock

import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.timerules.TimeLock
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.timerules.UNLOCKED

private const val LOG_TAG = "GlanceRowLock"

/**
 * The time lock the glance's rows follow outside the panel (the user, 2026-11-20): the store's wake and buzz count only
 * rows the rule leaves on the lock ([glanceRowsUnderLock]), so a post the glance doesn't show never lights the screen.
 * One pref read answers while nothing locks; under a rule or the used-up budget the time-rule screen's snapshot. Main
 * thread. Never throws.
 */
object GlanceRowLock {
    /** A fault while a rule may lock: no row counts (no wake, no buzz) - fail closed. */
    val CLOSED = TimeLock(LockReason.SCREEN_TIME, callsAllowed = false)

    fun now(): TimeLock = try {
        val reason = LauncherPreferences.mdm().lockReason()
        if (reason == LockReason.NONE) UNLOCKED else glanceRowLock(reason, TimeRulesRuntime.lockNow() ?: CLOSED)
    } catch (e: Throwable) {
        Log.w(LOG_TAG, "Glance row lock failed: ${e.javaClass.simpleName}")
        CLOSED
    }
}
