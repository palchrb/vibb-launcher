package com.kidslauncher.mdm.sms.mms

import com.kidslauncher.mdm.sms.vendor.pdu.AcknowledgeInd
import com.kidslauncher.mdm.sms.vendor.pdu.NotifyRespInd
import com.kidslauncher.mdm.sms.vendor.pdu.PduHeaders
import com.kidslauncher.mdm.sms.vendor.pdu.PduParser
import com.kidslauncher.mdm.sms.vendor.pdu.SendReq
import org.junit.Assert.assertArrayEquals
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
        val resp = PduParser(MmsCodec.composeNotifyResp("T-fake-0001", MmsNotifyStatus.RETRIEVED)!!, true).parseOrThrow() as NotifyRespInd
        assertEquals("T-fake-0001", String(resp.transactionId))
        assertEquals(PduHeaders.STATUS_RETRIEVED, resp.status)

        val ack = PduParser(MmsCodec.composeAcknowledge("T-fake-0001")!!, true).parseOrThrow() as AcknowledgeInd
        assertEquals("T-fake-0001", String(ack.transactionId))
    }
}
