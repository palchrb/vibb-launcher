package com.kidslauncher.mdm.server

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.xbill.DNS.DClass
import org.xbill.DNS.Message
import org.xbill.DNS.Name
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Type

/** Design 36 §5.2: the DoH request and the checks on its response. */
class DohMessageTest {
    private val query = Message.newQuery(Record.newRecord(Name.fromString("example.com."), Type.A, DClass.IN)).toWire()
    private fun reply(rcode: Int) = Message(0).apply { header.rcode = rcode }.toWire()

    @Test
    fun `the query goes with ID 0 and the answer comes back with its own ID`() {
        val sent = dohQuery(query)
        assertEquals(0, Message(sent).header.id)
        assertArrayEquals(query.copyOfRange(2, query.size), sent.copyOfRange(2, sent.size))
        val got = dohResponse(200, "application/dns-message", reply(Rcode.NOERROR), query) as Attempt.Answer
        assertEquals(Message(query).header.id, Message(got.bytes).header.id)
    }

    @Test
    fun `the media type's parameters are ignored, its case too`() {
        assertTrue(dohResponse(200, "Application/DNS-Message; charset=binary", reply(Rcode.NOERROR), query) is Attempt.Answer)
    }

    @Test
    fun `a wrong type, a missing or too big or unparsable body, a redirect or an error status is http`() {
        val http = Attempt.Transport(BackupReason.HTTP)
        assertEquals(http, dohResponse(200, "text/html", reply(Rcode.NOERROR), query))
        assertEquals(http, dohResponse(200, null, reply(Rcode.NOERROR), query))
        assertEquals(http, dohResponse(200, "application/dns-message", null, query))
        assertEquals(http, dohResponse(200, "application/dns-message", ByteArray(DOH_MAX_RESPONSE + 1), query))
        assertEquals(http, dohResponse(200, "application/dns-message", byteArrayOf(1, 2, 3), query))
        assertEquals(http, dohResponse(301, "application/dns-message", reply(Rcode.NOERROR), query))
        assertEquals(http, dohResponse(403, "application/dns-message", reply(Rcode.NOERROR), query))
    }

    @Test
    fun `REFUSED and SERVFAIL inside a good response are odd, judged by the canary`() {
        assertEquals(OddKind.REFUSED, (dohResponse(200, "application/dns-message", reply(Rcode.REFUSED), query) as Attempt.Odd).kind)
        assertEquals(OddKind.SERVFAIL, (dohResponse(200, "application/dns-message", reply(Rcode.SERVFAIL), query) as Attempt.Odd).kind)
    }
}
