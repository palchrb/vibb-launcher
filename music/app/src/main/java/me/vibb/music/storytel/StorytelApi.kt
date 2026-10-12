package me.vibb.music.storytel

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import me.vibb.music.bridge.StorytelLogin
import me.vibb.music.model.safeUrl
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * The phone's Storytel client, ported from palchrb/vibb `pi/vibb/storytel.py` (the CONSTANTS block,
 * `_encrypt_password`, `login`, `_bearer`, `_api`, `asset_url`, `_LOGIN_COOLDOWN_S` and
 * `_cooldown_*`; MIT License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0
 * (or later) - see NOTICE.md. Design 26b §4.1 (QA M7, L6). Pure and Android-free: the HTTP seam, the
 * stored login, the cooldown file and both clocks come in, so StorytelApiTest runs it on the JVM.
 *
 * UPSTREAM: storytel-player, tag 1.3.0 (github.com/debba/storytel-player, MIT, debba),
 * server/storytelApi.ts (`login`, `getApiBearer`, `getAudioStreamUrl`) and server/passwordCrypt.ts
 * (`encryptPassword`). Every URL, header and key that can drift lives in the constants below and in
 * StorytelEndpoints.kt. The phone never reads the shelf and never posts a bookmark (design 26b §0,
 * §9): only the login and the asset call are ported here.
 *
 * Secrets (§0): the password, the encrypted password (`pwd=` is AES with a public key and IV - as good
 * as the password), the email, the JWT and a minted URL never reach a log, a toString, an exception
 * message or a [Mint] reason. Nothing here logs; reasons are fixed words.
 */

/** The refused-login cooldown's store (vibb `storytel-login-refused.json`), per login generation. */
interface CooldownStore {
    /** When a login of [generation] was refused (wall clock, ms), or null. */
    fun refusedAt(generation: Long): Long?

    fun setRefused(generation: Long, wallMs: Long)
}

/** What a mint (`asset_url`) came to (QA M7). */
sealed interface Mint {
    /** The signed CDN URL: used once, never stored or logged. */
    class Url(val url: String) : Mint {
        override fun toString(): String = "Mint.Url(<redacted>)"
    }

    /** No login on the phone, or it can't be opened. */
    data object NotConfigured : Mint

    /** Storytel refused the login now or inside the cooldown, which runs until [untilWallMs]. */
    data class Refused(val untilWallMs: Long) : Mint

    /** The login was unreachable, ran out of time, or answered 200 without a JWT: no cooldown. */
    data class LoginFailed(val reason: String) : Mint

    /** 3xx without a usable Location, 403, 404, 410, 451 (locked, geo, gone): item error `no_audio`. */
    data class NoAudio(val status: Int) : Mint

    /** The asset call's 401 after the re-login, 408, 429, 5xx or anything else: `http_<status>`. */
    data class Failed(val status: Int) : Mint

    /** The asset call's transport failure or timeout: `network`. */
    data class Unreachable(val reason: String) : Mint
}

