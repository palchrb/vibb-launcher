package com.kidslauncher.mdm.calls

import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.InCallService
import android.util.Log

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
            showUi(call, startActivity = false)
            OngoingCalls.changed()
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

    override fun onDestroy() {
        if (OngoingCalls.service === this) OngoingCalls.service = null
        super.onDestroy()
    }

    override fun onCallAdded(call: Call) {
        OngoingCalls.add(call)
        call.registerCallback(callback)
        showUi(call)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        OngoingCalls.remove(call)
        val next = OngoingCalls.current
        if (next == null) CallNotifications.cancel(this) else showUi(next, startActivity = false)
    }

    /** Notification plus a direct activity start: we're device owner and HOME, so the start is
     * allowed from the background and doesn't depend on heads-up/full-screen intents. */
    private fun showUi(call: Call, startActivity: Boolean = true) {
        if (call.details.state == Call.STATE_DISCONNECTED) return
        val number = PhoneNumbers.numberFromHandle(call.details.handle?.toString())
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        val name = rules?.contactFor(number)?.name ?: number ?: getString(com.kidslauncher.mdm.R.string.calls_unknown_caller)
        CallNotifications.show(this, call, name)
        if (startActivity) {
            try {
                startActivity(InCallActivity.intent(this))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't start the in-call screen", e)
            }
        }
    }

    override fun onMuteStateChanged(isMuted: Boolean) = OngoingCalls.audioChanged(muted = isMuted)

    override fun onCallEndpointChanged(callEndpoint: CallEndpoint) = OngoingCalls.audioChanged(endpoint = callEndpoint)

    override fun onAvailableCallEndpointsChanged(availableEndpoints: MutableList<CallEndpoint>) =
        OngoingCalls.audioChanged(available = availableEndpoints.toList())
}
