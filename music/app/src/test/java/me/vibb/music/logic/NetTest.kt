package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import me.vibb.music.media.MediaCache
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.ServerSocket

/** The network gate (design 13's, ported), HLS cache keys (QA #2) and the public-address guard (21b §2.7). */
class NetTest {
    private val wifi = NetCaps(internet = true, notMetered = true, notRoaming = true, physical = true)
    private val mobile = wifi.copy(notMetered = false)
    private val roaming = mobile.copy(notRoaming = false)
    private val vpnOnly = wifi.copy(physical = false)

    @Test
    fun `streams need a physical network, no roaming, and Wi-Fi unless mobile data is on`() {
        assertTrue(streamAllowed(wifi, false))
        assertFalse(streamAllowed(mobile, false))
        assertTrue(streamAllowed(mobile, true))
        assertFalse(streamAllowed(roaming, true))
        assertFalse(streamAllowed(vpnOnly, true))
        assertFalse(streamAllowed(null, true))
    }

    @Test
    fun `downloads wait for Wi-Fi, storage or the end of roaming`() {
        assertNull(downloadWaiting(wifi, false, false))
        assertEquals(Waiting.WIFI, downloadWaiting(mobile, false, false))
        assertNull(downloadWaiting(mobile, true, false))
        assertEquals(Waiting.ROAMING, downloadWaiting(roaming, true, false))
        assertEquals(Waiting.STORAGE, downloadWaiting(wifi, false, true))
        assertEquals(Waiting.WIFI, downloadWaiting(null, true, false))
    }

    @Test
    fun `HLS keys survive a re-resolved url (new token, new CDN host), per item`() {
        val first = "https://cdn1.example.org/hls/ep1/master.m3u8?token=a"
        val again = "https://cdn2.example.org/od/x/ep1/master.m3u8?token=b"
        val key = itemCacheKey("abels_taarn", "l_123")
        val k1 = hlsResourceKey(key, first, "https://cdn1.example.org/hls/ep1/seg/00001.aac?token=a")
        val k2 = hlsResourceKey(key, again, "https://cdn2.example.org/od/x/ep1/seg/00001.aac?token=b")
        assertEquals("hls:abels_taarn:l_123/seg/00001.aac", k1)
        assertEquals(k1, k2)
        assertEquals("the playlist itself", "hls:abels_taarn:l_123/master.m3u8", hlsResourceKey(key, first, first))
        // A resource elsewhere is still the item's (deleted with it), keyed without its query.
        val elsewhere = hlsResourceKey(key, first, "https://other.example/seg.aac?sig=1")
        assertEquals("hls:abels_taarn:l_123/@other.example/seg.aac", elsewhere)
        // Two items sharing a directory never share a key (qa-21-step2-code L6).
        val other = itemCacheKey("abels_taarn", "l_124")
        assertTrue(hlsResourceKey(key, first, first) != hlsResourceKey(other, first, first))
    }

    @Test
    fun `one HLS rendition is downloaded - the best up to 192 kbit, else the lowest`() {
        assertEquals(1, pickVariant(listOf(64_000, 128_000, 320_000)))
        assertEquals(0, pickVariant(listOf(256_000, 320_000)))
        assertEquals(0, pickVariant(listOf(0, 0)))
        assertEquals(2, pickVariant(listOf(-1, 48_000, 192_000)))
    }

    private fun ip(s: String) = InetAddress.getByName(s)

    @Test
    fun `private, loopback, link-local, CGNAT and ULA addresses are refused`() {
        for (a in listOf(
            "0.1.2.3", "10.0.0.1", "100.64.0.1", "100.127.255.1", "127.0.0.1", "169.254.1.1", "172.16.0.1", "172.31.255.1",
            "192.0.0.8", "192.168.1.1", "198.18.0.1", "224.0.0.1", "255.255.255.255",
            "::", "::1", "fd7a:115c:a1e0::1", "fc00::1", "fe80::1", "ff02::1", "::ffff:192.168.1.1", "64:ff9b::a00:1",
        )) {
            assertTrue(a, isPrivateAddress(ip(a)))
        }
        for (a in listOf("8.8.8.8", "100.128.0.1", "172.32.0.1", "192.169.0.1", "2a00:1450:4001::1", "::ffff:8.8.8.8", "64:ff9b::808:808")) {
            assertFalse(a, isPrivateAddress(ip(a)))
        }
    }

    @Test
    fun `only an RSS entry the server marks lan may use private addresses`() {
        assertTrue(publicOnly(entry(1, Source.NRK)))
        assertTrue(publicOnly(entry(1, Source.NRK).copy(lan = true)))
        assertTrue(publicOnly(entry(1, Source.RSS)))
        assertFalse(publicOnly(entry(1, Source.RSS).copy(lan = true)))
    }

    // The guard on the wire (21b §2.7): a name that resolves to a private address, and an IP literal.

    private fun server(): ServerSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).also { socket ->
        Thread {
            runCatching {
                socket.accept().use { c ->
                    c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok".toByteArray())
                }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun get(client: OkHttpClient, url: String): Result<String> =
        runCatching { client.newCall(Request.Builder().url(url).build()).execute().use { it.body!!.string() } }

    @Test
    fun `a public entry's client refuses a name that resolves to a private address`() {
        server().use { s ->
            // "Public" to the server, 127.0.0.1 here.
            val rebinding = object : Dns {
                override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getLoopbackAddress())
            }
            val client = MediaCache.guarded(OkHttpClient(), rebinding)
            val failure = get(client, "http://feed.example:${s.localPort}/ep.mp3").exceptionOrNull()
            assertTrue("$failure", failure is MediaCache.PrivateAddressException)
            assertEquals("network", MediaCache.errorCode(failure!!))
        }
    }

    @Test
    fun `an IP literal never reaches the DNS - the connected address is checked`() {
        server().use { s ->
            val client = MediaCache.guarded(OkHttpClient(), Dns.SYSTEM)
            val failure = get(client, "http://127.0.0.1:${s.localPort}/ep.mp3").exceptionOrNull()
            assertTrue("$failure", failure is MediaCache.PrivateAddressException)
        }
    }

    @Test
    fun `a LAN entry's client may connect to the home network`() {
        server().use { s ->
            assertEquals("ok", get(OkHttpClient(), "http://127.0.0.1:${s.localPort}/ep.mp3").getOrThrow())
        }
    }
}
