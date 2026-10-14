package me.vibb.music.bridge

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.bridge.IMusicBridge
import me.vibb.bridge.ISpotifyTokenCallback

/*
 * A Spotify access token through the launcher (design 26c §5.5, §6.1): the bridge's
 * `getSpotifyToken`, answered through `ISpotifyTokenCallback` within [SpotifyBridgeToken.TIMEOUT_MS].
 * The launcher asks the server each time and keeps nothing; the music app's `TokenCache` (step 4)
 * keeps the token in memory only. Step 3 brings the call and its result; nothing plays with it yet.
 */

/** What a token ask gave (design 26c §5.5). */
sealed interface TokenResult {
    /** [token] until [expiresAtMs] (the elapsed-realtime clock), for [account] ("<id>:<generation>"). */
    data class Ok(val token: String, val expiresAtMs: Long, val account: String) : TokenResult {
        /** Never the token (design 26c §7.1). */
        override fun toString(): String = "Ok(token=<redacted>, expiresAtMs=$expiresAtMs, account=$account)"
    }

    /** Spotify is off on this phone (no app, not bound): the web player is cleared. */
    data object Off : TokenResult

    /** The login ended, or Spotify refused the app: the parent must act. */
    data object Reauthorize : TokenResult

    /** Asked too often (the server's limit or Spotify's): ask again after [retryAfterMs]. */
    data class Busy(val retryAfterMs: Long) : TokenResult

    /** Anything else: the server, the launcher or Spotify couldn't answer now (`unreachable`,
     * `key`, `launcher`, a timeout, an older launcher without the method, a word this app doesn't know). */
    data object Unreachable : TokenResult
}

/** A `busy` without a wait waits this long. */
const val TOKEN_BUSY_DEFAULT_MS = 30_000L

private val ACCOUNT = Regex("[0-9]{1,18}:[0-9]{1,18}")

/**
 * The callback's answer as a [TokenResult] (pure): `ok` needs a token, a life above 0 and an account
 * (else [TokenResult.Unreachable]); `busy`'s [expiresInMs] is its wait; every other status - known
 * or not - is [TokenResult.Unreachable] but `off` and `reauthorize`. [nowMs] is the elapsed clock.
 */
fun tokenResult(status: String?, accessToken: String?, expiresInMs: Long, account: String?, nowMs: Long): TokenResult = when (status) {
    "ok" -> if (!accessToken.isNullOrEmpty() && expiresInMs > 0 && account != null && ACCOUNT.matches(account)) {
        TokenResult.Ok(accessToken, nowMs + expiresInMs, account)
    } else {
        TokenResult.Unreachable
    }
    "off" -> TokenResult.Off
    "reauthorize" -> TokenResult.Reauthorize
    "busy" -> TokenResult.Busy(expiresInMs.takeIf { it > 0 } ?: TOKEN_BUSY_DEFAULT_MS)
    else -> TokenResult.Unreachable
}

object SpotifyBridgeToken {
    /** The launcher's answer is awaited this long (bridge_contract.json `spotify_token.timeout_ms`). */
    const val TIMEOUT_MS = 10_000L

    /** The statuses the launcher answers with (bridge_contract.json `spotify_token.statuses`). */
    val STATUSES = listOf("ok", "off", "reauthorize", "busy", "unreachable", "key", "launcher")

    /** A token through a bridge bound for this one call; [TokenResult.Unreachable] without one. Off the main thread. */
    suspend fun get(context: Context): TokenResult = Bridge.withBridge(context) { ask(it) } ?: TokenResult.Unreachable

    /**
     * One `getSpotifyToken` on [bridge], its answer awaited at most [TIMEOUT_MS]: an older launcher
     * drops the oneway call it doesn't know, which is a timeout - [TokenResult.Unreachable].
     */
    suspend fun ask(bridge: IMusicBridge): TokenResult {
        val answer = CompletableDeferred<TokenResult>()
        bridge.getSpotifyToken(object : ISpotifyTokenCallback.Stub() {
            override fun onToken(status: String?, accessToken: String?, expiresInMs: Long, account: String?) {
                answer.complete(tokenResult(status, accessToken, expiresInMs, account, SystemClock.elapsedRealtime()))
            }
        })
        return withTimeoutOrNull(TIMEOUT_MS) { answer.await() } ?: TokenResult.Unreachable
    }
}
