package com.kidslauncher.mdm.server

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.ARecord
import org.xbill.DNS.AAAARecord
import org.xbill.DNS.CNAMERecord
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Section

/** Design 36 §5.3: where the servers' names are looked up, and the memory cache. */
class BootstrapResolverTest {
    private val dohName = UpstreamTarget.Doh("https://abc123.dns.example/dns-query")
    private val dotName = UpstreamTarget.Dot("dns.example", 853)
    private val dotIp = UpstreamTarget.Dot("192.0.2.53", 853)
    private val dohIp = UpstreamTarget.Doh("https://[2001:db8::53]/dns-query")

    private fun config(primary: UpstreamTarget, backup: UpstreamTarget) = CustomUpstream(primary, backup, rev = 1)

    @Test
    fun `the backup's IP first, then the primary's, then Cloudflare - and nothing to look up with two IPs`() {
        assertEquals(listOf(dotIp, CLOUDFLARE_BOOTSTRAP), bootstrapResolvers(config(dohName, dotIp)))
        assertEquals(listOf(dohIp, CLOUDFLARE_BOOTSTRAP), bootstrapResolvers(config(dotName, dohIp)))
        assertEquals(listOf(dotIp, CLOUDFLARE_BOOTSTRAP), bootstrapResolvers(config(dotIp, dohName)))
        assertEquals(listOf(CLOUDFLARE_BOOTSTRAP), bootstrapResolvers(config(dohName, dotName)))
        assertEquals(emptyList<UpstreamTarget>(), bootstrapResolvers(config(dotIp, dohIp)))
    }

    @Test
    fun `only IP literals are addresses, a name is never resolved here`() {
        assertEquals(InetAddress.getByName("192.0.2.53"), ipLiteral("192.0.2.53"))
        assertEquals(InetAddress.getByName("2001:db8::53"), ipLiteral("[2001:db8::53]"))
        assertEquals(InetAddress.getByName("2001:db8::53"), ipLiteral(dohIp.serverHost))
        assertEquals(InetAddress.getByName("::1"), ipLiteral("::1"))
        assertEquals(InetAddress.getByName("2001:db8::"), ipLiteral("2001:db8::"))
        assertEquals(InetAddress.getByName("::ffff:192.0.2.1"), ipLiteral("::ffff:192.0.2.1"))
        assertEquals(InetAddress.getByName("2001:db8:0:0:0:0:0:53"), ipLiteral("2001:db8:0:0:0:0:0:53"))
        // Parsed, never resolved: anything not numeric is null, never a look-up.
        for (name in listOf(
            "dns.example", "abc123.dns.example", "1.1", "999.0.2.1", "192.0.2.1.example", "", "01.2.3.4.5",
            "dns.example:853", "1:2", "2001:db8::1::2", "2001:db8:0:0:0:0:0:0:1", "g::1", "[dns.example]",
        )) {
            assertNull(name, ipLiteral(name))
        }
        val lookedUp = mutableListOf<String>()
        val dns = BootstrapDns({ 0 }) { lookedUp += it }
        assertEquals(listOf(InetAddress.getByName("192.0.2.53")), dns.addresses("192.0.2.53"))
        assertEquals(listOf(InetAddress.getByName("1.1.1.1")), dns.addresses(CLOUDFLARE_BOOTSTRAP.host))
        assertTrue(lookedUp.isEmpty())
        // A name with nothing cached: one look-up, no address yet (OkHttp sees an unknown host).
        assertTrue(dns.addresses("dns.example").isEmpty())
        assertTrue(dns.addresses("dns.example").isEmpty())
        assertEquals(listOf("dns.example"), lookedUp)
    }

    @Test
    fun `the cache clamps the TTL, serves stale while one refresh runs and keeps it when that fails`() {
        val cache = BootstrapCache()
        val a = listOf(InetAddress.getByName("192.0.2.1"))
        assertTrue(cache.get("dns.example", 0).lookUp)
        assertFalse("one look-up at a time", cache.get("dns.example", 0).lookUp)
        cache.put("dns.example", a, ttlSeconds = 10, now = 0)
        assertEquals(CacheView(a, false), view(cache.get("dns.example", 299_999)))
        // Expired at the 5 min floor: still served, one refresh.
        assertEquals(CacheView(a, true), view(cache.get("dns.example", 300_000)))
        assertEquals(CacheView(a, false), view(cache.get("dns.example", 300_001)))
        cache.failed("dns.example", 300_500)
        assertTrue(cache.has("dns.example"))
        // A failed refresh: still served, no look-up per query - the next one 30 s later.
        assertEquals(CacheView(a, false), view(cache.get("dns.example", 300_600)))
        assertEquals(CacheView(a, true), view(cache.get("dns.example", 330_500)))
        // The 24 h ceiling.
        cache.put("dns.example", a, ttlSeconds = 7 * 86_400, now = 0)
        assertFalse(cache.get("dns.example", 86_399_999).lookUp)
        assertTrue(cache.get("dns.example", 86_400_000).lookUp)
    }

    @Test
    fun `a failed look-up with nothing cached waits 30 s, or for a network reset`() {
        val cache = BootstrapCache()
        assertTrue(cache.get("dns.example", 0).lookUp)
        cache.failed("dns.example", 1_000)
        assertFalse(cache.has("dns.example"))
        assertFalse(cache.get("dns.example", 30_999).lookUp)
        assertTrue(cache.get("dns.example", 31_000).lookUp)
        cache.failed("dns.example", 31_000)
        cache.forgetFailures()
        assertTrue(cache.get("dns.example", 31_001).lookUp)
    }

