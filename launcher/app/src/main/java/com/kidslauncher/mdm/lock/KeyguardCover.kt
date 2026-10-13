package com.kidslauncher.mdm.lock

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.ScreenTimeout
import java.util.concurrent.Executors

private const val LOG_TAG = "KeyguardCover"

/**
 * Design 32 step 2 (docs/design/32-lock-flash.md §8 step 2): the keyguard cover - a device experiment
 * behind `KeyguardCoverDebug`'s switch (debug builds only; the release one is always false, and then
 * everything here writes exactly what step 1 wrote and waits for nothing). The decisions are pure
 * ([keyguardMode], [writeKeyguard], [dismissDue] ... in KeyguardCoverPlan.kt); this is the glue.
 *
 * **The one keyguard writer** (H2, M6): `setKeyguardDisabled`, `setKeyguardDisabledFeatures` and
 * `setMaximumTimeToLock` are called only here - from [configure] (every apply), [rewrite] (the VoIP
 * edge, the re-arm, the fallback), [followTimeout] (the screen timeout, H1) and [disableNow] (the
 * shutdown, M4, and the crash guard, L8).
 *
 * The lock's dismissal (M7) lives in `PinLockActivity`; its state - the failures, the request - here,
 * on the main thread. So do the screens of other apps that wait for the unlock ([whenUnlocked], M5).
 */
object KeyguardCover {

    /** The debug switch, cached by [configure] and [loadSwitch] - never read from prefs on the main thread (L5). */
    @Volatile
    var switchOn = false
        private set

    /** COVER is in force: the features, T and `setKeyguardDisabled(false)` all held. */
    @Volatile
    var coverOn = false
        private set

    /** The debug receiver's dismissal knob ([DismissTest]); nothing else sets it. */
    @Volatile
    var dismissTest = DismissTest.NONE

    /** [configure]'s last inputs (`null` before the first apply in this process). */
    private data class Inputs(val managed: Boolean, val hasKidPin: Boolean, val deviceSecure: Boolean, val guardTripped: Boolean)

    @Volatile
    private var inputs: Inputs? = null

    /** The VoIP phase as last seen with the screen on (M5: written only while interactive). */
    @Volatile
    private var voipSeen = VoipPhase.NONE

    /**
     * The cover may be written: a lock resume with the screen on since this process started (M4 - a
     * clean boot comes up with the keyguard off since `ACTION_SHUTDOWN`) and since the last fallback (M7).
     */
    @Volatile
    var armed = false
        private set

    /** The fallback's wake isn't over: no re-arm before the next dark screen-off ([coverRearmDue]). */
    @Volatile
    private var wakePending = false

