package com.kidslauncher.mdm.music

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/*
 * The Storytel session courier (design 58 §4; SpotifyTokenCourier copied): the music app asks
 * through the bridge (`getStorytelToken`), the launcher asks the server (`POST
 * /api/devices/music/storytel/token`, MusicServer, the device bearer, over tsnet) and hands the
 * answer on. The phone never gets the family's login. This is the pure part: the server's answer
 * read into what the callback carries, and the request's body. No cache (the music app keeps the
 * JWT in memory; one source of expiry) and no log of the body, the JWT or the session. Pure;
 * StorytelTokenCourierTest.
 */

/** How long the launcher gives the server's answer (bridge_contract.json `storytel_token.timeout_ms`). */
const val STORYTEL_TOKEN_TIMEOUT_MS = 10_000L

/** The callback's statuses (bridge_contract.json `storytel_token.statuses`); `launcher` = the launcher couldn't get an answer. */
val STORYTEL_TOKEN_STATUSES = listOf("ok", "off", "refused", "busy", "unreachable", "key", "launcher")

/** A `busy` answer without a usable `retry_after_s` waits this long. */
const val STORYTEL_BUSY_DEFAULT_MS = 30_000L

/** A `refused` answer without a usable `retry_after_s` waits this long (the server's cooldown). */
const val STORYTEL_REFUSED_DEFAULT_MS = 3_600_000L

/** The status report's capability: this launcher carries the courier (design 58 §3.4; the server's Music page reads it). */
const val STORYTEL_JWT_CAPABILITY = "storytel_jwt_v1"

private val WORD = Regex("[a-z_]{1,32}")
private val SESSION = Regex("[0-9]{1,18}:[0-9a-f]{16}")
private val JWT = Regex("[\\x21-\\x7e]{1,8192}")
private val JSON = Json { ignoreUnknownKeys = true }

/**
 * What the callback gets: [status], [jwt] only with `ok`, [expiresInMs] the JWT's use left from now
 * with `ok` or the wait before asking again with `refused` and `busy` (else 0), [session]
 * ("<generation>:<16 hex>") only with `ok`.
 */
data class StorytelTokenAnswer(val status: String, val jwt: String?, val expiresInMs: Long, val session: String?) {
    /** Never the JWT (design 58 §7.1). */
    override fun toString(): String =
        "StorytelTokenAnswer(status=$status, jwt=${if (jwt == null) "null" else "<redacted>"}, expiresInMs=$expiresInMs, session=$session)"

    companion object {
        /** The launcher couldn't get an answer: no server set up, a transport failure, a timeout, junk. */
        val LAUNCHER = StorytelTokenAnswer("launcher", null, 0, null)
    }
}

/**
 * The server's answer ([httpStatus], [body]) for the callback (design 58 §2.2, §4):
 * - `ok` (only with HTTP 200, a `jwt`, `expires_in_s` 1..86400 and a `session`) -> the JWT, its
 *   use left in ms and the session; an `ok` missing any of them -> `launcher`;
 * - `refused` and `busy` -> their `retry_after_s` in ms (1 s .. 1 h; else [STORYTEL_REFUSED_DEFAULT_MS]
 *   and [STORYTEL_BUSY_DEFAULT_MS]);
 * - any other status word passes through as is (a newer server's too: the music app maps an
 *   unknown one to "unreachable");
 * - no status word: 404 -> `off` (an older server has no such route), 5xx -> `unreachable`,
 *   anything else -> `launcher`.
 */
fun storytelTokenAnswer(httpStatus: Int, body: String?): StorytelTokenAnswer {
    val o = body?.let { runCatching { JSON.parseToJsonElement(it) }.getOrNull() } as? JsonObject
    val status = (o?.get("status") as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { WORD.matches(it) }
    fun text(key: String) = (o?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.content
    fun number(key: String) = (o?.get(key) as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
    fun wait(default: Long) = number("retry_after_s")?.takeIf { it in 1..3600 }?.times(1000) ?: default
    return when (status) {
        "ok" -> {
            val jwt = text("jwt")?.takeIf { JWT.matches(it) }
            val life = number("expires_in_s")?.takeIf { it in 1..86_400 }
            val session = text("session")?.takeIf { SESSION.matches(it) }
            if (httpStatus != 200 || jwt == null || life == null || session == null) StorytelTokenAnswer.LAUNCHER
            else StorytelTokenAnswer("ok", jwt, life * 1000, session)
        }
        "refused" -> StorytelTokenAnswer("refused", null, wait(STORYTEL_REFUSED_DEFAULT_MS), null)
        "busy" -> StorytelTokenAnswer("busy", null, wait(STORYTEL_BUSY_DEFAULT_MS), null)
        null -> when (httpStatus) {
            404 -> StorytelTokenAnswer("off", null, 0, null)
            in 500..599 -> StorytelTokenAnswer("unreachable", null, 0, null)
            else -> StorytelTokenAnswer.LAUNCHER
        }
        else -> StorytelTokenAnswer(status, null, 0, null)
    }
}

/** The request's JSON (design 58 §2.2): `{"stale": "<session>"}` only for a valid session, else `{}`. */
fun staleJson(stale: String?): String = if (stale != null && SESSION.matches(stale)) """{"stale":"$stale"}""" else "{}"

/** [staleJson] as the request body: MusicServer's Retrofit has no converter, so it is built by hand. */
fun staleBody(stale: String?): RequestBody = staleJson(stale).toRequestBody("application/json".toMediaType())