class StorytelApi(
    private val http: StorytelHttp,
    /** Phase 1's sealed login (generation, email, password), read at every login and token check. */
    private val login: () -> StorytelLogin.Login?,
    private val cooldown: CooldownStore,
    /** A monotonic clock (ms): the JWT's TTL and the deadlines. */
    private val elapsedMs: () -> Long,
    /** The wall clock (ms): the cooldown's stamp survives a reboot. */
    private val wallMs: () -> Long,
    private val endpoints: Endpoints = Endpoints.STORYTEL,
) {
    /** The JWT, in memory only (vibb wrote it to a file nothing read, §9), for one login generation. */
    private class Jwt(val value: String, val expiresAtMs: Long, val generation: Long) {
        override fun toString(): String = "Jwt(<redacted>, generation=$generation)"
    }

    @Volatile
    private var jwt: Jwt? = null

    /**
     * One login at a time (design 26b §0, §4.2): the download worker and a Sonos actor never log in
     * twice at once. The wait for it counts against the caller's deadline (QA L6); a waiter that gets
     * it after another's login uses that JWT. An asset call holds no lock.
     */
    private val loginLock = ReentrantLock()

    /** Either a JWT or why there is none. */
    private sealed interface Bearer {
        class Token(val jwt: Jwt) : Bearer
        class None(val mint: Mint) : Bearer
    }

    /**
     * `asset_url`: the signed CDN URL of book [cid] from the asset call's 302 `Location`, never
     * followed (the bearer never rides to the CDN). [timeoutMs] bounds the asset request; [deadlineMs]
     * (on the elapsed clock) bounds the whole mint, the login and the wait for the login lock included
     * (downloads `(15 s, now + 60 s)`, the Sonos path `(6 s, now + 10 s)`). A 401 re-logs in once and
     * retries once (vibb `_api`). Never throws.
     */
    fun assetUrl(cid: String, timeoutMs: Long, deadlineMs: Long): Mint {
        if (!CID.matches(cid)) return Mint.NoAudio(0) // a listing key never fails this; no request for one that would
        var token = when (val b = bearer(force = false, used = null, deadlineMs = deadlineMs)) {
            is Bearer.Token -> b.jwt
            is Bearer.None -> return b.mint
        }
        var response = asset(cid, token, timeoutMs, deadlineMs) ?: return unreachable(deadlineMs)
        if (response.status == 401) {
            token = when (val b = bearer(force = true, used = token, deadlineMs = deadlineMs)) {
                is Bearer.Token -> b.jwt
                is Bearer.None -> return b.mint
            }
            response = asset(cid, token, timeoutMs, deadlineMs) ?: return unreachable(deadlineMs)
        }
        return classify(response)
    }

    /** The report's `storytel` (design 26b §4.5): `none` (no login stored), `refused` (the cooldown runs), `ok`. */
    fun state(): String {
        val stored = stored() ?: return "none"
        return if (cooldownUntil(stored.generation) != null) "refused" else "ok"
    }

    private fun asset(cid: String, token: Jwt, timeoutMs: Long, deadlineMs: Long): StorytelResponse? {
        val budget = minOf(timeoutMs, deadlineMs - elapsedMs())
        if (budget <= 0) return null
        val url = endpoints.api.newBuilder().addPathSegments(ASSET_PATH).addPathSegment(cid).addPathSegment(ASSET_FORMAT).build()
        val headers = listOf("Authorization" to "Bearer ${token.value}", "Accept" to "*/*")
        return try {
            http.send(StorytelRequest("GET", url, headers, follow = false, timeoutMs = budget))
        } catch (_: Exception) {
            null // IOException = unreachable; anything else is never thrown out of a mint either (vibb 4)
        }
    }

    private fun unreachable(deadlineMs: Long): Mint =
        Mint.Unreachable(if (elapsedMs() >= deadlineMs) "deadline" else "unreachable")

    /** The asset call's answer (QA M7). */
    private fun classify(r: StorytelResponse): Mint {
        val location = r.location?.let(::safeUrl)
        return when {
            r.status in 300..399 && location != null -> Mint.Url(location)
            r.status in 300..399 -> Mint.NoAudio(r.status)
            r.status in NO_AUDIO_STATUSES -> Mint.NoAudio(r.status)
            else -> Mint.Failed(r.status)
        }
    }

    /**
     * `_bearer`: the kept JWT unless [force]d, expired or of another login generation; else a login.
     * [used] is the token a 401 came back for: a forced call that finds another one already (a
     * concurrent re-login) takes it instead of logging in again.
     */
    private fun bearer(force: Boolean, used: Jwt?, deadlineMs: Long): Bearer {
        val stored = stored() ?: return Bearer.None(Mint.NotConfigured.also { jwt = null })
        fresh(stored, force, used)?.let { return Bearer.Token(it) }
        val wait = deadlineMs - elapsedMs()
        if (wait <= 0 || !lockWithin(wait)) return Bearer.None(Mint.LoginFailed("deadline"))
        try {
            // Read again under the lock: a login that finished while we waited serves us too.
            val now = stored() ?: return Bearer.None(Mint.NotConfigured.also { jwt = null })
            fresh(now, force, used)?.let { return Bearer.Token(it) }
            return logIn(now, deadlineMs)
        } finally {
            loginLock.unlock()
        }
    }

    private fun lockWithin(ms: Long): Boolean = try {
        loginLock.tryLock(ms, TimeUnit.MILLISECONDS)
    } catch (_: InterruptedException) {
        Thread.currentThread().interrupt()
        false
    }

    private fun fresh(stored: StorytelLogin.Login, force: Boolean, used: Jwt?): Jwt? {
        val kept = jwt ?: return null
        if (kept.generation != stored.generation || elapsedMs() >= kept.expiresAtMs) return null
        return if (!force || (used != null && kept !== used)) kept else null
    }

    /** `login`: the cooldown first (no request inside it), then `login.action` (redirects followed, as vibb). */
    private fun logIn(stored: StorytelLogin.Login, deadlineMs: Long): Bearer {
        cooldownUntil(stored.generation)?.let { return Bearer.None(Mint.Refused(it)) }
        val budget = minOf(LOGIN_TIMEOUT_MS, deadlineMs - elapsedMs())
        if (budget <= 0) return Bearer.None(Mint.LoginFailed("deadline"))
        val url = endpoints.login.newBuilder()
            .addQueryParameter("m", "1")
            .addQueryParameter("uid", stored.email)
            .addQueryParameter("pwd", encryptPassword(stored.password))
            .build()
        val response = try {
            http.send(StorytelRequest("GET", url, emptyList(), follow = true, timeoutMs = budget))
        } catch (_: Exception) {
            return Bearer.None(Mint.LoginFailed(if (elapsedMs() >= deadlineMs) "deadline" else "unreachable"))
        }
        if (response.status != 200) {
            // A refusal, not an outage (vibb): back off for an hour, unless a new login comes.
            val at = wallMs()
            cooldown.setRefused(stored.generation, at)
            jwt = null
            return Bearer.None(Mint.Refused(at + LOGIN_COOLDOWN_MS))
        }
        val value = jwtOf(response.body) ?: return Bearer.None(Mint.LoginFailed("no jwt"))
        return Bearer.Token(Jwt(value, elapsedMs() + JWT_TTL_MS, stored.generation).also { jwt = it })
    }

    /**
     * The end of the cooldown for [generation], when it runs now: vibb's `0 < now - at < 1 h`, but
     * `0 <=` - vibb's float seconds never see 0, our milliseconds do (a mint right after a refusal); a
     * stamp in the future (the wall clock went back) still counts as none.
     */
    private fun cooldownUntil(generation: Long): Long? {
        val at = runCatching { cooldown.refusedAt(generation) }.getOrNull() ?: return null
        val since = wallMs() - at
        return if (since in 0 until LOGIN_COOLDOWN_MS) at + LOGIN_COOLDOWN_MS else null
    }

    /** The stored login, or null when there is none or it can't be opened. */
    private fun stored(): StorytelLogin.Login? = runCatching { login() }.getOrNull()

    companion object {
        /** `ASSET` = `API` + `/assets/v2/consumables/{cid}/abook` (`/abook` is required). */
        const val ASSET_PATH = "assets/v2/consumables"
        const val ASSET_FORMAT = "abook"

        /** `AES_KEY`, `AES_IV` (passwordCrypt.ts): public, so `pwd=` is as secret as the password. */
        private val AES_KEY = "VQZBJ6TD8M9WBUWT".toByteArray(Charsets.US_ASCII)
        private val AES_IV = "joiwef08u23j341a".toByteArray(Charsets.US_ASCII)

        /** `JWT_TTL_S`: login.action gives no expiry; a 401 re-logs in too. */
        const val JWT_TTL_MS = 3_600_000L

        /** `_LOGIN_COOLDOWN_S`: a refused login waits an hour, unless a new login generation comes. */
        const val LOGIN_COOLDOWN_MS = 3_600_000L

        /** vibb's login timeout. */
        const val LOGIN_TIMEOUT_MS = 20_000L

        /** The asset call's statuses that say the book has no audio for this account (QA M7): permanent. */
        val NO_AUDIO_STATUSES = setOf(403, 404, 410, 451)

        /** A consumable id as the grammar and the listing keys spell it. */
        private val CID = Regex("[A-Za-z0-9_-]{1,64}")

        private val JSON = Json { ignoreUnknownKeys = true }

        private const val HEX = "0123456789ABCDEF"

        /** `_encrypt_password`: AES-128-CBC, PKCS#7 (JCA's PKCS5Padding is PKCS#7 for AES), UTF-8 in, UPPERCASE hex out. */
        fun encryptPassword(password: String): String {
            val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(AES_KEY, "AES"), IvParameterSpec(AES_IV))
            val out = StringBuilder()
            for (b in cipher.doFinal(password.toByteArray(Charsets.UTF_8))) {
                out.append(HEX[(b.toInt() shr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
            }
            return out.toString()
        }

        /** `accountInfo.jwt` when it is a non-empty string. */
        private fun jwtOf(body: ByteArray): String? {
            val root = runCatching { JSON.parseToJsonElement(body.toString(Charsets.UTF_8)) }.getOrNull() as? JsonObject ?: return null
            val info = root["accountInfo"] as? JsonObject ?: return null
            return (info["jwt"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }
        }
    }
}
