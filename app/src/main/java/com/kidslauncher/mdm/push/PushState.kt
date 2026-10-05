package com.kidslauncher.mdm.push

import android.content.Context
import android.content.SharedPreferences
import com.kidslauncher.mdm.server.dto.PushReport

/**
 * What the phone knows about its push transport (handy step 7), in CE preferences `push_state`
 * (written with `commit()`: a token or a nudge acknowledgement must not be lost to a process
 * kill right after). Never read before the first unlock - nothing push-related is
 * direct-boot-aware. The FCM token is a credential for sending to this phone; it only goes to our
 * own server (status report).
 */
object PushState {
    private const val PREFS = "push_state"
    private const val TOKEN = "fcm_token"
    private const val TOKEN_REQUESTED_AT = "fcm_token_requested_at"
    private const val LAST_NUDGE_MS = "last_nudge_ms"
    private const val LAST_NUDGE_ID = "last_nudge_id"
    private const val LAST_PRIORITY = "last_priority"
    private const val LAST_ORIGINAL_PRIORITY = "last_original_priority"
    private const val SERVER_KNEW_HASH = "server_knew_hash"

    /** Live state of the SSE stream (only meaningful while the transport is SSE). */
    @Volatile
    var sseConnected: Boolean = false

    /** The last transport decision, kept in memory for the backstop and the report. */
    @Volatile
    var lastDecision: TransportDecision = TransportDecision(PushTransport.SSE, SseReason.NO_CONFIG)

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun token(context: Context): String? = prefs(context).getString(TOKEN, null)

    fun tokenRequestedAt(context: Context): Long = prefs(context).getLong(TOKEN_REQUESTED_AT, 0L)

    fun markTokenRequested(context: Context, nowMs: Long) {
        prefs(context).edit().putLong(TOKEN_REQUESTED_AT, nowMs).commit()
    }

    /** The token hash the server last confirmed knowing (policy `fcm_token_hash`). */
    fun serverKnewHash(context: Context): String? = prefs(context).getString(SERVER_KNEW_HASH, null)

    fun setServerKnewHash(context: Context, hash: String?) {
        if (serverKnewHash(context) != hash) prefs(context).edit().putString(SERVER_KNEW_HASH, hash).commit()
    }

    fun saveToken(context: Context, token: String?) {
        prefs(context).edit().putString(TOKEN, token).commit()
    }

    /** One FCM message arrived: its `n` is our acknowledgement for the server's health check. */
    fun recordNudge(context: Context, nowMs: Long, nudgeId: String?, priority: String, originalPriority: String) {
        prefs(context).edit()
            .putLong(LAST_NUDGE_MS, nowMs)
            .putString(LAST_NUDGE_ID, nudgeId)
            .putString(LAST_PRIORITY, priority)
            .putString(LAST_ORIGINAL_PRIORITY, originalPriority)
            .commit()
    }

    fun report(context: Context, fcmConfigured: Boolean, gmsAvailable: Boolean): PushReport {
        val p = prefs(context)
        val decision = lastDecision
        return PushReport(
            fcmToken = p.getString(TOKEN, null),
            transport = decision.transport.wire,
            fcmConfigured = fcmConfigured,
            gmsAvailable = gmsAvailable,
            lastNudgeMs = p.getLong(LAST_NUDGE_MS, 0L).takeIf { it > 0 },
            lastNudgeId = p.getString(LAST_NUDGE_ID, null),
            lastPriority = p.getString(LAST_PRIORITY, null),
            lastOriginalPriority = p.getString(LAST_ORIGINAL_PRIORITY, null),
            reason = decision.sseReason,
        )
    }
}
