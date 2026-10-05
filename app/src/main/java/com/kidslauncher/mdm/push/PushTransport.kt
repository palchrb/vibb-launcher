package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.dto.PushPolicy
import java.security.MessageDigest

/*
 * How the server's "something changed, sync now" nudges reach the phone (handy step 7, design
 * 07-battery-fcm-play.md §1). Pure, no Android imports, unit-tested in PushTransportTest.
 *
 * FCM costs almost nothing while idle (Play services already holds one connection for the whole
 * phone); the SSE stream holds our own connection open and wakes the radio for its keepalive.
 * FCM is used only when everything says it works - any doubt lands on SSE: more battery, never
 * less reach. A nudge only ever triggers a sync, so a forged or replayed one costs one sync.
 */

enum class PushTransport(val wire: String) {
    FCM("fcm"),
    SSE("sse"),
}

/** Why the phone is on SSE - reported in `push.reason` so the device page can say it. */
object SseReason {
    const val NO_CONFIG = "no_config"
    const val NO_GMS = "no_gms"
    const val NO_PLAY_STORE = "no_play_store"
    const val NO_TOKEN = "no_token"
    const val SERVER_OFF = "server_off"
    const val TOKEN_UNKNOWN = "token_unknown"
    const val NOT_PROVEN = "not_proven"
}

data class PushInputs(
    /** The build carries a Firebase config (BuildConfig). */
    val fcmConfigured: Boolean,
    /** `com.google.android.gms` is installed and enabled. */
    val gmsAvailable: Boolean,
    /** `com.android.vending` is installed, enabled and not hidden (FCM's documented requirement;
     * our own suspension of it is fine - [com.kidslauncher.mdm.play] never hides it). */
    val playStorePresent: Boolean,
    /** Our current FCM registration token, if we have one. */
    val token: String?,
    /** `PolicyResponse.push` from the enforced policy; `null` from a server without FCM support. */
    val server: PushPolicy?,
)

data class TransportDecision(val transport: PushTransport, val sseReason: String?)

/**
 * FCM only if the build has a config, Play services and the Play Store are there, we hold a
 * token, the server has FCM on, knows *this* token (`fcm_token_hash`) and has seen it work
 * (`fcm_ok`, from its test nudges that our syncs acknowledge). Anything else: SSE, with the
 * first reason that failed.
 */
fun decidePushTransport(inputs: PushInputs): TransportDecision {
    fun sse(reason: String) = TransportDecision(PushTransport.SSE, reason)
    if (!inputs.fcmConfigured) return sse(SseReason.NO_CONFIG)
    if (!inputs.gmsAvailable) return sse(SseReason.NO_GMS)
    if (!inputs.playStorePresent) return sse(SseReason.NO_PLAY_STORE)
    val token = inputs.token?.takeIf { it.isNotBlank() } ?: return sse(SseReason.NO_TOKEN)
    val server = inputs.server
    if (server == null || !server.fcmEnabled) return sse(SseReason.SERVER_OFF)
    if (server.fcmTokenHash != fcmTokenHash(token)) return sse(SseReason.TOKEN_UNKNOWN)
    if (!server.fcmOk) return sse(SseReason.NOT_PROVEN)
    return TransportDecision(PushTransport.FCM, null)
}

/** First 16 hex characters of SHA-256 over the token's UTF-8 bytes - the server sends the same
 * for the token it stores (`push.fcm_token_hash`), so the token itself never comes back. */
fun fcmTokenHash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
        .take(16)

/** How often we ask Firebase for a fresh token while the server doesn't know ours (it cleared it
 * as dead after FCM answered UNREGISTERED, a lost report, a server restore): at most daily.
 * A new token also arrives by itself (`onNewToken`) and goes out with the next status report. */
const val TOKEN_RENEW_INTERVAL_MS = 24 * 60 * 60 * 1000L

/** Without any token: ask again at most hourly (no Play services, no network, ...). */
const val TOKEN_RETRY_INTERVAL_MS = 60 * 60 * 1000L

enum class TokenAction {
    NONE,

    /** `getToken()`: we have none yet. */
    GET,

    /** `deleteToken()` then `getToken()`: the server hasn't known our token for a day. */
    RENEW,
}

/**
 * What to do about the FCM token on this sync. [lastRequestMs] is when we last asked Firebase
 * (0 = never); a time in the future (the clock went back) counts as long ago. Every status
 * report carries the token, so the server normally knows it from the next sync on.
 */
fun tokenAction(
    fcmConfigured: Boolean,
    token: String?,
    server: PushPolicy?,
    lastRequestMs: Long,
    nowMs: Long,
): TokenAction {
    if (!fcmConfigured) return TokenAction.NONE
    val since = nowMs - lastRequestMs
    if (token.isNullOrBlank()) {
        val due = lastRequestMs == 0L || since < 0 || since >= TOKEN_RETRY_INTERVAL_MS
        return if (due) TokenAction.GET else TokenAction.NONE
    }
    if (server == null || !server.fcmEnabled) return TokenAction.NONE
    if (server.fcmTokenHash == fcmTokenHash(token)) return TokenAction.NONE
    val stale = since < 0 || since >= TOKEN_RENEW_INTERVAL_MS
    return if (stale) TokenAction.RENEW else TokenAction.NONE
}

/** RemoteMessage.PRIORITY_* as reported strings (duplicated so this file stays Android-free). */
fun priorityName(priority: Int): String = when (priority) {
    1 -> "high"
    2 -> "normal"
    else -> "unknown"
}
