package com.kidslauncher.mdm.server

/**
 * The sink shape's answers (design 95 §2.3 (a)): every packet another app sends while only Vibb may use cellular is
 * answered at once in the read loop, so the app fails in a millisecond and backs off instead of hanging until its own
 * timeout - a DNS query with `REFUSED`, a TCP segment (anything but a RST) with RST+ACK, the rest (UDP, QUIC, ICMP)
 * dropped. Synchronous and outside the DNS path's admission limit: no coroutine per packet. Pure, `SinkPacketsTest`
 * (v4 and v6 checksums). IPv4 with a real header (IHL >= 5) and not a later fragment; IPv6 without extension headers;
 * anything else is dropped. DNS over IPv6 (an app's own v6 resolver) is refused too (code QA L4).
 */
object SinkPackets {
    private const val PROTO_TCP = 6
    private const val PROTO_UDP = 17

    /** The answer to [packet] (its first [length] bytes), or `null` to drop it. */
    fun answer(packet: ByteArray, length: Int = packet.size): ByteArray? = try {
        when (protocol(packet, length)) {
            PROTO_TCP -> tcpReset(packet, length)
            PROTO_UDP -> dnsRefused(packet, length)
            else -> null
        }
    } catch (e: IndexOutOfBoundsException) {
        null
    }

    private fun protocol(b: ByteArray, n: Int): Int? = when (version(b, n)) {
        // Code QA L4: an IHL under 5 isn't a header, and a later fragment carries no transport header.
        4 -> if (n >= 20 && (u8(b, 0) and 15) >= 5 && (u16(b, 6) and 0x1fff) == 0) u8(b, 9) else null
        6 -> if (n >= 40) u8(b, 6) else null
        else -> null
    }

    private fun version(b: ByteArray, n: Int): Int? = if (n < 1) null else u8(b, 0) shr 4

    /** An IPv6 UDP query to port 53 answered `REFUSED`; the UDP checksum is mandatory over IPv6. */
    private fun dnsRefused6(b: ByteArray, n: Int): ByteArray? {
        if (n < 40 + 8 + 12 || u16(b, 42) != 53) return null
        val udpLength = u16(b, 44)
        if (udpLength < 8 + 12 || 40 + udpLength > n) return null
        val payload = b.copyOfRange(48, 40 + udpLength)
        payload[2] = (payload[2].toInt() or 0x80).toByte()
        payload[3] = ((payload[3].toInt() and 0xf0) or 5).toByte()
        val out = ByteArray(40 + 8 + payload.size)
        out[0] = 0x60
        p16(out, 4, 8 + payload.size)
        out[6] = PROTO_UDP.toByte()
        out[7] = 64
        b.copyInto(out, 8, 24, 40)
        b.copyInto(out, 24, 8, 24)
        p16(out, 40, u16(b, 42))
        p16(out, 42, u16(b, 40))
        p16(out, 44, 8 + payload.size)
        payload.copyInto(out, 48)
        var pseudo = 0L
        for (i in 8 until 40 step 2) pseudo += u16(out, i)
        pseudo += PROTO_UDP + 8 + payload.size
        val sum = checksum(out, 40, 8 + payload.size, pseudo)
        p16(out, 46, if (sum == 0) 0xffff else sum)
        return out
    }

    /** An IPv4 UDP query to port 53 answered `REFUSED`: the query's own bytes with QR set and RCODE 5. */
    private fun dnsRefused(b: ByteArray, n: Int): ByteArray? {
        if (version(b, n) == 6) return dnsRefused6(b, n)
        if (version(b, n) != 4) return null
        val ihl = (u8(b, 0) and 15) * 4
        if (n < ihl + 8 + 12 || u16(b, ihl + 2) != 53) return null
        val udpLength = u16(b, ihl + 4)
        if (udpLength < 8 + 12 || ihl + udpLength > n) return null
        val payload = b.copyOfRange(ihl + 8, ihl + udpLength)
        payload[2] = (payload[2].toInt() or 0x80).toByte()
        payload[3] = ((payload[3].toInt() and 0xf0) or 5).toByte()
        val out = ByteArray(20 + 8 + payload.size)
        out[0] = 0x45
        p16(out, 2, out.size)
        out[8] = 64
        out[9] = PROTO_UDP.toByte()
        b.copyInto(out, 12, 16, 20) // source = the query's destination
        b.copyInto(out, 16, 12, 16)
        p16(out, 10, checksum(out, 0, 20, 0))
        p16(out, 20, u16(b, ihl + 2))
        p16(out, 22, u16(b, ihl))
        p16(out, 24, 8 + payload.size)
        // UDP checksum 0: optional over IPv4.
        payload.copyInto(out, 28)
        return out
    }

