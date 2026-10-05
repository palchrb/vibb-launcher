package com.kidslauncher.mdm.timerules

import android.app.Activity
import android.app.Application
import android.app.KeyguardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.KidModeEnforcer
import com.kidslauncher.mdm.server.currentPolicyDecision

private const val LOG_TAG = "ScreenTimeTracker"

/** While counting, usage is saved this often (a process death loses at most this much). */
private const val CHECKPOINT_MS = 60_000L

/**
 * Counts screen time (handy step 6): the screen is on and unlocked, unless one of our free screens
 * (lock screen, phone book, our in-call screen, Settings - [FREE_SCREENS]) is in front and not
 * sharing the screen ([screenTimeCounts]). Calls elsewhere and Home count (QA step 6 #1, #2).
 * Event-driven, never polling while the screen is off: [update] runs on screen on/off and user
 * present (CommandListenerService), on our activities' resume/pause and on every lock re-check. While counting, a checkpoint saves usage every [CHECKPOINT_MS] and a
 * one-shot timer fires when the budget runs out (then [TimeRulesRuntime.recheck] locks). Main
 * thread only.
 */
object ScreenTimeTracker {
    private val handler = Handler(Looper.getMainLooper())
    /** Our free screens currently resumed (see [FREE_SCREENS]). */
    private val resumedFree = mutableSetOf<Activity>()
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
                if (activity.javaClass.name in FREE_SCREENS) resumedFree += activity
                update(activity.applicationContext)
            }

            override fun onActivityPaused(activity: Activity) {
                resumedFree -= activity
                update(activity.applicationContext)
            }

            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {
                resumedFree -= activity
            }
        })
    }

    private fun appInUse(context: Context): Boolean = screenTimeCounts(
        interactive = context.getSystemService(PowerManager::class.java)?.isInteractive == true,
        keyguardLocked = context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked != false,
        freeScreenInFront = resumedFree.isNotEmpty(),
        freeScreenSharesScreen = resumedFree.any { it.isInMultiWindowMode || it.isInPictureInPictureMode },
        pinLocked = com.kidslauncher.mdm.lock.PinLockRuntime.chromeLocked,
    )

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