    /** The last write, for the debug receiver's report. */
    @Volatile
    private var last: KeyguardWritten? = null

    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "keyguard-cover").apply { isDaemon = true } }

    // ---- the writer ----------------------------------------------------------------------------

    /** The debug switch, off the main thread at the process start ([PinLockRuntime.init]) - Home's boot
     * dismissal may need it before the first apply. */
    fun loadSwitch(context: Context) {
        switchOn = try {
            KeyguardCoverDebug.enabled(context.applicationContext)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * From `PinLockRuntime.configure` (every apply, background thread): writes the keyguard for these
     * inputs and returns `lockActivation`'s keyguard input - the cover set, or `setKeyguardDisabled(true)`
     * worked ([KeyguardWritten.lockMayRun]). A failed cover is today's disabled keyguard (H2).
     */
    @Synchronized
    fun configure(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        managed: Boolean,
        hasKidPin: Boolean,
        deviceSecure: Boolean,
        guardTripped: Boolean,
    ): Boolean {
        val app = context.applicationContext
        switchOn = try {
            KeyguardCoverDebug.enabled(app)
        } catch (e: Exception) {
            false
        }
        inputs = Inputs(managed, hasKidPin, deviceSecure, guardTripped)
        return write(app, dpm, admin, "apply").lockMayRun
    }

    /** [configure]'s inputs again, on the writer thread: the VoIP edge, the re-arm, the fallback. */
    private fun rewrite(context: Context, why: String) {
        val app = context.applicationContext
        writer.execute {
            try {
                val (dpm, admin) = owner(app) ?: return@execute
                synchronized(this) { write(app, dpm, admin, why) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Keyguard write ($why) failed", e)
            }
        }
    }

    /** Caller holds the monitor. */
    private fun write(app: Context, dpm: DevicePolicyManager, admin: ComponentName, why: String, forceOff: Boolean = false): KeyguardWritten {
        val i = inputs ?: Inputs(managed = false, hasKidPin = false, deviceSecure = false, guardTripped = false)
        val wasOn = coverOn
        val wanted = if (forceOff) {
            keyguardMode(i.managed, i.hasKidPin, i.deviceSecure, coverWanted = false, VoipPhase.NONE, i.guardTripped)
        } else {
            keyguardMode(i.managed, i.hasKidPin, i.deviceSecure, coverWanted = switchOn && armed, voipSeen, i.guardTripped)
        }
        val written = writeKeyguard(DpmKeyguard(dpm, admin), wanted, i.managed, coverAdded(app), { ScreenTimeout.effectiveMs(app) }) {
            storeCoverAdded(app, it)
        }
        coverOn = written.mode == KeyguardMode.COVER
        last = written
        // Release (the switch never on) logs nothing new.
        if (switchOn || wasOn || coverOn) {
            Log.i(LOG_TAG, "mode=${written.mode} features=${written.features?.let { "0x" + Integer.toHexString(it) }} " +
                "maxTimeToLock=${written.maxTimeToLockMs} disabled=${written.disabled} ($why${if (switchOn && !armed) ", held off until a lock resume with the screen on" else ""})")
        }
        written.coverFailed?.let {
            Log.w(LOG_TAG, "The cover couldn't be set ($it) - the keyguard stays disabled, the PIN lock active")
        }
        if (wasOn && !coverOn) handler.post { runWaiting(app, "the cover is off") }
        return written
    }

    /**
     * M4 / L8: `setKeyguardDisabled(true)` at once while the cover is on - [synchronous] at
     * `ACTION_SHUTDOWN` (a clean reboot has no keyguard), on the writer thread for the crash guard.
     * Nothing while the cover is off (release: as before).
     */
    fun disableNow(context: Context, why: String, synchronous: Boolean) {
        if (!coverOn) return
        val app = context.applicationContext
        val run = Runnable {
            try {
                val (dpm, admin) = owner(app) ?: return@Runnable
                synchronized(this) {
                    val ok = dpm.setKeyguardDisabled(admin, true)
                    coverOn = false
                    armed = false
                    Log.i(LOG_TAG, "disabled now ($why): setKeyguardDisabled(true)=$ok")
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "setKeyguardDisabled(true) ($why) failed", e)
            }
        }
        if (synchronous) run.run() else writer.execute(run)
    }

    /** L8: the crash guard tripped - no Swipe keyguard without a handy lock, at once. */
    fun guardTripped(context: Context) {
        inputs = inputs?.copy(guardTripped = true)
        disableNow(context, "crash guard", synchronous = false)
    }

    /** M4: `ACTION_SHUTDOWN` ([BootCover]'s receiver, synchronous). */
    fun onShutdown(context: Context) = disableNow(context, "shutdown", synchronous = true)

    /**
     * H1: [ScreenTimeout]'s write of `SCREEN_OFF_TIMEOUT` (it stays the only writer of the setting),
     * with T in the safe order ([timeoutWriteOrder]): shrinking, T first; growing, the setting first. T
     * then follows the setting in force - also after a failed write. Without the cover: just the write.
     */
    fun followTimeout(context: Context, dpm: DevicePolicyManager, admin: ComponentName, oldMs: Long?, newMs: Long, writeSetting: () -> Boolean): Boolean =
        synchronized(this) {
            if (!coverOn) return@synchronized writeSetting()
            val app = context.applicationContext
            if (timeoutWriteOrder(oldMs, newMs) == TimeoutWriteOrder.T_FIRST) setT(app, dpm, admin, newMs, "the timeout shrinks")
            val ok = writeSetting()
            setT(app, dpm, admin, ScreenTimeout.effectiveMs(app), "the timeout changed")
            ok
        }

    /** H1: the setting changed without our write (Release: the phone's own Settings) - T follows it. */
    fun timeoutChanged(context: Context, dpm: DevicePolicyManager, admin: ComponentName) = synchronized(this) {
        val app = context.applicationContext
        setT(app, dpm, admin, ScreenTimeout.effectiveMs(app), "the setting changed")
    }

    /** T = [ms] under the cover, read back; a failure falls back as in [writeKeyguard] (H1, H2). Monitor held. */
    private fun setT(app: Context, dpm: DevicePolicyManager, admin: ComponentName, ms: Long?, why: String) {
        if (!coverOn) return
        val t = maxTimeToLock(coverOn = true, effectiveTimeoutMs = ms)
        val ok = t > 0L && try {
            if (dpm.getMaximumTimeToLock(admin) != t) dpm.setMaximumTimeToLock(admin, t)
            dpm.getMaximumTimeToLock(admin) == t
        } catch (e: Exception) {
            false
        }
        if (ok) {
            Log.i(LOG_TAG, "maxTimeToLock=$t ($why)")
        } else {
            Log.w(LOG_TAG, "maxTimeToLock=$t couldn't be set ($why) - the cover falls back")
            write(app, dpm, admin, "max time to lock failed", forceOff = true)
        }
    }

    /** What is in force now - the debug receiver's report. */
    fun describe(): String {
        val w = last ?: return "no keyguard write in this process yet"
        return "mode=${w.mode} features=${w.features?.let { "0x" + Integer.toHexString(it) }} maxTimeToLock=${w.maxTimeToLockMs} " +
            "disabled=${w.disabled}${w.coverFailed?.let { " coverFailed=$it" } ?: ""} armed=$armed dismissTest=$dismissTest"
    }

    // ---- the lock's side (main thread) ---------------------------------------------------------------

    /** Failures in a row ([dismissFailed]) and the open request (elapsed ms). Main thread. */
    private var failures = 0
    private var requestedAtMs: Long? = null

    /**
     * From `PinLockRuntime.onLockResumed`: with the screen on, M4/M7's re-arm ([coverRearmDue]) and M5's
     * VoIP phase are written now - in place before the next sleep. Never at a screen-off (too late, M7.4).
     */
    fun onLockResumed(context: Context, interactive: Boolean) {
        var why: String? = null
        if (coverRearmDue(heldOff = !armed, interactive = interactive, lockResumed = true, wakePending = wakePending)) {
            armed = true
            why = "re-armed"
        }
        if (interactive && VoipCalls.phase != voipSeen) {
            voipSeen = VoipCalls.phase
            why = why ?: "VoIP ${voipSeen.name}"
        }
        if (why != null && switchOn) {
            if (why == "re-armed") Log.i(LOG_TAG, "re-armed")
            rewrite(context, why)
        }
    }

    /** M5: a VoIP phase edge - DISABLED in a call or ring, COVER at NONE - written only with the screen on. */
    fun onVoipPhase(context: Context, after: VoipPhase, interactive: Boolean) {
        if (!interactive || after == voipSeen) return
        voipSeen = after
        if (switchOn) rewrite(context, "VoIP ${after.name}")
    }

    /** A screen-off while dark: the fallback's wake is over ([coverRearmDue]). */
    fun onScreenOff(interactive: Boolean) {
        if (!interactive) wakePending = false
    }

    /** M7: the lock asks now - the first frame since its resume ([dismissDue]). */
    fun dismissDueNow(context: Context, resumed: Boolean, drawnSinceResume: Boolean, triedThisResume: Boolean): Boolean =
        coverOn && dismissTest != DismissTest.SKIP &&
            dismissDue(coverOn, resumed, interactive(context), keyguardLocked(context), drawnSinceResume, triedThisResume)

    /** The lock asked (or, with [DismissTest.FAIL], pretends to). Returns whether to really call. */
    fun dismissRequested(): Boolean {
        requestedAtMs = SystemClock.elapsedRealtime()
        return dismissTest == DismissTest.NONE
    }

    /**
     * M7, the state check - [DISMISS_CHECK_MS] after a request and once a second while the lock is
     * resumed: unlocked = a success (the count resets, the waiting screens go); still locked a second
     * after the request = one failure, the 2nd falls back; locked with no request open (a SystemUI
     * restart, a late keyguard) = ask again. Returns whether the lock asks now.
     */
    fun check(context: Context, resumed: Boolean, drawnSinceResume: Boolean): Boolean {
        if (!coverOn) {
            requestedAtMs = null
            return false
        }
        val locked = keyguardLocked(context)
        if (!locked) {
            if (requestedAtMs != null) Log.i(LOG_TAG, "dismissed")
            requestedAtMs = null
            failures = 0
            runWaiting(context, "unlocked")
            return false
        }
        val now = SystemClock.elapsedRealtime()
        if (dismissFailed(requestedAtMs, now, locked)) {
            failures++
            requestedAtMs = null
            Log.w(LOG_TAG, "dismissal failed (state), n=$failures")
            if (dismissFallback(failures)) {
                fallback(context)
                return false
            }
        }
        return requestedAtMs == null && resumed && drawnSinceResume && dismissTest != DismissTest.SKIP && interactive(context)
    }

    /** The dismissal's callbacks: logged only (M7) - a success lets the waiting screens go at once. */
    fun dismissCallback(context: Context, result: String) {
        if (result == "succeeded") {
            Log.i(LOG_TAG, "dismissed (callback)")
            requestedAtMs = null
            failures = 0
            runWaiting(context, "dismissed")
        } else {
            Log.w(LOG_TAG, "dismissal failed (callback: $result), n=$failures")
        }
    }

    /** M7: `setKeyguardDisabled(true)` (dismisses) until a lock resume with the screen on after the next
     * dark screen-off re-arms it. */
    private fun fallback(context: Context) {
        Log.w(LOG_TAG, "fallback")
        failures = 0
        armed = false
        wakePending = true
        rewrite(context, "fallback")
    }

    // ---- other apps' screens wait for the unlock (M5, main thread) ---------------------------------------

    private val waiting = LinkedHashMap<String, (Context) -> Unit>()

    /** Whether a screen sent from the lock must wait now ([sendAfterUnlock]). */
    fun mustWait(context: Context): Boolean = coverOn && sendAfterUnlock(coverOn, keyguardLocked(context))

    /**
     * Runs [send] now, or - the cover's keyguard locked ([sendAfterUnlock]) - once it is unlocked: the
     * dismissal's success or the state check. One per [key], the newest kept. Main thread.
     */
    fun whenUnlocked(context: Context, key: String, send: (Context) -> Unit) {
        if (!mustWait(context)) {
            send(context)
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            handler.post { whenUnlocked(context, key, send) }
            return
        }
        Log.i(LOG_TAG, "$key waits for the keyguard's dismissal")
        waiting.remove(key)
        waiting[key] = send
    }

    private fun runWaiting(context: Context, why: String) {
        if (waiting.isEmpty()) return
        val app = context.applicationContext
        val sends = waiting.entries.toList()
        waiting.clear()
        for ((key, send) in sends) {
            Log.i(LOG_TAG, "$key goes now ($why)")
            try {
                send(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "$key failed", e)
            }
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------

    private fun keyguardLocked(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (e: Exception) {
        false
    }

    private fun interactive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun owner(app: Context): Pair<DevicePolicyManager, ComponentName>? {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return null
        if (!dpm.isDeviceOwnerApp(app.packageName)) return null
        return dpm to ComponentName(app, MdmDeviceAdminReceiver::class.java)
    }

    /** CE prefs `keyguard_cover`: the bits the cover added to the keyguard features (M6). */
    private fun prefs(app: Context) = app.getSharedPreferences("keyguard_cover", Context.MODE_PRIVATE)

    private const val KEY_COVER_ADDED = "cover_added"

    private fun coverAdded(app: Context): Int = try {
        prefs(app).getInt(KEY_COVER_ADDED, 0)
    } catch (e: Exception) {
        0
    }

    private fun storeCoverAdded(app: Context, value: Int): Boolean = try {
        prefs(app).edit().putInt(KEY_COVER_ADDED, value).commit()
    } catch (e: Exception) {
        false
    }

    /** The DPM calls - here and nowhere else. */
    private class DpmKeyguard(private val dpm: DevicePolicyManager, private val admin: ComponentName) : KeyguardPolicy {
        override fun readFeatures(): Int = dpm.getKeyguardDisabledFeatures(admin)
        override fun writeFeatures(value: Int) = dpm.setKeyguardDisabledFeatures(admin, value)
        override fun readMaxTimeToLock(): Long = dpm.getMaximumTimeToLock(admin)
        override fun writeMaxTimeToLock(ms: Long) = dpm.setMaximumTimeToLock(admin, ms)
        override fun writeDisabled(disabled: Boolean): Boolean = dpm.setKeyguardDisabled(admin, disabled)
    }
}