    /** RST+ACK for a TCP segment over IPv4 or IPv6; never for a RST. */
    private fun tcpReset(b: ByteArray, n: Int): ByteArray? {
        val v4 = version(b, n) == 4
        val ipLength = if (v4) 20 else 40
        val tcp = if (v4) (u8(b, 0) and 15) * 4 else 40
        if (n < tcp + 20) return null
        val payloadLength = if (v4) u16(b, 2) - tcp else u16(b, 4)
        val flags = u8(b, tcp + 13)
        if (flags and 0x04 != 0) return null
        val dataOffset = (u8(b, tcp + 12) shr 4) * 4
        val seq = u32(b, tcp + 4)
        val ack = u32(b, tcp + 8)
        val segment = payloadLength - dataOffset + (if (flags and 0x02 != 0) 1 else 0) + (if (flags and 0x01 != 0) 1 else 0)
        val out = ByteArray(ipLength + 20)
        if (v4) {
            out[0] = 0x45
            p16(out, 2, out.size)
            out[8] = 64
            out[9] = PROTO_TCP.toByte()
            b.copyInto(out, 12, 16, 20)
            b.copyInto(out, 16, 12, 16)
            p16(out, 10, checksum(out, 0, 20, 0))
        } else {
            out[0] = 0x60
            p16(out, 4, 20)
            out[6] = PROTO_TCP.toByte()
            out[7] = 64
            b.copyInto(out, 8, 24, 40)
            b.copyInto(out, 24, 8, 24)
        }
        val t = ipLength
        p16(out, t, u16(b, tcp + 2))
        p16(out, t + 2, u16(b, tcp))
        val hadAck = flags and 0x10 != 0
        p32(out, t + 4, if (hadAck) ack else 0)
        p32(out, t + 8, (seq + segment) and 0xffffffffL)
        out[t + 12] = 0x50
        out[t + 13] = (0x04 or 0x10).toByte()
        var pseudo = 0L
        if (v4) {
            for (i in 12 until 20 step 2) pseudo += u16(out, i)
        } else {
            for (i in 8 until 40 step 2) pseudo += u16(out, i)
        }
        pseudo += PROTO_TCP + 20
        p16(out, t + 16, checksum(out, t, 20, pseudo))
        return out
    }

    /** The Internet checksum of [len] bytes at [off], [start] added (a pseudo-header's sum). */
    fun checksum(b: ByteArray, off: Int, len: Int, start: Long): Int {
        var s = start
        var i = 0
        while (i + 1 < len) {
            s += u16(b, off + i)
            i += 2
        }
        if (len and 1 == 1) s += (u8(b, off + len - 1) shl 8).toLong()
        while (s shr 16 != 0L) s = (s and 0xffff) + (s shr 16)
        return (s.inv() and 0xffff).toInt()
    }

    private fun u8(b: ByteArray, i: Int): Int = b[i].toInt() and 0xff
    private fun u16(b: ByteArray, i: Int): Int = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun u32(b: ByteArray, i: Int): Long = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()
    private fun p16(b: ByteArray, i: Int, v: Int) {
        b[i] = (v shr 8).toByte()
        b[i + 1] = v.toByte()
    }
    private fun p32(b: ByteArray, i: Int, v: Long) {
        p16(b, i, (v shr 16).toInt())
        p16(b, i + 2, v.toInt())
    }
}
