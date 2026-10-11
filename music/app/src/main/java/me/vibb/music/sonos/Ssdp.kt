package me.vibb.music.sonos

import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/*
 * SSDP discovery, as palchrb/vibb `pi/sonosd.py` `rescan()` gets it from SoCo's `discover` (MIT
 * License, Copyright (c) 2026 palchrb), reimplemented in Kotlin under the GPL-3.0 (or later) - see
 * NOTICE.md. Design 26a §2.1. Android-free; SsdpTest. SsdpScanner.kt adds the Wi-Fi binding and the
 * MulticastLock.
 *
 * SSDP only finds speakers (one is enough): the uids, names and groups come from that speaker's
 * GetZoneGroupState afterwards (SpeakerCache.kt), as SoCo does under the hood.
 */

/** An answering speaker: where its device description lives. */
data class SsdpHit(val ip: String, val port: Int)

object Ssdp {
    const val GROUP = "239.255.255.250"
    const val PORT = 1900
    const val ST = "urn:schemas-upnp-org:device:ZonePlayer:1"

    /** SoCo sends the search three times (multicast over Wi-Fi drops packets). */
    const val SENDS = 3

    /** Replies are collected this long (SoCo's `discover(timeout=3)`). */
    const val LISTEN_MS = 3_000L

    /** SoCo's `PLAYER_SEARCH`. */
    val M_SEARCH: String = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: $GROUP:$PORT\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 1\r\n" +
        "ST: $ST\r\n" +
        "\r\n"

    /** The speaker in one reply: a `SERVER` header naming Sonos and a `LOCATION`; else null. */
    fun parseReply(text: String): SsdpHit? {
        val headers = text.split("\r\n", "\n").drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().uppercase() to line.substring(colon + 1).trim()
        }.toMap()
        if (headers["SERVER"]?.contains("Sonos") != true) return null
        val (ip, port) = Topology.location(headers["LOCATION"] ?: return null) ?: return null
        return SsdpHit(ip, port)
    }

    /**
     * One round: [SENDS] searches, then replies for [listenMs] of [elapsedMs]; one hit per ip, in
     * the order they answered.
     */
    fun scan(io: SsdpIo, elapsedMs: () -> Long, listenMs: Long = LISTEN_MS): List<SsdpHit> {
        val payload = M_SEARCH.toByteArray(Charsets.US_ASCII)
        repeat(SENDS) { io.send(payload) }
        val deadline = elapsedMs() + listenMs
        val hits = linkedMapOf<String, SsdpHit>()
        while (true) {
            val left = deadline - elapsedMs()
            if (left <= 0) break
            val reply = io.receive(left) ?: break
            parseReply(reply)?.let { hits.putIfAbsent(it.ip, it) }
        }
        return hits.values.toList()
    }
}

/** The socket side of a round, so the round is testable. */
interface SsdpIo : Closeable {
    fun send(payload: ByteArray)

    /** The next datagram as text, or null when [timeoutMs] passed without one. */
    fun receive(timeoutMs: Long): String?
}

/** [SsdpIo] over a UDP socket (bound to the Wi-Fi network by the caller). */
class DatagramSsdpIo(private val socket: DatagramSocket) : SsdpIo {
    private val group = InetSocketAddress(Ssdp.GROUP, Ssdp.PORT)
    private val buffer = ByteArray(2048)

    override fun send(payload: ByteArray) {
        socket.send(DatagramPacket(payload, payload.size, group))
    }

    override fun receive(timeoutMs: Long): String? {
        socket.soTimeout = timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
        val packet = DatagramPacket(buffer, buffer.size)
        return try {
            socket.receive(packet)
            String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
        } catch (_: SocketTimeoutException) {
            null
        }
    }

    override fun close() = socket.close()
}
