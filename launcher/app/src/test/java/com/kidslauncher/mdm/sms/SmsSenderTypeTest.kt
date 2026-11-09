package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsSenderType
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 77 D4 rule 1 (QA H2): the TP-OA's type from the raw SMS-DELIVER, captured-style and crafted PDUs. */
class SmsSenderTypeTest {
    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** SMSC, first octet (SMS-DELIVER), the OA length in semi-octets, its type octet, the address, then PID, DCS, the
     * time stamp and an empty text. */
    private fun deliver(oaLength: Int, toa: Int, address: String, first: Int = 0x04, smsc: String = "07914740000000F0") =
        hex(smsc + "%02X%02X%02X".format(first, oaLength, toa) + address + "0000" + "62110911000040" + "00")

    @Test
    fun `an international number`() {
        // +4791234567: 10 digits, TON 001 (0x91), BCD swapped.
        assertEquals(SenderKind.NUMBER, SmsSenderType.of(deliver(10, 0x91, "7419325476"), "3gpp"))
    }

    @Test
    fun `a national number and a short code are numbers`() {
        assertEquals(SenderKind.NUMBER, SmsSenderType.of(deliver(8, 0xA1, "19325476"), "3gpp"))
        assertEquals(SenderKind.NUMBER, SmsSenderType.of(deliver(4, 0x81, "2222"), "3gpp"))
    }

    @Test
    fun `type of number 101 is a sender name - also one that reads like a number or 112`() {
        // "DNB" packed GSM-7: 3 octets, 6 semi-octets, TOA 0xD0.
        assertEquals(SenderKind.ALPHANUMERIC, SmsSenderType.of(deliver(6, 0xD0, "C4A708"), "3gpp"))
        // "+4791234567" as text: 11 characters, 20 semi-octets.
        assertEquals(SenderKind.ALPHANUMERIC, SmsSenderType.of(deliver(20, 0xD0, "2B1A0D96C564B3D96C36"), "3gpp"))
        // "112", 6 semi-octets.
        assertEquals(SenderKind.ALPHANUMERIC, SmsSenderType.of(deliver(6, 0xD0, "B1580C"), "3gpp"))
    }

    @Test
    fun `without an SMSC address it still reads`() {
        assertEquals(SenderKind.NUMBER, SmsSenderType.of(deliver(10, 0x91, "7419325476", smsc = "00"), "3gpp"))
    }

    @Test
    fun `malformed PDUs`() {
        val good = deliver(10, 0x91, "7419325476")
        assertEquals("cut in the address", SenderKind.MALFORMED, SmsSenderType.of(good.copyOf(12), "3gpp"))
        assertEquals("ends right after it", SenderKind.MALFORMED, SmsSenderType.of(good.copyOf(16), "3gpp"))
        assertEquals("empty", SenderKind.MALFORMED, SmsSenderType.of(ByteArray(0), "3gpp"))
        assertEquals("null", SenderKind.MALFORMED, SmsSenderType.of(null, "3gpp"))
        assertEquals("a status report", SenderKind.MALFORMED, SmsSenderType.of(deliver(10, 0x91, "7419325476", first = 0x06), "3gpp"))
        assertEquals("an OA of 0", SenderKind.MALFORMED, SmsSenderType.of(deliver(0, 0x91, ""), "3gpp"))
        assertEquals("an OA over 20 semi-octets", SenderKind.MALFORMED, SmsSenderType.of(deliver(22, 0xD0, "00".repeat(11)), "3gpp"))
        assertEquals("a type octet without bit 7", SenderKind.MALFORMED, SmsSenderType.of(deliver(10, 0x11, "7419325476"), "3gpp"))
        assertEquals("an SMSC length past the end", SenderKind.MALFORMED, SmsSenderType.of(hex("FF00"), "3gpp"))
        assertEquals("3gpp2 until step 0 says it occurs", SenderKind.MALFORMED, SmsSenderType.of(good, "3gpp2"))
        assertEquals("no format", SenderKind.MALFORMED, SmsSenderType.of(good, null))
    }

    @Test
    fun `every part must agree on the kind and the address`() {
        assertEquals(SenderKind.NUMBER, SmsSenderType.ofParts(listOf(SenderKind.NUMBER, SenderKind.NUMBER), listOf("+47912", "+47912")))
        assertEquals(SenderKind.MALFORMED, SmsSenderType.ofParts(listOf(SenderKind.NUMBER, SenderKind.ALPHANUMERIC), listOf("+47912", "+47912")))
        assertEquals(SenderKind.MALFORMED, SmsSenderType.ofParts(listOf(SenderKind.NUMBER, SenderKind.NUMBER), listOf("+47912", "+47913")))
        assertEquals(SenderKind.MALFORMED, SmsSenderType.ofParts(emptyList(), emptyList()))
        assertEquals(SenderKind.MALFORMED, SmsSenderType.ofParts(listOf(SenderKind.NUMBER), listOf("+47912", "+47912")))
    }
}
