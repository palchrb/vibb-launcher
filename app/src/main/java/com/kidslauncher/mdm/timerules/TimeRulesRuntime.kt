package com.kidslauncher.mdm.timerules

import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.calls.messagingAppPackages
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.KidModeEnforcer
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.TimeState
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.ui.LockActivity
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

private const val LOG_TAG = "TimeRulesRuntime"

/** CE prefs file for the screen-time ledger and the lift records (written with commit()). */
private const val PREFS = "time_rules_state"
private const val LEDGER_KEY = "screen_time_ledger"
private const val LIFTS_KEY = "lift_records"

/**
 * The Android side of the time rules (handy step 6): reads the clocks, keeps the screen-time
 * ledger and the lift records in CE preferences (synchronous `commit()`, so a process death or
 * reboot keeps them), and answers "what lock is in force" for the sync, [com.kidslauncher.mdm.server.AppEnforcer],
 * the lock screen and the call path. The decisions are the pure functions in this package. Only
 * used after the first unlock ([init] runs from the unlocked setup).
 */
object TimeRulesRuntime {
    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        TimeRulesStore.liveDecider = { callsBlockedNow() }
    }

    fun clocks() = Clocks(System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())

    private fun zone(): ZoneId = ZoneId.systemDefault()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun overrideActive() = OfflineOverride.isActive() || RestrictionsPause.isActive()

    private fun loadLedger(context: Context): ScreenTimeLedger = try {
        prefs(context).getString(LEDGER_KEY, null)?.let { ServerJson.decodeFromString(ScreenTimeLedger.serializer(), it) }
            ?: ScreenTimeLedger()
    } catch (e: Exception) {
        // An unreadable ledger restarts today's count at zero; the parent sees the day's usage
        // in the status report, and the date/time lock keeps this from being a way to refill.
        Log.e(LOG_TAG, "Screen-time ledger unreadable, starting over", e)
        ScreenTimeLedger()
    }

    private fun saveLedger(context: Context, before: ScreenTimeLedger, after: ScreenTimeLedger) {
        if (before == after) return
        val ok = prefs(context).edit().putString(LEDGER_KEY, ServerJson.encodeToString(ScreenTimeLedger.serializer(), after)).commit()
        if (!ok) Log.w(LOG_TAG, "Couldn't save the screen-time ledger")
    }

    /** The ledger moved to now, with new budget lifts applied - saved when it changed. */
    @Synchronized
    fun ledger(context: Context, policy: TimePolicy?, clocks: Clocks = clocks()): ScreenTimeLedger {
        val before = loadLedger(context)
        var after = observe(before, clocks, zone()).ledger
        if (policy != null) after = applyBudgetLifts(after, policy.lifts)
        saveLedger(context, before, after)
        return after
    }

    /** Adds a counted stretch that began at elapsed realtime [sinceElapsedMs]. */
    @Synchronized
    fun accrueScreenTime(context: Context, sinceElapsedMs: Long, clocks: Clocks = clocks()): ScreenTimeLedger {
        val before = loadLedger(context)
        val after = accrue(before, sinceElapsedMs, clocks, zone())
        saveLedger(context, before, after)
        return after
    }

    @Synchronized
    fun liftRecords(context: Context, lifts: List<Lift>, clocks: Clocks = clocks()): List<LiftRecord> {
        val serializer = ListSerializer(LiftRecord.serializer())
        val before = try {
            prefs(context).getString(LIFTS_KEY, null)?.let { ServerJson.decodeFromString(serializer, it) } ?: emptyList()
        } catch (e: Exception) {
            // Unreadable records: lifts start counting again from now - bounded by the server's
            // expires_at, so at worst a lift runs to its server-side end.
            Log.e(LOG_TAG, "Lift records unreadable", e)
            emptyList()
        }
        val after = updateLiftRecords(before, lifts, clocks)
        if (after != before) {
            prefs(context).edit().putString(LIFTS_KEY, ServerJson.encodeToString(serializer, after)).commit()
        }
        return after
    }

    private fun messagingApps(context: Context): Set<String> {
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        return messagingAppPackages(rules, CallSystem.defaultSmsPackage(context))
    }

    /** Everything the lock needs, at one instant. */
    data class Snapshot(
        val lock: TimeLock,
        val policy: TimePolicy?,
        val ledger: ScreenTimeLedger?,
        val budget: BudgetUse?,
        val records: List<LiftRecord>,
        val clocks: Clocks,
    )

    fun snapshot(context: Context, policy: PolicyResponse?, overrideActive: Boolean = overrideActive()): Snapshot {
        val clocks = clocks()
        val tp = KidModeEnforcer.timePolicyOf(policy)
            ?: return Snapshot(UNLOCKED, null, null, null, emptyList(), clocks)
        val ledger = ledger(context, tp, clocks)
        val records = liftRecords(context, tp.lifts, clocks)
        val budget = budgetUse(ledger, tp)
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(clocks.wallMs), zone())
        val lock = decideTimeLock(tp, at, overrideActive, activeRuleLifts(records, tp.lifts, clocks), budget, messagingApps(context))
        return Snapshot(lock, tp, ledger, budget, records, clocks)
    }

    /** The lock in force now for [policy] (the one being enforced). */
    fun currentLock(context: Context, policy: PolicyResponse?, overrideActive: Boolean): TimeLock =
        snapshot(context, policy, overrideActive).lock

    /** The call path's live answer (TimeRulesStore.liveDecider): a no-calls rule is in force,
     * counting lifts and the override PIN/pause. */
    fun callsBlockedNow(): Boolean {
        val context = appContext ?: return callsBlockedAt(TimeRulesStore.policy, LocalDateTime.now(), false, RuleLifts())
        val tp = TimeRulesStore.policy ?: return false
        val clocks = clocks()
        val records = liftRecords(context, tp.lifts, clocks)
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(clocks.wallMs), zone())
        return callsBlockedAt(tp, at, overrideActive(), activeRuleLifts(records, tp.lifts, clocks))
    }

    /** The status report's `time_state`. */
    fun report(context: Context, policy: PolicyResponse?, lockReason: LockReason): TimeState {
        val snap = snapshot(context, policy)
        val top = snap.lock.rules.firstOrNull()
        return TimeState(
            day = snap.ledger?.day,
            usedMinutes = ((snap.ledger?.usedMs ?: 0) / 60_000L).toInt(),
            budgetMinutes = snap.budget?.effectiveMinutes,
            extraMinutes = snap.ledger?.extraMinutes ?: 0,
            activeRuleId = top?.id,
            activeRuleName = top?.name,
            callsBlocked = snap.lock.callsBlocked,
            lockReason = lockReason.name,
            liftsActive = snap.ledger?.let { activeLiftIds(snap.records, snap.policy?.lifts.orEmpty(), it, snap.clocks) }.orEmpty(),
        )
    }

    /** When the lock may change next: rule boundaries, midnight with a budget, lift expiries,
     * and the end of an active offline override or pause (the rules come back then). */
    fun nextBoundary(context: Context): Instant? {
        val policy = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy) ?: return null
        val clocks = clocks()
        val records = liftRecords(context, policy.lifts, clocks)
        val mdm = LauncherPreferences.mdm()
        val overrideEnds = listOfNotNull(
            mdm.offlineOverrideExpiresAt().takeIf { OfflineOverride.isActive() },
            mdm.restrictionsPausedUntil().takeIf { RestrictionsPause.isActive() },
        ).map { Instant.ofEpochMilli(it) }
        return nextBoundary(policy, Instant.ofEpochMilli(clocks.wallMs), zone(), liftExpiries(records, clocks) + overrideEnds)
    }

    /**
     * Re-checks the lock from the cache (no network) and acts: re-applies enforcement when it
     * changed, shows [LockActivity] when a lock begins (not over a call in progress - it shows when
     * the kid is back on Home), re-arms the boundary alarm and the screen-time timer. Called on the
     * boundary alarm, time/zone changes, boot, screen on / user present, the budget running out
     * and every accepted policy. Main thread.
     */
    fun recheck(context: Context) {
        // First: fold the running screen-time stretch into the ledger, so the budget the lock
        // decision sees includes it (the budget timer lands here), and re-arm the timers.
        ScreenTimeTracker.update(context)
        try {
            val changedTo = reevaluateLockReasonFromCache(context)
            if (changedTo != null && changedTo != LockReason.NONE && OngoingCalls.calls.isEmpty()) {
                LockActivity.start(context)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Lock re-check failed", e)
        }
        TimeRuleAlarm.schedule(context)
    }
}
