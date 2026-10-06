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
private const val RECONCILE_DEBOUNCE_MS = 1_000L

/**
 * Emulator run 2026-10-06: after a call ended Telecom had no calls, but our ongoing-call
 * notification stayed and the call screen showed "Ended" with a dead Hang up button. The call UI
 * goes only when no call at all is live - ours ([ourStates]) or the in-call service's own list
 * ([serviceStates], `InCallService.getCalls()`): any state but DISCONNECTED counts, including
 * NEW, CONNECTING, SELECT_PHONE_ACCOUNT, DIALING and PULLING. `TelecomManager.isInCall` is not
 * used: it leaves out NEW/CONNECTING calls (AOSP `CallsManager.ONGOING_CALL_STATES`), so a quick
 * 112 redial that is still connecting would have lost its UI (qa-fixround-2026-10-06 #1).
 */
fun callUiShouldClear(ourStates: List<Int>, serviceStates: List<Int>): Boolean =
    (ourStates + serviceStates).none { it != Call.STATE_DISCONNECTED }

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

    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private var reconcilePending = false

    /** Forgets our DISCONNECTED calls; a live call is never dropped. */
    private fun dropEnded(): Boolean = calls.removeAll { it.details.state == Call.STATE_DISCONNECTED }

    private fun serviceStates(): List<Int> = try {
        service?.calls?.map { it.details.state }.orEmpty()
    } catch (e: Exception) {
        emptyList()
    }

    private fun shouldClear(): Boolean = callUiShouldClear(states, serviceStates())

    /**
     * Clears our call UI when no call is live ([callUiShouldClear]), debounced: checked now and
     * again [RECONCILE_DEBOUNCE_MS] later, and only cleared (ended calls forgotten, notification
     * cancelled, listeners told - the call screen finishes) if both times there was none. From a
     * disconnect that Telecom doesn't follow up with `onCallRemoved`, the "Ended" screen and
     * process start. Main thread.
     */
    fun reconcile(context: Context) {
        if (!shouldClear() || reconcilePending) return
        reconcilePending = true
        val app = context.applicationContext
        handler.postDelayed({
            reconcilePending = false
            if (!shouldClear()) {
                if (dropEnded()) changed()
                return@postDelayed
            }
            if (calls.isNotEmpty()) Log.w(LOG_TAG, "Clearing ${calls.size} ended call(s) Telecom didn't remove")
            calls.clear()
            CallNotifications.cancel(app)
            changed()
        }, RECONCILE_DEBOUNCE_MS)
    }

    /**
     * The in-call service was unbound or destroyed: Telecom has no call for us any more (it unbinds
     * only then, or when the dialer role moves - the calls are then another app's), and our `Call`
     * objects are dead. Clears at once.
     */
    fun serviceGone(context: Context) {
        handler.removeCallbacksAndMessages(null)
        reconcilePending = false
        calls.clear()
        CallNotifications.cancel(context)
        changed()
    }

    /** Process start: a notification a dead process left behind goes (debounced, so a call Telecom
     * is binding us for right now keeps it - onCallAdded re-posts it anyway). */
    fun reconcileAtStart(context: Context) = reconcile(context)

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
