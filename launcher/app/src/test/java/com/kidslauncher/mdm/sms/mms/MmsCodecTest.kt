package com.kidslauncher.mdm.sms.mms

import com.kidslauncher.mdm.sms.vendor.pdu.AcknowledgeInd
import com.kidslauncher.mdm.sms.vendor.pdu.NotifyRespInd
import com.kidslauncher.mdm.sms.vendor.pdu.PduHeaders
import com.kidslauncher.mdm.sms.vendor.pdu.PduParser
import com.kidslauncher.mdm.sms.vendor.pdu.SendReq
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 77 step 3a: the golden PDUs parse to exactly what they carry, and what the composer makes parses back. */
class MmsCodecTest {
    private fun <T> ok(result: MmsParseResult<T>): T {
        assertTrue("expected Ok, got $result", result is MmsParseResult.Ok)
        return (result as MmsParseResult.Ok).value
    }

    @Test
    fun `the notification golden vector parses to its headers, the sender's type kept`() {
        val n = ok(MmsCodec.parseNotification(PduVectors.notification()))
        assertEquals(PduVectors.TRANSACTION_ID, n.transactionId)
        assertEquals(PduVectors.CONTENT_LOCATION, n.contentLocation)
        assertEquals(MmsAddress("${PduVectors.SENDER}/TYPE=PLMN"), n.from)
        assertEquals(PduVectors.SENDER, n.from!!.value)
        assertEquals("PLMN", n.from!!.type)
        assertEquals(PduVectors.MESSAGE_SIZE, n.messageSize)
        assertEquals(PduVectors.EXPIRY_SECONDS, n.expirySeconds)
        assertEquals("personal", n.messageClass)
        assertNull(n.subject)
        assertEquals(PduHeaders.MMS_VERSION_1_2, n.mmsVersion)
    }

    @Test
    fun `a hidden sender is no address, and an e-mail sender keeps its own form`() {
        val hidden = PduBytes().byte(PduVectors.FROM).withLength(PduBytes().byte(0x81))
        assertNull(ok(MmsCodec.parseNotification(PduVectors.notification(hidden))).from)

        val mail = ok(MmsCodec.parseNotification(PduVectors.notification(PduVectors.from("someone@example.invalid")))).from!!
        assertEquals("someone@example.invalid", mail.value)
        assertNull(mail.type)
    }

    @Test
    fun `the retrieve-conf golden vector parses to its headers, a JPEG part and a text part`() {
        val m = ok(MmsCodec.parseRetrieveConf(PduVectors.retrieveConf()))
        assertEquals(PduVectors.TRANSACTION_ID, m.transactionId)
        assertEquals(PduVectors.MESSAGE_ID, m.messageId)
        assertEquals(PduVectors.DATE_SECONDS, m.dateSeconds)
        assertEquals(MmsAddress("${PduVectors.SENDER}/TYPE=PLMN"), m.from)
        assertEquals(listOf(MmsAddress("${PduVectors.RECIPIENT}/TYPE=PLMN")), m.to)
        assertEquals(emptyList<MmsAddress>(), m.cc)
        assertEquals("Bilde", m.subject)
        assertEquals(PduHeaders.RETRIEVE_STATUS_OK, m.retrieveStatus)
        assertEquals("application/vnd.wap.multipart.mixed", m.contentType)
        assertEquals(2, m.parts.size)

        val picture = m.parts[0]
        assertTrue(picture.isImage)
        assertEquals("image/jpeg", picture.mimeType)
        assertEquals("image000.jpg", picture.contentLocation)
        assertEquals("<image000>", picture.contentId)
        assertArrayEquals(PduVectors.jpeg, picture.data)

        val text = m.parts[1]
        assertTrue(text.isText)
        assertEquals(0x6A, text.charset)
        assertEquals(PduVectors.TEXT, text.text())
    }

    @Test
    fun `the send-conf golden vector parses`() {
        val c = ok(MmsCodec.parseSendConf(PduVectors.sendConf()))
        assertEquals(PduVectors.TRANSACTION_ID, c.transactionId)
        assertEquals(PduVectors.MESSAGE_ID, c.messageId)
        assertTrue(c.isOk)
    }

    @Test
    fun `each parse refuses another PDU type`() {
        assertEquals(MmsParseResult.Failed(MmsParseFailure.UNEXPECTED_TYPE), MmsCodec.parseNotification(PduVectors.sendConf()))
        assertEquals(MmsParseResult.Failed(MmsParseFailure.UNEXPECTED_TYPE), MmsCodec.parseRetrieveConf(PduVectors.notification()))
        assertEquals(MmsParseResult.Failed(MmsParseFailure.UNEXPECTED_TYPE), MmsCodec.parseSendConf(PduVectors.notification()))
    }

