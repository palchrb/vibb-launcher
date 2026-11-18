package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.kidslauncher.mdm.server.dto.PolicyResponse
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey

/** The Keystore alias of the parent PIN's vault key (design 24b §2). */
const val PIN_VAULT_KEY_ALIAS = "pin_vault_v1"

/** The Keystore key behind the vault, injected (PinVaultTest uses a JVM HMAC key). */
interface VaultKey {
    /** A Mac initialised with the key; [create] makes the key when it is missing (a wrap may, a
     * check may not - there a missing key is a failure). Throws when the key can't be used. */
    fun mac(create: Boolean): Mac

    /** Deletes the key and makes a new one. */
    fun recreate()
}

enum class PinVerdict { MATCH, NO_MATCH, UNAVAILABLE }

/** The parent PIN's fingerprint changed although the PIN didn't (a re-wrap): a lockout state stored
 * for one of [from] carries over to [to] (QA #6). */
data class FingerprintChange(val from: Set<String>, val to: String)

/** [PinVaultCore.accept]'s answer: the policy without the hash, the only copy cached or passed on. */
data class VaultAccept(val policy: PolicyResponse, val change: FingerprintChange?)

/** [policy] without the parent PIN's hash: `""` for one, `null` stays `null` (no PIN). */
fun redactedPolicy(policy: PolicyResponse): PolicyResponse = policy.copy(overridePinHash = policy.overridePinHash?.let { "" })

/**
 * The parent PIN's vault (design 24b §2, review L6): instead of the PBKDF2 hash, the phone keeps
 * `tag = HMAC(K, hash)` with a Keystore key K that never leaves the TEE (CE prefs `pin_vault`,
 * every write a `commit()`). [accept] is the only reader of a policy's `override_pin_hash`; the
 * policy it returns has `""` there, and only that is cached. A PIN is checked as
 * `HMAC(K, PBKDF2(pin, salt)) == tag`. So a copy of the files - a backup, an extraction without the
 * TEE - gives nothing to crack; a rooted, running phone can still try every PIN through the key
 * (the salt is in the clear, so PBKDF2 can be precomputed: about an hour for 6 digits).
 *
 * The parent PIN only. Since design r10 the kid's hash is sealed under its own key (`secrets_v1`, [SealedPrefs]), so
 * 24b's QA #7 ("the kid's lock never depends on a key") is reversed: a total Keystore loss leaves the lock `BAD_HASH`
 * and this vault `untilSync`, and "Ny oppsettkode" (design r10 §5) is the way back. A key that
 * fails ([VaultKey.mac] throws twice - one retry for a transient Keystore error) sets
 * `rewrap_needed`: the parent PIN answers "can't be checked until the phone reaches the server",
 * and the next accepted policy makes a new key and wraps again. Used only under [ParentPin]'s lock.
 */
