package com.kidslauncher.mdm.setup

/*
 * Design 114 §6.1: the setup hold - the restrictions pause, started with a longer end at a fresh enrolment and ended by
 * "Ferdig", the PWA's "Lås igjen", a restart or its ceiling. Its start and length live in `RestrictionsPause`
 * (`startHold`, `durationMs`); this is the pure decision.
 */

/**
 * §6.1.1: whether an enrolment starts the hold - decided at the top of `Enrollment.enroll`, before the token is stored
 * (QA L10): the phone is device owner and fresh, i.e. it has never applied a policy and holds no cached one. A managed
 * phone's re-enrolment never starts it (`policy_ever_applied` never goes back on an enrolled phone), so a new hold needs
 * a factory reset and the parent's one-shot code.
 */
fun setupHoldStarts(deviceOwner: Boolean, policyEverApplied: Boolean, cachedPolicy: Boolean): Boolean =
    deviceOwner && !policyEverApplied && !cachedPolicy

/** §4.4 (the contract's `tick.phone`): what the phone does with a tick's answer - `done` drops it from the pending set,
 * `retry` keeps it for the next sync. `null` is no answer. 401/403 retry: a re-enrolment clears the set anyway. */
enum class TickAction { DONE, RETRY }

fun tickAction(status: Int?): TickAction = when {
    status == null -> TickAction.RETRY
    status in 200..299 -> TickAction.DONE
    status == 401 || status == 403 -> TickAction.RETRY
    status in 400..499 -> TickAction.DONE
    else -> TickAction.RETRY
}
