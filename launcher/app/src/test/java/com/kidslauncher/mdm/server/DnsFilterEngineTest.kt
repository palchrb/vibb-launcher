package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Section
import org.xbill.DNS.Type
import java.io.File

/** The on-device filter's decision ([classifyDomain]): the suffix walk, FCM's global exemption and
 * Spotify's per-phone one (design 26c §6.2). */
class DnsFilterEngineTest {
    private val blocked = mapOf(
        "spotify.com" to "Streaming",
        "scdn.co" to "Streaming",
        "akamaized.net" to "Ads",
        "ads.example" to "Ads",
        "google.com" to "Ads",
    )

    @Test
    fun `the suffix walk blocks a domain and everything under it`() {
        assertEquals("Ads", classifyDomain(blocked, "ads.example", spotify = false))
        assertEquals("Ads", classifyDomain(blocked, "x.Ads.Example.", spotify = false))
        assertNull(classifyDomain(blocked, "example", spotify = false))
        assertNull(classifyDomain(blocked, "notads.example.org", spotify = false))
    }

    @Test
    fun `FCM's hosts always pass, a parent the parent blocked doesn't open the rest`() {
        assertNull(classifyDomain(blocked, "mtalk.google.com", spotify = false))
        assertEquals("Ads", classifyDomain(blocked, "ads.google.com", spotify = false))
    }

    @Test
    fun `Spotify's hosts under a blocked parent pass only while the phone plays Spotify (26c §6_2)`() {
        val spotifyHosts = listOf(
            "spotify.com", "api.spotify.com", "spclient.wg.spotify.com", "sdk.scdn.co", "i.scdn.co",
            "audio-ak-spotify-com.akamaized.net", "audio-akp-quic-spotify-com.akamaized.net.",
        )
        for (host in spotifyHosts) {
            assertNull(host, classifyDomain(blocked, host, spotify = true))
            assertEquals(host, if (host.contains("akamaized")) "Ads" else "Streaming", classifyDomain(blocked, host, spotify = false))
        }
        // Never the third-party parent, nor anything else under it, nor a look-alike.
        assertEquals("Ads", classifyDomain(blocked, "akamaized.net", spotify = true))
        assertEquals("Ads", classifyDomain(blocked, "other.akamaized.net", spotify = true))
        assertEquals("Ads", classifyDomain(blocked, "ads.example", spotify = true))
        assertEquals("Ads", classifyDomain(blocked + ("evilspotify.com" to "Ads"), "evilspotify.com", spotify = true))
    }

