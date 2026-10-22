package me.vibb.music.storytel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.bridge.JwtResult
import me.vibb.music.bridge.StorytelBridgeToken

/*
 * The server's Storytel session on the phone (design 58 §5.1-§5.3; TokenCache's shape, design 26c §5.5).
 * The family's login never reaches the phone: the JWT is the server's, asked through the launcher's
 * bridge (`getStorytelToken`) or, with the debug switch on an unmanaged app, the fake's route - and kept
 * in memory only, never a file, Room, the report or a toString ([Jwt] prints it redacted). Pure: the
 * generation, the ask, the clocks, the scope and the sleep come in. StorytelSessionTest.
 *
 * - A JWT is used while the generation is the one it was asked under and it hasn't expired (the server
 *   took its margin off). Generation 0 is Storytel off: no ask. A new generation drops the JWT and every
 *   wait.
 * - One ask at a time, in [scope], always with the bridge's whole 10 s: a waiter shares the ask in flight
 *   and waits only `min(10 s, deadline - now - timeoutMs)` (the asset call's own time kept); an answer
 *   its waiter gave up on is kept for the next mint (QA 58-6). Under 1 s to wait, no ask starts.
 * - The waits (§5.1's table): `off` 2 min, `refused` and `busy` the server's wait, `key` 10 min, and
 *   after an `unreachable` a 30 s hold (QA 58-3): no ask inside any of them, the same Mint answered. One
 *   prompt retry 5 s after an `unreachable` inside the same mint when it still fits (QA 58-2), past the
 *   hold that answer set. The download worker's come-back after such an outage grows 2, 4, 8, 16, then 30
 *   minutes over consecutive outages, reset by any JWT (qa-58-step2-code S2-2).
 * - Storytel's 401 (storytel-player's rule): the JWT is dropped and asked for once more with `stale`
 *   naming it; a different JWT is retried once, a declined force is its wait (QA N-1), the same JWT back
 *   is `Failed(401)` at once (a belt: the server never hands back the JWT a force names). A second 401
 *   drops the new JWT too.
 * - The prefetch while a room plays a book (QA 58-1, N-6, N-10): [prefetchIfDue].
 */
