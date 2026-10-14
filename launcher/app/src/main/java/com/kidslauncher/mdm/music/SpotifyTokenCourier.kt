package com.kidslauncher.mdm.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/*
 * The Spotify token courier (design 26c §6.1): the music app asks through the bridge
 * (`getSpotifyToken`), the launcher asks the server (`POST /api/devices/music/spotify/token`,
 * MusicServer, the device bearer, over tsnet) and hands the answer on. This is the pure part:
 * the server's answer read into what the callback carries. No cache (the music app caches; one
 * source of expiry) and no log of the body or the token. Pure; SpotifyTokenCourierTest.
 */

/** How long the launcher gives the server's answer (bridge_contract.json `spotify_token.timeout_ms`). */
const val SPOTIFY_TOKEN_TIMEOUT_MS = 10_000L

/** The callback's statuses (bridge_contract.json `spotify_token.statuses`); `launcher` = the launcher couldn't get an answer. */
val SPOTIFY_TOKEN_STATUSES = listOf("ok", "off", "reauthorize", "busy", "unreachable", "key", "launcher")

/** A `busy` answer without a usable `retry_after_s` waits this long. */
const val SPOTIFY_BUSY_DEFAULT_MS = 30_000L

private val WORD = Regex("[a-z_]{1,32}")
private val ACCOUNT = Regex("[0-9]{1,18}:[0-9]{1,18}")
private val TOKEN = Regex("[\\x21-\\x7e]{1,4096}")
private val JSON = Json { ignoreUnknownKeys = true }

/**
 * What the callback gets: [status], [accessToken] only with `ok`, [expiresInMs] the token's life
 * from now with `ok` or the wait before asking again with `busy` (else 0), [account]
 * ("<id>:<generation>") only with `ok`.
 */
data class SpotifyTokenAnswer(val status: String, val accessToken: String?, val expiresInMs: Long, val account: String?) {
    /** Never the token (design 26c §7.1). */
    override fun toString(): String =
        "SpotifyTokenAnswer(status=$status, accessToken=${if (accessToken == null) "null" else "<redacted>"}, expiresInMs=$expiresInMs, account=$account)"

    companion object {
        /** The launcher couldn't get an answer: no server set up, a transport failure, a timeout, junk. */
        val LAUNCHER = SpotifyTokenAnswer("launcher", null, 0, null)
    }
}

/**
 * The server's answer ([httpStatus], [body]) for the callback (design 26c §2.6):
 * - `ok` (only with HTTP 200, a token, `expires_in_s` 1..86400 and `account`) -> the token, its
 *   life in ms and the account; an `ok` missing any of them -> `launcher`;
 * - `busy` -> its `retry_after_s` in ms (1 s .. 1 h; else [SPOTIFY_BUSY_DEFAULT_MS]);
 * - any other status word passes through as is (a newer server's too: the music app maps an
 *   unknown one to "unreachable");
 * - no status word: 404 -> `off` (an older server has no such route), 5xx -> `unreachable`,
 *   anything else -> `launcher`.
 */
fun spotifyTokenAnswer(httpStatus: Int, body: String?): SpotifyTokenAnswer {
    val o = body?.let { runCatching { JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    val status = (o?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { WORD.matches(it) }
    fun text(key: String) = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun number(key: String) = (o?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    return when (status) {
        "ok" -> {
            val token = text("access_token")?.takeIf { TOKEN.matches(it) }
            val life = number("expires_in_s")?.takeIf { it in 1..86_400 }
            val account = text("account")?.takeIf { ACCOUNT.matches(it) }
            if (httpStatus != 200 || token == null || life == null || account == null) SpotifyTokenAnswer.LAUNCHER
            else SpotifyTokenAnswer("ok", token, life * 1000, account)
        }
        "busy" -> SpotifyTokenAnswer("busy", null, number("retry_after_s")?.takeIf { it in 1..3600 }?.times(1000) ?: SPOTIFY_BUSY_DEFAULT_MS, null)
        null -> when (httpStatus) {
            404 -> SpotifyTokenAnswer("off", null, 0, null)
            in 500..599 -> SpotifyTokenAnswer("unreachable", null, 0, null)
            else -> SpotifyTokenAnswer.LAUNCHER
        }
        else -> SpotifyTokenAnswer(status, null, 0, null)
    }
}
