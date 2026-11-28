package com.kidslauncher.mdm.lock

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import java.lang.ref.WeakReference

private const val LOG_TAG = "HomeRootStop"

/**
 * Design 141: the kiosk ends from its root. `LockTaskChrome.writeLockTask`'s unpin calls [stop] after the app block is
 * lifted and the stored switch is off, and before the list is emptied: Home - the kiosk's lock-task root (design 16,
 * `kiosk-escapes-and-updates.md` "Home stays the lock-task root") - stops lock task from its own task. AOSP's
 * `clearLockedTask` from the root clears every other locked task first, whatever its auth (a leftover
 * `BlockedAppActivity` task, `LAUNCHABLE_PRIV`, which the emptying never removes, included), then takes the root out
 * without clearing it: Home stays as it is, and the emptying that follows finds nothing locked.
 *
 * - Off the main thread, directly: `stopLockTask()` is one binder call with the activity's token, no foreground or
 *   thread check. No latch: the pass holds `LockTaskChrome`'s monitor, which the main thread takes itself (the LOCKED
 *   edge's pass, the "sos" yield) - a wait for main there would time out into the very path this fixes.
 * - The wait before the emptying ([settle], §3.5): on Android 14 and 15 the stop is oneway and returns before it has run,
 *   so the unpin reads `lockTaskModeState` until it isn't LOCKED, at most [KIOSK_END_STOP_SETTLE_MS] - a wait on
 *   system_server only, never on main. Not on 16+, where the stop is synchronous ([stopIsOneway]).
 * - Home registers in `onCreate` and unregisters in `onDestroy` unless `isChangingConfigurations` (a relaunch keeps the
 *   record and its token; the new instance registers again), and only while it is the registered one.
 * - The stuck check ([afterUnpin], §3.3): 1 s after a pass that found lock task LOCKED, lock task still on and not ours
 *   ([lockTaskStuck]) gets one retry from our resumed activity - after a launch in the gap before the posted stop
 *   (AOSP: it joined lock task and became the new root) its stop ends lock task -, then one log line if still stuck. The
 *   retry, as Home's stop, runs once per kiosk-off spell ([KioskEndSpell]): from a task that is no longer the root a stop
 *   clears that task.
 *   Nothing on the phone can end it then; the parent's remedy is a restart (Q1: log only).
 * - No Home in this process ([step], §3.4): with the screen on Home is brought (typed HOME, restarted in its root task -
 *   our package is still listed) and the list waits for its root stop (`HomeActivity.reconcileKioskMode`, which then asks
 *   for a chrome pass - only while that Home is awaited, [homeStopped]); a pass [KIOSK_END_HOME_WAIT_MS] later empties
 *   it regardless. One bring per kiosk-off spell.
 */
object HomeRootStop {

    private val main = Handler(Looper.getMainLooper())

    /** The live Home (`HomeActivity`, `singleTask`, typed HOME only - one instance). Main-thread writes. */
    @Volatile
    private var home: WeakReference<Activity>? = null

    /** Our resumed activity, for the stuck check's retry. Main-thread writes. */
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    /** Guards [log] and [spell]. */
    private val logLock = Any()
    private val log = KioskEndLogOnce()
    private val spell = KioskEndSpell()

    /** A stuck check is pending (one at a time). Main thread. */
    private var checkPending = false

    private var initialized = false

