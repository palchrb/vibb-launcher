package com.kidslauncher.mdm.badges

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.messagingAppPackages
import com.kidslauncher.mdm.server.dto.NotificationCancelEntry
import com.kidslauncher.mdm.server.dto.NotificationCancelsReport
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.ownPackageFamily

private const val LOG_TAG = "NotificationRule"

/**
 * The notification auto-cancel rule's state (handy step 11, pure rule in NotificationRule.kt):
 * the [NagPolicy] resolved by every `AppEnforcer.apply()` (background thread - the listener's
 * callbacks on the main thread only read it), the re-post budget and the counts for the status
 * report. `null` until the first apply in this process: nothing is cancelled then.
 */
object NotificationRuleRuntime {
    @Volatile
    var policy: NagPolicy? = null
        private set

    private val budget = RepostBudget()
    private val counts = NagCounts()
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var listener: BadgeListenerService? = null

    /**
     * From `AppEnforcer.apply` with the policy it enforced. The switch comes only from a real
     * policy (`LastEnforcedPlan` doesn't carry it), so with an unknown policy nothing is
     * cancelled; an override or pause counts as unmanaged (the standing rule).
     */
    fun refresh(
        context: Context,
        enforced: PolicyResponse?,
        overrideActive: Boolean,
        lockUsableApps: Set<String>,
        callState: CallPolicyState,
        essential: Set<String>,
    ) {
        val rules = (callState as? CallPolicyState.Managed)?.rules
        val allowed = enforced?.allowlist.orEmpty().toSet() +
            messagingAppPackages(rules, CallSystem.defaultSmsPackage(context)) + lockUsableApps
        val next = NagPolicy(
            enabled = enforced?.notificationAutoCancel == true,
            managed = enforced?.allowlist != null && !overrideActive,
            allowed = allowed,
            essential = essential,
            own = ownPackageFamily(context.packageName),
        )
        val changed = next != policy
        policy = next
        if (changed) Log.i(LOG_TAG, "Rule ${if (next.enabled && next.managed) "active" else "inactive"}: ${next.essential.size} essential packages")
        if (next.enabled && next.managed) main.post { listener?.sweep() }
    }

    fun attach(service: BadgeListenerService) {
        listener = service
    }

    fun detach(service: BadgeListenerService) {
        if (listener === service) listener = null
    }

    /** Cancel or snooze (the budget decides) - and count it. Only package and channel are kept. */
    fun act(facts: NotificationFacts): NagAction {
        val action = budget.action(facts.packageName, facts.channelId, SystemClock.elapsedRealtime())
        counts.record(facts.packageName, facts.channelId, action)
        val total = counts.total(facts.packageName, facts.channelId)
        if (nagLogDue(total)) {
            Log.i(LOG_TAG, "${action.name.lowercase()} ${facts.packageName} / ${facts.channelId ?: "-"} ($total since the last report)")
        }
        return action
    }

    /** For the status report: whether the rule is active and the counts since the last report. */
    fun report(): NotificationCancelsReport {
        val current = policy
        val (entries, dropped) = counts.take()
        return NotificationCancelsReport(
            active = current != null && current.enabled && current.managed,
            entries = entries.map { NotificationCancelEntry(it.packageName, it.channelId, it.cancelled, it.snoozed) },
            dropped = dropped,
        )
    }
}
