package com.kidslauncher.mdm.calls

import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CallLog
import android.util.Log
import androidx.core.content.edit

/**
 * Feeds [missedCallSummaries]: the call log of the last 14 days (READ_CALL_LOG, self-granted while
 * calls are managed - nothing without it) and the "seen" marks, kept in CE preferences
 * `missed_calls_seen` (never device-protected storage). Query on a background thread. The same
 * rows feed the missed-call notification ([missedCallNotice], [MissedCallNotifier]), which also
 * marks the log read.
 */
object MissedCallsRepo {
    private const val LOG_TAG = "MissedCalls"
    private const val PREFS = "missed_calls_seen"
    private const val WINDOW_MS = 14L * 24 * 60 * 60 * 1000
    private const val MAX_ROWS = 500

    /** Missed calls per contact number while calls are managed; empty otherwise. */
    fun summaries(context: Context, state: CallPolicyState, nowMs: Long = System.currentTimeMillis()): Map<String, MissedSummary> {
        val rules = (state as? CallPolicyState.Managed)?.rules ?: return emptyMap()
        return missedCallSummaries(recentLog(context, nowMs), rules.contacts, rules.defaultCc, seenMarks(context))
    }

    /** The call log of the last 14 days, newest first (with `_id` and `new`); empty when it can't be read. */
    fun recentLog(context: Context, nowMs: Long = System.currentTimeMillis()): List<CallLogEntry> =
        readLog(context, nowMs - WINDOW_MS)

    /** Contact number -> when the kid last opened it or called it. */
    fun seenMarks(context: Context): Map<String, Long> =
        prefs(context).all.mapNotNull { (key, value) -> (value as? Long)?.let { key to it } }.toMap()

    /**
     * The missed calls the log still has as unread (`new = 1`, any age): count, newest `_id` and
     * date - ids and dates only, never numbers. [UnreadMissed.NONE] when they can't be read
     * ([canKeepMissedCalls]). Queried before [recentLog]: everything up to the newest id has been
     * looked at by the time it is marked read (design 12, QA #1).
     */
    fun unreadMissed(context: Context): UnreadMissed {
        if (!missedCallDuty(context)) return UnreadMissed.NONE
        return try {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls._ID, CallLog.Calls.DATE),
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString()),
                "${CallLog.Calls._ID} DESC",
            )?.use { cursor ->
                if (cursor.moveToFirst()) UnreadMissed(cursor.count, cursor.getLong(0), cursor.getLong(1)) else UnreadMissed.NONE
            } ?: UnreadMissed.NONE
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the unread missed calls", e)
            UnreadMissed.NONE
        }
    }

    /**
     * Marks the unread missed calls up to [upToId] read (`new = 0`, `is_read = 1`), as Telecom
     * would for a dialer without the receiver - otherwise it sends them to us again at every boot
     * (design 12, QA #1). WRITE_CALL_LOG, held by policy while calls are managed (and by the dialer
     * role). Never throws.
     */
    fun markMissedRead(context: Context, upToId: Long): Boolean {
        if (upToId <= 0 || !missedCallDuty(context)) return false
        if (context.checkSelfPermission(android.Manifest.permission.WRITE_CALL_LOG) != PackageManager.PERMISSION_GRANTED) {
            Log.w(LOG_TAG, "No WRITE_CALL_LOG - missed calls stay unread")
            return false
        }
        return try {
            val values = ContentValues().apply {
                put(CallLog.Calls.NEW, 0)
                put(CallLog.Calls.IS_READ, 1)
            }
            val updated = context.contentResolver.update(
                CallLog.Calls.CONTENT_URI,
                values,
                "${CallLog.Calls.TYPE} = ? AND ${CallLog.Calls.NEW} = 1 AND ${CallLog.Calls._ID} <= ?",
                arrayOf(CallLog.Calls.MISSED_TYPE.toString(), upToId.toString()),
            )
            Log.i(LOG_TAG, "Marked $updated missed calls read")
            true
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't mark missed calls read", e)
            false
        }
    }

    /** The kid opened this contact or called it: its missed calls so far are dealt with. */
    fun markSeen(context: Context, number: String, nowMs: Long = System.currentTimeMillis()) {
        prefs(context).edit { putLong(number, nowMs) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** [canKeepMissedCalls]: managed, or our dialer role held (Telecom gives us the duty). */
    private fun missedCallDuty(context: Context): Boolean =
        canKeepMissedCalls(
            CallSystem.callLogGranted(context), CallPolicyStore.userUnlocked(context),
            CallPolicyStore.state.managed, CallSystem.dialerRoleHeld(context),
        )

    private fun readable(context: Context): Boolean =
        canReadCallLog(CallSystem.callLogGranted(context), CallPolicyStore.userUnlocked(context), CallPolicyStore.state.managed)

    private fun readLog(context: Context, sinceMs: Long): List<CallLogEntry> =
        if (!readable(context)) emptyList() else queryLog(context, sinceMs)

    private fun queryLog(context: Context, sinceMs: Long): List<CallLogEntry> = try {
        context.contentResolver.query(
            CallLog.Calls.CONTENT_URI,
            arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.DATE, CallLog.Calls._ID, CallLog.Calls.NEW),
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
                    add(CallLogEntry(cursor.getString(0), type, cursor.getLong(2), id = cursor.getLong(3), unread = cursor.getInt(4) == 1))
                }
            }
        }.orEmpty()
    } catch (e: Exception) {
        // SecurityException without READ_CALL_LOG, or a provider problem: no badges.
        Log.w(LOG_TAG, "Couldn't read the call log", e)
        emptyList()
    }
}
