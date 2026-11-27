package com.kidslauncher.mdm.timerules

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.KidModeEnforcer
import com.kidslauncher.mdm.server.currentPolicyDecision

private const val LOG_TAG = "ScreenTimeTracker"

/**
 * Counts screen time (handy step 6): the screen is on and unlocked, unless one of our free screens
 * (lock screen, phone book, our in-call screen, Settings - [FREE_SCREENS]) is in front and not
 * sharing the screen ([screenTimeCounts]). Calls elsewhere and Home count (QA step 6 #1, #2).
 * Event-driven, never polling while the screen is off: [update] runs on screen on/off and user
 * present (CommandListenerService), on our activities' resume/pause and on every lock re-check. While counting, a checkpoint saves usage every [CHECKPOINT_MS] and a
 * one-shot timer fires when the budget runs out (then [TimeRulesRuntime.recheck] locks). Main
 * thread only.
 */
object ScreenTimeTracker {
    private val handler = Handler(Looper.getMainLooper())
    /** Our free screens currently resumed (see [FREE_SCREENS]). */
    private val resumedFree = mutableSetOf<Activity>()
    private var countingSinceElapsed: Long? = null
    private var appContext: Context? = null

    private val checkpoint = object : Runnable {
        override fun run() {
            appContext?.let { update(it) }
        }
    }

    private val budgetRunsOut = Runnable { appContext?.let { TimeRulesRuntime.recheck(it) } }

    /** Design 139 §4.1 "The timer": a group's time runs out - fold again; [afterFold] re-checks when one closed. */
    private val groupRunsOut = Runnable { appContext?.let { AppGroupUsage.fold(it, live = true) } }

    /** Design 139 §4.1: the one fold outside the gate, [SETTLE_MS] + 0.5 s after the screen goes off, so the span up to
     * it is in the ledger while the screen is dark. */
    private val screenOffFold = Runnable { appContext?.let { AppGroupUsage.fold(it, live = false) } }

    /** The group gate at the last [update] (screen interactive, keyguard down). */
    private var groupGateWas = false

    /** The policy had a group at the last [update] (`null` before the first). */
    private var groupsWere: Boolean? = null

    /** The closed groups' apps at the last fold: a change re-checks (the plan, the cut). */
    private var groupsClosedWas: Set<String>? = null

