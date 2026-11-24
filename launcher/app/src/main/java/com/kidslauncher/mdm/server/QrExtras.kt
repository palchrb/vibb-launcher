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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import java.security.MessageDigest

private const val LOG_TAG = "Provisioning"

/** How long the provisioning receiver holds its broadcast for the apply (QA L4): the extras are kept for a retry. */
const val QR_RECEIVER_HOLD_MS = 8_000L

/**
 * The setup QR's admin extras applied once (2026-11-20, a real Jelly Star on Android 16): on a phone with Google Play
 * services Android runs QR provisioning through the DEVICE_POLICY_MANAGEMENT role holder (Google's CloudDPC), and the
 * extras then reach only our `ADMIN_POLICY_COMPLIANCE` activity - `onProfileProvisioningComplete` never runs. Both
 * places hand the bundle to [deliver]; the one apply is [applyProvisioningExtras] (S-M1's address rule, the tailnet
 * key, the enrolment), under [mutex] and the applied marker, so a second delivery is a no-op and a single-use code is
 * never sent twice for one setup. "Koble til" ([enrolTyped]) and Settings' QR scanner ([applyScanned]) enrol under the
 * same mutex.
 *
 * Process death and a failed enrolment: [deliver] seals the extras ([QrExtrasStore]) before it returns (one Keystore
 * operation on the caller's thread, so a process death right after the compliance activity's result loses nothing);
 * they are dropped only when the apply is done or refused for good. [retryPending] runs at the start of every sync run
 * (the anchor's start at every process start, the 30-minute backstop, every nudge), so a phone whose first try had no
 * network or tailnet enrols at the next one. The store is its own prefs file: the first-launch reset (which clears the
 * default prefs, the server address included, on every process start before Home's first while not enrolled) doesn't
 * touch it. Once the mutex is held, the rest runs [NonCancellable]: a POST the server took has used the code (QA M3).
 */
object QrExtras {
    /** The decision and the apply, one at a time in this process (both delivery paths, the retry and the typed code). */
    private val mutex = Mutex()

    /** The process's own scope: the apply outlives the compliance activity, which finishes at once. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val runningCount = MutableStateFlow(0)

    /** Applies and enrolments in flight or waiting for [mutex], in this process (QA M2, L6): "Koble til" shows busy. */
    val running: StateFlow<Int> = runningCount

    private suspend fun <T> counted(block: suspend () -> T): T {
        runningCount.update { it + 1 }
        try {
            return block()
        } finally {
            runningCount.update { it - 1 }
        }
    }

    /** Takes the mutex, then never cancelled (QA M3). */
    private suspend fun <T> locked(block: suspend () -> T): T = counted { mutex.withLock { withContext(NonCancellable) { block() } } }

    /** Design 129b D11: a waiting enrolment's try ([EnrolWait]) under the same mutex, never cancelled once sent. */
    suspend fun <T> underLock(block: suspend () -> T): T = locked(block)

    private fun store(context: Context) = QrExtrasStore(context.applicationContext.getSharedPreferences(QrExtrasStore.FILE, Context.MODE_PRIVATE))

    private fun enrolledNow(): Boolean = LauncherPreferences.mdm().enrolled() || DeviceCredentials.tokenStored()

