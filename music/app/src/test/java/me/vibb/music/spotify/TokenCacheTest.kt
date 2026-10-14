package me.vibb.music.spotify

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.vibb.music.bridge.TokenResult
import me.vibb.music.logic.SpotifyProblem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's token (design 26c §5.5): the 5-minute margin, one ask in flight, the cooldowns, the one
 * prompt retry at a start (qa-26c-step3-code L3), the refresh ahead of expiry while playing and its
 * 2-minute retry (QA M2), never a token in a toString.
 */
class TokenCacheTest {
    private var clock = 1_000_000L
    private val asks = mutableListOf<Long>()
    private val slept = mutableListOf<Long>()
    private val min = 60_000L

    private fun ok(n: Int, life: Long = 60 * min, account: String = "2:1") = TokenResult.Ok("FAKE-ACCESS-$n", clock + life, account)

    /** A cache over [answers] in order (the last repeats); the clock is [clock], a sleep moves it. */
    private fun cache(scope: kotlinx.coroutines.CoroutineScope, vararg answers: () -> TokenResult) = TokenCache(
        scope,
        ask = { asks += clock; answers.getOrElse(asks.size - 1) { answers.last() }() },
        elapsedMs = { clock },
        sleep = { slept += it; clock += it },
    )

    private val far get() = clock + 25_000

    @Test
    fun `the kept token while it has 5 minutes left, else one ask`() = runBlocking {
        val c = cache(this, { ok(1) }, { ok(2) })
        assertEquals("FAKE-ACCESS-1", (c.get(far) as TokenResult.Ok).token)
        clock += 54 * min
        assertEquals("6 minutes left: kept", "FAKE-ACCESS-1", (c.get(far) as TokenResult.Ok).token)
        assertEquals(1, asks.size)
        clock += 2 * min
        assertEquals("4 minutes left: a new one", "FAKE-ACCESS-2", (c.get(far) as TokenResult.Ok).token)
        assertEquals(2, asks.size)
        assertEquals("2:1", c.account)
        c.invalidate()
        c.get(far)
        assertEquals("after a 401 the kept one is no good", 3, asks.size)
    }

    @Test
    fun `one ask at a time - waiters share the ask in flight`() = runBlocking {
        val gate = CompletableDeferred<TokenResult>()
        val c = TokenCache(this, ask = { asks += clock; gate.await() }, elapsedMs = { clock })
        val a = async { c.get(clock + 25_000) }
        val b = async { c.get(clock + 25_000) }
        yield()
        yield()
        gate.complete(ok(1))
        assertEquals(a.await(), b.await())
        assertEquals("one ask", 1, asks.size)
    }

    @Test
    fun `cooldowns - reauthorize 10 minutes, busy its wait, unreachable none, off clears`() = runBlocking {
        val c = cache(this, { TokenResult.Reauthorize }, { ok(1) })
        assertEquals(TokenResult.Reauthorize, c.get(far))
        clock += 9 * min
        assertEquals("no ask inside 10 minutes", TokenResult.Reauthorize, c.get(far))
        assertEquals(1, asks.size)
        clock += min
        assertTrue(c.get(far) is TokenResult.Ok)
        assertEquals(2, asks.size)

        asks.clear()
        val busy = cache(this, { TokenResult.Busy(30_000) }, { ok(2) })
        assertEquals(TokenResult.Busy(30_000), busy.get(far))
        clock += 10_000
        assertEquals("the rest of the wait, no ask", TokenResult.Busy(20_000), busy.get(far))
        assertEquals(1, asks.size)
        clock += 20_000
        assertTrue(busy.get(far) is TokenResult.Ok)

        asks.clear()
        val down = cache(this, { TokenResult.Unreachable }, { ok(3) })
        assertEquals(TokenResult.Unreachable, down.get(far))
        assertTrue("the next play asks again", down.get(far) is TokenResult.Ok)
        assertEquals(2, asks.size)

        asks.clear()
        val off = cache(this, { ok(4) }, { TokenResult.Off }, { ok(5) })
        off.get(far)
        off.invalidate()
        assertEquals(TokenResult.Off, off.get(far))
        assertNull("off forgets the token and its account", off.account)
        assertEquals("FAKE-ACCESS-5", (off.get(far) as TokenResult.Ok).token)

        asks.clear()
        val thrown = cache(this, { throw IllegalStateException("bridge") })
        assertEquals("an ask that throws is unreachable", TokenResult.Unreachable, thrown.get(far))
    }

    @Test
    fun `one prompt retry about 5 s after unreachable at a start, inside its deadline (L3)`() = runBlocking {
        val c = cache(this, { TokenResult.Unreachable }, { ok(1) })
        assertTrue(c.get(far, atStart = true) is TokenResult.Ok)
        assertEquals(listOf(5_000L), slept)
        assertEquals(2, asks.size)

        asks.clear(); slept.clear()
        val twice = cache(this, { TokenResult.Unreachable })
        assertEquals("one retry only", TokenResult.Unreachable, twice.get(far, atStart = true))
        assertEquals(2, asks.size)
        assertEquals(listOf(5_000L), slept)

        asks.clear(); slept.clear()
        assertEquals("not at a start: no retry", TokenResult.Unreachable, twice.get(far))
        assertEquals(1, asks.size)

        asks.clear(); slept.clear()
        assertEquals("no room before the deadline", TokenResult.Unreachable, twice.get(clock + 5_000, atStart = true))
        assertEquals(1, asks.size)
        assertTrue(slept.isEmpty())

        asks.clear()
        val refused = cache(this, { TokenResult.Reauthorize })
        assertEquals("only unreachable is retried", TokenResult.Reauthorize, refused.get(far, atStart = true))
        assertEquals(1, asks.size)
    }

