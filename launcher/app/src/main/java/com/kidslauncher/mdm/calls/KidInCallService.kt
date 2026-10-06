package com.kidslauncher.mdm.calls

import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.util.Log
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.lock.LockMode
import com.kidslauncher.mdm.lock.PinLockRuntime

private const val LOG_TAG = "KidInCallService"

/**
 * Our in-call UI service: Telecom binds it while we hold the dialer role (manifest:
 * BIND_INCALL_SERVICE, IN_CALL_SERVICE_UI). Emergency calls are always shown by the preloaded
 * dialer instead, which is why that one is never suspended or hidden.
 *
 * We don't declare IN_CALL_SERVICE_RINGING: Telecom plays the ringtone and handles ringer mode and
 * DND.
 */
class KidInCallService : InCallService() {

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) {
            recordEmergency(call, state)
            showUi(call, startActivity = false)
            OngoingCalls.changed()
            // Telecom normally follows with onCallRemoved; if it doesn't, don't leave a dead
            // "ongoing call" behind (emulator run 2026-10-06).
            if (state == Call.STATE_DISCONNECTED) {
                handler.postDelayed({ OngoingCalls.reconcile(this@KidInCallService) }, 2_000L)
            }
        }

        override fun onDetailsChanged(call: Call, details: Call.Details) {
            OngoingCalls.changed()
        }
    }

    override fun onCreate() {
        super.onCreate()
        CallPolicyStore.ensureLoaded(this)
        OngoingCalls.service = this
    }

    /** Telecom unbinds when it has no call for us any more: nothing of ours may stay. */
    override fun onUnbind(intent: android.content.Intent?): Boolean {
        OngoingCalls.reconcile(this, telecomInCall = false)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        if (OngoingCalls.service === this) OngoingCalls.service = null
        handler.removeCallbacksAndMessages(null)
        OngoingCalls.reconcile(this, telecomInCall = false)
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        // Backstop for outgoing calls our redirection service didn't stop (no redirection role,
        // its timeout, or a path it doesn't see): hang up before it rings for long.
        if (call.details.callDirection == Call.Details.DIRECTION_OUTGOING && outgoingVerdict(call) == Verdict.BLOCK) {
            Log.i(LOG_TAG, "Disconnecting a not-allowed outgoing call")
            call.disconnect()
            Toast.makeText(applicationContext, R.string.calls_not_allowed, Toast.LENGTH_LONG).show()
            return
        }
        // Backstop for incoming calls screening didn't decide: withheld numbers (never screened),
        // a screening timeout, or the service not bound. Rejected without any UI; a withheld call
        // may ring for a moment first.
        if (call.details.callDirection == Call.Details.DIRECTION_INCOMING && incomingVerdict(this, call.details) == Verdict.BLOCK) {
            Log.i(LOG_TAG, "Rejecting an incoming call")
            call.reject(Call.REJECT_REASON_DECLINED)
            return
        }
        // Unknown direction (handover, conference parent, some connection services): kept only if
        // it's an emergency call or a contact allowed either way (QA step 2 #5).
        if (call.details.callDirection == Call.Details.DIRECTION_UNKNOWN && unknownVerdict(call) == Verdict.BLOCK) {
            Log.i(LOG_TAG, "Disconnecting a not-allowed call of unknown direction")
            call.disconnect()
            return
        }
        OngoingCalls.add(call)
        call.registerCallback(callback)
        recordEmergency(call, call.details.state)
        showUi(call)
    }

    /**
     * Opens the callback window (CallSystem.callbackWindowUntil) for an emergency call that
     * connected, if we see one at all - normally the preloaded dialer shows emergency calls and
     * the call log is what opens the window. Only the platform's answer counts.
     */
    private fun recordEmergency(call: Call, state: Int) {
        if (call.details.callDirection != Call.Details.DIRECTION_OUTGOING) return
        val connected = state == Call.STATE_ACTIVE ||
            (state == Call.STATE_DISCONNECTED && call.details.connectTimeMillis > 0)
        if (!connected) return
        val raw = PhoneNumbers.numberFromHandle(call.details.handle?.toString())
        if (Emergency.platformConfirms(raw, CallSystem.platformEmergency(this))) {
            CallPrefs.recordEmergencyConnected(this)
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        OngoingCalls.remove(call)
        val next = OngoingCalls.current
        if (next == null || !OngoingCalls.hasLiveCall) {
            OngoingCalls.reconcile(this, telecomInCall = OngoingCalls.telecomInCall(this))
            CallNotifications.cancel(this)
        } else {
            showUi(next, startActivity = false)
        }
    }

    private fun outgoingVerdict(call: Call): Verdict {
        val state = CallPolicyStore.effectiveState()
        return try {
            val raw = PhoneNumbers.numberFromHandle(call.details.handle?.toString())
            decideOutgoing(raw, state, CallSystem.isEmergencyOutgoing(this, raw))
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Outgoing call check failed", e)
            if (state is CallPolicyState.Unmanaged) Verdict.ALLOW else Verdict.BLOCK
        }
    }

    private fun unknownVerdict(call: Call): Verdict {
        val state = CallPolicyStore.effectiveState()
        return try {
            val raw = PhoneNumbers.numberFromHandle(call.details.handle?.toString())
            decideUnknownDirection(raw, state, CallSystem.isEmergencyOutgoing(this, raw))
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Unknown-direction call check failed", e)
            if (state is CallPolicyState.Unmanaged) Verdict.ALLOW else Verdict.BLOCK
        }
    }

    /** Notification plus a direct activity start: we're device owner and HOME, so the start is
     * allowed from the background and doesn't depend on heads-up/full-screen intents. */
    private fun showUi(call: Call, startActivity: Boolean = true) {
        if (call.details.state == Call.STATE_DISCONNECTED) return
        val number = PhoneNumbers.numberFromHandle(call.details.handle?.toString())
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        val name = rules?.contactFor(number)?.name?.takeIf { it.isNotBlank() } ?: number ?: getString(R.string.calls_unknown_caller)
        CallNotifications.show(this, call, name)
        if (startActivity) {
            startInCallUi()
            // Over handy's PIN lock the full-screen intent is probably suppressed with the shade
            // (QA 10 #8): the direct start is all there is, so it gets one retry, and a failure is
            // reported - an allowed call must always be answerable.
            if (PinLockRuntime.mode == LockMode.LOCKED) handler.postDelayed({ retryIfHidden(call) }, 300L)
        }
    }

    private val handler = Handler(Looper.getMainLooper())

    private fun startInCallUi(): Boolean = try {
        startActivity(InCallActivity.intent(this))
        true
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't start the in-call screen", e)
        false
    }

    private fun retryIfHidden(call: Call) {
        if (call !in OngoingCalls.calls || InCallActivity.resumed) return
        Log.w(LOG_TAG, "In-call screen not in front over the PIN lock - retrying once")
        startInCallUi()
        handler.postDelayed({
            if (call in OngoingCalls.calls && call.details.state == Call.STATE_RINGING && !InCallActivity.resumed) {
                Log.e(LOG_TAG, "In-call screen still not shown over the PIN lock")
                CallPrefs.recordInCallUiFailed(this)
            }
        }, 1_000L)
    }

    override fun onMuteStateChanged(isMuted: Boolean) = OngoingCalls.audioChanged(muted = isMuted)

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) = OngoingCalls.audioChanged(endpoint = callEndpoint)

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) =
        OngoingCalls.audioChanged(available = availableEndpoints.toList())
}
