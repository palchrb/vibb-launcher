package me.vibb.music.media

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyInfo
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.ContentMetadataMutations
import kotlinx.coroutines.runBlocking
import me.vibb.music.logic.BookKey
import me.vibb.music.logic.CANARY
import me.vibb.music.logic.FailCount
import me.vibb.music.logic.KEK_ALIAS_PREFIX
import me.vibb.music.logic.KeystoreSignal
import me.vibb.music.logic.SealRecord
import me.vibb.music.logic.SealSelfCheck
import me.vibb.music.logic.SealVerdict
import me.vibb.music.logic.canaryAad
import me.vibb.music.logic.countFailure
import me.vibb.music.logic.isSealedKey
import me.vibb.music.logic.kekAlias
import me.vibb.music.logic.kekIdHex
import me.vibb.music.logic.kekIdOf
import me.vibb.music.logic.keyBroken
import me.vibb.music.logic.sealFailure
import me.vibb.music.logic.unclassifiedLost
import me.vibb.music.sync.CacheThrough
import java.io.IOException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec

/*
 * The keys of sealed Storytel books (design 117 §2, §4.3, §7): one Keystore key `music_books_<id>` in the TEE wraps a
 * random AES-256 data key per book, kept in the book's seal record in the cache's index. A new Keystore key always gets
 * a new alias; "missing" is only `getKey` reading null; Lost only on definite signals, a bad tag only with the same key's
 * canary good, an unnamed fault only at 3 over 24 h. Never a key in the app's files or prefs (the prefs hold the id, the
 * canary and the counters). The Keystore, the prefs and the cache are seams ([KeyStoreSeam], [SealPrefs], [SealCache]):
 * BookKeysTest runs it on software AES-GCM under a fake-phrase key.
 */

/** A sealed book's key is gone for good (§7): the book downloads again, never retried by the player (§6). */
class SealLostException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A seal failure that may pass (§7): nothing is deleted; a download records `network`, a play "Kan ikke spilles nå". */
class SealTransientException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** What the sink and the source ask of the keys: the data key of sealed [cacheKey], from its record (§2.5). */
fun interface SealKeys {
    /** Throws [SealLostException] or [SealTransientException]. */
    fun keyFor(cacheKey: String): BookKey
}

/** The Android Keystore, as BookKeys uses it (§13). */
interface KeyStoreSeam {
    /** The key, or null only when it doesn't exist (KEY_NOT_FOUND); any other failure throws. */
    fun getKey(alias: String): SecretKey?

    fun generate(alias: String): SecretKey

    /** AES-GCM under [key] with an IV the Keystore picks: (iv, ciphertext and tag). */
    fun wrap(key: SecretKey, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray>

    fun unwrap(key: SecretKey, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray

    fun aliases(): List<String>

    fun delete(alias: String)

    /** What a Keystore exception says of itself (§7). */
    fun signal(error: Throwable): KeystoreSignal?
}

/** The app's seal prefs: the current key's id, the canaries, the Unclassified counters. Never a key. */
interface SealPrefs {
    fun get(name: String): String?

    /** null removes. Whether it reached the disk (`commit()`; code QA L5). */
    fun put(name: String, value: String?): Boolean

    /** Every name stored (the clean-up of orphan canaries, code QA L1). */
    fun names(): Set<String>
}

/** The cache as the keys see it: the records in its index, its spans, and a removal that spares what the player holds. */
interface SealCache {
    fun record(cacheKey: String): ByteArray?

    fun writeRecord(cacheKey: String, record: ByteArray)

    fun hasSpans(cacheKey: String): Boolean

    /** Every sealed key in the cache (rows or not): the aliases their records name stay. */
    fun sealedKeys(): List<String>

    /** `CacheThrough.removeCopy`: never while the player holds the key. */
    fun removeCopy(cacheKey: String)
}

class BookKeys(
    private val keystore: KeyStoreSeam,
    private val prefs: SealPrefs,
    private val cache: SealCache,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val newId: () -> Int = { random.nextInt() },
    private val randomBytes: (Int) -> ByteArray = { n -> ByteArray(n).also { random.nextBytes(it) } },
    private val selfCheck: () -> Boolean = SealSelfCheck::passes,
    private val log: (String) -> Unit = {},
    private val onKey: (SecretKey) -> Unit = {},
) : SealKeys {
    private val lock = Any()

    /** Up to 4 unwrapped keys, keyed by their record's bytes (§2.5, QA L3): a re-minted record never meets an old key. */
    private val memo = object : LinkedHashMap<String, BookKey>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, BookKey>?): Boolean = size > MEMO_SIZE
    }

