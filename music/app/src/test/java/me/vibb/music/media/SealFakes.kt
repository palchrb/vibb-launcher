package me.vibb.music.media

import me.vibb.music.logic.KeystoreSignal
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/*
 * Design 117 §13: the Keystore, the prefs and the cache behind BookKeys' seams, for JVM tests. No Keystore and no
 * real-looking key: each "Keystore" key is SHA-256 of a fake phrase and its alias, the wrap software AES-GCM with
 * spelled-out IVs (`FAKE-IV-0001`, ...). Any call can be made to throw.
 */

/** A Keystore error as the Android side reads it: [missing] = `ERROR_KEY_DOES_NOT_EXIST`, [transient] = `isTransientFailure`. */
class FakeKeystoreError(val missing: Boolean, val transient: Boolean) : Exception("a fake Keystore error")

/** A key that is permanently invalidated (the Android side's `KeyPermanentlyInvalidatedException`). */
class FakeInvalidated : Exception("a fake invalidated key")

fun fakeSignal(e: Throwable): KeystoreSignal? = when (e) {
    is FakeKeystoreError -> KeystoreSignal.Code(e.missing, e.transient)
    is FakeInvalidated -> KeystoreSignal.Invalidated
    else -> null
}

class FakeKeyStore : KeyStoreSeam {
    val keys = linkedMapOf<String, SecretKey>()
    val generated = mutableListOf<String>()
    val deleted = mutableListOf<String>()

    /** Throw this from `getKey` for an alias (null: answer). */
    var getKeyFails: (String) -> Exception? = { null }

    /** Throw this from `generate` (null: make it). */
    var generateFails: () -> Exception? = { null }

    /** Throw this from `unwrap`, given the AAD (the canary's starts with "canary:"; null: unwrap). */
    var unwrapFails: (ByteArray) -> Exception? = { null }

    /** Aliases whose key is present but broken (an OTA's corrupted key, code QA M1): every wrap and unwrap fails for good. */
    val broken = mutableSetOf<String>()

    private fun checkBroken(key: SecretKey) {
        if (keys.entries.any { it.value === key && it.key in broken }) throw FakeKeystoreError(missing = false, transient = false)
    }

    private var ivs = 0
    var wraps = 0
    var unwraps = 0

    override fun getKey(alias: String): SecretKey? {
        getKeyFails(alias)?.let { throw it }
        return keys[alias]
    }

    override fun generate(alias: String): SecretKey {
        generateFails()?.let { throw it }
        check(alias !in generated) { "$alias generated twice" }
        generated += alias
        return SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("FAKE-KEK-PHRASE-$alias".toByteArray()), "AES").also { keys[alias] = it }
    }

    override fun wrap(key: SecretKey, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        checkBroken(key)
        wraps++
        val iv = "FAKE-IV-%04d".format(++ivs).toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return iv to c.doFinal(plain)
    }

    override fun unwrap(key: SecretKey, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        unwraps++
        unwrapFails(aad)?.let { throw it }
        checkBroken(key)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(sealed)
    }

    override fun aliases(): List<String> = keys.keys.toList()

    override fun delete(alias: String) {
        keys.remove(alias)
        deleted += alias
    }

    override fun signal(error: Throwable): KeystoreSignal? = fakeSignal(error)
}

class FakePrefs : SealPrefs {
    val map = linkedMapOf<String, String>()

    /** A put of a name starting with this throws (a crash at that point), once. */
    var crashAt: String? = null

    /** A put of a name starting with this isn't committed (`commit()` false, code QA L5), once. */
    var uncommittedAt: String? = null

    override fun get(name: String): String? = map[name]

    override fun put(name: String, value: String?): Boolean {
        crashAt?.let { if (name.startsWith(it)) { crashAt = null; throw IllegalStateException("crash at $name") } }
        uncommittedAt?.let { if (name.startsWith(it)) { uncommittedAt = null; return false } }
        if (value == null) map.remove(name) else map[name] = value
        return true
    }

    override fun names(): Set<String> = map.keys.toSet()
}

/** The cache's records and spans (bytes per key); [held] keys are spared by `removeCopy`, as the player's are. */
class FakeSealCache : SealCache {
    val records = linkedMapOf<String, ByteArray>()
    val spans = linkedMapOf<String, Long>()
    val held = mutableSetOf<String>()
    val removed = mutableListOf<String>()

    override fun record(cacheKey: String): ByteArray? = records[cacheKey]

    override fun writeRecord(cacheKey: String, record: ByteArray) {
        records[cacheKey] = record.copyOf()
    }

    override fun hasSpans(cacheKey: String): Boolean = (spans[cacheKey] ?: 0) > 0

    override fun sealedKeys(): List<String> = (records.keys + spans.keys).filter { it.startsWith("sealed:") }.distinct()

    /** As `SimpleCache.removeResource`: the spans go, and the empty content with its record. */
    override fun removeCopy(cacheKey: String) {
        if (cacheKey in held) return
        removed += cacheKey
        spans.remove(cacheKey)
        records.remove(cacheKey)
    }
}

/** A [BookKeys] over the fakes, with ids 1, 2, 3, ... unless [ids] says otherwise, and the clock [now]. */
class SealWorld(ids: List<Int> = (1..100).toList()) {
    val keystore = FakeKeyStore()
    val prefs = FakePrefs()
    val cache = FakeSealCache()
    var now = 1_800_000_000_000L
    private val next = ArrayDeque(ids)
    private var nonces = 0
    val keys = BookKeys(
        keystore, prefs, cache,
        nowMs = { now },
        newId = { next.removeFirst() },
        randomBytes = { n -> "NONCE-%02d".format(++nonces).toByteArray().copyOf(n) },
    )
}
