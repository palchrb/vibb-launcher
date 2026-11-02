package com.kidslauncher.mdm.calls

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log

private const val LOG_TAG = "PlacedCallWatch"

/** How long the phone's call state must stay off-hook before a placed emergency call counts as
 * connected ([PlacedCallEvidence]): dialling an emergency number that never connects rarely lasts it. */
const val PLACED_CONNECT_EVIDENCE_MS = 20_000L

/** No off-hook this long after the placing: the call never started, the watch ends. */
private const val START_TIMEOUT_MS = 30_000L

/** The watch never outlives this, whatever the call state says. */
private const val MAX_WATCH_MS = 3 * 60 * 60 * 1000L

/**
 * What the phone's call state (`TelephonyCallback.CallStateListener`: idle, ringing or off-hook, nothing
 * finer) says of an emergency call the launcher placed that only the system dialer shows (E7's H1, QA
 * fix rounds) - pure, [PlacedCallWatch] feeds it elapsed-realtime instants. Off-hook held for
 * [PLACED_CONNECT_EVIDENCE_MS] is the evidence that it connected ([Step.PROMOTE]: the full window);
 * the end of the call after that - or the watch's own end ([MAX_WATCH_MS]) - records it once more, so
 * the hour runs from then ([Step.PROMOTE_AND_STOP]); an end before it leaves the provisional window
 * ([Step.STOP]). Idle before off-hook (the listener's first report, sent at registration, before the
 * call starts) changes nothing; ringing before it means another call, not ours ([Step.STOP]).
 */
internal class PlacedCallEvidence {
    enum class Step { NONE, PROMOTE, PROMOTE_AND_STOP, STOP }

    private var offhookSinceMs = -1L
    private var promoted = false

    val started: Boolean get() = offhookSinceMs >= 0

    fun offhook(nowMs: Long): Step {
        if (offhookSinceMs < 0) offhookSinceMs = nowMs
        return check(nowMs)
    }

    /** How long until [check] can promote: counted from the first off-hook (E7b's M-C1: a timer armed
     * at the placing fired just short of it, and nothing checked again during a steady call); `null`
     * once promoted or before the call started - no timer then. */
    fun checkDueInMs(nowMs: Long): Long? =
        if (promoted || !started) null else maxOf(0L, offhookSinceMs + PLACED_CONNECT_EVIDENCE_MS - nowMs)

    /** At the evidence timer, or any later report while off-hook. */
    fun check(nowMs: Long): Step =
        if (!promoted && started && nowMs - offhookSinceMs >= PLACED_CONNECT_EVIDENCE_MS) {
            promoted = true
            Step.PROMOTE
        } else {
            Step.NONE
        }

    fun idle(nowMs: Long): Step = if (started) finish(nowMs) else Step.NONE

    /** After off-hook only a call that ended can let a ringing one show; before it, it is someone else's. */
    fun ringing(nowMs: Long): Step = finish(nowMs)

    /** The call ended, or the watch does ([MAX_WATCH_MS]). */
    fun finish(nowMs: Long): Step = when {
        !started -> Step.STOP
        promoted || check(nowMs) == Step.PROMOTE -> Step.PROMOTE_AND_STOP
        else -> Step.STOP
    }
}

/**
 * The Android side of [PlacedCallEvidence]: one call-state listener per launcher-placed emergency call
 * (READ_PHONE_STATE, self-granted while calls are managed; without it the placing keeps its provisional
 * window). Main thread. Not started when another call was on before the placing (its off-hook would
 * read as ours - [CallSystem.callBeforePlacing]). Ends at the call's end, [START_TIMEOUT_MS] without a
 * start, [MAX_WATCH_MS], the next placing, or when our in-call service sees the call itself ([stop]: its
 * own states decide then). A process death ends it too: the placing then keeps what was recorded last.
 */
object PlacedCallWatch {
    private class Watch(val telephony: TelephonyManager, val callback: TelephonyCallback)

    private val handler = Handler(Looper.getMainLooper())
    private var current: Watch? = null

    fun start(context: Context, placedAtMs: Long) {
        stop()
        val app = context.applicationContext
        val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
        val evidence = PlacedCallEvidence()
        lateinit var watch: Watch
        lateinit var checkAtDue: Runnable

        /** Arms the evidence check for when it is due ([PlacedCallEvidence.checkDueInMs]); one at a time. */
        fun armCheck() {
            handler.removeCallbacks(checkAtDue)
            evidence.checkDueInMs(SystemClock.elapsedRealtime())?.let { handler.postDelayed(checkAtDue, it) }
        }
        fun act(step: PlacedCallEvidence.Step) {
            if (current !== watch) return
            when (step) {
                PlacedCallEvidence.Step.NONE -> Unit
                PlacedCallEvidence.Step.PROMOTE -> CallPrefs.promotePlaced(app, placedAtMs)
                PlacedCallEvidence.Step.PROMOTE_AND_STOP -> {
                    CallPrefs.promotePlaced(app, placedAtMs)
                    stop()
                }
                PlacedCallEvidence.Step.STOP -> stop()
            }
            if (step != PlacedCallEvidence.Step.NONE) Log.i(LOG_TAG, "Placed emergency call: $step")
        }
        val callback = object : TelephonyCallback(), TelephonyCallback.CallStateListener {
            override fun onCallStateChanged(state: Int) {
                val now = SystemClock.elapsedRealtime()
                act(
                    when (state) {
                        TelephonyManager.CALL_STATE_OFFHOOK -> evidence.offhook(now)
                        TelephonyManager.CALL_STATE_RINGING -> evidence.ringing(now)
                        else -> evidence.idle(now)
                    },
                )
                if (current === watch && state == TelephonyManager.CALL_STATE_OFFHOOK) armCheck()
            }
        }
        // Re-armed while not yet due (a timer firing early), none once promoted.
        checkAtDue = Runnable {
            act(evidence.check(SystemClock.elapsedRealtime()))
            if (current === watch) armCheck()
        }
        watch = Watch(telephony, callback)
        current = watch
        try {
            telephony.registerTelephonyCallback(app.mainExecutor, callback)
        } catch (e: Exception) {
            current = null
            Log.w(LOG_TAG, "Can't watch the placed emergency call (provisional window only): ${e.javaClass.simpleName}")
            return
        }
        handler.postDelayed({ if (!evidence.started) act(PlacedCallEvidence.Step.STOP) }, START_TIMEOUT_MS)
        handler.postDelayed({ act(evidence.finish(SystemClock.elapsedRealtime())) }, MAX_WATCH_MS)
    }

    fun stop() {
        val watch = current ?: return
        current = null
        handler.removeCallbacksAndMessages(null)
        try {
            watch.telephony.unregisterTelephonyCallback(watch.callback)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't unregister the call-state listener: ${e.javaClass.simpleName}")
        }
    }
}