    /**
     * The records this process has unwrapped once (code QA L3): the check skips them without an unwrap and without
     * touching the memo, so a library of many books never unwraps every book every sync nor evicts the playing book's key.
     */
    private val checked = HashSet<String>()

    /** The Keystore keys whose canary unwrapped in this process: `prepare` asks a key's canary once (code QA M1). */
    private val canaryGood = HashSet<Int>()

    @Volatile private var cipherChecked: Boolean? = null
    @Volatile private var keySeen = false

    /** The cipher's self-check, once per process (§2.5); a mismatch is logged once and seals and reads nothing. */
    private fun cipherOk(): Boolean = cipherChecked ?: selfCheck().also {
        cipherChecked = it
        if (!it) log("the cipher's self-check failed: nothing is sealed or read")
    }

    /**
     * Ready sealed [cacheKey] for a download, before any byte is written (§4.3): the current key (made when there is
     * none or it is broken); a readable record is kept, spans or not (code QA M2: a stopped writer's uncommitted hole
     * isn't a span yet, and may still commit under that record); a record that is absent or Lost gets a new one, after
     * the spans go (never the player's - if any stay, Transient). A start-over (the 416, the lost path) removes the
     * resource, record and all, so it always gets a new key. Throws [SealTransientException].
     */
    fun prepare(cacheKey: String): Unit = synchronized(lock) {
        require(isSealedKey(cacheKey)) { "not a sealed key" }
        if (!cipherOk()) throw SealTransientException("the cipher's self-check failed")
        val kek = current(create = true)!!
        val record = SealRecord.decode(cache.record(cacheKey))
        val verdict = if (record == null) {
            SealVerdict.LOST
        } else {
            if (synchronized(memo) { memo[record.memoKey()] } != null) return
            try {
                unwrap(cacheKey, record, memoize = true)
                return
            } catch (e: Exception) {
                verdictOf(e, record.keyId, HashMap())
            }
        }
        if (verdict != SealVerdict.LOST) throw SealTransientException("the book's record can't be read now")
        if (cache.hasSpans(cacheKey)) {
            cache.removeCopy(cacheKey)
            // QA M4: never a new record over spans that stayed (the player holds them): one book under two keys is noise.
            if (cache.hasSpans(cacheKey)) throw SealTransientException("a lost book's spans are held")
        }
        newRecord(cacheKey, kek)
    }

    override fun keyFor(cacheKey: String): BookKey {
        if (!isSealedKey(cacheKey)) throw SealLostException("not a sealed book")
        if (!cipherOk()) throw SealTransientException("the cipher's self-check failed")
        val record = SealRecord.decode(cache.record(cacheKey) ?: throw SealLostException("no seal record"))
            ?: throw SealLostException("an unreadable seal record")
        synchronized(memo) { memo[record.memoKey()] }?.let { return it }
        return try {
            unwrap(cacheKey, record, memoize = true)
        } catch (e: Exception) {
            val lost = e is KeyMissingException || sealFailure(e, canaryError(record.keyId) == null, keystore::signal) == SealVerdict.LOST
            if (lost) throw SealLostException("the book's key is lost", e)
            throw SealTransientException("the book's key can't be read now", e)
        }
    }

