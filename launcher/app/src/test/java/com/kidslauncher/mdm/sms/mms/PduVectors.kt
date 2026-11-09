package com.kidslauncher.mdm.sms.mms

import java.io.ByteArrayOutputStream

/**
 * Design 77 step 3a: the golden PDUs, built byte by byte here (not with the composer, so a bug shared by the
 * composer and the parser can't hide). Synthetic only: numbers in +4700000000-style (not assignable in Norway), an
 * `.invalid` MMSC host, a made-up JPEG.
 */
internal class PduBytes {
    private val out = ByteArrayOutputStream()

    fun byte(vararg values: Int) = apply { values.forEach { out.write(it) } }

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }

    /** Text-string: the octets and End-of-string. */
    fun text(value: String) = apply { raw(value.toByteArray(Charsets.ISO_8859_1)).byte(0) }

    fun uintvar(value: Long) = apply { raw(encodeUintvar(value)) }

    fun valueLength(length: Int) = apply { if (length <= 30) byte(length) else byte(31).uintvar(length.toLong()) }

    /** Long-integer: Short-length, then the value big-endian in as few octets as it needs. */
    fun longInteger(value: Long) = apply {
        val octets = generateSequence(value) { it ushr 8 }.takeWhile { it != 0L }.count().coerceAtLeast(1)
        byte(octets)
        for (i in octets - 1 downTo 0) byte(((value ushr (8 * i)) and 0xFF).toInt())
    }

    /** Value-length then the inner bytes, for the general forms. */
    fun withLength(inner: PduBytes) = apply { val b = inner.build(); valueLength(b.size).raw(b) }

    /** A body part: header length, data length, the content type and headers, the data. */
    fun part(headers: PduBytes, data: ByteArray) = apply {
        val h = headers.build()
        uintvar(h.size.toLong()).uintvar(data.size.toLong()).raw(h).raw(data)
    }

    fun build(): ByteArray = out.toByteArray()

    companion object {
        fun encodeUintvar(value: Long): ByteArray {
            val groups = ArrayList<Int>()
            var v = value
            do {
                groups.add((v and 0x7F).toInt())
                v = v ushr 7
            } while (v != 0L)
            groups.reverse()
            return ByteArray(groups.size) { i -> (if (i < groups.size - 1) groups[i] or 0x80 else groups[i]).toByte() }
        }
    }
}

internal object PduVectors {
    const val SENDER = "+4700000000"
    const val RECIPIENT = "+4700000001"
    const val CONTENT_LOCATION = "http://mmsc.example.invalid/m/0001"
    const val TRANSACTION_ID = "T-fake-0001"
    const val MESSAGE_ID = "fake-msg-0001"
    const val EXPIRY_SECONDS = 1_800_000_000L
    const val DATE_SECONDS = 1_790_000_000L
    const val MESSAGE_SIZE = 16_384L
    const val TEXT = "Hei fra testen, æøå ✓"

    // Header field codes (OMA-MMS-ENC) and well-known content types (WSP).
    const val MESSAGE_TYPE = 0x8C
    const val TRANSACTION = 0x98
    const val VERSION = 0x8D
    const val FROM = 0x89
    const val TO = 0x97
    const val CC = 0x82
    const val SUBJECT = 0x96
    const val DATE = 0x85
    const val MESSAGE_ID_FIELD = 0x8B
    const val MESSAGE_CLASS = 0x8A
    const val MESSAGE_SIZE_FIELD = 0x8E
    const val EXPIRY = 0x88
    const val LOCATION = 0x83
    const val CONTENT_TYPE = 0x84
    const val CT_TEXT_PLAIN = 0x83
    const val CT_IMAGE_JPEG = 0x9E
    const val CT_MULTIPART_MIXED = 0xA3
    const val CT_MULTIPART_ALTERNATIVE = 0xA6
    const val CT_MULTIPART_RELATED = 0xB3
    const val PART_CONTENT_LOCATION = 0x8E
    const val PART_CONTENT_ID = 0xC0
    const val PART_DISPOSITION = 0xC5
    const val PARAM_CHARSET = 0x81
    const val PARAM_START = 0x8A
    const val PARAM_TYPE = 0x89
    const val UTF_8 = 0xEA

    /** A made-up JPEG: SOI, an APP0 "JFIF" segment, filler, EOI. Never decoded here. */
    val jpeg: ByteArray = PduBytes()
        .byte(0xFF, 0xD8, 0xFF, 0xE0, 0x00, 0x10).raw("JFIF".toByteArray()).byte(0x00, 0x01, 0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00)
        .raw(ByteArray(64) { (it * 7).toByte() })
        .byte(0xFF, 0xD9)
        .build()

    fun from(address: String = "$SENDER/TYPE=PLMN"): PduBytes =
        PduBytes().byte(FROM).withLength(PduBytes().byte(0x80).text(address))

    /** A valid m-notification-ind as a carrier's WAP push carries it. */
    fun notification(from: PduBytes = from()): ByteArray = PduBytes()
        .byte(MESSAGE_TYPE, 0x82)
        .byte(TRANSACTION).text(TRANSACTION_ID)
        .byte(VERSION, 0x92)
        .raw(from.build())
        .byte(MESSAGE_CLASS, 0x80)
        .byte(MESSAGE_SIZE_FIELD).longInteger(MESSAGE_SIZE)
        .byte(EXPIRY).withLength(PduBytes().byte(0x80).longInteger(EXPIRY_SECONDS))
        .byte(LOCATION).text(CONTENT_LOCATION)
        .build()

    fun jpegPart(): PduBytes = PduBytes().byte(CT_IMAGE_JPEG)
        .byte(PART_CONTENT_LOCATION).text("image000.jpg")
        .byte(PART_CONTENT_ID).byte(0x22).text("<image000>")

    fun textPart(): PduBytes = PduBytes()
        .withLength(PduBytes().byte(CT_TEXT_PLAIN, PARAM_CHARSET, UTF_8))
        .byte(PART_CONTENT_LOCATION).text("text000.txt")

    /** The headers of an m-retrieve-conf, up to and including its Content-Type. */
    fun retrieveHeaders(contentType: PduBytes = PduBytes().byte(CT_MULTIPART_MIXED)): PduBytes = PduBytes()
        .byte(MESSAGE_TYPE, 0x84)
        .byte(TRANSACTION).text(TRANSACTION_ID)
        .byte(VERSION, 0x92)
        .byte(MESSAGE_ID_FIELD).text(MESSAGE_ID)
        .byte(DATE).longInteger(DATE_SECONDS)
        .raw(from().build())
        .byte(TO).text("$RECIPIENT/TYPE=PLMN")
        .byte(SUBJECT).text("Bilde")
        .byte(CONTENT_TYPE).raw(contentType.build())

    /** A valid m-retrieve-conf: multipart/mixed with a JPEG part and a UTF-8 text part. */
    fun retrieveConf(): ByteArray = retrieveHeaders()
        .uintvar(2)
        .part(jpegPart(), jpeg)
        .part(textPart(), TEXT.toByteArray(Charsets.UTF_8))
        .build()

    /** A valid m-send-conf. */
    fun sendConf(): ByteArray = PduBytes()
        .byte(MESSAGE_TYPE, 0x81)
        .byte(TRANSACTION).text(TRANSACTION_ID)
        .byte(VERSION, 0x92)
        .byte(0x92, 0x80) // X-Mms-Response-Status: Ok
        .byte(MESSAGE_ID_FIELD).text(MESSAGE_ID)
        .build()
}
