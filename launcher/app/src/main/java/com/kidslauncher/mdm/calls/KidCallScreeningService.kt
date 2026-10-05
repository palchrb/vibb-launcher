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

/** [decideIncoming] for a Telecom call; an unexpected error blocks unless calls are unmanaged. */
fun incomingVerdict(context: Context, details: Call.Details): Verdict {
    val state = CallPolicyStore.effectiveState()
    return try {
        decideIncoming(
            raw = PhoneNumbers.numberFromHandle(details.handle?.toString()),
            presentationAllowed = details.handlePresentation == TelecomManager.PRESENTATION_ALLOWED,
            verificationFailed = details.callerNumberVerificationStatus == Connection.VERIFICATION_STATUS_FAILED,
            state = state,
        ) { CallSystem.callbackWindowUntil(context) != null }
    } catch (e: Exception) {
        Log.e(LOG_TAG, "Incoming call check failed", e)
        if (state is CallPolicyState.Unmanaged) Verdict.ALLOW else Verdict.BLOCK
    }
}
