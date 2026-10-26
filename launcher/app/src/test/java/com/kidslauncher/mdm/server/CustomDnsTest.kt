package com.kidslauncher.mdm.server

import java.net.InetAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.ARecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type

/** Design 36 §5: the glue around the chooser - where each query goes, what is relayed, and the
 * bootstrap look-ups - with a fake transport and clock (documentation names and addresses only).
 * The scope is `Unconfined`, so a look-up runs inline when it starts. */
class CustomDnsTest {
    private val primaryDoh = UpstreamTarget.Doh("https://192.0.2.10/dns-query/abc123")
    private val backupDot = UpstreamTarget.Dot("192.0.2.20", 853)
    private val cloudflareDoh = UpstreamTarget.Doh("https://1.1.1.1/dns-query")
    private val request = Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN))
    private val query = request.toWire()

    private fun answer(rcode: Int, id: Int = request.header.id) = Message(id).apply { header.rcode = rcode }.toWire()
    private val backupAnswer = answer(Rcode.NOERROR, id = 7)

    /** What the fake transport was asked: the target and the question. */
    private data class Ask(val target: UpstreamTarget, val name: String, val type: Int)

    private class Calls {
        val asks = mutableListOf<Ask>()
        val targets get() = asks.filter { it.name == "example.com." }.map { it.target }
        val canaries get() = asks.count { it.type == Type.NS }
        fun lookups(name: String) = asks.filter { it.name == "$name." }.map { it.target to it.type }
    }

    private fun Message.isCanary() = question.type == Type.NS

    /** A look-up's answer: [name] -> [address], TTL 600. */
    private fun lookupAnswer(m: Message, address: String): Attempt {
        val reply = Message(m.header.id).apply {
            addRecord(m.question, Section.QUESTION)
            if (m.question.type == Type.A) addRecord(ARecord(m.question.name, DClass.IN, 600, InetAddress.getByName(address)), Section.ANSWER)
        }
        return Attempt.Answer(reply.toWire())
    }

    private fun dns(
        primary: UpstreamTarget = primaryDoh,
        backup: UpstreamTarget = backupDot,
        clock: () -> Long = { 1_000 },
        calls: Calls = Calls(),
        cacheFile: java.io.File? = null,
        answer: (UpstreamTarget, Message) -> Attempt,
    ) = CustomDns(
        CustomUpstream(primary, backup, rev = 4),
        protect = { true },
        scope = CoroutineScope(Dispatchers.Unconfined),
        elapsed = clock,
        log = {},
        transport = { target: UpstreamTarget, _: List<InetAddress>, bytes: ByteArray, _: Budget ->
            val m = Message(bytes)
            synchronized(calls) { calls.asks += Ask(target, m.question.name.toString(), m.question.type) }
            answer(target, m)
        },
        cloudflare = cloudflareResolver(clock, {}),
        cacheFile = cacheFile,
    )

    @Test
    fun `a socket our own VPN refused counts against no server`() {
        // The primary: dropped, the state untouched, the backup never asked.
        val calls = Calls()
        val d = dns(calls = calls) { _, _ -> Attempt.Unprotected }
        val before = d.state()
        repeat(3) { assertNull(d.resolve(query, request).bytes) }
        assertEquals(before, d.state())
        assertEquals(List(3) { primaryDoh }, calls.targets)
        // The canary: no verdict, so an odd answer's own outcome stands and nothing is counted.
        val odd = dns { _, m -> if (m.isCanary()) Attempt.Unprotected else Attempt.Odd(OddKind.SERVFAIL, null) }
        val start = odd.state()
        assertEquals(Rcode.SERVFAIL, Message(odd.resolve(query, request).bytes!!).rcode)
        assertEquals(start, odd.state())
    }

    @Test
    fun `a primary answer is relayed and the backup never asked`() {
        val calls = Calls()
        val relayed = answer(Rcode.NXDOMAIN)
        val d = dns(calls = calls) { _, _ -> Attempt.Answer(relayed) }
        assertArrayEquals(relayed, d.resolve(query, request).bytes)
        assertEquals(listOf<UpstreamTarget>(primaryDoh), calls.targets)
    }

    @Test
    fun `a transport failure goes to the configured backup, whose failure drops the query and leaves the state`() {
        for (backup in listOf(backupDot, UpstreamTarget.Doh("https://[2001:db8::20]/dns-query"))) {
            val calls = Calls()
            var backupWorks = true
            var now = 1_000L
            val d = dns(backup = backup, calls = calls, clock = { now }) { target, _ ->
                when {
                    target == primaryDoh -> Attempt.Transport(BackupReason.CONNECT)
                    backupWorks -> Attempt.Answer(backupAnswer)
                    else -> Attempt.Transport(BackupReason.TLS)
                }
            }
            val first = d.resolve(query, request)
            assertArrayEquals(backupAnswer, first.bytes)
            assertEquals(ReachKind.ANSWERED, reachKind(first))
            assertEquals(listOf(primaryDoh, backup), calls.targets)
            val period = d.state()
            assertTrue(period.backup)
            assertEquals(BackupReason.CONNECT, period.reason)
            // In the period the backup answers alone; when it fails too, the query is dropped: both
            // failed, an outage's query (design 67 §3.1).
            backupWorks = false
            assertEquals(ReachKind.FAILED, reachKind(d.resolve(query, request)))
            assertEquals(listOf(primaryDoh, backup, backup), calls.targets)
            assertEquals(period, d.state())
            // The back-off's probe fails and so does the backup: the same.
            now += UpstreamChooser.BACKOFF_BASE_MS
            assertEquals(ReachKind.FAILED, reachKind(d.resolve(query, request)))
            assertEquals(listOf(primaryDoh, backup, backup, primaryDoh, backup), calls.targets)
        }
    }

    @Test
    fun `an exception in our code drops the query as a failure`() {
        val d = dns { _, _ -> throw IllegalStateException("a bug") }
        assertEquals(ReachKind.FAILED, reachKind(d.resolve(query, request)))
    }

    @Test
    fun `REFUSED from a healthy server is the phone's NODATA, never the backup`() {
        val calls = Calls()
        val d = dns(calls = calls) { _, m ->
            if (m.isCanary()) Attempt.Answer(answer(Rcode.NOERROR)) else Attempt.Odd(OddKind.REFUSED, answer(Rcode.REFUSED))
        }
        val resolved = d.resolve(query, request)
        // The healthy canary proved the server alive (design 67 §3.1).
        assertEquals(ReachKind.ANSWERED, reachKind(resolved))
        val blocked = Message(resolved.bytes!!)
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
        val d = dns(calls = calls, clock = { now }) { _, m ->
            if (m.isCanary()) Attempt.Answer(answer(Rcode.NOERROR)) else Attempt.Odd(OddKind.TIMEOUT, null)
        }
        assertEquals(Rcode.SERVFAIL, Message(d.resolve(query, request).bytes!!).rcode)
        now += 59_000
        assertEquals(Rcode.SERVFAIL, Message(d.resolve(query, request).bytes!!).rcode)
        assertEquals(1, calls.canaries)
        now += 1_000
        d.resolve(query, request).bytes
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
        assertArrayEquals(backupAnswer, d.resolve(query, request).bytes)
        assertEquals(listOf(primaryDoh, backupDot), calls.targets)
        assertEquals(BackupReason.REFUSED, d.state().reason)
        assertEquals("backup", d.report(ReachState()).inUse)
        assertEquals("refused", d.report(ReachState()).reason)
    }

    @Test
    fun `a query that gives up waiting for another's canary answers alone and changes nothing`() {
        val canaryStarted = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d = dns { _, m ->
            if (m.isCanary()) {
                canaryStarted.countDown()
                release.await(10, TimeUnit.SECONDS)
                Attempt.Transport(BackupReason.CONNECT)
            } else {
                Attempt.Odd(OddKind.REFUSED, answer(Rcode.REFUSED))
            }
        }
        val first = thread { d.resolve(query, request).bytes }
        assertTrue(canaryStarted.await(5, TimeUnit.SECONDS))
        // The second query waits at most its canary budget + 0.5 s, then answers its own REFUSED as NODATA.
        val gaveUp = d.resolve(query, request)
        // Our own answer, which proves nothing about the server (design 67 §3.1).
        assertEquals(ReachKind.NEITHER, reachKind(gaveUp))
        val waited = Message(gaveUp.bytes!!)
        assertEquals(Rcode.NOERROR, waited.rcode)
        assertEquals(1, waited.getSection(Section.AUTHORITY).size)
        assertFalse(d.state().backup)
        release.countDown()
        first.join(5_000)
        // The canary's own failure is what starts the period.
        assertTrue(d.state().backup)
    }

    @Test
    fun `a network reset sends the next query to the primary again`() {
        val calls = Calls()
        var primaryUp = false
        val d = dns(calls = calls) { target, _ ->
            if (target == primaryDoh && !primaryUp) Attempt.Transport(BackupReason.CONNECT) else Attempt.Answer(backupAnswer)
        }
        d.resolve(query, request).bytes
        assertTrue(d.state().backup)
        primaryUp = true
        d.networkReset()
        d.resolve(query, request).bytes
        assertEquals(listOf(primaryDoh, backupDot, primaryDoh), calls.targets)
        assertFalse(d.state().backup)
        d.close()
    }

    @Test
    fun `a home-IP primary away from home, a backup by name, nothing cached - the look-up falls through and the backup answers`() {
        val homeDot = UpstreamTarget.Dot("192.0.2.10", 853)
        val familyDoh = UpstreamTarget.Doh("https://family.dns.example/dns-query")
        val calls = Calls()
        val d = dns(primary = homeDot, backup = familyDoh, calls = calls) { target, m ->
            when {
                target == homeDot -> Attempt.Transport(BackupReason.CONNECT)
                target == CLOUDFLARE_BOOTSTRAP -> lookupAnswer(m, "192.0.2.30")
                else -> Attempt.Answer(backupAnswer)
            }
        }
        d.start()
        // A through the primary (unreachable), then Cloudflare; AAAA skips the dead step.
        assertEquals(
            listOf(homeDot to Type.A, CLOUDFLARE_BOOTSTRAP to Type.A, CLOUDFLARE_BOOTSTRAP to Type.AAAA),
            calls.lookups("family.dns.example"),
        )
        assertArrayEquals(backupAnswer, d.resolve(query, request).bytes)
        assertEquals(listOf(homeDot, familyDoh), calls.targets)
        assertEquals(BackupReason.CONNECT, d.state().reason)
    }

    @Test
    fun `a working or answering bootstrap resolver is never passed by`() {
        val homeDot = UpstreamTarget.Dot("192.0.2.10", 853)
        val familyDoh = UpstreamTarget.Doh("https://family.dns.example/dns-query")
        for (nxdomain in listOf(false, true)) {
            val calls = Calls()
            val d = dns(primary = homeDot, backup = familyDoh, calls = calls) { _, m ->
                if (nxdomain) Attempt.Answer(answer(Rcode.NXDOMAIN, m.header.id)) else lookupAnswer(m, "192.0.2.30")
            }
            d.start()
            assertEquals(listOf(homeDot to Type.A, homeDot to Type.AAAA), calls.lookups("family.dns.example"))
        }
    }

    @Test
    fun `the primary's name - looked up at start, a miss goes to the backup, nothing at all is a bootstrap period`() {
        val named = UpstreamTarget.Dot("abc123.dns.example", 853)
        val calls = Calls()
        var lookupsWork = false
        var now = 1_000L
        val d = dns(primary = named, calls = calls, clock = { now }) { target, m ->
            when {
                m.question.name.toString() == "abc123.dns.example." ->
                    if (lookupsWork) lookupAnswer(m, "192.0.2.40") else Attempt.Transport(BackupReason.CONNECT)
                target == named -> Attempt.Answer(answer(Rcode.NOERROR))
                else -> Attempt.Answer(backupAnswer)
            }
        }
        // Both resolvers fail (Cloudflare's DoH too): the start's look-up gives nothing, a bootstrap period starts.
        d.start()
        assertEquals(
            listOf(backupDot to Type.A, CLOUDFLARE_BOOTSTRAP to Type.A, cloudflareDoh to Type.A),
            calls.lookups("abc123.dns.example"),
        )
        assertEquals(BackupReason.BOOTSTRAP, d.state().reason)
        val failures = d.state().failures
        assertArrayEquals(backupAnswer, d.resolve(query, request).bytes)
        // The back-off ends; the probe finds no address: the look-up runs again (30 s passed), the
        // query goes to the backup, the probe slot is given back and the period doesn't step.
        now += 61_000
        lookupsWork = true
        assertArrayEquals(backupAnswer, d.resolve(query, request).bytes)
        assertFalse(d.state().probing)
        assertEquals(failures, d.state().failures)
        // Now the address is in hand: the next query probes the primary and ends the period.
        assertArrayEquals(answer(Rcode.NOERROR), d.resolve(query, request).bytes)
        assertFalse(d.state().backup)
        assertEquals(listOf(backupDot, backupDot, named), calls.targets)
    }

    @Test
    fun `on a network that blocks 853 the bootstrap's Cloudflare step asks its DoH on 443`() {
        val homeDot = UpstreamTarget.Dot("192.0.2.10", 853)
        val familyDoh = UpstreamTarget.Doh("https://family.dns.example/dns-query")
        val calls = Calls()
        val d = dns(primary = homeDot, backup = familyDoh, calls = calls) { target, m ->
            when (target) {
                homeDot, CLOUDFLARE_BOOTSTRAP -> Attempt.Transport(BackupReason.CONNECT)
                cloudflareDoh -> lookupAnswer(m, "192.0.2.30")
                else -> Attempt.Answer(backupAnswer)
            }
        }
        d.start()
        assertEquals(
            listOf(
                homeDot to Type.A, CLOUDFLARE_BOOTSTRAP to Type.A, cloudflareDoh to Type.A,
                CLOUDFLARE_BOOTSTRAP to Type.AAAA, cloudflareDoh to Type.AAAA,
            ),
            calls.lookups("family.dns.example"),
        )
        // The backup's address came over 443: it answers.
        assertArrayEquals(backupAnswer, d.resolve(query, request).bytes)
    }

    @Test
    fun `the disk cache - written after a look-up, read before any look-up by the next instance, none for IP literals`() {
        val dir = java.nio.file.Files.createTempDirectory("dns-cache").toFile()
        val file = java.io.File(dir, BootstrapDiskCache.FILE_NAME)
        val named = UpstreamTarget.Doh("https://family.dns.example/dns-query")
        var now = 1_000L
        val calls = Calls()
        dns(backup = named, clock = { now }, calls = calls, cacheFile = file) { _, m -> lookupAnswer(m, "192.0.2.30") }.start()
        assertTrue(file.readText().contains("family.dns.example\t"))
        assertFalse(java.io.File(dir, file.name + ".tmp").exists())
        // A restart (a new instance, as after a reboot): the address is in hand, no look-up.
        val again = Calls()
        now = 2_000
        val d = dns(backup = named, clock = { now }, calls = again, cacheFile = file) { _, _ -> Attempt.Answer(backupAnswer) }
        d.start()
        assertTrue(again.lookups("family.dns.example").isEmpty())
        // Both servers IP literals: nothing to look up, nothing kept.
        dns(cacheFile = file) { _, _ -> Attempt.Answer(backupAnswer) }.start()
        assertFalse(file.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `the disk cache keeps only this config's names, and a closed instance never writes it`() {
        val dir = java.nio.file.Files.createTempDirectory("dns-cache").toFile()
        val file = java.io.File(dir, BootstrapDiskCache.FILE_NAME)
        val named = UpstreamTarget.Doh("https://family.dns.example/dns-query")
        val one = listOf(InetAddress.getByName("192.0.2.30"))
        val far = System.currentTimeMillis() + 3_600_000
        file.writeText("v1\nfamily.dns.example\t$far\t192.0.2.30\nold.dns.example\t$far\t192.0.2.31\n")
        dns(backup = named, cacheFile = file) { _, _ -> Attempt.Answer(backupAnswer) }
        assertTrue(file.readText().contains("family.dns.example\t"))
        assertFalse(file.readText().contains("old.dns.example"))
        // Nothing usable in it: it goes.
        file.writeText("not a cache")
        dns(backup = named, cacheFile = file) { _, _ -> Attempt.Answer(backupAnswer) }
        assertFalse(file.exists())
        // A look-up still running when its instance is closed (new servers, a Forget) writes nothing.
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val d = dns(backup = named, cacheFile = file) { _, m ->
            started.countDown()
            release.await(5, TimeUnit.SECONDS)
            lookupAnswer(m, one.single().hostAddress!!)
        }
        val lookUp = thread { d.start() }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        d.close()
        release.countDown()
        lookUp.join(5_000)
        assertFalse(file.exists())
        dir.deleteRecursively()
    }

    @Test
    fun `a backup by name with no address drops the query, the state untouched`() {
        val named = UpstreamTarget.Doh("https://family.dns.example/dns-query")
        val calls = Calls()
        val d = dns(backup = named, calls = calls) { target, m ->
            when {
                m.question.name.toString() == "family.dns.example." -> Attempt.Transport(BackupReason.CONNECT)
                target == primaryDoh -> Attempt.Transport(BackupReason.TLS)
                else -> Attempt.Answer(backupAnswer)
            }
        }
        d.start()
        assertNull(d.resolve(query, request).bytes)
        assertEquals(listOf<UpstreamTarget>(primaryDoh), calls.targets)
        assertEquals(BackupReason.TLS, d.state().reason)
    }
}
