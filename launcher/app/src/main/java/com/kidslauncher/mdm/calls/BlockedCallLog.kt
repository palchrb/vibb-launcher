package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log

private const val LOG_TAG = "BlockedCallLog"
private const val PREFS = "blocked_call_log"
private const val KEY_LAST_PRUNE_MS = "last_prune_ms"

/** Deletes blocked calls older than 30 days from the system call log - see BlockedCallRetention.kt. */
object BlockedCallLog {
    /** From the sync, after the status report. Never throws. */
    fun pruneIfDue(context: Context) {
        try {
            val now = System.currentTimeMillis()
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val last = prefs.getLong(KEY_LAST_PRUNE_MS, -1L).takeIf { it >= 0 }
            if (!blockedCallPruneDue(last, now)) return
            val allowed = blockedCallPruneAllowed(
                callsManaged = CallPolicyStore.state !is CallPolicyState.Unmanaged,
                unlocked = CallPolicyStore.userUnlocked(context),
                canWriteCallLog = context.checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) ==
                    PackageManager.PERMISSION_GRANTED,
            )
            if (!allowed) return
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} < ?",
                arrayOf(CallLog.Calls.BLOCKED_TYPE.toString(), blockedCallCutoffMs(now).toString()),
            )
            prefs.edit().putLong(KEY_LAST_PRUNE_MS, now).apply()
            if (deleted > 0) Log.i(LOG_TAG, "Deleted $deleted blocked calls older than $BLOCKED_CALL_RETENTION_DAYS days")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't prune the blocked-call log", e)
        }
    }
}