    @Test
    fun `an ask past the deadline is unreachable, and its token is kept for the next`() = runBlocking {
        val gate = CompletableDeferred<TokenResult>()
        val c = TokenCache(this, ask = { asks += clock; gate.await() }, elapsedMs = { clock })
        assertEquals(TokenResult.Unreachable, c.get(clock + 50))
        assertEquals(TokenResult.Unreachable, c.get(clock))
        gate.complete(ok(1))
        yield()
        assertEquals("FAKE-ACCESS-1", (c.get(clock + 25_000) as TokenResult.Ok).token)
        assertEquals("the ask its waiter gave up on", 1, asks.size)
    }

    @Test
    fun `ahead of expiry while playing - at 10 minutes left, again every 2 minutes until it expires (M2)`() = runBlocking {
        val answers = mutableListOf<() -> TokenResult>({ ok(1, life = 60 * min) })
        val c = TokenCache(this, ask = { asks += clock; answers.removeAt(0)() }, elapsedMs = { clock })
        val first = c.get(far) as TokenResult.Ok
        assertFalse("nothing due with 60 minutes left", c.prefetchDue(playing = true))
        assertEquals(first.expiresAtMs - 10 * min, c.nextPrefetchAtMs())
        clock = first.expiresAtMs - 10 * min
        assertTrue(c.prefetchDue(playing = true))
        assertFalse("only while playing", c.prefetchDue(playing = false))
        answers += { TokenResult.Unreachable }
        assertEquals(TokenResult.Unreachable, c.prefetch(far))
        assertEquals("the old one stays", "FAKE-ACCESS-1", (c.get(far) as TokenResult.Ok).token)
        assertFalse("not again at once", c.prefetchDue(true))
        assertEquals(clock + 2 * min, c.nextPrefetchAtMs())
        clock += 2 * min
        assertTrue("2 minutes on", c.prefetchDue(true))
        answers += { ok(1, life = 8 * min) }
        c.prefetch(far)
        assertFalse("the same token back is no refresh", c.prefetchDue(true))
        clock += 2 * min
        assertTrue(c.prefetchDue(true))
        answers += { ok(2, life = 60 * min) }
        assertEquals("FAKE-ACCESS-2", (c.prefetch(far) as TokenResult.Ok).token)
        assertFalse(c.prefetchDue(true))
        assertEquals("FAKE-ACCESS-2", (c.get(far) as TokenResult.Ok).token)
        assertEquals(4, asks.size)
        // Every prefetch failing until the old token expires: then nothing is due; the next get asks.
        clock += 50 * min
        answers += { TokenResult.Unreachable }
        c.prefetch(far)
        clock += 10 * min + 1
        assertFalse(c.prefetchDue(true))
        assertNull(c.nextPrefetchAtMs())
        answers += { ok(3) }
        assertEquals("FAKE-ACCESS-3", (c.get(far) as TokenResult.Ok).token)
    }

    @Test
    fun `the same token back is told by its value, not its expiry (qa-26c-step4-code L3)`() = runBlocking {
        val answers = mutableListOf<() -> TokenResult>({ ok(1, life = 12 * min) })
        val c = TokenCache(this, ask = { asks += clock; answers.removeAt(0)() }, elapsedMs = { clock })
        val first = c.get(far) as TokenResult.Ok
        clock += 2 * min // 10 minutes left
        assertTrue(c.prefetchDue(true))
        answers += { TokenResult.Ok("FAKE-ACCESS-1", first.expiresAtMs + 1_000, "2:1") } // whole seconds and latency: a second "later"
        c.prefetch(far)
        assertFalse("the same token: no refresh, tried again in 2 minutes", c.prefetchDue(true))
        assertEquals(clock + 2 * min, c.nextPrefetchAtMs())
        assertEquals("the earlier estimate of its expiry kept", first.expiresAtMs, (c.get(far, minLeftMs = 1) as TokenResult.Ok).expiresAtMs)
        clock += 2 * min
        answers += { TokenResult.Ok("FAKE-ACCESS-2", first.expiresAtMs - 1_000, "2:1") }
        c.prefetch(far)
        assertEquals("another token is a refresh, whatever its expiry says", "FAKE-ACCESS-2", (c.get(far, minLeftMs = 1) as TokenResult.Ok).token)
    }

    @Test
    fun `never a token in a toString, and each answer's problem`() {
        val ok = TokenResult.Ok("FAKE-ACCESS-7", 1, "2:1")
        assertFalse(ok.toString().contains("FAKE-ACCESS-7"))
        assertNull(ok.problem())
        assertEquals(SpotifyProblem.OFF, TokenResult.Off.problem())
        assertEquals(SpotifyProblem.REAUTHORIZE, TokenResult.Reauthorize.problem())
        assertEquals(SpotifyProblem.UNREACHABLE, TokenResult.Busy(1).problem())
        assertEquals(SpotifyProblem.UNREACHABLE, TokenResult.Unreachable.problem())
        assertEquals(5 * 60_000L, TokenCache.MIN_LEFT_MS)
        assertEquals(10 * 60_000L, TokenCache.PREFETCH_LEFT_MS)
        assertEquals(2 * 60_000L, TokenCache.PREFETCH_RETRY_MS)
        assertEquals(10 * 60_000L, TokenCache.REAUTHORIZE_COOLDOWN_MS)
        assertEquals(5_000L, TokenCache.PROMPT_RETRY_MS)
    }
}
