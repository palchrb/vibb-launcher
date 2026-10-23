package com.kidslauncher.mdm.lock

import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.ParentPin
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.server.ParentPinResult
import com.kidslauncher.mdm.server.PinHash
import com.kidslauncher.mdm.server.dto.KidLock
import com.kidslauncher.mdm.server.dto.LockStateReport
import com.kidslauncher.mdm.server.dto.VoipRang
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.timerules.ScreenTimeTracker
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.server.emergencySosPackage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "PinLock"

/** Status-report capability: this launcher has handy's own PIN lock and reads `kid_lock`. */
const val PIN_LOCK_CAPABILITY = "pin_lock_v1"

/**
 * [PinLockRuntime.verifyCommandPin]'s check (design 59 §3.5, Q59-1), outside the object so the JVM test runs it: the
 * PIN against [kidLock]'s own hash and salt - the fresh policy's -, exactly its length in digits. Reads and writes no
 * backoff. This file stays the only `PinHash.verify(` caller for the kid's PIN (`ParentPinCallersTest`).
 */
internal fun commandPinVerifies(kidLock: KidLock, pin: String): Boolean {
    val cfg = KidLockConfig(kidLock.pinHash, kidLock.pinSalt, kidPinLength(kidLock.pinLength), kidLock.pinRev)
    return cfg.usable && commandPinShape(pin, cfg.length) && PinHash.verify(pin, cfg.hashHex, cfg.saltHex)
}

/**
 * Handy's own PIN lock (step 10): the glue around the pure [step] state machine. Holds the mode
 * in memory (main thread), the screen receiver - registered process-wide from `Application`
 * (QA 10 #4), not by the anchor service - the re-front loop, PIN checks and the status report.
 * Only ever runs after the first unlock: nothing here is direct-boot-aware.
 */
object PinLockRuntime {

    @Volatile
    var mode: LockMode = LockMode.DISABLED
        private set

    /** LOCKED: the lock-task/status-bar chrome is the locked one ([LockTaskChrome]). */
    val chromeLocked: Boolean get() = mode == LockMode.LOCKED

    @Volatile
    private var inactive: LockInactive? = LockInactive.NO_PIN

    @Volatile
    private var config: KidLockConfig? = null

    private val handler = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var initialized = false

    /** PinLockActivity is resumed. */
    @Volatile
    var lockResumed = false
        private set

    /** Design 59: the live lock's own unlock after Android's keyguard went (`USER_PRESENT`), so a glance row's
     * target opens; set by `PinLockActivity` while it exists. Main thread. */
    var androidUnlockHook: (() -> Unit)? = null

    /** Screen-offs in this process (main thread): a PIN or parent-code check captures it when it
     * starts, and an Ok that comes back after a screen-off is dropped (design 24 QA #4). */
    @Volatile
    var screenOffs = 0
        private set

    /** This process's lock mode is decided ([decide], design 16c) - before that Home treats the
     * state as unknown ([homeShowsContent]). */
    @Volatile
    var decided = false
        private set

    /** The lock was active when this process started ([decide]). */
    private var startActive = false

    /** HomeActivity has resumed in this process (16c): our Home is up, the boot's start of it
     * isn't needed ([bootHomeAction]). */
    @Volatile
    var homeShown = false
        private set

    /** The lock state was readable at this process's start ([decide]) - unreadable: no boot wait. */
    private var startReadable = true

    /**
     * Design 16e: the boot's wait for the breathing mark ([bootMarkStep]) - [BootMarkState.Over]
     * unless [init] found the first process start of a boot. Only [show] holds the lock for it;
     * the chrome never waits. Main thread.
     */
    private var bootMark: BootMarkState = BootMarkState.Over

    /** Home's last resume was locked - the night ground - and no stop or focus loss since (16e,
     * an input of [homeMarkUp]). Main thread. */
    private var homeResumedLocked = false

    /** When Home's mark was first verifiably on screen in this process ([homeMarkShown]: locked,
     * the night ground drawn, the window focused; elapsed realtime) - the 3 s count from it
     * (qa-16e-fix2 #1). Main thread. */
    private var homeShownAtMs: Long? = null

    /** Home's last pause (`null` = none) and locked resume (elapsed realtime), for [homeMarkUp]. Main
     * thread. */
    private var homePausedAtMs: Long? = null
    private var homeResumedAtMs: Long? = null

    /** Home's mark is up now ([homeMarkUp]). Main thread. */
    private fun homeMarkUpNow(now: Long): Boolean = homeMarkUp(homeResumedLocked, homePausedAtMs, homeResumedAtMs, now)

    /** A pause of Home ends the wait only if Home doesn't resume within the grace (16e). */
    private val homePauseCheck = Runnable {
        val app = appContext ?: return@Runnable
        if (!homeMarkUpNow(SystemClock.elapsedRealtime())) {
            homeResumedLocked = false
            if (bootMark != BootMarkState.Over) endBootMark(app, "Home paused")
        }
    }

    /** The boot count [init] saw for the boot mark (-1 = unknown). */
    private var markBoot = -1

    /** The boot cover's first frame in this boot (elapsed realtime), read once off the main thread
     * at [init] (qa-16e-code #3); `null` = none, or not read yet - then 3 s from the ask. */
    @Volatile
    private var coverFrameMs: Long? = null

    /** Ends the wait at its end: 3 s after the mark's start, or - Home's mark never shown -
     * [LOCK_FALLBACK_MS] after the wait began (16e). */
    private val bootMarkBackstop = Runnable {
        val why = if (bootMark is BootMarkState.Waiting) BOOT_MARK_NEVER_SHOWN else BOOT_MARK_TIME_UP
        appContext?.let { endBootMark(it, why) }
    }

    private val modeListeners = mutableListOf<() -> Unit>()
    /** Told on the main thread after a chrome pass ([refreshChrome]) - the lock enters lock task
     * once its package is pinned (kiosk off). */
    private val chromeListeners = mutableListOf<() -> Unit>()

