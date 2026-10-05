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

    private val modeListeners = mutableListOf<() -> Unit>()
    private var refrontAttempt = 0
    private var yielding: String? = null
    @Volatile
    private var exemptYields = 0
    private var emergencyFlowUntilElapsed = 0L
    private var emergencyCallSeen = false
    private var rememberedAlarmMs: Long? = null
    private var callsSeen = false

    fun addModeListener(listener: () -> Unit) { modeListeners += listener }
    fun removeModeListener(listener: () -> Unit) { modeListeners -= listener }

    // ---- start-up -------------------------------------------------------------------------

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
        config = PinLockStore.config(app)
        val active = PinLockStore.active(app) && PinLockStore.guard(app).trippedAtMs == null
        inactive = PinLockStore.inactive(app)?.let { wire -> LockInactive.entries.firstOrNull { it.wire == wire } }
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
            dispatch(app, LockEvent.ProcessStart(active, interactive(app), ourCall(), systemCall(app)))
            // The chrome of a lock that was LOCKED when the process died is still set; an inactive
            // lock must not leave it behind either.
            LockTaskChrome.refresh(app)
        }
    }

    // ---- events ---------------------------------------------------------------------------

    private fun dispatch(context: Context, event: LockEvent) {
        val before = mode
        val result = step(before, event)
        mode = result.mode
        // The lock first: the chrome below may do binder calls (and, the first time with the kiosk
        // off, PackageManager work) - the lock screen's start must not wait for them (qa-10-code
        // #7). It only resumes after this returns, by when its lock-task packages are set.
        if (result.showLock) show(context)
        if (before != result.mode) {
            Log.i(LOG_TAG, "$before -> ${result.mode} on $event")
            if ((before == LockMode.LOCKED) != (result.mode == LockMode.LOCKED)) {
                try {
                    LockTaskChrome.refresh(context)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Lock chrome change failed", e)
                }
                if (result.mode != LockMode.LOCKED && !LockTaskChrome.hasPlan) requestApply(context)
            }
            modeListeners.toList().forEach { it() }
        }
        if (result.showCall) showCall(context)
        // LOCKED but not shown (the system dialer's call): the re-front loop waits for it to end.
        if (result.mode == LockMode.LOCKED && !result.showLock && !lockResumed && before != LockMode.LOCKED) {
            refrontAttempt = 0
            handler.removeCallbacks(refrontCheck)
            handler.postDelayed(refrontCheck, refrontDelayMs(0))
        }
        if (result.recheckTimeRules) afterUnlock(context)
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
                dispatch(app, LockEvent.ScreenOff(ourCall(), systemCall(app)))
                ScreenTimeTracker.update(app)
                // The nightly Play update window opens when the screen goes off inside it.
                try {
                    reevaluateLockReasonFromCache(app)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Re-check at screen off failed", e)
                }
            } else {
                // First, synchronously: end the Play update window before anything else (QA
                // step 7 #8); the full re-apply follows in the background.
                if (intent.action == Intent.ACTION_SCREEN_ON) PlayRuntime.suspendStoreAtScreenOn(app)
                // May start the time-rule screen - the PIN lock then goes above it.
                TimeRulesRuntime.recheck(app)
                dispatch(app, LockEvent.ScreenOn(lockResumed, ourCall(), systemCall(app)))
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
        val any = OngoingCalls.calls.isNotEmpty()
        if (ctx != null && callsSeen && !any) dispatch(ctx, LockEvent.CallsEnded(interactive(ctx)))
        callsSeen = any
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

    fun show(context: Context) {
        try {
            context.startActivity(
                Intent(context, PinLockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
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
        dispatch(context.applicationContext, LockEvent.LockResumed(ourCall()))
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
        if (emergencyFlowUntilElapsed > 0L && telecom) emergencyCallSeen = true
        val emergencyFlow = SystemClock.elapsedRealtime() < emergencyFlowUntilElapsed && !(emergencyCallSeen && !telecom)
        rememberAlarm(context)
        val inputs = RefrontInputs(
            locked = chromeLocked,
            lockResumed = lockResumed,
            interactive = interactive(context),
            ourCall = OngoingCalls.calls.isNotEmpty(),
            telecomInCall = telecom,
            emergencyFlow = emergencyFlow,
            alarmRinging = alarmLikelyRinging(rememberedAlarmMs, System.currentTimeMillis()),
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

    /** "Emergency call" on the lock was tapped: the dialer/Telecom screens are left alone. */
    fun emergencyFlowStarted() {
        emergencyFlowUntilElapsed = SystemClock.elapsedRealtime() + EMERGENCY_FLOW_MS
        emergencyCallSeen = false
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
    fun waitRemaining(context: Context): Long {
        val app = context.applicationContext
        val now = clocks()
        val stored = PinLockStore.backoff(app)
        val state = refreshBackoff(stored, now)
        if (state != stored) PinLockStore.saveBackoff(app, state)
        return backoffRemaining(state, now)
    }

    /**
     * Checks a kid PIN: refused during the wait; the failure (and its wait) is counted and
     * committed **before** PBKDF2 runs and undone on success (QA 10 #3). Background thread.
     */
    @Synchronized
    fun checkPin(context: Context, pin: String): PinResult {
        val app = context.applicationContext
        val cfg = config ?: PinLockStore.config(app)
        if (cfg == null || !cfg.usable) return PinResult.Unusable
        val now = clocks()
        val stored = backoffForHash(PinLockStore.backoff(app), cfg.fingerprint)
        val state = refreshBackoff(stored, now)
        val wait = backoffRemaining(state, now)
        if (wait > 0L) {
            if (state != stored) PinLockStore.saveBackoff(app, state)
            return PinResult.Waiting(wait)
        }
        val counted = beginAttempt(state, now)
        PinLockStore.saveBackoff(app, counted)
        return if (PinHash.verify(pin, cfg.hashHex, cfg.saltHex)) {
            PinLockStore.saveBackoff(app, attemptSucceeded(counted))
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
            PinLockStore.saveBackoff(app, attemptSucceeded(PinLockStore.backoff(app)))
        }
        return true
    }

    /** The right PIN or parent code was entered (main thread). */
    fun unlocked(context: Context) = dispatch(context.applicationContext, LockEvent.Unlocked)

    /** After an unlock: the time-rule screen comes up if a rule is on (it was never suppressed). */
    private fun afterUnlock(context: Context) {
        try {
            TimeRulesRuntime.recheck(context)
            if (LauncherPreferences.mdm().lockReason() != LockReason.NONE && OngoingCalls.calls.isEmpty()) {
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
        onMain { dispatch(app, LockEvent.RemoteLock(ourCall(), systemCall(app))) }
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

    private fun telecomInCall(context: Context): Boolean = try {
        context.getSystemService(TelecomManager::class.java)?.isInCall == true
    } catch (e: Exception) {
        // No READ_PHONE_STATE: a telephony call still sets the audio mode.
        context.getSystemService(AudioManager::class.java)?.mode == AudioManager.MODE_IN_CALL
    }

    private fun ourCall(): Boolean = OngoingCalls.calls.isNotEmpty()

    /** A call only the system dialer shows (emergency, or our dialer role not held). */
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