    /**
     * The check at every sync (§7), before the plan: each sealed row's record parsed, unwrapped the first time per
     * process or after a failure ([checked]). The Lost keys are returned with the record bytes they were judged on
     * (null: none; the caller drops a book only while its record is still those bytes, [stillLost], code QA L4).
     * With every record read and no failure, the aliases no record names and that aren't current go, and the canaries
     * of ids with no alias (§2.2, code QA L1).
     */
    fun check(rowKeys: Collection<String>): Map<String, ByteArray?> = synchronized(lock) {
        if (!cipherOk()) return emptyMap()
        val lost = LinkedHashMap<String, ByteArray?>()
        var clean = true
        val canaries = HashMap<Int, Throwable?>()
        for (key in rowKeys.filter(::isSealedKey).distinct()) {
            val bytes = cache.record(key)
            if (bytes == null) {
                // A sealed key with spans and no record can never be read; with none it is a book not begun.
                if (cache.hasSpans(key)) lost[key] = null
                continue
            }
            val record = SealRecord.decode(bytes)
            if (record == null) {
                lost[key] = bytes
                continue
            }
            val memoKey = record.memoKey()
            try {
                if (memoKey !in checked && synchronized(memo) { memo[memoKey] } == null) unwrap(key, record, memoize = false)
                checked += memoKey
                if (prefs.get(failPref(key)) != null) prefs.put(failPref(key), null)
            } catch (e: Exception) {
                checked -= memoKey
                when (verdictOf(e, record.keyId, canaries)) {
                    SealVerdict.LOST -> lost[key] = bytes
                    SealVerdict.TRANSIENT -> clean = false
                    SealVerdict.UNCLASSIFIED -> {
                        clean = false
                        val now = nowMs()
                        val count = countFailure(FailCount.decode(prefs.get(failPref(key))), now)
                        if (unclassifiedLost(count, now)) lost[key] = bytes else prefs.put(failPref(key), count.encode())
                    }
                }
            }
        }
        if (clean) dropOldKeys()
        lost
    }

    /** Is [cacheKey]'s record still [judged], the bytes `check` found Lost (code QA L4)? A download may have replaced it since. */
    fun stillLost(cacheKey: String, judged: ByteArray?): Boolean = cache.record(cacheKey).contentEquals(judged)

    /** A Lost or pruned book's counter goes with it (§7, code QA L2). */
    fun forget(cacheKey: String) {
        prefs.put(failPref(cacheKey), null)
    }

    private class KeyMissingException : Exception("the record's Keystore key reads null")

    /** A key's canary isn't in the prefs, or unwrapped to something else. */
    private class CanaryMissingException : Exception("no usable canary")

    /**
     * The class of a failed unwrap under key [keyId] (§7), [canaries] the canary results of this pass: a key that read
     * null is Lost; under a key that is present but broken, or has no canary (code QA M1), a fresh key standing in for
     * the canary ([standIn]) makes the book Unclassified (3 over 24 h), else Transient; anything else by [sealFailure].
     */
    private fun verdictOf(e: Exception, keyId: Int, canaries: HashMap<Int, Throwable?>): SealVerdict {
        if (e is KeyMissingException) return SealVerdict.LOST
        val canary = if (canaries.containsKey(keyId)) canaries[keyId] else canaryError(keyId).also { canaries[keyId] = it }
        if (canary != null && (canary is CanaryMissingException || keyBroken(canary, keystore::signal))) {
            return if (standIn(keyId)) SealVerdict.UNCLASSIFIED else SealVerdict.TRANSIENT
        }
        return sealFailure(e, canary == null, keystore::signal)
    }

    /**
     * Code QA M1: does the Keystore work beside broken key [brokenId]? The current key's canary, when the current key is
     * another; else a fresh alias whose canary wraps and unwraps, made current (a new alias destroys nothing).
     */
    private fun standIn(brokenId: Int): Boolean {
        val current = currentId()
        if (current != null && current != brokenId && canaryError(current) == null) return true
        return try {
            makeKey(current)
            true
        } catch (e: SealTransientException) {
            false
        }
    }

