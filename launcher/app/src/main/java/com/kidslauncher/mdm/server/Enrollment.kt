package com.kidslauncher.mdm.server

import android.content.Context
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.EnrollRequest
import com.kidslauncher.mdm.setup.setupHoldStarts
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/** Why an enrolment failed (code QA Q-1): [refused] - the server refused the code itself ([CODE_REFUSED_STATUSES]: a
 * wrong, used or expired code); else the server wasn't reached, was busy (408, 425, 429), answered 5xx, or the token
 * couldn't be stored - worth trying again. */
class EnrollFailure(val refused: Boolean, message: String) : Exception(message)

/** The statuses that are the code's fault (QA of qr-compliance-extras, M1): the server answers 429 (+Retry-After, the
 * enrol slots full) and 408 (the body's timeout) for a code that is still good. */
val CODE_REFUSED_STATUSES = setOf(400, 401, 403, 404, 410)

/** An HTTP status's failure: only [CODE_REFUSED_STATUSES] are the code's fault. */
fun enrollFailureOf(httpCode: Int): EnrollFailure = EnrollFailure(refused = httpCode in CODE_REFUSED_STATUSES, "HTTP $httpCode")

/**
 * The one enrolment (design r10 §5): `POST /api/devices/enroll` with a one-shot code, the new device token sealed
 * and committed ([DeviceToken.set], on IO - revision check N7), `enrolled`, and the old enrolment's state cleared.
 * Used by the QR's [applyProvisioningExtras], Settings' "Enroll now" and the lock's "Ny oppsettkode"; the callers
 * that want the first policy at once ask for the sync themselves. The tailnet comes up first (code QA Q-1: a
 * `*.ts.net` server resolves only through it, and in the deadlock no sync or stream retries it).
 */
