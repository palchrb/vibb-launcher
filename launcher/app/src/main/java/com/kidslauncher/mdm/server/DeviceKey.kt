package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import kotlinx.serialization.Serializable
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/*
 * Design 129 §2: the phone's hardware-bound key. An EC P-256 signing key in AndroidKeyStore (StrongBox when the phone
 * has it, the TEE otherwise - and the TEE once on any StrongBox failure, never further), attested for a challenge the
 * server fixes; no user authentication and no `UnlockedDeviceRequired` (the sync, the stream and the relock run while
 * locked). Never a key without attestation, never a software fallback. Aliases `device_key_<n>`; the phone keeps every
 * key it made until the server names one (`KeyRegistration`), at most [MAX_DEVICE_KEYS].
 */

/** The aliases' prefix. */
const val DEVICE_KEY_ALIAS_PREFIX = "device_key_"

/** Google's remote key provisioning app (design 129 Q9): it fills the attestation-key pool, so it stays outside the
 * kid's VPN (`KidVpnService`) and in lockdown's allow list (`MobileNetworkRuntime`). */
const val RKPD_PACKAGE = "com.android.rkpdapp"

/** Whether the phone has [RKPD_PACKAGE] (a system app on Android 14 and later; absent on some builds). Never throws. */
fun rkpdInstalled(context: Context): Boolean = try {
    context.packageManager.getApplicationInfo(RKPD_PACKAGE, PackageManager.ApplicationInfoFlags.of(0))
    true
} catch (e: Exception) {
    false
}

/** Lockdown's allow list (design 129 Q9): rkpd, when the phone has it and lockdown is on - else nothing (design 95
 * D4: neither the music app nor Play services goes around the sink). */
fun lockdownAllowList(lockdown: Boolean, rkpd: Boolean): Set<String> = if (lockdown && rkpd) setOf(RKPD_PACKAGE) else emptySet()

/** Why a key couldn't be made: [keysUnavailable] - the attestation-key pool is empty (`keys_unavailable`, transient:
 * a retry, never a downgrade); else the Keystore failed. */
class KeyGenFailure(val keysUnavailable: Boolean, cause: Throwable?) : Exception(
    if (keysUnavailable) "keys_unavailable" else "the key couldn't be made", cause,
)

/** The phone's key hardware, a seam for tests. */
interface KeyHardware {
    /** Makes [alias] attested for [challenge]; throws [KeyGenFailure]. */
    fun generate(alias: String, challenge: ByteArray)

    /** The alias's certificate chain, leaf first, as DER; `null` when the key is gone. */
    fun chain(alias: String): List<ByteArray>?

    /** DER ECDSA over [data] with SHA-256; throws when the key is unusable. */
    fun sign(alias: String, data: ByteArray): ByteArray

    /** Every alias with [DEVICE_KEY_ALIAS_PREFIX]. */
    fun aliases(): List<String>

    fun delete(alias: String)
}

/** The SHA-256 of a chain's leaf SubjectPublicKeyInfo, lowercase hex - the server's `spki_sha256`. */
fun leafSpkiSha256(chain: List<ByteArray>): String? {
    val leaf = chain.firstOrNull() ?: return null
    return try {
        val cert = java.security.cert.CertificateFactory.getInstance("X.509")
            .generateCertificate(leaf.inputStream()) as java.security.cert.X509Certificate
        hexOf(sha256(cert.publicKey.encoded))
    } catch (e: Exception) {
        null
    }
}

/** AndroidKeyStore. */
class AndroidKeyHardware(private val context: Context) : KeyHardware {
    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    override fun generate(alias: String, challenge: ByteArray) {
        val strongBox = try {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_STRONGBOX_KEYSTORE)
        } catch (e: Exception) {
            false
        }
        if (strongBox) {
            try {
                make(alias, challenge, strongBox = true)
                return
            } catch (e: Exception) {
                // Design 129 L5: any StrongBox failure falls back to the TEE, once.
                Log.w(LOG_TAG, "StrongBox couldn't make the key (${e.javaClass.simpleName}) - the TEE instead")
                runCatching { delete(alias) }
                if (keysUnavailable(e)) throw KeyGenFailure(true, e)
            }
        }
        try {
            make(alias, challenge, strongBox = false)
        } catch (e: Exception) {
            runCatching { delete(alias) }
            throw KeyGenFailure(keysUnavailable(e), e)
        }
    }

    private fun make(alias: String, challenge: ByteArray, strongBox: Boolean) {
        val generator = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore")
        generator.initialize(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_SIGN)
                .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                .setDigests(KeyProperties.DIGEST_SHA256)
                .setAttestationChallenge(challenge)
                .setIsStrongBoxBacked(strongBox)
                .build(),
        )
        generator.generateKeyPair()
    }

    override fun chain(alias: String): List<ByteArray>? = try {
        keyStore().getCertificateChain(alias)?.map { it.encoded }?.takeIf { it.isNotEmpty() }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "No chain for a key: ${e.javaClass.simpleName}")
        null
    }

    override fun sign(alias: String, data: ByteArray): ByteArray {
        val key = keys[alias] ?: (keyStore().getKey(alias, null) as? PrivateKey)?.also { keys[alias] = it }
            ?: throw IllegalStateException("no key")
        return try {
            Signature.getInstance("SHA256withECDSA").run {
                initSign(key)
                update(data)
                sign()
            }
        } catch (e: Exception) {
            keys.remove(alias)
            throw e
        }
    }

    override fun aliases(): List<String> = keyStore().aliases().toList().filter { it.startsWith(DEVICE_KEY_ALIAS_PREFIX) }

    override fun delete(alias: String) {
        keys.remove(alias)
        keyStore().deleteEntry(alias)
    }

    companion object {
        private const val LOG_TAG = "DeviceKey"

        /** The handles, kept so a request's sign doesn't reload the KeyStore. */
        private val keys = ConcurrentHashMap<String, PrivateKey>()

        /** Design 129 M7: an empty attestation-key pool (`ERROR_ATTESTATION_KEYS_UNAVAILABLE`, API 33), anywhere in the
         * cause chain. */
        fun keysUnavailable(e: Throwable): Boolean {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is android.security.KeyStoreException &&
                    cause.numericErrorCode == android.security.KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE
                ) {
                    return true
                }
                cause = cause.cause
            }
            return false
        }
    }
}

