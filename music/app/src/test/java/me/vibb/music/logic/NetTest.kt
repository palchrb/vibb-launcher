package me.vibb.music.logic

import me.vibb.music.Fixtures.entry
import me.vibb.music.model.Source
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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
    fun `HLS keys survive a re-resolved url (new token, new CDN host)`() {
        val first = "https://cdn1.example.org/hls/ep1/master.m3u8?token=a"
        val again = "https://cdn2.example.org/od/x/ep1/master.m3u8?token=b"
        val key = itemCacheKey("abels_taarn", "l_123")
        val k1 = hlsCacheKey(mapOf(urlDirectory(first)!! to key), "https://cdn1.example.org/hls/ep1/seg/00001.aac?token=a")
        val k2 = hlsCacheKey(mapOf(urlDirectory(again)!! to key), "https://cdn2.example.org/od/x/ep1/seg/00001.aac?token=b")
        assertEquals("hls:abels_taarn:l_123/seg/00001.aac", k1)
        assertEquals(k1, k2)
        assertNull("another host's resource isn't the item's", hlsCacheKey(mapOf(urlDirectory(first)!! to key), "https://other.example/seg.aac"))
        assertTrue(k1!!.startsWith(hlsKeyPrefix(key)))
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
    fun `NRK is always public-only, RSS waits for the server's lan flag, own files are local`() {
        assertTrue(publicOnly(entry(1, Source.NRK)))
        assertFalse(publicOnly(entry(1, Source.RSS)))
        assertFalse(publicOnly(entry(1, Source.OWN)))
    }
}