    @Test
    fun `a send-req round-trips with SMIL first, then the picture and the text, to the recipients as PLMN`() {
        val composed = MmsCodec.composeSendReq(
            to = listOf(PduVectors.SENDER, PduVectors.RECIPIENT),
            parts = listOf(MmsOutgoingPart.jpeg(PduVectors.jpeg), MmsOutgoingPart.text(PduVectors.TEXT)),
            subject = "Bilde",
            transactionId = "T-fake-send",
            nowSeconds = PduVectors.DATE_SECONDS,
        )
        val bytes = (composed as MmsComposeResult.Ok).bytes
        val req = PduParser(bytes, true).parseOrThrow() as SendReq

        assertEquals("T-fake-send", String(req.transactionId, Charsets.ISO_8859_1))
        assertEquals(listOf("${PduVectors.SENDER}/TYPE=PLMN", "${PduVectors.RECIPIENT}/TYPE=PLMN"), req.to.map { it.string })
        assertEquals(PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR, req.from.string)
        assertEquals("Bilde", req.subject.string)
        assertEquals(PduVectors.DATE_SECONDS, req.date)
        assertEquals("application/vnd.wap.multipart.related", String(req.contentType))

        val body = req.body
        assertEquals(3, body.partsNum)
        assertEquals("application/smil", String(body.getPart(0).contentType))
        val smil = String(body.getPart(0).data, Charsets.UTF_8)
        assertTrue(smil, smil.contains("<img src=\"image000.jpg\"") && smil.contains("<text src=\"text001.txt\""))
        assertEquals("image/jpeg", String(body.getPart(1).contentType))
        assertArrayEquals(PduVectors.jpeg, body.getPart(1).data)
        assertEquals("text/plain", String(body.getPart(2).contentType))
        assertEquals(0x6A, body.getPart(2).charset)
        assertEquals(PduVectors.TEXT, String(body.getPart(2).data, Charsets.UTF_8))
    }

