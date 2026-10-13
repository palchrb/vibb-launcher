package me.vibb.music.spotify

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import me.vibb.music.bridge.TokenResult
import me.vibb.music.bridge.tokenResult
import me.vibb.music.logic.SPOTIFY_CALL_MS
import me.vibb.music.logic.SPOTIFY_TRANSFER_SETTLE_MS
import me.vibb.music.logic.SpotifyProblem
import okhttp3.HttpUrl

/*
 * The phone's Web API calls (design 26c §5.4): play our window of uris on this phone's web player
 * (`PUT /v1/me/player/play`), make the player Spotify's active device when it isn't known yet
 * (`PUT /v1/me/player`, transfer), and add the next queue item one track ahead (`POST
 * /v1/me/player/queue`). Pure and Android-free over the [SpotifyHttp] seam; the waits (a token, the
 * SDK's state after a transfer, a `Retry-After`) come in as functions. SpotifyApiTest.
 *
 * The account's access token rides only in the header built here (SourceScanTest: `Authorization`
 * appears in StorytelApi.kt and this file only); outcomes and reasons are fixed words, never a token
 * or a body.
 */

/** What one play call carries. */
sealed interface PlayBody {
    /** List mode: our window of uris (a track's or an episode's), started at [positionMs] of the first. */
    data class Uris(val uris: List<String>, val positionMs: Long) : PlayBody

    /** Context mode (§5.9, step 6): Spotify's own order, from [offsetUri] (a playlist's stored track) if any. */
    data class Context(val contextUri: String, val offsetUri: String?, val positionMs: Long) : PlayBody

    /** The JSON body, exactly as play_request.json spells it (keys in this order, no spaces). */
    fun json(): String = when (this) {
        is Uris -> buildJsonObject {
            putJsonArray("uris") { for (u in uris) add(u) }
            put("position_ms", positionMs.coerceAtLeast(0))
        }
        is Context -> buildJsonObject {
            put("context_uri", contextUri)
            if (offsetUri != null) putJsonObject("offset") { put("uri", offsetUri) }
            if (offsetUri != null) put("position_ms", positionMs.coerceAtLeast(0))
        }
    }.toString()
}

/** What one Web API call came to. */
sealed interface PlayOutcome {
    /** 200, 202 or 204. */
    data object Ok : PlayOutcome

    /** 404: the device isn't known to Spotify yet (or any more). */
    data object DeviceUnknown : PlayOutcome

    /** 401: the token was refused. */
    data object Unauthorized : PlayOutcome

    /** 403 `PREMIUM_REQUIRED`. */
    data object NotPremium : PlayOutcome

    /** Any other 403 - the account or the app, never the track's (QA M5); [reason] Spotify's word if it gave one. */
    data class Refused(val reason: String?) : PlayOutcome

    /** 429 `QUOTA_EXCEEDED`: the developer account's bucket is empty. */
    data object Quota : PlayOutcome

    /** Any other 429; [retryAfterMs] from `Retry-After` (seconds), when it gave one. */
    data class RateLimited(val retryAfterMs: Long?) : PlayOutcome

    /** Any other status (5xx, 400, a redirect). */
    data class Failed(val status: Int) : PlayOutcome

    /** A transport failure or a timeout; [reason] a fixed word. */
    data class Unreachable(val reason: String) : PlayOutcome
}

/** How a whole start came out (§5.4 step 3, after its retries). */
sealed interface StartResult {
    /** The play call's 204: wait for our first uri in a playing state. */
    data object Sent : StartResult

    /** No token to play with: [result] is the token's answer (`off`, `reauthorize`, `busy`, `unreachable`). */
    data class NoToken(val result: TokenResult) : StartResult

    data object NotPremium : StartResult

    data object Refused : StartResult

    data object Quota : StartResult

    /** 5xx, IO, a long `Retry-After`, the device still unknown after a transfer, the budget spent; [reason] a fixed word. */
    data class Unreachable(val reason: String) : StartResult
}

/** The §5.8 problem a failed start is; null for [StartResult.Sent]. */
fun StartResult.problem(): SpotifyProblem? = when (this) {
    StartResult.Sent -> null
    is StartResult.NoToken -> result.problem()
    StartResult.NotPremium -> SpotifyProblem.NOT_PREMIUM
    StartResult.Refused -> SpotifyProblem.REFUSED
    StartResult.Quota -> SpotifyProblem.QUOTA
    is StartResult.Unreachable -> SpotifyProblem.UNREACHABLE
}

