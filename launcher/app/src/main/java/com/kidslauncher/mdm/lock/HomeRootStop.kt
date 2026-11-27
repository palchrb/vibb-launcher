package com.kidslauncher.mdm.lock

import android.app.Activity
import android.app.ActivityManager
import android.app.Application
import android.content.Context
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
 * - Home registers in `onCreate` and unregisters in `onDestroy` unless `isChangingConfigurations` (a relaunch keeps the
 *   record and its token; the new instance registers again), and only while it is the registered one.
 * - The stuck check ([afterUnpin], §3.3): 1 s after a pass that found lock task LOCKED, lock task still on and not ours
 *   ([lockTaskStuck]) gets one retry from our resumed activity - after a launch in the gap before the posted stop
 *   (AOSP: it joined lock task and became the new root) its stop ends lock task -, then one log line if still stuck.
 *   Nothing on the phone can end it then; the parent's remedy is a restart (Q1: log only).
 * - No Home in this process ([step], §3.4): with the screen on Home is brought (typed HOME, restarted in its root task -
 *   our package is still listed) and the list waits for its root stop (`HomeActivity.reconcileKioskMode`, which then asks
 *   for a chrome pass); a pass [KIOSK_END_HOME_WAIT_MS] later empties it regardless. One bring per kiosk-off spell.
 */
object HomeRootStop {

    private val main = Handler(Looper.getMainLooper())

    /** The live Home (`HomeActivity`, `singleTask`, typed HOME only - one instance). Main-thread writes. */
    @Volatile
    private var home: WeakReference<Activity>? = null

    /** Our resumed activity, for the stuck check's retry. Main-thread writes. */
    @Volatile
    private var resumed: WeakReference<Activity>? = null

    /** Guards [log]. */
    private val logLock = Any()
    private val log = KioskEndLogOnce()

    /** §3.4: when Home was brought in this kiosk-off spell (elapsed ms), `null` = not brought. */
    @Volatile
    private var broughtAtMs: Long? = null

    /** The last pass's answer, for the stuck line. */
    @Volatile
    private var lastAnswer: HomeRootStopAnswer? = null

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
        val answer = homeRootStopAnswer(activity != null, lockTaskState(context), PinLockRuntime.chromeLocked) {
            stopFrom(activity)
        }
        lastAnswer = answer
        if (synchronized(logLock) { log.answer(answer) }) Log.i(LOG_TAG, "The kiosk's end: Home's stop $answer")
        return answer
    }

    /**
     * Design 141 §3.4, after [stop]: whether the pass empties the list now ([unpinStep]). On [UnpinStep.BRING_HOME] Home
     * is brought and a chrome pass follows in [KIOSK_END_HOME_WAIT_MS].
     */
    fun step(context: Context, answer: HomeRootStopAnswer): UnpinStep {
        val now = SystemClock.elapsedRealtime()
        val brought = broughtAtMs
        val step = unpinStep(
            answer,
            interactive = interactive(context),
            broughtThisSpell = brought != null,
            timerDue = brought != null && now - brought >= KIOSK_END_HOME_WAIT_MS,
        )
        if (step == UnpinStep.BRING_HOME) {
            broughtAtMs = now
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
            main.postDelayed({ check(app, retry = null) }, KIOSK_END_STUCK_CHECK_MS)
        }
    }

    /** A kiosk-on pass (the pin): the next kiosk-off spell brings Home and logs again. */
    fun kioskOn() {
        broughtAtMs = null
        synchronized(logLock) { log.kioskOn() }
    }

    /** Main thread. [retry]: `null` on the first check, else the retry's answer. */
    private fun check(app: Context, retry: HomeRootStopAnswer?) {
        val stuck = lockTaskStuck(
            lockTaskState(app),
            kioskOn = LockTaskChrome.kioskOn,
            chromeLocked = PinLockRuntime.chromeLocked,
            voipPinned = VoipCalls.pinnedPackage != null,
        )
        if (!stuck) {
            checkPending = false
            if (retry != null) Log.i(LOG_TAG, "The kiosk's end: lock task ended after the retry ($retry)")
            return
        }
        if (retry == null) {
            // NO_HOME here: none of our activities is resumed.
            val answer = stopFrom(resumed?.get())
            Log.i(LOG_TAG, "The kiosk's end: lock task still on, retry from our resumed activity $answer")
            main.postDelayed({ check(app, answer) }, KIOSK_END_STUCK_CHECK_MS)
            return
        }
        checkPending = false
        if (synchronized(logLock) { log.stuck() }) {
            Log.w(LOG_TAG, "Lock task still on after the kiosk's end (Home's stop: $lastAnswer, retry: $retry)")
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
