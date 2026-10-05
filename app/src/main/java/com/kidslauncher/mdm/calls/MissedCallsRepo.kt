package com.kidslauncher.mdm.calls

import android.content.Context
import android.provider.CallLog
import android.util.Log
import androidx.core.content.edit

/**
 * Feeds [missedCallSummaries]: the call log of the last 14 days (READ_CALL_LOG, self-granted while
 * calls are managed - nothing without it) and the "seen" marks, kept in CE preferences
 * `missed_calls_seen` (never device-protected storage). Query on a background thread.
 */
object MissedCallsRepo {
    private const val LOG_TAG = "MissedCalls"
    private const val PREFS = "missed_calls_seen"
    private const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_ROWS = 500

    /** Missed calls per contact number while calls are managed; empty otherwise. */
    fun summaries(context: Context, state: CallPolicyState, nowMs: Long = System.currentTimeMillis()): Map<String, MissedSummary> {
        val rules = (state as? CallPolicyState.Managed)?.rules ?: return emptyMap()
        val seen = prefs(context).all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()
        return missedCallSummaries(readLog(context, nowMs - WINDOW_MS), rules.contacts, rules.defaultCc, seen)
    }

    /** The kid opened this contact or called it: its missed calls so far are dealt with. */
    fun markSeen(context: Context, number: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(number, nowMs) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readLog(context: Context, sinceMs: Long): List<CallLogEntry> = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE),
            "${CallLog.Calls.DATE} >= ?",
            arrayOf(sinceMs.toString()),
            // No LIMIT in the sort order: the call-log provider uses strict SQL grammar.
            "${CallLog.Calls.DATE} DESC",
        )?.use { cursor ->
            buildList {
                while (size < MAX_ROWS && cursor.moveToNext()) {
                    val type = when (cursor.getInt(1)) {
                        CallLog.Calls.MISSED_TYPE -> LoggedCallType.MISSED
                        CallLog.Calls.OUTGOING_TYPE -> LoggedCallType.OUTGOING
                        CallLog.Calls.INCOMING_TYPE, CallLog.Calls.ANSWERED_EXTERNALLY_TYPE -> LoggedCallType.INCOMING_ANSWERED
                        else -> LoggedCallType.OTHER // rejected, blocked, voicemail
                    }
                    add(CallLogEntry(cursor.getString(0), type, cursor.getLong(2)))
                }
            }
        }.orEmpty()
    } catch (e: Exception) {
        // SecurityException without READ_CALL_LOG, or a provider problem: no badges.
        Log.w(LOG_TAG, "Couldn't read the call log", e)
        emptyList()
    }
}