class SpotifyApi(
    private val http: SpotifyHttp,
    /** `SpotifyEndpoints.apiBase()`: Spotify's, or the fake's with the debug switch. */
    private val base: HttpUrl,
    /** The elapsed clock (ms): the deadlines. */
    private val elapsedMs: () -> Long,
) {
    /** `PUT {base}/v1/me/player/play?device_id=<id>` with [body]. */
    fun play(token: String, deviceId: String, body: PlayBody, timeoutMs: Long = SPOTIFY_CALL_MS): PlayOutcome =
        call("PUT", url("v1/me/player/play").addQueryParameter("device_id", deviceId).build(), token, body.json(), timeoutMs)

    /** `PUT {base}/v1/me/player` `{"device_ids": [<id>], "play": false}` (Transfer Playback). */
    fun transfer(token: String, deviceId: String, timeoutMs: Long = SPOTIFY_CALL_MS): PlayOutcome {
        val body = buildJsonObject {
            putJsonArray("device_ids") { add(deviceId) }
            put("play", false)
        }.toString()
        return call("PUT", url("v1/me/player").build(), token, body, timeoutMs)
    }

    /** `POST {base}/v1/me/player/queue?uri=<uri>&device_id=<id>` (Add Item to Playback Queue), no body. */
    fun queue(token: String, deviceId: String, uri: String, timeoutMs: Long = SPOTIFY_CALL_MS): PlayOutcome =
        call("POST", url("v1/me/player/queue").addQueryParameter("uri", uri).addQueryParameter("device_id", deviceId).build(), token, null, timeoutMs)

    /**
     * A start's play call with its retries (§5.4 step 3), within [deadlineMs] (the elapsed clock): a
     * token from [tokens] (`renew` true after a 401: drop the kept one and ask again, once); the play;
     * a 404 -> one transfer, its 204 awaited, then [awaitDevice] (a state or `ready` from the SDK, at
     * most [SPOTIFY_TRANSFER_SETTLE_MS] - the Player API doesn't order calls) and the play once more; a
     * 429 with a `Retry-After` of at most 5 s waited once ([sleep]). Each call at most
     * [SPOTIFY_CALL_MS], cut to the deadline. Blocking calls run on IO and can be interrupted.
     */
    suspend fun start(
        deviceId: String,
        body: PlayBody,
        deadlineMs: Long,
        tokens: suspend (renew: Boolean) -> TokenResult,
        awaitDevice: suspend (timeoutMs: Long) -> Boolean,
        sleep: suspend (Long) -> Unit = { delay(it) },
    ): StartResult {
        var token = when (val t = tokens(false)) {
            is TokenResult.Ok -> t.token
            else -> return StartResult.NoToken(t)
        }
        var renewed = false
        var transferred = false
        var waited = false
        while (true) {
            val budget = minOf(SPOTIFY_CALL_MS, deadlineMs - elapsedMs())
            if (budget <= 0) return StartResult.Unreachable("deadline")
            val outcome = runInterruptible(Dispatchers.IO) { play(token, deviceId, body, budget) }
            when (outcome) {
                PlayOutcome.Ok -> return StartResult.Sent
                PlayOutcome.DeviceUnknown -> {
                    if (transferred) return StartResult.Unreachable("device")
                    transferred = true
                    val left = minOf(SPOTIFY_CALL_MS, deadlineMs - elapsedMs())
                    if (left <= 0) return StartResult.Unreachable("deadline")
                    when (val moved = runInterruptible(Dispatchers.IO) { transfer(token, deviceId, left) }) {
                        PlayOutcome.Ok -> awaitDevice(minOf(SPOTIFY_TRANSFER_SETTLE_MS, (deadlineMs - elapsedMs()).coerceAtLeast(0)))
                        PlayOutcome.DeviceUnknown -> return StartResult.Unreachable("device")
                        else -> return finish(moved)
                    }
                }
                PlayOutcome.Unauthorized -> {
                    if (renewed) return StartResult.Unreachable("unauthorized")
                    renewed = true
                    token = when (val t = tokens(true)) {
                        is TokenResult.Ok -> t.token
                        else -> return StartResult.NoToken(t)
                    }
                }
                is PlayOutcome.RateLimited -> {
                    val wait = outcome.retryAfterMs
                    if (waited || wait == null || wait > RETRY_AFTER_MAX_MS || elapsedMs() + wait >= deadlineMs) {
                        return StartResult.Unreachable("rate_limited")
                    }
                    waited = true
                    sleep(wait)
                }
                else -> return finish(outcome)
            }
        }
    }

    private fun finish(outcome: PlayOutcome): StartResult = when (outcome) {
        PlayOutcome.Ok -> StartResult.Sent
        PlayOutcome.NotPremium -> StartResult.NotPremium
        is PlayOutcome.Refused -> StartResult.Refused
        PlayOutcome.Quota -> StartResult.Quota
        PlayOutcome.Unauthorized -> StartResult.Unreachable("unauthorized")
        PlayOutcome.DeviceUnknown -> StartResult.Unreachable("device")
        is PlayOutcome.RateLimited -> StartResult.Unreachable("rate_limited")
        is PlayOutcome.Failed -> StartResult.Unreachable("http_${outcome.status}")
        is PlayOutcome.Unreachable -> StartResult.Unreachable(outcome.reason)
    }

    /**
     * The fake's token route (`SpotifyEndpoints.debugTokens()`, the debug switch only, §9.3): the
     * server route's answer shape (§2.6) read into a [TokenResult]; anything else `Unreachable`.
     */
    fun debugToken(url: HttpUrl, timeoutMs: Long = SPOTIFY_CALL_MS): TokenResult {
        val response = try {
            http.send(SpotifyRequest("GET", url, listOf("Accept" to "application/json"), null, timeoutMs))
        } catch (_: Exception) {
            return TokenResult.Unreachable
        }
        return tokenAnswer(response.body.toString(Charsets.UTF_8), elapsedMs())
    }

    private fun url(path: String): HttpUrl.Builder = base.newBuilder().encodedPath("/").addPathSegments(path)

    private fun call(method: String, url: HttpUrl, token: String, body: String?, timeoutMs: Long): PlayOutcome {
        if (timeoutMs <= 0) return PlayOutcome.Unreachable("deadline")
        val started = elapsedMs()
        val headers = listOf("Authorization" to "Bearer $token", "Accept" to "application/json")
        val response = try {
            http.send(SpotifyRequest(method, url, headers, body, timeoutMs))
        } catch (_: Exception) {
            // IOException = unreachable; anything else is never thrown out of a call either
            return PlayOutcome.Unreachable(if (elapsedMs() - started >= timeoutMs) "timeout" else "network")
        }
        return classify(response)
    }

    companion object {
        /** A `Retry-After` longer than this isn't waited for at a start (§5.4: "if at most 5 s, once"). */
        const val RETRY_AFTER_MAX_MS = 5_000L

        private val JSON = Json { ignoreUnknownKeys = true }
        private val REASON = Regex("[A-Z_]{1,40}")

        /** One answer's outcome (§5.4 step 3). */
        fun classify(r: SpotifyResponse): PlayOutcome = when (r.status) {
            200, 202, 204 -> PlayOutcome.Ok
            401 -> PlayOutcome.Unauthorized
            403 -> reason(r.body).let { if (it == "PREMIUM_REQUIRED") PlayOutcome.NotPremium else PlayOutcome.Refused(it) }
            404 -> PlayOutcome.DeviceUnknown
            429 -> if (reason(r.body) == "QUOTA_EXCEEDED") PlayOutcome.Quota else PlayOutcome.RateLimited(retryAfterMs(r.retryAfter))
            else -> PlayOutcome.Failed(r.status)
        }

        /** `Retry-After` in seconds (`0..3600`) as ms; an HTTP date or junk is none. */
        fun retryAfterMs(raw: String?): Long? = raw?.trim()?.toLongOrNull()?.takeIf { it in 0..3_600 }?.times(1_000)

        /** Spotify's error `reason` (`{"error": {"reason": ...}}`, or top-level), a fixed word or null. */
        private fun reason(body: ByteArray): String? {
            val root = runCatching { JSON.parseToJsonElement(body.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject ?: return null
            val inner = (root["error"] as? JsonObject)?.get("reason") ?: root["reason"]
            return (inner as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { REASON.matches(it) }
        }

        /**
         * The token route's JSON (§2.6: `{"status", "access_token", "expires_in_s", "account"}`, `busy`'s
         * `retry_after_s`) as the bridge's [TokenResult] - the same reading as the launcher's courier and
         * `tokenResult` - at [nowMs] (the elapsed clock).
         */
        fun tokenAnswer(body: String, nowMs: Long): TokenResult {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return TokenResult.Unreachable
            fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun seconds(key: String) = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..86_400 }
            val status = text("status")
            val ms = (if (status == "busy") seconds("retry_after_s") else seconds("expires_in_s"))?.times(1_000) ?: 0
            return tokenResult(status, text("access_token"), ms, text("account"), nowMs)
        }
    }
}
