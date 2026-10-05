package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.util.Calendar

enum class LockReason {
    NONE, SCREEN_TIME, BEDTIME
}

/**
 * Pure decision logic for whether the device should currently be locked - no Android or network
 * dependencies, so it keeps working from the last-cached policy even when the server is
 * unreachable.
 */
object KidModeEnforcer {

    /**
     * The lock in force right now: [LockReason.NONE] while the offline override or the pause is
     * active, otherwise [evaluate]. The one rule shared by the sync, the offline re-checks and
     * [AppEnforcer.apply] (which suspends every app but ours and the system dialer while this
     * isn't NONE), so the overlay and the suspension always agree.
     */
    fun lockReasonNow(policy: PolicyResponse?, overrideActive: Boolean, now: Calendar): LockReason =
        if (overrideActive) LockReason.NONE else evaluate(policy, now)

    fun evaluate(policy: PolicyResponse?, now: Calendar): LockReason {
        if (policy == null) return LockReason.NONE

        val minuteOfDay = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        if (isRestricted(minuteOfDay, policy.bedtimeStartMinutes, policy.bedtimeEndMinutes)) {
            return LockReason.BEDTIME
        }

        val isWeekend = now.get(Calendar.DAY_OF_WEEK).let {
            it == Calendar.SATURDAY || it == Calendar.SUNDAY
        }
        val allowedStart = if (isWeekend) policy.weekendStartMinutes else policy.weekdayStartMinutes
        val allowedEnd = if (isWeekend) policy.weekendEndMinutes else policy.weekdayEndMinutes

        return if (isOutsideAllowedWindow(minuteOfDay, allowedStart, allowedEnd)) {
            LockReason.SCREEN_TIME
        } else {
            LockReason.NONE
        }
    }

    /** True while [minuteOfDay] falls inside a restricted [start]-[end] window (e.g. bedtime).
     * Handles overnight wraparound (start > end, e.g. 21:00-07:00). Null or start == end means
     * "no restriction". */
    private fun isRestricted(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return inWindow(minuteOfDay, start, end)
    }

    /** True while [minuteOfDay] falls outside an allowed [start]-[end] window (e.g. weekday screen
     * time). Handles overnight wraparound. Null or start == end means "always allowed". */
    private fun isOutsideAllowedWindow(minuteOfDay: Int, start: Int?, end: Int?): Boolean {
        if (start == null || end == null || start == end) return false
        return !inWindow(minuteOfDay, start, end)
    }

    private fun inWindow(minuteOfDay: Int, start: Int, end: Int): Boolean {
        return if (start < end) {
            minuteOfDay in start until end
        } else {
            minuteOfDay >= start || minuteOfDay < end
        }
    }
}
