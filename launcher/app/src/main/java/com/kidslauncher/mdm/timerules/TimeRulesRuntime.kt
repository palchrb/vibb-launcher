package com.kidslauncher.mdm.timerules

import android.content.Context
import android.content.SharedPreferences
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

    /** The trusted clocks ([com.kidslauncher.mdm.server.TrustedClock]): every decision here is on them. */
    fun clocks(): Clocks = com.kidslauncher.mdm.server.TrustedClock.clocks()

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

    /** Whether the phone's storage is full as its writes see it ([com.kidslauncher.mdm.server.StorageWrites]): a failed
     * ledger write counts. The count itself keeps enforcing while the process runs - SharedPreferences keeps the new
     * value in memory even when the write to disk fails -; a restart goes back to the last saved ledger, losing the
     * time used since storage filled (the owner's choice, 2026-11-25). */
    fun storageFull(): Boolean = com.kidslauncher.mdm.server.StorageWrites.full()

    private fun saveLedger(context: Context, before: ScreenTimeLedger, after: ScreenTimeLedger) {
        if (before == after) return
        val ok = prefs(context).edit().putString(LEDGER_KEY, ServerJson.encodeToString(ScreenTimeLedger.serializer(), after)).commit()
        if (com.kidslauncher.mdm.server.StorageWrites.record(com.kidslauncher.mdm.server.StorageWrites.Kind.LEDGER, ok)) {
            Log.w(LOG_TAG, "Couldn't save the screen-time ledger: storage full?")
        }
    }

    /** The ledger moved to now in [zone], with new budget lifts applied - saved when it changed. */
    @Synchronized
    fun ledger(context: Context, policy: TimePolicy?, clocks: Clocks = clocks(), zone: ZoneId = zone()): ScreenTimeLedger {
        val before = loadLedger(context, policy?.lifts.orEmpty(), clocks, zone)
        var after = observe(before, clocks, zone).ledger
        if (policy != null) after = applyGroupLifts(applyBudgetLifts(after, policy.lifts), policy.lifts)
        saveLedger(context, before, after)
        return after
    }

    /**
     * Design 139 §4.1: a fold's result into today's ledger - its groups' minutes and the new folded point, saved with
     * them - when the ledger's point is still the one the read started [from]: a read that raced a cleared or newer
     * point is dropped, so `from` and `carry` stay as they were. True when applied. An unreadable ledger takes the
     * point too (its groups stay closed, D6), so the next day starts with the state known.
     */
    @Synchronized
    fun applyGroupFold(context: Context, policy: TimePolicy, plan: ReadPlan, out: FoldOut, count: Boolean, from: FoldPoint?): Boolean {
        val clocks = clocks()
        val zone = zone()
        val before = loadLedger(context, policy.lifts, clocks, zone)
        val observed = observe(before, clocks, zone).ledger
        if (observed.foldPoint != from) return false
        val after = applyFold(observed, plan, out, clocks, zone, BootClock.bootCount(), count)
        saveLedger(context, before, after)
        return true
    }

    /** Design 139 §4.1: the folded point, `null` when there is none (no group, a new or unreadable ledger). */
    @Synchronized
    fun foldPoint(context: Context): FoldPoint? = try {
        prefs(context).getString(LEDGER_KEY, null)?.let { ServerJson.decodeFromString(ScreenTimeLedger.serializer(), it) }?.foldPoint
    } catch (e: Exception) {
        null
    }

    /** The policy lost its last group: the next group's first fold reads the lookback (§4.1 "The first fold"). */
    @Synchronized
    fun clearFoldPoint(context: Context) {
        val clocks = clocks()
        val zone = zone()
        val before = loadLedger(context, emptyList(), clocks, zone)
        if (before.foldPoint == null || before.unreadable) return
        saveLedger(context, before, before.copy(foldPoint = null))
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
        /** Design 98 §4.4: the policy's lift ids a parent-code pause on this phone stands behind (`lifts_active`). */
        val phoneLiftIds: List<Long> = emptyList(),
        /** The ledger can't be saved ([storageFull]): `time_state.storage_full`. */
        val storageFull: Boolean = false,
        /** Design 139: each app group today ([groupStates]). */
        val groups: List<GroupState> = emptyList(),
        /** Design 139: the usage access as read for this snapshot (`null` unreadable). */
        val usageAccess: Boolean? = null,
    )

    /** [zone] is read once here and passed down; nothing below reads the system zone again. */
    fun snapshot(context: Context, policy: PolicyResponse?, overrideActive: Boolean = overrideActive(), zone: ZoneId = zone()): Snapshot {
        val clocks = clocks()
        // The next boot carries this time over (at most once a minute).
        com.kidslauncher.mdm.server.TrustedClock.checkpoint()
        // Design 93 §9.1: a rule turned on now is gone from its `until_ms`.
        val tp = KidModeEnforcer.timePolicyOf(policy)?.inForceAt(clocks.wallMs)
            ?: return Snapshot(UNLOCKED, null, null, null, emptyList(), clocks, zone, storageFull = storageFull())
        val ledger = ledger(context, tp, clocks, zone)
        val records = liftRecords(context, tp.lifts, clocks)
        val budget = budgetUse(ledger, tp)
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(clocks.wallMs), zone)
        val decided = decideTimeLock(
            tp, at, overrideActive, ruleLifts(context, records, tp.lifts, clocks), budget, messagingApps(context),
            // Security review S-L8: only the music app with our signer is exempt from the budget.
            musicApps = com.kidslauncher.mdm.music.MusicTrust.installed(context).toSet(),
        )
        // Design 139 §4.2: the app groups today - lifted by the pause and the override (D9), their apps closed
        // otherwise, but the live VoIP call's own package (D7); out of the lock's usable apps (D8).
        val access = if (tp.groups.isEmpty()) null else UsageAccess.granted(context)
        val groups = groupStates(tp, ledger, access, AppGroupUsage.readFailed, overrideActive)
        val closed = groupSuspends(groupClosedApps(groups), com.kidslauncher.mdm.lock.VoipCalls.pinnedPackage)
        val lock = if (closed.isEmpty()) decided else decided.copy(groupClosed = closed, usableApps = decided.usableApps - closed, usableBeforeGroups = decided.usableApps)
        return Snapshot(
            lock, tp, ledger, budget, records, clocks, zone, PhonePauses.activeLiftIds(context, tp.lifts, clocks), storageFull(),
            groups = groups, usageAccess = access,
        )
    }

    /** The PWA's lifts through their records, and design 98's parent-code pauses through their own entries. */
    fun ruleLifts(context: Context, records: List<LiftRecord>, lifts: List<Lift>, clocks: Clocks): RuleLifts {
        val fromPolicy = activeRuleLifts(records, lifts, clocks)
        val phone = try {
            PhonePauses.liftedRuleIds(context, clocks)
        } catch (e: Exception) {
            emptySet()
        }
        return if (phone.isEmpty()) fromPolicy else fromPolicy.copy(ruleIds = fromPolicy.ruleIds + phone)
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

    /** The lock in force now for the cached policy, the override and the pause counted; `null` before [init]. */
    fun lockNow(): TimeLock? {
        val context = appContext ?: return null
        return snapshot(context, currentPolicyDecision().policy).lock
    }

    /** The call path's live answer (TimeRulesStore.liveDecider): a no-calls rule is in force,
     * counting lifts and the override PIN/pause. */
    fun callsBlockedNow(): Boolean {
        val context = appContext ?: return callsBlockedAt(
            TimeRulesStore.policy?.inForceAt(com.kidslauncher.mdm.server.TrustedClock.nowMs()),
            com.kidslauncher.mdm.server.TrustedClock.localNow(), false, RuleLifts(),
        )
        val clocks = clocks()
        val tp = TimeRulesStore.policy?.inForceAt(clocks.wallMs) ?: return false
        val records = liftRecords(context, tp.lifts, clocks)
        val at = LocalDateTime.ofInstant(Instant.ofEpochMilli(clocks.wallMs), zone())
        return callsBlockedAt(tp, at, overrideActive(), ruleLifts(context, records, tp.lifts, clocks))
    }

    /** The status report's `time_state`. */
    fun report(context: Context, policy: PolicyResponse?, lockReason: LockReason): TimeState =
        timeStateOf(snapshot(context, policy), lockReason, com.kidslauncher.mdm.server.TrustedClock.report())

    /** The `time_state` of [snap] - pure: its `zone` is the snapshot's own (design 34 P13); [clock] the decisions'
     * clock's three fields. */
    fun timeStateOf(snap: Snapshot, lockReason: LockReason, clock: com.kidslauncher.mdm.server.ClockReport? = null): TimeState {
        // Design 93 §9.2: the rule the card names (a turned-on rule with a certain end ahead of the kind order), so the
        // PWA's badge names the same rule and end.
        val top = try {
            com.kidslauncher.mdm.lock.cardPick(snap).rule
        } catch (e: Exception) {
            snap.lock.rules.firstOrNull()
        }
        return TimeState(
            day = snap.ledger?.day,
            usedMinutes = ((snap.ledger?.usedMs ?: 0) / 60_000L).toInt(),
            budgetMinutes = snap.budget?.effectiveMinutes,
            extraMinutes = snap.ledger?.extraMinutes ?: 0,
            activeRuleId = top?.id,
            activeRuleName = top?.name,
            callsBlocked = snap.lock.callsBlocked,
            lockReason = lockReason.name,
            liftsActive = snap.ledger?.let { activeLiftIds(snap.records, snap.policy?.lifts.orEmpty(), it, snap.clocks) + snap.phoneLiftIds }.orEmpty(),
            ledgerUnreadable = snap.ledger?.unreadable == true,
            zone = snap.zone.id,
            clockSkewMs = clock?.skewMs,
            clockTrust = clock?.trust?.wire,
            unanchoredMs = clock?.unanchoredMs,
            storageFull = snap.storageFull,
            usageAccess = snap.usageAccess,
            groups = snap.groups.map {
                com.kidslauncher.mdm.server.dto.GroupReport(
                    it.id, (it.usedMs / 60_000L).toInt(), it.extraMinutes, it.status.closes,
                )
            },
        )
    }

    /** When the lock may change next: rule boundaries, midnight with a budget, lift expiries,
     * and the end of an open offline override or pause - always, also without a time policy
     * (design 24b S2: the window locks again then, one alarm for everything). */
    fun nextBoundary(context: Context): Instant? {
        val clocks = clocks()
        val windowEnds = windowEnds(clocks)
            .filter { it > clocks.wallMs }
            .map { Instant.ofEpochMilli(it) }
        val all = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy) ?: return windowEnds.minOrNull()
        val policy = all.inForceAt(clocks.wallMs)
        val records = liftRecords(context, policy.lifts, clocks)
        // Design 100 (QA R2-L3, where r13's 30-minute instant was): the request's note changes by itself - "sendes når
        // telefonen er på nett" 15 s after the tap, an answer's 30 minutes in the shade over - one more boundary, so the
        // shade and the time-rule screen follow on time across a process death or a reboot.
        val noteChange = try {
            TimeRequests.noteChangeMs(context, ledger(context, policy, clocks, zone()).day, policy, clocks.wallMs)
        } catch (e: Exception) {
            null
        }
        // Design 93 §9.1: a turned-on rule's absolute end is a boundary of its own.
        return nextBoundary(
            policy, Instant.ofEpochMilli(clocks.wallMs), zone(),
            liftExpiries(records, clocks) + windowEnds + untilInstants(policy, clocks.wallMs) +
                PhonePauses.expiries(context, clocks) + listOfNotNull(noteChange?.let { Instant.ofEpochMilli(it) }),
        )
    }

    /**
     * Design 54 R2/R3: the music app [musicPkg]'s next suspension by a time rule, epoch seconds, 0 = none
     * ([com.kidslauncher.mdm.music.musicSuspendEdgeS]) - the managed config's `suspend_at_s`. The inputs are
     * [nextBoundary]'s: the policy in force, the PWA's rule lifts and the parent-code pauses with their ends, the open
     * override's or pause's end. Any thread. Null while the music app is suspended now or when something can't be read:
     * the written value stays (qa-54a2-code M1).
     */
    fun musicSuspendAtS(
        context: Context,
        policy: PolicyResponse? = currentPolicyDecision().policy,
        musicPkg: String? = com.kidslauncher.mdm.music.MusicStore.installedPackage(context),
    ): Int? {
        musicPkg ?: return 0
        return try {
            val clocks = clocks()
            val tp = KidModeEnforcer.timePolicyOf(policy)?.inForceAt(clocks.wallMs) ?: return 0
            val records = liftRecords(context, tp.lifts, clocks)
            val byId = records.associateBy { it.id }
            // The PWA's lifts through their records, each with its own end.
            val fromPolicy = tp.lifts
                .filter { it.target == TARGET_RULE && it.phoneKey == null }
                .mapNotNull { lift ->
                    byId[lift.id]?.takeIf { liftRecordActive(it, clocks) }
                        ?.let { com.kidslauncher.mdm.music.TimedLift(lift.ruleId, liftExpiry(it, clocks).toEpochMilli()) }
                }
            // Design 98's parent-code pauses: each rule an entry still lifts, until its own end.
            val pauses = try {
                val lifting = PhonePauses.lifting(context, clocks)
                PhonePauses.entries(context).flatMap { e ->
                    val lifted = lifting[e.key].orEmpty()
                    e.rules.filter { it.id in lifted }.zip(phonePauseExpiries(e, lifted, clocks)) { r, end ->
                        com.kidslauncher.mdm.music.TimedLift(r.id, end.toEpochMilli())
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
            val timed = fromPolicy + pauses
            val overrideEndMs = if (overrideActive()) windowEnds(clocks).filter { it > clocks.wallMs }.maxOrNull() ?: 0L else 0L
            com.kidslauncher.mdm.music.musicSuspendEdgeS(
                tp, clocks.wallMs, zone(), musicPkg,
                liftsAt = { com.kidslauncher.mdm.music.ruleLiftsAt(timed, it) },
                overrideEndMs = overrideEndMs,
                extra = timed.map { Instant.ofEpochMilli(it.untilMs) },
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't work out the music app's next suspension: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The open override's and pause's ends: the earlier of the wall end and now plus the elapsed
     * time left ([windowEndMs]). These windows are started and checked on the wall clock and bounded by elapsed
     * realtime ([BootClock]); their ends are moved into the trusted clock of [clocks] for the alarm. */
    private fun windowEnds(clocks: Clocks): List<Long> {
        val wall = System.currentTimeMillis()
        val mdm = LauncherPreferences.mdm()
        val override = WindowStart(mdm.offlineOverrideExpiresAt(), mdm.offlineOverrideElapsedStart(), mdm.offlineOverrideBoot())
            .takeIf { mdm.offlineOverrideActive() }
        val pause = WindowStart(mdm.restrictionsPausedUntil(), mdm.restrictionsPausedElapsedStart(), mdm.restrictionsPausedBoot())
            .takeIf { mdm.restrictionsPaused() }
        return listOfNotNull(
            override?.let { windowEndMs(it, OVERRIDE_DURATION_MS, wall, clocks.elapsedMs, clocks.bootCount) },
            pause?.let { windowEndMs(it, com.kidslauncher.mdm.server.pauseDurationMs(mdm.restrictionsPausedHold()), wall, clocks.elapsedMs, clocks.bootCount) },
        ).map { it - wall + clocks.wallMs }
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
            val policy = currentPolicyDecision().policy
            val snap = snapshot(context, policy)
            val changedTo = reevaluateLockReasonFromCache(context, snap)
            // A silent rule's Do Not Disturb follows the same lock (server 0121), at every boundary and screen-on.
            ZenRuleRuntime.follow(context, snap.lock, snap.clocks.elapsedMs)
            if (changedTo != null && changedTo != LockReason.NONE && !OngoingCalls.hasLiveCall) {
                LockActivity.start(context)
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
            // The user, 2026-11-20: what a rule without calls kept from the shade comes once, quietly, when it allows calls.
            RuleWaitingNotices.release(context)
            // Design 54 R3: the music app's `suspend_at_s` again at every boundary and screen-on (written only when it
            // differs, on IO).
            com.kidslauncher.mdm.music.MusicSync.restrictionsChanged(context, policy)
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
