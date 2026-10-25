package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.os.Looper
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** The Keystore alias of the key behind every sealed pref (design r10 §1). */
const val SECRETS_KEY_ALIAS = "secrets_v1"

/** A sealed field as read (design r10 §2): none stored, its plaintext, or stored but not openable. */
sealed interface Sealed {
    data object Absent : Sealed

    data class Value(val plain: String) : Sealed {
        override fun toString() = "Value(<redacted>)"
    }

    data object Unreadable : Sealed
}

/** AES-GCM under a key that never leaves the TEE, injected (the JVM tests use a JVM key). [aad] binds a blob to its
 * field (`prefs-file/key`). Every method throws on failure; only [seal] makes a missing key. */
interface SecretBox {
    /** IV (12 bytes) || ciphertext+tag. */
    fun seal(aad: String, plain: ByteArray): ByteArray

    fun open(aad: String, blob: ByteArray): ByteArray

    /** Deletes the key and makes a new one. */
    fun recreate()
}

/**
 * The phone's secrets on disk only as ciphertext (design r10 §2, security review P-L5): a sealed value is `ks1:` +
 * Base64(IV || ciphertext+tag) in the same pref key as the plaintext before it. Every plaintext the fields held is
 * hex, so the prefix alone tells sealed from legacy.
 *
 * - [read]: a sealed value is opened; one that doesn't open (or isn't Base64) is [Sealed.Unreadable], logged once
 *   per stored value, and the stored string is never touched - a transient Keystore error must not cost the phone
 *   its enrolment. A legacy plaintext is returned and, with `migrate`, sealed into the same key (§4).
 * - [edit]: every write of a sealed field - seal, put and commit inside [LOCK]; a failed seal commits nothing.
 *   Never a plaintext fallback. The migration commits under the same hold, so it never writes an old value over a
 *   newer one (QA R10-2).
 * - [LOCK] is a leaf lock: nothing but the prefs and the Keystore runs under it; callers may hold other locks.
 *   A read without `migrate` never takes it (revision check N6): the first-time keygen is only in a seal.
 * - The memo: one (stored string, plaintext) pair per AAD; a hit takes no lock and no Keystore call. Only values,
 *   never [Sealed.Unreadable], so a retry can heal (QA R10-12).
 */
class SealedPrefs(private val box: SecretBox, private val log: (String, Throwable?) -> Unit) {

    companion object {
        const val PREFIX = "ks1:"
    }

    val LOCK = Any()

    private class Memo(val stored: String, val plain: String)

    private val memos = ConcurrentHashMap<String, Memo>()

    /** The stored string last logged as unreadable, per AAD: logged once per stored value. */
    private val loggedUnreadable = ConcurrentHashMap<String, String>()

    /** Puts sealed values into one editor; see [edit]. */
    inner class Editor internal constructor(val raw: SharedPreferences.Editor) {
        private val sealed = mutableMapOf<String, Memo?>()

        /** Seals [plain] for [aad] into [key]; throws when the seal fails. */
        fun putSealed(aad: String, key: String, plain: String): Editor = apply {
            val stored = sealString(aad, plain)
            raw.putString(key, stored)
            sealed[aad] = Memo(stored, plain)
        }

        fun remove(aad: String, key: String): Editor = apply {
            raw.remove(key)
            sealed[aad] = null
        }

        /** After the commit: the memo follows what was written. */
        internal fun remember() {
            for ((aad, memo) in sealed) if (memo == null) memos.remove(aad) else memos[aad] = memo
        }
    }

    fun read(prefs: SharedPreferences, aad: String, key: String, migrate: Boolean): Sealed {
        val stored = prefs.getString(key, null) ?: return Sealed.Absent
        memos[aad]?.let { if (it.stored == stored) return Sealed.Value(it.plain) }
        if (!stored.startsWith(PREFIX)) {
            if (migrate) migrate(prefs, aad, key, stored)
            return Sealed.Value(stored)
        }
        return try {
            val blob = Base64.getDecoder().decode(stored.substring(PREFIX.length))
            val plain = String(box.open(aad, blob), Charsets.UTF_8)
            memos[aad] = Memo(stored, plain)
            loggedUnreadable.remove(aad)
            Sealed.Value(plain)
        } catch (e: Exception) {
            if (loggedUnreadable.put(aad, stored) != stored) log("Sealed $aad doesn't open - kept as it is", e)
            Sealed.Unreadable
        }
    }

    /** True when [block] ran and its edits were committed; a throwing seal (or block) commits nothing. */
    fun edit(prefs: SharedPreferences, block: (Editor) -> Unit): Boolean = tryEdit(prefs, block) ?: false

    /** `null`: the block threw (a seal failed) and nothing was committed; else the commit's result. */
    private fun tryEdit(prefs: SharedPreferences, block: (Editor) -> Unit): Boolean? = synchronized(LOCK) {
        val editor = Editor(prefs.edit())
        try {
            block(editor)
        } catch (e: Exception) {
            log("Sealing failed - nothing written", e)
            return@synchronized null
        }
        val ok = editor.raw.commit()
        if (ok) editor.remember()
        ok
    }