    fun init(app: Application) {
        appContext = app.applicationContext
        AppGroupUsage.onFolded = { afterFold(it) }
        UsageAccess.watch(app)
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                if (activity.javaClass.name in FREE_SCREENS) resumedFree += activity
                update(activity.applicationContext)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedFree -= activity
                update(activity.applicationContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                resumedFree -= activity
            }
        })
    }

    private fun appInUse(context: Context): Boolean = screenTimeCounts(
        interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true,
        keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
        freeScreenInFront = resumedFree.isNotEmpty(),
        freeScreenSharesScreen = resumedFree.any { it.isInMultiWindowMode || it.isInPictureInPictureMode },
        pinLocked = com.kidslauncher.mdm.lock.PinLockRuntime.chromeLocked,
        voipExempt = com.kidslauncher.mdm.lock.VoipCalls.exempt,
        musicListening = musicListening(
            com.kidslauncher.mdm.music.MusicForeground.inFront,
            com.kidslauncher.mdm.music.MusicNowPlaying.playing(context),
        ),
        setupHold = com.kidslauncher.mdm.server.RestrictionsPause.holdActive(),
    )

    /** Design 139 §4.1 "When it runs": the screen interactive and the keyguard down - its own gate, before
     * [appInUse]'s, since our free screens stop screen time, not the groups'. */
    private fun groupGate(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true &&
            context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == false

    private fun hasGroups(): Boolean =
        KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy)?.groups?.isNotEmpty() == true

    /** Design 139: after each fold (main thread) - a group closed or opened re-checks the lock and the plan; otherwise
     * the group timer is armed again under the gate, and the anchor's notice follows. */
    private fun afterFold(context: Context) {
        handler.removeCallbacks(groupRunsOut)
        try {
            val policy = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy)?.takeIf { it.groups.isNotEmpty() }
            val states = policy?.let {
                groupStates(it, TimeRulesRuntime.ledger(context, it), UsageAccess.granted(context), AppGroupUsage.readFailed,
                    com.kidslauncher.mdm.server.OfflineOverride.isActive() || com.kidslauncher.mdm.server.RestrictionsPause.isActive(),
                    TimeRulesRuntime.musicApps(context))
            }.orEmpty()
            // As the snapshot puts it on the lock: the live VoIP call's own app held back (D7).
            val closed = groupSuspends(groupClosedApps(states), com.kidslauncher.mdm.lock.VoipCalls.pinnedPackage)
            val recheck = groupRecheckNeeded(closed, groupsClosedWas, com.kidslauncher.mdm.server.AppEnforcer.lastGroupClosed)
            groupsClosedWas = closed
            if (recheck) {
                TimeRulesRuntime.recheck(context)
                return
            }
            if (!groupGate(context) || com.kidslauncher.mdm.server.RestrictionsPause.holdActive()) {
                // Code QA L6: no warning in the title outside the gate.
                com.kidslauncher.mdm.server.AnchorNotice.refreshGroups(context, emptyList(), emptySet())
                return
            }
            groupRunsOutDelayMs(states)?.let { handler.postDelayed(groupRunsOut, it) }
            try {
                com.kidslauncher.mdm.server.AnchorNotice.refreshGroups(context, states, AppGroupUsage.onScreen(context))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Group notice refresh failed: ${e.javaClass.simpleName}")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "After the group fold: ${e.javaClass.simpleName}")
        }
    }

    /** Folds the running stretch into the ledger and decides whether to keep counting. */
    fun update(context: Context) {
        appContext = context.applicationContext
        handler.removeCallbacks(checkpoint)
        handler.removeCallbacks(budgetRunsOut)
        try {
            val now = SystemClock.elapsedRealtime()
            countingSinceElapsed?.let { TimeRulesRuntime.accrueScreenTime(context, it) }
            countingSinceElapsed = null
            // Design 139 §4.1: the groups' fold, under its own gate; at the screen off one last fold a moment later.
            val groups = hasGroups()
            val gate = groups && groupGate(context)
            if (gate) {
                AppGroupUsage.fold(context, live = true)
            } else {
                handler.removeCallbacks(groupRunsOut)
                if (groupGateWas && groups) handler.postDelayed(screenOffFold, SETTLE_MS + 500L)
                // The policy lost its last group (or a new process): the point goes, once.
                if (!groups && groupsWere != false) {
                    AppGroupUsage.fold(context, live = false)
                    com.kidslauncher.mdm.server.AnchorNotice.refreshGroups(context, emptyList(), emptySet())
                }
            }
            groupGateWas = gate
            groupsWere = groups
            if (!appInUse(context)) {
                // The checkpoint keeps folding for the groups while screen time doesn't count (our free screens).
                if (gate) handler.postDelayed(checkpoint, CHECKPOINT_MS)
                return
            }
            countingSinceElapsed = now
            val policy = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy)
            if (policy == null) {
                handler.postDelayed(checkpoint, CHECKPOINT_MS)
                return
            }
            val ledger = TimeRulesRuntime.ledger(context, policy)
            val budget = budgetUse(ledger, policy)
            val remaining = budget?.remainingMs
            // Design r13 §2.3: while a budget counts down, the checkpoint lands on the next minute flip, and the anchor's
            // notice shows the new minute (the refresh posts only a changed notice) - from this budget, no snapshot of
            // its own (qa-r13-rule-card-code Q-5: one snapshot per re-check).
            handler.postDelayed(checkpoint, checkpointDelayMs(remaining))
            if (remaining == null) return
            try {
                com.kidslauncher.mdm.server.AnchorNotice.refresh(context, budget, policy, ledger.day, com.kidslauncher.mdm.server.TrustedClock.nowMs())
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Notice refresh failed: ${e.javaClass.simpleName}")
            }
            // Already used up: the lock is (being) applied by the re-check that got us here.
            if (remaining > 0) handler.postDelayed(budgetRunsOut, remaining + 1_000L)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Screen-time update failed", e)
        }
    }
}
