package com.kidslauncher.mdm.sms.mms

import com.kidslauncher.mdm.sms.mms.PduVectors.CONTENT_TYPE
import com.kidslauncher.mdm.sms.mms.PduVectors.CT_IMAGE_JPEG
import com.kidslauncher.mdm.sms.mms.PduVectors.CT_MULTIPART_ALTERNATIVE
import com.kidslauncher.mdm.sms.mms.PduVectors.CT_MULTIPART_MIXED
import com.kidslauncher.mdm.sms.mms.PduVectors.CT_MULTIPART_RELATED
import com.kidslauncher.mdm.sms.mms.PduVectors.CT_TEXT_PLAIN
import com.kidslauncher.mdm.sms.mms.PduVectors.MESSAGE_TYPE
import com.kidslauncher.mdm.sms.mms.PduVectors.PARAM_CHARSET
import com.kidslauncher.mdm.sms.mms.PduVectors.PARAM_START
import com.kidslauncher.mdm.sms.mms.PduVectors.PARAM_TYPE
import com.kidslauncher.mdm.sms.mms.PduVectors.PART_CONTENT_ID
import com.kidslauncher.mdm.sms.mms.PduVectors.PART_CONTENT_LOCATION
import com.kidslauncher.mdm.sms.mms.PduVectors.PART_DISPOSITION
import com.kidslauncher.mdm.sms.mms.PduVectors.TO
import com.kidslauncher.mdm.sms.vendor.pdu.PduParser
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 77 §8.1: one crafted PDU per hardened spot of the vendored parser (the H numbers are its README's). Each
 * would throw, allocate an attacker's size, loop or misparse in upstream's copy.
 */
class PduHardeningTest {
    private fun retrieve(bytes: ByteArray) = MmsCodec.parseRetrieveConf(bytes)

    private fun failed(reason: MmsParseFailure) = MmsParseResult.Failed(reason)

    private fun message(bytes: ByteArray): MmsMessage {
        val result = retrieve(bytes)
        assertTrue("expected Ok, got $result", result is MmsParseResult.Ok)
        return (result as MmsParseResult.Ok).value
    }

    /** A retrieve-conf of [parts] after the golden headers. */
    private fun withBody(body: PduBytes, contentType: PduBytes = PduBytes().byte(CT_MULTIPART_MIXED)): ByteArray =
        PduVectors.retrieveHeaders(contentType).raw(body.build()).build()

    @Test
    fun `H1 input over the cap is refused before parsing, a push over 2 KB too`() {
        val golden = PduVectors.retrieveConf()
        assertEquals(failed(MmsParseFailure.TOO_LARGE), MmsCodec.parseRetrieveConf(golden, maxBytes = golden.size - 1))
        assertTrue(MmsCodec.parseRetrieveConf(golden, maxBytes = golden.size) is MmsParseResult.Ok)
        assertEquals(failed(MmsParseFailure.TOO_LARGE), MmsCodec.parseNotification(PduVectors.notification() + ByteArray(2048)))
        // The parser's own check, for a caller that skips the API.
        assertEquals(null, PduParser(golden, true, 10).parse())
    }

    @Test
    fun `H2 every truncation of the golden vectors fails typed, none reads past the end as 0xFF`() {
        for (golden in listOf(PduVectors.notification(), PduVectors.retrieveConf(), PduVectors.sendConf())) {
            for (end in 0 until golden.size) {
                val result = MmsCodec.parseRetrieveConf(golden.copyOf(end))
                assertFalse("cut at $end: $result", result == failed(MmsParseFailure.INTERNAL))
            }
        }
        // The Message-Size's Long-integer says 4 octets and the input ends after 1.
        val cut = PduBytes().byte(MESSAGE_TYPE, 0x82).byte(PduVectors.MESSAGE_SIZE_FIELD, 0x04, 0x01).build()
        assertEquals(failed(MmsParseFailure.TRUNCATED), MmsCodec.parseNotification(cut))
    }

    @Test
    fun `H3 a uintvar of six octets, or beyond 31 bits, is a bad length`() {
        val six = PduBytes().byte(0x81, 0x80, 0x80, 0x80, 0x80, 0x00)
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), retrieve(withBody(six)))
        val big = PduBytes().raw(PduBytes.encodeUintvar(1L shl 31))
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), retrieve(withBody(big)))
    }

    @Test
    fun `H4 a Value-length beyond the input is a bad length (GrapheneOS's oversized Content-Type parameter)`() {
        val pdu = byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x84.toByte(), 0x1F, 0x87.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x83.toByte(), 0x00)
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), MmsCodec.parseNotification(pdu))
        // A Content-Type general form whose length runs past the end (the platform's 2026 parseContentType fix).
        val contentType = PduBytes().byte(0x1F).uintvar(5000).byte(CT_MULTIPART_MIXED)
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), retrieve(PduVectors.retrieveHeaders(contentType).build()))
        // GrapheneOS's invalid From value length.
        val from = byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x89.toByte(), 0x40)
        assertTrue(MmsCodec.parseNotification(from) is MmsParseResult.Failed)
    }

    @Test
    fun `H5 a part's data length beyond the input is refused before any allocation`() {
        // GrapheneOS's case: one part, header length 1, data length 0x1FFFFF... far beyond the input.
        val body = PduBytes().uintvar(1).uintvar(1).byte(0x8F, 0xFF, 0xFF, 0x7F).byte(CT_TEXT_PLAIN).raw("AB".toByteArray())
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), retrieve(withBody(body)))
        // Upstream's worst case: ~2 GB asked for by a few bytes (would be OutOfMemoryError, not a typed failure).
        val huge = PduBytes().uintvar(1).uintvar(1).uintvar(Int.MAX_VALUE.toLong()).byte(CT_TEXT_PLAIN)
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), retrieve(withBody(huge)))
        // A header length beyond the input.
        val header = PduBytes().uintvar(1).uintvar(100_000).uintvar(0).byte(CT_TEXT_PLAIN)
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), retrieve(withBody(header)))
    }

    @Test
    fun `H6 an unknown part header and a Content-Disposition's extra parameters are skipped, never read into a new array`() {
        val headers = PduBytes().byte(CT_IMAGE_JPEG)
            .byte(PART_DISPOSITION).withLength(PduBytes().byte(0x81).byte(0x98).text("a.jpg").raw(ByteArray(40)))
            .byte(0xB0).raw(ByteArray(20)) // an unassigned well-known header: the rest of the headers is skipped
        val m = message(withBody(PduBytes().uintvar(1).part(headers, PduVectors.jpeg)))
        assertEquals("a.jpg", m.parts.single().name)
        assertArrayEquals(PduVectors.jpeg, m.parts.single().data)
        // A disposition whose length runs past the part.
        val over = PduBytes().byte(CT_IMAGE_JPEG).byte(PART_DISPOSITION).byte(0x1F).uintvar(1_000_000).byte(0x81)
        assertTrue(retrieve(withBody(PduBytes().uintvar(1).part(over, PduVectors.jpeg))) is MmsParseResult.Failed)
    }

    @Test
    fun `H7 an unknown well-known content type is any type, not an index out of bounds`() {
        for (code in listOf(0xD3, 0xFF)) {
            val m = message(withBody(PduBytes().uintvar(1).part(PduBytes().byte(code), PduVectors.jpeg)))
            assertEquals("*/*", m.parts.single().contentType)
        }
        // GrapheneOS's push with Content-Type 0xD3 and nothing else: refused, not thrown.
        val push = byteArrayOf(0x8C.toByte(), 0x82.toByte(), 0x84.toByte(), 0xD3.toByte())
        assertEquals(failed(MmsParseFailure.REJECTED), MmsCodec.parseNotification(push))
    }

    @Test
    fun `H8 multipart-alternative takes its first child, one level deep at most, and an empty child ends the parse`() {
        val child = PduBytes().uintvar(2)
            .part(PduBytes().withLength(PduBytes().byte(CT_TEXT_PLAIN, PARAM_CHARSET, PduVectors.UTF_8)), "first".toByteArray())
            .part(PduBytes().byte(CT_TEXT_PLAIN), "second".toByteArray())
            .build()
        val one = PduBytes().uintvar(1).part(PduBytes().byte(CT_MULTIPART_ALTERNATIVE), child)
        assertEquals("first", message(withBody(one)).parts.single().text())

        val nested = PduBytes().uintvar(1).part(PduBytes().byte(CT_MULTIPART_ALTERNATIVE), one.build()).build()
        val deep = PduBytes().uintvar(1).part(PduBytes().byte(CT_MULTIPART_ALTERNATIVE), nested)
        assertEquals(failed(MmsParseFailure.TOO_DEEP), retrieve(withBody(deep)))

        val empty = PduBytes().uintvar(1).part(PduBytes().byte(CT_MULTIPART_ALTERNATIVE), PduBytes().uintvar(0).build())
        assertEquals(failed(MmsParseFailure.BAD_STRUCTURE), retrieve(withBody(empty)))
        // Upstream dereferenced the null of a child that failed to parse.
        val broken = PduBytes().uintvar(1).part(PduBytes().byte(CT_MULTIPART_ALTERNATIVE), byteArrayOf(0x01, 0x00, 0x00))
        assertTrue(retrieve(withBody(broken)) is MmsParseResult.Failed)
    }

    @Test
    fun `H9 at most 20 parts and 300 header fields`() {
        fun parts(n: Int) = PduBytes().uintvar(n.toLong()).apply { repeat(n) { part(PduBytes().byte(CT_TEXT_PLAIN), "x".toByteArray()) } }
        assertEquals(20, message(withBody(parts(20))).parts.size)
        assertEquals(failed(MmsParseFailure.TOO_MANY_PARTS), retrieve(withBody(parts(21))))
        // The count is judged before any part is read.
        assertEquals(failed(MmsParseFailure.TOO_MANY_PARTS), retrieve(withBody(PduBytes().uintvar(1_000_000))))

        val headers = PduBytes().byte(MESSAGE_TYPE, 0x84)
        repeat(300) { headers.byte(TO).text("+4700000001/TYPE=PLMN") }
        val pdu = headers.byte(CONTENT_TYPE, CT_MULTIPART_MIXED).uintvar(0).build()
        assertEquals(failed(MmsParseFailure.TOO_MANY_HEADERS), retrieve(pdu))
    }

    @Test
    fun `H10 empty values where upstream dereferenced null parse as absent`() {
        val headers = PduBytes()
            .withLength(PduBytes().byte(CT_TEXT_PLAIN, PARAM_CHARSET).byte(0x00)) // charset as an empty Text-string
            .byte(PART_DISPOSITION).withLength(PduBytes().byte(0x00)) // an empty Token-text disposition
            .byte(PART_DISPOSITION).withLength(PduBytes().byte(0x81, 0x98, 0x00)) // an empty filename
            .byte(0x41).byte(0x00).byte(0x00) // an untyped header with an empty name and value
        val m = message(withBody(PduBytes().uintvar(1).part(headers, "hei".toByteArray())))
        assertEquals("hei", m.parts.single().text())

        // An X-Mms-Element-Descriptor with a well-known type parameter: upstream put it into a null map.
        val descriptor = PduBytes().byte(0xB2).withLength(PduBytes().text("x").byte(0x83, 0x83))
        val pdu = PduBytes().raw(descriptor.build()).raw(PduVectors.retrieveHeaders().build()).uintvar(0).build()
        assertTrue(retrieve(pdu) is MmsParseResult.Ok)
    }

    @Test(timeout = 5_000)
    fun `H11 a Content-Type parameter that can't be skipped ends the loop`() {
        // An unknown parameter claiming the whole remaining length, then the input ends: one pass, not 2^31.
        val contentType = PduBytes().withLength(PduBytes().byte(CT_MULTIPART_MIXED, 0xFE).raw(ByteArray(20)))
        val result = retrieve(PduVectors.retrieveHeaders(contentType).uintvar(0).build())
        assertTrue(result is MmsParseResult.Ok)
    }

    @Test
    fun `H12 the start parameter is per parse, so parallel parses never borrow each other's`() {
        val start = PduBytes().withLength(PduBytes().byte(CT_MULTIPART_RELATED, PARAM_START).text("<b>").byte(PARAM_TYPE).text("text/plain"))
        fun body() = PduBytes().uintvar(2)
            .part(PduBytes().byte(CT_TEXT_PLAIN).byte(PART_CONTENT_ID).byte(0x22).text("<a>"), "a".toByteArray())
            .part(PduBytes().byte(CT_TEXT_PLAIN).byte(PART_CONTENT_ID).byte(0x22).text("<b>"), "b".toByteArray())
        val related = withBody(body(), start)
        val mixed = withBody(body())
        assertEquals(listOf("b", "a"), message(related).parts.map { it.text() })
        assertEquals(listOf("a", "b"), message(mixed).parts.map { it.text() })

        val pool = Executors.newFixedThreadPool(4)
        val results = (0 until 2000).map { i ->
            pool.submit<Boolean> {
                val expected = if (i % 2 == 0) listOf("b", "a") else listOf("a", "b")
                message(if (i % 2 == 0) related else mixed).parts.map { it.text() } == expected
            }
        }
        val wrong = results.count { !it.get() }
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        assertEquals(0, wrong)
    }

    @Test
    fun `H13 a Content-Transfer-Encoding is kept, never decoded`() {
        // Upstream's untyped-header parse drops the name's first octet, so "XContent-..." reads as the real name.
        val headers = PduBytes().byte(CT_TEXT_PLAIN).text("XContent-Transfer-Encoding").text("base64")
        val m = message(withBody(PduBytes().uintvar(1).part(headers, "aGVp".toByteArray())))
        assertEquals("aGVp", m.parts.single().text())
    }

    @Test
    fun `H14 addresses keep their type, so a name sender can't pass as a number`() {
        val n = (MmsCodec.parseNotification(PduVectors.notification(PduVectors.from("DNB/TYPE=ALPHA"))) as MmsParseResult.Ok).value
        assertEquals("DNB", n.from!!.value)
        assertEquals("ALPHA", n.from!!.type)
        val bare = (MmsCodec.parseNotification(PduVectors.notification(PduVectors.from(PduVectors.SENDER))) as MmsParseResult.Ok).value
        assertEquals(null, bare.from!!.type)
    }

    @Test
    fun `H15 delivery and read reports are not parsed`() {
        for (type in listOf(0x86, 0x87, 0x88)) {
            val pdu = PduBytes().byte(MESSAGE_TYPE, type).byte(PduVectors.VERSION, 0x92)
                .byte(PduVectors.MESSAGE_ID_FIELD).text(PduVectors.MESSAGE_ID).byte(TO).text("+4700000001/TYPE=PLMN")
                .byte(PduVectors.DATE).longInteger(PduVectors.DATE_SECONDS).byte(0x95, 0x80).byte(0x9B, 0x80)
                .raw(PduVectors.from().build()).build()
            assertEquals(failed(MmsParseFailure.REJECTED), MmsCodec.parseNotification(pdu))
        }
    }

    @Test
    fun `H16 a Long-integer of more than 8 octets keeps its reason`() {
        val pdu = PduBytes().byte(MESSAGE_TYPE, 0x82).byte(PduVectors.DATE, 0x09).raw(ByteArray(9)).build()
        assertEquals(failed(MmsParseFailure.BAD_LENGTH), MmsCodec.parseNotification(pdu))
    }

    @Test
    fun `the vendored package has no Android imports and stays out of the MIT directories`() {
        val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
        val dir = File(main, "java/com/kidslauncher/mdm/sms/vendor/pdu")
        val sources = dir.listFiles { f -> f.name.endsWith(".java") }!!.toList()
        assertTrue(sources.size > 10)
        for (file in sources) {
            val text = file.readText()
            assertFalse(file.name, Regex("""^import (android|androidx|com\.android)\.""", RegexOption.MULTILINE).containsMatchIn(text))
            assertTrue(file.name, text.contains("Vibb One"))
        }
        for (mit in listOf("ui/list", "apps")) {
            val names = File(main, "java/com/kidslauncher/mdm/$mit").walk().map { it.name }.toList()
            assertFalse(mit, names.any { it.startsWith("Pdu") || it.startsWith("Mms") })
        }
    }
}