    /** The record's data key, unwrapped under the key it names; into the memo with [memoize] (the player's and the download's). */
    private fun unwrap(cacheKey: String, record: SealRecord, memoize: Boolean): BookKey {
        val kek = keystore.getKey(kekAlias(record.keyId)) ?: throw KeyMissingException()
        val raw = keystore.unwrap(kek, record.wrapIv, record.aad(cacheKey), record.wrapped)
        try {
            val key = BookKey(raw, record.nonce)
            if (memoize) synchronized(memo) { memo[record.memoKey()] = key }
            return key
        } finally {
            raw.fill(0)
        }
    }

    /** Key [id]'s canary now (§2.3): null when it unwraps, else why not. Never throws. */
    private fun canaryError(id: Int): Throwable? {
        val error: Throwable? = try {
            val stored = prefs.get(canaryPref(id))?.let(::hexBytes)
            val key = keystore.getKey(kekAlias(id))
            when {
                key == null -> KeyMissingException()
                stored == null || stored.size <= GCM_IV -> CanaryMissingException()
                keystore.unwrap(key, stored.copyOfRange(0, GCM_IV), canaryAad(id), stored.copyOfRange(GCM_IV, stored.size)).contentEquals(CANARY) -> null
                else -> CanaryMissingException()
            }
        } catch (e: Exception) {
            e
        }
        if (error == null) canaryGood += id else canaryGood -= id
        return error
    }

    private fun currentId(): Int? = prefs.get(PREF_ID)?.toLongOrNull(16)?.toInt()

    /**
     * The current Keystore key (§2.2): the stored id's, when `getKey` reads it. Null there makes a new one only with
     * [create]; `getKey` throwing is Transient, never a new key. With [create] its canary is asked once per process: a
     * key that is present but broken, or has no canary, is replaced by a new alias (code QA M1); any other canary
     * failure is Transient.
     */
    private fun current(create: Boolean): Pair<Int, SecretKey>? {
        val id = currentId()
        if (id != null) {
            val key = try {
                keystore.getKey(kekAlias(id))
            } catch (e: Exception) {
                throw SealTransientException("the current key can't be read now", e)
            }
            if (key != null) {
                if (!create || id in canaryGood) return (id to key).also { seen(key) }
                val canary = canaryError(id) ?: return (id to key).also { seen(key) }
                if (canary !is CanaryMissingException && !keyBroken(canary, keystore::signal)) {
                    throw SealTransientException("the current key's canary fails now", canary)
                }
            }
        }
        return if (create) makeKey(id) else null
    }

    /**
     * A new Keystore key under a new alias (§2.2): an id that reads null and that neither the stored id nor any record
     * names; then the key, its canary (wrapped and unwrapped again: the probe of code QA M1) and the id, in that order,
     * each prefs write committed or Transient (code QA L5). A crash between leaves an unused alias that [dropOldKeys]
     * removes. Never generated over an alias that might exist.
     */
    private fun makeKey(stored: Int?): Pair<Int, SecretKey> {
        try {
            val taken = cache.sealedKeys().mapNotNull { SealRecord.decode(cache.record(it))?.keyId }.toSet() + listOfNotNull(stored)
            var id: Int? = null
            var tries = 0
            while (id == null && tries++ < ID_TRIES) {
                val candidate = newId()
                if (candidate !in taken && keystore.getKey(kekAlias(candidate)) == null) id = candidate
            }
            if (id == null) throw SealTransientException("no free key id")
            val key = keystore.generate(kekAlias(id))
            val (iv, sealed) = keystore.wrap(key, canaryAad(id), CANARY)
            if (!keystore.unwrap(key, iv, canaryAad(id), sealed).contentEquals(CANARY)) throw SealTransientException("a new key's canary failed")
            if (!prefs.put(canaryPref(id), hex(iv + sealed))) throw SealTransientException("the canary wasn't stored")
            if (!prefs.put(PREF_ID, kekIdHex(id))) throw SealTransientException("the key id wasn't stored")
            canaryGood += id
            seen(key)
            return id to key
        } catch (e: SealTransientException) {
            throw e
        } catch (e: Exception) {
            throw SealTransientException("a new key couldn't be made", e)
        }
    }

