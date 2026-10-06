package com.kidslauncher.mdm.calls

import android.net.Uri
import android.telecom.CallRedirectionService
import android.telecom.PhoneAccountHandle
import android.util.Log
import android.widget.Toast
import com.kidslauncher.mdm.R

private const val LOG_TAG = "KidCallRedirection"

/**
 * Stops a not-allowed outgoing call before it's placed, so the other phone never rings or logs a
 * missed call (QA #5) - whichever app dialled it (another app's ACTION_CALL, the system dialer,
 * Bluetooth redial, the Assistant). Needs ROLE_CALL_REDIRECTION: there is no device-owner API for
 * it, so it's granted at provisioning (`cmd role add-role-holder android.app.role.CALL_REDIRECTION
 * <package>`) or via the system prompt from Home; the status report says whether we hold it.
 * Telecom skips redirection for emergency calls, and its timeout fails open - so this answers from
 * memory, and [KidInCallService] disconnects anything that slips through.
 */
class KidCallRedirectionService : CallRedirectionService() {

    override fun onCreate() {
        super.onCreate()
        CallPolicyStore.ensureLoaded(this)
    }

    override fun onPlaceCall(handle: Uri, initialPhoneAccount: PhoneAccountHandle, allowInteractiveResponsePostRedirect: Boolean) {
        val state = CallPolicyStore.effectiveState()
        var target: String? = null
        var busy = false
        val verdict = try {
            val raw = PhoneNumbers.numberFromHandle(handle.toString())
            val emergency = CallSystem.isEmergencyOutgoing(this, raw)
            // One call at a time (fix round 2026-10-06): whoever dials it, a second call is
            // cancelled while one rings, dials, is active or held - emergency numbers excepted.
            if (!secondCallAllowed(OngoingCalls.states, emergency)) {
                Log.i(LOG_TAG, "Cancelling a second call while one exists")
                busy = true
                Verdict.BLOCK
            } else decideOutgoing(raw, state, emergency).also {
                if (it == Verdict.ALLOW) target = outgoingDialTarget(raw, state, emergency)
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Outgoing call check failed", e)
            if (state is CallPolicyState.Unmanaged) Verdict.ALLOW else Verdict.BLOCK
        }
        val redirectTo = target
        if (verdict == Verdict.ALLOW && redirectTo != null) {
            // Dial the number we checked, not the string typed (QA step 2 #6).
            redirectCall(Uri.fromParts("tel", redirectTo, null), initialPhoneAccount, false)
        } else if (verdict == Verdict.ALLOW) {
            placeCallUnmodified()
        } else {
            cancelCall()
            Toast.makeText(applicationContext, if (busy) R.string.calls_busy else R.string.calls_not_allowed, Toast.LENGTH_LONG).show()
        }
    }
}
