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
