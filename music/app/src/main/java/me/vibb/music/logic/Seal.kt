package me.vibb.music.logic

import me.vibb.music.data.DownloadRow
import java.io.IOException
import java.nio.ByteBuffer
import java.security.InvalidKeyException
import java.security.KeyStoreException
import java.security.MessageDigest
import java.security.ProviderException
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Storytel books sealed on the phone (design 117): the sealed cache key, the 78-byte seal record, the AES-256-CTR
 * cipher at the byte's own position, the cipher's self-check vector, the failure classes and the Unclassified counter,
 * and the plain Storytel rows from before. Pure (javax.crypto only, no Keystore); SealFormatTest, SealFailureTest. The
 * Keystore side is media/BookKeys.kt, the streams media/SealStreams.kt.
 */

/** A sealed book's cache keys start with this (§4.1). */
const val SEALED_PREFIX = "sealed:"

/** The cache key of Storytel book [cid] of entry [entryKey]: always sealed (§4.1). */
fun sealedCacheKey(entryKey: String, cid: String): String = SEALED_PREFIX + itemCacheKey(entryKey, cid)

/** Is [key] a sealed book's cache key? Whether a book is sealed follows from its key, never from metadata (§4.1). */
fun isSealedKey(key: String?): Boolean = key != null && key.startsWith(SEALED_PREFIX)

/** A Storytel book's source id (`storytel:book:<cid>`, the uri its `MediaItem` carries), never a network URL. */
fun isStorytelSourceId(uri: String?): Boolean = uri != null && uri.startsWith("storytel:")

/** §4.5: a plain sink (the `ThroughSink`, `NoWriteSink`, the download's) refuses a sealed key and a Storytel source id. */
fun plainSinkRefuses(key: String?, uri: String?): Boolean = isSealedKey(key) || isStorytelSourceId(uri)

/** A plain sink was handed a Storytel book (§4.5): the item fails rather than storing a plain book. */
class PlainSinkRefusedException : IOException("a plain cache sink refused a sealed book")

/**
 * §8: the Storytel rows whose copy is plain (made before 117; only the emulators hold any). Each is removed and the book
 * downloads again sealed - no migration (the user, 2026-11-18).
 */
fun plainStorytelRows(rows: List<DownloadRow>): List<DownloadRow> =
    rows.filter { it.url.startsWith(bookTarget("")) && !isSealedKey(it.cacheKey) }

// The Keystore key's alias (§2.2): a new key always gets a new id.

const val KEK_ALIAS_PREFIX = "music_books_"

/** The alias of Keystore key [id] (its 8 hex digits). */
fun kekAlias(id: Int): String = KEK_ALIAS_PREFIX + kekIdHex(id)

fun kekIdHex(id: Int): String = "%08x".format(id)

/** The id of [alias], or null when it isn't one of ours. */
fun kekIdOf(alias: String): Int? =
    alias.removePrefix(KEK_ALIAS_PREFIX).takeIf { alias.startsWith(KEK_ALIAS_PREFIX) && it.length == 8 }?.toLongOrNull(16)?.toInt()

/** The canary's plaintext and its AAD (§2.3): unwrapping it shows whether a key and the Keystore work now. */
val CANARY: ByteArray = "VIBB-SEAL-CANARY".toByteArray(Charsets.US_ASCII)

fun canaryAad(id: Int): ByteArray = "canary:${kekIdHex(id)}".toByteArray(Charsets.US_ASCII)

/**
 * The seal record (§3.1), the resource's `vibb_seal` content metadata: magic `VBSL`, version 1, suite 1, the Keystore
 * key's id, the CTR nonce, the wrap's GCM IV and the wrapped data key with its tag. 78 bytes. The GCM AAD is bytes 0-17
 * and the UTF-8 sealed cache key, so a record moved to another book, or with another nonce or key id, never unwraps.
 */
class SealRecord(val keyId: Int, nonce: ByteArray, wrapIv: ByteArray, wrapped: ByteArray) {
    val nonce: ByteArray = nonce.copyOf()
    val wrapIv: ByteArray = wrapIv.copyOf()
    val wrapped: ByteArray = wrapped.copyOf()

    init {
        require(this.nonce.size == NONCE_SIZE && this.wrapIv.size == WRAP_IV_SIZE && this.wrapped.size == WRAPPED_SIZE)
    }

    /** Bytes 0-17: magic, version, suite, key id, nonce. */
    fun header(): ByteArray = header(keyId, nonce)

    fun encode(): ByteArray = header() + wrapIv + wrapped