class PinVaultCore(
    private val prefs: SharedPreferences,
    private val key: VaultKey,
    private val log: (String, Throwable?) -> Unit,
) {
    private companion object {
        const val TAG = "tag"
        const val SALT = "salt"
        const val REWRAP = "rewrap_needed"
        const val FINGERPRINT_CHARS = 16
    }

    private fun tag(): String? = prefs.getString(TAG, null)?.takeIf { PinHash.parseHex(it) != null }

    fun salt(): String? = prefs.getString(SALT, null)?.takeIf { PinHash.parseHex(it) != null }

    fun rewrapNeeded(): Boolean = prefs.getBoolean(REWRAP, false)

    /** A parent PIN is set (checkable, or waiting for its re-wrap). */
    fun configured(): Boolean = salt() != null && (tag() != null || rewrapNeeded())

    /** Which PIN a lockout state is about: the first 16 hex characters of the tag. */
    fun fingerprint(): String? = tag()?.take(FINGERPRINT_CHARS)

    private fun hmac(bytes: ByteArray, create: Boolean): ByteArray? {
        repeat(2) { attempt ->
            try {
                return key.mac(create).doFinal(bytes)
            } catch (e: Exception) {
                log("Parent PIN vault: the key failed (try ${attempt + 1})", e)
            }
        }
        return null
    }

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    /** Checks [pin] (PBKDF2, then the key's HMAC; ~0.5 s). [PinVerdict.UNAVAILABLE] while a re-wrap
     * is needed, or when the key fails now (then it is needed). */
    fun verify(pin: String): PinVerdict {
        if (rewrapNeeded()) return PinVerdict.UNAVAILABLE
        val tag = PinHash.parseHex(tag()) ?: return PinVerdict.NO_MATCH
        val salt = PinHash.parseHex(salt()) ?: return PinVerdict.NO_MATCH
        val derived = try {
            PinHash.derive(pin, salt)
        } catch (e: Exception) {
            return PinVerdict.NO_MATCH
        }
        val mac = hmac(derived, create = false) ?: run {
            prefs.edit().putBoolean(REWRAP, true).commit()
            return PinVerdict.UNAVAILABLE
        }
        return if (MessageDigest.isEqual(mac, tag)) PinVerdict.MATCH else PinVerdict.NO_MATCH
    }

    /**
     * An accepted fresh policy: wraps its hash (a new key first when one is needed), stores the
     * tag with the salt, and returns the policy with `override_pin_hash = ""` - `null` stays `null`
     * (no PIN: the tag goes). A policy that is already redacted (`""`) changes nothing.
     */
    fun accept(policy: PolicyResponse): VaultAccept {
        val hash = policy.overridePinHash
        if (hash == "") return VaultAccept(policy, null)
        val redacted = redactedPolicy(policy)
        val saltHex = policy.overridePinSalt
        val previousTag = tag()
        val previousSalt = salt()
        if (rewrapNeeded()) {
            try {
                key.recreate()
            } catch (e: Exception) {
                log("Parent PIN vault: couldn't make a new key", e)
            }
        }
        if (hash == null || saltHex == null || !PinHash.usable(hash, saltHex)) {
            prefs.edit().remove(TAG).remove(SALT).putBoolean(REWRAP, false).commit()
            return VaultAccept(redacted, null)
        }
        val tag = hmac(PinHash.parseHex(hash)!!, create = true)?.let(::hex)
        if (tag == null) {
            // The old tag and salt stay (QA 24b Low 1), as in verify(): the next re-wrap of the same
            // PIN still carries its lockout over. Without one, the salt marks the PIN as set.
            val editor = prefs.edit().putBoolean(REWRAP, true)
            if (previousTag == null) editor.putString(SALT, saltHex)
            editor.commit()
            return VaultAccept(redacted, null)
        }
        if (tag != previousTag || saltHex != previousSalt || rewrapNeeded()) {
            prefs.edit().putString(TAG, tag).putString(SALT, saltHex).putBoolean(REWRAP, false).commit()
        }
        val to = tag.take(FINGERPRINT_CHARS)
        // The same PIN as before (the same salt: the server makes a new one for every save) under a
        // new key: its lockout carries over. Another PIN resets it.
        val from = previousTag?.take(FINGERPRINT_CHARS)?.takeIf { previousSalt == saltHex && it != to }
        return VaultAccept(redacted, from?.let { FingerprintChange(setOf(it), to) })
    }

}

/** The vault on this phone: CE prefs `pin_vault`, the Keystore key [PIN_VAULT_KEY_ALIAS]. Only
 * [ParentPin] uses it, under its lock. */
object PinVault {
    private const val PREFS = "pin_vault"
    private const val LOG_TAG = "PinVault"

    fun of(context: Context): PinVaultCore = PinVaultCore(
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE),
        KeystoreVaultKey,
    ) { message, error -> Log.w(LOG_TAG, message, error) }
}

/** HMAC-SHA256 in the AndroidKeyStore: not exportable, the TEE (no StrongBox: an HMAC wrapper
 * gains nothing from it but latency, QA S4), no user authentication. */
private object KeystoreVaultKey : VaultKey {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun create(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(PIN_VAULT_KEY_ALIAS, KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY).build())
        return generator.generateKey()
    }

    override fun mac(create: Boolean): Mac {
        val key = keyStore().getKey(PIN_VAULT_KEY_ALIAS, null) as? SecretKey
            ?: if (create) create() else throw IllegalStateException("no vault key")
        return Mac.getInstance("HmacSHA256").apply { init(key) }
    }

    override fun recreate() {
        keyStore().deleteEntry(PIN_VAULT_KEY_ALIAS)
        create()
    }
}
