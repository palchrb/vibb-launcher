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
 * What the phone's call state (`TelephonyCallback.CallStateListener`: idle or off-hook, nothing finer)
 * says of an emergency call the launcher placed that only the system dialer shows (E7's H1, QA fix
 * round) - pure, [PlacedCallWatch] feeds it elapsed-realtime instants. Off-hook held for
 * [PLACED_CONNECT_EVIDENCE_MS] is the evidence that it connected ([Step.PROMOTE]: the full window);
 * the end of the call after that re-records it so the hour runs from the hang-up ([Step.HUNG_UP]); an
 * end before it leaves the provisional window ([Step.STOP]). Not off-hook yet (the listener's first
 * report, sent at registration, may be the idle before the call starts) changes nothing.
 */
internal class PlacedCallEvidence {
    enum class Step { NONE, PROMOTE, HUNG_UP, STOP }

    private var offhookSinceMs = -1L
    private var promoted = false

    val started: Boolean get() = offhookSinceMs >= 0

    fun offhook(nowMs: Long): Step {
        if (offhookSinceMs < 0) offhookSinceMs = nowMs
        return check(nowMs)
    }

    /** At the evidence timer, or any later report while off-hook. */
    fun check(nowMs: Long): Step =
        if (!promoted && started && nowMs - offhookSinceMs >= PLACED_CONNECT_EVIDENCE_MS) {
            promoted = true
            Step.PROMOTE
        } else {
            Step.NONE
        }

    /** Any state but off-hook: the call ended (a ringing call can only follow it). */
    fun notOffhook(nowMs: Long): Step = when {
        !started -> Step.NONE
        promoted || check(nowMs) == Step.PROMOTE -> Step.HUNG_UP
        else -> Step.STOP
    }
}

/**
 * The Android side of [PlacedCallEvidence]: one call-state listener per launcher-placed emergency call
 * (READ_PHONE_STATE, self-granted while calls are managed; without it the placing keeps its provisional
 * window). Main thread. Ends at the call's end, [START_TIMEOUT_MS] without a start, [MAX_WATCH_MS], the
 * next placing, or when our in-call service sees the call itself ([stop]: its own states decide then).
 * A process death ends it too: the placing then keeps what was recorded last.
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
        fun act(step: PlacedCallEvidence.Step) {
            if (current !== watch) return
            when (step) {
                PlacedCallEvidence.Step.NONE -> Unit
                PlacedCallEvidence.Step.PROMOTE -> CallPrefs.promotePlaced(app, placedAtMs)
                PlacedCallEvidence.Step.HUNG_UP -> {
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
                act(if (state == TelephonyManager.CALL_STATE_OFFHOOK) evidence.offhook(now) else evidence.notOffhook(now))
            }
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
        handler.postDelayed({ act(evidence.check(SystemClock.elapsedRealtime())) }, PLACED_CONNECT_EVIDENCE_MS + 500)
        handler.postDelayed({ if (!evidence.started) act(PlacedCallEvidence.Step.STOP) }, START_TIMEOUT_MS)
        handler.postDelayed({ act(PlacedCallEvidence.Step.STOP) }, MAX_WATCH_MS)
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