    private fun source(name: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/server/$name"), File("app/src/main/java/com/kidslauncher/mdm/server/$name"))
            .first { it.exists() }.readText()

    /** qa-26c-step3-code L1: the flag [classifyDomain] gets is set at both places - every fresh sync, before
     * the list's refetch, and the VPN's start from the cached policy - and `classify` passes it. */
    @Test
    fun `the Spotify flag is set by the sync and at the VPN's load, and classify reads it`() {
        val sync = source("MdmSyncWorker.kt")
        val set = sync.indexOf("DnsFilterEngine.setSpotify(com.kidslauncher.mdm.music.musicSpotify(freshPolicy.music))")
        val refresh = sync.indexOf("DnsFilterEngine.refreshIfNeeded(context, api, freshPolicy.dnsFilterVersion)")
        assertTrue("the sync sets it", set >= 0)
        assertTrue("before the list is refetched", set < refresh)
        val engine = source("DnsFilterEngine.kt")
        val load = engine.substringAfter("fun loadFromDisk(context: Context) {").substringBefore("val listFile")
        assertTrue(load, Regex("spotifyOn = runCatching \\{\\s*com\\.kidslauncher\\.mdm\\.music\\.musicSpotify\\(\\(cachedPolicy\\(\\) as\\? CachedPolicy\\.Ok\\)\\?\\.policy\\?\\.music\\)").containsMatchIn(load))
        assertTrue(engine.contains("fun setSpotify(on: Boolean) {\n        spotifyOn = on"))
        assertTrue(engine.contains("fun classify(domainRaw: String): String? = classifyDomain(blockedDomains, domainRaw, spotifyOn, dohHosts)"))
    }

    // Design 67 §2.2: the built-in secure-DNS hosts.

    private val dohFile = listOf(File("src/main/assets/$DOH_HOSTS_ASSET"), File("app/src/main/assets/$DOH_HOSTS_ASSET")).first { it.exists() }
    private val doh = parseDohHosts(dohFile.readText())

    @Test
    fun `every built-in secure-DNS host is a lowercase LDH name, once, under no other`() {
        val label = "[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?"
        val names = dohFile.readLines().map { it.substringBefore('#').trim() }.filter { it.isNotEmpty() }
        assertTrue(names.size > 30)
        for (name in names) assertTrue(name, Regex("$label(\\.$label)+").matches(name))
        assertEquals("no repeats", names.size, names.toSet().size)
        for (name in names) assertNull("$name is under another entry", classifyDomain(emptyMap(), name, false, doh - name))
    }

    @Test
    fun `the built-in hosts block by suffix, before the server's lists, after FCM`() {
        for (hit in listOf("chrome.cloudflare-dns.com", "dns.google.", "DoH.OpenDNS.com", "family.cloudflare-dns.com")) {
            assertEquals(hit, DOH_BYPASS_CATEGORY, classifyDomain(blocked, hit, spotify = false, dohHosts = doh))
        }
        for (miss in listOf("example.com", "dohmain.com", "doh.com", "notdns.google.com", "doh.wa.gov", "doh.gov.ph", "doh.gov.ae", "google")) {
            assertNull(miss, classifyDomain(emptyMap(), miss, spotify = false, dohHosts = doh))
        }
        // Before a server list that has the same name; FCM's hosts still pass whatever the set holds.
        assertEquals(DOH_BYPASS_CATEGORY, classifyDomain(mapOf("dns.google" to "Ads"), "dns.google", false, doh))
        assertNull(classifyDomain(blocked, "mtalk.google.com", spotify = false, dohHosts = setOf("google.com")))
    }

    @Test
    fun `the built-in hosts load before the list's early return, so they hold before the first sync`() {
        val load = source("DnsFilterEngine.kt").substringAfter("fun loadFromDisk(context: Context) {")
        val read = load.indexOf("dohHosts = parseDohHosts(context.assets.open(DOH_HOSTS_ASSET)")
        assertTrue(read >= 0 && read < load.indexOf("if (!listFile.exists()) return"))
    }
}

/** Design 67 §2.3: Firefox's canary and its answer. */
class NxdomainTest {
    @Test
    fun `the canary is the exact name, answered NXDOMAIN with the SOA, the ID and RD echoed`() {
        assertTrue(isFirefoxCanary("use-application-dns.net"))
        assertTrue(isFirefoxCanary("Use-Application-DNS.net."))
        assertFalse(isFirefoxCanary("x.use-application-dns.net"))
        assertFalse(isFirefoxCanary("use-application-dns.net.example"))
        for (rd in listOf(true, false)) {
            val request = Message.newQuery(Record.newRecord(Name.fromString("use-application-dns.net."), Type.A, DClass.IN))
            if (!rd) request.header.unsetFlag(Flags.RD.toInt())
            val answer = Message(nxdomainResponse(request))
            assertEquals(Rcode.NXDOMAIN, answer.rcode)
            assertEquals(request.header.id, answer.header.id)
            assertEquals(rd, answer.header.getFlag(Flags.RD.toInt()))
            assertTrue(answer.header.getFlag(Flags.QR.toInt()) && answer.header.getFlag(Flags.RA.toInt()))
            assertEquals(request.question, answer.question)
            assertTrue(answer.getSection(Section.ANSWER).isEmpty())
            assertEquals(Type.SOA, answer.getSection(Section.AUTHORITY).single().type)
        }
    }
}
