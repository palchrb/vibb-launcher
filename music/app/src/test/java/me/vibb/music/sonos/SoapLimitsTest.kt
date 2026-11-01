package me.vibb.music.sonos

import okhttp3.MediaType
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xml.sax.SAXException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import kotlin.concurrent.thread

/** Security review 2026-10-21 S-L1: a reply is capped at 1 MiB, every call has a deadline, a DTD is refused. */
class SoapLimitsTest {
    /** A body of unknown length (chunked): only the read cap stops it. */
    private fun body(size: Long): ResponseBody = object : ResponseBody() {
        private val buf = Buffer().apply { repeat((size / 1024).toInt()) { write(ByteArray(1024) { 'x'.code.toByte() }) }; write(ByteArray((size % 1024).toInt()) { 'x'.code.toByte() }) }
        override fun contentType(): MediaType? = null
        override fun contentLength(): Long = -1
        override fun source(): BufferedSource = buf
    }

    @Test
    fun `a reply over 1 MiB is refused, one at the cap is read`() {
        assertEquals(Soap.MAX_REPLY_BYTES.toInt(), Soap.readCapped(body(Soap.MAX_REPLY_BYTES)).length)
        assertThrows(SpeakerUnreachable::class.java) { Soap.readCapped(body(Soap.MAX_REPLY_BYTES + 1)) }
    }

    @Test
    fun `a DOCTYPE or an ENTITY is refused, in the envelope and in a Browse Result`() {
        val dtd = """<?xml version="1.0"?><!DOCTYPE s [<!ENTITY a "aaaa">]>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:GetVolumeResponse xmlns:u="x">""" +
            """<CurrentVolume>&a;</CurrentVolume></u:GetVolumeResponse></s:Body></s:Envelope>"""
        assertThrows(SpeakerUnreachable::class.java) { Soap.parseResponse(200, dtd, "GetVolume") }
        assertThrows(SpeakerUnreachable::class.java) { Soap.parseQueuePage(mapOf("Result" to """<!doctype DIDL-Lite><DIDL-Lite/>""")) }
        // refused by our own check, before any parser (the JDK's feature would refuse a DOCTYPE too; Android's doesn't)
        for (text in listOf(dtd, """<x><! ENTITY b "c"></x>""")) {
            assertEquals("a DOCTYPE or ENTITY declaration", assertThrows(SAXException::class.java) { parseXml(text) }.message)
        }
    }

    @Test
    fun `a speaker trickling its reply is cut by the call's deadline`() {
        val server = ServerSocket(0, 5, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            try {
                server.accept().use { s ->
                    val out = s.getOutputStream()
                    out.write("HTTP/1.1 200 OK\r\nContent-Type: text/xml\r\nContent-Length: 100000\r\n\r\n".toByteArray())
                    // one byte every 100 ms: the 5 s read timeout never fires
                    repeat(1000) { out.write('x'.code); out.flush(); Thread.sleep(100) }
                }
            } catch (_: SocketException) {
            }
        }
        server.use {
            val start = System.nanoTime()
            assertThrows(SpeakerUnreachable::class.java) { Soap(500) { Soap.client() }.get("127.0.0.1", server.localPort, "/xml/device_description.xml") }
            assertTrue("cut by the 500 ms deadline", System.nanoTime() - start < 3_000_000_000L)
        }
    }
}
