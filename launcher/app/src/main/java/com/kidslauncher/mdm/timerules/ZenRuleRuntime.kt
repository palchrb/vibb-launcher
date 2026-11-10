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

private const val LOG_TAG = "ZenRuleRuntime"

/** After a status change, how long to wait before looking (our own off-and-on sends two broadcasts). */
private const val REASSERT_DELAY_MS = 1_500L

/** At most one off-and-on per this long; a later one waits for it, never dropped. */
private const val REASSERT_SPACING_MS = 10_000L

/** The condition id's first path segment; a replacement (code QA M2) adds a second, fresh one. */
private const val SILENCE_PATH = "rule-silence"

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
 * accepted policy, process start) and `AppEnforcer.apply`, the newer decision winning ([lockIsNewer]). Each apply's
 * step is [zenStep]; after switching on it reads the rule back and replaces one Android keeps disabled
 * ([zenRuleNeedsReplacing]). Lifted while Find My Device rings ([holdForRing]); put back when the kid switches it off
 * during the rule ([silenceNeedsReassert]); removed when the app stops being device admin ([release]). The lock asks
 * [silencesAlarms]: an alarm it silences is no ringing alarm there.
 */
object ZenRuleRuntime {
    /** What the time lock asks for (the last [follow]). */
    @Volatile
    private var wanted: RuleSilence = NOT_SILENT

    /** Elapsed realtime the last followed lock was decided at ([lockIsNewer]). */
    private var lastDecidedAtMs = Long.MIN_VALUE

    /** Find My Device's ring is playing. */
    @Volatile
    private var ringHeld = false

    /** What was last put in force; `null` = not known (a new process, a failed call), so the next apply looks. */
    @Volatile
    private var applied: RuleSilence? = null

    /** Android 15+: the rule whose last status broadcast said deactivated (cleared by its "activated"). */
    @Volatile
    private var deactivatedId: String? = null

    /** E7 code QA 5: our own last switch-off of a rule - its id and elapsed realtime ([ownOffEcho]). */
    @Volatile
    private var ownOff: Pair<String, Long>? = null

