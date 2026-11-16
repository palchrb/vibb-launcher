package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.Serializable
import okhttp3.ResponseBody
import retrofit2.Response
import java.security.MessageDigest

/**
 * The policy's ETag and 304 (design 95 §3.5, step 95-T1). The server tags `GET /api/devices/policy` with a weak ETag
 * over the exact bytes it served; the phone keeps the **raw 200 body** with it and sends `If-None-Match` while the
 * kept body is still the one for this launcher and this server. A `304` is then that body arriving again: the sync
 * decodes, judges and applies it exactly as a 200 ([performMdmSync]).
 *
 * The kept body is not the policy cache (`kidModePolicy` is re-encoded by the DTOs, unknown keys dropped, and the PIN
 * hashes redacted): it holds the parent PIN's and the kid lock's hashes, so it is sealed ([SealedPrefs], AAD
 * [AAD]) in credential-encrypted prefs of its own. It is kept only for an accepted policy without a pending command
 * ([keptBodyStep]: a command's body is one-shot, and `set_kid_pin`'s carries the Child PIN, which goes no further than
 * its command - design 59 §1), and only while its versionCode (a newer launcher must see keys an older DTO dropped),
 * server address and device token (a re-enrollment gives a new one) are the phone's now ([KeptPolicy.matches]).
 * Anything else - a mismatch, a blob that doesn't open or decode - drops it, and the next GET goes without the header.
 */
class PolicyEtagStore(
    private val prefs: SharedPreferences,
    private val sealed: SealedPrefs = Secrets.prefs,
    private val migrate: () -> Boolean = Secrets::migrateHere,
) {
    companion object {
        const val PREFS_NAME = "policy_etag"
        private const val KEY = "kept"
        const val AAD = "$PREFS_NAME/$KEY"

        fun of(context: Context): PolicyEtagStore =
            PolicyEtagStore(context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
    }

    /** The kept body for [now], or `null` (and dropped) when there is none for it. Never throws. */
    fun usable(now: PolicyOwner): KeptPolicy? {
        val kept = when (val state = sealed.read(prefs, AAD, KEY, migrate())) {
            Sealed.Absent -> return null
            Sealed.Unreadable -> null
            is Sealed.Value -> decodeKept(state.plain)
        }
        if (kept != null && kept.matches(now)) return kept
        drop()
        return null
    }

    /** Keeps [body] (an accepted 200's, [keepable]) under [etag] for [now]; false when it couldn't be written. */
    fun keep(now: PolicyOwner, etag: String, body: String): Boolean {
        val kept = KeptPolicy(etag, now.versionCode, now.serverUrl, now.tokenHash, body)
        return sealed.edit(prefs) { it.putSealed(AAD, KEY, ServerJson.encodeToString(KeptPolicy.serializer(), kept)) }
    }

    fun drop() {
        if (prefs.contains(KEY)) sealed.edit(prefs) { it.remove(AAD, KEY) }
    }
}

/** What a kept body belongs to: this build, this server address, this device token (its SHA-256, never the token). */
data class PolicyOwner(val versionCode: Int, val serverUrl: String, val tokenHash: String) {
    companion object {
        fun of(versionCode: Int, serverUrl: String, token: String) = PolicyOwner(versionCode, serverUrl, sha256Hex(token))
    }
}

/** The kept 200: [etag] as the server sent it, [body] byte for byte (as text). */
@Serializable
data class KeptPolicy(
    val etag: String,
    val versionCode: Int,
    val serverUrl: String,
    val tokenHash: String,
    val body: String,
) {
    fun matches(now: PolicyOwner): Boolean =
        versionCode == now.versionCode && serverUrl == now.serverUrl && tokenHash == now.tokenHash

    override fun toString() = "KeptPolicy(etag=$etag, versionCode=$versionCode, <redacted>)"
}

/** The body a policy GET brought: a 200's with its `ETag`, or the kept one a `304` named ([notModified]). */
class ServedPolicy(val body: String, val etag: String?, val notModified: Boolean)

/**
 * One policy fetch with the kept body (design 95 §3.5): [get] is `MdmApi.getPolicy`, sent with the kept ETag for
 * [owner] (none: no header). A `304` is the kept body, decoded as a 200's would be; a 304 with nothing kept, or a kept
 * body that no longer decodes, drops it and asks once more without the header. Otherwise as before: a non-2xx is
 * `null`, or [serverError] (the same instance) on a 5xx; a 2xx is its decoded body. [onServed] gets the body that was
 * decoded, [onResponse] each raw response (design 76 reads its handshake, a 304's as a 200's). Throws what [get]
 * throws.
 */
suspend fun fetchRevalidated(
    get: suspend (ifNoneMatch: String?) -> Response<ResponseBody>,
    store: PolicyEtagStore,
    owner: PolicyOwner,
    serverError: FreshDecode.Failed,
    log: (String) -> Unit,
    onServed: (ServedPolicy) -> Unit,
    onResponse: (okhttp3.Response) -> Unit = {},
): FreshDecode? {
    var kept = store.usable(owner)
    // At most twice: the second time only after a 304 the kept body couldn't answer, without `If-None-Match`.
    repeat(2) {
        val response = get(kept?.etag)
        onResponse(response.raw())
        if (response.code() == 304) {
            response.errorBody()?.close()
            val body = kept?.body
            val decoded = body?.let(::decodeFresh)
            if (body != null && decoded is FreshDecode.Ok) {
                onServed(ServedPolicy(body, kept?.etag, notModified = true))
                return decoded
            }
            store.drop()
            if (kept == null) {
                // Code QA L4: our server never sends this; a middlebox might. No policy this time, nothing changed.
                log("Policy 304 to a request without If-None-Match - not from our server, keeping the current policy")
                return null
            }
            log("Policy 304 without a usable kept body - asking again without it")
            kept = null
            return@repeat
        }
        if (!response.isSuccessful) {
            log("Policy fetch returned HTTP ${response.code()}, keeping the current policy")
            response.errorBody()?.close()
            return if (response.code() >= 500) serverError else null
        }
        val body = response.body()?.use { it.string() }
        body?.let { onServed(ServedPolicy(it, response.headers()["ETag"], notModified = false)) }
        return decodeFresh(body)
    }
    return null
}

/** What the sync does with the kept body after accepting a policy. */
sealed interface KeptBodyStep {
    data class Keep(val etag: String, val body: String) : KeptBodyStep {
        override fun toString() = "Keep(etag=$etag, <redacted>)"
    }

    /** A 200 that may not be kept: whatever was kept goes, and the next GET has no `If-None-Match`. */
    data object Drop : KeptBodyStep

    /** A 304: the kept body is the one just applied. */
    data object Leave : KeptBodyStep
}

/**
 * The kept body after [accepted] - the policy as accepted, **before** `cacheablePolicy` - arrived as [served]
 * (code QA L1): a 200 is kept only with an ETag (an older server sends none) and without a `pending_command` (one-shot,
 * and `set_kid_pin`'s carries the Child PIN, which goes no further than its command - design 59 §1); a 304 leaves it.
 */
fun keptBodyStep(served: ServedPolicy?, accepted: PolicyResponse): KeptBodyStep {
    if (served == null || served.notModified) return KeptBodyStep.Leave
    val etag = served.etag
    return if (!etag.isNullOrBlank() && accepted.pendingCommand == null) KeptBodyStep.Keep(etag, served.body) else KeptBodyStep.Drop
}

private fun decodeKept(plain: String): KeptPolicy? = try {
    ServerJson.decodeFromString(KeptPolicy.serializer(), plain)
} catch (e: Exception) {
    null
}

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
