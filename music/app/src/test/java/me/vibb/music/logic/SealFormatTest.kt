package me.vibb.music.logic

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import me.vibb.music.Fixtures
import me.vibb.music.data.DownloadRow
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.MessageDigest
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Design 117 §3 and §13: the 78-byte record and its AAD, AES-256-CTR at the byte's own position against one pass from
 * 0, `seal_vectors.json` (made with Python's cryptography, throwaway values from fake phrases) and the phone's
 * self-check constant, and §8's plain Storytel rows.
 */
class SealFormatTest {
    private val vectors = Json.parseToJsonElement(Fixtures.resource("seal_vectors.json")).jsonObject

    private fun str(name: String) = vectors.getValue(name).jsonPrimitive.content

    private fun hex(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private fun sha256(phrase: String) = MessageDigest.getInstance("SHA-256").digest(phrase.toByteArray())

    private val dataKey = BookKey(sha256(str("data_key_phrase")), str("nonce_ascii").toByteArray())

    private fun unwrap(record: SealRecord, cacheKey: String, kek: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek, "AES"), GCMParameterSpec(128, record.wrapIv))
        c.updateAAD(record.aad(cacheKey))
        return c.doFinal(record.wrapped)
    }

    /** [length] bytes sealed from [offset] in calls of random sizes. */
    private fun seal(key: BookKey, offset: Long, plain: ByteArray, random: Random): ByteArray {
        val out = ByteArray(plain.size)
        val c = SealCipher(key, offset)
        var at = 0
        while (at < plain.size) {
            val n = minOf(plain.size - at, 1 + random.nextInt(70_000))
            c.update(plain, at, n, out, at)
            at += n
        }
        return out
    }

    @Test
    fun `the vectors' keys are the fake phrases' hashes`() {
        assertEquals(str("data_key_hex"), hex(sha256(str("data_key_phrase"))))
        assertEquals(str("kek_hex"), hex(sha256(str("kek_phrase"))))
        assertTrue(str("data_key_phrase").startsWith("FAKE-") && str("kek_phrase").startsWith("FAKE-"))
    }

    @Test
    fun `a record is 78 bytes, decodes to itself, and the vector's record unwraps to the vector's data key`() {
        val bytes = hex(str("record_hex"))
        assertEquals(78, bytes.size)
        val record = SealRecord.decode(bytes)!!
        assertEquals(str("key_id_hex").toLong(16).toInt(), record.keyId)
        assertArrayEquals(str("nonce_ascii").toByteArray(), record.nonce)
        assertArrayEquals(str("wrap_iv_ascii").toByteArray(), record.wrapIv)
        assertArrayEquals(bytes, record.encode())
        assertArrayEquals(sha256(str("data_key_phrase")), unwrap(record, str("cache_key"), sha256(str("kek_phrase"))))
        assertEquals("SealRecord(redacted)", record.toString())
        assertEquals("BookKey(redacted)", dataKey.toString())
    }

    @Test
    fun `a wrong magic, version, suite or length is unreadable`() {
        val good = hex(str("record_hex"))
        for (at in listOf(0, 3, 4, 5)) assertNull("byte $at", SealRecord.decode(good.copyOf().also { it[at] = (it[at] + 1).toByte() }))
        assertNull(SealRecord.decode(good.copyOf(77)))
        assertNull(SealRecord.decode(good + byteArrayOf(0)))
        assertNull(SealRecord.decode(null))
    }

    @Test
    fun `the AAD covers the cache key, the nonce and the key id - changing any one fails the unwrap`() {
        val record = SealRecord.decode(hex(str("record_hex")))!!
        val kek = sha256(str("kek_phrase"))
        val changed = listOf(
            record to "sealed:e7:222",
            SealRecord(record.keyId, "NONCE-02".toByteArray(), record.wrapIv, record.wrapped) to str("cache_key"),
            SealRecord(record.keyId + 1, record.nonce, record.wrapIv, record.wrapped) to str("cache_key"),
        )
        for ((r, key) in changed) {
            try {
                unwrap(r, key, kek)
                fail("$key: a moved or edited record must not unwrap")
            } catch (e: AEADBadTagException) {
                // expected
            }
        }
    }

    @Test
    fun `the cipher at any start and length equals slicing one pass from 0 - across a 5 MiB split and a 2^32-block carry`() {
        val random = Random(117)
        val size = 12 * 1024 * 1024
        val plain = SealSelfCheck.plain(0, size)
        val whole = seal(dataKey, 0, plain, random)
        repeat(40) {
            val start = random.nextInt(size - 1)
            val length = 1 + random.nextInt(minOf(size - start, 300_000))
            val part = seal(dataKey, start.toLong(), plain.copyOfRange(start, start + length), random)
            assertArrayEquals("$start+$length", whole.copyOfRange(start, start + length), part)
        }
        val split = 5 * 1024 * 1024 - 7
        assertArrayEquals(whole.copyOfRange(split, split + 40), seal(dataKey, split.toLong(), plain.copyOfRange(split, split + 40), random))
        // the carry: one pass from a block before it, sliced
        val base = ((1L shl 32) - 4) * 16
        val far = SealSelfCheck.plain(base, 256)
        val farWhole = seal(dataKey, base, far, random)
        for (skip in listOf(1, 15, 16, 17, 63, 64, 65, 100)) {
            assertArrayEquals("carry +$skip", farWhole.copyOfRange(skip, 256), seal(dataKey, base + skip, far.copyOfRange(skip, 256), random))
        }
        assertTrue("sealed bytes are not the plain ones", (0 until size step 4096).none { whole[it] == plain[it] && whole[it + 1] == plain[it + 1] && whole[it + 2] == plain[it + 2] })
    }

    @Test
    fun `the vectors pin the sealed bytes, and the phone's self-check is the vectors' self_check case`() {
        val cases = vectors.getValue("cases").jsonArray.map { it.jsonObject }
        assertEquals(setOf(0L, 15L, 16L, 17L, 5L * 1024 * 1024 - 1, (1L shl 32) * 16 - 21), cases.map { it.getValue("offset").jsonPrimitive.long }.toSet())
        for (c in cases) {
            val offset = c.getValue("offset").jsonPrimitive.long
            val length = c.getValue("length").jsonPrimitive.int
            assertEquals(c.toString(), c.getValue("sealed_hex").jsonPrimitive.content, hex(seal(dataKey, offset, SealSelfCheck.plain(offset, length), Random(offset))))
        }
        val check = cases.single { it.getValue("name").jsonPrimitive.content == "self_check" }
        assertEquals(SealSelfCheck.OFFSET, check.getValue("offset").jsonPrimitive.long)
        assertEquals(SealSelfCheck.LENGTH, check.getValue("length").jsonPrimitive.int)
        assertEquals(SealSelfCheck.SEALED_HEX, check.getValue("sealed_hex").jsonPrimitive.content)
        assertEquals(SealSelfCheck.KEY_PHRASE, str("data_key_phrase"))
        assertEquals(SealSelfCheck.NONCE, str("nonce_ascii"))
        assertTrue(SealSelfCheck.passes())
    }

    @Test
    fun `plainStorytelRows picks exactly the Storytel rows whose key isn't sealed`() {
        fun row(key: String, cacheKey: String, url: String) = DownloadRow(7, key, cacheKey, url, false, true, 1000, null, null)
        val plain = row("111", "e7:111", "storytel:book:111")
        val partial = row("222", "e7:222", "storytel:book:222").copy(done = false)
        val rows = listOf(
            plain, partial,
            row("333", "sealed:e7:333", "storytel:book:333"),
            row("k1", "e1:k1", "https://cdn.example.org/k1.mp3"),
        )
        assertEquals(listOf(plain, partial), plainStorytelRows(rows))
        assertEquals("sealed:e7:111", sealedCacheKey("e7", "111"))
        assertTrue(isSealedKey("sealed:e7:111") && !isSealedKey("e7:111") && !isSealedKey(null))
    }
}