    private fun seen(key: SecretKey) {
        if (keySeen) return
        keySeen = true
        runCatching { onKey(key) }
    }

    /**
     * A new data key and record for [cacheKey], overwriting any leftover (§4.3): a book with no record or a Lost one.
     * A new record starts its book's Unclassified count afresh (code QA L2).
     */
    private fun newRecord(cacheKey: String, kek: Pair<Int, SecretKey>) {
        val raw = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey().encoded
        try {
            val nonce = randomBytes(SealRecord.NONCE_SIZE)
            val (iv, wrapped) = try {
                keystore.wrap(kek.second, SealRecord.aad(kek.first, nonce, cacheKey), raw)
            } catch (e: Exception) {
                throw SealTransientException("the book's key couldn't be wrapped", e)
            }
            if (iv.size != SealRecord.WRAP_IV_SIZE || wrapped.size != SealRecord.WRAPPED_SIZE) throw SealTransientException("an unexpected wrap")
            val record = SealRecord(kek.first, nonce, iv, wrapped)
            try {
                cache.writeRecord(cacheKey, record.encode())
            } catch (e: Exception) {
                throw SealTransientException("the record couldn't be stored", e)
            }
            synchronized(memo) { memo[record.memoKey()] = BookKey(raw, nonce) }
            forget(cacheKey)
        } finally {
            raw.fill(0)
        }
    }

    /**
     * §2.2: the aliases no record names and that aren't current go, with their canaries; so do the canaries of ids
     * that are neither current nor named and have no alias left (code QA L1). A failure waits for the next check.
     */
    private fun dropOldKeys() {
        try {
            val named = cache.sealedKeys().mapNotNull { SealRecord.decode(cache.record(it))?.keyId }.toSet()
            val current = currentId()
            val aliases = keystore.aliases().mapNotNull(::kekIdOf).toSet()
            for (id in aliases) {
                if (id == current || id in named) continue
                keystore.delete(kekAlias(id))
                prefs.put(canaryPref(id), null)
            }
            for (name in prefs.names()) {
                if (!name.startsWith(CANARY_PREFIX)) continue
                val id = kekIdOf(KEK_ALIAS_PREFIX + name.removePrefix(CANARY_PREFIX)) ?: continue
                if (id != current && id !in named && id !in aliases) prefs.put(name, null)
            }
        } catch (e: Exception) {
            // the next clean check tries again
        }
    }

    companion object {
        const val MEMO_SIZE = 4
        const val PREF_ID = "seal_kek_id"
        private const val ID_TRIES = 8
        private const val GCM_IV = 12
        private const val TAG = "BookKeys"
        private val random = SecureRandom()

        private const val CANARY_PREFIX = "seal_canary_"

        fun canaryPref(id: Int) = CANARY_PREFIX + kekIdHex(id)

        fun failPref(cacheKey: String) = "seal_fail_$cacheKey"

        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

        private fun hexBytes(text: String) = ByteArray(text.length / 2) { text.substring(2 * it, 2 * it + 2).toInt(16).toByte() }

        @Volatile private var instance: BookKeys? = null

        /** The process's one [BookKeys] over the Android Keystore (TEE), the `music_seal` prefs and the one cache. */
        fun get(context: Context): BookKeys = instance ?: synchronized(this) {
            instance ?: create(context.applicationContext).also { instance = it }
        }

        private fun create(app: Context): BookKeys = BookKeys(
            AndroidKeys(),
            AppSealPrefs(app),
            MediaSealCache(app),
            log = { Log.w(TAG, it) },
            // §2.1: the key's security level, logged once per process (TEE expected; a software key only on a non-CTS phone)
            onKey = { key ->
                val info = SecretKeyFactory.getInstance(key.algorithm, ANDROID_KEYSTORE).getKeySpec(key, KeyInfo::class.java) as KeyInfo
                Log.i(TAG, "seal level ${info.securityLevel}")
            },
        )

        const val ANDROID_KEYSTORE = "AndroidKeyStore"
    }
}

