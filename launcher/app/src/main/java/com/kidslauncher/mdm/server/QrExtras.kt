package com.kidslauncher.mdm.server

import android.content.Context
import android.content.SharedPreferences
import android.os.PersistableBundle
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.security.MessageDigest

private const val LOG_TAG = "Provisioning"

/**
 * The setup QR's admin extras applied once (2026-11-20, a real Jelly Star on Android 16): on a phone with Google Play
 * services Android runs QR provisioning through the DEVICE_POLICY_MANAGEMENT role holder (Google's CloudDPC), and the
 * extras then reach only our `ADMIN_POLICY_COMPLIANCE` activity - `onProfileProvisioningComplete` never runs. Both
 * places hand the bundle to [deliver]; the one apply is [applyProvisioningExtras] (S-M1's address rule, the tailnet
 * key, the enrolment), under [mutex] and the applied marker, so a second delivery is a no-op and a single-use code is
 * never sent twice for one setup.
 *
 * Process death and a failed enrolment: the extras are kept sealed ([QrExtrasStore]) before the apply starts and
 * dropped only when it is done or refused; [retryPending] runs at the start of every sync run (the anchor's start at
 * every process start, the 30-minute backstop, every nudge), so a phone whose first try had no network or tailnet
 * enrols at the next one. The store is its own prefs file: the first-launch reset (which clears the default prefs,
 * the server address included, on every process start before Home's first while not enrolled) doesn't touch it.
 */
object QrExtras {
    /** The decision and the apply, one at a time in this process (both delivery paths and the retry). */
    private val mutex = Mutex()