    @Test
    fun `the final name of a CNAME chain gives the addresses, at most two, IPv4 first`() {
        val qname = Name.fromString("abc123.dns.example.")
        val edge = Name.fromString("edge.cdn.example.")
        val last = Name.fromString("node.cdn.example.")
        val response = Message().apply {
            header.rcode = Rcode.NOERROR
            addRecord(CNAMERecord(qname, DClass.IN, 3600, edge), Section.ANSWER)
            addRecord(CNAMERecord(edge, DClass.IN, 600, last), Section.ANSWER)
            addRecord(AAAARecord(last, DClass.IN, 900, InetAddress.getByName("2001:db8::1")), Section.ANSWER)
            addRecord(ARecord(last, DClass.IN, 1200, InetAddress.getByName("192.0.2.1")), Section.ANSWER)
            addRecord(ARecord(Name.fromString("other.example."), DClass.IN, 60, InetAddress.getByName("192.0.2.99")), Section.ANSWER)
        }
        val answer = addressesFromAnswer(response, qname)
        assertEquals(600, answer.ttlSeconds)
        assertEquals(
            listOf(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("2001:db8::1")),
            ipv4First(answer.addresses),
        )
        val nx = Message().apply { header.rcode = Rcode.NXDOMAIN }
        assertTrue(addressesFromAnswer(nx, qname).addresses.isEmpty())
    }

    @Test
    fun `the disk cache round-trips by the wall clock, honours the expiry and ignores a corrupt file`() {
        val a = listOf(InetAddress.getByName("192.0.2.1"), InetAddress.getByName("2001:db8::1"))
        val names = setOf("dns.example", "family.dns.example")
        val written = BootstrapDiskCache.encode(
            mapOf("dns.example" to CachedAddresses(a, 600_000), "family.dns.example" to CachedAddresses(a.take(1), 50_000)),
            elapsedNow = 100_000, wallNow = 5_000_000,
        )
        // After a reboot: elapsed time restarted, the wall clock went on by 200 s.
        val read = BootstrapDiskCache.decode(written, names, elapsedNow = 1_000, wallNow = 5_200_000)
        assertEquals(CachedAddresses(a, 1_000 + 300_000), read["dns.example"])
        assertEquals(CachedAddresses(a.take(1), 1_000 - 250_000), read["family.dns.example"])
        // Seeded: the fresh one is used as is, the expired one is served while one refresh runs.
        val cache = BootstrapCache()
        read.forEach { (host, cached) -> cache.seed(host, cached) }
        assertEquals(CacheView(a, false), view(cache.get("dns.example", 1_000)))
        assertEquals(CacheView(a.take(1), true), view(cache.get("family.dns.example", 1_000)))
        assertEquals(read, cache.snapshot())
        // Only the current config's names; a clock far behind can't make an entry live past 24 h.
        assertEquals(setOf("dns.example"), BootstrapDiskCache.decode(written, setOf("dns.example"), 0, 0).keys)
        assertEquals(BootstrapCache.MAX_TTL_S * 1000, BootstrapDiskCache.decode(written, names, 0, -100_000_000)["dns.example"]!!.expiresAtMs)
        // Anything malformed is no cache at all - never a look-up of what's in it.
        for (corrupt in listOf(
            "", "v2\n", written.replace("192.0.2.1", "dns.example"), written.replace("\t", " "),
            written + "x\t1\t192.0.2.7,192.0.2.8,192.0.2.9\n", written.replaceFirst("\t", "\tsoon"), "v1\ndns.example\t1\t\n",
        )) {
            assertTrue(corrupt, BootstrapDiskCache.decode(corrupt, names, 0, 0).isEmpty())
        }
    }

    @Test
    fun `only the names are kept, and the file is replaced through a rename`() {
        assertEquals(setOf("dns.example"), BootstrapDiskCache.namesOf(config(dotName, dohIp)))
        assertEquals(emptySet<String>(), BootstrapDiskCache.namesOf(config(dotIp, dohIp)))
        assertEquals(emptySet<String>(), BootstrapDiskCache.namesOf(config(dotIp, UpstreamTarget.Dot(CLOUDFLARE_BOOTSTRAP.host, 853))))
        val dir = java.nio.file.Files.createTempDirectory("dns-cache").toFile()
        val file = java.io.File(dir, BootstrapDiskCache.FILE_NAME)
        writeAtomically(file, "old")
        writeAtomically(file, "new")
        assertEquals("new", file.readText())
        assertEquals(listOf(file.name), dir.list()!!.toList())
        dir.deleteRecursively()
        // Never device-protected storage, never logged with what it holds.
        val src = listOf("src", "app/src").map { java.io.File(it) }.first { java.io.File(it, "main/java").isDirectory }
        val vpn = java.io.File(src, "main/java/com/kidslauncher/mdm/server/KidVpnService.kt").readText()
        assertTrue("File(noBackupFilesDir, BootstrapDiskCache.FILE_NAME)" in vpn)
        // Kept only for a custom server with a name; decided before syncCustom's early return (M1).
        assertFalse(BootstrapDiskCache.keptFor(null))
        assertFalse(BootstrapDiskCache.keptFor(config(dotIp, dohIp)))
        assertTrue(BootstrapDiskCache.keptFor(config(dotName, dohIp)))
        val sync = vpn.substringAfter("private fun syncCustom()").substringBefore("\n    }\n")
        val delete = sync.indexOf("if (!BootstrapDiskCache.keptFor(wanted)) cacheFile.delete()")
        assertTrue(delete >= 0 && delete < sync.indexOf("if (!changed) return"))
        assertFalse("DeviceProtectedStorage" in vpn)
    }

    private data class CacheView(val addresses: List<InetAddress>, val lookUp: Boolean)

    private fun view(found: BootstrapCache.Found) = CacheView(found.addresses, found.lookUp)
}
