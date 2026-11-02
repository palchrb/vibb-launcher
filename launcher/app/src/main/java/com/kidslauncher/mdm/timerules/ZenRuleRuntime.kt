package com.kidslauncher.mdm.timerules

import android.app.AutomaticZenRule
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.Condition
import android.service.notification.ZenPolicy
import android.util.Log
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.managed

private const val LOG_TAG = "ZenRuleRuntime"

/** After a status change, how long to wait before looking (our own off-and-on sends two broadcasts). */
private const val REASSERT_DELAY_MS = 1_500L

/** At most one off-and-on per this long; a later one waits for it, never dropped. */
private const val REASSERT_SPACING_MS = 10_000L

/**
 * A silent time rule's Do Not Disturb (the user, 2026-11-02; server migration 0121; the pure rules are
 * [ruleSilenceFor]): one `AutomaticZenRule` of our own, with a priority-only [ZenPolicy], switched on and off with
 * `setAutomaticZenRuleState`. Switching it off hands Android back whatever the kid had - their own Do Not Disturb, a
 * mode, nothing - so nothing is ever saved or restored here, and a crash or an update can't leave the phone silent:
 * the next re-check, at the latest the process start's, puts the rule where the time rules say.
 *
 * The device owner has Do Not Disturb access (`NotificationManagerService.checkPolicyAccess`, design 18's research);
 * without it (not the device owner, a debug build) this does nothing. On Android 15+ the rule is `TYPE_MANAGED` (a
 * device owner's, not fully editable by the phone's user).
 *
 * Followed from where the time lock is decided: [TimeRulesRuntime.recheck] (the boundary alarm, screen on, boot, every
 * accepted policy, process start) and `AppEnforcer.apply`. Lifted while Find My Device rings ([holdForRing]); put back
 * when the kid switches it off from Quick Settings during the rule ([silenceNeedsReassert]); removed when the app stops
 * being device owner ([release]).
 */
object ZenRuleRuntime {
    /** What the time lock asks for (the last [follow]). */
    @Volatile
    private var wanted: RuleSilence = NOT_SILENT

    /** Find My Device's ring is playing. */
    @Volatile
    private var ringHeld = false

    /** What was last put in force; `null` = not known (a new process, a failed call), so the next apply looks. */
    @Volatile
    private var applied: RuleSilence? = null

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appContext: Context? = null

    private val reassert = Runnable { appContext?.let { reassertIfNeeded(it) } }

    /** Elapsed realtime of the last off-and-on ([reassertIfNeeded]), so a phone whose rule won't take never loops. */
    @Volatile
    private var lastReassertMs = Long.MIN_VALUE / 2

