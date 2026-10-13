package me.vibb.music.spotify

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.bridge.TokenResult
import me.vibb.music.logic.SpotifyProblem

/*
 * The phone's Spotify access token (design 26c §5.5): in memory only - never a file, Room,
 * renderer.json, a log or a toString (`TokenResult.Ok` prints it redacted). One ask at a time through
 * the launcher's bridge (or, with the debug switch, the fake's token route): a waiter shares the call
 * in flight, and a call its waiter gave up on still finishes and is kept (the server's `MintJob` rule).
 * Pure: the ask, the clock and the sleep come in. TokenCacheTest.
 *
 * - `get` gives the kept token while it has at least 5 minutes left, else asks.
 * - Cooldowns: after `reauthorize` no ask for 10 minutes; after `busy` none until its wait is over;
 *   after `unreachable` the next play asks again.
 * - One prompt retry at a play start (qa-26c-step3-code L3): an `unreachable` is asked once more about
 *   5 s later, inside the start's deadline - the server's refresh may only have waited behind a sweep
 *   past its 8 s budget, and is then kept, so the second ask finds it.
 * - Ahead of expiry while playing (QA M2): with 10 minutes left the renderer asks for a new token
 *   ([prefetchDue], [prefetch]); a failed refresh is tried again every 2 minutes until the old token
 *   expires. The SDK's own `getOAuthToken` is then answered from memory.
 */
