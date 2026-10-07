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
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.PinHash
import com.kidslauncher.mdm.server.dto.LockStateReport
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.timerules.ScreenTimeTracker
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.ui.LockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "PinLock"

/** Status-report capability: this launcher has handy's own PIN lock and reads `kid_lock`. */
const val PIN_LOCK_CAPABILITY = "pin_lock_v1"

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
    private var emergencyCallSeen = false
    private var rememberedAlarmMs: Long? = null
    private var callsSeen = false

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
        startActive = lockActiveAtStart({ Log.e(LOG_TAG, "Lock state unreadable - starting LOCKED", it) }) {
            PinLockStore.active(app) && PinLockStore.guard(app).trippedAtMs == null
        }
        mode = step(LockMode.DISABLED, LockEvent.ProcessStart(startActive, interactive = false)).mode
        decided = true
        Log.i(LOG_TAG, "Process start: $mode (decided before any screen)")
    }

    /** For Home while [decided] is false (16c): the stored lock state or the cached policy says a
     * kid PIN is set. Unreadable counts as set. */
    fun pinSetStored(context: Context): Boolean = try {
        val app = context.applicationContext
        PinLockStore.active(app) || PinLockStore.config(app) != null ||
            (com.kidslauncher.mdm.server.cachedPolicy() as? com.kidslauncher.mdm.server.CachedPolicy.Ok)?.policy?.kidLock != null
    } catch (e: Exception) {
        true
    }

    /** HomeActivity resumed (16c, [homeShown]). */
    fun onHomeResumed() {
        homeShown = true
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
        decide(app)
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
        OngoingCalls.addListener(callsListener)
        // Design 17: a VoIP call's stored exemption keeps its package pinned from the first chrome
        // refresh on, until the notification listener reports again (QA #4).
        VoipCalls.init(app)
        // Off the main thread: the clock app for the alarm exemption, and the kiosk-off lock
        // helpers, so a screen-off never waits for PackageManager (qa-10-code #7).
        CoroutineScope(Dispatchers.IO).launch {
            systemClockPackage = try {
                com.kidslauncher.mdm.server.alarmAppPackage(app)?.takeIf { pkg ->
                    (app.packageManager.getApplicationInfo(pkg, 0).flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                }
            } catch (e: Exception) {
                null
            }
            LockTaskChrome.prefetchHelpers(app)
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
                LockEvent.ProcessStart(active, interactive(app), ourCall(), systemCall(app), homeFirst = homeFirst, voip = VoipCalls.phase),
                from = LockMode.DISABLED,
            )
            // The chrome of a lock that was LOCKED when the process died is still set; an inactive
            // lock must not leave it behind either. Same for the camera lock: released
            // (idempotently). LOCKED did both in the dispatch.
            if (!chromeLocked) {
                refreshChrome(app)
                CameraLock.onLockChanged(app)
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
        // The lock's start first, so its window covers the screen at once; then, on the LOCKED
        // edge, the chrome on this thread before anything else - the lock resumes only after this
        // returns, so the shade, overlays and Overview are blocked no later than the lock is up
        // (qa-16c-code #1; status bar first, no PackageManager work). Every other chrome change
        // runs on its own thread ([refreshChrome]).
        if (result.showLock) show(context, wake = result.wake)
        if (lockedEdge) refreshChromeNow(context)
        // Home was started first (design 16): the re-front check shows the lock if Home didn't.
        if (result.showLockLater) {
            refrontAttempt = 0
            handler.removeCallbacks(refrontCheck)
            handler.postDelayed(refrontCheck, LOCK_FALLBACK_MS)
        }
        if (before != result.mode) {
            Log.i(LOG_TAG, "$before -> ${result.mode} on $event")
            if ((before == LockMode.LOCKED) != (result.mode == LockMode.LOCKED)) {
                if (!lockedEdge) refreshChrome(context)
                // The camera gesture must not open a camera over the lock (background thread).
                CameraLock.onLockChanged(context)
                if (result.mode != LockMode.LOCKED && !LockTaskChrome.hasPlan) requestApply(context)
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
        if (result.showCall) showCall(context)
        // Design 17: the VoIP app's call screen back over the lock (sent from the resumed lock).
        if (result.showVoipCall && !VoipCalls.reopenCall(context)) Log.i(LOG_TAG, "No VoIP call screen to bring back")
        // LOCKED but not shown (the system dialer's call): the re-front loop waits for it to end.
        if (result.mode == LockMode.LOCKED && !result.showLock && !result.showLockLater && !lockResumed && before != LockMode.LOCKED) {
            refrontAttempt = 0
            handler.removeCallbacks(refrontCheck)
            handler.postDelayed(refrontCheck, refrontDelayMs(0))
        }
        if (result.recheckTimeRules) afterUnlock(context)
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

    /** Our call screen in front of the lock (inside the lock task - our package is pinned). */
    private fun showCall(context: Context) {
        try {
            context.startActivity(com.kidslauncher.mdm.calls.InCallActivity.intent(context))
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
                // The lock first: started now, it is drawn before the next screen-on.
                rememberAlarm(app)
                // The power button silences a VoIP ring on a ringing lock; a ring that began
                // unlocked starts ringing at this screen-off instead (qa-16-17-code #5).
                if (screenOffSilencesRing(mode == LockMode.LOCKED, VoipCalls.phase == VoipPhase.RINGING)) {
                    silencedRing = VoipCalls.ringId
                }
                dispatch(app, LockEvent.ScreenOff(ourCall(), systemCall(app), VoipCalls.phase))
                syncVoipRinger(app)
                ScreenTimeTracker.update(app)
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
            } else {
                // First, synchronously: end the Play update window before anything else (QA
                // step 7 #8); the full re-apply follows in the background.
                if (intent.action == Intent.ACTION_SCREEN_ON) {
                    PlayRuntime.suspendStoreAtScreenOn(app)
                    // No self-update commit while someone uses the phone (step 11).
                    com.kidslauncher.mdm.server.SelfUpdate.onScreenOn(app)
                }
                // May start the time-rule screen - the PIN lock then goes above it.
                TimeRulesRuntime.recheck(app)
                dispatch(app, LockEvent.ScreenOn(lockResumed, ourCall(), systemCall(app), VoipCalls.phase))
                // Backstop for the migration (QA 10 #15): the Android credential is gone but no
                // onPasswordChanged arrived - check now rather than at the next sync.
                if (intent.action == Intent.ACTION_USER_PRESENT && inactive == LockInactive.ANDROID_CREDENTIAL && !deviceSecure(app)) {
                    requestApply(app)
                }
            }
        }
    }

    private val callsListener: () -> Unit = {
        val ctx = appContext
        val any = OngoingCalls.hasLiveCall
        if (ctx != null && callsSeen && !any) dispatch(ctx, LockEvent.CallsEnded(interactive(ctx)))
        callsSeen = any
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
        val ours = ourCall()
        val emergency = emergencyFlowNow(telecom)
        val alarm = alarmNow(app)
        if (after == VoipPhase.RINGING && before != VoipPhase.RINGING) {
            dispatch(app, LockEvent.VoipRinging(ours, !ours && telecom, emergency, alarm))
        }
        if (after == VoipPhase.NONE && before != VoipPhase.NONE) {
            dispatch(app, LockEvent.VoipEnded(interactive(app), ours, !ours && telecom, emergency, alarm))
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
        )
        if (wanted) VoipRinger.start(app) else if (VoipRinger.active) VoipRinger.stop(app)
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

    /** A time-rule screen was just started over everything: the PIN lock goes on top (QA 10 #4:
     * the time rules are never suppressed by the PIN lock). */
    fun afterTimeRuleShown(context: Context) = onMain { dispatch(context.applicationContext, LockEvent.TimeRuleShown) }

    // ---- configuration (from AppEnforcer.apply, background thread) ------------------------

    /**
     * Design §3: decides whether the lock is active and switches Android's keyguard off for it
     * (`setKeyguardDisabled(true)` = screen lock "None", only possible without an Android
     * credential). Re-checked on every apply; an override or pause doesn't switch the lock off.
     */
    fun configure(context: Context, dpm: DevicePolicyManager, admin: ComponentName, policy: PolicyResponse?, managed: Boolean) {
        val app = context.applicationContext
        val kidLock = policy?.kidLock
        val newConfig = kidLock?.let { KidLockConfig(it.pinHash, it.pinSalt, kidPinLength(it.pinLength)) }
        val deviceSecure = try {
            app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
        } catch (e: Exception) {
            false
        }
        val wantDisabled = wantKeyguardDisabled(managed, newConfig != null, deviceSecure)
        val keyguardDisabled = try {
            // false while a credential exists; true also re-dismisses a showing keyguard.
            dpm.setKeyguardDisabled(admin, wantDisabled) && wantDisabled
        } catch (e: Exception) {
            Log.w(LOG_TAG, "setKeyguardDisabled($wantDisabled) failed", e)
            false
        }
        // The crash guard re-arms at a sync at most once every 10 minutes.
        val guard = guardRearm(PinLockStore.guard(app), System.currentTimeMillis())
        PinLockStore.saveGuard(app, guard)
        val (active, why) = lockActivation(
            managed = managed,
            hasKidPin = newConfig != null,
            deviceSecure = deviceSecure,
            keyguardDisabled = keyguardDisabled,
            hashUsable = newConfig?.usable == true,
            guardTripped = guard.trippedAtMs != null,
        )
        // A new PIN from the parent resets the wrong-PIN count and wait (design §5).
        synchronized(this) {
            val backoff = PinLockStore.backoff(app)
            val forHash = backoffForHash(backoff, newConfig?.fingerprint)
            if (forHash != backoff) PinLockStore.saveBackoff(app, forHash)
        }
        if (newConfig != config || active != PinLockStore.active(app) || why != inactive) {
            PinLockStore.saveConfig(app, newConfig, active, why)
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
     * [wake]: turn the screen on for it (a VoIP ring, design 17). The lock itself never sets
     * turn-screen-on - an existing, stopped lock would learn it only after its start, and a left-over
     * bit would wake a later start in a pocket (qa-16-17-code #2): a fresh [VoipWakeActivity], whose
     * manifest says `turnScreenOn`, goes on top of it in its task and finishes itself.
     */
    fun show(context: Context, wake: Boolean = false) {
        try {
            context.startActivity(
                Intent(context, PinLockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
            if (wake) context.startActivity(VoipWakeActivity.intent(context))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't start the lock screen", e)
        }
    }

    private val refrontCheck = Runnable { runRefrontCheck() }

    fun onLockResumed(context: Context) {
        lockResumed = true
        refrontAttempt = 0
        // Back in front after the alarm: its exemption ends now (qa-10-code #4).
        rememberedAlarmMs = alarmAfterResume(rememberedAlarmMs, yielding)
        yielding = null
        handler.removeCallbacks(refrontCheck)
        // During our call, the call screen goes back on top (qa-10-code #1).
        dispatch(context.applicationContext, LockEvent.LockResumed(ourCall(), VoipCalls.phase))
    }

    fun onLockPaused() {
        lockResumed = false
    }

    /** PinLockActivity.onStop: unless it's a configuration change or the finish after an unlock,
     * the lock lost the front - bring it back ([refrontAction]). */
    fun onLockStopped(changingConfigurations: Boolean, finishing: Boolean) {
        lockResumed = false
        if (!stopStartsRefront(chromeLocked, changingConfigurations, finishing)) return
        refrontAttempt = 0
        handler.removeCallbacks(refrontCheck)
        handler.postDelayed(refrontCheck, refrontDelayMs(0))
    }

    private fun runRefrontCheck() {
        val context = appContext ?: return
        val telecom = telecomInCall(context)
        val inputs = RefrontInputs(
            locked = chromeLocked,
            lockResumed = lockResumed,
            interactive = interactive(context),
            ourCall = OngoingCalls.hasLiveCall,
            telecomInCall = telecom,
            emergencyFlow = emergencyFlowNow(telecom),
            alarmRinging = alarmNow(context),
            voipCall = VoipCalls.phase != VoipPhase.NONE,
        )
        when (val action = refrontAction(inputs, refrontAttempt)) {
            RefrontAction.Stop -> yielding = null
            is RefrontAction.Yield -> {
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
                Log.i(LOG_TAG, "Re-fronting the lock (#$refrontAttempt)")
                show(context)
                handler.postDelayed(refrontCheck, action.nextCheckMs)
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

    /** The system clock app (resolved off the main thread at init) - only its alarms open the
     * alarm exemption (qa-10-code #4). */
    @Volatile
    private var systemClockPackage: String? = null

    private fun rememberAlarm(context: Context) {
        val next = try {
            context.getSystemService(AlarmManager::class.java)?.nextAlarmClock
        } catch (e: Exception) {
            null
        }
        rememberedAlarmMs = rememberAlarm(
            rememberedAlarmMs, next?.triggerTime, next?.showIntent?.creatorPackage, systemClockPackage, System.currentTimeMillis(),
        )
    }

    // ---- unlocking ------------------------------------------------------------------------

    sealed interface PinResult {
        data object Ok : PinResult
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
            PinResult.Ok
        } else {
            PinResult.Wrong(counted.failures, backoffRemaining(counted, clocks()))
        }
    }

    /** The parent code (the override PIN): its own 5/15-minute counter, works during the kid PIN's
     * wait and offline; a match opens only this lock - no override. Background thread. */
    fun checkParentCode(context: Context, code: String): Boolean {
        if (!OfflineOverride.verifyPin(code)) return false
        synchronized(this) {
            val app = context.applicationContext
            // An unreadable store never keeps the parent out (qa-16c-code #3).
            try {
                PinLockStore.saveBackoff(app, attemptSucceeded(PinLockStore.backoff(app)))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Backoff reset failed", e)
            }
        }
        return true
    }

    /** The right PIN or parent code was entered (main thread). */
    fun unlocked(context: Context) = dispatch(context.applicationContext, LockEvent.Unlocked)

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

    /** `lock_state` for the status report: never unlock times or PIN material. Resets the count of
     * exempt-screen yields. */
    fun report(context: Context): LockStateReport {
        val app = context.applicationContext
        val now = clocks()
        val backoff = PinLockStore.backoff(app)
        val wait = backoffRemaining(refreshBackoff(backoff, now), now)
        val yields = exemptYields
        exemptYields = 0
        return LockStateReport(
            active = mode != LockMode.DISABLED,
            inactive = inactive?.wire,
            locked = mode == LockMode.LOCKED,
            failures = backoff.failures,
            backoffUntilMs = if (wait > 0L) now.wallMs + wait else null,
            exemptYields = yields,
            voipFsiDenied = VoipCalls.fsiDenied,
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
                com.kidslauncher.mdm.server.AppEnforcer.apply(app, com.kidslauncher.mdm.server.currentPolicyDecision().policy)
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
