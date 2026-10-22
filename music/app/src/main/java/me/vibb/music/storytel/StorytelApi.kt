package me.vibb.music.storytel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import me.vibb.music.bridge.JwtResult
import me.vibb.music.bridge.jwtResult
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/*
 * The phone's Storytel client, ported from palchrb/vibb `pi/vibb/storytel.py` (the CONSTANTS block,
 * `_api`, `asset_url`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the
 * GPL-3.0 (or later) - see NOTICE.md. Design 26b §4.1 (QA M7), design 58 §5.1. Pure and Android-free:
 * the HTTP seam and the clock come in, so StorytelApiTest runs it on the JVM.
 *
 * UPSTREAM: storytel-player, tag 1.3.0 (github.com/debba/storytel-player, MIT, debba),
 * server/storytelApi.ts (`getAudioStreamUrl`). Every URL and header that can drift lives in the
 * constants below and in StorytelEndpoints.kt. Since design 58 the phone never logs in: the JWT is the
 * server's, through the launcher (StorytelSession), so only the asset call is here. The phone never
 * reads the shelf and never posts a bookmark (design 26b §0, §9).
 *
 * Secrets (design 58 §7.1): the JWT and a minted URL never reach a log, a toString, an exception message
 * or a [Mint] reason. Nothing here logs; reasons are fixed words.
 */

/** What a mint (`asset_url`) came to (QA M7). */
sealed interface Mint {
    /** The signed CDN URL: used once, never stored or logged. */
    class Url(val url: String) : Mint {
        override fun toString(): String = "Mint.Url(<redacted>)"
    }

    /** Storytel is off on this phone (its switch, or no login on the server). */
    data object NotConfigured : Mint

    /** Storytel refused the family's login: the server's cooldown runs until [untilWallMs]. */
    data class Refused(val untilWallMs: Long) : Mint

    /**
     * No JWT now ([reason]: `busy`, `key`, `unreachable`, `deadline`): the download worker comes back at
     * [backAtWallMs] (design 58 §5.3).
     */
    data class LoginFailed(val reason: String, val backAtWallMs: Long) : Mint

    /** 3xx without a usable Location, 403, 404, 410, 451 (locked, geo, gone): item error `no_audio`. */
    data class NoAudio(val status: Int) : Mint

    /** The asset call's 401 after the retry, 408, 429, 5xx or anything else: `http_<status>`. */
    data class Failed(val status: Int) : Mint

    /** The asset call's transport failure or timeout: `network`. */
    data class Unreachable(val reason: String) : Mint
}

