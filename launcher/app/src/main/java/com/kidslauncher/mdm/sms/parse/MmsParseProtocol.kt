package com.kidslauncher.mdm.sms.parse

import android.os.Parcel
import com.kidslauncher.mdm.sms.mms.MmsCodec

/**
 * The isolated parse service's wire (design 77 D8, §8.2): a plain Binder protocol both sides write by hand, so the main
 * process reads every answer field by field and checks each length and count **before** it reads the value - nothing
 * from the isolated process is unparcelled by generated code.
 *
 * Every request carries the input as a pipe (a [android.os.ParcelFileDescriptor]) and its length; every answer starts
 * with a status. Pictures come back as raw RGBA_8888 pixels in a shared-memory file descriptor with their width and
 * height - never an image format; the main process reads them with `pread` (a peer that shrinks the region gets a
 * short read, never a fault - code QA L4).
 */
internal object MmsParseProtocol {
    const val DESCRIPTOR = "me.vibb.launcher.sms.parse.MmsParse"

    /** An m-notification-ind (the WAP push). */
    const val PARSE_NOTIFICATION = android.os.IBinder.FIRST_CALL_TRANSACTION

    /** An m-retrieve-conf (the downloaded message), with or without its pictures decoded (D11). */
    const val PARSE_RETRIEVE = android.os.IBinder.FIRST_CALL_TRANSACTION + 1

    /** An m-send-conf (the MMSC's answer to a send). */
    const val PARSE_SEND_CONF = android.os.IBinder.FIRST_CALL_TRANSACTION + 2

    /** A picture the kid picked (D11): decoded to pixels for the main process to re-encode. */
    const val DECODE_IMAGE = android.os.IBinder.FIRST_CALL_TRANSACTION + 3

    const val STATUS_OK = 0

    /** Not a PDU or picture we accept (the codec's verdict on the bytes). */
    const val STATUS_REJECTED = 1

    /** A part's kind in a [PARSE_RETRIEVE] answer. */
    const val PART_PICTURE = 0
    const val PART_NOT_SHOWN = 1
    const val PART_UNDECODABLE = 2
    const val PART_OTHER = 3

    /** D8: at most 1,600 px a side for a received picture (so at most about 10 MB of pixels). */
    const val PICTURE_MAX_PX = 1600

    /** A picture the kid sends: decoded no larger than this (the re-encode then fits the carrier's limit). */
    const val PICK_MAX_PX = 1280

    /** §8.2: refused before decoding - more than 8,192 px a side or 40 MP. */
    const val DECODE_MAX_SIDE = 8192
    const val DECODE_MAX_PIXELS = 40_000_000L

    /** A picked picture's file, at most (a camera's JPEG is a few MB). */
    const val PICK_MAX_BYTES = 30 * 1024 * 1024

    /** A received text part (D8). */
    const val TEXT_MAX_CHARS = MmsCodec.MAX_TEXT_CHARS

    /** A part's type, as handed back for a part that isn't shown. */
    const val MIME_MAX_CHARS = MmsCodec.MAX_ID_CHARS

    /** A [Parcel] as [WireIn], for [ParseWire]'s length-first reads. */
    class ParcelWire(private val parcel: Parcel) : WireIn {
        override val position: Int get() = parcel.dataPosition()
        override fun seek(position: Int) = parcel.setDataPosition(position)
        override fun readInt(): Int = parcel.readInt()
        override fun readString(): String? = parcel.readString()
    }

    fun readString(parcel: Parcel, maxChars: Int, nullable: Boolean = false): String? = ParseWire.readString(ParcelWire(parcel), maxChars, nullable)

    fun readCount(parcel: Parcel, max: Int): Int = ParseWire.readCount(ParcelWire(parcel), max)
}
