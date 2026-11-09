package com.kidslauncher.mdm.sms.parse

/*
 * The isolated parse's wire, the parts that judge what the other side sent (design 77 D8; code QA of step 3b, test 7):
 * pure, no Android imports, so the length-first reads and the size checks have JVM tests (`ParseWireTest`).
 * `MmsParseProtocol` adapts an Android `Parcel` to [WireIn].
 */

/** What [ParseWire] reads from: a position, ints, and strings as `Parcel.writeString` wrote them (their length in UTF-16
 * units first, -1 for null). */
interface WireIn {
    val position: Int
    fun seek(position: Int)
    fun readInt(): Int
    fun readString(): String?
}

/** The protocol's own error: a field over its cap, a count out of range, a size that doesn't match. */
class WireMalformed(what: String) : Exception(what)

object ParseWire {
    /**
     * A string, its length read and judged first: the length int is read and checked against [maxChars], the position
     * put back, and only then the string read - and its length must be the one announced.
     */
    fun readString(wire: WireIn, maxChars: Int, nullable: Boolean = false): String? {
        val position = wire.position
        val length = wire.readInt()
        if (length == -1 && nullable) return null
        if (length < 0 || length > maxChars) throw WireMalformed("string length $length")
        wire.seek(position)
        val value = wire.readString() ?: throw WireMalformed("null string")
        if (value.length != length) throw WireMalformed("string length")
        return value
    }

    /** A count within 0..[max]. */
    fun readCount(wire: WireIn, max: Int): Int {
        val count = wire.readInt()
        if (count < 0 || count > max) throw WireMalformed("count $count")
        return count
    }

    /** A picture's announced size: both sides within 1..[maxPx]; its bytes are then `width * height * 4`. */
    fun pixelBytes(width: Int, height: Int, maxPx: Int): Int {
        if (width !in 1..maxPx || height !in 1..maxPx) throw WireMalformed("size $width x $height")
        val pixels = width.toLong() * height
        if (pixels > Int.MAX_VALUE / 4) throw WireMalformed("size $width x $height")
        return (pixels * 4).toInt()
    }

    /**
     * Exactly [size] bytes through [read] (`pread`: buffer, offset, length, position -> bytes read). A short read, an
     * end, or any exception the read throws - `ErrnoException` is a checked one (verification V1) - is out of protocol,
     * [WireMalformed]; nothing else escapes.
     */
    fun readFully(size: Int, read: (ByteArray, Int, Int, Long) -> Int): ByteArray {
        val bytes = ByteArray(size)
        var done = 0
        while (done < size) {
            val n = try {
                read(bytes, done, size - done, done.toLong())
            } catch (e: WireMalformed) {
                throw e
            } catch (e: Exception) {
                throw WireMalformed("pixels unreadable at $done of $size: ${e.javaClass.simpleName}")
            }
            if (n <= 0) throw WireMalformed("pixels ended at $done of $size")
            done += n
        }
        return bytes
    }

    /**
     * Code QA M1: one message's pictures, bounded as a whole - at most [MAX_PICTURES] decoded, at most [MAX_PIXELS]
     * between them. The isolated service stops decoding at the bound (the rest are "not shown"); the main process
     * refuses an answer past it as out of protocol.
     */
    class PictureBudget {
        private var pictures = 0
        private var pixels = 0L

        /** Whether one more picture may be decoded at all. */
        fun roomForOne(): Boolean = pictures < MAX_PICTURES && pixels < MAX_PIXELS

        /** Takes [width] x [height]; `false` (nothing taken) when it doesn't fit. */
        fun take(width: Int, height: Int): Boolean {
            val more = width.toLong() * height
            if (pictures >= MAX_PICTURES || pixels + more > MAX_PIXELS) return false
            pictures++
            pixels += more
            return true
        }
    }

    /** At most this many pictures of one message are decoded (the kid's bubble shows them one under the other). */
    const val MAX_PICTURES = 4

    /** At most this many pixels between them: about 4 pictures of 1,600 x 1,200 (48 MB of RGBA at most). */
    const val MAX_PIXELS = 8_000_000L
}