    @Test
    fun `the send-req keeps to its caps and its recipients' form`() {
        val picture = listOf(MmsOutgoingPart.jpeg(PduVectors.jpeg))
        fun failure(result: MmsComposeResult) = (result as MmsComposeResult.Failed).reason
        assertEquals(MmsComposeFailure.NO_RECIPIENTS, failure(MmsCodec.composeSendReq(emptyList(), picture)))
        assertEquals(
            MmsComposeFailure.TOO_MANY_RECIPIENTS,
            failure(MmsCodec.composeSendReq(List(MmsCodec.MAX_RECIPIENTS + 1) { "+47000000%02d".format(it) }, picture)),
        )
        assertEquals(MmsComposeFailure.BAD_RECIPIENT, failure(MmsCodec.composeSendReq(listOf("someone@example.invalid"), picture)))
        assertEquals(MmsComposeFailure.BAD_RECIPIENT, failure(MmsCodec.composeSendReq(listOf("+47 00 00 00 00"), picture)))
        assertEquals(MmsComposeFailure.NO_PARTS, failure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), emptyList())))
        assertEquals(
            MmsComposeFailure.TOO_MANY_PARTS,
            failure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), List(MmsCodec.MAX_PARTS) { MmsOutgoingPart.text("x") })),
        )
        assertEquals(
            MmsComposeFailure.BAD_PART,
            failure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), listOf(MmsOutgoingPart("image/jpeg", ByteArray(0))))),
        )
        // The picture fits the cap but the whole PDU (headers, SMIL) doesn't.
        assertEquals(
            MmsComposeFailure.TOO_LARGE,
            failure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), picture, maxBytes = PduVectors.jpeg.size + 10)),
        )
        assertEquals(
            MmsComposeFailure.TOO_LARGE,
            failure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), picture, maxBytes = PduVectors.jpeg.size - 1)),
        )
    }

    @Test
    fun `the notify-resp and the acknowledge round-trip`() {
        val resp = PduParser((MmsCodec.composeNotifyResp("T-fake-0001", MmsNotifyStatus.RETRIEVED) as MmsComposeResult.Ok).bytes, true).parseOrThrow() as NotifyRespInd
        assertEquals("T-fake-0001", String(resp.transactionId))
        assertEquals(PduHeaders.STATUS_RETRIEVED, resp.status)

        val ack = PduParser((MmsCodec.composeAcknowledge("T-fake-0001") as MmsComposeResult.Ok).bytes, true).parseOrThrow() as AcknowledgeInd
        assertEquals("T-fake-0001", String(ack.transactionId))
    }

    // The fix round of docs/review/qa-sms-mms-codec.md.

    private fun composeFailure(result: MmsComposeResult) = (result as MmsComposeResult.Failed).reason

    private val oneText = listOf(MmsOutgoingPart.text("hei"))

    @Test
    fun `M1 a subject with a control character or over 40 characters is refused`() {
        for (subject in listOf("Hei\u0000\u0081+4700000099", "a\nb", "\u007F", "x\u0085y", "x".repeat(41))) {
            assertEquals(subject, MmsComposeFailure.BAD_SUBJECT, composeFailure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), oneText, subject = subject)))
        }
        assertTrue(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), oneText, subject = "Hei på deg ✓ ".padEnd(40, 'x')) is MmsComposeResult.Ok)
    }

    @Test
    fun `M1 every compose call refuses a transaction id outside printable ASCII, 1 to 64`() {
        for (id in listOf("", "T\u0000\u0081+4700000099", "T fake", "T-æ", "T".repeat(65))) {
            assertEquals(id, MmsComposeFailure.BAD_TRANSACTION_ID, composeFailure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), oneText, transactionId = id)))
            assertEquals(id, MmsComposeFailure.BAD_TRANSACTION_ID, composeFailure(MmsCodec.composeNotifyResp(id, MmsNotifyStatus.RETRIEVED)))
            assertEquals(id, MmsComposeFailure.BAD_TRANSACTION_ID, composeFailure(MmsCodec.composeAcknowledge(id)))
        }
        assertTrue(MmsCodec.composeAcknowledge("T".repeat(64)) is MmsComposeResult.Ok)
    }

    @Test
    fun `N3 only text, JPEG, PNG and GIF parts can be sent`() {
        for (type in listOf("application/smil", "application/vnd.wap.multipart.mixed", "text/html", "image/jpeg; x=y")) {
            assertEquals(type, MmsComposeFailure.BAD_PART, composeFailure(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), listOf(MmsOutgoingPart(type, byteArrayOf(1))))))
        }
    }

    @Test
    fun `L3 a carrier maximum of 0 or below is the default cap, not a 1-byte one`() {
        assertEquals(MmsCodec.DEFAULT_MAX_PDU_BYTES, MmsCodec.capOf(0))
        assertEquals(MmsCodec.DEFAULT_MAX_PDU_BYTES, MmsCodec.capOf(-1))
        assertEquals(MmsCodec.MAX_PDU_BYTES_CEILING, MmsCodec.capOf(Int.MAX_VALUE))
        assertEquals(300_000, MmsCodec.capOf(300_000))
        assertTrue(MmsCodec.parseRetrieveConf(PduVectors.retrieveConf(), maxBytes = 0) is MmsParseResult.Ok)
        assertTrue(MmsCodec.composeSendReq(listOf(PduVectors.SENDER), oneText, maxBytes = -1) is MmsComposeResult.Ok)
    }

    @Test
    fun `L2 header strings over their caps fail as too long`() {
        val longAddress = PduVectors.notification(PduVectors.from("+" + "0".repeat(MmsCodec.MAX_ADDRESS_CHARS) + "/TYPE=PLMN"))
        assertEquals(MmsParseResult.Failed(MmsParseFailure.TOO_LONG), MmsCodec.parseNotification(longAddress))

        val subject = PduBytes().byte(PduVectors.SUBJECT).text("s".repeat(MmsCodec.MAX_SUBJECT_CHARS + 1))
        // On a notification: the retrieve-conf's own Subject would replace it.
        val push = PduBytes().raw(subject.build()).raw(PduVectors.notification()).build()
        assertEquals(MmsParseResult.Failed(MmsParseFailure.TOO_LONG), MmsCodec.parseNotification(push))

        val many = PduBytes()
        repeat(MmsCodec.MAX_ADDRESSES) { many.byte(PduVectors.CC).text("+4700000001/TYPE=PLMN") }
        val crowded = PduBytes().raw(many.build()).raw(PduVectors.retrieveHeaders().build()).uintvar(0).build()
        assertEquals(MmsParseResult.Failed(MmsParseFailure.TOO_LONG), MmsCodec.parseRetrieveConf(crowded))
        val fits = PduBytes().raw(PduBytes().apply { repeat(MmsCodec.MAX_ADDRESSES - 1) { byte(PduVectors.CC).text("+4700000001/TYPE=PLMN") } }.build())
            .raw(PduVectors.retrieveHeaders().build()).uintvar(0).build()
        assertTrue(MmsCodec.parseRetrieveConf(fits) is MmsParseResult.Ok)
    }

    @Test
    fun `N5 a text part decodes at most its cap`() {
        val part = MmsPart("text/plain", 0x6A, null, null, null, "æ".repeat(10_000).toByteArray(Charsets.UTF_8))
        assertEquals("æ".repeat(MmsCodec.MAX_TEXT_CHARS), part.text())
        assertEquals("ææ", part.text(2))
        // A multi-octet character cut at the octet limit leaves no replacement character behind.
        val cut = MmsPart("text/plain", 0x6A, null, null, null, "✓✓✓✓✓".toByteArray(Charsets.UTF_8))
        assertFalse(cut.text(1).contains('\uFFFD'))
    }

    @Test
    fun `the PLMN number takes ASCII digits after an optional plus and nothing else`() {
        assertEquals("+4700000000", MmsAddress("+4700000000/TYPE=PLMN").plmnNumber)
        assertEquals("4700000000", MmsAddress("4700000000/TYPE=PLMN").plmnNumber)
        for (raw in listOf("+4700000000", "+4700000000/TYPE=IPV4", "+47٠٠٠٠٠٠٠٠/TYPE=PLMN", "+47００００００００/TYPE=PLMN", "+47 00000000/TYPE=PLMN", "DNB/TYPE=PLMN", "/TYPE=PLMN")) {
            assertNull(raw, MmsAddress(raw).plmnNumber)
        }
    }
}
