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
import com.kidslauncher.mdm.server.OVERRIDE_DURATION_MS
import com.kidslauncher.mdm.server.RESTRICTIONS_PAUSE_DURATION_MS
import com.kidslauncher.mdm.server.RelockTrigger
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.WindowStart
import com.kidslauncher.mdm.server.windowEndMs
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

    /** An unreadable record fails closed: today counts as used up and the budget lifts being
     * delivered as applied ([unreadableLedger], QA step 6 #6); reported in `time_state`. */
    private fun loadLedger(context: Context, lifts: List<Lift>, clocks: Clocks, zone: ZoneId): ScreenTimeLedger {
        val raw = prefs(context).getString(LEDGER_KEY, null) ?: return ScreenTimeLedger()
        return try {
            ServerJson.decodeFromString(ScreenTimeLedger.serializer(), raw)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Screen-time ledger unreadable: today counts as used up", e)
            unreadableLedger(clocks, zone, lifts)
        }
    }

    private fun saveLedger(context: Context, before: ScreenTimeLedger, after: ScreenTimeLedger) {
        if (before == after) return
        val ok = prefs(context).edit().putString(LEDGER_KEY, ServerJson.encodeToString(ScreenTimeLedger.serializer(), after)).commit()
        if (!ok) Log.w(LOG_TAG, "Couldn't save the screen-time ledger")
    }

    /** The ledger moved to now in [zone], with new budget lifts applied - saved when it changed. */
    @Synchronized
    fun ledger(context: Context, policy: TimePolicy?, clocks: Clocks = clocks(), zone: ZoneId = zone()): ScreenTimeLedger {
        val before = loadLedger(context, policy?.lifts.orEmpty(), clocks, zone)
        var after = observe(before, clocks, zone).ledger
        if (policy != null) after = applyBudgetLifts(after, policy.lifts)
        saveLedger(context, before, after)
        return after
    }

    /** Adds a counted stretch that began at elapsed realtime [sinceElapsedMs]. */
    @Synchronized
    fun accrueScreenTime(context: Context, sinceElapsedMs: Long, clocks: Clocks = clocks()): ScreenTimeLedger {
        val zone = zone()
        val before = loadLedger(context, KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy)?.lifts.orEmpty(), clocks, zone)
        val after = accrue(before, sinceElapsedMs, clocks, zone)
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

    /** Everything the lock needs, at one instant, in one [zone] (the ledger's day and the rules'
     * local time are both counted in it; the status report sends it - design 34 P13). */
    data class Snapshot(
        val lock: TimeLock,
        val policy: TimePolicy?,
        val ledger: ScreenTimeLedger?,
        val budget: BudgetUse?,
        val records: List<LiftRecord>,
        val clocks: Clocks,
        val zone: ZoneId,
    )

    /** [zone] is read once here and passed down; nothing below reads the system zone again. */
    fun snapshot(context: Context, policy: PolicyResponse?, overrideActive: Boolean = overrideActive(), zone: ZoneId = zone()): Snapshot {
        val clocks = clocks()
        val tp = KidModeEnforcer.timePolicyOf(policy)
            ?: return Snapshot(UNLOCKED, null, null, null, emptyList(), clocks, zone)
        val ledger = ledger(context, tp, clocks, zone)
        val records = liftRecords(context, tp.lifts, clocks)
        val budget = budgetUse(ledger, tp)
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(clocks.wallMs), zone)
        val lock = decideTimeLock(
            tp, at, overrideActive, activeRuleLifts(records, tp.lifts, clocks), budget, messagingApps(context),
            musicApps = com.kidslauncher.mdm.music.MUSIC_PACKAGES.toSet(),
        )
        return Snapshot(lock, tp, ledger, budget, records, clocks, zone)
    }

    /** Lock listeners (design r13 §1.4): handed each re-check's snapshot after the lock reason and key are written.
     * Main thread. */
    private val lockListeners = java.util.concurrent.CopyOnWriteArrayList<(Snapshot) -> Unit>()

    fun addLockListener(listener: (Snapshot) -> Unit) {
        lockListeners.addIfAbsent(listener)
    }

    fun removeLockListener(listener: (Snapshot) -> Unit) {
        lockListeners.remove(listener)
    }

    private fun notifyLockListeners(snap: Snapshot) {
        for (listener in lockListeners) {
            try {
                listener(snap)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Lock listener failed: ${e.javaClass.simpleName}")
            }
        }
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
    fun report(context: Context, policy: PolicyResponse?, lockReason: LockReason): TimeState =
        timeStateOf(snapshot(context, policy), lockReason)

    /** The `time_state` of [snap] - pure: its `zone` is the snapshot's own (design 34 P13). */
    fun timeStateOf(snap: Snapshot, lockReason: LockReason): TimeState {
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
            ledgerUnreadable = snap.ledger?.unreadable == true,
            zone = snap.zone.id,
        )
    }

    /** When the lock may change next: rule boundaries, midnight with a budget, lift expiries,
     * and the end of an open offline override or pause - always, also without a time policy
     * (design 24b S2: the window locks again then, one alarm for everything). */
    fun nextBoundary(context: Context): Instant? {
        val clocks = clocks()
        // Design r13 §2.3 (R13-8): an open request for more time ends at its stored instant - one more boundary, so
        // "Spurt" goes on time across a process death or a reboot.
        val windowEnds = (windowEnds(clocks) + listOfNotNull(TimeRequests.openUntilMs(context)))
            .filter { it > clocks.wallMs }
            .map { Instant.ofEpochMilli(it) }
        val policy = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy) ?: return windowEnds.minOrNull()
        val records = liftRecords(context, policy.lifts, clocks)
        return nextBoundary(policy, Instant.ofEpochMilli(clocks.wallMs), zone(), liftExpiries(records, clocks) + windowEnds)
    }

    /** The open override's and pause's ends: the earlier of the wall end and now plus the elapsed
     * time left ([windowEndMs]). */
    private fun windowEnds(clocks: Clocks): List<Long> {
        val mdm = LauncherPreferences.mdm()
        val override = WindowStart(mdm.offlineOverrideExpiresAt(), mdm.offlineOverrideElapsedStart(), mdm.offlineOverrideBoot())
            .takeIf { mdm.offlineOverrideActive() }
        val pause = WindowStart(mdm.restrictionsPausedUntil(), mdm.restrictionsPausedElapsedStart(), mdm.restrictionsPausedBoot())
            .takeIf { mdm.restrictionsPaused() }
        return listOfNotNull(
            override?.let { windowEndMs(it, OVERRIDE_DURATION_MS, clocks.wallMs, clocks.elapsedMs, clocks.bootCount) },
            pause?.let { windowEndMs(it, RESTRICTIONS_PAUSE_DURATION_MS, clocks.wallMs, clocks.elapsedMs, clocks.bootCount) },
        )
    }

    /**
     * Re-checks the lock from the cache (no network) and acts: re-applies enforcement when it
     * changed, shows [LockActivity] when a lock begins (not over a call in progress - it shows when
     * the kid is back on Home), re-arms the boundary alarm and the screen-time timer. Called on the
     * boundary alarm, time/zone changes, boot, screen on / user present, the budget running out
     * and every accepted policy. Then a parent-PIN window that ended locks again ([WindowRelock],
     * design 24b; [trigger] is the report's). Main thread.
     */
    fun recheck(context: Context, trigger: RelockTrigger = RelockTrigger.TIMER) {
        // First: fold the running screen-time stretch into the ledger, so the budget the lock
        // decision sees includes it (the budget timer lands here), and re-arm the timers.
        ScreenTimeTracker.update(context)
        try {
            // Design r13 §1.4 (R13-5): one snapshot per re-check, for the lock reason and the listeners.
            val snap = snapshot(context, currentPolicyDecision().policy)
            val changedTo = reevaluateLockReasonFromCache(context, snap)
            if (changedTo != null && changedTo != LockReason.NONE && !OngoingCalls.hasLiveCall) {
                // Design 32 step 2 (M5): not while the keyguard cover is locked (the screen-on's start
                // under the lock) - it would un-occlude it; at once without the cover.
                // When it runs later, only if a rule still locks and no call came (qa-32-s2-code L5).
                com.kidslauncher.mdm.lock.KeyguardCover.whenUnlocked(context, "the time-rule screen") {
                    if (LauncherPreferences.mdm().lockReason() != LockReason.NONE && !OngoingCalls.hasLiveCall) LockActivity.start(it)
                }
            }
            // Design r13 §2.3 (R13-3): the anchor's notice from the same snapshot, counting or not.
            try {
                com.kidslauncher.mdm.server.AnchorNotice.refresh(context, snap)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Notice refresh failed: ${e.javaClass.simpleName}")
            }
            // The glance's rule card (design r13 §1.4) before the missed calls' re-learn, so the panel's render that
            // `relearn` may fire already has the new card - the page is laid out once.
            notifyLockListeners(snap)
            // The lock's glance page shows what our missed-call notification shows: a rule's start or
            // end re-learns it (design 25, code QA Low 3; never posts anew, never clears).
            if (changedTo != null) com.kidslauncher.mdm.calls.MissedCallNotifier.relearn(context)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Lock re-check failed", e)
        }
        TimeRuleAlarm.schedule(context)
        // Never blocks: hands off to the relock worker.
        try {
            WindowRelock.check(context, trigger)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Relock check failed", e)
        }
    }
}
