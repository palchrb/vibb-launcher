package com.kidslauncher.mdm.calls

/*
 * Retention of the blocked-call log (cleanup round 2026-10-06). Our screening rejects a call with
 * `setDisallowCall` + `setRejectCall`, and Telecom keeps it in the system call log as BLOCKED - the
 * only place blocked calls are recorded. Those rows (strangers' numbers) are deleted after
 * [BLOCKED_CALL_RETENTION_DAYS], once a day, while calls are managed - only rows our own screening
 * blocked (block reason "call screening service" and our component, qa-cleanup #6), never the
 * system blocklist's. Nothing else in the call log is touched: missed calls feed the badges
 * (14 days), outgoing emergency calls the callback window.
 * Pure, tested in BlockedCallRetentionTest; [BlockedCallLog] does the delete.
 */

const val BLOCKED_CALL_RETENTION_DAYS = 30
private const val DAY_MS = 24 * 60 * 60 * 1000L
const val BLOCKED_CALL_PRUNE_INTERVAL_MS = DAY_MS

/** `CallLog.Calls.BLOCK_REASON_CALL_SCREENING_SERVICE`. */
const val BLOCK_REASON_CALL_SCREENING_SERVICE = 1

/**
 * Blocked calls from before this are deleted: 30 days before the earlier of now and the newest
 * call-log row ([newestLoggedMs], `null` = empty log) - a clock set far ahead must not empty the
 * log (qa-cleanup #6).
 */
fun blockedCallCutoffMs(nowMs: Long, newestLoggedMs: Long? = null): Long =
    minOf(nowMs, newestLoggedMs ?: nowMs) - BLOCKED_CALL_RETENTION_DAYS * DAY_MS

/** The `call_screening_component_name` our screening service is logged under: Telecom stores
 * `ComponentName.flattenToString()` (the short form is the same - the class isn't under the
 * package since the rename). */
fun ourScreeningComponentNames(ownPackage: String): List<String> =
    listOf("$ownPackage/com.kidslauncher.mdm.calls.KidCallScreeningService")

/** Once a day; never run yet, or a last run "in the future" (the clock went back) = due. */
fun blockedCallPruneDue(lastRunMs: Long?, nowMs: Long): Boolean =
    lastRunMs == null || lastRunMs > nowMs || nowMs - lastRunMs >= BLOCKED_CALL_PRUNE_INTERVAL_MS

/**
 * Only on a phone whose calls we manage (blocked rows of an unmanaged phone are the owner's own
 * blocks), after the first unlock (the call log is credential-encrypted) and with WRITE_CALL_LOG
 * (self-granted while calls are managed; the dialer role grants it too).
 */
fun blockedCallPruneAllowed(callsManaged: Boolean, unlocked: Boolean, canWriteCallLog: Boolean): Boolean =
    callsManaged && unlocked && canWriteCallLog

/** A wall clock earlier than this build's commit is unset (a fresh boot without network time):
 * no prune then. */
fun wallClockPlausible(nowMs: Long, buildTimeMs: Long): Boolean = nowMs >= buildTimeMs
