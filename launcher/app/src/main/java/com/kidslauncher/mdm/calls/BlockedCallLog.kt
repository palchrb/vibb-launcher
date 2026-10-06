package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import com.kidslauncher.mdm.BuildConfig

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
            if (!wallClockPlausible(now, BuildConfig.GIT_COMMIT_TIME_MS)) {
                Log.i(LOG_TAG, "The clock looks unset - not pruning blocked calls")
                return
            }
            val cutoff = blockedCallCutoffMs(now, newestLoggedMs(context))
            val components = ourScreeningComponentNames(context.packageName)
            val deleted = context.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.DATE} < ? AND ${CallLog.Calls.BLOCK_REASON} = ? " +
                    "AND ${CallLog.Calls.CALL_SCREENING_COMPONENT_NAME} IN (${components.joinToString { "?" }})",
                arrayOf(
                    CallLog.Calls.BLOCKED_TYPE.toString(),
                    cutoff.toString(),
                    BLOCK_REASON_CALL_SCREENING_SERVICE.toString(),
                ) + components,
            )
            prefs.edit().putLong(KEY_LAST_PRUNE_MS, now).apply()
            if (deleted > 0) Log.i(LOG_TAG, "Deleted $deleted blocked calls older than $BLOCKED_CALL_RETENTION_DAYS days")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't prune the blocked-call log", e)
        }
    }

    /** The newest call-log date, `null` for an empty log. */
    private fun newestLoggedMs(context: Context): Long? =
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI.buildUpon().appendQueryParameter(CallLog.Calls.LIMIT_PARAM_KEY, "1").build(),
            arrayOf(CallLog.Calls.DATE),
            null,
            null,
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
}
