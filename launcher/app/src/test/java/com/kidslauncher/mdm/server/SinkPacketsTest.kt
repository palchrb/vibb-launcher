package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Design 95 §7: the sink's answers with captured-shape packets, v4 and v6 - a wrong checksum would make apps hang
 * instead of failing at once. */
class SinkPacketsTest {
    private fun hex(s: String): ByteArray = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** 10.0.2.15:40000 -> 1.1.1.1:443, SYN, seq 1000, no options. */
    private val syn4 = hex(
        "4500 0028 0001 4000 4006 0000 0a00 020f 0101 0101" +
            "9c40 01bb 0000 03e8 0000 0000 5002 ffff 0000 0000",
    )

    /** fd00::2 -> 2606:4700::1111, port 40000 -> 443, ACK+PSH with 5 bytes, seq 7, ack 9. */
    private val data6 = hex(
        "6000 0000 0019 0640 fd00 0000 0000 0000 0000 0000 0000 0002 2606 4700 0000 0000 0000 0000 0000 1111" +
            "9c40 01bb 0000 0007 0000 0009 5018 ffff 0000 0000 6865 6c6c 6f",
    )

    private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)
    private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

    @Test
    fun `a v4 SYN gets a RST+ACK with valid checksums`() {
        val rst = SinkPackets.answer(syn4)!!
        assertEquals(40, rst.size)
        assertEquals(0, SinkPackets.checksum(rst, 0, 20, 0))
        var pseudo = 0L
        for (i in 12 until 20 step 2) pseudo += u16(rst, i)
        assertEquals(0, SinkPackets.checksum(rst, 20, 20, pseudo + 6 + 20))
        assertEquals(listOf(443, 40000), listOf(u16(rst, 20), u16(rst, 22)))
        assertEquals(1001L, u32(rst, 28)) // the SYN counts one
        assertEquals(0x14, rst[33].toInt())
    }

    @Test
    fun `a v6 data segment gets a RST+ACK acknowledging its bytes`() {
        val rst = SinkPackets.answer(data6)!!
        assertEquals(60, rst.size)
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += u16(rst, i)
        assertEquals(0, SinkPackets.checksum(rst, 40, 20, pseudo + 6 + 20))
        assertEquals(9L, u32(rst, 44))
        assertEquals(12L, u32(rst, 48))
    }

    @Test
    fun `a RST is never answered, UDP other than DNS is dropped, DNS is refused`() {
        val rstIn = syn4.copyOf().also { it[33] = 0x04 }
        assertNull(SinkPackets.answer(rstIn))
        // 10.0.2.15:5353 -> 192.0.2.2:53, a 12-byte DNS header (id 0x1234, RD).
        val dns = hex("4500 0028 0002 0000 4011 0000 0a00 020f c000 0202 14e9 0035 0014 0000 1234 0100 0000 0000 0000 0000")
        val refused = SinkPackets.answer(dns)
        assertNotNull(refused)
        assertEquals(0, SinkPackets.checksum(refused!!, 0, 20, 0))
        assertEquals(0x1234, u16(refused, 28))
        assertEquals(0x81, refused[30].toInt() and 0xff)
        assertEquals(5, refused[31].toInt() and 0x0f)
        val quic = dns.copyOf().also { it[22] = 0x01; it[23] = 0xbb.toByte() }
        assertNull(SinkPackets.answer(quic))
        assertNull(SinkPackets.answer(ByteArray(3)))
    }

    /** Code QA L4: short, malformed and fragment packets are dropped, never answered with garbage, never thrown. */
    @Test
    fun `short, malformed and later-fragment packets are dropped`() {
        assertNull(SinkPackets.answer(syn4.copyOf().also { it[0] = 0x44 })) // IHL 4
        assertNull(SinkPackets.answer(syn4.copyOf().also { it[6] = 0x00; it[7] = 0x10 })) // fragment offset 16
        assertNotNull(SinkPackets.answer(syn4.copyOf().also { it[6] = 0x20 })) // MF on the first fragment: answered
        for (length in listOf(0, 1, 19, 20, 39)) assertNull("v4 $length", SinkPackets.answer(syn4, length))
        for (length in listOf(39, 40, 59)) assertNull("v6 $length", SinkPackets.answer(data6, length))
        assertNull(SinkPackets.answer(syn4.copyOf().also { it[0] = 0x75 })) // version 7
        // A DNS query whose UDP length points past the packet.
        val dns = hex("4500 0028 0002 0000 4011 0000 0a00 020f c000 0202 14e9 0035 00ff 0000 1234 0100 0000 0000 0000 0000")
        assertNull(SinkPackets.answer(dns))
    }

    @Test
    fun `a v6 DNS query is refused with a valid UDP checksum`() {
        // fd00::2:5353 -> 2001:4860:4860::8888:53, a 12-byte DNS header (id 0xbeef).
        val q = hex(
            "6000 0000 0014 1140 fd00 0000 0000 0000 0000 0000 0000 0002 2001 4860 4860 0000 0000 0000 0000 8888" +
                "14e9 0035 0014 0000 beef 0100 0000 0000 0000 0000",
        )
        val r = SinkPackets.answer(q)!!
        assertEquals(60, r.size)
        assertEquals(listOf(53, 5353), listOf(u16(r, 40), u16(r, 42)))
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += u16(r, i)
        assertEquals(0, SinkPackets.checksum(r, 40, 20, pseudo + 17 + 20))
        assertEquals(0xbeef, u16(r, 48))
        assertEquals(5, r[51].toInt() and 0x0f)
    }
}
