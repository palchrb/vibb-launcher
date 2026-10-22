package me.vibb.music.bridge

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.bridge.IMusicBridge
import me.vibb.bridge.IStorytelTokenCallback

/*
 * The server's Storytel session through the launcher (design 58 §5.4; SpotifyBridgeToken copied):
 * the bridge's `getStorytelToken`, answered through `IStorytelTokenCallback` within
 * [StorytelBridgeToken.TIMEOUT_MS]. The launcher asks the server each time and keeps nothing; the
 * music app keeps the JWT in memory only (`storytel/StorytelSession`, design 58 §5.1).
 */

/** What a session ask gave (design 58 §5.4). */
sealed interface JwtResult {
    /** [jwt] until [expiresAtMs] (the elapsed-realtime clock; the server took its margin off), named [session] ("<generation>:<16 hex>"). */
    data class Ok(val jwt: String, val expiresAtMs: Long, val session: String) : JwtResult {
        /** Never the JWT, nor the session that names it (design 58 §7.1). */
        override fun toString(): String = "Ok(jwt=<redacted>, expiresAtMs=$expiresAtMs, session=<set>)"
    }

    /** Storytel is off on this phone (its switch, or no login on the server). */
    data object Off : JwtResult

    /** Storytel refused the family's login: no ask before [retryAfterMs] (the server's cooldown). */
    data class Refused(val retryAfterMs: Long) : JwtResult

    /** Asked too often, or the server can't log in now: ask again after [retryAfterMs]. */
    data class Busy(val retryAfterMs: Long) : JwtResult

    /** Anything else, by its [word]: `unreachable`, `key`, `launcher` (a timeout, an older launcher without the
     * method), or a word this app doesn't know. */
    data class Unreachable(val word: String) : JwtResult
}

/** A `refused` without a wait waits this long (the server's cooldown). */
const val JWT_REFUSED_DEFAULT_MS = 3_600_000L

/** A `busy` without a wait waits this long. */
const val JWT_BUSY_DEFAULT_MS = 30_000L

private val SESSION = Regex("[0-9]{1,18}:[0-9a-f]{16}")

/**
 * The callback's answer as a [JwtResult] (pure): `ok` needs a non-empty JWT, a life above 0 and a valid
 * session (else `Unreachable("launcher")`); `refused` and `busy` take [expiresInMs] as their wait (else
 * the defaults); `off` is [JwtResult.Off]; every other word is [JwtResult.Unreachable] with that word.
 * [nowMs] is the elapsed clock.
 */
fun jwtResult(status: String?, jwt: String?, expiresInMs: Long, session: String?, nowMs: Long): JwtResult = when (status) {
    "ok" -> if (!jwt.isNullOrEmpty() && expiresInMs > 0 && session != null && SESSION.matches(session)) {
        JwtResult.Ok(jwt, nowMs + expiresInMs, session)
    } else {
        JwtResult.Unreachable("launcher")
    }
    "off" -> JwtResult.Off
    "refused" -> JwtResult.Refused(expiresInMs.takeIf { it > 0 } ?: JWT_REFUSED_DEFAULT_MS)
    "busy" -> JwtResult.Busy(expiresInMs.takeIf { it > 0 } ?: JWT_BUSY_DEFAULT_MS)
    else -> JwtResult.Unreachable(status ?: "launcher")
}

object StorytelBridgeToken {
    /** The launcher's answer is awaited this long (bridge_contract.json `storytel_token.timeout_ms`). */
    const val TIMEOUT_MS = 10_000L

    /** The statuses the launcher answers with (bridge_contract.json `storytel_token.statuses`). */
    val STATUSES = listOf("ok", "off", "refused", "busy", "unreachable", "key", "launcher")

    /** A session through a bridge bound for this one call; `Unreachable("launcher")` without one. Off the main thread. */
    suspend fun get(context: Context, stale: String?): JwtResult =
        Bridge.withBridge(context) { ask(it, stale) } ?: JwtResult.Unreachable("launcher")

    /**
     * One `getStorytelToken` on [bridge] ([stale] the session Storytel answered 401 to, else null), its answer
     * awaited at most [TIMEOUT_MS]: an older launcher drops the oneway call it doesn't know, which is a
     * timeout - `Unreachable("launcher")`.
     */
    suspend fun ask(bridge: IMusicBridge, stale: String?): JwtResult {
        val answer = CompletableDeferred<JwtResult>()
        bridge.getStorytelToken(stale, object : IStorytelTokenCallback.Stub() {
            override fun onToken(status: String?, jwt: String?, expiresInMs: Long, session: String?) {
                answer.complete(jwtResult(status, jwt, expiresInMs, session, SystemClock.elapsedRealtime()))
            }
        })
        return withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: JwtResult.Unreachable("launcher")
    }
}