    private val tracker = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: Activity) {
            // The glance's lock task is its own (lockTaskEntry, lockLeave): never stopped from here.
            if (activity !is PinLockActivity) resumed = WeakReference(activity)
        }

        override fun onActivityPaused(activity: Activity) {
            if (resumed?.get() === activity) resumed = null
        }

        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
        override fun onActivityStarted(activity: Activity) {}
        override fun onActivityStopped(activity: Activity) {}
        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
        override fun onActivityDestroyed(activity: Activity) {}
    }

    /** From `PinLockRuntime.init` (main thread): the resumed-activity tracker for the retry. */
    fun init(app: Application) {
        if (initialized) return
        initialized = true
        app.registerActivityLifecycleCallbacks(tracker)
    }

    /** `HomeActivity.onCreate`. */
    fun register(activity: Activity) {
        home = WeakReference(activity)
    }

    /** `HomeActivity.onDestroy`, only when not `isChangingConfigurations`. */
    fun unregister(activity: Activity) {
        if (home?.get() === activity) home = null
    }

    /** Design 141 §3.1 step 3: Home's stop, from the pass's own thread. Never throws. */
    fun stop(context: Context): HomeRootStopAnswer {
        val activity = home?.get()
        val asked = synchronized(logLock) { spell.homeAsked }
        val answer = homeRootStopAnswer(activity != null, lockTaskState(context), PinLockRuntime.chromeLocked, asked) {
            stopFrom(activity)
        }
        val logIt = synchronized(logLock) {
            if (answer == HomeRootStopAnswer.ASKED) spell.asked(SystemClock.elapsedRealtime())
            log.answer(answer)
        }
        if (logIt) Log.i(LOG_TAG, "The kiosk's end: Home's stop $answer")
        return answer
    }

    /**
     * Home stopped lock task itself (`HomeActivity.reconcileKioskMode`, main thread): Home's stop counts as asked for this
     * spell (code QA M1 - never again from a task that may no longer be the root). Returns whether a brought Home was
     * awaited (§3.4) - only then does Home ask for the pass that empties the list.
     */
    fun homeStopped(): Boolean = synchronized(logLock) {
        spell.asked(SystemClock.elapsedRealtime())
        spell.awaitingHome
    }

    /**
     * Design 141 §3.5, after [step] answered EMPTY and before the emptying: when Home's stop went through less than
     * [KIOSK_END_STOP_SETTLE_MS] ago (this pass's ASKED, or Home's own stop whose pass this is) and lock task still reads
     * LOCKED, wait for it to read otherwise - on Android 14 and 15 `stopLockTask()` is oneway and returns before the stop
     * has run, and an emptying that gets there first removes Home's root task and leaves a blocked task locked alone
     * (emulator.md §6cg F1). Reads every [KIOSK_END_STOP_POLL_MS]; the wait waits on system_server alone (its stop and
     * its posted `performStopLockTask`), never on this process's main thread, so holding the chrome monitor here can't
     * deadlock with a main-thread taker - it waits at most the bound. Out of time: today's emptying, one log line per
     * spell. Only on 14 and 15 ([stopIsOneway]). Never throws.
     */
    fun settle(context: Context): StopSettle {
        // Code QA L1: on 16+ the stop is synchronous - no wait, so §2's gap stays one binder round trip.
        if (!stopIsOneway(Build.VERSION.SDK_INT)) return StopSettle.NOT_ASKED
        val askedAt = synchronized(logLock) { spell.askedAtMs } ?: return StopSettle.NOT_ASKED
        var reads = 0
        while (true) {
            val since = SystemClock.elapsedRealtime() - askedAt
            val settle = stopSettle(since, waited = reads > 0) { lockTaskState(context) }
            when (settle) {
                StopSettle.WAIT -> {
                    reads++
                    // Uninterruptible: an interrupt can't cut the wait short into the race.
                    SystemClock.sleep(KIOSK_END_STOP_POLL_MS)
                }
                StopSettle.ENDED -> {
                    if (reads > 0) Log.i(LOG_TAG, "The kiosk's end: lock task ended $since ms after Home's stop, then the list")
                    return settle
                }
                StopSettle.TIMED_OUT -> {
                    if (synchronized(logLock) { log.settleTimedOut() }) {
                        Log.w(LOG_TAG, "The kiosk's end: lock task still LOCKED $since ms after Home's stop - the list is emptied regardless")
                    }
                    return settle
                }
                StopSettle.NOT_ASKED -> return settle
            }
        }
    }

    /**
     * Design 141 §3.4, after [stop]: whether the pass empties the list now ([unpinStep]). On [UnpinStep.BRING_HOME] Home
     * is brought and a chrome pass follows in [KIOSK_END_HOME_WAIT_MS].
     */
    fun step(context: Context, answer: HomeRootStopAnswer): UnpinStep {
        val now = SystemClock.elapsedRealtime()
        val interactive = interactive(context)
        val step = synchronized(logLock) {
            val brought = spell.broughtAtMs
            unpinStep(
                answer,
                interactive = interactive,
                broughtThisSpell = brought != null,
                timerDue = brought != null && now - brought >= KIOSK_END_HOME_WAIT_MS,
            ).also { step ->
                when (step) {
                    UnpinStep.BRING_HOME -> spell.brought(now)
                    UnpinStep.EMPTY -> spell.emptied()
                    UnpinStep.WAIT_FOR_HOME -> Unit
                }
            }
        }
        if (step == UnpinStep.BRING_HOME) {
            val app = context.applicationContext
            Log.i(LOG_TAG, "The kiosk's end: no Home in this process - Home brought, the list waits for its stop")
            HomeFront.bring(app, "kiosk end")
            main.postDelayed({ PinLockRuntime.requestChromePass(app) }, KIOSK_END_HOME_WAIT_MS)
        }
        return step
    }

    /** Design 141 §3.3, after the emptying: the stuck check 1 s later when [answer] found lock task LOCKED. */
    fun afterUnpin(context: Context, answer: HomeRootStopAnswer) {
        if (!stuckCheckDue(answer)) return
        val app = context.applicationContext
        main.post {
            if (checkPending) return@post
            checkPending = true
            main.postDelayed({ check(app, answer, afterRetry = false) }, KIOSK_END_STUCK_CHECK_MS)
        }
    }

    /** A kiosk-on pass (the pin): the next kiosk-off spell brings Home and logs again. */
    fun kioskOn() {
        synchronized(logLock) {
            spell.kioskOn()
            log.kioskOn()
        }
    }

    /** Main thread. [scheduledBy]: the answer of the pass that scheduled the check (code QA L3); [afterRetry]: this is
     * the check after the retry. */
    private fun check(app: Context, scheduledBy: HomeRootStopAnswer, afterRetry: Boolean) {
        val stuck = lockTaskStuck(
            lockTaskState(app),
            kioskOn = LockTaskChrome.kioskOn,
            chromeLocked = PinLockRuntime.chromeLocked,
            voipPinned = VoipCalls.pinnedPackage != null,
        )
        if (!stuck) {
            checkPending = false
            if (afterRetry) Log.i(LOG_TAG, "The kiosk's end: lock task ended after the retry")
            return
        }
        // One retry per kiosk-off spell (code QA L1), so its line is once per spell too.
        if (!afterRetry && synchronized(logLock) { spell.takeRetry() }) {
            // NO_HOME here: none of our activities is resumed.
            val answer = stopFrom(resumed?.get())
            synchronized(logLock) { spell.retried(answer) }
            Log.i(LOG_TAG, "The kiosk's end: lock task still on, retry from our resumed activity $answer")
            main.postDelayed({ check(app, scheduledBy, afterRetry = true) }, KIOSK_END_STUCK_CHECK_MS)
            return
        }
        checkPending = false
        val (logIt, retry) = synchronized(logLock) { log.stuck() to spell.retryAnswer }
        if (logIt) {
            Log.w(LOG_TAG, "Lock task still on after the kiosk's end (Home's stop: $scheduledBy, retry: ${retry ?: "none"})")
        }
    }

    private fun stopFrom(activity: Activity?): HomeRootStopAnswer {
        if (activity == null) return HomeRootStopAnswer.NO_HOME
        return try {
            activity.stopLockTask()
            HomeRootStopAnswer.ASKED
        } catch (e: SecurityException) {
            HomeRootStopAnswer.REFUSED
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The kiosk's end: stopLockTask failed", e)
            HomeRootStopAnswer.FAILED
        }
    }

    private fun lockTaskState(context: Context): LockTaskState = try {
        lockTaskStateOf(context.getSystemService(ActivityManager::class.java)?.lockTaskModeState ?: ActivityManager.LOCK_TASK_MODE_NONE)
    } catch (e: Exception) {
        LockTaskState.NONE
    }

    /** Unknown reads as off: no bring, today's emptying. */
    private fun interactive(context: Context): Boolean = try {
        context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
    } catch (e: Exception) {
        false
    }
}
