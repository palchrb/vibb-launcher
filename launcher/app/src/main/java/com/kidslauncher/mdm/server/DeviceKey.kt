package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
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

/** The service action Keystore's remote provisioning binds (AOSP `packages/modules/RemoteKeyProvisioning`,
 * `app/AndroidManifest.xml`: `.service.RemoteProvisioningService`, read 2026-11-24) - how rkpd is found whatever a
 * vendor names its package. */
const val RKPD_SERVICE_ACTION = "com.android.rkpdapp.IRemoteProvisioning"

/**
 * Google's remote key provisioning app (design 129 Q9): it fills the attestation-key pool, so it stays outside the kid's
 * VPN (`KidVpnService`) and in lockdown's allow list (`MobileNetworkRuntime`). Found by what it does - a system service
 * for [RKPD_SERVICE_ACTION] - and by its known names ([RKPD_PACKAGES]: AOSP's and Google's, which the Jelly Star has),
 * each kept only under [rkpdExemptable]'s guard. Read once a process. None found: logged once; the key path then waits
 * as `keys_unavailable` where the pool runs dry, never a lockout.
 */
object Rkpd {
    @Volatile
    private var found: Set<String>? = null

    fun packages(context: Context): Set<String> = found ?: find(context).also { found = it }

    private fun find(context: Context): Set<String> {
        val pm = context.packageManager
        val byAction = try {
            pm.queryIntentServices(
                Intent(RKPD_SERVICE_ACTION),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_SYSTEM_ONLY.toLong()),
            ).mapNotNull { it.serviceInfo?.packageName }
        } catch (e: Exception) {
            emptyList()
        }
        val out = (byAction + RKPD_PACKAGES).distinct().filter { pkg ->
            try {
                val info = pm.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(0))
                val ok = rkpdExemptable(info.uid, info.flags and ApplicationInfo.FLAG_SYSTEM != 0, pm.getPackagesForUid(info.uid)?.size ?: 0)
                if (!ok) Log.w("DeviceKey", "An rkpd candidate fails the guard - not exempted")
                ok
            } catch (e: Exception) {
                false
            }
        }.toSet()
        if (out.isEmpty()) Log.w("DeviceKey", "No rkpd on this phone - the key waits for keys where the sink is on")
        return out
    }
}

/** Lockdown's allow list (design 129 Q9): the rkpd packages while lockdown is on - else nothing (design 95 D4: neither
 * the music app nor Play services goes around the sink). */
fun lockdownAllowList(lockdown: Boolean, rkpd: Set<String>): Set<String> = if (lockdown) rkpd else emptySet()

/** Why a key couldn't be made: [keysUnavailable] - the attestation-key pool is empty (`keys_unavailable`, transient:
 * a retry, never a downgrade); else the Keystore failed. */
class KeyGenFailure(val keysUnavailable: Boolean, cause: Throwable?) : Exception(
    if (keysUnavailable) "keys_unavailable" else "the key couldn't be made", cause,
)

/** The key can never sign again (no entry, permanently invalidated, unrecoverable) - code QA M3. */
class KeyGone(cause: Throwable?) : Exception("the device key is gone", cause)

/** The phone's key hardware, a seam for tests. */
interface KeyHardware {
    /** Makes [alias] attested for [challenge]; throws [KeyGenFailure]. */
    fun generate(alias: String, challenge: ByteArray)

    /** The alias's certificate chain, leaf first, as DER; `null` when the key is gone. */
    fun chain(alias: String): List<ByteArray>?

    /** DER ECDSA over [data] with SHA-256; throws [KeyGone] when the key can never sign again, another exception for a
     * failure that may pass. */
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
        // Code QA M1: a handle kept for an alias of an earlier key (a book reset) is never used for the new one.
        keys.remove(alias)
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
                // Design 129 L5: any StrongBox failure - an empty StrongBox pool included (code QA L1) - falls back to
                // the TEE, once.
                Log.w(LOG_TAG, "StrongBox couldn't make the key (${e.javaClass.simpleName}) - the TEE instead")
                runCatching { delete(alias) }
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

