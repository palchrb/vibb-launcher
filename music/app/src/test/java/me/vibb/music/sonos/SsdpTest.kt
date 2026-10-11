package me.vibb.music.sonos

import me.vibb.music.sonos.SonosFakes.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The SSDP round palchrb/vibb's `rescan()` gets from SoCo's `discover` (MIT, Copyright (c) 2026
 * palchrb; design 26a §2.1, QA L6): SoCo's search text, sent three times, only Sonos answers kept,
 * the LOCATION's ip and port.
 */
class SsdpTest {
    private class FakeIo(private val clock: FakeClock, replies: List<String?>) : SsdpIo {
        val sent = mutableListOf<String>()
        private val queue = ArrayDeque(replies)
        val left: Int get() = queue.size

        override fun send(payload: ByteArray) {
            sent.add(String(payload, Charsets.US_ASCII))
        }

        override fun receive(timeoutMs: Long): String? {
            val r = queue.removeFirstOrNull()
            clock.advance(if (r == null) timeoutMs else 100)
            return r
        }

        override fun close() {}
    }

    @Test
    fun `the M-SEARCH is SoCo's`() {
        assertEquals(
            "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n",
            Ssdp.M_SEARCH,
        )
    }

    @Test
    fun `three sends, then replies until the deadline - non-Sonos ignored, one hit per ip`() {
        val clock = FakeClock()
        val other = fixture("ssdp_other.txt")
        val stua = fixture("ssdp_sonos.txt")
        val kjokken = stua.replace("10.0.0.11:1400", "10.0.0.12:1401")
        val io = FakeIo(clock, listOf(other, stua, stua, kjokken, null))
        val hits = Ssdp.scan(io, { clock.now })
        assertEquals(List(3) { Ssdp.M_SEARCH }, io.sent)
        assertEquals(listOf(SsdpHit("10.0.0.11", 1400), SsdpHit("10.0.0.12", 1401)), hits)
    }

    @Test
    fun `the round ends at the listen deadline even while replies keep coming`() {
        val clock = FakeClock()
        val io = FakeIo(clock, List(100) { fixture("ssdp_other.txt") })
        assertEquals(emptyList<SsdpHit>(), Ssdp.scan(io, { clock.now }, listenMs = 1_000))
        assertEquals("ten replies of 100 ms fill the second", 90, io.left)
    }

    @Test
    fun `LOCATION gives ip and port, a missing port is 1400, SERVER must name Sonos`() {
        fun reply(location: String, server: String = "Linux UPnP/1.0 Sonos/70.3-35220 (ZPS1)") =
            "HTTP/1.1 200 OK\r\nLOCATION: $location\r\nSERVER: $server\r\n\r\n"
        assertEquals(SsdpHit("192.168.1.17", 1400), Ssdp.parseReply(reply("http://192.168.1.17:1400/xml/device_description.xml")))
        assertEquals(SsdpHit("192.168.1.17", 1400), Ssdp.parseReply(reply("http://192.168.1.17/xml/device_description.xml")))
        assertEquals(SsdpHit("10.0.2.2", 1402), Ssdp.parseReply(reply("http://10.0.2.2:1402/xml/device_description.xml")))
        assertEquals("headers are case-insensitive", SsdpHit("10.0.0.5", 1400), Ssdp.parseReply("HTTP/1.1 200 OK\r\nlocation: http://10.0.0.5:1400/x\r\nServer: Sonos/1\r\n\r\n"))
        assertNull(Ssdp.parseReply(reply("http://192.168.1.17:1400/x", server = "Linux UPnP/1.0 MiniUPnPd/2.2")))
        assertNull(Ssdp.parseReply("HTTP/1.1 200 OK\r\nSERVER: Sonos/1\r\n\r\n"))
        assertNull(Ssdp.parseReply(reply("not a url")))
    }
}