class StorytelApi(
    private val http: StorytelHttp,
    /** A monotonic clock (ms): the deadlines and a JWT's expiry. */
    private val elapsedMs: () -> Long,
    private val endpoints: Endpoints = Endpoints.STORYTEL,
) {
    /**
     * `asset_url`: the signed CDN URL of book [cid] from the asset call's 302 `Location`, never followed
     * (the bearer never rides to the CDN), with the server's [jwt]. [timeoutMs] bounds the request,
     * [deadlineMs] (the elapsed clock) the whole mint. A 401 is `Failed(401)`: StorytelSession asks for
     * another JWT and retries once. Interruptible: a cancelled caller (a download job WorkManager stops)
     * ends a blocked request at once and gets its CancellationException. Never throws otherwise.
     */
    suspend fun assetUrl(cid: String, jwt: String, timeoutMs: Long, deadlineMs: Long): Mint {
        if (!CID.matches(cid)) return Mint.NoAudio(0) // a listing key never fails this; no request for one that would
        val budget = minOf(timeoutMs, deadlineMs - elapsedMs())
        if (budget <= 0) return Mint.Unreachable("deadline")
        val url = endpoints.api.newBuilder().addPathSegments(ASSET_PATH).addPathSegment(cid).addPathSegment(ASSET_FORMAT).build()
        val headers = listOf("Authorization" to "Bearer $jwt", "Accept" to "*/*")
        // A cancelled caller interrupts the thread; whatever the request then throws, runInterruptible hands the
        // caller its CancellationException (the prompt cancellation of its withContext), never this answer.
        val response = runInterruptible(Dispatchers.IO) {
            try {
                http.send(StorytelRequest("GET", url, headers, follow = false, timeoutMs = budget))
            } catch (_: Exception) {
                null // IOException = unreachable; anything else is never thrown out of a mint either (vibb 4)
            }
        } ?: return Mint.Unreachable(if (elapsedMs() >= deadlineMs) "deadline" else "unreachable")
        return classify(response)
    }

    /**
     * The fake's session route (`StorytelEndpoints.debugJwt()`, the debug switch only, design 58 §5.6): the
     * server route's answer shape read into a [JwtResult] ([jwtAnswer]); [stale] goes as `?stale=`.
     */
    fun debugJwt(url: HttpUrl, stale: String?, timeoutMs: Long = DEBUG_JWT_TIMEOUT_MS): JwtResult {
        val ask = if (stale == null) url else url.newBuilder().addQueryParameter("stale", stale).build()
        val response = try {
            http.send(StorytelRequest("GET", ask, listOf("Accept" to "application/json"), follow = false, timeoutMs = timeoutMs))
        } catch (_: Exception) {
            return JwtResult.Unreachable("unreachable")
        }
        return jwtAnswer(response.body.toString(Charsets.UTF_8), elapsedMs())
    }

    /** The asset call's answer (QA M7). */
    private fun classify(r: StorytelResponse): Mint {
        val location = mintedUrl(r.location)
        return when {
            r.status in 300..399 && location != null -> Mint.Url(location)
            r.status in 300..399 -> Mint.NoAudio(r.status)
            r.status in NO_AUDIO_STATUSES -> Mint.NoAudio(r.status)
            else -> Mint.Failed(r.status)
        }
    }

    companion object {
        /** `ASSET` = `API` + `/assets/v2/consumables/{cid}/abook` (`/abook` is required). */
        const val ASSET_PATH = "assets/v2/consumables"
        const val ASSET_FORMAT = "abook"

        /** The asset call's statuses that say the book has no audio for this account (QA M7): permanent. */
        val NO_AUDIO_STATUSES = setOf(403, 404, 410, 451)

        /** The fake's session route is given this long (the bridge's own bound). */
        const val DEBUG_JWT_TIMEOUT_MS = 10_000L

        /** A consumable id as the grammar and the listing keys spell it. */
        private val CID = Regex("[A-Za-z0-9_-]{1,64}")

        private val JSON = Json { ignoreUnknownKeys = true }

        /** The longest minted URL taken: a signed CDN URL is never stored, and some run long. */
        const val MINTED_URL_MAX = 8 * 1024

        /**
         * A minted `Location` as the player and the speaker can use it (qa-26b-step1-code L3): what OkHttp
         * parses (not `java.net.URI`, which refuses an unencoded `|` or space that OkHttp and ExoPlayer
         * take), http(s), a host, no username or password, at most [MINTED_URL_MAX] characters; the
         * canonically encoded form, else null. §5.1's check on the Sonos path uses it too.
         */
        fun mintedUrl(location: String?): String? {
            if (location == null || location.length > MINTED_URL_MAX) return null
            val url = location.toHttpUrlOrNull() ?: return null
            if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.host.isEmpty()) return null
            return url.toString().takeIf { it.length <= MINTED_URL_MAX }
        }

        /**
         * `POST /api/devices/music/storytel/token`'s body (design 58 §2.2, storytel_token_answers.json) as
         * the launcher hands it on: `expires_in_s` with `ok`, `retry_after_s` with `refused` and `busy`, read
         * by [jwtResult]; anything that isn't a JSON object is `Unreachable("unreachable")`. [nowMs] is the
         * elapsed clock.
         */
        fun jwtAnswer(body: String, nowMs: Long): JwtResult {
            val root = runCatching { JSON.parseToJsonElement(body) }.getOrNull() as? JsonObject ?: return JwtResult.Unreachable("unreachable")
            fun text(key: String) = (root[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
            fun seconds(key: String) = (root[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.takeIf { it in 0..86_400 }
            val status = text("status")
            val ms = (if (status == "ok") seconds("expires_in_s") else seconds("retry_after_s"))?.times(1_000) ?: 0
            return jwtResult(status, text("jwt"), ms, text("session"), nowMs)
        }
    }
}