    /** The wrap's AAD: the header and the sealed cache key. */
    fun aad(cacheKey: String): ByteArray = aad(keyId, nonce, cacheKey)

    /** The memo's key (§2.5, QA L3): the record's own bytes, so a re-minted record never meets an old key. */
    fun memoKey(): String = encode().joinToString("") { "%02x".format(it) }

    override fun toString(): String = "SealRecord(redacted)"

    companion object {
        val MAGIC: ByteArray = "VBSL".toByteArray(Charsets.US_ASCII)
        const val VERSION: Byte = 1
        const val SUITE: Byte = 1
        const val NONCE_SIZE = 8
        const val WRAP_IV_SIZE = 12
        const val WRAPPED_SIZE = 48
        const val HEADER_SIZE = 18
        const val SIZE = HEADER_SIZE + WRAP_IV_SIZE + WRAPPED_SIZE

        fun header(keyId: Int, nonce: ByteArray): ByteArray =
            ByteBuffer.allocate(HEADER_SIZE).put(MAGIC).put(VERSION).put(SUITE).putInt(keyId).put(nonce).array()

        /** The wrap's AAD for a record of key [keyId] and [nonce] on [cacheKey] (made before the wrap). */
        fun aad(keyId: Int, nonce: ByteArray, cacheKey: String): ByteArray = header(keyId, nonce) + cacheKey.toByteArray(Charsets.UTF_8)

        /** [bytes] as a record; null when unreadable (an unknown magic, version or suite, or the wrong length: §7 Lost). */
        fun decode(bytes: ByteArray?): SealRecord? {
            if (bytes == null || bytes.size != SIZE) return null
            if (!bytes.copyOfRange(0, 4).contentEquals(MAGIC) || bytes[4] != VERSION || bytes[5] != SUITE) return null
            val b = ByteBuffer.wrap(bytes)
            b.position(6)
            val id = b.int
            return SealRecord(id, bytes.copyOfRange(10, 18), bytes.copyOfRange(18, 30), bytes.copyOfRange(30, SIZE))
        }
    }
}

/** A book's unwrapped data key and its nonce (§2.5): in memory only, printed redacted. */
class BookKey(raw: ByteArray, nonce: ByteArray) {
    internal val spec = SecretKeySpec(raw.copyOf(), "AES")
    internal val nonce: ByteArray = nonce.copyOf()

    init {
        require(raw.size == 32 && nonce.size == SealRecord.NONCE_SIZE)
    }

    override fun toString(): String = "BookKey(redacted)"
}

/** The IV of the 16-byte block [block]: `nonce || uint64_be(block)` (§3.2). */
fun ctrIv(nonce: ByteArray, block: Long): ByteArray = ByteBuffer.allocate(16).put(nonce).putLong(block).array()

/**
 * AES-256-CTR at the byte's own position (§3.2): the counter of the block holding byte `p` is `p / 16`, and a start at
 * `p` drops `p % 16` bytes of keystream. Any start, any length; the partial block carries across calls. The same call
 * seals and opens. Throws when the provider returns fewer bytes than it was given (§2.5's check).
 */
class SealCipher(key: BookKey, position: Long) {
    private val cipher: Cipher = Cipher.getInstance("AES/CTR/NoPadding")

    init {
        require(position >= 0)
        cipher.init(Cipher.ENCRYPT_MODE, key.spec, IvParameterSpec(ctrIv(key.nonce, position / 16)))
        val skip = (position % 16).toInt()
        if (skip > 0) update(ByteArray(skip), 0, skip, ByteArray(skip), 0)
    }

    fun update(input: ByteArray, inOffset: Int, length: Int, output: ByteArray, outOffset: Int) {
        if (length == 0) return
        val n = cipher.update(input, inOffset, length, output, outOffset)
        if (n != length) throw IllegalStateException("the cipher returned $n of $length bytes")
    }
}

/**
 * The cipher's check on the phone (§2.5, QA L7): one case of `seal_vectors.json` (a start mid-block and a counter carry
 * past a 2^32 boundary, in two calls), run through the platform's provider at first use. Throwaway values: the key is
 * SHA-256 of a fake phrase.
 */
object SealSelfCheck {
    const val KEY_PHRASE = "FAKE-SEAL-KEY-1"
    const val NONCE = "NONCE-01"
    const val OFFSET = 68_719_476_715L
    const val LENGTH = 48
    const val SEALED_HEX = "6d3b59df9db62de646333563fac4b9b56eb8c68c7737dfa7cb5c75103e402e82e2e81cb349d85f0df0edfd3fd8bae27a"

