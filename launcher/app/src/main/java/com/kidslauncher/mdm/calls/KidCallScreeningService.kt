package com.kidslauncher.mdm.calls

import android.content.Context
import android.telecom.Call
import android.telecom.CallScreeningService
import android.telecom.Connection
import android.telecom.TelecomManager
import android.util.Log

private const val LOG_TAG = "KidCallScreening"

/**
 * First filter for incoming calls: Telecom binds the default dialer's screening service for every
 * incoming call (we see numbers in the contacts too because we hold READ_CONTACTS). A blocked call
 * is rejected without ringing or a notification; it stays in the system call log as blocked.
 * Telecom gives us 5 s and then lets the call through, and never asks us about withheld numbers,
 * so [KidInCallService] re-checks every incoming call (the backstop). Answers from memory
 * (CallPolicyStore); only a call that would be blocked reads the call log, for the callback window.
 */
class KidCallScreeningService : CallScreeningService() {

    override fun onCreate() {
        super.onCreate()
        CallPolicyStore.ensureLoaded(this)
    }

    override fun onScreenCall(details: Call.Details) {
        if (details.callDirection != Call.Details.DIRECTION_INCOMING) {
            // Respond anyway (an empty response allows); only incoming calls can be rejected.
            respondToCall(details, CallResponse.Builder().build())
            return
        }
        val response = if (incomingVerdict(this, details) == Verdict.ALLOW) {
            CallResponse.Builder().build()
        } else {
            Log.i(LOG_TAG, "Rejecting an incoming call")
            CallResponse.Builder()
                .setDisallowCall(true)
                .setRejectCall(true)
                .setSkipNotification(true)
                .build()
        }
        respondToCall(details, response)
    }
}

/** [decideIncoming] for a Telecom call; an unexpected error blocks unless calls are unmanaged. The
 * emergency callback passes by Telecom's own mark or our window ([emergencyCallbackPass]); a pass on the
 * mark records the emergency call placed elsewhere ([CallSystem.telecomMarkSeen]). Telecom skipped this
 * screening service for such a call on the emulator (E7: `Skipping call filtering ... ecm=true`), so
 * [KidInCallService]'s re-check is where it is let through - a device check whether a real SIM does too. */
fun incomingVerdict(context: Context, details: Call.Details): Verdict {
    val state = CallPolicyStore.effectiveState()
    return try {
        decideIncoming(
            raw = PhoneNumbers.numberFromHandle(details.handle?.toString()),
            presentationAllowed = details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED,
            verificationFailed = details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED,
            state = state,
        ) {
            val pass = emergencyCallbackPass(
                ecbm = details.hasProperty(Call.Details.PROPERTY_EMERGENCY_CALLBACK_MODE),
                simAccount = {
                    CallSystem.simAccount(context, details.accountHandle)
                        .also { Log.i(LOG_TAG, "Telecom marks an emergency callback (SIM account: $it)") }
                },
                windowOpen = { CallSystem.callbackWindowUntil(context) != null },
            )
            if (pass == CallbackPass.TELECOM_MARK) CallSystem.telecomMarkSeen(context)
            pass != null
        }
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Incoming call check failed", e)
        if (state is CallPolicyState.Unmanaged) Verdict.ALLOW else Verdict.BLOCK
    }
}
