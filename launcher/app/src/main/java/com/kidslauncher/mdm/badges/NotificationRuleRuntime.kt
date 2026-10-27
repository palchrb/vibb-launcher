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

    // The 2026-10-27 diagnostic: the kept notifications' counts (process lifetime, see KeepCounts),
    // the post-cancel checks in flight and the (package, channel) pairs whose refused cancel was
    // logged already - each line once per process.
    private val keeps = KeepCounts()
    private val postCancelChecks = PostCancelChecks()
    private val platformKeptLogged = LinkedHashSet<Pair<String, String?>>()
    private val postCancelToken = Any()

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
        val allowlist = enforced?.allowlist.orEmpty().toSet()
        val allowed = allowlist + messaging + lockUsableApps
        val own = ownPackageFamily(context.packageName)
        val next = NagPolicy(
            enabled = enforced?.notificationAutoCancel == true,
            managed = enforced?.allowlist != null && !overrideActive,
            allowed = allowed,
            essential = essential,
            own = own,
            allowlist = allowlist,
            messaging = messaging,
            lockUsable = lockUsableApps,
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
        // qa-nf-keep-log-code Q-2: the resolved essentials by name whenever the set changes (system package
        // names, our own info log), so an ESSENTIAL_PACKAGE line can be read against the set it came from.
        val essentialChanged = next.essential != policy?.essential
        policy = next
        if (changed) {
            val names = if (essentialChanged) ": ${next.essential.sorted().joinToString(", ")}" else ""
            Log.i(LOG_TAG, "Rule ${if (next.enabled && next.managed) "active" else "inactive"}: ${next.essential.size} essential packages$names")
        }
        if (next.enabled && next.managed) main.post { listener?.sweep() }
    }

    fun attach(service: BadgeListenerService) {
        listener = service
    }

    fun detach(service: BadgeListenerService) {
        if (listener === service) {
            listener = null
            // The checks hold nothing but a key; unbound, the answer would be "can't read" anyway.
            main.removeCallbacksAndMessages(postCancelToken)
            postCancelChecks.clear()
        }
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

    /**
     * The verdict was a Keep: logged by [keepLogDue]'s rule as `kept <package> / <channel>: <REASON>
     * (N so far)` - N counts this process's keeps of that (package, channel, reason). ALLOWED names
     * the set that allowed it ([allowedBy], from the [policy] the verdict read - never a second read
     * of the volatile, qa-nf-keep-log-code Q-5). Never the key, title or text.
     */
    fun kept(facts: NotificationFacts, reason: NagKeep, policy: NagPolicy?) {
        if (reason in NAG_KEEP_SILENT) return
        val label = if (reason == NagKeep.ALLOWED) "ALLOWED via ${allowedBy(policy, facts.packageName)}" else reason.name
        logKept(facts.packageName, facts.channelId, reason.name, label) { keepLogDue(reason, it) }
    }

    /** `applyRule` returned before any verdict: the facts couldn't be read ([exceptionName]). */
    fun keptFactsFailed(packageName: String, channelId: String?, exceptionName: String) =
        logKept(packageName, channelId, "FACTS_FAILED", "FACTS_FAILED $exceptionName", ::nagLogDue)

    /** A group summary the rule would cancel, kept for a child that stays (qa-11-code #6). */
    fun keptGroupChild(facts: NotificationFacts) =
        logKept(facts.packageName, facts.channelId, "GROUP_CHILD_KEPT", "GROUP_CHILD_KEPT", ::nagLogDue)

    /** A group summary the rule would cancel, kept because its children couldn't be read (the listener
     * unbound in between, or a RemoteException it caught; qa-nf-keep-log-code Q-1). */
    fun keptActiveUnreadable(facts: NotificationFacts) =
        logKept(facts.packageName, facts.channelId, "ACTIVE_UNREADABLE", "ACTIVE_UNREADABLE", ::nagLogDue)

    /** A cancel or snooze that threw (unbound in between, or the platform refused with an exception):
     * under the filter's one tag, so B6's `-s NotificationRule` shows it (qa-nf-keep-log-code Q-4). */
    fun removeFailed(facts: NotificationFacts, e: Exception) =
        Log.w(LOG_TAG, "couldn't remove ${facts.packageName} / ${facts.channelId ?: "-"}: ${e.javaClass.simpleName}")

    private fun logKept(packageName: String, channelId: String?, reason: String, label: String, due: (Int) -> Boolean) {
        val total = keeps.record(packageName, channelId, reason)
        if (due(total)) Log.i(LOG_TAG, "kept $packageName / ${channelId ?: "-"}: $label ($total so far)")
    }

    /**
     * After a cancel: [POST_CANCEL_CHECK_MS] later, on the main thread, the listener looks whether
     * [key] is still active ([platformKeptAfterCancel]: Android 15/16 can refuse a listener's cancel
     * - `FLAG_NO_DISMISS`, a lifetime-extended notification) and logs `platform kept <package> /
     * <channel>` once per (package, channel). At most [POST_CANCEL_CHECKS_MAX] checks wait at once;
     * the rest are skipped - counted, and said once when the burst's last check is done
     * (qa-nf-keep-log-code Q-6) -, and [detach] drops them all.
     */
    fun checkAfterCancel(service: BadgeListenerService, key: String, postTime: Long, facts: NotificationFacts) {
        if (!postCancelChecks.take()) return
        main.postDelayed({
            val skipped = postCancelChecks.done()
            if (skipped > 0) Log.i(LOG_TAG, "post-cancel checks skipped ($skipped)")
            val live = listener?.takeIf { it === service } ?: return@postDelayed
            if (!platformKeptAfterCancel(live.activePostTime(key), postTime)) return@postDelayed
            if (platformKeptLogged.add(facts.packageName to facts.channelId)) {
                while (platformKeptLogged.size > 64) platformKeptLogged.remove(platformKeptLogged.first())
                Log.i(LOG_TAG, "platform kept ${facts.packageName} / ${facts.channelId ?: "-"}")
            }
        }, postCancelToken, POST_CANCEL_CHECK_MS)
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
