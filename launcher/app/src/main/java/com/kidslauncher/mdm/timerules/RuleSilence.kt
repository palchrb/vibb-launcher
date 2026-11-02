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
 * [calls]: calls from anyone, and repeat callers (our call screening, when calls are managed, already turns away
 * every caller the rules don't allow - so this never widens who may call); `false` = no calls ring.
 */
data class RuleSilence(
    val on: Boolean,
    val alarms: Boolean = false,
    val media: Boolean = false,
    val calls: Boolean = false,
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
 * - Calls: when every active rule allows calls, or whenever [callsScreened] (calls managed: our screening already
 *   rejects every caller the rules don't allow, so what gets through - the emergency callback window included -
 *   rings). Only unmanaged calls under a no-calls rule ring nowhere.
 */
fun ruleSilenceFor(lock: TimeLock, callsScreened: Boolean): RuleSilence {
    val silent = lock.rules.filter { it.silent }
    if (silent.isEmpty()) return NOT_SILENT
    val bedtimeOnly = silent.all { it.kind == KIND_BEDTIME }
    return RuleSilence(
        on = true,
        alarms = bedtimeOnly,
        media = bedtimeOnly,
        calls = lock.callsAllowed || callsScreened,
    )
}

/** The silence to put in force: none while Find My Device rings ([ringHeld] - its alarm-stream ring must be heard),
 * else [wanted]. */
fun silenceInForce(wanted: RuleSilence, ringHeld: Boolean): RuleSilence = if (ringHeld) NOT_SILENT else wanted

/**
 * After the rule's status changed under us (the kid's Quick Settings tile, Settings): whether to switch it off and on
 * again. Only while it should be on and Android says nothing filters ([interruptionsAll]) - the kid's own Do Not
 * Disturb, or any other mode, is never touched.
 */
fun silenceNeedsReassert(inForce: RuleSilence, interruptionsAll: Boolean): Boolean = inForce.on && interruptionsAll