    /**
     * The runtime's chrome passes ([LockTaskChrome.refresh]) other than the LOCKED edge's
     * ([refreshChromeNow]), off the main thread (16c): each is ~8 DPM binder calls that persist
     * the policy file, and it waits for any apply() pass holding [LockTaskChrome]. One at a time,
     * in order; each reads the mode under the monitor when it runs, so the newest state wins.
     */
    private val chromeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "lock-chrome").apply { isDaemon = true }
    }
    private var refrontAttempt = 0
    private var yielding: String? = null
    @Volatile
    private var exemptYields = 0
    @Volatile
    private var emergencyFlowUntilElapsed = 0L
    /** The last screen-on (elapsed ms) and a probable Emergency SOS's end ([sosGestureLikely]). Main thread. */
    private var lastScreenOnElapsed: Long? = null
    private var sosFlowUntilElapsed = 0L

    /** Design 59d N1: the mark was set at a pause and not logged yet - the pause's check logs it if it still holds
     * ([sosPauseMarkLogDue]). Main thread. */
    private var sosMarkUnlogged = false

    /** Our resumed activities other than the lock (bug 25b-H). Main thread. */
    private var ownOthersResumed = 0

    // ---- design 59b §3: the face's loss rule ([androidLossStep]) ----------------------------------------------------

    /** The face's re-fronts since the last screen-off or `USER_PRESENT` - the cap's count ([ANDROID_REFRONT_CAP]; a
     * resume, pause or stop never zeroes it, unlike [refrontAttempt]). Main thread. */
    private var androidRefronts = 0

    /** What the glance last lost the front to ("paused"/"stopped"), for the re-front's log line. Main thread. */
    private var androidLossWhy = "lost the front"

    /** The last of our own starts ([noteOwnStart], elapsed; `null` = none). Any thread. */
    @Volatile
    private var ownStartAt: Long? = null

    /** The open ask for Android's PIN pad and the last answer or close of one (`PinLockActivity`, [noteAndroidAsk];
     * elapsed). Main thread. */
    private var androidAskAt: Long? = null
    private var androidAnsweredAt: Long? = null

    /** qa-59b-code B-3: a *cancelled* ask's start (elapsed) - the pad's Emergency button may hide the bouncer with a
     * cancel, and Android's Emergency dialer then stops the glance: still ours for [ANDROID_ASK_OPEN_MS] from the ask.
     * Cleared by a new ask and the screen-off. Main thread. */
    private var androidCancelledAskAt: Long? = null

    /** qa-59b-code B-3: the pending re-front check was scheduled by a fresh pause or stop of the glance (consumed by the
     * check). Main thread. */
    private var androidLossPending = false

    /** qa-59b-code B-5: the face already waited once for `USER_PRESENT` with Android open ([RefrontAction.AndroidWait]).
     * Main thread. */
    private var androidWaited = false

    /**
     * Design 59b §3 step 3 (Q59b-8): one of our own starts - an activity of ours ([startOwn]) or a call app's intent sent
     * from the lock (`VoipCalls.send`) - so the face's loss rule doesn't cover it. The one stamping point.
     */
    fun noteOwnStart(why: String) {
        ownStartAt = SystemClock.elapsedRealtime()
        if (AndroidLock.faceOn) Log.d(ANDROID_LOCK_TAG, "own start: $why")
    }

    /** Every start of one of our own screens over the glance (InCallActivity, VoipWakeActivity, the time-rule screen) goes
     * through here ([noteOwnStart]) - the glance's own start in [show] is the one exception (a source test). */
    fun startOwn(context: Context, intent: Intent, why: String) {
        noteOwnStart(why)
        context.startActivity(intent)
    }

    /** `PinLockActivity`'s ask for Android's PIN pad opened ([open]) or closed (answered, recovered, dropped). Main thread. */
    fun noteAndroidAsk(open: Boolean, cancelled: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (open) {
            androidAskAt = now
            androidCancelledAskAt = null
        } else {
            if (androidAskAt != null) {
                androidAnsweredAt = now
                // B-3: a cancel (the pad's Emergency button) stays ours for the ask's whole window.
                if (cancelled) androidCancelledAskAt = androidAskAt
            }
            androidAskAt = null
        }
    }
    private val ownScreens = object : android.app.Application.ActivityLifecycleCallbacks {
        override fun onActivityResumed(activity: android.app.Activity) {
            if (activity !is PinLockActivity) ownOthersResumed++
        }

        override fun onActivityPaused(activity: android.app.Activity) {
            if (activity !is PinLockActivity) ownOthersResumed = (ownOthersResumed - 1).coerceAtLeast(0)
        }

        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) {}
        override fun onActivityStarted(activity: android.app.Activity) {}
        override fun onActivityStopped(activity: android.app.Activity) {}
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) {}
        override fun onActivityDestroyed(activity: android.app.Activity) {}
    }
    private var emergencyCallSeen = false
    private var rememberedAlarmMs: Long? = null
    private var callsSeen = false
    /** This process healed the status bar at a screen-on ([HealTrigger.FIRST_SCREEN_ON]). Main thread. */
    private var screenOnHealed = false
    /** The last [HealTrigger.LOCK_RESUMED] heal (elapsed ms). Main thread. */
    private var lockResumedHealAt: Long? = null

    /** The last [HealTrigger.SYSTEM_HOME] heal (elapsed ms). Main thread. */
    private var systemHomeHealAt: Long? = null

    /** Design 59d §8.16: the last gesture ask marked ([gestureAskDue]; elapsed ms), whether the lock's resume still has
     * to take it ([takeGestureAsk]), and the last notice that came ([noticeCame]). Main thread. */
    private var gestureAskAt: Long? = null
    private var gestureAskPending = false
    private var lastNoticeElapsed: Long? = null

    /** A screen-off heal ran since the lock last resumed (main thread, bug 25b-C). */
    private var screenOffHealed = false

    /** Design 32 step 1 item 4: the lock is the top task since its last lock-up ([lockOnTopAfter],
     * best effort) - the next screen-off doesn't start it again. Main thread. */
    private var lockOnTop = false

    /** The ScreenOn backstop's start onto the lock on top (elapsed), and its new-intent pause held
     * ([newIntentPauseKept]). Main thread. */
    private var backstopOntoTopAt: Long? = null
    private var newIntentPauseHeld = false

    /**
     * Design 32 step 1 item 3 (QA M1): a HOME_THEN_LOCK lock-up since the last interactive resume of
     * the lock or Home, and ended by every other Home start ([HomeFront.bring]) - Home's resume
     * while dark then only shows its night ground ([homeDarkResume]). Main thread.
     */
    @Volatile
    var darkLockUp = false
        private set

    fun addModeListener(listener: () -> Unit) { modeListeners += listener }
    fun removeModeListener(listener: () -> Unit) { modeListeners -= listener }
    fun addChromeListener(listener: () -> Unit) { chromeListeners += listener }
    fun removeChromeListener(listener: () -> Unit) { chromeListeners -= listener }

    // ---- start-up -------------------------------------------------------------------------

    /**
     * Design 16c: this process's lock mode, decided first thing in the unlocked setup (one small
     * preferences file) - before any activity of ours exists. The stock launcher's hand-over at
     * boot queues our Home's start ahead of [init]'s posted ProcessStart, and that Home saw the
     * lock DISABLED and showed the contacts and apps. Fail closed: LOCKED when the last apply left
     * the lock active. [init]'s ProcessStart does the rest (show, chrome, camera). Main thread.
     */
    fun decide(context: Context) {
        if (decided) return
        val app = context.applicationContext
        startActive = lockActiveAtStart({
            startReadable = false
            Log.e(LOG_TAG, "Lock state unreadable - starting LOCKED", it)
        }) {
            PinLockStore.active(app) && PinLockStore.guard(app).trippedAtMs == null
        }
        // Design 59 §3.1 (Q59-2, L-4): every CE start stores the boot count; with Android's lock as the gate the
        // boot's first CE start is UNLOCKED (CE unlocked = the Child PIN was typed), every later start LOCKED.
        // Unreadable: own mode's rule (LOCKED when active).
        startAndroidBoot = try {
            val boot = com.kidslauncher.mdm.server.BootClock.bootCount()
            val stored = PinLockStore.swapAndroidBoot(app, boot)
            val requested = PinLockStore.keyguardRequested(app)
            val face = startActive && requested && AndroidLock.deviceSecure(app)
            AndroidLock.atProcessStart(requested, face, firstCeStart(boot, stored))
            processStartUnlocked(face, boot, stored)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Android's lock state unreadable at the start - our own rule", e)
            false
        }
        mode = step(LockMode.DISABLED, LockEvent.ProcessStart(startActive, interactive = false, androidBoot = startAndroidBoot)).mode
        decided = true
        Log.i(LOG_TAG, "Process start: $mode (decided before any screen${if (startAndroidBoot) ", the boot's first unlock of Android's lock" else ""})")
    }

    /** Design 59: this process is the boot's first CE start with Android's lock as the gate ([processStartUnlocked]). */
    private var startAndroidBoot = false

    /** For Home while [decided] is false (16c): the stored lock state or the cached policy says a
     * kid PIN is set. Unreadable counts as set. */
    fun pinSetStored(context: Context): Boolean = try {
        val app = context.applicationContext
        PinLockStore.active(app) || PinLockStore.config(app) != null ||
            (com.kidslauncher.mdm.server.cachedPolicy() as? com.kidslauncher.mdm.server.CachedPolicy.Ok)?.policy?.kidLock != null
    } catch (e: Exception) {
        true
    }

    /** Any Home start but the lock-up's own ([HomeFront.bring]): Home's resume is the usual one. */
    fun endDarkLockUp() {
        darkLockUp = false
    }

    /** HomeActivity resumed (16c, [homeShown]); [interactive]: a lock-up's dark mark ends (design 32). */
    fun onHomeResumed(interactive: Boolean) {
        homeShown = true
        if (interactive) darkLockUp = false
    }

    /** HomeActivity resumed showing the mark (16e, the locked resume): the boot's lock may wait for
     * it now; a pause just before (a re-delivered HOME intent) doesn't count. Main thread. */
    fun onHomeMarkUp() {
        homeResumedLocked = true
        homeResumedAtMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(homePauseCheck)
    }

    /**
     * From Home at its window focus, its night ground's first frame and its locked resume (16e,
     * qa-16e-fix2 #1): when the mark is verifiably on screen ([homeMarkShown]: resumed locked, the
     * ground drawn, the window focused - the old front is gone), the first time is the mark's start,
     * and a wait for it holds the lock 3 s from there ([bootMarkShown]) - a slow boot no longer eats
     * them (emulator 2026-10-09). Main thread.
     */
    fun onHomeMarkShown(context: Context, groundDrawn: Boolean, focused: Boolean) {
        if (homeShownAtMs != null || !homeMarkShown(homeResumedLocked, groundDrawn, focused)) return
        val now = SystemClock.elapsedRealtime()
        homeShownAtMs = now
        val before = bootMark
        if (!before.holdsLock) return
        val next = bootMarkShown(before, now, coverFrameMs, now)
        if (next == BootMarkState.Over) {
            endBootMark(context.applicationContext, if (coverFrameMs != null) BOOT_MARK_TIME_UP else BOOT_MARK_NEVER_SHOWN)
        } else {
            moveBootMark(before, next, now, "")
        }
    }

    /**
     * HomeActivity paused (16e, qa-16e-code #2): something may cover the mark - a translucent
     * activity, a dialog. The wait ends only if Home isn't resumed again within
     * [HOME_PAUSE_GRACE_MS]: the system's own HOME start after boot re-delivers the intent, which
     * pauses and resumes Home at once and ended the mark after 0.1 s (emulator 2026-10-09). Not for
     * a recreation. Main thread.
     */
    fun onHomePaused(changingConfigurations: Boolean) {
        if (changingConfigurations) return
        homePausedAtMs = SystemClock.elapsedRealtime()
        handler.removeCallbacks(homePauseCheck)
        handler.postDelayed(homePauseCheck, HOME_PAUSE_GRACE_MS)
    }

    /**
     * HomeActivity stopped or lost window focus (16e, qa-16e-code #2): something else is in front -
     * an app, Recents, the assistant, the power menu, a call or alarm screen - so the mark isn't up
     * any more and the boot's wait ends now. Not for a recreation. Main thread.
     */
    fun onHomeCovered(context: Context, changingConfigurations: Boolean, why: String) {
        if (changingConfigurations) return
        homeResumedLocked = false
        handler.removeCallbacks(homePauseCheck)
        if (bootMark != BootMarkState.Over) endBootMark(context.applicationContext, why)
    }

    // ---- the boot mark (design 16e) ------------------------------------------------------------

    /** [BootMarkState.Due] at the first process start of a boot ([bootMarkDue]); stores the boot
     * count. Unreadable: no wait. */
    private fun bootMarkAtStart(context: Context): BootMarkState = try {
        val boot = BootClock.bootCount()
        markBoot = boot
        val stored = PinLockStore.swapMarkBoot(context, boot)
        val due = bootMarkDue(boot, stored, startActive, startReadable, SystemClock.elapsedRealtime())
        if (due) BootMarkState.Due else BootMarkState.Over
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Boot count unreadable - the lock doesn't wait for the mark", e)
        BootMarkState.Over
    }

    /**
     * An ask for the lock ([bootMarkStep]): whether the boot's mark holds it now - only while Home's
     * mark is up ([homeMarkUp]). The first held ask waits for the mark to be shown (at most
     * [LOCK_FALLBACK_MS]) or, with its start known, fixes the end 3 s after it, and posts the
     * backstop. Main thread.
     */
    private fun bootMarkHolds(context: Context, ask: LockAsk): Boolean {
        val before = bootMark
        if (before == BootMarkState.Over) return false
        val app = context.applicationContext
        val now = SystemClock.elapsedRealtime()
        val up = homeMarkUpNow(now)
        val next = bootMarkStep(before, ask, up, now, coverFrameMs, homeShownAtMs) {
            val telecom = telecomInCall(app)
            bootMarkExempt(ourCall(), telecom, emergencyFlowNow(telecom), alarmNow(app), VoipCalls.phase)
        }
        moveBootMark(before, next.state, now, bootMarkOverWhy(before, ask, up, now, bootMarkStartMs(coverFrameMs, homeShownAtMs)))
        return !next.show
    }

    /** Home was started first (design 16, `showLockLater`): the wait begins now ([bootMarkClock]),
     * so the backstop comes whether Home draws or not. Main thread. */
    private fun startBootMarkClock() {
        val before = bootMark
        val now = SystemClock.elapsedRealtime()
        moveBootMark(before, bootMarkClock(before, now, coverFrameMs, homeShownAtMs), now, BOOT_MARK_TIME_UP)
    }

    /** [bootMark] to [state]: entering Waiting or Holding (re)posts the backstop for its end, leaving
     * them removes it and logs how long the mark was on screen. */
    private fun moveBootMark(before: BootMarkState, state: BootMarkState, now: Long, why: String) {
        bootMark = state
        if (state == before) return
        when (state) {
            is BootMarkState.Waiting -> {
                Log.i(LOG_TAG, "Boot mark: the lock waits for Home's mark to show, at most ${state.deadlineMs - now} ms")
                handler.removeCallbacks(bootMarkBackstop)
                handler.postDelayed(bootMarkBackstop, state.deadlineMs - now)
            }
            is BootMarkState.Holding -> {
                Log.i(LOG_TAG, "Boot mark: the lock waits ${state.untilMs - now} ms")
                handler.removeCallbacks(bootMarkBackstop)
                handler.postDelayed(bootMarkBackstop, state.untilMs - now)
            }
            BootMarkState.Over -> {
                handler.removeCallbacks(bootMarkBackstop)
                if (before.holdsLock) logBootMarkOver(why, now)
            }
            BootMarkState.Due -> Unit
        }
    }

    /** Nothing waits for the mark any more ([why]: the lock is up, an exempt screen). Returns
     * whether a lock was held. Main thread. */
    private fun skipBootMark(why: String): Boolean {
        val before = bootMark
        if (before == BootMarkState.Over) return false
        bootMark = BootMarkState.Over
        handler.removeCallbacks(bootMarkBackstop)
        handler.removeCallbacks(homePauseCheck)
        if (before.holdsLock) logBootMarkOver(why, SystemClock.elapsedRealtime())
        return before.holdsLock
    }

    /** The wait is over: why, and how long the mark was on screen ([bootMarkOnScreenMs]) - the
     * smoke test checks it. */
    private fun logBootMarkOver(why: String, now: Long) {
        val shown = bootMarkOnScreenMs(now, coverFrameMs, homeShownAtMs)
        Log.i(LOG_TAG, "Boot mark over: $why - the mark on screen ${if (shown == null) "never" else "$shown ms"}")
    }

    /** The wait ends without an ask ([bootMarkEnd]): the backstop, Home covered, a call. Main thread. */
    private fun endBootMark(context: Context, why: String) {
        when (bootMarkEnd(skipBootMark(why), chromeLocked, lockResumed, ourCall())) {
            BootMarkEnd.NOTHING -> Unit
            BootMarkEnd.SHOW -> show(context)
            BootMarkEnd.RECHECK -> {
                refrontAttempt = 0
                handler.removeCallbacks(refrontCheck)
                runRefrontCheck()
            }
        }
    }

    /**
     * From `Application.initRest` (CE storage is unlocked). Starts LOCKED when the last apply left
     * the lock active - a killed or crashed process fails closed - and shows the lock at once when
     * the screen is on (QA 10 #4). Registers the screen receiver for the whole process.
     */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        val app = context.applicationContext
        appContext = app
        processStartElapsed = SystemClock.elapsedRealtime()
        // Bug 25b-H: one of our own screens in front (Home, the time-rule screen) is never the SOS.
        (app as? android.app.Application)?.registerActivityLifecycleCallbacks(ownScreens)
        decide(app)
        // Design 16e: the first process start of a boot shows the mark for 3 s before the lock.
        bootMark = bootMarkAtStart(app)
        val readCoverFrame = bootMark == BootMarkState.Due
        // Unreadable (qa-16c-code #3): no kid PIN known - the lock still comes up, and the parent
        // code opens it; the receiver and ProcessStart below must run whatever happens here.
        config = try {
            PinLockStore.config(app)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Kid PIN unreadable", e)
            null
        }
        val active = startActive
        inactive = try {
            PinLockStore.inactive(app)?.let { wire -> LockInactive.entries.firstOrNull { it.wire == wire } }
        } catch (e: Exception) {
            null
        }
        ContextCompat.registerReceiver(
            app,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_USER_PRESENT)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // Design 59 (the run's bug 1): SystemUI sends USER_PRESENT from its own uid, and a NOT_EXPORTED receiver
        // only hears system_server, root and us (AOSP BroadcastSkipPolicy) - so it never reached the filter above.
        // Its own receiver, EXPORTED for this one action: USER_PRESENT is a <protected-broadcast> (frameworks/base
        // core/res/AndroidManifest.xml), so no app can send it.
        ContextCompat.registerReceiver(
            app,
            userPresentReceiver,
            IntentFilter(Intent.ACTION_USER_PRESENT),
            ContextCompat.RECEIVER_EXPORTED,
        )
        // Design 16d decision 1: one more status-bar heal when the boot is complete (a protected
        // broadcast; it reaches a registered receiver started before it is sent).
        ContextCompat.registerReceiver(
            app,
            bootCompletedReceiver,
            IntentFilter(Intent.ACTION_BOOT_COMPLETED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        OngoingCalls.addListener(callsListener)
        // Design 17: a VoIP call's stored exemption keeps its package pinned from the first chrome
        // refresh on, until the notification listener reports again (QA #4).
        VoipCalls.init(app)
        // Off the main thread: the clock app for the alarm exemption, and the kiosk-off lock
        // helpers, so a screen-off never waits for PackageManager (qa-10-code #7).
        CoroutineScope(Dispatchers.IO).launch {
            systemClockPackage = systemClockApp(app)
            ClockApp.refresh(app)
            LockTaskChrome.prefetchHelpers(app)
            LockTaskChrome.prefetchRecentsPin(app)
            sosPackage = runCatching { emergencySosPackage(app) }.getOrNull()
            // Round 7 §3: the parent's switch from the cached policy (never over a sync's).
            noticeWakeFromCache()
            // Design 32 step 2 (qa-32-s2-code M2): the switch, and a cover the device policy may still
            // hold from an earlier process or boot goes off now - before the first apply.
            KeyguardCover.atProcessStart(app)
            // Design 59 §3.2: the restart line goes at the boot's first CE start (the face was set in decide).
            AndroidLock.atBootStart(app)
        }
        // Design 16e (qa-16e-code #3): the boot cover's first frame, a device-protected file, read
        // once here - never by an ask on the main thread.
        if (readCoverFrame) {
            CoroutineScope(Dispatchers.IO).launch {
                coverFrameMs = try {
                    coverFrameThisBoot(BootCoverGuard.read(app), markBoot)
                } catch (e: Exception) {
                    null
                }
            }
        }
        // After Application.onCreate returns (an activity start from inside it is too early).
        handler.post {
            // Design 16b: a boot cover still enabled hands over first (so the typed HOME start
            // below resolves to HomeActivity only), and the shutdown receiver re-arms it.
            try {
                BootCover.init(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Boot cover init failed", e)
            }
            // Design 16 (A, QA #2/#6): the first start of a boot brings our Home to the front -
            // before the CE unlock a direct-boot-aware stock launcher was Home. Home roots lock
            // task (kiosk on) and shows the lock; the lock's own start is then a 1 s fallback.
            // 16c: when the system already brought our Home up in this process, the lock goes up
            // now, in this pass (a second Home start gave no resume, so it waited for the fallback).
            val homeFirst = try {
                BootHome.startIfDue(app, lockActive = active, homeShown = homeShown)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Boot Home check failed", e)
                false
            }
            // Counted from DISABLED, the process's real start ([decide] only set the mode early),
            // so a LOCKED start refreshes the chrome and the camera lock once, as before.
            dispatch(
                app,
                LockEvent.ProcessStart(active, interactive(app), ourCall(), systemCall(app), homeFirst = homeFirst, voip = VoipCalls.phase, androidBoot = startAndroidBoot),
                from = LockMode.DISABLED,
            )
            // The chrome of a lock that was LOCKED when the process died is still set; an inactive
            // lock must not leave it behind either. Same for the camera lock: released
            // (idempotently). LOCKED did both in the dispatch.
            if (!chromeLocked) {
                refreshChrome(app)
                CameraLock.onLockChanged(app)
            }
            // Design 16d decision 1: SystemUI can lose lock task's status-bar flags at boot (the
            // shade and Quick Settings opened over the lock) - heal them several times after the
            // start, past quickstep's start-up.
            for (delay in STATUS_BAR_HEALS_AFTER_START_MS) {
                handler.postDelayed({ healStatusBar(app, HealTrigger.PROCESS_START) }, delay)
            }
        }
    }

    // ---- events ---------------------------------------------------------------------------

    /** [from]: the mode the change effects count from - the current one, except for ProcessStart. */
    private fun dispatch(context: Context, event: LockEvent, from: LockMode = mode) {
        val before = from
        val result = step(mode, event)
        mode = result.mode
        val lockedEdge = before != LockMode.LOCKED && result.mode == LockMode.LOCKED
        // Design 59 §3.3: a USER_PRESENT counts only since our lock last went LOCKED - every edge, every screen-off,
        // every remote lock (a mark left from an earlier wake never opens a later one).
        if (result.mode == LockMode.LOCKED && (lockedEdge || event is LockEvent.ScreenOff || event is LockEvent.RemoteLock)) {
            AndroidLock.lockedAgain()
        }
        // The lock's start first, so its window covers the screen at once; then, on the LOCKED
        // edge, the chrome on this thread before anything else - the lock resumes only after this
        // returns, so the shade, overlays and Overview are blocked no later than the lock is up
        // (qa-16c-code #1; status bar first, no PackageManager work). Every other chrome change
        // runs on its own thread ([refreshChrome]).
        // Design 16e: while the boot's mark may hold the lock, the chrome goes first - show's hold
        // reads (binder calls) must not delay it, and a held show starts nothing (qa-16e-code #3).
        val chromeFirst = lockedEdge && bootMark != BootMarkState.Over
        if (chromeFirst) refreshChromeNow(context)
        // Design 32 step 1: a screen-off's starts are the lock-up's ([lockUp]) - Home under the lock
        // while dark, or nothing when the lock is on top already. The ScreenOn backstop belongs to
        // the same lock-up ([lockOnTopAfter]).
        if (event is LockEvent.ScreenOff) {
            lockUpAtScreenOff(context, event, result)
        } else if (result.showLock) {
            show(context, wake = WakeKind.VOIP.takeIf { result.wake }, ask = lockAsk(event), sameLockUp = event is LockEvent.ScreenOn)
        } else if (screenOnUnshown(event, result.showLock)) {
            // qa-32-s1-code M1: the lock isn't resumed and nothing starts it (a system-dialer or VoIP
            // call came over the sleep-stopped lock) - nobody knows what is on top now.
            lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.SCREEN_ON_UNSHOWN)
        }
        if (lockedEdge && !chromeFirst) refreshChromeNow(context)
        // Home was started first (design 16): the re-front check shows the lock if Home didn't.
        if (result.showLockLater) {
            // Design 16e: the mark's 3 s run from here, so the lock comes even if Home never draws;
            // without Home's mark up the 1 s fallback shows it, as before.
            startBootMarkClock()
            refrontAttempt = 0
            handler.removeCallbacks(refrontCheck)
            handler.postDelayed(refrontCheck, LOCK_FALLBACK_MS)
        }
        if (before != result.mode) {
            Log.i(LOG_TAG, "$before -> ${result.mode} on $event")
            if ((before == LockMode.LOCKED) != (result.mode == LockMode.LOCKED)) {
                // Design 32: unlocked (or switched off) - the next lock-up starts the lock again.
                if (result.mode != LockMode.LOCKED) lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.UNLOCKED)
                if (!lockedEdge) refreshChrome(context)
                // The camera gesture must not open a camera over the lock (background thread).
                CameraLock.onLockChanged(context)
                if (result.mode != LockMode.LOCKED && !LockTaskChrome.hasPlan) requestApply(context)
                // Design 59d step A: what waited for Android's unlock (Home, the time-rule screen) goes now - posted, so
                // the glance's own leave comes first.
                if (result.mode != LockMode.LOCKED) {
                    val app = context.applicationContext
                    handler.post { KeyguardCover.runAfterUnlock(app, "our lock left LOCKED") }
                }
            }
            modeListeners.toList().forEach { it() }
            // A ring that began unlocked rings once the phone locks; unlocking stops it (#5).
            syncVoipRinger(context)
        }
        // Something exempt is in front (qa-16-17-code #1): the re-front loop yields to it and brings
        // the lock back after it.
        if (result.recheck && !lockResumed) {
            refrontAttempt = 0
            handler.removeCallbacks(refrontCheck)
            handler.postDelayed(refrontCheck, refrontDelayMs(0))
        }
        // Our call screen at once: it is showWhenLocked, so it never un-occludes the keyguard cover
        // (qa-32-s2-code L5) - a live call's screen doesn't wait for a dismissal.
        if (result.showCall) showCall(context)
        // Design 17: the VoIP app's call screen back over the lock (sent from the resumed lock). Design
        // 32 step 2 (M5): not showWhenLocked, so it waits while the cover's keyguard is locked
        // ([KeyguardCover.whenUnlocked]; at once without the cover) - and when it runs later, only if
        // the lock and the VoIP call are still there (L5).
        if (result.showVoipCall) {
            KeyguardCover.whenUnlocked(context, "the VoIP call screen") {
                if (mode == LockMode.LOCKED && !ourCall() && VoipCalls.phase == VoipPhase.IN_CALL) {
                    // Design 59b §4 (Q59b-9): as Android's face, only a call (IN_COMMUNICATION) - never a flipped ring.
                    if (!voipReopenDue(AndroidLock.faceOn, VoipCalls.inCommunication(it))) {
                        AndroidLock.note(it, "VoIP IN_CALL without IN_COMMUNICATION - no call screen sent")
                    } else if (!VoipCalls.reopenCall(it)) {
                        Log.i(LOG_TAG, "No VoIP call screen to bring back")
                    }
                }
            }
        }
        // LOCKED but not shown (the system dialer's call): the re-front loop waits for it to end.
        if (result.mode == LockMode.LOCKED && !result.showLock && !result.showLockLater && !lockResumed && before != LockMode.LOCKED) {
            refrontAttempt = 0
            handler.removeCallbacks(refrontCheck)
            handler.postDelayed(refrontCheck, refrontDelayMs(0))
        }
        if (result.recheckTimeRules) afterUnlock(context)
    }

    /**
     * Design 32 step 1: the screen-off's lock-up, planned by [lockUpStarts] (the debug build's H2
     * experiment may re-start a lock on top, [LockUpDebug]) and carried out by [lockUp]. A call is
     * ours, Telecom's (the event's reads, no new binder calls before the start) or VoIP.
     */
    private fun lockUpAtScreenOff(context: Context, event: LockEvent.ScreenOff, result: LockStep) {
        val callLive = event.ourCall || event.systemCall || event.voip != VoipPhase.NONE
        val interactive = interactive(context)
        // Design 32 step 2: under the keyguard cover with its keyguard locked at this sleep, the lock
        // alone ([lockUpStarts]' coverLocked). Not locked - an app's KeyguardLock, a SystemUI restart -
        // is step 1's Home under the lock (qa-32-s2-code L4). The binder read only with the cover.
        // Design 59 likewise: as Android's face, its locked keyguard hides Home anyway.
        val cover = KeyguardCover.coverOn || AndroidLock.faceOn
        val kg = cover && KeyguardCover.keyguardLockedNow(context)
        // S3-3: Android read locked since this screen-off - an open read at the next resume is then an unlock, so
        // the wait for USER_PRESENT is the longer one (a fingerprint from dark).
        if (kg && AndroidLock.faceOn) AndroidLock.sawLocked(screenOffs)
        // Design 59d step C: as the face the lock alone whatever kg read (kg still feeds sawLocked above).
        val start = LockUpDebug.adjust(lockUpStarts(result.showLock, lockOnTop, callLive, interactive, cover && kg, face = AndroidLock.faceOn), result.showLock)
        lockUp(context, start, lockAsk(event))
        AndroidLock.note(context, "screen-off, lock-up $start")
        if (result.mode == LockMode.LOCKED) Log.i(LOG_TAG, "Lock-up at screen-off: $start (call=$callLive, interactive=$interactive, cover=$cover, kg=$kg)")
    }

    /**
     * Every lock-up at a screen-off, in one place (design 32 step 1 item 1, QA L6). HOME_THEN_LOCK:
     * Home first, without an open animation (QA M2), so what lies under the lock is Home - its
     * snapshot plain black - and not the app the kid was in; then the lock. LOCK: the lock alone. Both
     * on this thread, before anything else of the screen-off (bug 25b-B). The lock is then the top
     * task ([lockOnTop]).
     */
    private fun lockUp(context: Context, start: LockUpStart, ask: LockAsk) {
        if (start == LockUpStart.NONE) return
        // Round 7 §3: no notice wake in the first 2 s after a lock-up ([noticeWakeAllowed]).
        lockUpAtElapsed = SystemClock.elapsedRealtime()
        if (start == LockUpStart.HOME_THEN_LOCK) {
            darkLockUp = true
            HomeFront.bring(context, "under the lock (a lock-up while dark)", animate = false)
        }
        if (show(context, ask = ask, fromLockUp = true)) lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.LOCK_UP)
    }

    /**
     * The LOCKED edge's chrome pass, here on the main thread before the lock resumes
     * (qa-16c-code #1): cached helpers only - when none were cached yet (a process start with the
     * kiosk off, before the prefetch), our package is pinned alone and a pass on the chrome thread
     * adds them.
     */
    private fun refreshChromeNow(context: Context) {
        val app = context.applicationContext
        try {
            LockTaskChrome.refresh(app, resolveMissing = false)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Lock chrome change failed", e)
        }
        if (LockTaskChrome.helpersMissing) refreshChrome(app)
    }

    /**
     * A chrome pass for the current mode on [chromeExecutor] (16c - the unlock, a DISABLED start,
     * the helpers' follow-up), then the [chromeListeners] on the main thread.
     */
    private fun refreshChrome(context: Context) {
        val app = context.applicationContext
        chromeExecutor.execute {
            try {
                LockTaskChrome.refresh(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Lock chrome change failed", e)
            }
            handler.post { chromeListeners.toList().forEach { it() } }
        }
    }

    /**
     * Design 16d decision 1: a status-bar heal on the chrome thread, after any pass queued before it -
     * [LockTaskChrome.healStatusBar] flips SYSTEM_INFO, then [HealTrigger.flipMs] outside the
     * monitor (a LOCKED edge's pass may run meanwhile and write the features itself), then
     * [LockTaskChrome.endStatusBarHeal] writes the computed features back.
     */
    private fun healStatusBar(context: Context, trigger: HealTrigger) {
        val app = context.applicationContext
        chromeExecutor.execute {
            try {
                if (LockTaskChrome.healStatusBar(app, trigger)) {
                    Thread.sleep(trigger.flipMs)
                    LockTaskChrome.endStatusBarHeal(app)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Status bar heal (${trigger.label}) failed", e)
            }
        }
    }

    /**
     * Design 59d §8.9 (a): Home got an intent ([HomeActivity.onNewIntent]; [broughtByUs] = ours, `HomeFront.EXTRA_BROUGHT`).
     * The system's Home while LOCKED with the screen on means the home gesture got through - lock task's flags are out of
     * sync in SystemUI ([systemHomeHealDue]) - so the status bar is healed now, and the lock's resume that follows needn't
     * heal again. Home's locked resume shows the lock as before. Main thread.
     */
    fun homeIntent(context: Context, broughtByUs: Boolean) {
        val now = SystemClock.elapsedRealtime()
        // Design 59d §8.16 (§8.9's option (b)): the lock this Home start brings back asks for the unlock at once - where no
        // heal can help (no navigation bar of SystemUI's: Quickstep never hears that Home is disabled), the swipe becomes
        // "a flash, then the pad". Decided before the heal, which waits 5 s between its own runs.
        if (gestureAskDue(
                locked = mode == LockMode.LOCKED,
                interactive = interactive(context),
                broughtByUs = broughtByUs,
                callLive = callLiveNow(context),
                lastScreenOnElapsedMs = lastScreenOnElapsed,
                lastNoticeElapsedMs = lastNoticeElapsed,
                lastAskElapsedMs = gestureAskAt,
                nowElapsedMs = now,
            )
        ) {
            gestureAskAt = now
            gestureAskPending = true
            Log.i(LOG_TAG, "The system's Home while LOCKED: the lock asks for the unlock when it is back")
        }
        if (!systemHomeHealDue(mode == LockMode.LOCKED, interactive(context), broughtByUs, systemHomeHealAt, now)) return
        systemHomeHealAt = now
        lockResumedHealAt = now
        Log.i(LOG_TAG, "The system's Home while LOCKED (a home gesture got through): lock task's flags healed")
        healStatusBar(context, HealTrigger.SYSTEM_HOME)
    }

    /** Design 59d §8.16: whether the lock's resume asks for the unlock now - the gesture ask [homeIntent] marked, taken
     * once and only within [GESTURE_ASK_WINDOW_MS] ([gestureAskTaken]). Main thread. */
    fun takeGestureAsk(): Boolean {
        if (!gestureAskPending) return false
        gestureAskPending = false
        return gestureAskTaken(gestureAskAt, SystemClock.elapsedRealtime())
    }

    private val bootCompletedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) healStatusBar(context, HealTrigger.BOOT_COMPLETED)
        }
    }

    /** Our call screen in front of the lock (inside the lock task - our package is pinned). */
    private fun showCall(context: Context) {
        try {
            startOwn(context, com.kidslauncher.mdm.calls.InCallActivity.intent(context), "the call screen")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't bring the call screen to the front", e)
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else handler.post(block)
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val app = context.applicationContext
            if (intent.action == Intent.ACTION_SCREEN_OFF) {
                screenOffs++
                endNoticeWake()
                // Design 59b §3: the face's re-front cap is per screen-on; an ask dies with the screen-off.
                androidRefronts = 0
                androidAskAt = null
                androidCancelledAskAt = null
                androidWaited = false
                // The lock-up first ([lockUp]). This broadcast comes after the display is off, and the
                // lock can't draw while dark: it is drawn only after the next screen-on (design 32).
                rememberAlarm(app)
                // The power button silences a VoIP ring on a ringing lock; a ring that began
                // unlocked starts ringing at this screen-off instead (qa-16-17-code #5).
                if (screenOffSilencesRing(mode == LockMode.LOCKED, VoipCalls.phase == VoipPhase.RINGING)) {
                    silencedRing = VoipCalls.ringId
                }
                dispatch(app, LockEvent.ScreenOff(ourCall(), systemCall(app), VoipCalls.phase))
                // Design 59 §3.3: as Android's face, Android must be locked under the glance - checked once it slept.
                handler.removeCallbacks(androidSleepCheck)
                if (AndroidLock.faceOn && mode == LockMode.LOCKED) handler.postDelayed(androidSleepCheck, ANDROID_SLEEP_CHECK_MS)
                // Design 32 step 2: a dark screen-off ends the cover fallback's wake (after the start).
                if (KeyguardCover.switchOn) KeyguardCover.onScreenOff(interactive(app))
                syncVoipRinger(app)
                ScreenTimeTracker.update(app)
                // Bug 25b-B: the rest waits until the lock just started has had the main thread for
                // its first frame - the activity's creation runs only after this receiver returns.
                handler.removeCallbacks(screenOffWork)
                handler.postDelayed(screenOffWork, SCREEN_OFF_WORK_DELAY_MS)
            } else {
                // Design 59: as Android's face, USER_PRESENT is [userPresentReceiver]'s (should it ever come here too).
                if (intent.action == Intent.ACTION_USER_PRESENT && AndroidLock.faceOn) return
                // A wake before the sleep check: the glance's resume decides (59 §3.3).
                if (intent.action == Intent.ACTION_SCREEN_ON) handler.removeCallbacks(androidSleepCheck)
                // First, synchronously: end the Play update window before anything else (QA
                // step 7 #8); the full re-apply follows in the background.
                // Back on before the screen-off's deferred work ran: it is dropped (the screen-on's
                // own re-checks follow; a status-bar heal now would be visible).
                handler.removeCallbacks(screenOffWork)
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    lastScreenOnElapsed = SystemClock.elapsedRealtime()
                    // Design 59b §6: one line per screen-on with the keyguard read (the screen-off has its lock-up's).
                    AndroidLock.note(app, "screen-on")
                    // A screen-on well after a notice wake is the kid's own (power): the 15 s end (qa-l7-b-code 3).
                    if (noticeWakeEndsAtScreenOn(noticeWokeAt, SystemClock.elapsedRealtime())) endNoticeWake()
                    PlayRuntime.suspendStoreAtScreenOn(app)
                    // No self-update commit while someone uses the phone (step 11).
                    com.kidslauncher.mdm.server.SelfUpdate.onScreenOn(app)
                }
                // Design 19: a command stream that is down or went silent while the phone slept
                // reconnects now - a lift or a lock matters most while the phone is in use.
                com.kidslauncher.mdm.server.CommandListenerService.checkStream("screen")
                // F3C-2: Telecom's own calls have no listener - a boot Home waiting for a call looks again.
                BootHome.callMayHaveEnded(app)
                // May start the time-rule screen - the PIN lock then goes above it. A parent-PIN
                // window that ended in the phone's sleep locks again now (design 24b).
                TimeRulesRuntime.recheck(app, com.kidslauncher.mdm.server.RelockTrigger.SCREEN_ON)
                // Design 59d step C (§8.5): face and lockOnTop read here, not stored - a new process has lockOnTop false.
                dispatch(app, LockEvent.ScreenOn(lockResumed, ourCall(), systemCall(app), VoipCalls.phase, face = AndroidLock.faceOn, lockOnTop = lockOnTop))
                // Design 16d decision 1: the boot's first screen-on heals the status bar once more.
                if (intent.action == Intent.ACTION_SCREEN_ON && !screenOnHealed) {
                    screenOnHealed = true
                    healStatusBar(app, HealTrigger.FIRST_SCREEN_ON)
                }
                // Backstop for the migration (QA 10 #15): the Android credential is gone but no
                // onPasswordChanged arrived - check now rather than at the next sync.
                if (intent.action == Intent.ACTION_USER_PRESENT && inactive == LockInactive.ANDROID_CREDENTIAL && !deviceSecure(app)) {
                    requestApply(app)
                }
            }
        }
    }

    /**
     * Design 59 §3.3, [FaceTrigger.SLEEP_CHECK]: [ANDROID_SLEEP_CHECK_MS] after a screen-off while LOCKED and still
     * dark, Android's keyguard must be locked under the glance - else RELOCK (`lockNow`). Main thread.
     */
    private val androidSleepCheck = Runnable {
        val app = appContext ?: return@Runnable
        if (!AndroidLock.faceOn || mode != LockMode.LOCKED || interactive(app)) return@Runnable
        when (AndroidLock.step(app, FaceTrigger.SLEEP_CHECK)) {
            FaceStep.RELOCK -> AndroidLock.relock(app, "the sleep check")
            FaceStep.STAY -> AndroidLock.sawLocked(screenOffs)
            else -> Unit
        }
    }

    /**
     * Design 59 (the run's bug 1): `USER_PRESENT`, registered EXPORTED for that action alone - SystemUI sends it.
     * As Android's face only (own mode: as before, where it never arrived): the mark ([AndroidLock.userPresent]),
     * then [FaceTrigger.USER_PRESENT] - Android open: the glance leaves, through the live lock (a row's target
     * opens, an open ask's answer first) or, with none, here. Main thread.
     */
    private val userPresentReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != Intent.ACTION_USER_PRESENT || !AndroidLock.faceOn) return
            val app = context.applicationContext
            // S3-2: Android's keyguard is done only with the screen on - a late broadcast after a screen-off (it
            // rides the background queue) never marks the next wake.
            if (!interactive(app)) return
            AndroidLock.userPresent()
            // Design 59b §3: Android open - the face's re-front count starts again.
            androidRefronts = 0
            androidWaited = false
            AndroidLock.note(app, "USER_PRESENT")
            if (mode != LockMode.LOCKED) return
            if (AndroidLock.step(app, FaceTrigger.USER_PRESENT) != FaceStep.LEAVE) return
            val hook = androidUnlockHook
            if (hook != null) hook() else dispatch(app, LockEvent.Unlocked(screenOffs, screenOffs))
        }
    }

    /**
     * The screen-off's work that the lock doesn't need first (bug 25b-B), [SCREEN_OFF_WORK_DELAY_MS]
     * after it; dropped if the screen came back on meanwhile ([screenOffWorkDue]).
     */
    private val screenOffWork = Runnable {
        val app = appContext ?: return@Runnable
        if (!screenOffWorkDue(interactive(app))) return@Runnable
        // Step 11: a screen-off may end the update fence in the new build, and starts the
        // wait for the self-update window.
        com.kidslauncher.mdm.server.UpdateFence.onFront(app)
        com.kidslauncher.mdm.server.SelfUpdate.onScreenOff(app)
        // The nightly Play update window opens when the screen goes off inside it.
        try {
            reevaluateLockReasonFromCache(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Re-check at screen off failed", e)
        }
        // Design 16d decision 1: lock task's status-bar flags sent to SystemUI again while
        // the screen is off - a SystemUI or quickstep restart isn't visible to an app.
        healStatusBar(app, HealTrigger.SCREEN_OFF)
        // The lock-up after the next screen-on needs no visible heal of its own (bug 25b-C).
        screenOffHealed = true
    }

    /**
     * A call is live - ours, a VoIP call, or one the system dialer shows (qa-25b-25f M1): the
     * camera stays enabled by policy then, even while LOCKED (a video call answered from the
     * lock). Written on the main thread at each call event, read by CameraLock's thread.
     */
    @Volatile
    var callForCamera = false
        private set

    private fun updateCallForCamera(context: Context, telecom: Boolean) {
        val now = cameraCallActive(ourCall(), VoipCalls.phase != VoipPhase.NONE, telecom)
        if (now == callForCamera) return
        callForCamera = now
        CameraLock.onLockChanged(context.applicationContext)
    }

    private val callsListener: () -> Unit = {
        val ctx = appContext
        val any = OngoingCalls.hasLiveCall
        if (ctx != null) updateCallForCamera(ctx, telecomInCall(ctx))
        // Design 16e: a call skips the boot's wait for the mark at once.
        if (ctx != null && !callsSeen && any) endBootMark(ctx, "a call")
        if (ctx != null && callsSeen && !any) dispatch(ctx, LockEvent.CallsEnded(interactive(ctx)))
        callsSeen = any
        // F3C-2: a boot Home start declined for a call comes at its end.
        if (ctx != null && !any) BootHome.callMayHaveEnded(ctx)
    }

    /**
     * From [VoipCalls] (main thread): a VoIP ring began - the lock wakes, rings and shows its card;
     * the exemption ended - the lock comes back, as after a phone call (design 17).
     */
    fun onVoipPhase(context: Context, before: VoipPhase, after: VoipPhase) {
        val app = context.applicationContext
        // Another call (emergency included), the emergency flow or a ringing alarm always win
        // (qa-16-17-code #1): no card and no wake over them, and the lock doesn't come back over them.
        val telecom = telecomInCall(app)
        updateCallForCamera(app, telecom)
        // Design 32 step 2 (M5): no keyguard cover in a VoIP call or ring - written only with the screen on.
        if (KeyguardCover.switchOn) KeyguardCover.onVoipPhase(app, after, interactive(app))
        // Design 59's prototype: a VoIP call over Android's keyguard (its call screen shows only if showWhenLocked).
        if (AndroidLock.faceOn) AndroidLock.note(app, "VoIP $before -> $after (${VoipCalls.ringingPackage ?: "-"})")
        val ours = ourCall()
        val emergency = emergencyFlowNow(telecom)
        val alarm = alarmNow(app)
        if (after != VoipPhase.RINGING) handler.removeCallbacks(voipGrace)
        if (after == VoipPhase.RINGING && before != VoipPhase.RINGING) {
            // Design 59b §4: as Android's face with Android locked, the ring's full-screen intent is the platform's - no
            // lock-up or wake of ours; the grace's timer decides after VOIP_PLATFORM_GRACE_MS (N4).
            val platform = voipPlatformRing(AndroidLock.faceOn, AndroidLock.refrontHeld(app), VoipCalls.ringHasFullScreen)
            voipPlatformRingId = if (platform) VoipCalls.ringId else null
            dispatch(app, LockEvent.VoipRinging(ours, !ours && telecom, emergency, alarm, platformRing = platform))
            handler.removeCallbacks(voipGrace)
            if (platform) handler.postDelayed(voipGrace, VOIP_PLATFORM_GRACE_MS)
        }
        if (after == VoipPhase.NONE && before != VoipPhase.NONE) {
            dispatch(app, LockEvent.VoipEnded(interactive(app), ours, !ours && telecom, emergency, alarm))
            BootHome.callMayHaveEnded(app)
        }
    }

    /** The ring the power button (or Avvis) silenced ([VoipCalls.ringId]). */
    private var silencedRing: Long? = null

    /** The ring Avvis dismissed: the card stays hidden for it (qa-16-17-code #3). */
    private var dismissedRing: Long? = null

    val voipRingDismissed: Boolean get() = VoipCalls.ringId != null && VoipCalls.ringId == dismissedRing

    /** Avvis on the card: this ring's card goes and it stays silent, whatever the decline did. */
    fun dismissVoipRing(context: Context) {
        dismissedRing = VoipCalls.ringId
        silencedRing = VoipCalls.ringId
        syncVoipRinger(context)
    }

    /** The ring Svar silenced, and this ring's silence before it (qa-17b-code #1). */
    private var answeredRing: Long? = null
    private var silencedBeforeAnswer: Long? = null

    /** Svar on the card went out (17b QA #6): after the answer action this ring is silent - the app
     * cancels its ring only once the call is joined, seconds later; after the app's own ring screen
     * it rings on ([answerSilences], qa-17b-code #1). */
    fun answeredVoipRing(context: Context, sent: VoipAnswerSent) {
        if (!answerSilences(sent)) return
        val ring = VoipCalls.ringId ?: return
        answeredRing = ring
        silencedBeforeAnswer = silencedRing
        silencedRing = ring
        syncVoipRinger(context)
    }

    /** Svar's start was refused (the lock never left the front): the ring is as before Svar. */
    fun voipAnswerRefused(context: Context) {
        silencedRing = silenceAfterRefusedAnswer(silencedRing, answeredRing, silencedBeforeAnswer)
        answeredRing = null
        syncVoipRinger(context)
    }

    /** The lock's try at the app's own ring screen (design 17b), one per ring. */
    private var voipFsiTry: VoipFsiTry? = null

    /** Design 59b §4: the ring ([VoipCalls.ringId]) whose full-screen intent is the platform's ([voipPlatformRing]);
     * `null` = none. Main thread. */
    private var voipPlatformRingId: Long? = null

    /** The glance's last pause (elapsed; `null` = none in this process) - "resumed since the ring's first sight". */
    private var lockPausedAt: Long? = null

    /**
     * Design 59b §4 (N4): the platform ring's grace timer, posted at the ringing edge and dropped when the ring ends -
     * [voipRingStep] after [VOIP_PLATFORM_GRACE_MS]: the one heads-up send, "the platform had its turn" (the card), or,
     * still dark, the wake and the card. Main thread.
     */
    private val voipGrace: Runnable = Runnable {
        val app = appContext ?: return@Runnable
        val ringId = VoipCalls.ringId
        if (ringId == null || ringId != voipPlatformRingId) return@Runnable
        val now = SystemClock.elapsedRealtime()
        val grace = voipRingStep(
            ringId = ringId,
            locked = mode == LockMode.LOCKED,
            dismissed = voipRingDismissed,
            otherScreen = voipOtherScreen(app),
            hasFullScreen = VoipCalls.ringHasFullScreen,
            last = voipFsiTry,
            resumedSinceRing = lockResumed && (lockPausedAt ?: Long.MIN_VALUE) < ringId,
            interactive = interactive(app),
            androidLocked = AndroidLock.refrontHeld(app),
            nowElapsedMs = now,
        )
        when (grace.act) {
            VoipGraceAct.NONE -> Unit
            VoipGraceAct.WAIT -> handler.postDelayed(voipGrace, grace.waitMs)
            VoipGraceAct.SEND -> {
                val sent = VoipCalls.showRingScreen(app)
                voipFsiTry = VoipFsiTry(ringId, now, sent)
                Log.i(ANDROID_LOCK_TAG, "VoIP ring: the heads-up case - the app's ring screen ${if (sent) "sent" else "couldn't be sent: the card"}")
                if (sent) handler.postDelayed({ VoipCalls.changed() }, VOIP_FSI_CHECK_MS)
            }
            VoipGraceAct.PLATFORM -> {
                voipFsiTry = VoipFsiTry(ringId, now, sent = false)
                Log.i(ANDROID_LOCK_TAG, "VoIP ring: the platform had its turn - the card from here")
            }
            VoipGraceAct.WAKE -> {
                voipFsiTry = VoipFsiTry(ringId, now, sent = false)
                Log.i(ANDROID_LOCK_TAG, "VoIP ring: still dark after the grace - the wake and the card")
                show(app, wake = WakeKind.VOIP)
            }
        }
        // The glance renders the ring again (the card after PLATFORM or WAKE).
        if (grace.act != VoipGraceAct.WAIT && grace.act != VoipGraceAct.NONE) VoipCalls.changed()
    }

    /** Another call (ours or Telecom's, emergency included) or the emergency dialer flow is on: no
     * VoIP intent is ever sent ([VoipCalls] checks it at every send, 17b QA #5). */
    fun voipCallOrEmergency(context: Context): Boolean {
        val telecom = telecomInCall(context.applicationContext)
        return ourCall() || telecom || emergencyFlowNow(telecom)
    }

    /** ... or a ringing alarm: no card, no ring screen and no ring (qa-16-17-code #1, 17b QA #5). */
    fun voipOtherScreen(context: Context): Boolean = voipCallOrEmergency(context) || alarmNow(context.applicationContext)

    /** What the lock shows for the current ring ([voipRingUi]). Main thread. */
    fun voipRingUiNow(context: Context): VoipRingUi {
        val ringId = VoipCalls.ringId ?: return VoipRingUi.NONE
        return voipRingUi(
            ringId = ringId,
            locked = mode == LockMode.LOCKED,
            dismissed = voipRingDismissed,
            otherScreen = voipOtherScreen(context),
            hasFullScreen = VoipCalls.ringHasFullScreen,
            last = voipFsiTry,
            nowElapsedMs = SystemClock.elapsedRealtime(),
        )
    }

    /** The resumed lock sends the app's own ring screen now ([voipFsiDue]) - never with the screen
     * off (a sleeping lock is no visible sender). Main thread. */
    fun voipFsiDueNow(context: Context): Boolean {
        val ringId = VoipCalls.ringId ?: return false
        if (!interactive(context.applicationContext)) return false
        return voipFsiDue(
            ringId, mode == LockMode.LOCKED, voipRingDismissed, voipOtherScreen(context), VoipCalls.ringHasFullScreen, voipFsiTry,
            platformRing = ringId == voipPlatformRingId,
        )
    }

    /** Design 17b: the resumed, settled lock sends the ring's full-screen intent - once per ring,
     * whatever comes of it. Returns when the lock should look again (`null`: no try open). */
    fun sendVoipRingScreen(context: Context): Long? {
        if (!voipFsiDueNow(context)) return null
        val ringId = VoipCalls.ringId ?: return null
        // Design 32 step 2 (M5): not while the cover's keyguard is locked - no try is spent; the lock
        // looks again (the dismissal comes right after its first frame).
        if (KeyguardCover.mustWait(context)) return VOIP_FSI_SETTLE_MS
        val sent = VoipCalls.showRingScreen(context)
        voipFsiTry = VoipFsiTry(ringId, SystemClock.elapsedRealtime(), sent)
        Log.i(LOG_TAG, if (sent) "VoIP ring screen sent" else "VoIP ring screen couldn't be sent: the card")
        return if (sent) VOIP_FSI_CHECK_MS else null
    }

    /** When the lock last asked for [VoipWakeActivity] (elapsed), and whether it is gone since. */
    private var voipWakeAskedAt: Long? = null
    private var voipWakeGone = true

    /** The lock left the front ([sendPending]: its ring-screen send was still settling): the app's
     * ring screen came up (17b) - a later resume in the same ring shows the card, never a second
     * try; a pause that isn't our wake activity counts even before our send ([pauseIsTry],
     * qa-17b-code #5). */
    fun voipLockLeft(sendPending: Boolean) = notePause(sendPending = sendPending, wakeCovered = false)

    /** [VoipWakeActivity] paused: [covered] = before it finished itself (something came over it -
     * SystemUI's own launch of the app's full-screen intent, qa-17b-code #5). */
    fun voipWakePaused(covered: Boolean) {
        notePause(sendPending = false, wakeCovered = covered)
        voipWakeGone = true
    }

    private fun notePause(sendPending: Boolean, wakeCovered: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val isTry = pauseIsTry(sendPending, wakeCovered, voipWakeAskedAt, voipWakeGone, now)
        val next = voipTryAfterPause(voipFsiTry, VoipCalls.ringId, isTry, now)
        if (next != null && next.ringId != voipFsiTry?.ringId) Log.i(LOG_TAG, "The VoIP ring screen came up without our send")
        voipFsiTry = next
    }

    /** The lock's own ring on or off ([voipRingWanted]); on every VoIP evaluation (also the 2 s
     * poll while it rings) and every lock-mode change. Main thread. */
    fun syncVoipRinger(context: Context) {
        val app = context.applicationContext
        val ringing = VoipCalls.phase == VoipPhase.RINGING
        val wanted = ringing && voipRingWanted(
            ringing = true,
            locked = mode == LockMode.LOCKED,
            silenced = VoipCalls.ringId != null && VoipCalls.ringId == silencedRing,
            otherCall = ourCall() || telecomInCall(app),
            emergencyFlow = emergencyFlowNow(telecomInCall(app)),
            alarmRinging = alarmNow(app),
            callService = VoipCalls.ringCallServiceUp,
        )
        if (wanted) VoipRinger.start(app) else if (VoipRinger.active) VoipRinger.stop(app)
        // Design r7 §1.1.3: a ring that began LOCKED, or during which the phone locked.
        VoipCalls.noteRingWhileLocked(app, locked = mode == LockMode.LOCKED)
    }

    /** The emergency flow as the re-front check sees it: tapped < 2 min ago, until its call ended. */
    private fun emergencyFlowNow(telecom: Boolean): Boolean {
        if (emergencyFlowUntilElapsed > 0L && telecom) emergencyCallSeen = true
        return SystemClock.elapsedRealtime() < emergencyFlowUntilElapsed && !(emergencyCallSeen && !telecom)
    }

    /** The system clock app's alarm is probably ringing ([alarmLikelyRinging]). */
    private fun alarmNow(context: Context): Boolean {
        rememberAlarm(context)
        return alarmLikelyRinging(rememberedAlarmMs, System.currentTimeMillis())
    }

    // ---- the notice wake (launcher round 7 §3) ------------------------------------------------

    /** The parent's "Wake the screen for a notification" (`launcher_ui.lock_wake_on_notification`),
     * from the cached policy at process start and every accepted sync ([noticeWakeSwitch]). */
    @Volatile
    var noticeWakeOn = false
        private set

    /** A sync's value arrived: the cache read at process start never overwrites it. */
    @Volatile
    private var noticeWakeFresh = false

    /** This process's start, the last lock-up and the last notice wakes (elapsed). Main thread. */
    private var processStartElapsed = 0L
    private var lockUpAtElapsed: Long? = null
    private var noticeWakes: List<Long> = emptyList()

    /** The current notice wake (elapsed; `null` = none on) and whether the lock was touched since. */
    private var noticeWokeAt: Long? = null
    private var noticeTouched = false

    /** A proximity read is running: a second notice meanwhile is the same wake. Main thread. */
    private var proximityReading = false

    private val noticeWakeOff = Runnable { noticeWakeOffCheck() }

    /** The lock's own dialogs open now (`PinLockActivity`: Foreldrekode, the emergency confirm): a notice wake
     * never turns the screen off under one (qa-l7-b-code 2). Main thread. */
    @Volatile
    var lockDialogsOpen = 0

    /** TalkBack's touch exploration is on: its hovers and clicks never reach `onUserInteraction` (qa-l7-b-code 2). */
    private fun touchExploration(context: Context): Boolean = try {
        context.getSystemService(android.view.accessibility.AccessibilityManager::class.java)?.isTouchExplorationEnabled == true
    } catch (e: Exception) {
        true
    }

    /** After every accepted sync (`MdmSyncWorker`): the switch as the parent set it now. */
    fun noticeWakeSwitch(ui: com.kidslauncher.mdm.server.dto.LauncherUi?) {
        noticeWakeFresh = true
        setNoticeWake(ui?.lockWakeOnNotification == true)
    }

    /** Process start (off the main thread): the `Ok` cached policy's switch, unless a sync came first. */
    private fun noticeWakeFromCache() {
        val ui = try {
            (com.kidslauncher.mdm.server.cachedPolicy() as? com.kidslauncher.mdm.server.CachedPolicy.Ok)?.policy?.launcherUi
        } catch (e: Exception) {
            null
        }
        if (!noticeWakeFresh) setNoticeWake(ui?.lockWakeOnNotification == true)
    }

    private fun setNoticeWake(on: Boolean) {
        if (on != noticeWakeOn) Log.i(LOG_TAG, "Notice wake: ${if (on) "on" else "off"}")
        noticeWakeOn = on
    }

    /** What keeps the screen dark for a notice now ([NoticeBusy]). Main thread. */
    private fun noticeBusy(app: Context, now: Long): NoticeBusy {
        val telecom = telecomInCall(app)
        return NoticeBusy(
            ourCall = ourCall(),
            systemCall = telecom,
            voip = VoipCalls.phase != VoipPhase.NONE,
            emergencyFlow = emergencyFlowNow(telecom),
            alarm = alarmNow(app),
            bootMark = bootMark != BootMarkState.Over,
            sinceProcessStartMs = now - processStartElapsed,
        )
    }

    private fun noticeAllowed(app: Context, now: Long, near: Boolean?): Boolean = noticeWakeAllowed(
        switchOn = noticeWakeOn,
        mode = mode,
        interactive = interactive(app),
        busy = noticeBusy(app, now),
        sinceLastLockUpMs = lockUpAtElapsed?.let { now - it },
        sinceLastWakeMs = noticeWakes.lastOrNull()?.let { now - it },
        wakesThisHour = wakesInHour(noticeWakes, now).size,
        near = near,
    )

    /**
     * A row came on the lock's glance page (`GlanceReader.posted`) or our missed-call notice alerted
     * (`MissedCallNotifier`, posted here): wake the screen on the lock when [noticeWakeAllowed] says so.
     * Carries nothing about the notice - only that one came. The proximity read comes first (300 ms,
     * near = no wake). Main thread.
     */
    fun noticeCame() {
        // Design 59d §8.16: a notice this recent makes a system Home start no gesture of the kid's.
        lastNoticeElapsed = SystemClock.elapsedRealtime()
        val app = appContext ?: return
        try {
            if (proximityReading || !noticeAllowed(app, SystemClock.elapsedRealtime(), near = false)) return
            proximityReading = true
            NoticeProximity.read(app, handler) { near ->
                proximityReading = false
                val now = SystemClock.elapsedRealtime()
                if (!noticeAllowed(app, now, near)) {
                    if (near == true) Log.i(LOG_TAG, "Notice wake: the phone is in a pocket")
                    return@read
                }
                noticeWakes = wakesAfter(noticeWakes, now)
                Log.i(LOG_TAG, "Notice wake (proximity=${near ?: "unknown"})")
                // The 15 s only for a start that went (qa-l7-b-code 3): a refused one lit nothing.
                if (show(app, wake = WakeKind.NOTICE)) {
                    noticeWokeAt = now
                    noticeTouched = false
                    handler.removeCallbacks(noticeWakeOff)
                    handler.postDelayed(noticeWakeOff, NOTICE_WAKE_ON_MS)
                }
            }
        } catch (e: Exception) {
            proximityReading = false
            Log.w(LOG_TAG, "Notice wake failed", e)
        }
    }

    /** The lock was touched (`PinLockActivity.onUserInteraction`): a notice wake leaves the screen
     * to the phone's own timeout. Main thread. */
    fun noticeWakeTouched() {
        if (noticeWokeAt == null) return
        noticeTouched = true
        endNoticeWake()
    }

    private fun endNoticeWake() {
        noticeWokeAt = null
        handler.removeCallbacks(noticeWakeOff)
    }

    /** [NOTICE_WAKE_ON_MS] after a notice wake: still untouched on the lock - the screen goes off
     * (`lockNow`, the device owner's; the screen-off locks as always). Design 59d step E: never as Android's face - the
     * phone's own screen timeout ends the wake ([noticeWakeOffDue]'s `face`). */
    private fun noticeWakeOffCheck() {
        val app = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        val busy = noticeBusy(app, now)
        val due = noticeWakeOffDue(
            wokeAtMs = noticeWokeAt,
            touched = noticeTouched,
            interactive = interactive(app),
            locked = mode == LockMode.LOCKED,
            lockResumed = lockResumed,
            busy = busy.ourCall || busy.systemCall || busy.voip || busy.emergencyFlow || busy.alarm,
            dialogOpen = lockDialogsOpen > 0,
            touchExploration = touchExploration(app),
            nowMs = now,
            face = AndroidLock.faceOn,
        )
        endNoticeWake()
        if (!due) return
        try {
            app.getSystemService(DevicePolicyManager::class.java)?.lockNow()
            Log.i(LOG_TAG, "Notice wake over: screen off")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Notice wake: couldn't turn the screen off", e)
        }
    }

    /** A time-rule screen was just started over everything: the PIN lock goes on top (QA 10 #4:
     * the time rules are never suppressed by the PIN lock). */
    fun afterTimeRuleShown(context: Context) = onMain { dispatch(context.applicationContext, LockEvent.TimeRuleShown) }

    // ---- configuration (from AppEnforcer.apply, background thread) ------------------------

    /**
     * Design §3: decides whether the lock is active and switches Android's keyguard off for it
     * (`setKeyguardDisabled(true)` = screen lock "None", only possible without an Android
     * credential). Re-checked on every apply; an override or pause doesn't switch the lock off.
     * Design 59: `kid_lock.keyguard` "android" ([androidRequested]) makes Android's PIN the gate and the lock its
     * face; [parentChoice] - a fresh or `Ok` cached policy (L-1) - is the only kind that may roll it back.
     */
    fun configure(context: Context, dpm: DevicePolicyManager, admin: ComponentName, policy: PolicyResponse?, managed: Boolean, parentChoice: Boolean = false) {
        val app = context.applicationContext
        val kidLock = policy?.kidLock
        val newConfig = kidLock?.let { KidLockConfig(it.pinHash, it.pinSalt, kidPinLength(it.pinLength), it.pinRev) }
        val requested = androidRequested(kidLock)
        // Design 59 first: the token, the rollback and the owned flag - the keyguard below is written for the
        // credential this leaves (a rollback's clear, then DISABLED).
        val owned = AndroidLock.configure(app, requested, parentChoice)
        val deviceSecure = try {
            app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        } catch (e: Exception) {
            false
        }
        // The crash guard re-arms at a sync at most once every 10 minutes.
        val guard = guardRearm(PinLockStore.guard(app), System.currentTimeMillis())
        PinLockStore.saveGuard(app, guard)
        // The one keyguard writer (design 32 step 2, [KeyguardCover]): `setKeyguardDisabled(true)` where
        // today's code disables it ([wantKeyguardDisabled] - false while a credential exists; true also
        // re-dismisses a showing keyguard), the features (B2's camera bit) and, only under the debug
        // switch's cover, the Swipe keyguard with T. True: the cover is set or the keyguard disabled - a
        // failed cover is today's disabled keyguard, never the lock off (H2).
        // Design 59: requested with a credential, the keyguard is ANDROID and the lock stays active as its face
        // (androidGate); the camera lock follows what the write read back.
        val androidGate = AndroidLock.face(app, dpm, admin, requested, managed, newConfig != null, deviceSecure)
        // S3-1: an apply without the parent's choice (the last-enforced plan has no kid_lock) keeps Android's
        // keyguard write while we own its live credential - no notification content, face or trust agents back.
        val androidHeld = androidKeyguardHeld(owned, deviceSecure, parentChoice)
        val keyguardDisabled = KeyguardCover.configure(app, dpm, admin, managed, newConfig != null, deviceSecure, guard.trippedAtMs != null, requested, androidHeld)
        AndroidLock.afterKeyguard(app)
        val (active, why) = lockActivation(
            managed = managed,
            hasKidPin = newConfig != null,
            deviceSecure = deviceSecure,
            keyguardDisabled = keyguardDisabled,
            hashUsable = newConfig?.usable == true,
            guardTripped = guard.trippedAtMs != null,
            androidGate = androidGate,
        )
        // A new PIN from the parent resets the wrong-PIN count and wait (design §5).
        synchronized(this) {
            val backoff = PinLockStore.backoff(app)
            val forHash = backoffForHash(backoff, newConfig?.fingerprint)
            if (forHash != backoff) PinLockStore.saveBackoff(app, forHash)
        }
        // S3-1: the request is stored only from a policy with the parent's choice - never from a fallback's missing kid_lock.
        val storedRequest = requestToStore(requested, parentChoice)
        if (newConfig != config || active != PinLockStore.active(app) || why != inactive ||
            (storedRequest != null && storedRequest != PinLockStore.keyguardRequested(app))
        ) {
            PinLockStore.saveConfig(app, newConfig, active, why, storedRequest)
        }
        config = newConfig
        inactive = why
        if (why != null && why != LockInactive.NO_PIN) Log.i(LOG_TAG, "Lock state: active=$active, $why")
        onMain { dispatch(app, LockEvent.Configured(active)) }
    }

    /** The crash guard tripped in PinLockActivity.onCreate: the lock is off until a sync re-arms it. */
    fun guardTripped(context: Context) {
        val app = context.applicationContext
        Log.w(LOG_TAG, "PinLockActivity crashed repeatedly - lock off until the next sync (crash guard)")
        inactive = LockInactive.CRASH_GUARD
        // Design 32 step 2 (L8): no Swipe keyguard without a handy lock - off at once (with the cover).
        KeyguardCover.guardTripped(app)
        // Design 59 (Q59-2): as Android's face, Android's lock now - SystemUI's face with the PIN, never open.
        AndroidLock.crashGuardTripped(app)
        PinLockStore.saveConfig(app, config, active = false, inactive = LockInactive.CRASH_GUARD)
        onMain { dispatch(app, LockEvent.Configured(false)) }
        requestSync(app, "pin_lock_crash_guard")
    }

    // ---- showing the lock, re-front -------------------------------------------------------

    /**
     * After Home was brought to the front by an update (step 11, qa-11-design.md #9): the lock
     * goes on top when LOCKED. Home's resume shows it (and, with the kiosk on, roots lock task
     * first - design 16 QA #2), so this is the [LOCK_FALLBACK_MS] backstop: delayed, a
     * ProcessStart still queued in this new process goes first.
     */
    fun showIfLocked(context: Context) {
        val app = context.applicationContext
        handler.postDelayed({ if (mode == LockMode.LOCKED && !lockResumed) show(app) }, LOCK_FALLBACK_MS)
    }

    /** The lock is active - in memory, else as the last apply stored it (a receiver in a new
     * process can run before its ProcessStart). */
    fun activeOrStored(context: Context): Boolean =
        mode != LockMode.DISABLED || PinLockStore.active(context.applicationContext)

    /**
     * [wake]: turn the screen on for it - a VoIP ring (design 17, [WakeKind.VOIP]) or a notice the
     * parent's switch wakes for (round 7 §3, [WakeKind.NOTICE]); `null` = no wake. The lock itself never sets
     * turn-screen-on - an existing, stopped lock would learn it only after its start, and a left-over
     * bit would wake a later start in a pocket (qa-16-17-code #2): a fresh [VoipWakeActivity], whose
     * manifest says `turnScreenOn`, goes on top of it in its task and finishes itself. The wake window
     * is only ever started here, right after the lock's own start - never alone (design 32 §7: over a
     * task that isn't the lock it would light a live app on a LOCKED phone).
     *
     * [ask] (design 16e): [LockAsk.BOOT] may be held while the boot's mark shows - then nothing is
     * started and `false` is returned (the backstop shows it); every other ask, and a wake, ends the
     * wait and shows the lock at once. `false` too when the start threw (qa-32-s1-code L4): the
     * lock-up then marks no lock on top that never came.
     *
     * Design 32 (QA L6): every start but [lockUp]'s own ([fromLockUp]) clears [lockOnTop] here, so no
     * call site can forget it - except the ScreenOn backstop of the same lock-up ([sameLockUp]).
     *
     * [noUserAction] (design 59b §3 step 5, the face's re-front only): `FLAG_ACTIVITY_NO_USER_ACTION` - the covered app
     * gets no user-leave hint (no PiP, no Element hang-up).
     */
    fun show(
        context: Context,
        wake: WakeKind? = null,
        ask: LockAsk = LockAsk.AT_ONCE,
        sameLockUp: Boolean = false,
        fromLockUp: Boolean = false,
        noUserAction: Boolean = false,
    ): Boolean {
        if (!fromLockUp) {
            // The backstop's start onto the lock on top: its new-intent pause keeps the mark.
            backstopOntoTopAt = if (sameLockUp && lockOnTop) SystemClock.elapsedRealtime() else null
            newIntentPauseHeld = false
            lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.START, sameLockUp = sameLockUp)
        }
        if (bootMarkHolds(context, if (wake != null) LockAsk.AT_ONCE else ask)) return false
        var started = false
        try {
            context.startActivity(
                // No open animation (bug 25b-B): started while the display sleeps, the lock's
                // transition otherwise played at the next screen-on - Home (its last, unlocked
                // frame) first, the lock sliding in over it. Started with the screen on (a double
                // press that cancels the screen-off fade, design 32 §12), the black splash and
                // Shell's default close of the app the kid was in remain: the lock's own task is a
                // task change, and no public option overrides its transition (qa-32-s2-code M1).
                Intent(context, PinLockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NO_ANIMATION)
                    .addFlags(if (noUserAction) Intent.FLAG_ACTIVITY_NO_USER_ACTION else 0),
            )
            started = true
            when (wake) {
                WakeKind.VOIP -> {
                    voipWakeAskedAt = SystemClock.elapsedRealtime()
                    voipWakeGone = false
                    startOwn(context, VoipWakeActivity.intent(context, WakeKind.VOIP), "the VoIP wake")
                }
                // No voipWakePaused bookkeeping for a notice (17b's try detection stays the ring's).
                WakeKind.NOTICE -> startOwn(context, VoipWakeActivity.intent(context, WakeKind.NOTICE), "the notice wake")
                null -> Unit
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't start the lock screen", e)
        }
        return started
    }

    private val refrontCheck = Runnable { runRefrontCheck() }

    fun onLockResumed(context: Context) {
        lockResumed = true
        // A pause's new intent comes before the resume: a held pause without one stays cleared.
        newIntentPauseHeld = false
        val interactiveNow = interactive(context)
        // Design 32: an interactive resume ends a dark lock-up's mark for Home.
        if (interactiveNow) darkLockUp = false
        // Design 32 step 2: with the screen on, the keyguard cover's re-arm (M4, M7) and VoIP phase (M5).
        KeyguardCover.onLockResumed(context, interactiveNow)
        skipBootMark("the lock is up")
        refrontAttempt = 0
        // Back in front after the alarm: its exemption ends now (qa-10-code #4).
        rememberedAlarmMs = alarmAfterResume(rememberedAlarmMs, yielding)
        yielding = null
        sosFlowUntilElapsed = 0L
        sosMarkUnlogged = false
        narrowForSos(context, false)
        // Back after a call the system dialer showed: the camera policy follows (qa-25b-25f M1).
        updateCallForCamera(context, telecomInCall(context))
        handler.removeCallbacks(refrontCheck)
        androidLossPending = false
        androidWaited = false
        // During our call, the call screen goes back on top (qa-10-code #1).
        dispatch(context.applicationContext, LockEvent.LockResumed(ourCall(), VoipCalls.phase))
        // qa-16d-code #2: a desync that begins with the screen on (a quickstep restart, the lock back
        // after a call, an alarm or a yield) is healed when the lock resumes - at most once a minute.
        val now = SystemClock.elapsedRealtime()
        val afterScreenOffHeal = screenOffHealed
        screenOffHealed = false
        if (lockResumedHealDue(lockResumedHealAt, now, afterScreenOffHeal)) {
            lockResumedHealAt = now
            healStatusBar(context, HealTrigger.LOCK_RESUMED)
        }
    }

    /** PinLockActivity.onPause: while LOCKED the lock may have lost the front to Recents, which only
     * pauses it - the re-front check runs after [PAUSE_REFRONT_MS] (bug 25b-A). */
    fun onLockPaused(finishing: Boolean = false) {
        lockResumed = false
        if (!finishing) {
            appContext?.let { AndroidLock.note(it, "the glance paused") }
            androidLossWhy = "the glance paused"
            lockPausedAt = SystemClock.elapsedRealtime()
        }
        // Design 32: the sleep's own pause keeps the lock on top; an interactive one doesn't - unless
        // it is the backstop's new-intent delivery ([newIntentPauseKept]; the new intent decides).
        val wasOnTop = lockOnTop
        lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.PAUSE, interactive = interactiveOrUnknown())
        newIntentPauseHeld = !lockOnTop && newIntentPauseKept(wasOnTop, backstopOntoTopAt, SystemClock.elapsedRealtime())
        if (!pauseStartsRefront(chromeLocked, finishing)) return
        // The rc.2 run's R1: as Android's face a pause just after a screen-on (the SOS gesture's activity starting; a cold
        // one stops the glance only after ~1 s) is a probable SOS at once - never re-fronted over before its stop.
        val pausedNow = SystemClock.elapsedRealtime()
        val ours = androidLossOurs(androidAskAt ?: androidCancelledAskAt, androidAnsweredAt, ownStartAt, ownOthersResumed > 0, pausedNow)
        if (sosMarkAtPause(AndroidLock.faceOn, ours, lastScreenOnElapsed, pausedNow, sosPackage != null)) noteProbableSos(pausedNow, logNow = false)
        refrontAttempt = 0
        handler.removeCallbacks(refrontCheck)
        // Just after a screen-on the stop that marks Emergency SOS may still come (bug 25b-H).
        markAndroidLoss()
        handler.postDelayed(refrontCheck, pauseRefrontDelayMs(lastScreenOnElapsed, SystemClock.elapsedRealtime()))
    }

    /** PinLockActivity.onStop: unless it's a configuration change or the finish after an unlock,
     * the lock lost the front - bring it back ([refrontAction]). */
    fun onLockStopped(changingConfigurations: Boolean, finishing: Boolean) {
        lockResumed = false
        // Design 59's prototype: an interactive stop is something over the glance - with Android's keyguard
        // locked only a showWhenLocked window, or the keyguard un-occluded (SystemUI's own lock face).
        if (!finishing) {
            appContext?.let { AndroidLock.note(it, "the glance stopped") }
            androidLossWhy = "the glance stopped"
        }
        newIntentPauseHeld = false
        backstopOntoTopAt = null
        lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.STOP, interactive = interactiveOrUnknown())
        if (!stopStartsRefront(chromeLocked, changingConfigurations, finishing)) return
        noteProbableSos(SystemClock.elapsedRealtime())
        refrontAttempt = 0
        handler.removeCallbacks(refrontCheck)
        // As quick as the pause's check (bug 25b-A): whatever came over the lock - Recents, a
        // SystemUI restart, an app - is covered again within PAUSE_REFRONT_MS.
        markAndroidLoss()
        handler.postDelayed(refrontCheck, PAUSE_REFRONT_MS)
    }

    /** qa-59b-code B-3/B-5: the check just scheduled is a fresh loss of the front; a new loss waits for USER_PRESENT anew. */
    private fun markAndroidLoss() {
        androidLossPending = true
        androidWaited = false
    }

    /** PinLockActivity.onDestroy: the next screen-off starts the lock again (design 32). */
    fun onLockDestroyed() {
        newIntentPauseHeld = false
        backstopOntoTopAt = null
        lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.DESTROY)
    }

    /** PinLockActivity.onNewIntent (design 32): after the backstop's new-intent pause the lock is still
     * the top task ([newIntentPauseKept]). Main thread. */
    fun onLockNewIntent() {
        lockOnTop = lockOnTopAfter(lockOnTop, LockTopEvent.NEW_INTENT, sameLockUp = newIntentPauseHeld)
        newIntentPauseHeld = false
        backstopOntoTopAt = null
    }

    /** The Emergency SOS app ([emergencySosPackage]), resolved off the main thread - at init and by
     * every `apply()` (qa-25b-25f L3: no PackageManager work at a lock stop). `null` = none or not yet. */
    @Volatile
    var sosPackage: String? = null

    /** The "sos" yield narrows lock task ([LockTaskChrome.sosYield]) - set on the main thread. */
    private var sosNarrowed = false

    /** The lock left the front: probably the Emergency SOS gesture ([sosGestureLikely], bug 25b-H)? [logNow] false (the
     * face's pause, design 59d N1): the line waits for the pause's check, so the wake's own pause/resume pair logs nothing. */
    private fun noteProbableSos(leftFrontAt: Long, logNow: Boolean = true) {
        val app = appContext ?: return
        // A stop at the screen-off is no SOS (the gesture's presses keep the screen on).
        if (sosFlowUntilElapsed > 0L || !interactive(app)) return
        val now = SystemClock.elapsedRealtime()
        if (!sosGestureLikely(lastScreenOnElapsed, leftFrontAt, sosAppPresent = sosPackage != null)) return
        sosFlowUntilElapsed = now + SOS_FLOW_MS
        sosMarkUnlogged = !logNow
        if (logNow) Log.i(LOG_TAG, SOS_MARK_LINE)
    }

    /** The "sos" yield's narrowed lock task on or off (qa-25b-25f H1). Main thread. */
    private fun narrowForSos(context: Context, on: Boolean) {
        if (sosNarrowed == on) return
        sosNarrowed = on
        Log.i(LOG_TAG, if (on) "SOS yield: lock task narrowed to the lock's helpers and the SOS app" else "SOS yield over: lock task as before")
        try {
            LockTaskChrome.sosYield(context.applicationContext, on)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't change lock task for the SOS yield", e)
        }
    }

    private fun runRefrontCheck() {
        val context = appContext ?: return
        val telecom = telecomInCall(context)
        updateCallForCamera(context, telecom)
        val now = SystemClock.elapsedRealtime()
        val inputs = RefrontInputs(
            locked = chromeLocked,
            lockResumed = lockResumed,
            interactive = interactive(context),
            ourCall = OngoingCalls.hasLiveCall,
            telecomInCall = telecom,
            emergencyFlow = emergencyFlowNow(telecom),
            alarmRinging = alarmNow(context),
            voipCall = VoipCalls.phase != VoipPhase.NONE,
            sosFlow = sosFlowNow(sosFlowUntilElapsed, now, ownOthersResumed > 0),
            coverOn = KeyguardCover.coverOn,
            lockOnTop = lockOnTop,
            // Design 59b §3 (P59-3): the face's loss rule - Android locked under it, or open with USER_PRESENT (no binder
            // call without the face, or with the glance in front).
            android = if (chromeLocked && !lockResumed) AndroidLock.loss(context) else null,
            androidOurs = androidLossOurs(androidAskAt ?: androidCancelledAskAt, androidAnsweredAt, ownStartAt, ownOthersResumed > 0, now),
            androidRefronts = androidRefronts,
            androidFreshLoss = androidLossPending,
            androidWaited = androidWaited,
        )
        androidLossPending = false
        // Design 59d N1: a pause's mark is logged only when its check still finds it (no resume came in between).
        if (sosPauseMarkLogDue(sosMarkUnlogged, sosFlowUntilElapsed > 0L)) Log.i(LOG_TAG, SOS_MARK_LINE)
        sosMarkUnlogged = false
        val action = refrontAction(inputs, refrontAttempt)
        // qa-25b-25f H1: the mark ends on anything but its own yield; while it runs, only our
        // package, the helpers and the SOS app stay in lock task.
        val sos = sosMarkKept(action)
        if (!sos) sosFlowUntilElapsed = 0L
        // 59d D (qa-59b-code B-2): as the face SOS is an exemption only - Android's locked keyguard holds, so lock task
        // isn't narrowed for it.
        narrowForSos(context, sos && inputs.android == null)
        when (action) {
            RefrontAction.Stop -> yielding = null
            is RefrontAction.Yield -> {
                // Design 16e: a call, the emergency flow, an alarm or VoIP skips the boot's wait.
                skipBootMark("yields to ${action.reason}")
                if (yielding != action.reason) {
                    exemptYields++
                    Log.i(LOG_TAG, "Lock yields to an exempt screen: ${action.reason}")
                }
                yielding = action.reason
                refrontAttempt = 0
                handler.postDelayed(refrontCheck, action.recheckMs)
            }
            is RefrontAction.Refront -> {
                yielding = null
                refrontAttempt++
                // Design 59b §3: the face's re-front, counted for the cap.
                if (action.noUserAction) Log.i(ANDROID_LOCK_TAG, "re-front #${++androidRefronts} ($androidLossWhy)")
                // Design 16e: the boot's first lock may still wait for the mark (its backstop ends it).
                if (show(context, ask = LockAsk.BOOT, noUserAction = action.noUserAction)) Log.i(LOG_TAG, "Re-fronting the lock (#$refrontAttempt)")
                handler.postDelayed(refrontCheck, action.nextCheckMs)
            }
            RefrontAction.AndroidWait -> {
                // B-5: Android open with no USER_PRESENT yet - one wait for it, then own mode's backstop.
                yielding = null
                androidWaited = true
                handler.postDelayed(refrontCheck, ANDROID_ANSWER_WAIT_MS)
            }
            RefrontAction.AndroidLeave -> {
                // Design 59b §2 (Q59b-6): the kid unlocked Android - the glance's USER_PRESENT leave (no Home), or with no
                // glance the runtime's own.
                yielding = null
                val hook = androidUnlockHook
                if (hook != null) hook() else dispatch(context, LockEvent.Unlocked(screenOffs, screenOffs))
            }
            RefrontAction.DarkLockUp -> {
                // Design 32 step 2 (M5): a system-dialer call ended while dark under the keyguard cover -
                // the lock-up the screen-off couldn't do, once, so the keyguard is occluded at the wake.
                // The lock alone, as every lock-up under the cover ([lockUpStarts]' coverOn).
                yielding = null
                lockUp(context, LockUpStart.LOCK, LockAsk.AT_ONCE)
                Log.i(LOG_TAG, "Lock-up while dark: LOCK (the re-front check, the keyguard cover)")
            }
        }
    }

    /** "Emergency call" was tapped on the lock less than [EMERGENCY_FLOW_MS] ago (the self-update
     * waits, step 11). */
    val emergencyFlowActive: Boolean
        get() = emergencyFlowUntilElapsed > 0L && SystemClock.elapsedRealtime() < emergencyFlowUntilElapsed

    /** "Emergency call" on the lock was tapped: the dialer/Telecom screens are left alone. */
    fun emergencyFlowStarted() {
        emergencyFlowUntilElapsed = SystemClock.elapsedRealtime() + EMERGENCY_FLOW_MS
        emergencyCallSeen = false
        // A VoIP ring stops at once (qa-16-17-code #1).
        appContext?.let { syncVoipRinger(it) }
    }

    /** The system clock app (resolved off the main thread at init) - its alarms open the alarm
     * exemption (qa-10-code #4), as do the pinned clock app's ([clockPackages]). */
    @Volatile
    private var systemClockPackage: String? = null

    /**
     * The system clock app: a system handler of `AlarmClock.ACTION_SHOW_ALARMS` - never the next
     * alarm's creator, which may be the catalog's clock app; with that one holding the next alarm at
     * process start the system clock's own alarms lost the exemption (qa-r6-b-code L8).
     */
    private fun systemClockApp(context: Context): String? = try {
        context.packageManager.queryIntentActivities(
            android.content.Intent(android.provider.AlarmClock.ACTION_SHOW_ALARMS),
            android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_SYSTEM_ONLY.toLong()),
        ).map { it.activityInfo.packageName }.firstOrNull { it != "android" }
    } catch (e: Exception) {
        null
    }

    /** The clock apps whose alarms show over the lock and under the glance page's date (design 42
     * §1.2): the system clock app and Fossify Clock while its signer is verified. Read-only. */
    fun clockPackages(): Set<String> = lockClockPackages(systemClockPackage, ClockApp.verified)

    private fun rememberAlarm(context: Context) {
        val next = try {
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        } catch (e: Exception) {
            null
        }
        rememberedAlarmMs = rememberAlarm(
            rememberedAlarmMs, next?.triggerTime, next?.showIntent?.creatorPackage, clockPackages(), System.currentTimeMillis(),
        )
    }

    // ---- unlocking ------------------------------------------------------------------------

    sealed interface PinResult {
        /** [pinRev]: the `pin_rev` of the hash the PIN was checked against (design 59 §3.4, Q59-5 - the keypad
         * migration commits this rev, never a newer policy's). */
        data class Ok(val pinRev: Long) : PinResult
        data class Wrong(val failures: Int, val waitMs: Long) : PinResult
        data class Waiting(val waitMs: Long) : PinResult
        /** The stored hash can't be used: only the parent code opens the lock (bad_hash). */
        data object Unusable : PinResult
    }

    private fun clocks() = LockClocks(System.currentTimeMillis(), SystemClock.elapsedRealtime(), BootClock.bootCount())

    /** The time left of the wrong-PIN wait (0 = none). Background thread (commit). */
    @Synchronized
    fun waitRemaining(context: Context): Long = try {
        val app = context.applicationContext
        val now = clocks()
        val stored = PinLockStore.backoff(app)
        val state = refreshBackoff(stored, now)
        if (state != stored) PinLockStore.saveBackoff(app, state)
        backoffRemaining(state, now)
    } catch (e: Exception) {
        // Unreadable: no wait shown - checkPin then refuses the kid PIN (qa-16c-code #3).
        0L
    }

    /**
     * Checks a kid PIN: refused during the wait; the failure (and its wait) is counted and
     * committed **before** PBKDF2 runs and undone on success (QA 10 #3). Background thread.
     */
    @Synchronized
    fun checkPin(context: Context, pin: String): PinResult {
        val app = context.applicationContext
        val cfg = config ?: runCatching { PinLockStore.config(app) }.getOrNull()
        if (cfg == null || !cfg.usable) return PinResult.Unusable
        val now = clocks()
        val counted = try {
            val stored = backoffForHash(PinLockStore.backoff(app), cfg.fingerprint)
            val state = refreshBackoff(stored, now)
            val wait = backoffRemaining(state, now)
            if (wait > 0L) {
                if (state != stored) PinLockStore.saveBackoff(app, state)
                return PinResult.Waiting(wait)
            }
            beginAttempt(state, now).also { PinLockStore.saveBackoff(app, it) }
        } catch (e: Exception) {
            // The failure can't be counted first (QA 10 #3): only the parent code opens the lock
            // then (qa-16c-code #3).
            Log.e(LOG_TAG, "Wrong-PIN count unreadable - parent code only", e)
            return PinResult.Unusable
        }
        return if (PinHash.verify(pin, cfg.hashHex, cfg.saltHex)) {
            runCatching { PinLockStore.saveBackoff(app, attemptSucceeded(counted)) }
            PinResult.Ok(cfg.pinRev)
        } else {
            PinResult.Wrong(counted.failures, backoffRemaining(counted, clocks()))
        }
    }

    /**
     * Design 59 §3.5 (Q59-1): `set_kid_pin`'s PIN against the **fresh** policy's [kidLock] - not the applied config,
     * which still holds the old PIN until this sync's apply. No backoff, no failure counted: the command is no kid's
     * try. Exactly the policy's length in digits ([commandPinShape]). Background thread (PBKDF2).
     */
    fun verifyCommandPin(kidLock: KidLock, pin: String): Boolean = commandPinVerifies(kidLock, pin)

    /** The parent code (the parent PIN, through its own gate and lockout - [ParentPin]): works
     * during the kid PIN's wait and offline; a match opens only this lock - no override - and
     * resets the kid's wait. Not nested: [ParentPin]'s lock is released before this one is taken.
     * Background thread. */
    fun checkParentCode(context: Context, code: String): ParentPinResult {
        val result = ParentPin.check(context, ParentPinEntry.PIN_LOCK, code)
        if (result != ParentPinResult.Ok) return result
        synchronized(this) {
            val app = context.applicationContext
            // An unreadable store never keeps the parent out (qa-16c-code #3).
            try {
                PinLockStore.saveBackoff(app, attemptSucceeded(PinLockStore.backoff(app)))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Backoff reset failed", e)
            }
        }
        return result
    }

    /** The right PIN or parent code was entered, its check started at [checkedAt] screen-offs
     * (main thread). After a screen-off since, nothing opens. */
    fun unlocked(context: Context, checkedAt: Int) =
        dispatch(context.applicationContext, LockEvent.Unlocked(checkedAt, screenOffs))

    /** After an unlock: the time-rule screen comes up if a rule is on (it was never suppressed). */
    private fun afterUnlock(context: Context) {
        try {
            TimeRulesRuntime.recheck(context)
            if (LauncherPreferences.mdm().lockReason() != LockReason.NONE && !OngoingCalls.hasLiveCall) {
                LockActivity.start(context)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Time-rule check after unlock failed", e)
        }
    }

    // ---- remote lock, report ---------------------------------------------------------------

    /** The server's `lock` command: our lock when it's active (then the caller still turns the
     * screen off). Returns whether our lock took it. */
    fun lockNow(context: Context): Boolean {
        if (mode == LockMode.DISABLED) return false
        val app = context.applicationContext
        onMain { dispatch(app, LockEvent.RemoteLock(ourCall(), systemCall(app), VoipCalls.phase)) }
        return true
    }

    /** `lock_state` for the status report: never unlock times or PIN material - design 59's gate names its PIN by
     * the server's rev only ([AndroidLock.report]). Resets the count of exempt-screen yields; the relocks go only once
     * the report landed ([AndroidLock.relocksReported]). */
    fun report(context: Context): LockStateReport {
        val app = context.applicationContext
        val now = clocks()
        val backoff = PinLockStore.backoff(app)
        val wait = backoffRemaining(refreshBackoff(backoff, now), now)
        val yields = exemptYields
        exemptYields = 0
        val android = AndroidLock.report(app, ownRev = (config ?: runCatching { PinLockStore.config(app) }.getOrNull())?.pinRev)
        return LockStateReport(
            active = mode != LockMode.DISABLED,
            inactive = inactive?.wire,
            locked = mode == LockMode.LOCKED,
            failures = backoff.failures,
            backoffUntilMs = if (wait > 0L) now.wallMs + wait else null,
            exemptYields = yields,
            voipFsiDenied = VoipCalls.fsiDenied,
            voipRang = VoipCalls.rangReport(app).map { VoipRang(it.packageName, it.lastAtMs) },
            keyguard = android.keyguard,
            pinRev = android.pinRev,
            resetToken = android.resetToken,
            keyguardRelocks = android.relocks,
        )
    }

    // ---- helpers --------------------------------------------------------------------------

    val pinLength: Int get() = config?.length ?: 4

    val pinUsable: Boolean get() = config?.usable == true

    private fun deviceSecure(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    } catch (e: Exception) {
        true
    }

    private fun interactive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** [interactive], with no context yet counting as interactive - [lockOnTop] is then cleared. */
    private fun interactiveOrUnknown(): Boolean = appContext?.let { interactive(it) } ?: true

    /** A call is live - ours, Telecom's or a VoIP phase other than NONE: unlocking doesn't land on
     * Home over it ([lockLeave], design 32 QA L2). Main thread. */
    fun callLiveNow(context: Context): Boolean =
        ourCall() || telecomInCall(context.applicationContext) || VoipCalls.phase != VoipPhase.NONE

    /** A call the system dialer may show: Telecom's managed calls only - a self-managed app's
     * call (WhatsApp, Signal, a game) never holds the lock open (design 17 QA #7, [managedCallActive]).
     * Without READ_PHONE_STATE a telephony call still sets the audio mode. */
    private fun telecomInCall(context: Context): Boolean {
        val managed = try {
            context.getSystemService(TelecomManager::class.java)?.isInManagedCall
        } catch (e: Exception) {
            null
        }
        val audioMode = try {
            context.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AudioManager.MODE_NORMAL
        }
        return managedCallActive(managed, audioMode)
    }

    private fun ourCall(): Boolean = OngoingCalls.hasLiveCall

    /** A call only the system dialer shows (our in-call UI couldn't be bound, or our dialer role
     * isn't held) - Telecom binds our UI for emergency calls too. */
    private fun systemCall(context: Context): Boolean = !ourCall() && telecomInCall(context)

    private fun requestApply(context: Context) {
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.kidslauncher.mdm.server.AppEnforcer.apply(app, com.kidslauncher.mdm.server.currentPolicyDecision())
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Apply after unlock failed", e)
            }
        }
    }

    private fun requestSync(context: Context, reason: String) {
        try {
            com.kidslauncher.mdm.push.SyncRunner.request(context, reason)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't request a sync", e)
        }
    }
}
