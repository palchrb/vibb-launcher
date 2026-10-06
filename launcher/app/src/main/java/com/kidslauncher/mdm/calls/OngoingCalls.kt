package com.kidslauncher.mdm.calls

import android.content.Context
import android.os.OutcomeReceiver
import android.telecom.Call
import android.telecom.CallEndpoint
import android.telecom.CallEndpointException
import android.telecom.VideoProfile
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

private const val LOG_TAG = "OngoingCalls"

/**
 * Emulator run 2026-10-06: after a call ended Telecom had no calls, but our ongoing-call
 * notification stayed and the call screen showed "Ended" with a dead Hang up button. A call counts
 * only while it isn't DISCONNECTED; with none live - or Telecom saying it has no call at all
 * ([telecomInCall] false; `null` = couldn't ask) - every call trace of ours is cleared.
 */
fun callUiShouldClear(callStates: List<Int>, telecomInCall: Boolean?): Boolean =
    telecomInCall == false || callStates.none { it != Call.STATE_DISCONNECTED }

/**
 * The calls [KidInCallService] shows, and the audio state, shared with [InCallActivity] and the
 * notification buttons ([CallActionReceiver]). Main thread only (Telecom calls InCallService on
 * the main thread).
 */
object OngoingCalls {
    val calls = CopyOnWriteArrayList<Call>()
    var service: KidInCallService? = null
    var muted = false
        private set
    var endpoint: CallEndpoint? = null
        private set
    var availableEndpoints: List<CallEndpoint> = emptyList()
        private set

    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    fun addListener(listener: () -> Unit) { listeners += listener }

    fun removeListener(listener: () -> Unit) { listeners -= listener }

    fun changed() = listeners.forEach { it() }

    /** The states of the calls we show, for [secondCallAllowed]. */
    val states: List<Int> get() = calls.map { it.details.state }

    /** A call that isn't DISCONNECTED - "our call" for the PIN lock, locale switches, time rules. */
    val hasLiveCall: Boolean get() = calls.any { it.details.state != Call.STATE_DISCONNECTED }

    /**
     * Clears our call UI when no call is live ([callUiShouldClear]): forgets disconnected calls,
     * cancels the notification and tells the listeners (the call screen finishes). From
     * `onCallRemoved`, a disconnect that Telecom never follows up, the service's unbind/destroy
     * and process start. Main thread.
     */
    fun reconcile(context: Context, telecomInCall: Boolean? = telecomInCall(context)) {
        if (!callUiShouldClear(calls.map { it.details.state }, telecomInCall)) return
        if (calls.isNotEmpty()) Log.w(LOG_TAG, "Clearing ${calls.size} call(s) Telecom no longer has")
        calls.clear()
        CallNotifications.cancel(context)
        changed()
    }

    /** Process start: a notification a dead process left behind goes, unless Telecom has a call
     * (then our service is bound again and re-posts it with onCallAdded). */
    fun reconcileAtStart(context: Context) {
        val inCall = telecomInCall(context)
        if (inCall != true) reconcile(context, inCall ?: false)
    }

    /** `TelecomManager.isInCall` (we hold READ_PHONE_STATE / the dialer role); `null` if refused. */
    fun telecomInCall(context: Context): Boolean? = try {
        context.getSystemService(android.telecom.TelecomManager::class.java)?.isInCall
    } catch (e: Exception) {
        null
    }

    /** The call the UI is about: a ringing one first, else the newest. */
    val current: Call? get() = calls.firstOrNull { it.details.state == Call.STATE_RINGING } ?: calls.lastOrNull()

    internal fun add(call: Call) { calls += call; changed() }

    internal fun remove(call: Call) {
        calls -= call
        changed()
    }

    internal fun audioChanged(muted: Boolean? = null, endpoint: CallEndpoint? = null, available: List<CallEndpoint>? = null) {
        muted?.let { this.muted = it }
        endpoint?.let { this.endpoint = it }
        available?.let { availableEndpoints = it }
        changed()
    }

    /** Video calls are answered as audio only. */
    fun answer(call: Call) = call.answer(VideoProfile.STATE_AUDIO_ONLY)

    fun hangUp(call: Call) {
        if (call.details.state == Call.STATE_RINGING) call.reject(Call.REJECT_REASON_DECLINED) else call.disconnect()
    }

    fun toggleMute() { service?.setMuted(!muted) }

    val speakerOn: Boolean get() = endpoint?.endpointType == CallEndpoint.TYPE_SPEAKER

    /** Speaker on/off via `requestCallEndpointChange` (`setAudioRoute` is deprecated). */
    fun toggleSpeaker(context: Context) {
        val service = service ?: return
        val wanted = if (speakerOn) {
            availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_BLUETOOTH }
                ?: availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_WIRED_HEADSET }
                ?: availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_EARPIECE }
        } else {
            availableEndpoints.firstOrNull { it.endpointType == CallEndpoint.TYPE_SPEAKER }
        } ?: return
        service.requestCallEndpointChange(
            wanted,
            context.mainExecutor,
            object : OutcomeReceiver<Void, CallEndpointException> {
                override fun onResult(result: Void?) {}

                override fun onError(error: CallEndpointException) {
                    Log.w(LOG_TAG, "Couldn't switch the audio route", error)
                }
            },
        )
    }
}
