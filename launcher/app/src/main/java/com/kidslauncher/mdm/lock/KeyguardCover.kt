package com.kidslauncher.mdm.lock

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.server.ScreenTimeout

private const val LOG_TAG = "KeyguardCover"

/**
 * The one keyguard writer (design 32 step 2's H2/M6, kept by design 59; design 132 removed the keyguard cover and the
 * disabled keyguard of our own lock, so only two modes are left - [keyguardMode]: ANDROID on a managed phone with
 * Android's credential, UNTOUCHED otherwise, both with `setKeyguardDisabled(false)`). The decisions are pure
 * ([keyguardMode], [writeKeyguard] in KeyguardCoverPlan.kt); this is the glue.
 *
 * `setKeyguardDisabled`, `setKeyguardDisabledFeatures` and `setMaximumTimeToLock` are called only here - from
 * [configure] (every apply) and [followTimeout]/[timeoutChanged] (the screen timeout, T follows it as Android's lock).
 *
 * Screens of other apps that are not showWhenLocked wait here for Android's unlock ([afterAndroidUnlock], 59d step A).
 */
object KeyguardCover {

    /** Design 59's ANDROID is in force (Android's credential on a managed phone, [AndroidLock]): the Android PIN is the
     * gate, T follows the screen timeout. "Lås opp" asks Android. */
    @Volatile
    var androidOn = false
        private set

    /** P59-4: ANDROID's last write read B2's SECURE_CAMERA back on a managed phone ([secureCameraHeld]) - only then
     * may the camera lock stand down ([AndroidLock.cameraGuarded]). */
    @Volatile
    var androidCameraGuard = false
        private set

    /** T (`setMaximumTimeToLock`) follows the screen timeout under Android's lock (59 §3.2). */
    val timeToLockFollows: Boolean get() = androidOn

    private val handler = Handler(Looper.getMainLooper())

    // ---- the writer ----------------------------------------------------------------------------

    /**
     * From `PinLockRuntime.configure` (every apply, background thread): writes the keyguard for [managed] and
     * [deviceSecure] ([keyguardMode]). [hasKidPin] and [guardTripped] are logged only: a fallback apply (no `kid_lock`)
     * and the crash guard keep Android's keyguard.
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
    ) {
        val app = context.applicationContext
        val wanted = keyguardMode(managed, deviceSecure)
        write(app, dpm, admin, "apply (kidPin=$hasKidPin, guard=$guardTripped)", wanted, managed)
    }

    /** Caller holds the monitor. */
    private fun write(app: Context, dpm: DevicePolicyManager, admin: ComponentName, why: String, wanted: KeyguardMode, managed: Boolean): KeyguardWritten {
        val wasAndroid = androidOn
        val written = writeKeyguard(
            DpmKeyguard(dpm, admin), wanted, managed, coverAdded(app), { ScreenTimeout.effectiveMs(app) },
            storeCoverAdded = { storeCoverAdded(app, it) },
        )
        androidOn = written.mode == KeyguardMode.ANDROID
        androidCameraGuard = androidOn && secureCameraHeld(managed, written.features)
        for ((what, e) in written.errors) Log.w(LOG_TAG, what, e)
        if (androidOn != wasAndroid || androidOn) {
            Log.i(ANDROID_LOCK_TAG, "keyguard: mode=${written.mode} features=${written.features?.let { "0x" + Integer.toHexString(it) }} " +
                "maxTimeToLock=${written.maxTimeToLockMs} disabled=${written.disabled}${written.failed?.let { " failed=$it" } ?: ""} ($why)")
        }
        written.failed?.let {
            // P59 nit: ANDROID never falls back - the keyguard stays on with Android's PIN as the gate.
            Log.w(ANDROID_LOCK_TAG, "Android's keyguard: a step failed ($it) - Android's PIN stays the gate, the camera lock ${if (androidCameraGuard) "off" else "on"}")
        }
        return written
    }

