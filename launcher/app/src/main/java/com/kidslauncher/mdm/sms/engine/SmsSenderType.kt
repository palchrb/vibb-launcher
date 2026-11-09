package com.kidslauncher.mdm.sms.engine

/*
 * Design 77 D4 rule 1 (QA H2): the sender's type is read from the raw PDU, never from the text. Android decodes an
 * alphanumeric TP-OA (type of number 101) as GSM-7 text and returns it from `getOriginatingAddress()` without saying
 * so (frameworks/base GsmSmsAddress.java), so a sender *named* "+4791234567" would otherwise pass as the parent.
 * Pure, tested in SmsSenderTypeTest.
 */

enum class SenderKind { NUMBER, ALPHANUMERIC, MALFORMED }

object SmsSenderType {
    /** `SmsMessage.FORMAT_3GPP`; `3gpp2` (CDMA) counts as [SenderKind.MALFORMED] - step 0 checks it never occurs. */
    const val FORMAT_3GPP = "3gpp"

    /** TP-OA's type of number, bits 6-4 of its type-of-address octet: alphanumeric (3GPP 23.040 §9.1.2.5). */
    private const val TON_ALPHANUMERIC = 0b101

    /**
     * The TP-OA type of a 3GPP SMS-DELIVER [pdu] as the platform hands it in `SMS_DELIVER`'s `pdus`: the SMSC address
     * (its length octet, then that many octets), the first octet (TP-MTI 00 = SMS-DELIVER), the OA length (in
     * semi-octets), its type-of-address octet and the address itself. Anything short, another message type, an OA
     * longer than 20 semi-octets (11 GSM-7 characters is 20 for a name, 20 digits for a number) or another [format]
     * is [SenderKind.MALFORMED].
     */
    fun of(pdu: ByteArray?, format: String?): SenderKind {
        if (pdu == null || format != FORMAT_3GPP) return SenderKind.MALFORMED
        var i = 0
        fun octet(): Int? = if (i < pdu.size) pdu[i++].toInt() and 0xff else null
        val smscLength = octet() ?: return SenderKind.MALFORMED
        if (smscLength > 12) return SenderKind.MALFORMED
        i += smscLength
        val first = octet() ?: return SenderKind.MALFORMED
        if (first and 0b11 != 0b00) return SenderKind.MALFORMED
        val oaSemiOctets = octet() ?: return SenderKind.MALFORMED
        if (oaSemiOctets == 0 || oaSemiOctets > 20) return SenderKind.MALFORMED
        val toa = octet() ?: return SenderKind.MALFORMED
        // Bit 7 of a type-of-address octet is always 1.
        if (toa and 0x80 == 0) return SenderKind.MALFORMED
        // The address must be there in full (and the PDU go on past it: PID, DCS, the time stamp).
        if (i + (oaSemiOctets + 1) / 2 >= pdu.size) return SenderKind.MALFORMED
        return if ((toa shr 4) and 0b111 == TON_ALPHANUMERIC) SenderKind.ALPHANUMERIC else SenderKind.NUMBER
    }

    /**
     * One message's sender over all its parts: every part must give the same kind and the same address
     * ([addresses], `getOriginatingAddress()` per part), else [SenderKind.MALFORMED] (FM took the last part's address
     * with a TODO). No parts is malformed too.
     */
    fun ofParts(kinds: List<SenderKind>, addresses: List<String?>): SenderKind {
        if (kinds.isEmpty() || kinds.size != addresses.size) return SenderKind.MALFORMED
        val kind = kinds.first()
        if (kinds.any { it != kind } || addresses.any { it != addresses.first() }) return SenderKind.MALFORMED
        return kind
    }
}
