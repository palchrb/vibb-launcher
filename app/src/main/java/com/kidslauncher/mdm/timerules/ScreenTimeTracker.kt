package com.kidslauncher.mdm.timerules

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.media.AudioManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.server.KidModeEnforcer
import com.kidslauncher.mdm.server.currentPolicyDecision

private const val LOG_TAG = "ScreenTimeTracker"

/** While counting, usage is saved this often (a process death loses at most this much). */
private const val CHECKPOINT_MS = 60_000L

/**
 * Counts screen time (handy step 6): the screen is on and unlocked, no call is going on (our
 * in-call list, or the audio mode says a call or VoIP session), and none of our own activities is
 * in front (Home, phone book, lock screen, Settings don't count) - so the time an app is in use.
 * Event-driven, never polling while the screen is off: [update] runs on screen on/off and user
 * present (CommandListenerService), on our activities' resume/pause, on audio-mode changes and on
 * every lock re-check. While counting, a checkpoint saves usage every [CHECKPOINT_MS] and a
 * one-shot timer fires when the budget runs out (then [TimeRulesRuntime.recheck] locks). Main
 * thread only.
 */
object ScreenTimeTracker {
    private val handler = Handler(Looper.getMainLooper())
    private var resumedOwnActivities = 0
    private var countingSinceElapsed: Long? = null
    private var appContext: Context? = null

    private val checkpoint = object : Runnable {
        override fun run() {
            appContext?.let { update(it) }
        }
    }

    private val budgetRunsOut = Runnable { appContext?.let { TimeRulesRuntime.recheck(it) } }

    fun init(app: Application) {
        appContext = app.applicationContext
        app.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityResumed(activity: Activity) {
                resumedOwnActivities++
                update(activity.applicationContext)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedOwnActivities = (resumedOwnActivities - 1).coerceAtLeast(0)
                update(activity.applicationContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
        try {
            app.getSystemService(AudioManager::class.java)
                ?.addOnModeChangedListener(app.mainExecutor) { update(app.applicationContext) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No audio-mode listener; calls are only seen through our in-call service", e)
        }
    }

    private fun appInUse(context: Context): Boolean {
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true
        val locked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false
        val mode = context.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        val inCall = OngoingCalls.calls.isNotEmpty() ||
            mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION ||
            mode == AudioManager.MODE_CALL_SCREENING || mode == AudioManager.MODE_RINGTONE
        return interactive && !locked && !inCall && resumedOwnActivities == 0
    }

    /** Folds the running stretch into the ledger and decides whether to keep counting. */
    fun update(context: Context) {
        appContext = context.applicationContext
        handler.removeCallbacks(checkpoint)
        handler.removeCallbacks(budgetRunsOut)
        try {
            val now = SystemClock.elapsedRealtime()
            countingSinceElapsed?.let { TimeRulesRuntime.accrueScreenTime(context, it) }
            countingSinceElapsed = null
            if (!appInUse(context)) return
            countingSinceElapsed = now
            handler.postDelayed(checkpoint, CHECKPOINT_MS)
            val policy = KidModeEnforcer.timePolicyOf(currentPolicyDecision().policy) ?: return
            val remaining = budgetUse(TimeRulesRuntime.ledger(context, policy), policy)?.remainingMs ?: return
            // Already used up: the lock is (being) applied by the re-check that got us here.
            if (remaining > 0) handler.postDelayed(budgetRunsOut, remaining + 1_000L)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Screen-time update failed", e)
        }
    }
}
