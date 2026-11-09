package com.kidslauncher.mdm.sms.engine

import java.security.SecureRandom

/** The engine's ids (§6.2): ULIDs for conversations and messages, random hex for the rows the server is told of. */
interface IdSource {
    /** A ULID: 48 bits of [now] (ms) and 80 random bits, Crockford base32 - sortable by time, never reused. */
    fun ulid(now: Long): String

    /** 32 lowercase hex characters (the server takes 16-64): a held or blocked row's id. */
    fun hex(): String
}

object RandomIds : IdSource {
    private const val CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    override fun ulid(now: Long): String {
        val out = CharArray(26)
        var time = now
        for (i in 9 downTo 0) {
            out[i] = CROCKFORD[(time and 31).toInt()]
            time = time ushr 5
        }
        val bytes = ByteArray(10).also { random.nextBytes(it) }
        // 80 bits as 16 characters of 5 bits.
        var bits = 0L
        var count = 0
        var pos = 10
        for (b in bytes) {
            bits = (bits shl 8) or (b.toLong() and 0xff)
            count += 8
            while (count >= 5) {
                out[pos++] = CROCKFORD[((bits ushr (count - 5)) and 31).toInt()]
                count -= 5
            }
        }
        return String(out)
    }

    override fun hex(): String {
        val bytes = ByteArray(16).also { random.nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}