/** The adopted key: the server's [keyId] for [alias], and the state step 1 last named. */
@Serializable
data class AdoptedKey(val alias: String, val keyId: String, val state: String = "observed")

/** What the phone keeps about its keys (plain prefs, not secrets, design 129 §2). */
@Serializable
data class KeyBookState(
    val next: Int = 1,
    val keys: List<KeyAlias> = emptyList(),
    val adopted: AdoptedKey? = null,
    /** The key (SPKI hash) last reported as not this phone's (§5.2 step 3): reported once. */
    val conflictReported: String? = null,
    /** §5.4: the server no longer knows the adopted key and no bearer is left - "Ny oppsettkode". */
    val lost: Boolean = false,
    /** The last refusal's reason (a registration's 400 word, `keys_unavailable`, `keystore`), for the log and Settings. */
    val lastRefusal: String? = null,
    /** The SPKI hash of each key, read once from its chain. */
    val spki: Map<String, String> = emptyMap(),
    /** An enrolment key's challenge (Base64url): a retried enrolment with the same code reuses its key (§5.1). */
    val enrolChallenges: Map<String, String> = emptyMap(),
)

/** The key book: one JSON in CE prefs `device_key`, written with `commit()` under one lock. */
object KeyBook {
    private const val PREFS = "device_key"
    private const val STATE = "state"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun read(context: Context): KeyBookState = try {
        prefs(context).getString(STATE, null)?.let { ServerJson.decodeFromString(KeyBookState.serializer(), it) } ?: KeyBookState()
    } catch (e: Exception) {
        Log.w("DeviceKey", "The key book didn't read: ${e.javaClass.simpleName}")
        KeyBookState()
    }

    @Synchronized
    fun update(context: Context, change: (KeyBookState) -> KeyBookState): KeyBookState {
        val next = change(read(context))
        prefs(context).edit().putString(STATE, ServerJson.encodeToString(KeyBookState.serializer(), next)).commit()
        return next
    }

    /** A key is adopted (no Keystore call). */
    fun adoptedStored(context: Context): Boolean = read(context).adopted != null
}

/** One made key with what goes to the server. */
class MadeKey(val alias: String, val chain: List<ByteArray>, val spki: String) {
    /** The chain as the server reads it: standard Base64 DER, leaf first. */
    fun chainB64(): List<String> = chain.map { Base64.getEncoder().encodeToString(it) }
}

/**
 * Making, proving and dropping keys - the one place the book and the hardware change together. Only the registration
 * flow (`KeyRegistration`) and the enrolment (`Enrollment`) call it.
 */
object DeviceKeys {
    private const val LOG_TAG = "DeviceKey"

    /** An enrolment key's challenge holds for the code's life; the phone doesn't know it, so a day. */
    const val ENROL_CHALLENGE_HOLDS_MS = 24 * 60 * 60_000L

    @Volatile
    var hardwareFor: (Context) -> KeyHardware = { AndroidKeyHardware(it.applicationContext) }

    fun hardware(context: Context): KeyHardware = hardwareFor(context)

