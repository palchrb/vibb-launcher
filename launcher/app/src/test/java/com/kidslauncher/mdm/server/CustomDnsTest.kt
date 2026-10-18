package com.kidslauncher.mdm.server

import java.net.InetAddress
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/** Design 36 §5: the glue around the chooser - where each query goes and what is relayed - with a
 * fake transport and clock (documentation addresses only, so no look-up ever runs). */
class CustomDnsTest {
    private val primaryDoh = UpstreamTarget.Doh("https://192.0.2.10/dns-query/abc123")
    private val backupDot = UpstreamTarget.Dot("192.0.2.20", 853)
    private val request = Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN))
    private val query = request.toWire()

    private fun answer(rcode: Int, id: Int = request.header.id) = Message(id).apply { header.rcode = rcode }.toWire()
    private val backupAnswer = answer(Rcode.NOERROR, id = 7)

    private class Calls {
        val targets = mutableListOf<UpstreamTarget>()
        var canaries = 0
    }

    private fun dns(
        primary: UpstreamTarget = primaryDoh,
        backup: UpstreamTarget = backupDot,
        clock: () -> Long = { 1_000 },
        calls: Calls = Calls(),
        answer: (UpstreamTarget, Boolean) -> Attempt,
    ) = CustomDns(
        CustomUpstream(primary, backup, rev = 4),
        protect = { true },
        scope = CoroutineScope(Dispatchers.Unconfined),
        elapsed = clock,
        log = {},
        transport = { target: UpstreamTarget, _: List<InetAddress>, bytes: ByteArray, _: Budget ->
            val canary = Message(bytes).question.type == Type.NS
            if (canary) calls.canaries++ else calls.targets += target
            answer(target, canary)
        },
    )

    @Test
    fun `a primary answer is relayed and the backup never asked`() {
        val calls = Calls()
        val relayed = answer(Rcode.NXDOMAIN)
        val d = dns(calls = calls) { _, _ -> Attempt.Answer(relayed) }
        assertArrayEquals(relayed, d.resolve(query, request))
        assertEquals(listOf<UpstreamTarget>(primaryDoh), calls.targets)
    }

    @Test
    fun `a transport failure goes to the configured backup, whose failure drops the query and leaves the state`() {
        for (backup in listOf(backupDot, UpstreamTarget.Doh("https://[2001:db8::20]/dns-query"))) {
            val calls = Calls()
            var backupWorks = true
            val d = dns(backup = backup, calls = calls) { target, _ ->
                when {
                    target == primaryDoh -> Attempt.Transport(BackupReason.CONNECT)
                    backupWorks -> Attempt.Answer(backupAnswer)
                    else -> Attempt.Transport(BackupReason.TLS)
                }
            }
            assertArrayEquals(backupAnswer, d.resolve(query, request))
            assertEquals(listOf(primaryDoh, backup), calls.targets)
            val period = d.state()
            assertTrue(period.backup)
            assertEquals(BackupReason.CONNECT, period.reason)
            // In the period the backup answers alone; when it fails too, the query is dropped.
            backupWorks = false
            assertNull(d.resolve(query, request))
            assertEquals(listOf(primaryDoh, backup, backup), calls.targets)
            assertEquals(period, d.state())
        }
    }

    @Test
    fun `REFUSED from a healthy server is the phone's NODATA, never the backup`() {
        val calls = Calls()
        val d = dns(calls = calls) { _, canary ->
            if (canary) Attempt.Answer(answer(Rcode.NOERROR)) else Attempt.Odd(OddKind.REFUSED, answer(Rcode.REFUSED))
        }
        val blocked = Message(d.resolve(query, request)!!)
        assertEquals(Rcode.NOERROR, blocked.rcode)
        assertEquals(request.header.id, blocked.header.id)
        assertEquals(0, blocked.getSection(Section.ANSWER).size)
        assertEquals(1, blocked.getSection(Section.AUTHORITY).size)
        assertEquals(listOf<UpstreamTarget>(primaryDoh), calls.targets)
        assertFalse(d.state().backup)
    }

    @Test
    fun `silence after the request from a healthy server is a SERVFAIL, and the canary is reused`() {
        val calls = Calls()
        var now = 1_000L
        val d = dns(calls = calls, clock = { now }) { _, canary ->
            if (canary) Attempt.Answer(answer(Rcode.NOERROR)) else Attempt.Odd(OddKind.TIMEOUT, null)
        }
        assertEquals(Rcode.SERVFAIL, Message(d.resolve(query, request)!!).rcode)
        now += 59_000
        assertEquals(Rcode.SERVFAIL, Message(d.resolve(query, request)!!).rcode)
        assertEquals(1, calls.canaries)
        now += 1_000
        d.resolve(query, request)
        assertEquals(2, calls.canaries)
        assertEquals(listOf(primaryDoh, primaryDoh, primaryDoh), calls.targets)
        assertFalse(d.state().backup)
    }

    @Test
    fun `REFUSED with a refused canary - an id the server doesn't allow - goes to the backup`() {
        val calls = Calls()
        val d = dns(calls = calls) { target, _ ->
            if (target == primaryDoh) Attempt.Odd(OddKind.REFUSED, answer(Rcode.REFUSED)) else Attempt.Answer(backupAnswer)
        }
        assertArrayEquals(backupAnswer, d.resolve(query, request))
        assertEquals(listOf(primaryDoh, backupDot), calls.targets)
        assertEquals(BackupReason.REFUSED, d.state().reason)
        assertEquals("backup", d.report().inUse)
        assertEquals("refused", d.report().reason)
    }

    @Test
    fun `a network reset sends the next query to the primary again`() {
        val calls = Calls()
        var primaryUp = false
        val d = dns(calls = calls) { target, _ ->
            if (target == primaryDoh && !primaryUp) Attempt.Transport(BackupReason.CONNECT) else Attempt.Answer(backupAnswer)
        }
        d.resolve(query, request)
        assertTrue(d.state().backup)
        primaryUp = true
        d.networkReset()
        d.resolve(query, request)
        assertEquals(listOf(primaryDoh, backupDot, primaryDoh), calls.targets)
        assertFalse(d.state().backup)
        d.close()
    }
}