    /** The process's own scope: the apply outlives the compliance activity, which finishes at once. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun store(context: Context) = QrExtrasStore(context.applicationContext.getSharedPreferences(QrExtrasStore.FILE, Context.MODE_PRIVATE))

    /**
     * A delivery of the admin extras bundle from [source] ("Admin policy compliance", "Provisioning complete"):
     * returns at once - never blocks the caller's thread - with the apply's job, or `null` when the bundle holds no
     * usable extras. Never logs the code or the key.
     */
    fun deliver(context: Context, bundle: PersistableBundle?, source: String): Job? {
        val extras = ProvisioningExtras.fromAdminExtrasBundle(bundle)
        if (extras == null) {
            Log.i(LOG_TAG, "$source: no admin extras (bundle ${if (bundle == null) "absent" else "without a server address or code"}) - nothing to apply")
            return null
        }
        Log.i(LOG_TAG, "$source: admin extras found")
        val app = context.applicationContext
        return scope.launch {
            try {
                apply(app, extras, source)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$source: applying the admin extras failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** At the start of a sync run: extras kept by an apply that failed for a transient reason are applied again. */
    suspend fun retryPending(context: Context) {
        val store = store(context)
        if (!store.hasPending()) return
        val extras = store.pending()
        if (extras == null) {
            Log.w(LOG_TAG, "Kept admin extras don't open - dropped")
            store.dropPending()
            return
        }
        apply(context.applicationContext, extras, "Retry")
    }

    /**
     * "Koble til" (`setup/ConnectActivity`): the address and code the parent typed, through the same one apply and
     * under the same [mutex] - a kept QR retry never runs beside it - with the kept extras' tailnet key, if any. Once it
     * enrols, the kept extras go (the retry would skip them anyway: enrolled). Never logs the code.
     */
    suspend fun enrolTyped(context: Context, serverUrl: String, code: String): Result<Unit> = mutex.withLock {
        val app = context.applicationContext
        val store = store(app)
        val key = store.pending()?.tailscaleAuthKey.orEmpty()
        Log.i(LOG_TAG, "Koble til: enrolling with a typed code")
        val result = applyProvisioningExtras(app, ProvisioningExtras(serverUrl.trim(), key, code.trim()))
        if (result.isSuccess) {
            store.dropPending()
            Log.i(LOG_TAG, "Koble til: enrolled")
        } else {
            Log.w(LOG_TAG, "Koble til: enrolment failed (${result.exceptionOrNull()?.javaClass?.simpleName})")
        }
        result
    }

    private suspend fun apply(context: Context, extras: ProvisioningExtras, source: String) = mutex.withLock {
        val store = store(context)
        val mdm = LauncherPreferences.mdm()
        val enrolled = mdm.enrolled() || DeviceToken.present()
        when (qrExtrasAction(extras, store.appliedHash(), enrolled, com.kidslauncher.mdm.BuildConfig.DEBUG)) {
            QrExtrasAction.NONE -> Unit
            QrExtrasAction.SKIP_APPLIED -> {
                Log.i(LOG_TAG, "$source: admin extras skipped (already applied)")
                store.dropPending()
            }
            QrExtrasAction.SKIP_ENROLLED -> {
                Log.i(LOG_TAG, "$source: admin extras skipped (already enrolled)")
                store.dropPending()
            }
            QrExtrasAction.REFUSE -> {
                Log.w(LOG_TAG, "$source: admin extras refused - the server address isn't https")
                store.markApplied(qrCodeHash(extras.enrollmentCode), refused = true)
            }
            QrExtrasAction.APPLY -> {
                if (!store.setPending(extras)) Log.w(LOG_TAG, "$source: couldn't keep the admin extras for a retry")
                Log.i(LOG_TAG, "$source: applying admin extras")
                val result = applyProvisioningExtras(context, extras)
                when (qrExtrasAfter(result)) {
                    QrExtrasAfter.DONE -> {
                        store.markApplied(qrCodeHash(extras.enrollmentCode), refused = false)
                        Log.i(LOG_TAG, "$source: admin extras applied - enrolled")
                    }
                    QrExtrasAfter.REFUSED -> {
                        store.markApplied(qrCodeHash(extras.enrollmentCode), refused = true)
                        Log.w(LOG_TAG, "$source: admin extras applied, enrolment refused (${result.exceptionOrNull()?.message})")
                    }
                    QrExtrasAfter.RETRY -> Log.w(
                        LOG_TAG,
                        "$source: enrolment failed (${result.exceptionOrNull()?.javaClass?.simpleName}) - tried again at the next sync",
                    )
                }
            }
        }
    }
}

/** What one delivery (or retry) of the QR's admin extras does; [qrExtrasAction]. */
enum class QrExtrasAction { NONE, SKIP_APPLIED, SKIP_ENROLLED, REFUSE, APPLY }

/**
 * Pure: no extras - nothing; this code applied (or refused) before - skip; the phone already enrolled (a QR setup is
 * on a wiped phone, so its delivery after an enrolment is this setup's again) - skip; an address S-M1 refuses - refuse
 * (nothing stored, as [applyProvisioningExtras]); else apply.
 */
fun qrExtrasAction(extras: ProvisioningExtras?, appliedHash: String?, enrolled: Boolean, debug: Boolean): QrExtrasAction = when {
    extras == null -> QrExtrasAction.NONE
    appliedHash != null && appliedHash == qrCodeHash(extras.enrollmentCode) -> QrExtrasAction.SKIP_APPLIED
    enrolled -> QrExtrasAction.SKIP_ENROLLED
    !serverUrlUsable(extras.serverUrl, debug) -> QrExtrasAction.REFUSE
    else -> QrExtrasAction.APPLY
}

/** After an apply: done, refused for good (the address, or a 4xx for the code), or kept for a retry. */
enum class QrExtrasAfter { DONE, REFUSED, RETRY }

fun qrExtrasAfter(result: Result<Unit>): QrExtrasAfter = when {
    result.isSuccess -> QrExtrasAfter.DONE
    (result.exceptionOrNull() as? EnrollFailure)?.refused == true -> QrExtrasAfter.REFUSED
    else -> QrExtrasAfter.RETRY
}

/** The applied marker's key: SHA-256 of the code, hex - the code itself is never kept in the clear. */
fun qrCodeHash(code: String): String =
    MessageDigest.getInstance("SHA-256").digest(code.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
private class KeptExtras(val serverUrl: String, val tailscaleAuthKey: String, val enrollmentCode: String)

/**
 * [QrExtras]' state, prefs file `qr_extras` (credential-encrypted, every write a `commit()`): the applied marker
 * ([qrCodeHash] and whether it was refused) and the pending extras, sealed (design r10: the code and the tailnet key
 * are secrets).
 */
class QrExtrasStore(
    private val prefs: SharedPreferences,
    private val sealed: SealedPrefs = Secrets.prefs,
) {
    companion object {
        const val FILE = "qr_extras"
        private const val APPLIED = "applied_code_sha256"
        private const val APPLIED_REFUSED = "applied_refused"
        private const val PENDING = "pending"
        private const val PENDING_AAD = "qr_extras/pending"
    }

    fun appliedHash(): String? = prefs.getString(APPLIED, null)

    fun appliedRefused(): Boolean = prefs.getBoolean(APPLIED_REFUSED, false)

    /** The marker written and the pending extras dropped in one commit. */
    fun markApplied(hash: String, refused: Boolean): Boolean = sealed.edit(prefs) {
        it.raw.putString(APPLIED, hash).putBoolean(APPLIED_REFUSED, refused)
        it.remove(PENDING_AAD, PENDING)
    }

    /** No Keystore call: something is kept. */
    fun hasPending(): Boolean = prefs.contains(PENDING)

    /** The kept extras, or `null` - none, or kept but not readable. */
    fun pending(): ProvisioningExtras? {
        val plain = (sealed.read(prefs, PENDING_AAD, PENDING) as? Sealed.Value)?.plain ?: return null
        return try {
            val kept = ServerJson.decodeFromString(KeptExtras.serializer(), plain)
            ProvisioningExtras(kept.serverUrl, kept.tailscaleAuthKey, kept.enrollmentCode)
        } catch (e: Exception) {
            null
        }
    }

    fun setPending(extras: ProvisioningExtras): Boolean {
        val plain = ServerJson.encodeToString(KeptExtras.serializer(), KeptExtras(extras.serverUrl, extras.tailscaleAuthKey, extras.enrollmentCode))
        return sealed.edit(prefs) { it.putSealed(PENDING_AAD, PENDING, plain) }
    }

    fun dropPending() {
        sealed.edit(prefs) { it.remove(PENDING_AAD, PENDING) }
    }
}
