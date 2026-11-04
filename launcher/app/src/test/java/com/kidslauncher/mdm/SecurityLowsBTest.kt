package com.kidslauncher.mdm

import com.kidslauncher.mdm.server.Attempt
import com.kidslauncher.mdm.server.Budget
import com.kidslauncher.mdm.server.CallMarks
import com.kidslauncher.mdm.server.DotLink
import com.kidslauncher.mdm.server.DotPool
import com.kidslauncher.mdm.server.OddKind
import com.kidslauncher.mdm.server.dotAnswers
import com.kidslauncher.mdm.server.dotAttempt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Record
import org.xbill.DNS.Type
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** The launcher's security Lows, the rest (security review 2026-10-21, round L4b): one property pinned per fix. */
class SecurityLowsBTest {
    private val server = InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 53))
    private val key = DotPool.Key(server, 853, "dns.example")
    private val query = Message.newQuery(Record.newRecord(Name.fromString("a.example."), Type.A, DClass.IN)).toWire()
    private val answer = Message(query).apply { header.setFlag(Flags.QR.toInt()) }.toWire()

    /** A DoT connection that answers by [reply] (or throws), and remembers being cut. */
    private class FakeLink(private val reply: (ByteArray) -> ByteArray) : DotLink {
        var cut = false
        var asked = 0
        override fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray {
            asked++
            marks.written = true
            return reply(query)
        }
        override fun cut() { cut = true }
    }

    private fun attempt(pool: DotPool?, opened: MutableList<FakeLink>, budget: Budget = Budget(100, 500), next: () -> FakeLink) =
        dotAttempt(query, listOf(server), budget, pool, { key }, log = {}) { _, _, marks ->
            marks.connected = true
            next().also { opened += it }
        }

    @Test
    fun `S-L9 - a DoT connection that answered is kept and asked again, one the server closed is replaced`() {
        val pool = DotPool()
        val opened = mutableListOf<FakeLink>()
        assertTrue(attempt(pool, opened) { FakeLink { answer } } is Attempt.Answer)
        assertTrue(attempt(pool, opened) { FakeLink { answer } } is Attempt.Answer)
        assertEquals("the second query rides the first connection", 1, opened.size)
        assertEquals(2, opened[0].asked)
        assertEquals(1, pool.size())

        // The server closed it while idle: the query is asked again on a fresh connection, which is kept instead.
        val gone = FakeLink { throw EOFException() }
        pool.evictAll()
        pool.give(key, gone)
        assertTrue(attempt(pool, opened) { FakeLink { answer } } is Attempt.Answer)
        assertTrue(gone.cut)
        assertEquals(2, opened.size)
        assertEquals(1, pool.size())

        // An answer to another query (out of step) or garbage is never kept.
        val other = answer.copyOf().also { it[1] = (it[1] + 1).toByte() }
        assertFalse(dotAnswers(query, other))
        assertFalse(dotAnswers(query, answer.copyOf(11)))
        assertTrue(dotAnswers(query, answer))
        pool.evictAll()
        val garbage = FakeLink { ByteArray(12).also { it[5] = 1 } } // one question promised, none there
        assertTrue(attempt(pool, opened) { garbage } is Attempt.Transport)
        assertTrue(garbage.cut)
        assertEquals(0, pool.size())

        // Without a pool (tests, a stopped VPN) every connection is closed after its answer.
        val once = FakeLink { answer }
        assertTrue(attempt(null, opened) { once } is Attempt.Answer)
        assertTrue(once.cut)
    }

    @Test
    fun `S-L9 - a kept connection silent to the deadline is silence, not a retry`() {
        val pool = DotPool()
        val released = CountDownLatch(1)
        val silent = object : DotLink {
            override fun ask(query: ByteArray, remainingMs: () -> Int, marks: CallMarks): ByteArray {
                marks.written = true
                released.await(5, TimeUnit.SECONDS)
                throw IOException("closed")
            }
            override fun cut() = released.countDown()
        }
        pool.give(key, silent)
        val opened = mutableListOf<FakeLink>()
        val outcome = attempt(pool, opened, Budget(50, 80)) { FakeLink { answer } }
        assertTrue("$outcome", outcome is Attempt.Odd && outcome.kind == OddKind.TIMEOUT)
        assertEquals("no fresh connection past the deadline", 0, opened.size)
        assertEquals(0, pool.size())
    }

    @Test
    fun `S-L9 - the pool keeps a few connections a short while and nothing once closed`() {
        var now = 0L
        val pool = DotPool(idleMs = 10_000L, maxIdle = 2, nowMs = { now })
        val links = List(3) { FakeLink { answer } }
        links.forEach { pool.give(key, it) }
        assertTrue("the oldest past the cap is closed", links[0].cut)
        assertSame("the newest goes first", links[2], pool.take(key))
        assertNull(pool.take(DotPool.Key(server, 853, "other.example")))
        now = 10_000L
        assertNull("idle too long", pool.take(key))
        assertTrue(links[1].cut)
        val last = FakeLink { answer }
        pool.close()
        pool.give(key, last)
        assertTrue(last.cut)
        assertEquals(0, pool.size())
    }
}