    /** Listens for the rule's status and Do Not Disturb changes (the kid's Quick Settings tile) while the process lives.
     * Not exported: only the system sends them. */
    fun init(context: Context) {
        if (appContext != null) return
        val app = context.applicationContext
        appContext = app
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        }
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    main.removeCallbacks(reassert)
                    main.postDelayed(reassert, REASSERT_DELAY_MS)
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't listen for Do Not Disturb changes: ${e.javaClass.simpleName}")
        }
    }

    /** The time lock in force now: our rule follows it. Never throws. */
    fun follow(context: Context, lock: TimeLock) {
        wanted = ruleSilenceFor(lock, callsScreened = CallPolicyStore.state.managed)
        apply(context.applicationContext, force = false)
    }

    /** Find My Device's ring starts ([held]) or ends: its alarm-stream ring must be heard, so our rule is off for it. */
    fun holdForRing(context: Context, held: Boolean) {
        if (ringHeld == held) return
        ringHeld = held
        apply(context.applicationContext, force = false)
    }

    /** Our rule is on and lets calls ring - the VoIP ring's question on Android < 16, where the notification policy it
     * reads is the manual one and doesn't fold our rule in. */
    fun letsCallsRing(): Boolean = applied?.let { it.on && it.calls } == true

    private fun silenceUri(context: Context): Uri =
        Uri.Builder().scheme(Condition.SCHEME).authority(context.packageName).appendPath("rule-silence").build()

    private fun isDeviceOwner(context: Context): Boolean = try {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /** Our rules (Android returns only the caller's). */
    private fun ours(nm: NotificationManager, uri: Uri): Map<String, AutomaticZenRule> =
        nm.automaticZenRules.orEmpty().filterValues { it.conditionId == uri }

    @Synchronized
    private fun apply(context: Context, force: Boolean) {
        val target = silenceInForce(wanted, ringHeld)
        if (!force && target == applied) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isDeviceOwner(context) || !nm.isNotificationPolicyAccessGranted) {
            if (target.on) Log.w(LOG_TAG, "A silent rule is in force, but there is no Do Not Disturb access")
            applied = null
            return
        }
        try {
            val uri = silenceUri(context)
            val existing = ours(nm, uri)
            // A second copy (a lost add's retry) goes; the first one stays.
            existing.keys.drop(1).forEach { runCatching { nm.removeAutomaticZenRule(it) } }
            val id = existing.keys.firstOrNull()
            if (!target.on) {
                if (id != null) {
                    nm.setAutomaticZenRuleState(id, condition(context, uri, on = false))
                    Log.i(LOG_TAG, "Silence off")
                }
                applied = target
                return
            }
            val ruleId = when {
                id == null -> addRule(nm, context, uri, target)
                else -> {
                    val current = existing.getValue(id)
                    // Keeps the type it was added with (a plain one where Android refused the managed type).
                    val managed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
                        current.type == AutomaticZenRule.TYPE_MANAGED
                    val rule = buildRule(context, uri, target, managedType = managed)
                    if (!current.isEnabled || current.zenPolicy != rule.zenPolicy || current.name != rule.name) {
                        nm.updateAutomaticZenRule(id, rule)
                    }
                    id
                }
            }
            if (force) nm.setAutomaticZenRuleState(ruleId, condition(context, uri, on = false))
            nm.setAutomaticZenRuleState(ruleId, condition(context, uri, on = true))
            applied = target
            Log.i(LOG_TAG, "Silence on: alarms=${target.alarms} media=${target.media} calls=${target.calls}")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the silent rule's Do Not Disturb: ${e.javaClass.simpleName}")
            applied = null
        }
    }

    /** Adds the rule: `TYPE_MANAGED` on Android 15+, plain if this Android refuses that type. */
    private fun addRule(nm: NotificationManager, context: Context, uri: Uri, target: RuleSilence): String =
        try {
            nm.addAutomaticZenRule(buildRule(context, uri, target))
        } catch (e: IllegalArgumentException) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) throw e
            Log.w(LOG_TAG, "The managed rule type was refused; adding a plain rule")
            nm.addAutomaticZenRule(buildRule(context, uri, target, managedType = false))
        }

    /** After a status change: the kid switched it off (Quick Settings), removed or disabled it - put it back, off and
     * on again (a rule the user switched off stays off for a mere "on" until it has been off). */
    private fun reassertIfNeeded(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val inForce = silenceInForce(wanted, ringHeld)
        val all = try {
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (e: Exception) {
            false
        }
        val gone = inForce.on && try {
            ours(nm, silenceUri(context)).values.none { it.isEnabled }
        } catch (e: Exception) {
            false
        }
        if (silenceNeedsReassert(inForce, all) || gone) {
            val now = android.os.SystemClock.elapsedRealtime()
            val wait = lastReassertMs + REASSERT_SPACING_MS - now
            if (wait > 0) {
                main.removeCallbacks(reassert)
                main.postDelayed(reassert, wait)
                return
            }
            lastReassertMs = now
            Log.i(LOG_TAG, "The silent rule was switched off during a silent time rule - on again")
            apply(context, force = true)
        }
    }

    /** The app stops being device owner: our rule goes (Android restores the phone's own state). */
    fun release(context: Context) {
        val app = context.applicationContext
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            ours(nm, silenceUri(app)).keys.forEach { nm.removeAutomaticZenRule(it) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't remove the silent rule: ${e.javaClass.simpleName}")
        }
        applied = null
    }

    private fun condition(context: Context, uri: Uri, on: Boolean): Condition {
        val state = if (on) Condition.STATE_TRUE else Condition.STATE_FALSE
        val summary = context.getString(R.string.rule_silence_name)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            Condition(uri, summary, state, Condition.SOURCE_SCHEDULE)
        } else {
            Condition(uri, summary, state)
        }
    }

    private fun buildRule(context: Context, uri: Uri, target: RuleSilence, managedType: Boolean = true): AutomaticZenRule {
        val name = context.getString(R.string.rule_silence_name)
        // Settings' "open in app" lands on our PIN-gated Settings, which gives the kid nothing.
        val config = ComponentName(context, com.kidslauncher.mdm.ui.settings.SettingsActivity::class.java)
        val policy = zenPolicy(target)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            return AutomaticZenRule.Builder(name, uri)
                .setConfigurationActivity(config)
                // The builder's interruption filter is priority-only already, the one a ZenPolicy works with.
                .setZenPolicy(policy)
                .setType(if (managedType) AutomaticZenRule.TYPE_MANAGED else AutomaticZenRule.TYPE_SCHEDULE_TIME)
                .setTriggerDescription(context.getString(R.string.rule_silence_trigger))
                .setManualInvocationAllowed(false)
                .setEnabled(true)
                .build()
        }
        return AutomaticZenRule(name, null, config, uri, policy, NotificationManager.INTERRUPTION_FILTER_PRIORITY, true)
    }

    /** [target] as a policy: only what it lets through; intercepted notifications stay in the shade but never come up
     * over the screen (no full-screen alarm when alarms are silenced, no heads-up, no lights). */
    private fun zenPolicy(target: RuleSilence): ZenPolicy {
        val builder = ZenPolicy.Builder()
            .allowAlarms(target.alarms)
            .allowMedia(target.media)
            .allowSystem(false)
            .allowReminders(false)
            .allowEvents(false)
            .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
            .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
            .allowCalls(if (target.calls) ZenPolicy.PEOPLE_TYPE_ANYONE else ZenPolicy.PEOPLE_TYPE_NONE)
            .allowRepeatCallers(target.calls)
            .showFullScreenIntent(false)
            .showLights(false)
            .showPeeking(false)
            .showInAmbientDisplay(false)
            .showBadges(true)
            .showStatusBarIcons(true)
            .showInNotificationList(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) builder.allowPriorityChannels(false)
        return builder.build()
    }
}
