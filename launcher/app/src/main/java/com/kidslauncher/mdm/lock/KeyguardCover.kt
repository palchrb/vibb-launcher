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
 * edge, the re-arm, the fallback), [followTimeout] (the screen timeout, H1), [disableNow] (the
 * shutdown, M4, and the crash guard, L8) and [atProcessStart] (a cover an earlier process left in the
 * device policy, qa-32-s2-code M2 - prefs `keyguard_cover` `cover_written` says whether it may be there).
 *
 * The lock's dismissal (M7) lives in `PinLockActivity`; its state - the failures, the request - here,
 * on the main thread. So do the screens of other apps that wait for the unlock ([whenUnlocked], M5).
 */
object KeyguardCover {

    /** The debug switch, cached by [configure] and [atProcessStart] - never read from prefs on the main thread (L5). */
    @Volatile
    var switchOn = false
        private set

    /** COVER is in force: the features, T and `setKeyguardDisabled(false)` all held. */
    @Volatile
    var coverOn = false
        private set

    /** Design 59's ANDROID is in force (`kid_lock.keyguard` "android" with a credential, [AndroidLock]): the Android
     * PIN is the gate, T follows the screen timeout. No dismissal here - "Lås opp" asks Android. */
    @Volatile
    var androidOn = false
        private set

    /** P59-4: ANDROID's last write read B2's SECURE_CAMERA back on a managed phone ([secureCameraHeld]) - only then
     * may the camera lock stand down ([AndroidLock.cameraGuarded]). */
    @Volatile
    var androidCameraGuard = false
        private set

    /** T (`setMaximumTimeToLock`) follows the screen timeout: under the cover (H1) or Android's lock (59 §3.2). */
    val timeToLockFollows: Boolean get() = coverOn || androidOn

    /** The debug receiver's dismissal knob ([DismissTest]); nothing else sets it. */
    @Volatile
    var dismissTest = DismissTest.NONE

    /** [configure]'s last inputs (`null` before the first apply in this process). [androidRequested]: design 59's switch. */
    private data class Inputs(val managed: Boolean, val hasKidPin: Boolean, val deviceSecure: Boolean, val guardTripped: Boolean, val androidRequested: Boolean = false, val androidHeld: Boolean = false)

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

    /** `ACTION_SHUTDOWN` came: every later write is the cover off, and nothing re-arms (M2.4). Monitor. */
    @Volatile
    private var shuttingDown = false

    /** The stored `cover_written` (M2), cached once read or written; `null` = not read yet. */
    @Volatile
    private var written: Boolean? = null