    /**
     * A new key for [challenge] (§2): the cap first ([keyCapPlan]: `null` - no key may go, nothing is made), then the
     * alias, its chain and SPKI hash. Throws [KeyGenFailure].
     */
    fun make(context: Context, challenge: ByteArray, challengeUntilMs: Long, nowMs: Long, enrolChallenge: String? = null): MadeKey? {
        val hw = hardware(context)
        val book = reconcile(context, hw)
        val drop = keyCapPlan(book.keys, nowMs) ?: run {
            Log.w(LOG_TAG, "Three keys the server may still name - no new one yet")
            return null
        }
        for (alias in drop) forget(context, hw, alias)
        val alias = DEVICE_KEY_ALIAS_PREFIX + KeyBook.update(context) { it.copy(next = it.next + 1) }.next.minus(1)
        hw.generate(alias, challenge)
        val chain = hw.chain(alias)
        val spki = chain?.let(::leafSpkiSha256)
        if (chain == null || spki == null) {
            runCatching { hw.delete(alias) }
            throw KeyGenFailure(false, null)
        }
        KeyBook.update(context) { state ->
            state.copy(
                keys = state.keys + KeyAlias(alias, nowMs, challengeUntilMs),
                spki = state.spki + (alias to spki),
                enrolChallenges = if (enrolChallenge != null) state.enrolChallenges + (alias to enrolChallenge) else state.enrolChallenges,
            )
        }
        return MadeKey(alias, chain, spki)
    }

    /** §5.1: a key made earlier for the same enrolment challenge (a retried enrolment), still in the Keystore. */
    fun enrolKeyFor(context: Context, challenge: String): MadeKey? {
        val hw = hardware(context)
        val book = reconcile(context, hw)
        val alias = book.enrolChallenges.entries.firstOrNull { it.value == challenge }?.key ?: return null
        val chain = hw.chain(alias) ?: return null
        val spki = book.spki[alias] ?: leafSpkiSha256(chain) ?: return null
        return MadeKey(alias, chain, spki)
    }

    /** The proof over [challenge] (§5.1, §5.2) - signed afresh on every attempt. */
    fun proof(context: Context, alias: String, challenge: ByteArray, kind: ProofKind): String =
        b64u(hardware(context).sign(alias, proofMessage(challenge, kind).toByteArray(Charsets.UTF_8)))

    /** The book's keys that are in the Keystore, and the Keystore's that the book lost (as never named, long expired). */
    fun reconcile(context: Context, hw: KeyHardware = hardware(context)): KeyBookState {
        val present = try {
            hw.aliases().toSet()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The Keystore's aliases didn't read: ${e.javaClass.simpleName}")
            return KeyBook.read(context)
        }
        return KeyBook.update(context) { state ->
            val known = state.keys.filter { it.alias in present }
            val strays = (present - known.map { it.alias }.toSet()).map { KeyAlias(it, 0L, 0L) }
            state.copy(
                keys = known + strays,
                spki = state.spki.filterKeys { it in present },
                enrolChallenges = state.enrolChallenges.filterKeys { it in present },
                adopted = state.adopted?.takeIf { it.alias in present },
            )
        }
    }

    /** The SPKI hash of each local key, read from its chain when the book has none. */
    fun localSpki(context: Context, hw: KeyHardware = hardware(context)): Map<String, String> {
        val book = reconcile(context, hw)
        val out = LinkedHashMap<String, String>()
        for (key in book.keys) {
            val spki = book.spki[key.alias] ?: hw.chain(key.alias)?.let(::leafSpkiSha256) ?: continue
            out[key.alias] = spki
        }
        if (out != book.spki) KeyBook.update(context) { it.copy(spki = out) }
        return out
    }

    /**
     * Adopts [alias] under the server's [keyId] (§5.2 step 2): marks it named and deletes every other key. A new key id
     * clears a reported conflict and the lost mark.
     */
    fun adopt(context: Context, alias: String, keyId: String, state: String) {
        val hw = hardware(context)
        val others = KeyBook.read(context).keys.map { it.alias }.filter { it != alias }
        KeyBook.update(context) { book ->
            book.copy(
                adopted = AdoptedKey(alias, keyId, state),
                keys = book.keys.map { if (it.alias == alias) it.copy(named = true) else it },
                conflictReported = null,
                lost = false,
                lastRefusal = null,
            )
        }
        for (other in others) forget(context, hw, other)
    }

    /** Drops [alias] from the Keystore and the book. Only an adoption, the cap, a refused or unknown key, and a new
     * enrolment call it. */
    fun forget(context: Context, hw: KeyHardware = hardware(context), alias: String) {
        try {
            hw.delete(alias)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A key didn't delete: ${e.javaClass.simpleName}")
        }
        KeyBook.update(context) { book ->
            book.copy(
                keys = book.keys.filter { it.alias != alias },
                spki = book.spki - alias,
                enrolChallenges = book.enrolChallenges - alias,
                adopted = book.adopted?.takeIf { it.alias != alias },
            )
        }
    }

    /** A new enrolment (§5.1): the old enrolment's keys and marks go, but [keep] (this enrolment's key). */
    fun newEnrolment(context: Context, keep: String?) {
        val hw = hardware(context)
        for (key in reconcile(context, hw).keys) if (key.alias != keep) forget(context, hw, key.alias)
        KeyBook.update(context) { it.copy(adopted = null, conflictReported = null, lost = false, lastRefusal = null) }
    }

    fun refused(context: Context, reason: String) {
        KeyBook.update(context) { it.copy(lastRefusal = reason) }
    }
}