    /**
     * The device token's write rule (QA R10-9): a failed seal is retried once; failing again, the key is made anew
     * under [LOCK] (the memo cleared) and sealed once more; false then, nothing written. Only for a field that is
     * being replaced anyway - the other fields' blobs the old key leaves behind come back by their own paths.
     */
    fun putRecovering(prefs: SharedPreferences, aad: String, key: String, plain: String): Boolean = synchronized(LOCK) {
        val write: (Editor) -> Unit = { it.putSealed(aad, key, plain) }
        repeat(2) { tryEdit(prefs, write)?.let { return@synchronized it } }
        try {
            box.recreate()
        } catch (e: Exception) {
            log("Couldn't make a new secrets key", e)
        }
        memos.clear()
        tryEdit(prefs, write) ?: false
    }

    /** §4: re-read under [LOCK]; still the same plaintext -> sealed into the same key in one commit. A failed seal
     * keeps the plaintext (the next read retries). */
    private fun migrate(prefs: SharedPreferences, aad: String, key: String, plain: String) {
        synchronized(LOCK) {
            if (prefs.getString(key, null) != plain) return
            val stored = try {
                sealString(aad, plain)
            } catch (e: Exception) {
                log("Sealing $aad failed - the plaintext stays until the next read", e)
                return
            }
            if (prefs.edit().putString(key, stored).commit()) memos[aad] = Memo(stored, plain) else log("Couldn't commit sealed $aad", null)
        }
    }

    private fun sealString(aad: String, plain: String): String =
        PREFIX + Base64.getEncoder().encodeToString(box.seal(aad, plain.toByteArray(Charsets.UTF_8)))
}

/** The sealed fields on this phone, under [SECRETS_KEY_ALIAS]. A read on the main thread never migrates (QA R10-7):
 * it opens, or returns the legacy value; the background pass and background readers migrate. */
object Secrets {
    val prefs = SealedPrefs(KeystoreSecretBox) { message, error -> Log.w("SealedPrefs", message, error) }

    fun migrateHere(): Boolean = Looper.myLooper() != Looper.getMainLooper()

    /** `Application.initRest`'s one background pass (§4): the three through their own accessors, so a reset token
     * isn't left plain until the next window. Never throws. */
    fun migrateAll(context: Context) {
        val app = context.applicationContext
        try {
            DeviceToken.get()
            com.kidslauncher.mdm.lock.PinLockStore.config(app)
            WindowRelock.store(app).token()
        } catch (e: Exception) {
            Log.w("SealedPrefs", "The secrets' migration pass failed", e)
        }
    }
}

/**
 * The device token (design r10 §3.1): default prefs `mdm.device_token`, sealed. The only code that names the
 * generated getter or setter (`SealedPrefsTest`'s source scan): a raw read would send `Bearer ks1:...`.
 */
object DeviceToken {
    private const val AAD = "default/mdm.device_token"

    private fun prefs(): SharedPreferences = LauncherPreferences.getSharedPreferences()

    private fun key(): String = LauncherPreferences.mdm().keys().deviceToken()

    fun state(): Sealed = Secrets.prefs.read(prefs(), AAD, key(), migrate = Secrets.migrateHere())

    /** The token, or `null` - none, blank or [Sealed.Unreadable]: every site's "not enrolled, skip" covers a broken key. */
    fun get(): String? = (state() as? Sealed.Value)?.plain?.takeIf { it.isNotBlank() }

    /** Something is stored (sealed or not), no Keystore call: the first-launch reset's "enrolled" half. */
    fun present(): Boolean = !prefs().getString(key(), null).isNullOrBlank()

    /** Sealed and committed synchronously; false when it couldn't be sealed or written. Off the main thread. */
    fun set(token: String): Boolean = Secrets.prefs.putRecovering(prefs(), AAD, key(), token)
}

/** AES-256-GCM in the AndroidKeyStore: not exportable, the TEE (not StrongBox, the user's choice), no user
 * authentication and no `UnlockedDeviceRequired` - the sync, the stream and the relock use the secrets while the
 * screen is locked (design r10 §1, §6: never add either). */
private object KeystoreSecretBox : SecretBox {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun create(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(SECRETS_KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    private fun key(): SecretKey? = keyStore().getKey(SECRETS_KEY_ALIAS, null) as? SecretKey

    override fun seal(aad: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key() ?: create())
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv.size == IV_BYTES) { "unexpected IV length" }
        return iv + sealed
    }

    override fun open(aad: String, blob: ByteArray): ByteArray {
        require(blob.size > IV_BYTES) { "short blob" }
        val key = key() ?: throw IllegalStateException("no secrets key")
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, blob, 0, IV_BYTES))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(blob, IV_BYTES, blob.size - IV_BYTES)
    }

    override fun recreate() {
        keyStore().deleteEntry(SECRETS_KEY_ALIAS)
        create()
    }
}