    /**
     * H1: [ScreenTimeout]'s write of `SCREEN_OFF_TIMEOUT` (it stays the only writer of the setting),
     * with T in the safe order ([timeoutWriteOrder]): shrinking, T first; growing, the setting first. T
     * then follows the setting in force - also after a failed write. Without Android's lock: just the write.
     */
    fun followTimeout(context: Context, dpm: DevicePolicyManager, admin: ComponentName, oldMs: Long?, newMs: Long, writeSetting: () -> Boolean): Boolean =
        synchronized(this) {
            if (!timeToLockFollows) return@synchronized writeSetting()
            val app = context.applicationContext
            if (timeoutWriteOrder(oldMs, newMs) == TimeoutWriteOrder.T_FIRST) setT(dpm, admin, newMs, "the timeout shrinks")
            val ok = writeSetting()
            setT(dpm, admin, ScreenTimeout.effectiveMs(app), "the timeout changed")
            ok
        }

    /** H1: the setting changed without our write (Release: the phone's own Settings) - T follows it. */
    fun timeoutChanged(context: Context, dpm: DevicePolicyManager, admin: ComponentName) = synchronized(this) {
        val app = context.applicationContext
        setT(dpm, admin, ScreenTimeout.effectiveMs(app), "the setting changed")
    }

    /** T = [ms] under Android's lock, read back; no fallback - Android's own lock delay applies until the next write. */
    private fun setT(dpm: DevicePolicyManager, admin: ComponentName, ms: Long?, why: String) {
        if (!timeToLockFollows) return
        val t = maxTimeToLock(ms)
        val ok = t > 0L && try {
            if (dpm.getMaximumTimeToLock(admin) != t) dpm.setMaximumTimeToLock(admin, t)
            dpm.getMaximumTimeToLock(admin) == t
        } catch (e: Exception) {
            false
        }
        if (ok) {
            Log.i(ANDROID_LOCK_TAG, "maxTimeToLock=$t ($why)")
        } else {
            Log.w(ANDROID_LOCK_TAG, "maxTimeToLock=$t couldn't be set ($why) - Android's own lock delay applies")
        }
    }

    // ---- other apps' screens wait for Android's unlock (59d step A, main thread) ----------------------------------

    private val waiting = LinkedHashMap<String, (Context) -> Unit>()

    /**
     * Design 59d step A (qa-59b-code B-1): with Android's secure keyguard locked under the glance ([AndroidLock.refrontHeld]),
     * a start of a screen that isn't showWhenLocked - Home after a policy edge, the time-rule screen - would land behind
     * the keyguard and un-occlude it (SystemUI's face over the glance). It waits and runs when our lock leaves LOCKED
     * ([runAfterUnlock]); at once otherwise. One per [key], the newest kept. Any thread.
     */
    fun afterAndroidUnlock(context: Context, key: String, send: (Context) -> Unit) {
        if (!AndroidLock.refrontHeld(context)) {
            // The rc.2 run's R4: sent now, so a queued copy of the same start (it would run when the queue goes, a few
            // ms later - two stacked time-rule screens) is dropped: one path only.
            dropWaiting(context, key)
            send(context)
            return
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val app = context.applicationContext
            handler.post { afterAndroidUnlock(app, key, send) }
            return
        }
        Log.i(LOG_TAG, "$key waits for Android's unlock")
        waiting.remove(key)
        waiting[key] = send
    }

    /** [key]'s queued start goes (it is being sent now) - on the main thread, which owns the queue. */
    private fun dropWaiting(context: Context, key: String) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            if (waiting.remove(key) != null) Log.i(LOG_TAG, "$key: sent now - the queued one dropped")
        } else {
            val app = context.applicationContext
            handler.post { dropWaiting(app, key) }
        }
    }

    /** Our lock left LOCKED (59d step A): what waited for the unlock goes, unless Android's keyguard still holds it. Main
     * thread. */
    fun runAfterUnlock(context: Context, why: String) {
        if (waiting.isEmpty() || AndroidLock.refrontHeld(context)) return
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
    fun keyguardLockedNow(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
    } catch (e: Exception) {
        false
    }

    /** CE prefs `keyguard_cover`: the bits Android's lock added to the keyguard features (M6; the name is the cover's). */
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
