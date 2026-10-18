package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.DotBlockedRule.afterDotFailure
import com.kidslauncher.mdm.server.DotBlockedRule.afterDotWorked
import com.kidslauncher.mdm.server.DotBlockedRule.route
import java.io.File
import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Type

/** Design 36 step 3: DoH on 443 for the public resolvers when 853 is blocked - the pure rule, the
 * same provider's DoH (never the other's), and never a guessed DoH URL for a parent's server. */
class PublicResolverTest {
    private val query = Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN)).toWire()
    private val answer = Message(7).apply { header.rcode = Rcode.NOERROR }.toWire()
    private val blocked = Attempt.Transport(BackupReason.CONNECT)

    @Test
    fun `three DoT failures within a minute start a DoH period, failures spread out don't`() {
        var s = DotBlocked()
        s = afterDotFailure(s, 0, probe = false)
        s = afterDotFailure(s, 30_000, probe = false)
        assertFalse(s.doh)
        // The window started at the first failure: a third after it ends counts as the first again.
        s = afterDotFailure(s, 60_000, probe = false)
        assertFalse(s.doh)
        assertEquals(1, s.failures)
        s = afterDotFailure(s, 61_000, probe = false)
        s = afterDotFailure(s, 62_000, probe = false)
        assertTrue(s.doh)
        assertEquals(62_000 + DotBlockedRule.BACKOFF_BASE_MS, s.dohUntilMs)
        // DoT that works clears everything.
        assertEquals(DotBlocked(), afterDotWorked(afterDotFailure(DotBlocked(), 0, false)))
    }

    @Test
    fun `one probe per period, a failed probe doubles the period to the one-hour cap`() {
        var s = DotBlocked(doh = true, periods = 1, dohUntilMs = 300_000)
        assertEquals(DotRoute.DOH, route(s, 299_999).first)
        val periods = mutableListOf<Long>()
        repeat(6) {
            val now = s.dohUntilMs
            val (probe, probing) = route(s, now)
            assertEquals(DotRoute.PROBE, probe)
            // While the probe runs, everyone else stays on DoH; a late plain DoT failure changes nothing.
            assertEquals(DotRoute.DOH, route(probing, now).first)
            assertEquals(probing, afterDotFailure(probing, now, probe = false))
            s = afterDotFailure(probing, now, probe = true)
            periods += (s.dohUntilMs - now) / 60_000
        }
        assertEquals(listOf(10L, 20, 40, 60, 60, 60), periods)
        // A probe that gets through ends the period.
        assertEquals(DotBlocked(), afterDotWorked(route(s, s.dohUntilMs).second))
    }

    @Test
    fun `Cloudflare and Quad9 each fall back to their own DoH on 443, never the other's`() {
        for ((resolver, url) in listOf(
            cloudflareResolver({ 0 }, {}) to "https://1.1.1.1/dns-query",
            quad9Resolver({ 0 }, {}) to "https://9.9.9.9/dns-query",
        )) {
            val asked = mutableListOf<UpstreamTarget>()
            val budgets = mutableListOf<Budget>()
            val transport = { target: UpstreamTarget, addresses: List<InetAddress>, _: ByteArray, budget: Budget ->
                asked += target
                budgets += budget
                assertEquals(listOf(resolver.address), addresses)
                if (target is UpstreamTarget.Dot) blocked else Attempt.Answer(answer)
            }
            // Each failed DoT query is answered over DoH at once; the third failure starts the period.
            repeat(3) {
                val got = resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport) as Attempt.Answer
                assertArrayEquals(answer, got.bytes)
            }
            assertTrue(resolver.state().doh)
            // In the period DoT isn't tried at all.
            resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport)
            assertEquals(List(3) { listOf(resolver.dot, UpstreamTarget.Doh(url)) }.flatten() + UpstreamTarget.Doh(url), asked)
            assertTrue(asked.filterIsInstance<UpstreamTarget.Doh>().all { it.url == url })
            assertEquals(PROVIDER_BUDGET, budgets[0])
            assertEquals(UPSTREAM_BUDGET, budgets[1])
            // A network reset tries DoT first again.
            resolver.reset()
            assertEquals(DotBlocked(), resolver.state())
        }
    }

    @Test
    fun `a probe gets a short DoT budget, and a slow DoT failure leaves no time for DoH`() {
        var now = 0L
        val resolver = cloudflareResolver({ now }, {})
        val budgets = mutableListOf<Pair<UpstreamTarget, Budget>>()
        var dotTakes = 0L
        val transport = { target: UpstreamTarget, _: List<InetAddress>, _: ByteArray, budget: Budget ->
            budgets += target to budget
            if (target is UpstreamTarget.Dot) { now += dotTakes; blocked } else Attempt.Answer(answer)
        }
        // A dropped SYN: 5 s gone, the query is dropped (the stub retries) and counted.
        dotTakes = 5_000
        assertEquals(blocked, resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport))
        assertEquals(1, budgets.size)
        repeat(2) { resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport) }
        assertTrue(resolver.state().doh)
        // The period's probe: DoT within at most UPSTREAM_BUDGET, then DoH inside the same deadline.
        now = resolver.state().dohUntilMs
        dotTakes = 2_500
        budgets.clear()
        assertTrue(resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport) is Attempt.Answer)
        assertEquals(listOf<UpstreamTarget>(resolver.dot, resolver.doh), budgets.map { it.first })
        assertEquals(UPSTREAM_BUDGET, budgets[0].second)
        assertEquals(2, resolver.state().periods)
    }

    @Test
    fun `a dropped connect at the providers' 2_5 s still gets DoH in time, under the floor it doesn't`() {
        // The providers' connect must leave the floor for DoH inside the deadline (M2).
        assertTrue(PROVIDER_BUDGET.connectMs + BACKUP_FLOOR_MS <= QUERY_DEADLINE_MS)
        for ((dotTakes, dohAsked) in listOf(PROVIDER_BUDGET.connectMs.toLong() to true, 4_000L to false)) {
            var now = 0L
            val resolver = quad9Resolver({ now }, {})
            val asked = mutableListOf<Pair<UpstreamTarget, Budget>>()
            val got = resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET) { target, _, _, budget ->
                asked += target to budget
                if (target is UpstreamTarget.Dot) { now += dotTakes; blocked } else Attempt.Answer(answer)
            }
            assertEquals(dohAsked, got is Attempt.Answer)
            assertEquals(if (dohAsked) listOf<UpstreamTarget>(resolver.dot, resolver.doh) else listOf(resolver.dot), asked.map { it.first })
            if (dohAsked) assertEquals(Budget(1500, 2300), asked[1].second)
        }
    }

    @Test
    fun `in a DoH period a blocked DoH tries DoT in the same query, and DoT that works ends the period`() {
        val resolver = cloudflareResolver({ 0 }, {})
        val asked = mutableListOf<UpstreamTarget>()
        var dotWorks = false
        val transport = { target: UpstreamTarget, _: List<InetAddress>, _: ByteArray, _: Budget ->
            asked += target
            when {
                target is UpstreamTarget.Doh -> if (dotWorks) blocked else Attempt.Answer(answer)
                dotWorks -> Attempt.Answer(answer)
                else -> blocked
            }
        }
        repeat(3) { resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport) }
        assertTrue(resolver.state().doh)
        // Now the network blocks 443 to the provider but not 853 (and a transport that throws counts as a failure).
        dotWorks = true
        asked.clear()
        assertTrue(resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport) is Attempt.Answer)
        assertEquals(listOf<UpstreamTarget>(resolver.doh, resolver.dot), asked)
        assertEquals(DotBlocked(), resolver.state())
        val thrown = resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET) { _, _, _, _ -> throw IllegalStateException() }
        assertEquals(Attempt.Transport(BackupReason.CONNECT), thrown)
    }

    @Test
    fun `under Custom a parent's DoT server never gets a DoH URL, and only two DoH URLs exist`() {
        val primary = UpstreamTarget.Dot("192.0.2.10", 853)
        val backup = UpstreamTarget.Dot("192.0.2.20", 853)
        val asked = mutableListOf<UpstreamTarget>()
        val d = CustomDns(
            CustomUpstream(primary, backup, rev = 1),
            protect = { true },
            scope = CoroutineScope(Dispatchers.Unconfined),
            elapsed = { 1_000 },
            log = {},
            transport = { target: UpstreamTarget, _: List<InetAddress>, _: ByteArray, _: Budget ->
                asked += target
                blocked
            },
            cloudflare = cloudflareResolver({ 1_000 }, {}),
        )
        d.start()
        repeat(5) { d.resolve(query, Message(query)) }
        assertEquals(listOf<UpstreamTarget>(primary, backup), asked.distinct())
        // Structurally: a DoH target is built only from the parent's URL or the two public resolvers'.
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val built = File(src, "main/java").walkTopDown().filter { it.extension == "kt" }
            .flatMap { file -> Regex("""UpstreamTarget\.Doh\(([^)]*)\)""").findAll(file.readText()).map { "${file.name}: ${it.groupValues[1]}" } }
            .toList()
        assertEquals(
            listOf("CustomUpstream.kt: it", "PublicResolver.kt: \"https://1.1.1.1/dns-query\"", "PublicResolver.kt: \"https://9.9.9.9/dns-query\""),
            built.sorted(),
        )
    }

    @Test
    fun `the public resolvers' DoH client is the protected one and never looks a name up`() {
        val vpn = File(listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }, "main/java/com/kidslauncher/mdm/server/KidVpnService.kt").readText()
        assertTrue("buildDohClient(IP_LITERAL_DNS, ::protect)" in vpn)
        // Built at the first DoH use, not the first provider query (N2).
        assertTrue("resolveProvider(query, upstream, ::protect) { providerDoh.value }" in vpn)
        // A network reset puts both providers back on DoT (L2: the reset is wired).
        val onNetwork = vpn.substringAfter("private fun onNetwork(").substringBefore("\n    }\n")
        val changed = onNetwork.substringAfter("if (changed) {")
        assertTrue("PublicResolvers.reset()" in changed && "custom?.networkReset()" in changed)
        assertEquals(listOf(InetAddress.getByName("192.0.2.1")), IP_LITERAL_DNS.lookup("192.0.2.1"))
        try {
            IP_LITERAL_DNS.lookup("dns.example")
            fail("a name was resolved")
        } catch (e: UnknownHostException) {
            // never the system resolver
        }
    }
}