class TokenCache(
    /** Where an ask runs, so it outlives a waiter that gave up (the renderer's scope). */
    private val scope: CoroutineScope,
    /** One ask: `SpotifyBridgeToken.get` (10 s), or `SpotifyApi.debugToken` with the debug switch. */
    private val ask: suspend () -> TokenResult,
    /** The elapsed clock (ms), the one `TokenResult.Ok.expiresAtMs` is on. */
    private val elapsedMs: () -> Long,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
    /** The debug log: fixed words and times, never the token. */
    private val log: (String) -> Unit = {},
) {
    private val lock = Any()
    private var kept: TokenResult.Ok? = null
    private var inFlight: Deferred<TokenResult>? = null
    private var reauthorizeUntil = Long.MIN_VALUE
    private var busyUntil = Long.MIN_VALUE
    private var refreshFailedAt: Long? = null

    /** The account the kept token is for (`"<id>:<generation>"`): the host drops the player's storage when it changes. */
    val account: String? get() = synchronized(lock) { kept?.account }

    /**
     * A token with at least [minLeftMs] left, within [deadlineMs] (the elapsed clock): the kept one, or
     * one ask (shared with one in flight). [atStart] (a play start) asks once more about
     * [PROMPT_RETRY_MS] after an `unreachable`, when that still fits before the deadline.
     */
    suspend fun get(deadlineMs: Long, minLeftMs: Long = MIN_LEFT_MS, atStart: Boolean = false): TokenResult {
        val first = once(deadlineMs, minLeftMs)
        if (!atStart || first != TokenResult.Unreachable || elapsedMs() + PROMPT_RETRY_MS >= deadlineMs) return first
        log("token: unreachable at a start, asking again in ${PROMPT_RETRY_MS / 1000} s")
        sleep(PROMPT_RETRY_MS)
        return once(deadlineMs, minLeftMs)
    }

    /** After the Web API's 401: the kept token is no good. */
    fun invalidate() = synchronized(lock) { kept = null }

    /** Spotify off, or another account: forget the token and every cooldown. */
    fun clear() = synchronized(lock) {
        kept = null
        reauthorizeUntil = Long.MIN_VALUE
        busyUntil = Long.MIN_VALUE
        refreshFailedAt = null
    }

    /**
     * Whether the renderer, [playing] (`playWhenReady`), should ask for a new token now (QA M2): the
     * kept one has [PREFETCH_LEFT_MS] or less left and hasn't expired, no ask is in flight, no cooldown
     * runs, and the last failed refresh was [PREFETCH_RETRY_MS] ago or more.
     */
    fun prefetchDue(playing: Boolean): Boolean = synchronized(lock) {
        val now = elapsedMs()
        val token = kept ?: return false
        playing && running() == null && now < token.expiresAtMs && token.expiresAtMs - now <= PREFETCH_LEFT_MS &&
            now >= reauthorizeUntil && now >= busyUntil && refreshFailedAt.let { it == null || now - it >= PREFETCH_RETRY_MS }
    }

    /**
     * When (the elapsed clock) [prefetchDue] next turns true while playing, for the renderer's timer; null
     * when nothing is kept or the kept token expired (the next `get` asks).
     */
    fun nextPrefetchAtMs(): Long? = synchronized(lock) {
        val token = kept ?: return null
        val now = elapsedMs()
        if (now >= token.expiresAtMs) return null
        val at = maxOf(token.expiresAtMs - PREFETCH_LEFT_MS, refreshFailedAt?.plus(PREFETCH_RETRY_MS) ?: Long.MIN_VALUE, reauthorizeUntil, busyUntil)
        if (at >= token.expiresAtMs) null else maxOf(at, now)
    }

    /**
     * The refresh ahead of expiry: one ask whatever is kept (shared with one in flight), within
     * [deadlineMs]. A new token replaces the kept one only when it lasts longer; anything else keeps the
     * old one and is tried again [PREFETCH_RETRY_MS] later.
     */
    suspend fun prefetch(deadlineMs: Long): TokenResult {
        val call = synchronized(lock) { running() ?: start() }
        val result = await(call, deadlineMs)
        log("token: refreshed ahead of expiry: ${word(result)}")
        return result
    }

    private suspend fun once(deadlineMs: Long, minLeftMs: Long): TokenResult {
        val call = synchronized(lock) {
            val now = elapsedMs()
            kept?.let { if (it.expiresAtMs - now >= minLeftMs) return it }
            if (now < reauthorizeUntil) return TokenResult.Reauthorize
            if (now < busyUntil) return TokenResult.Busy(busyUntil - now)
            running() ?: start()
        }
        val result = await(call, deadlineMs)
        log("token: ${word(result)}")
        return result
    }

    /**
     * The ask in flight; under [lock]. A finished one is none: an ask that completes before
     * [start] stores it (an ask that never suspends) must not be awaited again.
     */
    private fun running(): Deferred<TokenResult>? = inFlight?.takeIf { !it.isCompleted }

    /** A new ask in [scope]; under [lock]. */
    private fun start(): Deferred<TokenResult> = scope.async {
        val result = try {
            ask()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            TokenResult.Unreachable
        }
        synchronized(lock) {
            inFlight = null
            keep(result)
        }
        result
    }.also { inFlight = it }

    private suspend fun await(call: Deferred<TokenResult>, deadlineMs: Long): TokenResult {
        val wait = deadlineMs - elapsedMs()
        if (wait <= 0) return TokenResult.Unreachable
        return withTimeoutOrNull(wait) { call.await() } ?: TokenResult.Unreachable
    }

    /** An ask's answer into the cache; under [lock]. */
    private fun keep(result: TokenResult) {
        val now = elapsedMs()
        val old = kept
        when (result) {
            is TokenResult.Ok -> if (old == null || result.expiresAtMs > old.expiresAtMs || result.account != old.account) {
                kept = result
                refreshFailedAt = null
            } else {
                refreshFailedAt = now // the same token back: not a refresh
            }
            TokenResult.Off -> clear()
            TokenResult.Reauthorize -> {
                reauthorizeUntil = now + REAUTHORIZE_COOLDOWN_MS
                if (old != null) refreshFailedAt = now
            }
            is TokenResult.Busy -> {
                busyUntil = now + result.retryAfterMs
                if (old != null) refreshFailedAt = now
            }
            TokenResult.Unreachable -> if (old != null) refreshFailedAt = now
        }
    }

    private fun word(result: TokenResult): String = when (result) {
        is TokenResult.Ok -> "ok"
        TokenResult.Off -> "off"
        TokenResult.Reauthorize -> "reauthorize"
        is TokenResult.Busy -> "busy"
        TokenResult.Unreachable -> "unreachable"
    }

    companion object {
        /** `get`'s margin: a play starts only with a token that lasts at least this long. */
        const val MIN_LEFT_MS = 5 * 60_000L

        /** While playing, a new token is asked for when this much is left (QA M2). */
        const val PREFETCH_LEFT_MS = 10 * 60_000L

        /** ...and a failed refresh is tried again this often until the old token expires. */
        const val PREFETCH_RETRY_MS = 2 * 60_000L

        /** After `reauthorize`, no ask for this long (the line says so). */
        const val REAUTHORIZE_COOLDOWN_MS = 10 * 60_000L

        /** The one prompt retry after an `unreachable` at a play start (qa-26c-step3-code L3). */
        const val PROMPT_RETRY_MS = 5_000L
    }
}

/** The §5.8 problem a token answer is; null for a token. */
fun TokenResult.problem(): SpotifyProblem? = when (this) {
    is TokenResult.Ok -> null
    TokenResult.Off -> SpotifyProblem.OFF
    TokenResult.Reauthorize -> SpotifyProblem.REAUTHORIZE
    is TokenResult.Busy, TokenResult.Unreachable -> SpotifyProblem.UNREACHABLE
}
