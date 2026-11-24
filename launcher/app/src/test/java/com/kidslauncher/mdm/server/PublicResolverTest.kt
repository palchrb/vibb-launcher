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
    fun `each provider falls back to its own DoH on 443, never another's`() {
        for ((resolver, url) in listOf(
            cloudflareResolver({ 0 }, {}) to "https://security.cloudflare-dns.com/dns-query",
            quad9Resolver({ 0 }, {}) to "https://dns.quad9.net/dns-query",
        )) {
            val asked = mutableListOf<UpstreamTarget>()
            val budgets = mutableListOf<Budget>()
            val dotAt = mutableListOf<InetAddress>()
            val transport = { target: UpstreamTarget, addresses: List<InetAddress>, _: ByteArray, budget: Budget ->
                asked += target
                budgets += budget
                // DoT one address at a time; DoH every address (OkHttp tries them in turn).
                if (target is UpstreamTarget.Dot) dotAt += addresses.single() else assertEquals(resolver.addresses, addresses)
                if (target is UpstreamTarget.Dot) blocked else Attempt.Answer(answer)
            }
            // Each failed DoT query (every address refused at once) is answered over DoH at once, and counts once;
            // the third failure starts the period.
            repeat(3) {
                val got = resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport) as Attempt.Answer
                assertArrayEquals(answer, got.bytes)
                assertEquals(it < 2, !resolver.state().doh)
            }
            assertTrue(resolver.state().doh)
            // In the period DoT isn't tried at all.
            resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport)
            val n = resolver.addresses.size
            assertEquals(4, n)
            assertEquals(List(3) { List(n) { resolver.dot } + UpstreamTarget.Doh(url) }.flatten() + UpstreamTarget.Doh(url), asked)
            assertEquals(List(3) { resolver.addresses }.flatten(), dotAt)
            assertTrue(asked.filterIsInstance<UpstreamTarget.Doh>().all { it.url == url })
            assertEquals(Budget(PROVIDER_BUDGET.connectMs, QUERY_DEADLINE_MS.toInt()), budgets[0])
            // The next address only within what still leaves DoH its floor.
            assertEquals(Budget(PROVIDER_BUDGET.connectMs, (QUERY_DEADLINE_MS - BACKUP_FLOOR_MS).toInt()), budgets[1])
            assertEquals(UPSTREAM_BUDGET, budgets[n])
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
    fun `under Custom a parent's DoT server never gets a DoH URL, and only three DoH URLs exist`() {
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
            providers = listOf(quad9Resolver({ 1_000 }, {})),
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
            listOf(
                "CustomUpstream.kt: it", "PublicResolver.kt: \"https://dns.quad9.net/dns-query\"",
                "PublicResolver.kt: \"https://security.cloudflare-dns.com/dns-query\"",
            ),
            built.sorted(),
        )
    }

    @Test
    fun `the public resolvers' DoH client is the protected one and never looks a name up`() {
        val vpn = File(listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }, "main/java/com/kidslauncher/mdm/server/KidVpnService.kt").readText()
        assertTrue("buildDohClient(PINNED_DNS, ::protect)" in vpn)
        // Built at the first DoH use, not the first provider query (N2).
        assertTrue("resolveProvider(query, upstream, ::protect, dotPool) { providerDoh.value }" in vpn)
        // A network reset puts both providers back on DoT (L2: the reset is wired).
        val onNetwork = vpn.substringAfter("private fun onNetwork(").substringBefore("\n    }\n")
        val changed = onNetwork.substringAfter("if (changed) {")
        assertTrue("PublicResolvers.reset()" in changed && "custom?.networkReset()" in changed)
        // S-L9: the kept DoT connections ran over the old network.
        assertTrue("dotPool::evictAll" in changed)
        assertEquals(listOf(InetAddress.getByName("192.0.2.1")), PINNED_DNS.lookup("192.0.2.1"))
        // A provider's name is its fixed addresses, never looked up: Cloudflare's malware-blocking resolver and Quad9's
        // "Recommended" service, each with both IPv4 addresses first, then both IPv6.
        assertEquals(
            listOf("1.1.1.2", "1.0.0.2", "2606:4700:4700::1112", "2606:4700:4700::1002").map(InetAddress::getByName),
            PINNED_DNS.lookup("security.cloudflare-dns.com"),
        )
        assertEquals(
            listOf("9.9.9.9", "149.112.112.112", "2620:fe::fe", "2620:fe::9").map(InetAddress::getByName),
            PINNED_DNS.lookup("dns.quad9.net"),
        )
        assertEquals(CLOUDFLARE_ADDRESSES, cloudflareResolver({ 0 }, {}).addresses)
        assertEquals(QUAD9_ADDRESSES, quad9Resolver({ 0 }, {}).addresses)
        try {
            PINNED_DNS.lookup("dns.example")
            fail("a name was resolved")
        } catch (e: UnknownHostException) {
            // never the system resolver
        }
    }

    @Test
    fun `the parent's choice first, then the other - Quad9 by default, Mullvad is no provider`() {
        fun labels(p: String?) = PublicResolvers.order(p).map { it.label }
        assertEquals(listOf("quad9", "cloudflare"), labels(null))
        assertEquals(listOf("quad9", "cloudflare"), labels("bogus"))
        assertEquals(listOf("quad9", "cloudflare"), labels("mullvad"))
        assertEquals(listOf("cloudflare", "quad9"), labels("cloudflare"))
        assertEquals(listOf("quad9"), PublicResolvers.bootstrap.map { it.label })
    }

    @Test
    fun `a provider that doesn't answer is skipped for a while, the next answers, a probe brings it back`() {
        var now = 0L
        val order = listOf(quad9Resolver({ now }, {}), cloudflareResolver({ now }, {}))
        val asked = mutableListOf<String>()
        val answered = mutableListOf<String>()
        var quad9Up = false
        val transport = { target: UpstreamTarget, _: List<InetAddress>, _: ByteArray, _: Budget ->
            val host = target.serverHost
            asked += host
            if (host == "dns.quad9.net") { if (quad9Up) Attempt.Answer(answer) else blocked } else Attempt.Answer(answer)
        }
        fun ask() = resolveChain(order, query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport, { now }) { answered += it }
        // Quad9 refused (DoT at every address, then its own DoH): the same query goes on to Cloudflare.
        assertTrue(ask() is Attempt.Answer)
        assertEquals(List(QUAD9_ADDRESSES.size + 1) { "dns.quad9.net" } + "security.cloudflare-dns.com", asked)
        assertEquals(listOf("cloudflare"), answered)
        // Skipped while down.
        asked.clear()
        now = ProviderChainRule.BACKOFF_BASE_MS - 1
        ask()
        assertEquals(listOf("security.cloudflare-dns.com"), asked)
        // Due: one query probes it; it answers and is first again.
        quad9Up = true
        asked.clear()
        now = ProviderChainRule.BACKOFF_BASE_MS
        ask()
        assertEquals(listOf("dns.quad9.net"), asked)
        assertEquals(ProviderChainRule.UP, order[0].down.get())
        assertEquals("quad9", answered.last())
    }

    @Test
    fun `the back-off doubles to its cap, only the probe counts, and with every provider down the first is still asked`() {
        var s = ProviderDown()
        s = ProviderChainRule.afterFailure(s, 0, probe = false)
        assertEquals(ProviderDown(untilMs = 60_000, periods = 1), s)
        // A second query in flight failing too counts once.
        assertEquals(s, ProviderChainRule.afterFailure(s, 10, probe = false))
        val periods = mutableListOf<Long>()
        repeat(6) {
            val (ask, probing) = ProviderChainRule.ask(s, s.untilMs)
            assertTrue(ask)
            assertFalse(ProviderChainRule.ask(probing, s.untilMs).first)
            val at = s.untilMs
            s = ProviderChainRule.afterFailure(probing, at, probe = true)
            periods += (s.untilMs - at) / 60_000
        }
        assertEquals(listOf(2L, 4, 8, 15, 15, 15), periods)
        val order = listOf(quad9Resolver({ 0 }, {}), cloudflareResolver({ 0 }, {}))
        order.forEach { it.down.set(ProviderDown(untilMs = 1_000_000, periods = 3)) }
        val asked = mutableListOf<String>()
        resolveChain(order, query, PROVIDER_BUDGET, UPSTREAM_BUDGET, { t, _, _, _ -> asked += t.serverHost; Attempt.Answer(answer) }, { 0 })
        assertEquals(listOf("dns.quad9.net"), asked)
        assertEquals(ProviderChainRule.UP, order[0].down.get())
    }

    @Test
    fun `after a reset a provider is tried by one query at a time until it answers - the others go on or wait`() {
        var now = 0L
        val order = listOf(cloudflareResolver({ now }, {}), quad9Resolver({ now }, {}))
        order.forEach { it.down.set(ProviderChainRule.UP) }
        order.forEach(PublicResolver::reset)
        assertEquals(ProviderChainRule.UNPROVEN, order[0].down.get())
        val asked = mutableListOf<String>()
        val transport = { t: UpstreamTarget, _: List<InetAddress>, _: ByteArray, _: Budget -> asked += t.serverHost; Attempt.Answer(answer) }
        // Another query is finding out whether Cloudflare answers: this one goes to Quad9, not into the same wait.
        order[0].down.set(ProviderChainRule.UNPROVEN.copy(probing = true))
        resolveChain(order, query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport, { now })
        assertEquals(listOf("dns.quad9.net"), asked)
        // Every one being found out: the query waits, then takes the first that answered.
        order.forEach { it.down.set(ProviderChainRule.UNPROVEN.copy(probing = true)) }
        asked.clear()
        var waits = 0
        resolveChain(order, query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport, { now }, pause = { ms ->
            now += ms
            if (++waits == 2) order[1].down.set(ProviderChainRule.UP)
        })
        assertEquals(2, waits)
        assertEquals(listOf("dns.quad9.net"), asked)
        // A provider that never answers: only the query that probed it pays.
        assertEquals(ProviderDown(untilMs = 60_000, periods = 1), ProviderChainRule.afterFailure(ProviderChainRule.UNPROVEN.copy(probing = true), 0, probe = true))
        assertEquals(ProviderChainRule.UNPROVEN, ProviderChainRule.afterFailure(ProviderChainRule.UNPROVEN, 0, probe = false))
    }

    @Test
    fun `a blocked address isn't the provider failing - the next address answers, and later queries start there`() {
        var now = 0L
        val resolver = quad9Resolver({ now }, {})
        val (nine, second) = QUAD9_ADDRESSES
        val asked = mutableListOf<Pair<UpstreamTarget, List<InetAddress>>>()
        var dropMs = 0L
        val transport = { target: UpstreamTarget, addresses: List<InetAddress>, _: ByteArray, _: Budget ->
            asked += target to addresses
            when {
                target is UpstreamTarget.Doh -> Attempt.Answer(answer)
                addresses.single() == nine -> { now += dropMs; blocked }
                else -> Attempt.Answer(answer)
            }
        }
        fun ask() = resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport)
        // Refused at once: the same query's DoT goes on to the next address, which answers - nothing counted.
        assertTrue(ask() is Attempt.Answer)
        assertEquals(listOf<Pair<UpstreamTarget, List<InetAddress>>>(QUAD9_DOT to listOf(nine), QUAD9_DOT to listOf(second)), asked)
        assertEquals(DotBlocked(), resolver.state())
        assertEquals(second, resolver.preferredAddress())
        // The next query starts at the address that answered.
        asked.clear()
        ask()
        assertEquals(listOf<Pair<UpstreamTarget, List<InetAddress>>>(QUAD9_DOT to listOf(second)), asked)
        // A network reset starts from the first again.
        resolver.reset()
        assertEquals(nine, resolver.preferredAddress())
        // Dropped (the whole connect gone): no time for another DoT address, the query's DoH (every address) answers;
        // the next query's DoT starts at the second address, which answers and clears the count.
        dropMs = PROVIDER_BUDGET.connectMs.toLong()
        asked.clear()
        assertTrue(ask() is Attempt.Answer)
        assertEquals(listOf(QUAD9_DOT to listOf(nine), resolver.doh to QUAD9_ADDRESSES), asked)
        assertEquals(1, resolver.state().failures)
        asked.clear()
        ask()
        assertEquals(listOf<Pair<UpstreamTarget, List<InetAddress>>>(QUAD9_DOT to listOf(second)), asked)
        assertEquals(DotBlocked(), resolver.state())
    }

    @Test
    fun `queries in flight failing at the same address count once`() {
        var now = 0L
        val resolver = quad9Resolver({ now }, {})
        var inFlight = true
        lateinit var transport: (UpstreamTarget, List<InetAddress>, ByteArray, Budget) -> Attempt
        transport = { target, _, _, _ ->
            if (target is UpstreamTarget.Dot) {
                // A second query fails at the same address while this one waits on it.
                if (inFlight) {
                    inFlight = false
                    resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport)
                }
                now += PROVIDER_BUDGET.connectMs
                blocked
            } else {
                Attempt.Answer(answer)
            }
        }
        resolver.resolve(query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport)
        assertEquals(1, resolver.state().failures)
        assertEquals(QUAD9_ADDRESSES[1], resolver.preferredAddress())
    }

    @Test
    fun `with one Quad9 address blocked Quad9 still answers - Cloudflare only when every Quad9 address fails`() {
        val order = listOf(quad9Resolver({ 0 }, {}), cloudflareResolver({ 0 }, {}))
        val answered = mutableListOf<String>()
        val hosts = mutableListOf<String>()
        var blockedHere = setOf(QUAD9_ADDRESSES[0])
        val transport = { target: UpstreamTarget, addresses: List<InetAddress>, _: ByteArray, _: Budget ->
            hosts += target.serverHost
            val reachable = addresses.filterNot { it in blockedHere }
            if (reachable.isEmpty()) blocked else Attempt.Answer(answer)
        }
        assertTrue(resolveChain(order, query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport, { 0 }) { answered += it } is Attempt.Answer)
        assertEquals(listOf("quad9"), answered)
        assertEquals(listOf("dns.quad9.net", "dns.quad9.net"), hosts)
        assertEquals(ProviderChainRule.UP, order[0].down.get())
        // Every Quad9 address blocked, on 853 and 443: only then the query goes on to Cloudflare.
        blockedHere = QUAD9_ADDRESSES.toSet()
        hosts.clear()
        assertTrue(resolveChain(order, query, PROVIDER_BUDGET, UPSTREAM_BUDGET, transport, { 0 }) { answered += it } is Attempt.Answer)
        assertEquals("cloudflare", answered.last())
        assertEquals(List(QUAD9_ADDRESSES.size + 1) { "dns.quad9.net" } + "security.cloudflare-dns.com", hosts)
    }
}
