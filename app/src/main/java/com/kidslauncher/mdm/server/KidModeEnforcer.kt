package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.RuleLifts
import com.kidslauncher.mdm.timerules.TimeLock
import com.kidslauncher.mdm.timerules.TimePolicy
import com.kidslauncher.mdm.timerules.decideTimeLock
import com.kidslauncher.mdm.timerules.legacyTimePolicy
import java.time.LocalDateTime

/**
 * Why the phone is locked. Stored as the `lock_reason` preference and sent as the status report's
 * `lockReason`. SCREEN_TIME = the daily budget is used up; BEDTIME/SCHOOL/RULE = a time rule of
 * that kind (RULE = custom) is active. The names are stored, so existing values must stay.
 */
/** Status-report capability: this launcher enforces `time_policy` (handy step 6). */
const val TIME_RULES_CAPABILITY = "time_rules_v1"

enum class LockReason {
    NONE, SCREEN_TIME, BEDTIME, SCHOOL, RULE
}

/**
 * Pure decision logic for whether the device should currently be locked - no Android or network
 * dependencies, so it keeps working from the last-cached policy even when the server is
 * unreachable. The rules themselves are [com.kidslauncher.mdm.timerules] (handy step 6); this is
 * the bridge from a [PolicyResponse].
 */
object KidModeEnforcer {

    /** The time rules and budget [policy] enforces: its `time_policy`, or an older server's fixed
     * weekday/weekend/bedtime windows converted to rules. `null` = no policy at all. */
    fun timePolicyOf(policy: PolicyResponse?): TimePolicy? = policy?.let {
        it.timePolicy ?: legacyTimePolicy(
            it.weekdayStartMinutes, it.weekdayEndMinutes,
            it.weekendStartMinutes, it.weekendEndMinutes,
            it.bedtimeStartMinutes, it.bedtimeEndMinutes,
        )
    }

    /**
     * The lock in force at local date-time [at]: nothing while the offline override or the pause
     * is active, otherwise [decideTimeLock]. The one rule shared by the sync, the offline re-checks
     * and [AppEnforcer.apply] (which suspends every app but ours, the system dialer and the
     * lock's usable apps while it is locked), so the lock screen and the suspension always agree.
     */
    fun lockNow(
        policy: PolicyResponse?,
        overrideActive: Boolean,
        at: LocalDateTime,
        lifts: RuleLifts = RuleLifts(),
        budget: BudgetUse? = null,
        messagingApps: Set<String> = emptySet(),
    ): TimeLock = decideTimeLock(timePolicyOf(policy), at, overrideActive, lifts, budget, messagingApps)

    fun evaluate(policy: PolicyResponse?, at: LocalDateTime): LockReason = lockNow(policy, false, at).reason
}
