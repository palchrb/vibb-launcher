package com.kidslauncher.mdm.badges

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.messagingAppPackages
import com.kidslauncher.mdm.lock.voipAllowedApps
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.dto.NotificationCancelEntry
import com.kidslauncher.mdm.server.dto.NotificationCancelsReport
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.ownPackageFamily
import com.kidslauncher.mdm.server.smsPackages

private const val LOG_TAG = "NotificationRule"

/**
 * Design r7 §1.1.1: what never rings over the lock as a VoIP app, whatever the allowlist says - the
 * SMS apps (Google Messages posts call-category notifications for RCS video calls), the essentials
 * (the fixed [ESSENTIAL_PACKAGES] and the resolved ones: the dialers - the system dialer's cellular
 * ring carries an FSI -, Telecom, the emergency dialer, the clock, the keyboards) and our own family.
 */
fun voipExcluded(defaultSms: String?, essential: Set<String>, own: Set<String>): Set<String> =
    smsPackages(defaultSms) + ESSENTIAL_PACKAGES + essential + own

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

    /**
     * Design r7 §1.1.1: the apps whose call-style ring may ring over the lock ([voipAllowedApps]),
     * computed by [refresh] with the [policy] (a throw earlier in `apply()` keeps the previous one);
     * `null` while apps are unmanaged or before the first [refresh] ([policy] `null` then).
     */
    @Volatile
    var voipAllowed: Set<String>? = null
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
        val defaultSms = CallSystem.defaultSmsPackage(context)
        val messaging = messagingAppPackages(rules, defaultSms)
        val allowed = enforced?.allowlist.orEmpty().toSet() + messaging + lockUsableApps
        val own = ownPackageFamily(context.packageName)
        val next = NagPolicy(
            enabled = enforced?.notificationAutoCancel == true,
            managed = enforced?.allowlist != null && !overrideActive,
            allowed = allowed,
            essential = essential,
            own = own,
        )
        // Design r7 §1.1.1: the apps that may ring over the lock, once per pass (the listener's main
        // thread only reads it) - the effective allowlist, never the raw one.
        voipAllowed = voipAllowedApps(
            allowlist = enforced?.allowlist,
            relockBlocked = WindowRelock.blockedPackages(context),
            messagingApps = messaging,
            lockUsableApps = lockUsableApps,
            excluded = voipExcluded(defaultSms, essential, own),
        )
        val changed = next != policy
        policy = next
        if (changed) Log.i(LOG_TAG, "Rule ${if (next.enabled && next.managed) "active" else "inactive"}: ${next.essential.size} essential packages")
        if (next.enabled && next.managed) main.post { listener?.sweep() }
    }

    fun attach(service: BadgeListenerService) {
        listener = service
    }

    /** The connected listener (design 59c prototype, debug only: its `cancel` variant). */
    internal fun connectedListener(): BadgeListenerService? = listener

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