object Enrollment {
    private fun deviceOwnerNow(context: Context): Boolean = try {
        context.getSystemService(android.app.admin.DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /** [connectTailnet] false: the caller has just tried the tailnet in this flow ([applyProvisioningExtras]) - a
     * second attempt would only double the wait (code QA L2 of the upstream port). */
    suspend fun enroll(
        context: Context,
        serverUrl: String,
        code: String,
        connectTailnet: Boolean = true,
    ): Result<Unit> = withContext(Dispatchers.IO + NonCancellable) {
        // QA of qr-compliance-extras, M3: never cancelled half-way - a POST the server took has used the one-shot code,
        // and its token must be stored. Every call in here is time-bounded (the tsnet connect, OkHttp's timeouts).
        try {
            // Design 114 §6.1.1 (QA L10): whether this enrolment starts the setup hold is decided before the token is
            // stored, so no sync can apply a policy between the token's commit and the check.
            val mdmBefore = LauncherPreferences.mdm()
            val startsHold = setupHoldStarts(
                deviceOwner = deviceOwnerNow(context),
                policyEverApplied = mdmBefore.policyEverApplied(),
                cachedPolicy = !mdmBefore.kidModePolicy().isNullOrBlank(),
            )
            // No-ops when connected or when no key is set.
            if (connectTailnet) TsnetClient.connectFromPreferences(context)
            // Design 129 §5.1: the new hardware key, attested for this code's challenge (a retry with the same code
            // reuses its key), and a proof signed afresh on every attempt. No key: the enrolment goes without one
            // (S1 - the bearer; the registration flow tries again later).
            val key = enrolKey(context, code)
            val response = createMdmApi(serverUrl).enroll(EnrollRequest(code, key?.second, key?.third))
            val body = response.body()
            if (!response.isSuccessful || body == null) return@withContext Result.failure(enrollFailureOf(response.code()))
            val mdm = LauncherPreferences.mdm()
            if (!DeviceToken.set(body.deviceToken)) {
                return@withContext Result.failure(EnrollFailure(refused = false, "the token couldn't be stored"))
            }
            enrolledKey(context, key?.first, body.key)
            mdm.enrolled(true)
            // Design 95 (code QA L3): the old enrollment's kept policy body (its PIN hashes) goes; the token hash would
            // refuse it anyway.
            PolicyEtagStore.of(context).drop()
            // And the last full report the old enrollment's server took (design 95 §3.5): the next one is full.
            HeartbeatAckStore.of(context).clear()
            // A new enrollment: the queued parent-PIN events were about the old one (design 24).
            ParentPin.clearEvents(context)
            // And the commands it ran and the answers it queued (QA of the launcher wave, Medium 1).
            CommandAnswers.clear(context)
            // And the old enrollment's zone watch: fences, queue, kept fix, beat, mock count (design 64 step 2, Q-8).
            com.kidslauncher.mdm.zones.ZoneWatch.forget(context)
            // And a running Install Now (design 37): its command was the old enrollment's.
            InstallNow.clear(context)
            // And the setup ticks it hadn't posted (design 114 §4.4): they were for the old enrollment's phone.
            com.kidslauncher.mdm.setup.SetupTicks.clear(context)
            // Design 114 §6.1: a fresh phone comes up open, in the setup flow; the first policy's applies run inside the
            // hold. Before the callers' "enrolled" sync.
            if (startsHold) RestrictionsPause.startHold(context)
            // The first policy's storm follows (2026-10-25): its package callbacks fold for 2 min.
            com.kidslauncher.mdm.ui.home.AppLoadHold.provisioning(android.os.SystemClock.elapsedRealtime())
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** The enrolment's key (§5.1): its alias, chain and a fresh proof, or `null` when none could be made. */
    private fun enrolKey(context: Context, code: String): Triple<String, List<String>, String>? = try {
        val challenge = enrolChallenge(code)
        val now = TrustedClock.nowMs()
        val made = DeviceKeys.enrolKeyFor(context, b64u(challenge))
            ?: DeviceKeys.make(context, challenge, now + DeviceKeys.ENROL_CHALLENGE_HOLDS_MS, now, enrolChallenge = b64u(challenge))
        made?.let { Triple(it.alias, it.chainB64(), DeviceKeys.proof(context, it.alias, challenge, ProofKind.ENROLL)) }
    } catch (e: KeyGenFailure) {
        android.util.Log.w("Enrollment", "No hardware key for the enrolment: ${e.message}")
        DeviceKeys.refused(context, if (e.keysUnavailable) "keys_unavailable" else "keystore")
        null
    } catch (e: Exception) {
        android.util.Log.w("Enrollment", "No hardware key for the enrolment: ${e.javaClass.simpleName}")
        null
    }

    /** After the token is stored: the old enrolment's keys go; this one's is adopted when the server took it, deleted
     * when it refused it (S1: the phone stays on its bearer, the registration flow tries again). */
    private fun enrolledKey(context: Context, alias: String?, answer: com.kidslauncher.mdm.server.dto.EnrollKeyAnswer?) {
        try {
            val keyId = answer?.keyId
            val keep = if (answer?.state == "observed" && !keyId.isNullOrBlank()) alias else null
            DeviceKeys.newEnrolment(context, keep)
            if (keep != null && keyId != null) {
                DeviceKeys.adopt(context, keep, keyId, "observed")
            } else if (answer?.state == "refused") {
                android.util.Log.w("Enrollment", "The server refused the enrolment's key: ${answer.reason}")
                DeviceKeys.refused(context, answer.reason ?: "invalid")
            }
        } catch (e: Exception) {
            android.util.Log.w("Enrollment", "The enrolment's key wasn't kept: ${e.javaClass.simpleName}")
        }
    }
}

/**
 * "Ny oppsettkode" in the parent-code dialogs (design r10 §5, QA R10-5): only in the deadlock - the device token is
 * [Sealed.Unreadable] **and** the parent PIN can't be checked until a sync (or there is none), with a server URL
 * stored. Not kid-controllable: a key goes only by an OEM fault, root or our uid. Design 129 (§5.4, §6.2): an adopted
 * hardware key still speaks for an unreadable token ([keyAdopted]); a key the server no longer knows, or one that stopped
 * signing with no bearer beside it ([keyUnusable]), needs the new setup code too.
 */
fun showReEnrol(
    token: Sealed,
    parent: ParentPinStatus,
    serverUrl: String?,
    keyAdopted: Boolean = false,
    keyUnusable: Boolean = false,
): Boolean =
    ((token == Sealed.Unreadable && !keyAdopted) || keyUnusable) && (parent.untilSync || !parent.configured) && !serverUrl.isNullOrBlank()

/** The code dialog's line for a failed enrolment: only a refused code says "that code didn't work" (Q-1). */
fun enrolFailureRefused(error: Throwable?): Boolean = (error as? EnrollFailure)?.refused == true
