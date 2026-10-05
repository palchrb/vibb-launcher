package com.kidslauncher.mdm.server

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * PBKDF2-HMAC-SHA256 PIN verification, shared by the offline override ([OfflineOverride]) and the
 * kid's lock-screen PIN (handy step 10). Must match `security::hash_pin` in kid-phone-server
 * (`pin_hash_shared_vector` there, `PinHashTest` here): the server hashes, the phone only
 * verifies, fully offline. ~0.5 s on a phone - never on the main thread.
 */
object PinHash {
    const val ROUNDS = 210_000
    const val HASH_LEN_BYTES = 32

    /** Lowercase/uppercase hex to bytes; `null` for anything else (odd length, other chars). */
    fun parseHex(hex: String?): ByteArray? {
        if (hex == null || hex.isEmpty() || hex.length % 2 != 0) return null
        val out = ByteArray(hex.length / 2)
        for (i in out.indices) {
            val hi = Character.digit(hex[i * 2], 16)
            val lo = Character.digit(hex[i * 2 + 1], 16)
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) + lo).toByte()
        }
        return out
    }

    /** A hash/salt pair a PIN could ever verify against. */
    fun usable(hashHex: String?, saltHex: String?): Boolean =
        parseHex(hashHex)?.size == HASH_LEN_BYTES && parseHex(saltHex) != null

    fun derive(pin: String, salt: ByteArray): ByteArray =
        SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(PBEKeySpec(pin.toCharArray(), salt, ROUNDS, HASH_LEN_BYTES * 8))
            .encoded

    /** Constant-time; false for an unusable pair or any error. */
    fun verify(pin: String, hashHex: String?, saltHex: String?): Boolean {
        val expected = parseHex(hashHex)?.takeIf { it.size == HASH_LEN_BYTES } ?: return false
        val salt = parseHex(saltHex) ?: return false
        return try {
            MessageDigest.isEqual(derive(pin, salt), expected)
        } catch (e: Exception) {
            false
        }
    }
}