class StorytelSession(
    /** The login generation now (the managed config's `storytel_generation`; 0 = off), read live at every mint and [state]. */
    private val generation: () -> Long,
    /** One ask, [stale] the session Storytel answered 401 to: `StorytelBridgeToken.get`, or the fake's route. */
    private val ask: suspend (stale: String?) -> JwtResult,
    /** The elapsed clock (ms), the one `JwtResult.Ok.expiresAtMs` is on. */
    private val elapsedMs: () -> Long,
    /** The wall clock (ms): when the download worker comes back. */
    private val wallMs: () -> Long,
    /** Where an ask runs, so it outlives a waiter that gave up (the account's own). */
    private val scope: CoroutineScope,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    /** The server's JWT for [generation] until [expiresAtMs], [lifeMs] long when it came. Never printed. */
    class Jwt(val value: String, val expiresAtMs: Long, val generation: Long, val session: String, val lifeMs: Long) {
        override fun toString(): String = "Jwt(<redacted>, expiresAtMs=$expiresAtMs, generation=$generation, session=<set>)"
    }

    /** One ask in flight: for [generation], [stale] the JWT it names (a force), or null. */
    private class Ask(val stale: Jwt?, val generation: Long, val call: Deferred<JwtResult>)

    private sealed interface Answer {
        class Token(val jwt: Jwt) : Answer
        class None(val mint: Mint) : Answer
    }

    private val lock = Any()

    /** The generation everything below belongs to; Long.MIN_VALUE until the first read. */
    private var seen = Long.MIN_VALUE
    private var kept: Jwt? = null
    private var asking: Ask? = null
    private var offUntil = Long.MIN_VALUE
    private var refusedUntil = Long.MIN_VALUE
    private var keyUntil = Long.MIN_VALUE
    private var busyUntil = Long.MIN_VALUE
    private var holdUntil = Long.MIN_VALUE
    private var prefetchFails = 0
    private var prefetchAt = Long.MIN_VALUE

    /** Consecutive mints that ended on an outage since the last JWT: the come-back's backoff (S2-2). */
    private var outages = 0

    /**
     * A mint with the server's JWT: [asset] is the asset call with the JWT's value (StorytelApi.assetUrl),
     * [timeoutMs] its own bound, [deadlineMs] (the elapsed clock) the whole mint's. Never throws but for the
     * caller's cancellation.
     */
    suspend fun mint(timeoutMs: Long, deadlineMs: Long, asset: suspend (jwt: String) -> Mint): Mint {
        val first = when (val b = bearer(null, timeoutMs, deadlineMs)) {
            is Answer.Token -> b.jwt
            is Answer.None -> return b.mint
        }
        val tried = asset(first.value)
        if (!unauthorized(tried)) return tried
        drop(first)
        val second = when (val b = bearer(first, timeoutMs, deadlineMs)) {
            is Answer.Token -> b.jwt
            is Answer.None -> return b.mint
        }
        // A fresh JWT refused too (qa-26b-step1-code L4): dropped, so the next mint asks first.
        return asset(second.value).also { if (unauthorized(it)) drop(second) }
    }

    /** The report's `storytel` (design 58 §5.2): `none` (off, or `off` answered), `refused` (the server's cooldown), `ok`. */
    fun state(): String {
        val g = generation()
        synchronized(lock) {
            settle(g)
            val now = elapsedMs()
            return when {
                g == 0L || now < offUntil -> "none"
                now < refusedUntil -> "refused"
                else -> "ok"
            }
        }
    }

    /**
     * The refresh ahead of a room's advance (design 58 §5.1, QA 58-1): an ask when the kept JWT has less than
     * `min(10 min, its life / 3)` left and hasn't expired - never for a life of 240 s or less (QA N-6) - or
     * when none is kept; and only with no ask in flight, no wait running and the prefetch's own backoff
     * past (2, 4, then 8 minutes after a failed one or the same JWT back, reset by a new JWT; QA N-10).
     * Called while a room plays a Storytel book (its poll tick) and at a room's start, off main; cheap when
     * nothing is due (the generation is read only then). Waits for its ask; never throws but for its
     * caller's cancellation.
     */
    suspend fun prefetchIfDue() {
        if (synchronized(lock) { !prefetchDue() }) return
        val g = generation()
        val (call, before) = synchronized(lock) {
            settle(g)
            if (!prefetchDue()) return
            start(null, g) to kept?.takeIf { elapsedMs() < it.expiresAtMs }?.value
        }
        val result = await(call, StorytelBridgeToken.TIMEOUT_MS * 2)
        synchronized(lock) {
            if (result is JwtResult.Ok && result.jwt != before) return // keep() took it and reset the backoff
            prefetchFails++
            prefetchAt = elapsedMs() + (PREFETCH_RETRY_MS shl (prefetchFails - 1).coerceAtMost(2))
        }
    }

    /** Is a prefetch due now (the doc of [prefetchIfDue])? Under [lock]. */
    private fun prefetchDue(): Boolean {
        val now = elapsedMs()
        if (seen == 0L || running() != null || now < prefetchAt || waiting(now, hold = true) != null) return false
        val jwt = kept?.takeIf { now < it.expiresAtMs } ?: return true
        return jwt.lifeMs > PREFETCH_MIN_LIFE_MS && jwt.expiresAtMs - now < minOf(PREFETCH_LEFT_MAX_MS, jwt.lifeMs / 3)
    }

    /** A JWT for a mint (other than [stale], the one Storytel just refused), or the Mint there is instead. */
    private suspend fun bearer(stale: Jwt?, timeoutMs: Long, deadlineMs: Long): Answer {
        var retried = false
        while (true) {
            val g = generation()
            val wait = minOf(StorytelBridgeToken.TIMEOUT_MS, deadlineMs - elapsedMs() - timeoutMs)
            val (call, ours) = synchronized(lock) {
                settle(g)
                if (g == 0L) return Answer.None(Mint.NotConfigured)
                val now = elapsedMs()
                kept?.takeIf { now < it.expiresAtMs && it.value != stale?.value }?.let { return Answer.Token(it) }
                waiting(now, hold = !retried)?.let { return Answer.None(it) }
                if (wait < MIN_WAIT_MS) return Answer.None(Mint.LoginFailed("deadline", comeBack()))
                val running = running()
                when {
                    running == null -> start(stale, g) to true
                    stale == null || running.stale?.value == stale.value -> running to true
                    else -> running to false // a plain ask runs: its answer first, then a look again
                }
            }
            val result = await(call, wait)
            if (!ours || generation() != call.generation) continue // its answer is kept (or dropped): look again
            when (result) {
                // the same JWT back for a force (a belt: the server never hands back the JWT a force names)
                is JwtResult.Ok -> return if (result.jwt == call.stale?.value) {
                    Answer.None(Mint.Failed(401))
                } else {
                    Answer.Token(Jwt(result.jwt, result.expiresAtMs, call.generation, result.session, result.expiresAtMs - elapsedMs()))
                }
                JwtResult.Off -> return Answer.None(Mint.NotConfigured)
                is JwtResult.Refused -> return Answer.None(Mint.Refused(wallMs() + result.retryAfterMs))
                is JwtResult.Busy -> return Answer.None(Mint.LoginFailed("busy", wallMs() + result.retryAfterMs))
                is JwtResult.Unreachable -> if (result.word == "key") return Answer.None(Mint.LoginFailed("key", wallMs() + KEY_WAIT_MS))
                null -> Unit // the waiter's time ran out first: the ask goes on, and its answer is kept
            }
            // QA 58-2: one more ask 5 s later, when that still leaves a whole wait before the asset call's time
            if (retried || elapsedMs() + PROMPT_RETRY_MS + MIN_WAIT_MS + timeoutMs > deadlineMs) {
                return Answer.None(Mint.LoginFailed("unreachable", synchronized(lock) { outages++; comeBack() }))
            }
            retried = true
            sleep(PROMPT_RETRY_MS)
        }
    }

    /**
     * The ask's answer, or null when [waitMs] ran out first. An ask that ended without an answer (its scope
     * cancelled: a debug rebuild) is `Unreachable("launcher")`; the caller's own cancellation goes on up.
     */
    private suspend fun await(call: Ask, waitMs: Long): JwtResult? = try {
        withTimeoutOrNull(waitMs) { call.call.await() }
    } catch (e: CancellationException) {
        currentCoroutineContext().ensureActive()
        JwtResult.Unreachable("launcher")
    }

    /** What a mint answers inside a wait, with no ask (§5.1's table); null when none runs. Under [lock]. */
    private fun waiting(now: Long, hold: Boolean): Mint? = when {
        now < offUntil -> Mint.NotConfigured
        now < refusedUntil -> Mint.Refused(wallMs() + (refusedUntil - now))
        now < keyUntil -> Mint.LoginFailed("key", wallMs() + (keyUntil - now))
        now < busyUntil -> Mint.LoginFailed("busy", wallMs() + (busyUntil - now))
        hold && now < holdUntil -> Mint.LoginFailed("unreachable", comeBack())
        else -> null
    }

    /**
     * When the download worker comes back after an outage (wall clock): [COME_BACK_MS] doubling with each
     * consecutive outage stop, at most [COME_BACK_MAX_MS] (S2-2: an old launcher or a server away for a weekend
     * costs a few jobs an hour, not 30). Under [lock] or with only a read of [outages].
     */
    private fun comeBack(): Long =
        wallMs() + minOf(COME_BACK_MS shl (outages - 1).coerceIn(0, 4), COME_BACK_MAX_MS)

    /** A new generation drops the JWT, every wait and the prefetch's backoff (design 58 §5.2). Under [lock]. */
    private fun settle(g: Long) {
        if (g == seen) return
        seen = g
        kept = null
        offUntil = Long.MIN_VALUE
        refusedUntil = Long.MIN_VALUE
        keyUntil = Long.MIN_VALUE
        busyUntil = Long.MIN_VALUE
        holdUntil = Long.MIN_VALUE
        prefetchFails = 0
        prefetchAt = Long.MIN_VALUE
        outages = 0
    }

    /** The ask in flight for this generation; under [lock]. A finished one is none. */
    private fun running(): Ask? = asking?.takeIf { !it.call.isCompleted && it.generation == seen }

    /** A new ask in [scope] (started once it is the one in flight); under [lock]. */
    private fun start(stale: Jwt?, g: Long): Ask {
        lateinit var self: Ask
        val call = scope.async(start = CoroutineStart.LAZY) {
            val result = try {
                ask(stale?.session)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                JwtResult.Unreachable("launcher") // a launcher that went away mid-call (RemoteException)
            }
            synchronized(lock) { keep(result, self) }
            result
        }
        self = Ask(stale, g, call)
        asking = self
        call.start()
        return self
    }

    /** An answer into the session, whoever waits for it; an answer for an older generation is dropped. Under [lock]. */
    private fun keep(result: JwtResult, ask: Ask) {
        if (ask.generation != seen) return
        val now = elapsedMs()
        when (result) {
            // the same value as an unexpired kept JWT is no new one (S2-1: an expired one is replaced by its renewal)
            is JwtResult.Ok -> {
                outages = 0 // the server answered: the next outage's come-back starts at 2 minutes again
                if (result.jwt != ask.stale?.value && result.jwt != kept?.takeIf { now < it.expiresAtMs }?.value) {
                    kept = Jwt(result.jwt, result.expiresAtMs, ask.generation, result.session, result.expiresAtMs - now)
                    prefetchFails = 0
                    prefetchAt = Long.MIN_VALUE
                }
            }
            JwtResult.Off -> {
                kept = null
                offUntil = now + OFF_WAIT_MS
            }
            is JwtResult.Refused -> refusedUntil = now + result.retryAfterMs
            is JwtResult.Busy -> busyUntil = now + result.retryAfterMs
            is JwtResult.Unreachable -> if (result.word == "key") keyUntil = now + KEY_WAIT_MS else holdUntil = now + HOLD_MS
        }
    }

    /** A JWT Storytel refused: never used again (by value; the kept one only when it is that JWT). */
    private fun drop(jwt: Jwt) = synchronized(lock) {
        if (kept?.value == jwt.value) kept = null
    }

    private fun unauthorized(mint: Mint): Boolean = mint is Mint.Failed && mint.status == 401

    companion object {
        /** A waiter with less than this to wait starts no ask: `LoginFailed("deadline")`. */
        const val MIN_WAIT_MS = 1_000L

        /** The one prompt retry after an `unreachable` inside a mint (QA 58-2; TokenCache's). */
        const val PROMPT_RETRY_MS = 5_000L

        /** After an `unreachable`, no ask for this long (QA 58-3: a kid's taps don't spend the server's 20 an hour). */
        const val HOLD_MS = 30_000L

        /** After `off`, no ask for this long (cleared when the generation reads 0 or changes; QA 58-10). */
        const val OFF_WAIT_MS = 2 * 60_000L

        /** After `key` (the server can't open the login), no ask for this long. */
        const val KEY_WAIT_MS = 10 * 60_000L

        /** The download worker comes back this long after an outage, a deadline or a 401 stop (design 58 §5.3, QA N-1)... */
        const val COME_BACK_MS = 2 * 60_000L

        /** ...doubling over consecutive outages to at most this (qa-58-step2-code S2-2). */
        const val COME_BACK_MAX_MS = 30 * 60_000L

        /** A room's prefetch asks at `min(this, life / 3)` left (QA 58-1). */
        const val PREFETCH_LEFT_MAX_MS = 10 * 60_000L

        /** ...never for a JWT whose life on the phone was this or less (QA N-6: `(L - 60 s) / 3 + 60 s < L / 2`). */
        const val PREFETCH_MIN_LIFE_MS = 240_000L

        /** A failed prefetch waits this long before the next, doubling to 4 times it (QA N-10). */
        const val PREFETCH_RETRY_MS = 2 * 60_000L
    }
}
