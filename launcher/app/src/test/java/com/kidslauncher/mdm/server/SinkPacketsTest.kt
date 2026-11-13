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
}