    /** The vectors' plaintext: byte `p` is `(p * 7 + 3) mod 256`. */
    fun plain(offset: Long, length: Int): ByteArray = ByteArray(length) { ((offset + it) * 7 + 3).toInt().toByte() }

    fun key(): BookKey = BookKey(MessageDigest.getInstance("SHA-256").digest(KEY_PHRASE.toByteArray()), NONCE.toByteArray())

    /** Does this provider seal the vector right? Never throws. */
    fun passes(): Boolean = runCatching {
        val input = plain(OFFSET, LENGTH)
        val out = ByteArray(LENGTH)
        val c = SealCipher(key(), OFFSET)
        c.update(input, 0, 7, out, 0)
        c.update(input, 7, LENGTH - 7, out, 7)
        out.joinToString("") { "%02x".format(it) } == SEALED_HEX
    }.getOrDefault(false)
}

// What a failure means (§7): Lost only on definite signals, never on a hiccup.

enum class SealVerdict { LOST, TRANSIENT, UNCLASSIFIED }

/** What a Keystore exception says of itself; the Android side reads `android.security.KeyStoreException` (BookKeys). */
sealed interface KeystoreSignal {
    /** `KeyPermanentlyInvalidatedException`. */
    data object Invalidated : KeystoreSignal

    /** An `android.security.KeyStoreException`: `ERROR_KEY_DOES_NOT_EXIST` or not, and `isTransientFailure()`. */
    data class Code(val keyMissing: Boolean, val transient: Boolean) : KeystoreSignal
}

/**
 * The class of a failed unwrap [error] (§7, QA H1(b), M1), its cause chain read (at most eight):
 * - Lost: a permanently invalidated key; a Keystore "key does not exist" that isn't transient; a bad tag on the record
 *   while the same key's canary unwrapped in the same check ([canaryOk]);
 * - Transient: a transient Keystore failure; anything while the canary fails; an `UnrecoverableKeyException`,
 *   `InvalidKeyException`, `ProviderException` or Keystore error without a definite cause;
 * - Unclassified: anything else with a good canary (counted, [countFailure]).
 * A key that read null and an unreadable record are Lost before any unwrap (the callers).
 */
fun sealFailure(error: Throwable, canaryOk: Boolean, signal: (Throwable) -> KeystoreSignal?): SealVerdict {
    val chain = generateSequence(error) { it.cause }.take(8).toList()
    val signals = chain.mapNotNull(signal)
    if (signals.any { it is KeystoreSignal.Invalidated }) return SealVerdict.LOST
    val codes = signals.filterIsInstance<KeystoreSignal.Code>()
    if (codes.any { it.keyMissing && !it.transient }) return SealVerdict.LOST
    if (codes.any { it.transient }) return SealVerdict.TRANSIENT
    if (chain.any { it is AEADBadTagException }) return if (canaryOk) SealVerdict.LOST else SealVerdict.TRANSIENT
    if (!canaryOk || codes.isNotEmpty()) return SealVerdict.TRANSIENT
    val keystoreLike = chain.any { it is UnrecoverableKeyException || it is InvalidKeyException || it is ProviderException || it is KeyStoreException }
    return if (keystoreLike) SealVerdict.TRANSIENT else SealVerdict.UNCLASSIFIED
}

/** An Unclassified failure's count, kept in prefs as `seal_fail_<sealed key>` = "first_at,count" (§7). */
data class FailCount(val firstAt: Long, val count: Int) {
    fun encode(): String = "$firstAt,$count"

    companion object {
        fun decode(text: String?): FailCount? {
            val parts = text?.split(',')?.takeIf { it.size == 2 } ?: return null
            val at = parts[0].toLongOrNull() ?: return null
            val n = parts[1].toIntOrNull() ?: return null
            return FailCount(at, n)
        }
    }
}

const val UNCLASSIFIED_LOST_COUNT = 3
const val UNCLASSIFIED_LOST_MS = 24 * 3600_000L

fun countFailure(previous: FailCount?, nowMs: Long): FailCount = previous?.copy(count = previous.count + 1) ?: FailCount(nowMs, 1)

/** An Unclassified failure becomes Lost at 3 or more over at least 24 h (§7): a fault we can't name never lasts for ever. */
fun unclassifiedLost(count: FailCount, nowMs: Long): Boolean =
    count.count >= UNCLASSIFIED_LOST_COUNT && nowMs - count.firstAt >= UNCLASSIFIED_LOST_MS
