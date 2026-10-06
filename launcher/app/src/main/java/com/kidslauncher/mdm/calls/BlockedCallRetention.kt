package com.kidslauncher.mdm.calls

/*
 * Retention of the blocked-call log (cleanup round 2026-10-06). Our screening rejects a call with
 * `setDisallowCall` + `setRejectCall`, and Telecom keeps it in the system call log as BLOCKED - the
 * only place blocked calls are recorded. Those rows (strangers' numbers) are deleted after
 * [BLOCKED_CALL_RETENTION_DAYS], once a day, while calls are managed. Nothing else in the call log
 * is touched: missed calls feed the badges (14 days), outgoing emergency calls the callback window.
 * Pure, tested in BlockedCallRetentionTest; [BlockedCallLog] does the delete.
 */

const val BLOCKED_CALL_RETENTION_DAYS = 30
private const val DAY_MS = 24 * 60 * 60 * 1000L
const val BLOCKED_CALL_PRUNE_INTERVAL_MS = DAY_MS

/** Blocked calls from before this (wall clock) are deleted. */
fun blockedCallCutoffMs(nowMs: Long): Long = nowMs - BLOCKED_CALL_RETENTION_DAYS * DAY_MS

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