/** The Android Keystore (keystore2, KeyMint in the TEE; no StrongBox, §2.1). `containsAlias` is never used (§2.2). */
private class AndroidKeys : KeyStoreSeam {
    private val store: KeyStore by lazy { KeyStore.getInstance(BookKeys.ANDROID_KEYSTORE).apply { load(null) } }

    override fun getKey(alias: String): SecretKey? = store.getKey(alias, null) as SecretKey?

    override fun generate(alias: String): SecretKey {
        // The TEE; the default randomized IV; no user auth; no setUnlockedDeviceRequired; no StrongBox (§2.1).
        val spec = KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build()
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, BookKeys.ANDROID_KEYSTORE).apply { init(spec) }.generateKey()
    }

    override fun wrap(key: SecretKey, aad: ByteArray, plain: ByteArray): Pair<ByteArray, ByteArray> {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key)
        c.updateAAD(aad)
        val sealed = c.doFinal(plain)
        return c.iv to sealed
    }

    override fun unwrap(key: SecretKey, iv: ByteArray, aad: ByteArray, sealed: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return c.doFinal(sealed)
    }

    override fun aliases(): List<String> = store.aliases().toList().filter { it.startsWith(KEK_ALIAS_PREFIX) }

    override fun delete(alias: String) = store.deleteEntry(alias)

    override fun signal(error: Throwable): KeystoreSignal? = when (error) {
        is KeyPermanentlyInvalidatedException -> KeystoreSignal.Invalidated
        is android.security.KeyStoreException ->
            KeystoreSignal.Code(error.numericErrorCode == android.security.KeyStoreException.ERROR_KEY_DOES_NOT_EXIST, error.isTransientFailure)
        else -> null
    }
}

/** `music_seal` (noBackupFilesDir isn't needed: every domain is excluded from backup and transfer). Written with commit. */
private class AppSealPrefs(context: Context) : SealPrefs {
    private val prefs = context.getSharedPreferences("music_seal", Context.MODE_PRIVATE)

    override fun get(name: String): String? = prefs.getString(name, null)

    override fun put(name: String, value: String?): Boolean {
        val edit = prefs.edit()
        if (value == null) edit.remove(name) else edit.putString(name, value)
        return edit.commit()
    }

    override fun names(): Set<String> = prefs.all.keys.toSet()
}

/** The record is the resource's `vibb_seal` content metadata, stored synchronously with the index (§3.1). */
@OptIn(UnstableApi::class)
private class MediaSealCache(private val context: Context) : SealCache {
    private val cache get() = MediaCache.get(context)

    override fun record(cacheKey: String): ByteArray? = cache.getContentMetadata(cacheKey).get(SEAL_METADATA, null as ByteArray?)

    override fun writeRecord(cacheKey: String, record: ByteArray) =
        cache.applyContentMetadataMutations(cacheKey, ContentMetadataMutations().set(SEAL_METADATA, record))

    override fun hasSpans(cacheKey: String): Boolean = cache.getCachedSpans(cacheKey).isNotEmpty()

    override fun sealedKeys(): List<String> = cache.keys.filter(::isSealedKey)

    override fun removeCopy(cacheKey: String) {
        runBlocking { CacheThrough.removeCopy(context, cacheKey, false) }
    }

    companion object {
        const val SEAL_METADATA = "vibb_seal"
    }
}