    /** Told on the main thread when [silencesAlarms] flips (the time-rule screen's alarm row). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners.addIfAbsent(listener) }

    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

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
                    noteStatus(intent)
                    main.removeCallbacks(reassert)
                    main.postDelayed(reassert, REASSERT_DELAY_MS)
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't listen for Do Not Disturb changes: ${e.javaClass.simpleName}")
        }
    }

    /** Android 15+'s activated/deactivated statuses of our rule (Android 14 sends only enabled/disabled/removed). */
    private fun noteStatus(intent: Intent) {
        if (intent.action != NotificationManager.ACTION_AUTOMATIC_ZEN_RULE_STATUS_CHANGED) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
        val id = intent.getStringExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_ID) ?: return
        when (intent.getIntExtra(NotificationManager.EXTRA_AUTOMATIC_ZEN_RULE_STATUS, 0)) {
            NotificationManager.AUTOMATIC_RULE_STATUS_DEACTIVATED ->
                if (!ownOffEcho(ownOff?.takeIf { it.first == id }?.second, android.os.SystemClock.elapsedRealtime())) deactivatedId = id
            NotificationManager.AUTOMATIC_RULE_STATUS_ACTIVATED -> if (deactivatedId == id) deactivatedId = null
        }
    }

    /** The time lock in force, decided at elapsed realtime [decidedAtMs]: our rule follows it, unless a newer decision
     * already did (code QA L3). Never throws. */
    @Synchronized
    fun follow(context: Context, lock: TimeLock, decidedAtMs: Long) {
        if (!lockIsNewer(decidedAtMs, lastDecidedAtMs)) return
        lastDecidedAtMs = decidedAtMs
        wanted = ruleSilenceFor(lock)
        apply(context.applicationContext, force = false)
    }

    /** Find My Device's ring starts ([held]) or ends: its alarm-stream ring must be heard, so our rule is off for it. */
    @Synchronized
    fun holdForRing(context: Context, held: Boolean) {
        if (ringHeld == held) return
        ringHeld = held
        apply(context.applicationContext, force = false)
    }

    /** Our rule is on - it lets calls from anyone ring: the VoIP ring's question on Android < 16, where the notification
     * policy it reads is the manual one and doesn't fold our rule in. */
    fun letsCallsRing(): Boolean = applied?.on == true

    /** A silent time rule is in force - asked for by the time lock, or our rule on: the lock's notice buzz keeps quiet
     * under it, also where Do Not Disturb couldn't be switched on or Find My Device's ring holds it off (design 82 W3). */
    fun ruleSilent(): Boolean = wanted.on || applied?.on == true

    /** Our rule is on and silences alarms ([silencesAlarms]): the lock's "an alarm rings" is false then (code QA H1). */
    fun silencesAlarms(): Boolean = silencesAlarms(applied)

    private fun silenceUri(context: Context, fresh: Boolean = false): Uri {
        val builder = Uri.Builder().scheme(Condition.SCHEME).authority(context.packageName).appendPath(SILENCE_PATH)
        if (fresh) builder.appendPath(System.currentTimeMillis().toString())
        return builder.build()
    }

    private fun isDeviceOwner(context: Context): Boolean = try {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /** Our rules (Android returns only the caller's), the one to keep first ([zenRulesKeptFirst]). */
    private fun ours(nm: NotificationManager, context: Context): List<Pair<String, AutomaticZenRule>> {
        val found = nm.automaticZenRules.orEmpty().filterValues { rule ->
            val id = rule.conditionId
            id != null && id.scheme == Condition.SCHEME && id.authority == context.packageName &&
                id.pathSegments.firstOrNull() == SILENCE_PATH
        }
        return zenRulesKeptFirst(found.mapValues { it.value.isEnabled }).map { id -> id to found.getValue(id) }
    }

    /** [applyStep], telling the listeners when [silencesAlarms] flipped. */
    @Synchronized
    private fun apply(context: Context, force: Boolean) {
        val before = silencesAlarms()
        try {
            applyStep(context, force)
        } finally {
            if (silencesAlarms() != before) main.post { listeners.forEach { listener -> runCatching { listener() } } }
        }
    }

    private fun applyStep(context: Context, force: Boolean) {
        val target = silenceInForce(wanted, ringHeld)
        if (!force && target == applied) return
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!isDeviceOwner(context) || !nm.isNotificationPolicyAccessGranted) {
            if (target.on) Log.w(LOG_TAG, "A silent rule is in force, but there is no Do Not Disturb access")
            applied = null
            return
        }
        try {
            val existing = ours(nm, context)
            // A second copy (a lost add's retry) goes; the first one - an enabled one if any (L-R1) - stays.
            existing.drop(1).forEach { (id, _) -> runCatching { nm.removeAutomaticZenRule(id) } }
            val entry = existing.firstOrNull()
            val step = zenStep(target, applied, force, entry?.let { ZenRuleSeen(it.second.isEnabled) })
            when (step) {
                ZenStep.SKIP -> return
                ZenStep.RECORD -> {}
                ZenStep.OFF -> {
                    val (id, rule) = entry!!
                    switchOff(nm, context, id, rule.conditionId)
                    Log.i(LOG_TAG, "Silence off")
                }
                ZenStep.ADD -> {
                    val uri = silenceUri(context)
                    val id = addRule(nm, context, uri, target)
                    switchOn(nm, context, id, uri, target, offFirst = false)
                }
                ZenStep.ON, ZenStep.OFF_ON -> {
                    val (id, current) = entry!!
                    updateIfChanged(nm, context, id, current, target)
                    switchOn(nm, context, id, current.conditionId, target, offFirst = step == ZenStep.OFF_ON)
                }
            }
            applied = target
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the silent rule's Do Not Disturb: ${e.javaClass.simpleName}")
            applied = null
        }
    }

    /** The rule's policy or name changed, or it is disabled: updated, keeping the type it was added with (a plain one
     * where Android refused the managed type). */
    private fun updateIfChanged(nm: NotificationManager, context: Context, id: String, current: AutomaticZenRule, target: RuleSilence) {
        val managed = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM &&
            current.type == AutomaticZenRule.TYPE_MANAGED
        val rule = buildRule(context, current.conditionId, target, managedType = managed)
        if (!current.isEnabled || current.zenPolicy != rule.zenPolicy || current.name != rule.name) {
            nm.updateAutomaticZenRule(id, rule)
        }
    }

    /** On ([offFirst]: off, then on), then read back (code QA M2): a rule still disabled - Android 15+ keeps a user's
     * disable - goes, and a new one under a fresh condition id takes its place (Android restores a deleted rule's user
     * changes when the same condition id comes back). */
    private fun switchOn(nm: NotificationManager, context: Context, id: String, uri: Uri, target: RuleSilence, offFirst: Boolean) {
        if (offFirst) switchOff(nm, context, id, uri)
        nm.setAutomaticZenRuleState(id, condition(context, uri, on = true))
        // L-R2: switched on - a deactivation heard before no longer says it is off (its "activated" may never come).
        deactivatedId = null
        if (!zenRuleNeedsReplacing(nm.getAutomaticZenRule(id)?.isEnabled)) {
            Log.i(LOG_TAG, "Silence on: alarms=${target.alarms} media=${target.media}")
            return
        }
        Log.w(LOG_TAG, "The silent rule stays disabled - replaced by a new one")
        runCatching { nm.removeAutomaticZenRule(id) }
        val fresh = silenceUri(context, fresh = true)
        val newId = addRule(nm, context, fresh, target)
        nm.setAutomaticZenRuleState(newId, condition(context, fresh, on = true))
        if (zenRuleNeedsReplacing(nm.getAutomaticZenRule(newId)?.isEnabled)) {
            throw IllegalStateException("the new silent rule is disabled too")
        }
    }

    /** Our own switch-off, noted so its status echo isn't read as the kid's ([ownOffEcho]). */
    private fun switchOff(nm: NotificationManager, context: Context, id: String, uri: Uri) {
        ownOff = id to android.os.SystemClock.elapsedRealtime()
        nm.setAutomaticZenRuleState(id, condition(context, uri, on = false))
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

    /** Whether our rule [id] is in force: Android 15+ says so (and its last status broadcast); Android 14 can't. */
    private fun activeOf(nm: NotificationManager, id: String): Boolean? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return null
        if (deactivatedId == id) return false
        return nm.getAutomaticZenRuleState(id) == Condition.STATE_TRUE
    }

    /** After a status change: our rule should be on and isn't ([silenceNeedsReassert]) - switched off by the kid,
     * removed or disabled: off and on again, at most once per [REASSERT_SPACING_MS]. */
    private fun reassertIfNeeded(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val inForce = silenceInForce(wanted, ringHeld)
        if (!inForce.on) return
        val all = try {
            nm.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL
        } catch (e: Exception) {
            false
        }
        val seen = try {
            ours(nm, context).firstOrNull()?.let { (id, rule) -> ZenRuleSeen(rule.isEnabled, activeOf(nm, id)) }
        } catch (e: Exception) {
            return
        }
        if (!silenceNeedsReassert(inForce, seen, all)) return
        val now = android.os.SystemClock.elapsedRealtime()
        val wait = lastReassertMs + REASSERT_SPACING_MS - now
        if (wait > 0) {
            main.removeCallbacks(reassert)
            main.postDelayed(reassert, wait)
            return
        }
        lastReassertMs = now
        Log.i(LOG_TAG, "The silent rule isn't in force during a silent time rule - on again")
        apply(context, force = true)
    }

    /** The app stops being device admin: our rule goes (Android restores the phone's own state). */
    fun release(context: Context) {
        val app = context.applicationContext
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            ours(nm, app).forEach { (id, _) -> nm.removeAutomaticZenRule(id) }
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
            // Who may call is the call rules' (code QA L6): with calls managed our screening turns the rest away.
            .allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE)
            .allowRepeatCallers(true)
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