    private val handler = Handler(Looper.getMainLooper())
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "keyguard-cover").apply { isDaemon = true } }

    // ---- the writer ----------------------------------------------------------------------------

    /**
     * Off the main thread at the process start ([PinLockRuntime.init]): the debug switch, and (M2) a cover
     * an earlier process or boot left in the device policy goes off at once - [processStartWrite]:
     * DISABLED (`setKeyguardDisabled(true)` dismisses a Swipe screen a reboot without `ACTION_SHUTDOWN`
     * left up), the cover's bits and T out, unless an apply already wrote in this process. The cover
     * comes back at the first lock resume with the screen on after the first apply (M4).
     */
    fun atProcessStart(context: Context) {
        val app = context.applicationContext
        switchOn = try {
            KeyguardCoverDebug.enabled(app)
        } catch (e: Exception) {
            false
        }
        try {
            synchronized(this) {
                if (!processStartWrite(coverWritten(app), configured = inputs != null)) return
                val (dpm, admin) = owner(app) ?: return
                // Only a managed phone ever had the cover: B2's camera bit stays.
                write(app, dpm, admin, "a cover left by an earlier process", KeyguardMode.DISABLED, managed = true)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The process start's keyguard write failed", e)
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
        androidRequested: Boolean = false,
        androidHeld: Boolean = false,
    ): Boolean {
        val app = context.applicationContext
        switchOn = try {
            KeyguardCoverDebug.enabled(app)
        } catch (e: Exception) {
            false
        }
        inputs = Inputs(managed, hasKidPin, deviceSecure, guardTripped, androidRequested, androidHeld)
        val (wanted, forManaged) = wantedNow()
        return write(app, dpm, admin, "apply", wanted, forManaged).lockMayRun
    }

    /** [configure]'s inputs again, on the writer thread: the VoIP edge, the re-arm, the fallback - never
     * before the first apply in this process ([rewriteDue], M2: it would write UNTOUCHED from no inputs). */
    private fun rewrite(context: Context, why: String) {
        val app = context.applicationContext
        writer.execute {
            try {
                val (dpm, admin) = owner(app) ?: return@execute
                synchronized(this) {
                    if (!rewriteDue(configured = inputs != null)) return@synchronized
                    val (wanted, forManaged) = wantedNow()
                    write(app, dpm, admin, why, wanted, forManaged)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Keyguard write ($why) failed", e)
            }
        }
    }

    /** The mode for [configure]'s inputs now, and their `managed`; [forceOff] (or a shutdown, M2.4): the
     * cover never. Monitor held, inputs set. */
    private fun wantedNow(forceOff: Boolean = false): Pair<KeyguardMode, Boolean> {
        val i = inputs ?: Inputs(managed = false, hasKidPin = false, deviceSecure = false, guardTripped = false)
        val off = forceOff || shuttingDown
        val wanted = keyguardMode(
            i.managed, i.hasKidPin, i.deviceSecure,
            coverWanted = !off && switchOn && armed,
            voip = if (off) VoipPhase.NONE else voipSeen,
            guardTripped = i.guardTripped,
            // Design 59: not the cover's - a shutdown or a failed T never takes Android mode's features out.
            androidRequested = i.androidRequested,
            androidHeld = i.androidHeld,
        )
        return wanted to i.managed
    }

    /** Caller holds the monitor. */
    private fun write(app: Context, dpm: DevicePolicyManager, admin: ComponentName, why: String, wanted: KeyguardMode, managed: Boolean): KeyguardWritten {
        val wasOn = coverOn
        val wasAndroid = androidOn
        val written = writeKeyguard(
            DpmKeyguard(dpm, admin), wanted, managed, coverAdded(app), { ScreenTimeout.effectiveMs(app) },
            storeCoverAdded = { storeCoverAdded(app, it) },
            storeWritten = { storeWritten(app, it) },
        )
        coverOn = written.mode == KeyguardMode.COVER
        androidOn = written.mode == KeyguardMode.ANDROID
        androidCameraGuard = androidOn && secureCameraHeld(managed, written.features)
        last = written
        // Release (the switch never on) logs nothing new - except what threw (qa-32-s2-code nit 2).
        for ((what, e) in written.errors) Log.w(LOG_TAG, what, e)
        if (androidOn != wasAndroid || androidOn) {
            Log.i(ANDROID_LOCK_TAG, "keyguard: mode=${written.mode} features=${written.features?.let { "0x" + Integer.toHexString(it) }} " +
                "maxTimeToLock=${written.maxTimeToLockMs} disabled=${written.disabled}${written.coverFailed?.let { " failed=$it" } ?: ""} ($why)")
        }
        if (switchOn || wasOn || coverOn) {
            Log.i(LOG_TAG, "mode=${written.mode} features=${written.features?.let { "0x" + Integer.toHexString(it) }} " +
                "maxTimeToLock=${written.maxTimeToLockMs} disabled=${written.disabled} ($why${if (switchOn && !armed) ", held off until a lock resume with the screen on" else ""})")
        }
        written.coverFailed?.let {
            if (androidOn) {
                // P59 nit: ANDROID never falls back - the keyguard stays on with Android's PIN as the gate.
                Log.w(ANDROID_LOCK_TAG, "Android's keyguard: a step failed ($it) - Android's PIN stays the gate, the camera lock ${if (androidCameraGuard) "off" else "on"}")
            } else {
                Log.w(LOG_TAG, "The cover couldn't be set ($it) - the keyguard stays disabled, the PIN lock active")
            }
        }
        if (wasOn && !coverOn) handler.post { runWaiting(app, "the cover is off") }
        return written
    }

    /**
     * M4 / L8: `setKeyguardDisabled(true)` at once while the cover may be in the device policy - in
     * memory or by the stored flag ([disableNowDue], M2: the crash guard trips in a new process) -
     * [synchronous] at `ACTION_SHUTDOWN` (a clean reboot has no keyguard; every later write is the cover
     * off), on the writer thread for the crash guard. Decided under the monitor, so a `configure`
     * writing the cover finishes first (M2.4). Nothing without a cover (release: as before).
     */
    fun disableNow(context: Context, why: String, synchronous: Boolean, shutdown: Boolean = false) {
        val app = context.applicationContext
        val run = Runnable {
            try {
                synchronized(this) {
                    if (shutdown) shuttingDown = true
                    if (!disableNowDue(coverOn, coverWritten(app))) return@synchronized
                    val (dpm, admin) = owner(app) ?: return@synchronized
                    val ok = dpm.setKeyguardDisabled(admin, true)
                    if (ok) storeWritten(app, false)
                    coverOn = false
                    armed = false
                    last = (last ?: KeyguardWritten(KeyguardMode.DISABLED, ok)).copy(
                        mode = KeyguardMode.DISABLED, lockMayRun = ok, coverFailed = null, disabled = true, errors = emptyList(),
                    )
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
    fun onShutdown(context: Context) = disableNow(context, "shutdown", synchronous = true, shutdown = true)

    /**
     * H1: [ScreenTimeout]'s write of `SCREEN_OFF_TIMEOUT` (it stays the only writer of the setting),
     * with T in the safe order ([timeoutWriteOrder]): shrinking, T first; growing, the setting first. T
     * then follows the setting in force - also after a failed write. Without the cover: just the write.
     */
    fun followTimeout(context: Context, dpm: DevicePolicyManager, admin: ComponentName, oldMs: Long?, newMs: Long, writeSetting: () -> Boolean): Boolean =
        synchronized(this) {
            if (!timeToLockFollows) return@synchronized writeSetting()
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
        if (!timeToLockFollows) return
        val t = maxTimeToLock(coverOn = true, effectiveTimeoutMs = ms)
        val ok = t > 0L && try {
            if (dpm.getMaximumTimeToLock(admin) != t) dpm.setMaximumTimeToLock(admin, t)
            dpm.getMaximumTimeToLock(admin) == t
        } catch (e: Exception) {
            false
        }
        if (ok) {
            Log.i(if (androidOn) ANDROID_LOCK_TAG else LOG_TAG, "maxTimeToLock=$t ($why)")
        } else if (androidOn) {
            // Design 59: no fallback - the Android PIN is the gate; Android's own lock delay applies until the next write.
            Log.w(ANDROID_LOCK_TAG, "maxTimeToLock=$t couldn't be set ($why) - Android's own lock delay applies")
        } else {
            Log.w(LOG_TAG, "maxTimeToLock=$t couldn't be set ($why) - the cover falls back")
            val (wanted, forManaged) = wantedNow(forceOff = true)
            write(app, dpm, admin, "max time to lock failed", wanted, forManaged)
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
        if (!shuttingDown && coverRearmDue(heldOff = !armed, interactive = interactive, lockResumed = true, wakePending = wakePending)) {
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

    /** The keyguard is locked now (the lock-up's `kg`, qa-32-s2-code L4). */
    fun keyguardLockedNow(context: Context): Boolean = keyguardLocked(context)

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

    /** M2: the device policy may hold the cover. Read once, then cached; unreadable = maybe while the
     * switch is on (release: never a cover, so no). */
    private const val KEY_COVER_WRITTEN = "cover_written"

    private fun coverWritten(app: Context): Boolean = written ?: try {
        prefs(app).getBoolean(KEY_COVER_WRITTEN, false)
    } catch (e: Exception) {
        switchOn
    }.also { written = it }

    /** Committed only when it changes - release (never a cover) never writes it. */
    private fun storeWritten(app: Context, value: Boolean): Boolean {
        if (coverWritten(app) == value) return true
        val ok = try {
            prefs(app).edit().putBoolean(KEY_COVER_WRITTEN, value).commit()
        } catch (e: Exception) {
            false
        }
        if (ok) written = value
        return ok
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
