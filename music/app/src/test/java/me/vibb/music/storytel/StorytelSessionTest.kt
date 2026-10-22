package me.vibb.music.storytel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import me.vibb.music.bridge.JwtResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server's Storytel session on the phone (design 58 §5.1-§5.3, §8): one shared ask, a late answer kept,
 * the prompt retry and the hold, each answer's wait with no ask inside it, the generation, Storytel's 401
 * (a different JWT retried once, a declined force's wait, the same JWT back), the room's prefetch and its
 * backoff. The clocks are virtual (a sleep moves them); only a waiter's own timeout runs in real time (1 s).
 */
class StorytelSessionTest {
    private val min = 60_000L
    private var clock = 1_000_000L
    private var wall = 1_800_000_000_000L
    private var gen = 1L

    /** Each ask's `stale`, in order. */
    private val asks = mutableListOf<String?>()
    private val slept = mutableListOf<Long>()

    /** Each asset call's JWT, in order. */
    private val assets = mutableListOf<String>()

    private fun session(n: Int) = "1:%016x".format(n)

    private fun ok(n: Int, life: Long = 59 * min) = JwtResult.Ok("FAKE-JWT-$n", clock + life, session(n))

    /** A session over [answers] in order (the last repeats); a sleep moves both clocks. */
    private fun session(scope: CoroutineScope, vararg answers: suspend () -> JwtResult) = StorytelSession(
        generation = { gen },
        ask = { stale -> asks += stale; answers.getOrElse(asks.size - 1) { answers.last() }() },
        elapsedMs = { clock },
        wallMs = { wall },
        scope = scope,
        sleep = { slept += it; clock += it; wall += it },
    )

    /** The asset call: [refused] JWTs answer 401, every other one a URL. */
    private fun asset(vararg refused: String): suspend (String) -> Mint = { jwt ->
        assets += jwt
        if (jwt in refused) Mint.Failed(401) else Mint.Url("https://cdn.example.invalid/x?token=FAKE-SIG-${assets.size}")
    }

    private suspend fun StorytelSession.download(asset: suspend (String) -> Mint = asset()) = mint(15_000, clock + 60_000, asset)
    private suspend fun StorytelSession.room(asset: suspend (String) -> Mint = asset()) = mint(6_000, clock + 10_000, asset)

    @Test
    fun `one ask at a time - a download and a room share the ask in flight`() = runBlocking {
        val gate = CompletableDeferred<JwtResult>()
        val s = session(this, { gate.await() })
        val a = async { s.download() }
        val b = async { s.room() }
        yield()
        yield()
        gate.complete(ok(1))
        assertTrue(a.await() is Mint.Url)
        assertTrue(b.await() is Mint.Url)
        assertEquals("one ask", listOf<String?>(null), asks)
        assertEquals(listOf("FAKE-JWT-1", "FAKE-JWT-1"), assets)
        assertTrue("then the kept JWT: no ask", s.room() is Mint.Url)
        assertEquals(1, asks.size)
    }

    @Test
    fun `a late answer is kept for the next mint, and a cancelled waiter's ask goes on (QA 58-6)`() = runBlocking {
        val gate = CompletableDeferred<JwtResult>()
        val s = session(this, { gate.await() })
        // a room whose budget leaves 1 s to wait: the waiter gives up, the ask runs on
        val m = s.mint(6_000, clock + 7_000, asset())
        assertEquals(Mint.LoginFailed("unreachable", wall + StorytelSession.COME_BACK_MS), m)
        assertTrue("no asset call without a JWT", assets.isEmpty())
        gate.complete(ok(1))
        yield()
        assertTrue("the answer was kept", s.room() is Mint.Url)
        assertEquals(1, asks.size)

        // a download job WorkManager stops: its cancellation goes up, the ask's answer is kept
        asks.clear()
        val gate2 = CompletableDeferred<JwtResult>()
        val t = session(this, { gate2.await() })
        val job = async { t.download() }
        yield()
        job.cancel()
        assertTrue(runCatching { job.await() }.exceptionOrNull() is CancellationException)
        gate2.complete(ok(2))
        yield()
        assertTrue(t.room() is Mint.Url)
        assertEquals(listOf<String?>(null), asks)
    }

    @Test
    fun `an ask whose scope ended is an outage, never the caller's cancellation (a debug rebuild)`() = runBlocking {
        val job = SupervisorJob()
        val started = CompletableDeferred<Unit>()
        val s = session(CoroutineScope(job + Dispatchers.Default), { started.complete(Unit); CompletableDeferred<JwtResult>().await() })
        val m = async { s.download() }
        started.await()
        job.cancel()
        val result = m.await()
        assertTrue("$result", result is Mint.LoginFailed && result.reason == "unreachable")
    }

    @Test
    fun `under a second to wait no ask starts`() = runBlocking {
        val s = session(this, { ok(1) })
        assertEquals(Mint.LoginFailed("deadline", wall + StorytelSession.COME_BACK_MS), s.mint(6_000, clock + 6_999, asset()))
        assertTrue(asks.isEmpty())
        assertTrue(s.mint(6_000, clock + 7_000, asset()) is Mint.Url)
    }

    @Test
    fun `the prompt retry - an unreachable asked once more 5 s later when it fits, then the 30 s hold (QA 58-2, 58-3)`() = runBlocking {
        val s = session(this, { JwtResult.Unreachable("unreachable") }, { ok(1) })
        assertTrue(s.download() is Mint.Url)
        assertEquals("one more ask", 2, asks.size)
        assertEquals(listOf(StorytelSession.PROMPT_RETRY_MS), slept)
        assertEquals(listOf("FAKE-JWT-1"), assets)

        asks.clear(); slept.clear()
        val once = session(this, { JwtResult.Unreachable("launcher") })
        val m = once.download()
        assertEquals("once only", Mint.LoginFailed("unreachable", wall + StorytelSession.COME_BACK_MS), m)
        assertEquals(2, asks.size)
        assertEquals(listOf(5_000L), slept)

        asks.clear(); slept.clear()
        val room = session(this, { JwtResult.Unreachable("unreachable") }, { ok(1) })
        assertTrue("a room's 4 s don't fit it", room.room() is Mint.LoginFailed)
        assertEquals(1, asks.size)
        assertTrue(slept.isEmpty())
        clock += StorytelSession.HOLD_MS - 1
        assertEquals("the hold: no ask", Mint.LoginFailed("unreachable", wall + StorytelSession.COME_BACK_MS), room.room())
        assertEquals("ok", room.state())
        assertEquals(1, asks.size)
        clock += 1
        assertTrue(room.room() is Mint.Url)
        assertEquals(2, asks.size)
    }

    @Test
    fun `each answer's wait - off, refused, busy and key - with no ask inside it`() = runBlocking {
        val off = session(this, { JwtResult.Off }, { ok(1) })
        assertEquals(Mint.NotConfigured, off.download())
        assertEquals("none", off.state())
        clock += StorytelSession.OFF_WAIT_MS - 1
        assertEquals(Mint.NotConfigured, off.room())
        assertEquals(1, asks.size)
        clock += 1
        assertTrue(off.room() is Mint.Url)
        assertEquals("ok", off.state())

        asks.clear()
        val refused = session(this, { JwtResult.Refused(40 * min) }, { ok(1) })
        assertEquals(Mint.Refused(wall + 40 * min), refused.download())
        assertEquals("refused", refused.state())
        clock += 39 * min
        wall += 39 * min
        assertEquals("no prompt retry, no ask inside", Mint.Refused(wall + min), refused.download())
        assertEquals(1, asks.size)
        assertTrue(slept.isEmpty())
        clock += min
        assertTrue(refused.download() is Mint.Url)
        assertEquals("ok", refused.state())

        asks.clear()
        val busy = session(this, { JwtResult.Busy(30_000) }, { ok(1) })
        assertEquals(Mint.LoginFailed("busy", wall + 30_000), busy.download())
        assertEquals("busy is no refusal", "ok", busy.state())
        clock += 29_999
        assertEquals(Mint.LoginFailed("busy", wall + 1), busy.room())
        assertEquals(1, asks.size)
        clock += 1
        assertTrue(busy.room() is Mint.Url)

        asks.clear()
        val key = session(this, { JwtResult.Unreachable("key") }, { ok(1) })
        assertEquals(Mint.LoginFailed("key", wall + StorytelSession.KEY_WAIT_MS), key.download())
        assertEquals("no prompt retry for the key", 1, asks.size)
        clock += StorytelSession.KEY_WAIT_MS - 1
        assertTrue(key.download() is Mint.LoginFailed)
        assertEquals(1, asks.size)
        clock += 1
        assertTrue(key.download() is Mint.Url)
        assertTrue(slept.isEmpty())
    }

    @Test
    fun `a new generation drops the JWT and every wait - generation 0 is off with no ask`() = runBlocking {
        val s = session(this, { ok(1) }, { ok(2) })
        assertTrue(s.room() is Mint.Url)
        gen = 2
        assertTrue(s.room() is Mint.Url)
        assertEquals("a parent's save: asked again", listOf("FAKE-JWT-1", "FAKE-JWT-2"), assets)
        gen = 0
        assertEquals(Mint.NotConfigured, s.room())
        assertEquals("none", s.state())
        assertEquals(2, asks.size)
        gen = 2
        assertTrue(s.room() is Mint.Url)
        assertEquals("the JWT went at generation 0", 3, asks.size)

        asks.clear()
        gen = 1
        val refused = session(this, { JwtResult.Refused(60 * min) }, { JwtResult.Off }, { ok(3) })
        assertTrue(refused.room() is Mint.Refused)
        gen = 2
        assertEquals("a new login: the cooldown is the old one's", "ok", refused.state())
        assertEquals(Mint.NotConfigured, refused.room())
        gen = 3
        assertTrue("off went with the generation too", refused.room() is Mint.Url)
        assertEquals(3, asks.size)
    }

    @Test
    fun `a 401 - another JWT retried once, the ask naming the refused session`() = runBlocking {
        val s = session(this, { ok(1) }, { ok(2) })
        assertTrue(s.download(asset("FAKE-JWT-1")) is Mint.Url)
        assertEquals(listOf(null, session(1)), asks)
        assertEquals(listOf("FAKE-JWT-1", "FAKE-JWT-2"), assets)
        assertTrue("the new one is kept", s.room() is Mint.Url)
        assertEquals(2, asks.size)

        // a second 401 on the new JWT: Failed(401), and the next mint asks first (qa-26b-step1-code L4)
        asks.clear(); assets.clear()
        val twice = session(this, { ok(1) }, { ok(2) }, { ok(3) })
        assertEquals(Mint.Failed(401), twice.download(asset("FAKE-JWT-1", "FAKE-JWT-2")))
        assertEquals(listOf("FAKE-JWT-1", "FAKE-JWT-2"), assets)
        assertTrue(twice.download(asset("FAKE-JWT-1", "FAKE-JWT-2")) is Mint.Url)
        assertEquals(listOf(null, session(1), null), asks)
    }

    @Test
    fun `a declined force is its wait - the refused JWT dropped (QA N-1)`() = runBlocking {
        val s = session(this, { ok(1) }, { JwtResult.Busy(20_000) }, { ok(2) })
        val m = s.download(asset("FAKE-JWT-1"))
        assertEquals(Mint.LoginFailed("busy", wall + 20_000), m)
        assertEquals(listOf("FAKE-JWT-1"), assets)
        assertEquals("inside the wait: no ask, and never the refused JWT", Mint.LoginFailed("busy", wall + 20_000), s.room(asset("FAKE-JWT-1")))
        assertEquals(1, assets.size)
        clock += 20_000
        assertTrue(s.room(asset("FAKE-JWT-1")) is Mint.Url)
        assertEquals(listOf(null, session(1), null), asks)

        asks.clear(); assets.clear()
        val refused = session(this, { ok(1) }, { JwtResult.Refused(30 * min) })
        assertEquals(Mint.Refused(wall + 30 * min), refused.download(asset("FAKE-JWT-1")))
        assertEquals("refused", refused.state())
        assertEquals(Mint.Refused(wall + 30 * min), refused.room(asset("FAKE-JWT-1")))
        assertEquals(1, assets.size)
    }

    @Test
    fun `the same JWT back for a force is Failed(401) at once, with no second request (a belt)`() = runBlocking {
        val s = session(this, { ok(1) }, { ok(1) }, { ok(2) })
        assertEquals(Mint.Failed(401), s.download(asset("FAKE-JWT-1")))
        assertEquals("no second asset call", listOf("FAKE-JWT-1"), assets)
        assertTrue("dropped: the next mint asks", s.room(asset("FAKE-JWT-1")) is Mint.Url)
        assertEquals(listOf(null, session(1), null), asks)
    }

    @Test
    fun `a 401 whose JWT another mint already replaced takes the new one without an ask`() = runBlocking {
        val s = session(this, { ok(1) }, { ok(2) })
        var inner: Mint? = null
        var first = true
        val outer = s.download { jwt ->
            assets += jwt
            when {
                jwt != "FAKE-JWT-1" -> Mint.Url("https://cdn.example.invalid/x?token=FAKE-SIG-1")
                first -> {
                    first = false
                    // a concurrent mint meets the same 401 first and gets JWT 2
                    inner = s.room(asset("FAKE-JWT-1"))
                    Mint.Failed(401)
                }
                else -> Mint.Failed(401)
            }
        }
        assertTrue(outer is Mint.Url)
        assertTrue(inner is Mint.Url)
        assertEquals(listOf(null, session(1)), asks)
        assertEquals(listOf("FAKE-JWT-1", "FAKE-JWT-1", "FAKE-JWT-2", "FAKE-JWT-2"), assets)
    }

    @Test
    fun `a force while a prefetch is in flight waits for it, then asks with the stale session`() = runBlocking {
        val gate = CompletableDeferred<JwtResult>()
        val s = session(this, { ok(1) }, { gate.await() }, { ok(2) })
        assertTrue(s.room() is Mint.Url)
        clock += 50 * min
        val prefetch = launch { s.prefetchIfDue() }
        while (asks.size < 2) yield()
        val m = async { s.download(asset("FAKE-JWT-1")) }
        yield()
        gate.complete(ok(1)) // the server's kept JWT, the one Storytel just refused
        assertTrue(m.await() is Mint.Url)
        prefetch.join()
        assertEquals(listOf(null, null, session(1)), asks)
        assertEquals(listOf("FAKE-JWT-1", "FAKE-JWT-1", "FAKE-JWT-2"), assets)
    }

    @Test
    fun `the prefetch - due at min(10 min, life over 3) left while a room plays, then the advance asks nothing (QA 58-1)`() = runBlocking {
        val s = session(this, { ok(1, 60 * min) }, { ok(2, 60 * min) })
        s.prefetchIfDue()
        assertEquals("none kept: due", 1, asks.size)
        s.prefetchIfDue()
        assertEquals("fresh: not due", 1, asks.size)
        clock += 50 * min
        s.prefetchIfDue()
        assertEquals("10 minutes left: not yet", 1, asks.size)
        clock += 1
        s.prefetchIfDue()
        assertEquals(2, asks.size)
        s.prefetchIfDue()
        assertEquals("a new JWT: not again", 2, asks.size)
        assertTrue(s.room() is Mint.Url)
        assertEquals("the advance's mint makes no ask", listOf("FAKE-JWT-2"), assets)
        assertEquals(2, asks.size)

        // life / 3 below 10 minutes
        asks.clear()
        val short = session(this, { ok(1, 6 * min) }, { ok(2, 6 * min) })
        short.room()
        clock += 4 * min
        short.prefetchIfDue()
        assertEquals("2 minutes left of 6: not yet", 1, asks.size)
        clock += 1
        short.prefetchIfDue()
        assertEquals(2, asks.size)
    }

    @Test
    fun `never a prefetch for a life of 240 s or less, nor twice at once (QA N-6)`() = runBlocking {
        val s = session(this, { ok(1, StorytelSession.PREFETCH_MIN_LIFE_MS) })
        s.room()
        clock += StorytelSession.PREFETCH_MIN_LIFE_MS - 1
        s.prefetchIfDue()
        assertEquals(1, asks.size)
        clock += 1
        s.prefetchIfDue()
        assertEquals("expired: none kept, so due", 2, asks.size)

        asks.clear()
        val gate = CompletableDeferred<JwtResult>()
        val t = session(this, { gate.await() })
        val first = launch { t.prefetchIfDue() }
        while (asks.isEmpty()) yield()
        t.prefetchIfDue()
        assertEquals("one in flight: not twice", 1, asks.size)
        gate.complete(ok(1))
        first.join()
    }

    @Test
    fun `a failed prefetch or the same JWT back waits 2, 4, then 8 minutes, and a new JWT resets it (QA N-10)`() = runBlocking {
        val s = session(
            this,
            { ok(1, 120 * min) },
            { JwtResult.Unreachable("unreachable") },
            { ok(1, 70 * min) },
            { JwtResult.Unreachable("unreachable") },
            { JwtResult.Unreachable("unreachable") },
            { ok(2, 120 * min) },
            { JwtResult.Unreachable("unreachable") },
        )
        s.room()
        clock += 110 * min + 1
        for ((n, wait) in listOf(2 to 2 * min, 3 to 4 * min, 4 to 8 * min, 5 to 8 * min)) {
            s.prefetchIfDue()
            assertEquals(n, asks.size)
            clock += wait - 1
            s.prefetchIfDue()
            assertEquals("ask $n waits ${wait / min} minutes", n, asks.size)
            clock += 1
        }
        s.prefetchIfDue()
        assertEquals("JWT 2", 6, asks.size)
        clock += 110 * min + 1
        s.prefetchIfDue()
        assertEquals(7, asks.size)
        clock += 2 * min
        s.prefetchIfDue()
        assertEquals("reset: 2 minutes again", 8, asks.size)
    }

    @Test
    fun `no prefetch inside a wait or the hold, and none while off`() = runBlocking {
        val s = session(this, { ok(1) }, { JwtResult.Busy(5 * min) }, { ok(2) })
        s.room()
        clock += 50 * min
        s.prefetchIfDue()
        assertEquals(2, asks.size)
        clock += 4 * min
        s.prefetchIfDue()
        assertEquals("busy's 5 minutes", 2, asks.size)
        clock += min
        s.prefetchIfDue()
        assertEquals(3, asks.size)

        asks.clear()
        val held = session(this, { JwtResult.Unreachable("unreachable") }, { ok(1) })
        held.room()
        held.prefetchIfDue()
        assertEquals("the hold", 1, asks.size)

        asks.clear()
        gen = 0
        val off = session(this, { ok(1) })
        off.prefetchIfDue()
        assertEquals(Mint.NotConfigured, off.room())
        off.prefetchIfDue()
        assertTrue(asks.isEmpty())
    }

    @Test
    fun `the JWT never prints`() {
        val jwt = StorytelSession.Jwt("FAKE-JWT-1", 1, 1, session(1), 1)
        assertFalse(jwt.toString().contains("FAKE-JWT"))
        assertFalse(jwt.toString().contains(session(1)))
    }
}
