package com.kidslauncher.mdm

import com.kidslauncher.mdm.server.Attempt
import com.kidslauncher.mdm.server.Budget
import com.kidslauncher.mdm.server.CallMarks
import com.kidslauncher.mdm.server.DotLink
import com.kidslauncher.mdm.server.DotPool
import com.kidslauncher.mdm.server.LEAVE_TAILNET_COMMAND
import com.kidslauncher.mdm.server.OddKind
import com.kidslauncher.mdm.server.TSNET_DEATH_WINDOW_MS
import com.kidslauncher.mdm.server.crashStreakAfter
import com.kidslauncher.mdm.server.deathAfterConnectIsCrash
import com.kidslauncher.mdm.server.dotAnswers
import com.kidslauncher.mdm.server.dotAttempt
import com.kidslauncher.mdm.server.leaveTailnetAnswer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
import android.app.ApplicationExitInfo
import java.io.EOFException
import java.io.File
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

    private fun src(path: String) = listOf(File("src/main/$path"), File("app/src/main/$path")).first { it.exists() }

    @Test
    fun `S-L10 - a death soon after a tsnet connect counts toward the crash guard, Android's kills don't`() {
        val at = 1_793_000_000_000L
        assertTrue(deathAfterConnectIsCrash(at, at + 60_000, ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertTrue("Go's exit(2)", deathAfterConnectIsCrash(at, at + 60_000, ApplicationExitInfo.REASON_EXIT_SELF))
        assertTrue("an abort", deathAfterConnectIsCrash(at, at + TSNET_DEATH_WINDOW_MS, ApplicationExitInfo.REASON_SIGNALED))
        assertFalse("past the window", deathAfterConnectIsCrash(at, at + TSNET_DEATH_WINDOW_MS + 1, ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertFalse("a death before this connect", deathAfterConnectIsCrash(at, at - 1, ApplicationExitInfo.REASON_CRASH_NATIVE))
        for (kill in listOf(
            ApplicationExitInfo.REASON_LOW_MEMORY, ApplicationExitInfo.REASON_USER_REQUESTED,
            ApplicationExitInfo.REASON_PACKAGE_UPDATED, ApplicationExitInfo.REASON_OTHER,
        )) {
            assertFalse("$kill", deathAfterConnectIsCrash(at, at + 1_000, kill))
        }
        assertFalse("no record", deathAfterConnectIsCrash(at, null, null))
        assertFalse("no connect", deathAfterConnectIsCrash(0, at, ApplicationExitInfo.REASON_CRASH_NATIVE))

        // The streak: a connect that never returned or died soon after adds one; one that lived on ends it.
        assertEquals(2, crashStreakAfter(1, wasLeftPending = true, lastConnect = null))
        assertEquals(2, crashStreakAfter(1, wasLeftPending = false, lastConnect = true))
        assertEquals(0, crashStreakAfter(1, wasLeftPending = false, lastConnect = false))
        assertEquals(1, crashStreakAfter(1, wasLeftPending = false, lastConnect = null))
        // A connect that returned no longer ends the streak by itself (the old finally reset it to 0).
        val client = src("java/com/kidslauncher/mdm/server/TsnetClient.kt").readText()
        val finally = client.substringAfter("connect(hostname, authKey, stateDir)").substringAfter("} finally {").substringBefore("\n        }")
        assertFalse(finally.contains("CRASH_COUNT_KEY"))
        assertTrue(finally.contains("CONNECT_RETURNED_AT_KEY"))
    }

    @Test
    fun `S-L10 - leave_tailnet's answers are the shared contract's`() {
        val contract = javaClass.classLoader!!.getResource("leave_tailnet.json")!!.readText()
        val parsed = Json.parseToJsonElement(contract).jsonObject
        assertEquals(LEAVE_TAILNET_COMMAND, parsed.getValue("command").jsonPrimitive.content)
        val rows = parsed.getValue("answers").jsonArray.map {
            it.jsonObject.getValue("success").jsonPrimitive.boolean to it.jsonObject.getValue("message").jsonPrimitive.content
        }
        assertEquals(rows, listOf(leaveTailnetAnswer(true, true), leaveTailnetAnswer(false, true), leaveTailnetAnswer(true, false)))
        assertEquals(leaveTailnetAnswer(true, false), leaveTailnetAnswer(false, false))
        // The sync runs it.
        val sync = src("java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        assertTrue(sync.contains("LEAVE_TAILNET_COMMAND -> {\n            val (ok, message) = TsnetClient.leave(context)"))
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/leave_tailnet.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }
}
