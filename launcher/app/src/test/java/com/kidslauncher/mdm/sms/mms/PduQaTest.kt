package com.kidslauncher.mdm.sms.mms

import com.kidslauncher.mdm.sms.mms.PduVectors.CC
import com.kidslauncher.mdm.sms.mms.PduVectors.FROM
import com.kidslauncher.mdm.sms.mms.PduVectors.TO
import com.kidslauncher.mdm.sms.mms.PduVectors.UTF_8
import com.kidslauncher.mdm.sms.vendor.pdu.PduParser
import com.kidslauncher.mdm.sms.vendor.pdu.SendReq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QA of design 77 step 3a (docs/review/qa-sms-mms-codec.md). The H14 rows check the address type on every
 * address field and encoding D10 reads; the composer rows are the review's M1 and fail until it is fixed.
 */
class PduQaTest {
    private val plmn = "${PduVectors.SENDER}/TYPE=PLMN"

    /** Encoded-string-value with a charset: Value-length, Short-integer charset, Text-string. */
    private fun withCharset(text: String) = PduBytes().withLength(PduBytes().byte(UTF_8).text(text))

    private fun retrieveWith(from: PduBytes, to: PduBytes, cc: PduBytes): ByteArray = PduBytes()
        .byte(PduVectors.MESSAGE_TYPE, 0x84)
        .byte(PduVectors.TRANSACTION).text(PduVectors.TRANSACTION_ID)
        .byte(PduVectors.VERSION, 0x92)
        .byte(PduVectors.DATE).longInteger(PduVectors.DATE_SECONDS)
        .raw(from.build()).raw(to.build()).raw(cc.build())
        .byte(PduVectors.CONTENT_TYPE, PduVectors.CT_MULTIPART_MIXED)
        .uintvar(0)
        .build()

    @Test
    fun `H14 the type survives on From, To and Cc with a charset, and without`() {
        val from = PduBytes().byte(FROM).withLength(PduBytes().byte(0x80).raw(withCharset(plmn).build()))
        val to = PduBytes().byte(TO).raw(withCharset(plmn).build()).byte(TO).text("${PduVectors.RECIPIENT}/TYPE=PLMN")
        val cc = PduBytes().byte(CC).raw(withCharset(plmn).build()).byte(CC).text("${PduVectors.RECIPIENT}/TYPE=PLMN")
        val m = (MmsCodec.parseRetrieveConf(retrieveWith(from, to, cc)) as MmsParseResult.Ok).value
        assertEquals("PLMN", m.from!!.type)
        assertEquals(PduVectors.SENDER, m.from!!.value)
        assertEquals(listOf("PLMN", "PLMN"), m.to.map { it.type })
        assertEquals(listOf("PLMN", "PLMN"), m.cc.map { it.type })

        val n = (MmsCodec.parseNotification(PduVectors.notification(PduBytes().byte(FROM).withLength(PduBytes().byte(0x80).raw(withCharset(plmn).build()))))
            as MmsParseResult.Ok).value
        assertEquals(MmsAddress(plmn), n.from)
    }

    @Test
    fun `H14 an insert-address-token is no sender on a retrieve-conf either`() {
        val hidden = PduBytes().byte(FROM).withLength(PduBytes().byte(0x81))
        val m = (MmsCodec.parseRetrieveConf(retrieveWith(hidden, PduBytes(), PduBytes())) as MmsParseResult.Ok).value
        assertNull(m.from)
    }

    @Test
    fun `M1 a NUL in the subject can't add a header to the send-req`() {
        val result = MmsCodec.composeSendReq(
            to = listOf(PduVectors.RECIPIENT),
            parts = listOf(MmsOutgoingPart.text("hei")),
            subject = "Hei\u0000\u0081+4700000099",
            transactionId = "T-fake-qa",
            nowSeconds = PduVectors.DATE_SECONDS,
        )
        // Either refused, or composed so that no Bcc appears when the PDU is read back.
        if (result is MmsComposeResult.Ok) {
            val req = PduParser(result.bytes, true).parseOrThrow() as SendReq
            assertNull("an injected Bcc: ${req.bcc?.map { it.string }}", req.bcc)
        } else {
            assertTrue(result is MmsComposeResult.Failed)
        }
    }

    @Test
    fun `M1 a NUL in the transaction id can't add a header to the send-req`() {
        val result = MmsCodec.composeSendReq(
            to = listOf(PduVectors.RECIPIENT),
            parts = listOf(MmsOutgoingPart.text("hei")),
            transactionId = "T\u0000\u0081+4700000099",
            nowSeconds = PduVectors.DATE_SECONDS,
        )
        if (result is MmsComposeResult.Ok) {
            val req = PduParser(result.bytes, true).parseOrThrow() as SendReq
            assertNull("an injected Bcc: ${req.bcc?.map { it.string }}", req.bcc)
        } else {
            assertTrue(result is MmsComposeResult.Failed)
        }
    }
}
