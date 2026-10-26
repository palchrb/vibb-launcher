package com.kidslauncher.mdm.server

import java.io.File
import java.io.IOException
import java.net.Proxy
import java.net.Socket
import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Design 36's headline guards (qa-36-s2-code L3): the phone's lists before any resolver, the DoH
 * client's protected sockets and no proxy, and no status field once the VPN is off. */
class CustomDnsGuardsTest {
    private val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
    private fun source(name: String) = File(src, "main/java/com/kidslauncher/mdm/server/$name").readText()

    @Test
    fun `the lists decide before the custom server or the provider`() {
        val packet = source("KidVpnService.kt").substringAfter("private fun handlePacket(").substringBefore("\n    }\n")
        val classify = packet.indexOf("val category = DnsFilterEngine.classify(domain)\n")
        assertTrue("the plain classify line", classify >= 0)
        // Firefox's canary only before them (design 67 §2.3): answered here, never forwarded or logged.
        assertTrue(packet.indexOf("if (isFirefoxCanary(domain)) return reply(nxdomainResponse(message))") in 0 until classify)
        val blocked = packet.indexOf("val responseBytes = if (category != null) {")
        val custom = packet.indexOf("custom.resolve(")
        val provider = packet.indexOf("resolveProvider(")
        assertTrue(classify < blocked && blocked < custom && custom < provider)
        // The blocked branch answers NODATA and logs, before any resolver is named.
        val branch = packet.substring(blocked, custom)
        assertTrue("blockedResponse(message)" in branch && "BlockedEventLog.record(" in branch)
    }

    @Test
    fun `the DoH client is its own, with protected sockets and no proxy`() {
        val dns = object : Dns {
            override fun lookup(hostname: String) = emptyList<java.net.InetAddress>()
        }
        val protected = mutableListOf<Socket>()
        val client = buildDohClient(dns) { protected += it; true }
        assertEquals(Proxy.NO_PROXY, client.proxy)
        assertTrue(client.socketFactory is ProtectedSocketFactory)
        assertSame(dns, client.dns)
        assertFalse(client.followRedirects)
        assertFalse(client.followSslRedirects)
        assertNull(client.cache)
        val socket = client.socketFactory.createSocket()
        assertEquals(listOf(socket), protected)
        socket.close()
        // A socket that can't be protected is closed and never handed out.
        var refused: Socket? = null
        try {
            ProtectedSocketFactory { refused = it; false }.createSocket()
            fail("an unprotected socket was handed out")
        } catch (e: IOException) {
            assertTrue(refused!!.isClosed)
        }
        for (name in listOf("UpstreamTransport.kt", "CustomDns.kt", "BootstrapDns.kt")) {
            assertFalse("$name builds on another client", ".newBuilder(" in source(name))
        }
    }

    @Test
    fun `no status field once the VPN is off`() {
        val vpn = source("KidVpnService.kt")
        val stop = vpn.substringAfter("private fun stopSelfCleanly() {").substringBefore("\n    }\n")
        val locked = stop.substringAfter("synchronized(this) {").substringBefore("\n        }")
        // The stop is marked first (VpnTunnelState.stop: a volatile flag syncCustom reads under the
        // monitor), then live is cleared under it (qa-lfix-notifs-code #3).
        assertTrue("live cleared under the service's monitor", "if (live === this) live = null" in locked)
        assertTrue(stop.indexOf("state.stop()") in 0 until stop.indexOf("synchronized(this) {"))
        assertTrue("wanted.takeUnless { state.stopped }" in vpn)
        assertTrue(Regex("""fun dnsUpstreamReport\(\): DnsUpstreamReport\? = live\?\.upstreamReport\(\)""").containsMatchIn(vpn))
        // Design 67 §3.2 (S-M2): the field only while the tunnel is up - a dead read loop sends none.
        val report = vpn.substringAfter("private fun upstreamReport(): DnsUpstreamReport? {").substringBefore("\n    }\n")
        assertTrue(report.trimStart().startsWith("if (state.stopped || state.tunnel == null) return null"))
        assertTrue(vpn.indexOf("state.established(established") in 0 until vpn.indexOf("syncCustom()"))
    }
}