    /**
     * A delivery of the admin extras bundle from [source] ("Admin policy compliance", "Provisioning complete"): seals
     * the extras when they are due (QA L1; one Keystore operation), then returns with the apply's job, or `null` when
     * the bundle holds no usable extras. Never logs the code or the key.
     */
    fun deliver(context: Context, bundle: PersistableBundle?, source: String): Job? {
        val extras = ProvisioningExtras.fromAdminExtrasBundle(bundle)
        if (extras == null) {
            Log.i(LOG_TAG, "$source: no admin extras (bundle ${if (bundle == null) "absent" else "without a server address or code"}) - nothing to apply")
            return null
        }
        Log.i(LOG_TAG, "$source: admin extras found")
        val app = context.applicationContext
        try {
            val store = store(app)
            if (qrExtrasAction(extras, store.appliedHash(), enrolledNow(), com.kidslauncher.mdm.BuildConfig.DEBUG) == QrExtrasAction.APPLY &&
                !store.setPending(extras)
            ) {
                Log.w(LOG_TAG, "$source: couldn't keep the admin extras - a failed enrolment now isn't tried again")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "$source: keeping the admin extras failed: ${e.javaClass.simpleName}")
        }
        return scope.launch {
            try {
                apply(app, extras, source)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$source: applying the admin extras failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The provisioning receiver's `goAsync`: [finish] once [job] ends or after [QR_RECEIVER_HOLD_MS] (QA L4). */
    fun finishWithin(job: Job, finish: () -> Unit) {
        scope.launch {
            try {
                withTimeoutOrNull(QR_RECEIVER_HOLD_MS) { job.join() }
            } finally {
                finish()
            }
        }
    }

    /** At the start of a sync run: extras kept by an apply that failed for a transient reason are applied again. */
    suspend fun retryPending(context: Context) {
        val store = store(context)
        if (!store.hasPending()) return
        // Design 129b D11: a waiting enrolment tries the kept code at its own pace.
        if (EnrolWait.running()) return
        when (val kept = store.pendingRead()) {
            is KeptRead.Value -> apply(context.applicationContext, kept.extras, "Retry")
            KeptRead.Absent -> Unit
            KeptRead.Corrupt -> {
                Log.w(LOG_TAG, "Kept admin extras don't parse - dropped")
                store.dropPending()
            }
            // Design r10: a transient Keystore error never costs state - dropped only when the key itself is broken.
            KeptRead.Unreadable -> if (store.keyBroken()) {
                Log.w(LOG_TAG, "Kept admin extras don't open and the key is broken - dropped")
                store.dropPending()
            } else {
                Log.w(LOG_TAG, "Kept admin extras don't open now - kept for the next sync")
            }
        }
    }

    /**
     * "Koble til" (`setup/ConnectActivity`): the address and code the parent typed, through the same one apply, under
     * the same mutex - with the kept extras' tailnet key, if any. An enrolled phone sends nothing (QA M2: a second tap,
     * a second screen, a QR retry that got there first) and answers success. Enrolled, the code is marked and the
     * kept extras go. Never logs the code.
     */
    suspend fun enrolTyped(context: Context, serverUrl: String, code: String): Result<Unit> = locked {
        val app = context.applicationContext
        val store = store(app)
        if (enrolledNow()) {
            Log.i(LOG_TAG, "Koble til: skipped (already enrolled)")
            store.dropPending()
            return@locked Result.success(Unit)
        }
        val key = (store.pendingRead() as? KeptRead.Value)?.extras?.tailscaleAuthKey.orEmpty()
        Log.i(LOG_TAG, "Koble til: enrolling with a typed code")
        val extras = ProvisioningExtras(serverUrl.trim(), key, code.trim())
        val result = applyProvisioningExtras(app, extras)
        if (result.isSuccess) {
            store.markApplied(qrCodeHash(code), refused = false)
            Log.i(LOG_TAG, "Koble til: enrolled")
        } else if (result.exceptionOrNull() is EnrollWaiting) {
            // Design 129b D11: a typed code lives only in the process; the screen shows the number while it waits.
            Log.i(LOG_TAG, "Koble til: waiting for a parent")
            waitFor(app, extras, result.exceptionOrNull() as EnrollWaiting, keepPending = false)
        } else {
            Log.w(LOG_TAG, "Koble til: enrolment failed (${result.exceptionOrNull()?.javaClass?.simpleName})")
        }
        result
    }

    /**
     * Settings' in-app QR scanner (QA L3): the same apply under the same mutex. A deliberate re-scan of a *new* code
     * re-enrols an enrolled phone, as before; the code that enrolled this phone isn't sent again ([scannedCodeSends]) -
     * the server would refuse a used code, and the apply would reset the certificate check for nothing.
     */
    suspend fun applyScanned(context: Context, extras: ProvisioningExtras): Result<Unit> = locked {
        val app = context.applicationContext
        val store = store(app)
        if (!scannedCodeSends(enrolledNow(), store.appliedHash(), extras.enrollmentCode)) {
            Log.i(LOG_TAG, "Scanned QR: skipped (this code enrolled the phone)")
            return@locked Result.success(Unit)
        }
        Log.i(LOG_TAG, "Scanned QR: applying")
        val result = applyProvisioningExtras(app, extras)
        if (result.isSuccess) store.markApplied(qrCodeHash(extras.enrollmentCode), refused = false)
        (result.exceptionOrNull() as? EnrollWaiting)?.let { waitFor(app, extras, it, keepPending = false) }
        result
    }

    /** Design 129b D11: the waiting enrolment for [extras]' code; enrolled, the code is marked (and the kept extras go);
     * refused or run out, the kept extras go too. */
    private fun waitFor(context: Context, extras: ProvisioningExtras, first: EnrollWaiting, keepPending: Boolean) {
        val app = context.applicationContext
        EnrolWait.start(
            app,
            first,
            tryOnce = { applyProvisioningExtras(app, extras) },
            onEnd = { result ->
                val store = store(app)
                if (result.isSuccess) {
                    store.markApplied(qrCodeHash(extras.enrollmentCode), refused = false)
                } else if (keepPending) {
                    store.markApplied(qrCodeHash(extras.enrollmentCode), refused = true)
                }
            },
        )
    }

    private suspend fun apply(context: Context, extras: ProvisioningExtras, source: String) = locked {
        val store = store(context)
        when (qrExtrasAction(extras, store.appliedHash(), enrolledNow(), com.kidslauncher.mdm.BuildConfig.DEBUG)) {
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
                // Kept by deliver (or the retry's own copy); kept here only when that seal failed.
                if (!store.hasPending() && !store.setPending(extras)) {
                    Log.w(LOG_TAG, "$source: couldn't keep the admin extras - a failed enrolment now isn't tried again")
                }
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
                    // Design 129b D11: kept while it waits - after a process death the next sync run's retry gets the 409
                    // again and starts the waiting with the same attempt.
                    QrExtrasAfter.WAIT -> {
                        Log.i(LOG_TAG, "$source: waiting for a parent")
                        if (!EnrolWait.running()) {
                            waitFor(context, extras, result.exceptionOrNull() as EnrollWaiting, keepPending = true)
                        }
                    }
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

/** Pure (QA L3): Settings' scanner sends a code unless it is the one that enrolled this phone. */
fun scannedCodeSends(enrolled: Boolean, appliedHash: String?, code: String): Boolean =
    !(enrolled && appliedHash != null && appliedHash == qrCodeHash(code))

/** After an apply: done, refused for good (the address, or a status that is the code's fault), kept for a retry, or
 * kept while the server waits for a parent (design 129b D11). */
enum class QrExtrasAfter { DONE, REFUSED, RETRY, WAIT }

fun qrExtrasAfter(result: Result<Unit>): QrExtrasAfter = when {
    result.isSuccess -> QrExtrasAfter.DONE
    result.exceptionOrNull() is EnrollWaiting -> QrExtrasAfter.WAIT
    (result.exceptionOrNull() as? EnrollFailure)?.refused == true -> QrExtrasAfter.REFUSED
    else -> QrExtrasAfter.RETRY
}

/** The applied marker's key: SHA-256 of the code, hex - the code itself is never kept in the clear. */
fun qrCodeHash(code: String): String =
    MessageDigest.getInstance("SHA-256").digest(code.trim().toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

@Serializable
private class KeptExtras(val serverUrl: String, val tailscaleAuthKey: String, val enrollmentCode: String)

/** The kept extras as read: none, the extras, sealed but not openable now, or opened but not ours to parse. */
sealed interface KeptRead {
    data object Absent : KeptRead

    data class Value(val extras: ProvisioningExtras) : KeptRead {
        override fun toString() = "Value(<redacted>)"
    }

    data object Unreadable : KeptRead

    data object Corrupt : KeptRead
}

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

    fun pendingRead(): KeptRead = when (val read = sealed.read(prefs, PENDING_AAD, PENDING)) {
        Sealed.Absent -> KeptRead.Absent
        Sealed.Unreadable -> KeptRead.Unreadable
        is Sealed.Value -> try {
            val kept = ServerJson.decodeFromString(KeptExtras.serializer(), read.plain)
            KeptRead.Value(ProvisioningExtras(kept.serverUrl, kept.tailscaleAuthKey, kept.enrollmentCode))
        } catch (e: Exception) {
            KeptRead.Corrupt
        }
    }

    /** The kept extras, or `null` - none, or kept but not readable. */
    fun pending(): ProvisioningExtras? = (pendingRead() as? KeptRead.Value)?.extras

    /** Whether the secrets key itself is broken (not a transient Keystore error). */
    fun keyBroken(): Boolean = sealed.keyBroken()

    fun setPending(extras: ProvisioningExtras): Boolean {
        val plain = ServerJson.encodeToString(KeptExtras.serializer(), KeptExtras(extras.serverUrl, extras.tailscaleAuthKey, extras.enrollmentCode))
        return sealed.edit(prefs) { it.putSealed(PENDING_AAD, PENDING, plain) }
    }

    fun dropPending() {
        sealed.edit(prefs) { it.remove(PENDING_AAD, PENDING) }
    }
}