    /**
     * Code QA M3: one sign at a time (StrongBox serialises anyway), and a failure that may pass - a transient Keystore
     * error, a `ProviderException` - is tried twice more after a short pause; a key that is gone is [KeyGone] at once.
     */
    override fun sign(alias: String, data: ByteArray): ByteArray = synchronized(signLock) {
        var last: Exception? = null
        for (pause in SIGN_RETRY_PAUSES_MS) {
            if (pause > 0) Thread.sleep(pause)
            try {
                return@synchronized signOnce(alias, data)
            } catch (e: KeyGone) {
                throw e
            } catch (e: Exception) {
                keys.remove(alias)
                if (permanent(e)) throw KeyGone(e)
                last = e
                if (!transient(e)) break
            }
        }
        throw last ?: IllegalStateException("not signed")
    }

    private fun signOnce(alias: String, data: ByteArray): ByteArray {
        val key = keys[alias] ?: (keyStore().getKey(alias, null) as? PrivateKey)?.also { keys[alias] = it }
            ?: throw KeyGone(null)
        return Signature.getInstance("SHA256withECDSA").run {
            initSign(key)
            update(data)
            sign()
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
        private val signLock = Any()
        private val SIGN_RETRY_PAUSES_MS = listOf(0L, 50L, 250L)

        /** Design 129 M7: an empty attestation-key pool (`ERROR_ATTESTATION_KEYS_UNAVAILABLE`, API 33), anywhere in the
         * cause chain. */
        fun keysUnavailable(e: Throwable): Boolean =
            causes(e).any { it is android.security.KeyStoreException && it.numericErrorCode == android.security.KeyStoreException.ERROR_ATTESTATION_KEYS_UNAVAILABLE }

        private fun causes(e: Throwable): Sequence<Throwable> = generateSequence(e) { it.cause }.take(8)

        private fun permanent(e: Throwable): Boolean = causes(e).any {
            it is android.security.keystore.KeyPermanentlyInvalidatedException || it is java.security.UnrecoverableKeyException
        }

        private fun transient(e: Throwable): Boolean = causes(e).any {
            (it is android.security.KeyStoreException && it.isTransientFailure) || it is java.security.ProviderException
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
    /** §5.4: the server no longer knows the adopted key (code QA H1: [LOST_STRIKES] step-1 answers over [LOST_SPAN_MS])
     * and no bearer is left - "Ny oppsettkode"; step 1 is still asked now and then, and a known key clears it. */
    val lost: Boolean = false,
    /** The strikes towards [lost] so far. */
    val suspicion: LostSuspicion? = null,
    /** Code QA M2: the adopted key's alias left the Keystore. */
    val keyGone: Boolean = false,
    /** The last refusal's reason (a registration's 400 word, `keys_unavailable`, `keystore`), for the log and Settings. */
    val lastRefusal: String? = null,
    /** The SPKI hash of each key, read once from its chain. */
    val spki: Map<String, String> = emptyMap(),
    /** An enrolment key's challenge (Base64url): a retried enrolment with the same code reuses its key (§5.1). */
    val enrolChallenges: Map<String, String> = emptyMap(),
    /** Design 129b D4 (M4): enrolment challenge (Base64url) -> the attempt secret (16 bytes, Base64url) made once per
     * setup code, sent with every try for it - independent of any alias: a phone without a key has one too. */
    val enrolAttempts: Map<String, String> = emptyMap(),
    /** Design 129b D10: when an exempt phone last tried a key ([TrustedClock] ms) - a restart never tries sooner. */
    val exemptTriedMs: Long? = null,
    /** Code QA M1: the stored book didn't decode - nothing is written over it until a new enrolment resets it. */
    @kotlinx.serialization.Transient val corrupt: Boolean = false,
)

/**
 * The key book: one JSON in CE prefs `device_key`, written with `commit()` under one lock. Code QA M1: a book that
 * doesn't decode is never written over (only [reset], at a new enrolment), and the adopted key is mirrored in DE prefs
 * `device_key_adopted`, so a corrupt book still knows which key the phone signs with.
 */
object KeyBook {
    private const val PREFS = "device_key"
    private const val MIRROR = "device_key_adopted"
    private const val STATE = "state"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun mirror(context: Context): SharedPreferences? = try {
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(MIRROR, Context.MODE_PRIVATE)
    } catch (e: Exception) {
        null
    }

    private fun mirrored(context: Context): AdoptedKey? = try {
        mirror(context)?.getString(STATE, null)?.let { ServerJson.decodeFromString(AdoptedKey.serializer(), it) }
    } catch (e: Exception) {
        null
    }

    @Synchronized
    fun read(context: Context): KeyBookState {
        val raw = prefs(context).getString(STATE, null) ?: return KeyBookState()
        return try {
            ServerJson.decodeFromString(KeyBookState.serializer(), raw)
        } catch (e: Exception) {
            Log.w("DeviceKey", "The key book didn't read - kept as it is: ${e.javaClass.simpleName}")
            KeyBookState(corrupt = true, adopted = mirrored(context))
        }
    }

    @Synchronized
    fun update(context: Context, change: (KeyBookState) -> KeyBookState): KeyBookState {
        val before = read(context)
        if (before.corrupt) return before
        return write(context, change(before))
    }

    /** A new enrolment's empty book (with [next] past every alias the Keystore holds). */
    @Synchronized
    fun reset(context: Context, next: Int): KeyBookState = write(context, KeyBookState(next = next))

    private fun write(context: Context, state: KeyBookState): KeyBookState {
        prefs(context).edit().putString(STATE, ServerJson.encodeToString(KeyBookState.serializer(), state)).commit()
        if (state.adopted != mirrored(context)) {
            mirror(context)?.edit()?.apply {
                if (state.adopted == null) remove(STATE) else putString(STATE, ServerJson.encodeToString(AdoptedKey.serializer(), state.adopted))
            }?.commit()
        }
        return state
    }

    /** A key is adopted (no Keystore call). */
    fun adoptedStored(context: Context): Boolean = read(context).adopted != null
}

/** Design 129b D4: the book with an attempt for [challenge] - the one already kept wins (one attempt per challenge). */
fun withEnrolAttempt(book: KeyBookState, challenge: String, made: String): KeyBookState =
    if (book.enrolAttempts.containsKey(challenge)) book else book.copy(enrolAttempts = book.enrolAttempts + (challenge to made))

/** A new enrolment's book (§5.1, design 129b D4): the old enrolment's marks, attempts and exempt try go. */
fun newEnrolmentBook(book: KeyBookState): KeyBookState = book.copy(
    adopted = null, conflictReported = null, lost = false, suspicion = null, keyGone = false, lastRefusal = null,
    enrolAttempts = emptyMap(), exemptTriedMs = null,
)

/** Design 129b D2 (M3): why an enrolment has no key - `make` gave none ([failure] `null`): the book doesn't read
 * (`keystore`) or the cap left no room (`cap`); an empty attestation-key pool is `keys_unavailable`; anything else
 * `keystore`. */
fun noKeyReason(bookCorrupt: Boolean, failure: Throwable?): String = when {
    failure == null -> if (bookCorrupt) "keystore" else "cap"
    failure is KeyGenFailure && failure.keysUnavailable -> "keys_unavailable"
    else -> "keystore"
}

/** One made key with what goes to the server. */
class MadeKey(val alias: String, val chain: List<ByteArray>, val spki: String) {
    /** The chain as the server reads it: standard Base64 DER, leaf first. */
    fun chainB64(): List<String> = chain.map { Base64.getEncoder().encodeToString(it) }
}

/** An alias's number (`device_key_<n>`), or `null`. */
fun aliasNumber(alias: String): Int? = alias.removePrefix(DEVICE_KEY_ALIAS_PREFIX).takeIf { alias.startsWith(DEVICE_KEY_ALIAS_PREFIX) }?.toIntOrNull()

/**
 * The book's keys against the Keystore's [present] aliases (pure): keys gone from the Keystore leave (an adopted one
 * marks [KeyBookState.keyGone], code QA M2); a Keystore alias the book lost is a stray - never named, long expired - unless
 * its number is at or past the book's `next` (a key made whose book write didn't land, code QA L8: kept as fresh);
 * duplicates go; `next` moves past every alias.
 */
fun reconcileBook(state: KeyBookState, present: Set<String>, nowMs: Long, freshForMs: Long): KeyBookState {
    val known = state.keys.filter { it.alias in present }.distinctBy { it.alias }
    val knownAliases = known.map { it.alias }.toSet()
    val strays = (present - knownAliases).sorted().map { alias ->
        val n = aliasNumber(alias)
        if (n != null && n >= state.next) KeyAlias(alias, nowMs, nowMs + freshForMs) else KeyAlias(alias, 0L, 0L)
    }
    val maxN = present.mapNotNull(::aliasNumber).maxOrNull() ?: 0
    val adoptedGone = state.adopted != null && state.adopted.alias !in present
    return state.copy(
        next = maxOf(state.next, maxN + 1),
        keys = known + strays,
        spki = state.spki.filterKeys { it in present },
        enrolChallenges = state.enrolChallenges.filterKeys { it in present },
        adopted = state.adopted?.takeIf { !adoptedGone },
        keyGone = state.keyGone || adoptedGone,
    )
}

/**
 * Making, proving and dropping keys - the one place the book and the hardware change together. Only the registration
 * flow (`KeyRegistration`) and the enrolment (`Enrollment`) call it.
 */
object DeviceKeys {
    private const val LOG_TAG = "DeviceKey"

    /** An enrolment key's challenge holds for the code's life; the phone doesn't know it, so a day. */
    const val ENROL_CHALLENGE_HOLDS_MS = 24 * 60 * 60_000L

    /** A stray alias newer than the book: kept this long before the cap may take it. */
    private const val STRAY_FRESH_MS = 10 * 60_000L

    @Volatile
    var hardwareFor: (Context) -> KeyHardware = { AndroidKeyHardware(it.applicationContext) }

    fun hardware(context: Context): KeyHardware = hardwareFor(context)

    /**
     * A new key for [challenge] (§2): the cap first ([keyCapPlan]: `null` - no key may go, nothing is made), then an
     * alias no Keystore entry has (code QA M1), its chain and SPKI hash. Throws [KeyGenFailure]. `null` too while the book
     * is corrupt (nothing is written over it).
     */
    fun make(context: Context, challenge: ByteArray, challengeUntilMs: Long, nowMs: Long, enrolChallenge: String? = null): MadeKey? {
        val hw = hardware(context)
        val book = reconcile(context, hw, nowMs)
        if (book.corrupt) return null
        val drop = keyCapPlan(book.keys, nowMs) ?: run {
            Log.w(LOG_TAG, "Three keys the server may still name - no new one yet")
            return null
        }
        for (alias in drop) forget(context, hw, alias)
        val taken = runCatching { hw.aliases().toSet() }.getOrElse { throw KeyGenFailure(false, it) }
        var n = book.next
        while (DEVICE_KEY_ALIAS_PREFIX + n in taken) n++
        val alias = DEVICE_KEY_ALIAS_PREFIX + n
        KeyBook.update(context) { it.copy(next = n + 1) }
        hw.generate(alias, challenge)
        val chain = hw.chain(alias)
        val spki = chain?.let(::leafSpkiSha256)
        if (chain == null || spki == null) {
            runCatching { hw.delete(alias) }
            throw KeyGenFailure(false, null)
        }
        KeyBook.update(context) { state ->
            state.copy(
                keys = state.keys.filter { it.alias != alias } + KeyAlias(alias, nowMs, challengeUntilMs),
                spki = state.spki + (alias to spki),
                enrolChallenges = if (enrolChallenge != null) state.enrolChallenges + (alias to enrolChallenge) else state.enrolChallenges,
            )
        }
        return MadeKey(alias, chain, spki)
    }

    /** §5.1: a key made earlier for the same enrolment challenge (a retried enrolment), still in the Keystore. */
    fun enrolKeyFor(context: Context, challenge: String, nowMs: Long): MadeKey? {
        val hw = hardware(context)
        val book = reconcile(context, hw, nowMs)
        val alias = book.enrolChallenges.entries.firstOrNull { it.value == challenge }?.key ?: return null
        val chain = hw.chain(alias) ?: return null
        val spki = book.spki[alias] ?: leafSpkiSha256(chain) ?: return null
        return MadeKey(alias, chain, spki)
    }

    /** The proof over [challenge] (§5.1, §5.2) - signed afresh on every attempt. */
    fun proof(context: Context, alias: String, challenge: ByteArray, kind: ProofKind): String =
        b64u(hardware(context).sign(alias, proofMessage(challenge, kind).toByteArray(Charsets.UTF_8)))

    /** [reconcileBook] against the Keystore now. */
    fun reconcile(context: Context, hw: KeyHardware = hardware(context), nowMs: Long = TrustedClock.nowMs()): KeyBookState {
        val present = try {
            hw.aliases().toSet()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The Keystore's aliases didn't read: ${e.javaClass.simpleName}")
            return KeyBook.read(context)
        }
        return KeyBook.update(context) { reconcileBook(it, present, nowMs, STRAY_FRESH_MS) }
    }

    /** Whether the Keystore holds any device key (a Keystore call; code QA M1's "keys but nothing adopted"). */
    fun anyKeyStored(context: Context): Boolean = try {
        hardware(context).aliases().isNotEmpty()
    } catch (e: Exception) {
        false
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
     * clears a reported conflict and the lost marks.
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
                suspicion = null,
                keyGone = false,
                lastRefusal = null,
            )
        }
        for (other in others) forget(context, hw, other)
    }

    /** Drops [alias] from the Keystore and the book. Only an adoption, the cap, a refused or unused key, and a new
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

    /** Code QA L5: before a new enrolment's token is stored, the old enrolment's key stops being the credential. A
     * corrupt book is reset here (an enrolment is the one way out of it). */
    fun dropAdopted(context: Context) {
        val book = KeyBook.read(context)
        if (book.corrupt) {
            KeyBook.reset(context, nextPast(context))
        } else {
            KeyBook.update(context) { it.copy(adopted = null) }
        }
    }

    /** A new enrolment (§5.1): the old enrolment's keys and marks go, but [keep] (this enrolment's key). */
    fun newEnrolment(context: Context, keep: String?) {
        val hw = hardware(context)
        if (KeyBook.read(context).corrupt) KeyBook.reset(context, nextPast(context))
        for (key in reconcile(context, hw).keys) if (key.alias != keep) forget(context, hw, key.alias)
        KeyBook.update(context, ::newEnrolmentBook)
        synchronized(fallbackAttempts) { fallbackAttempts.clear() }
    }

    /** Code QA L4: the enrolment key made for [challenge] (Base64url) goes - its code was abandoned. */
    fun forgetEnrolKey(context: Context, challenge: String) {
        val alias = KeyBook.read(context).enrolChallenges.entries.firstOrNull { it.value == challenge }?.key ?: return
        forget(context, alias = alias)
    }

    /** Attempts of a book that doesn't decode (nothing is written over it): this process's only. */
    private val fallbackAttempts = HashMap<String, String>()

    /**
     * Design 129b D4: the attempt for the enrolment [challenge] (Base64url) - made once (16 random bytes) and kept in the
     * book, so every try with the code, a process death included, sends the same one and the phone shows the same number.
     */
    fun enrolAttempt(context: Context, challenge: String): String {
        KeyBook.read(context).enrolAttempts[challenge]?.let { return it }
        val made = b64u(ByteArray(16).also { java.security.SecureRandom().nextBytes(it) })
        val after = KeyBook.update(context) { withEnrolAttempt(it, challenge, made) }
        if (after.corrupt) return synchronized(fallbackAttempts) { fallbackAttempts.getOrPut(challenge) { made } }
        return after.enrolAttempts[challenge] ?: made
    }

    /** Design 129b D10: an exempt phone's try of a key now ([TrustedClock] ms), kept for the week's pace. */
    fun exemptTried(context: Context, nowMs: Long) {
        KeyBook.update(context) { it.copy(exemptTriedMs = nowMs) }
    }

    private fun nextPast(context: Context): Int =
        (runCatching { hardware(context).aliases() }.getOrDefault(emptyList()).mapNotNull(::aliasNumber).maxOrNull() ?: 0) + 1

    fun refused(context: Context, reason: String) {
        KeyBook.update(context) { it.copy(lastRefusal = reason) }
    }
}
