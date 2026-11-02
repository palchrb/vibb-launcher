package com.kidslauncher.mdm.timerules

/*
 * A time rule's "Stille" switch (the user, 2026-11-02; server migration 0121) - the pure part, tested in
 * RuleSilenceTest. While a silent rule is in force the phone runs our own Do Not Disturb rule
 * ([com.kidslauncher.mdm.timerules.ZenRuleRuntime]): an `AutomaticZenRule` with a priority-only policy, switched with
 * `setAutomaticZenRuleState`, so turning it off hands Android back the kid's own state - never a manual DND, the
 * ringer mode, master mute, `DISALLOW_ADJUST_VOLUME` or suspending the clock (design 18: each of those either ends the
 * kid's own DND or schedules, or leaves the phone silent with nothing to undo it).
 */

/**
 * What our Do Not Disturb rule lets through while [on]. Everything else is silent - notifications, system and touch
 * sounds, reminders, events, messages and conversations. [alarms]: alarm sounds. [media]: music, videos, games.
 * Calls always ring from anyone (and repeat callers): who may call is the call rules' and our screening's question
 * (code QA L6, the coordinator's decision) - with calls managed the screening turns away everyone the rules don't
 * allow (the emergency callback window's calls get through and ring); unmanaged calls ring as they did.
 */
data class RuleSilence(
    val on: Boolean,
    val alarms: Boolean = false,
    val media: Boolean = false,
)

val NOT_SILENT = RuleSilence(on = false)

/**
 * The silence [lock] asks for. Its rules are the active ones no lift suspends, and none under the parent's override or
 * the pause ([decideTimeLock] gives no rules then), so the silence is liftable like the lock; the used-up budget alone
 * never silences.
 *
 * - No active rule is silent: [NOT_SILENT]. Several: silence wins.
 * - Every active silent rule is a bedtime ("silent, alarms still ring"): alarms ring, and so does media - the apps a
 *   bedtime leaves usable (an audiobook, the music app) keep their sound; nothing else can play under the lock.
 * - Any active silent rule is another kind (school, the parent's own): fully silent - no alarms, no media.
 */
fun ruleSilenceFor(lock: TimeLock): RuleSilence {
    val silent = lock.rules.filter { it.silent }
    if (silent.isEmpty()) return NOT_SILENT
    val bedtimeOnly = silent.all { it.kind == KIND_BEDTIME }
    return RuleSilence(on = true, alarms = bedtimeOnly, media = bedtimeOnly)
}

/** The silence to put in force: none while Find My Device rings ([ringHeld] - its alarm-stream ring must be heard),
 * else [wanted]. */
fun silenceInForce(wanted: RuleSilence, ringHeld: Boolean): RuleSilence = if (ringHeld) NOT_SILENT else wanted

/** Whether [applied] (the silence last put in force, `null` = not known) silences alarms: an alarm our rule silences
 * isn't a ringing alarm for the lock (code QA H1) - no yield, no re-sent alarm screen. Read live: a rule that ends while
 * the alarm still rings makes it a ringing alarm again; while Find My Device rings [applied] isn't silent. */
fun silencesAlarms(applied: RuleSilence?): Boolean = applied?.let { it.on && !it.alarms } == true

/** What Android shows of our rule: [enabled], and whether it is in force ([active]; `null` = this Android can't say). */
data class ZenRuleSeen(val enabled: Boolean, val active: Boolean? = null)

/** One apply's step ([zenStep]). */
enum class ZenStep {
    /** What is in force is what was last put in force. */
    SKIP,

    /** Not silent and no rule of ours: nothing to switch off - only remembered. */
    RECORD,

    /** Not silent: our rule's condition false (Android hands back the kid's state). */
    OFF,

    /** Silent and no rule of ours yet: add it, then on. */
    ADD,

    /** Silent: on (its policy updated first when it changed). */
    ON,

    /** Silent, and a switch-off by the user may stand in the way (a forced re-assert, a new process - code QA L2 - or a
     * disabled rule): off, then on - a rule the user switched off stays off for a mere "on" until its condition has
     * been false. */
    OFF_ON,
}

/** What [ZenRuleRuntime]'s apply does for [target], given [applied] (`null` = not known: a new process or a failed
 * call), [force] (the re-assert) and [ours] (our rule as Android has it, `null` = none). */
fun zenStep(target: RuleSilence, applied: RuleSilence?, force: Boolean, ours: ZenRuleSeen?): ZenStep = when {
    !force && target == applied -> ZenStep.SKIP
    !target.on -> if (ours == null) ZenStep.RECORD else ZenStep.OFF
    ours == null -> ZenStep.ADD
    force || applied == null || !ours.enabled -> ZenStep.OFF_ON
    else -> ZenStep.ON
}

/** Code QA M2: read back after switching on - a rule still disabled (Android 15+ keeps a user's disable and ignores the
 * app's re-enable) or gone ([enabledAfter] `null`) is replaced by a new one under a fresh condition id. */
fun zenRuleNeedsReplacing(enabledAfter: Boolean?): Boolean = enabledAfter != true

/**
 * After a status change (the kid's Quick Settings tile, Settings): whether to switch our rule off and on again - while
 * it should be on and it isn't in force: gone, disabled, said to be off ([ZenRuleSeen.active]; code QA L1 - also with
 * the kid's own Do Not Disturb on as well), or nothing filters at all ([interruptionsAll]). Switching our own rule
 * never touches the kid's own Do Not Disturb or mode.
 */
fun silenceNeedsReassert(inForce: RuleSilence, ours: ZenRuleSeen?, interruptionsAll: Boolean): Boolean =
    inForce.on && (ours == null || !ours.enabled || ours.active == false || interruptionsAll)

/** Code QA L3: follows come from two threads (the re-check on main, `AppEnforcer.apply` on IO); a lock decided before
 * the last one followed ([decidedAtMs] < [lastDecidedAtMs], elapsed realtime) is older and is dropped. */
fun lockIsNewer(decidedAtMs: Long, lastDecidedAtMs: Long): Boolean = decidedAtMs >= lastDecidedAtMs
