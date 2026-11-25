package com.kidslauncher.mdm.lock

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.dto.KidLock
import java.util.concurrent.atomic.AtomicInteger

/**
 * Design 59 (docs/design/59-android-keyguard-design.md §3): Android's secure keyguard as the gate, our glance
 * its face - the glue around the pure AndroidLockPlan.kt. Since design 132 (phase D) the only lock.
 *
 * - A Child PIN, no Android credential: no lock until the server's `set_kid_pin` ([applyPinCommand]) sets the
 *   Child PIN as Android's PIN through our reset token; it carries every later Child PIN too.
 * - A Child PIN with a credential ([faceOn]): Android's keyguard is the gate (written by [KeyguardCover], ANDROID);
 *   our glance (`PinLockActivity`, showWhenLocked) its face; it leaves only on Android's verdict ([faceStep]).
 * - Nothing here ever clears Android's PIN (Q1 (a): the Child PIN is changed, never removed).
 *
 * Never logs or stores the PIN; the reset token is `WindowRelockStore`'s only. [report] gives `lock_state` the gate.
 */
object AndroidLock {

    /** [androidFace] as the last apply (or the process start) decided: the glance is Android's face. */
    @Volatile
    var faceOn = false
        private set

    /** This process is the boot's first CE start ([firstCeStart]) - step 4's `unlocked_at_ms` reads it. */
    @Volatile
    var firstCeStartOfBoot = false
        private set

    /**
     * `keyguard_relocks` (59 §3.3): [relock]s not yet in a report that landed, at most 1000. [report] reads them, and
     * only a status report the server took subtracts them ([relocksReported], qa-59-step4-code N2).
     */
    private val relocks = AtomicInteger(0)

    /**
     * A `USER_PRESENT` since our lock last went LOCKED (59 §3.3; reset at every LOCKED edge, screen-off and remote
     * lock - [lockedAgain]). Main thread.
     */
    var presentSinceLocked = false
        private set

    /** The camera guard the camera lock last heard of ([afterKeyguard]). */
    @Volatile
    private var cameraGuardWas = false

    /** S4-2: the command just set Android's PIN - the apply that follows locks Android when our lock is up and dark. */
    @Volatile
    private var lockAfterSet = false

    // ---- the process start, the apply -------------------------------------------------------------------------

    /** From [PinLockRuntime.decide] (main thread, before any activity): the face (an active lock and a live credential),
     * and whether this is the boot's first CE start. */
    fun atProcessStart(face: Boolean, firstCeStart: Boolean) {
        faceOn = face
        firstCeStartOfBoot = firstCeStart
    }

    /**
     * The boot's first CE start (off the main thread, [PinLockRuntime.init]): the restart line goes - it showed on
     * Android's face before the first unlock (59 §3.2, open question 2). Every mode.
     */
    fun atBootStart(context: Context) {
        if (!firstCeStartOfBoot) return
        owner(context.applicationContext)?.let { (dpm, admin) -> lockScreenInfo(dpm, admin, null) }
    }

    /**
     * [PinLockRuntime.configure] (every apply, background thread), before the keyguard is written: the reset token kept
     * active in every state ([WindowRelock.ensureAndroidResetToken], §3.6, design 132 §7.1), the owned flag - owned
     * with no credential left (removed on the phone) goes, and its rev - and the password quality ([passwordQualityNow]).
     * Returns whether we still own Android's credential. Android's PIN is never cleared here (Q1 (a)).
     */
    fun configure(context: Context, dpm: DevicePolicyManager, admin: ComponentName, managed: Boolean): Boolean = synchronized(this) {
        val app = context.applicationContext
        val owned = try {
            PinLockStore.keyguardAndroid(app)
        } catch (e: Exception) {
            false
        }
        WindowRelock.ensureAndroidResetToken(app)
        val secure = deviceSecure(app)
        val stillOwned = if (owned && !secure) {
            val ok = PinLockStore.saveAndroid(app, owned = false, rev = null)
            Log.i(ANDROID_LOCK_TAG, "Android's PIN is gone - ours no more (stored: $ok)")
            false
        } else {
            owned
        }
        passwordQualityNow(dpm, admin, passwordQuality(managed, stillOwned, secure))
        stillOwned
    }

    /**
     * Design 132 §4.5 (QA H1): `setPasswordQuality` written and read back - NUMERIC once Android has the Child PIN, so
     * Settings › Screen lock offers no "None", "Swipe" or pattern; UNSPECIFIED otherwise. Deprecated for a plain device
     * admin, supported for a Device Owner on API 34-37 (API 8, no SDK gate). A mismatch or a failure is logged; the next
     * apply writes it again.
     */
    @Suppress("DEPRECATION") // setPasswordQuality: deprecated for device admins, still the Device Owner's call (design 132 §4.5).
    private fun passwordQualityNow(dpm: DevicePolicyManager, admin: ComponentName, quality: Int) {
        try {
            if (dpm.getPasswordQuality(admin) != quality) {
                dpm.setPasswordQuality(admin, quality)
                Log.i(ANDROID_LOCK_TAG, "password quality: ${qualityName(quality)}")
            }
            val read = dpm.getPasswordQuality(admin)
            if (read != quality) Log.w(ANDROID_LOCK_TAG, "password quality read back ${qualityName(read)}, wanted ${qualityName(quality)}")
        } catch (e: Exception) {
            Log.w(ANDROID_LOCK_TAG, "password quality ${qualityName(quality)} failed: ${e.javaClass.simpleName}")
        }
    }

    private fun qualityName(quality: Int): String = when (quality) {
        PASSWORD_QUALITY_NUMERIC -> "NUMERIC"
        PASSWORD_QUALITY_UNSPECIFIED -> "UNSPECIFIED"
        else -> "0x" + Integer.toHexString(quality)
    }

    /**
     * The face for this apply ([androidFace]), from [PinLockRuntime.configure] after [configure]. The camera lock re-syncs
     * on both edges (P59-8); leaving the face clears the restart line.
     */
    fun face(context: Context, dpm: DevicePolicyManager, admin: ComponentName, managed: Boolean, hasKidPin: Boolean, deviceSecure: Boolean): Boolean {
        val app = context.applicationContext
        val face = androidFace(managed, hasKidPin, deviceSecure)
        val was = faceOn
        faceOn = face
        if (face != was) CameraLock.onLockChanged(app)
        // Design 59d step K: Android's lock wallpaper is the glance's ground as the face - re-applied on both edges.
        if (face != was) com.kidslauncher.mdm.ui.wallpaper.WallpaperStore.faceChanged(app)
        if (was && !face) lockScreenInfo(dpm, admin, null)
        if (hasKidPin || was) {
            Log.i(ANDROID_LOCK_TAG, "apply: managed=$managed kidPin=$hasKidPin secure=$deviceSecure face=$face" +
                "${if (face != was) " (changed)" else ""}${if (managed && hasKidPin && !deviceSecure) " - no Android PIN: no lock until set_kid_pin" else ""}")
        }
        return face
    }

    /**
     * After the keyguard write: the camera lock stands down only on the secure-camera bit really written (P59-4). After
     * the command set Android's PIN (S4-2), the first such apply locks Android at once while our lock is LOCKED and the
     * screen dark ([lockAfterPinSet]) - the keyguard was off at the last screen-off. Not a relock: not counted.
     */
    fun afterKeyguard(context: Context) {
        val app = context.applicationContext
        val guarded = cameraGuarded()
        if (guarded != cameraGuardWas) {
            cameraGuardWas = guarded
            CameraLock.onLockChanged(app)
        }
        if (lockAfterSet) {
            lockAfterSet = false
            if (lockAfterPinSet(faceOn, PinLockRuntime.mode == LockMode.LOCKED, interactive(app))) {
                Log.i(ANDROID_LOCK_TAG, "Android's PIN set under our lock in the dark - Android locked now")
                lockNow(app)
            }
        }
    }

    /** The camera lock's input: the face, and ANDROID's write read SECURE_CAMERA back ([cameraLockEngaged]). */
    fun cameraGuarded(): Boolean = faceOn && KeyguardCover.androidCameraGuard

    // ---- the credential -------------------------------------------------------------------------------------------

    /**
     * 59 §3.5, `set_kid_pin` (the sync's dispatch, background thread, before this sync's apply): [pin] - the command's,
     * held only for this call - checked against the **fresh** policy's [kidLock] ([pinCommandStep]), then set as
     * Android's PIN: `keyguard_android` committed first (write-ahead, M-1, with the rev Android holds now -
     * [commandWriteAheadRev]), the reset with our change marked ([reset], carrying the fresh rev for a death before its
     * commit - S4-1), then the fresh rev. A failed reset gives back what was written ahead. Works with Android's
     * keyguard up (AFU); the sync's apply that follows turns the face on - and locks Android when our lock is up in the
     * dark (S4-2, [afterKeyguard]). Never stores, caches or logs [pin]; the answer names only its words and the rev.
     */
    fun applyPinCommand(context: Context, kidLock: KidLock?, pin: String?): PinCommandAnswer {
        val app = context.applicationContext
        val answer = try {
            synchronized(this) {
                pinCommandStep(
                    pin = pin,
                    verifies = { kidLock != null && PinLockRuntime.verifyCommandPin(kidLock, it) },
                    tokenActive = { WindowRelock.ensureAndroidResetToken(app) },
                ) ?: setFromCommand(app, pin!!, kidLock!!.pinRev)
            }
        } catch (e: Exception) {
            Log.w(ANDROID_LOCK_TAG, "PIN command failed: ${e.javaClass.simpleName}")
            PinCommandAnswer.FAILED
        }
        if (answer == PinCommandAnswer.PIN_SET) lockAfterSet = true
        Log.i(ANDROID_LOCK_TAG, "PIN command: ${answer.message} (rev ${kidLock?.pinRev}, secure now=${deviceSecure(app)})")
        return answer
    }

    /** [applyPinCommand]'s set, under the monitor (shared with `onPasswordChanged`). */
    private fun setFromCommand(app: Context, pin: String, rev: Long): PinCommandAnswer {
        val owned = PinLockStore.keyguardAndroid(app)
        val before = commandWriteAheadRev(owned, PinLockStore.androidPinRev(app))
        if (!PinLockStore.saveAndroid(app, owned = true, rev = before)) return PinCommandAnswer.FAILED
        if (!reset(app, pin, settingRev = rev)) {
            // Give back what was written ahead: a credential not ours stays not ours.
            PinLockStore.saveAndroid(app, owned = owned, rev = before)
            return PinCommandAnswer.FAILED
        }
        commitRev(app, rev)
        return PinCommandAnswer.PIN_SET
    }

    /**
     * The rev of the PIN our reset just set, committed beside the owned flag - once more if the commit fails
     * (qa-59-step4-code N1). Still failing, it is logged: memory holds the rev for this process's reports, and after a
     * restart the older rev on disk makes the server send the PIN again (it never claims a PIN Android lacks).
     */
    private fun commitRev(app: Context, rev: Long) {
        if (PinLockStore.saveAndroid(app, owned = true, rev = rev) || PinLockStore.saveAndroid(app, owned = true, rev = rev)) return
        Log.w(ANDROID_LOCK_TAG, "Android's PIN is set, but its rev couldn't be stored - the server will send it again")
    }

    /**
     * `lock_state`'s design-59 fields (the status report, the sync's thread): the gate and its rev ([keyguardReport]),
     * our reset token active, and the relocks not yet reported - only a report the server took subtracts them
     * ([relocksReported]; a failed POST keeps them for the next).
     */
    fun report(context: Context): AndroidLockReport {
        val app = context.applicationContext
        val owned = owned(app)
        val androidRev = try {
            PinLockStore.androidPinRev(app)
        } catch (e: Exception) {
            null
        }
        val gate = keyguardReport(owned, androidRev)
        return AndroidLockReport(gate.keyguard, gate.pinRev, WindowRelock.resetTokenActive(app), relocks.get())
    }

    /** The status report carrying [reported] relocks landed (2xx): those are told, newer ones stay counted. */
    fun relocksReported(reported: Int) {
        if (reported > 0) relocks.updateAndGet { (it - reported).coerceAtLeast(0) }
    }

    /** What [report] gives `lock_state`. */
    data class AndroidLockReport(val keyguard: String, val pinRev: Long?, val resetToken: Boolean, val relocks: Int)

    /** Android's PIN is the gate now ([androidGate], live): the remote lock's answer (59 §3.9). */
    fun gateNow(context: Context): Boolean {
        val app = context.applicationContext
        return androidGate(faceOn, owned(app), deviceSecure(app))
    }

    /** What Settings' "Fingerprint" row asks of Android's lock ([com.kidslauncher.mdm.server.fingerprintRowShown]):
     * CE `keyguard_android`, a secure keyguard and the stored `android_pin_rev` (null after a change on the phone,
     * §3.7). Anything unreadable counts against the row. */
    fun fingerprintLockState(context: Context): com.kidslauncher.mdm.server.FingerprintLockState {
        val app = context.applicationContext
        val pinRevKnown = try {
            PinLockStore.androidPinRev(app) != null
        } catch (e: Exception) {
            false
        }
        return com.kidslauncher.mdm.server.FingerprintLockState(owned = owned(app), deviceSecure = deviceSecure(app), pinRevKnown = pinRevKnown)
    }

    /** CE `keyguard_android`; unreadable = not ours. */
    private fun owned(app: Context): Boolean = try {
        PinLockStore.keyguardAndroid(app)
    } catch (e: Exception) {
        false
    }

    /**
     * Every own `resetPasswordWithToken` (the command), under the monitor: our change is marked
     * first (§3.7, S4-1: [ownChangeArmed] in CE - one more expected, and the rev it sets, [settingRev], `null` for a
     * clear), so its `onPasswordChanged` is known as ours also in a new process and beside another of ours. A failed
     * reset takes its mark back ([ownChangeConsumed]).
     */
    private fun reset(app: Context, pin: String?, settingRev: Long?): Boolean {
        val boot = BootClock.bootCount()
        PinLockStore.saveOwnChange(app, ownChangeArmed(PinLockStore.ownChange(app), boot, SystemClock.elapsedRealtime(), settingRev))
        val ok = WindowRelock.resetCredential(app, pin)
        if (!ok) {
            PinLockStore.saveOwnChange(app, ownChangeConsumed(PinLockStore.ownChange(app), boot, SystemClock.elapsedRealtime(), failed = true))
        }
        return ok
    }

    /**
     * 59 §3.7, `onPasswordChanged` (background thread, before its apply): our own change ([ownChangeLive]) consumes one
     * mark and keeps the rev - or takes the rev its reset was setting when the commit never came (the process died
     * between the two, S4-1); any other change makes the rev unknown ([androidPinRevAfterChange]) - the PWA then warns
     * and offers the Child PIN again.
     */
    fun onPasswordChanged(context: Context) = synchronized(this) {
        val app = context.applicationContext
        try {
            val boot = BootClock.bootCount()
            val now = SystemClock.elapsedRealtime()
            val change = PinLockStore.ownChange(app)
            val expected = ownChangeLive(change, boot, now)
            PinLockStore.saveOwnChange(app, ownChangeConsumed(change, boot, now))
            val rev = PinLockStore.androidPinRev(app)
            val after = androidPinRevAfterChange(expected, rev, change?.settingRev.takeIf { expected })
            if (after != rev) {
                PinLockStore.saveAndroid(app, PinLockStore.keyguardAndroid(app), after)
                Log.i(ANDROID_LOCK_TAG, "Android's PIN was changed on the phone - not the Child PIN as far as we know")
            }
        } catch (e: Exception) {
            Log.w(ANDROID_LOCK_TAG, "onPasswordChanged: the rev couldn't be updated (${e.javaClass.simpleName})")
        }
    }

    // ---- the face's decisions (main thread is fine: short binder reads) -------------------------------------------

    /** An Android credential now; unreadable = none. */
    fun deviceSecure(context: Context): Boolean = try {
        context.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    } catch (e: Exception) {
        false
    }

    /** Android's keyguard asks for the PIN now: `isDeviceLocked || isKeyguardLocked`; unreadable = locked. */
    fun androidLocked(context: Context): Boolean = try {
        val km = context.getSystemService(KeyguardManager::class.java)
        km == null || km.isDeviceLocked || km.isKeyguardLocked
    } catch (e: Exception) {
        true
    }

    /** [faceStep] with the live reads (P59-8: a credential gone since the last apply means LEAVE - no lock left). */
    fun step(context: Context, trigger: FaceTrigger): FaceStep =
        faceStep(trigger, deviceSecure(context), androidLocked(context), presentSinceLocked)

    /** Android's secure keyguard is locked under the face now ([androidRefrontHeld], P59-3); no binder call without
     * [faceOn]. Design 59b: the platform shows a ring's full-screen intent itself then ([voipPlatformRing]). */
    fun refrontHeld(context: Context): Boolean = faceOn && androidRefrontHeld(true, deviceSecure(context), androidLocked(context))

    /** Design 59b §3: the face's loss rule's input now ([androidLoss], live reads); no binder call without [faceOn]. */
    fun loss(context: Context): AndroidLoss? =
        if (!faceOn) null else androidLoss(face = true, deviceSecure(context), androidLocked(context), presentSinceLocked)

    /** The screen-off count when Android last read locked ([sawLocked]; -1 = never in this process). */
    @Volatile
    private var lockedSeenAtOffs = -1

    /** Android read locked after the screen-off [offs] (the lock-up's read, the sleep check). S3-3. */
    fun sawLocked(offs: Int) {
        lockedSeenAtOffs = offs
    }

    /** A [FaceStep.WAIT]'s length now ([faceWaitMs]): longer once Android read locked since this screen-off ([offs]). */
    fun waitMs(offs: Int): Long = faceWaitMs(lockedSeenAtOffs == offs)

    /** `USER_PRESENT` (the runtime's exported receiver, the screen on). Main thread. */
    fun userPresent() {
        presentSinceLocked = true
    }

    /** Our lock went LOCKED (an edge, a screen-off, a remote lock): the mark goes. Main thread. */
    fun lockedAgain() {
        presentSinceLocked = false
    }

    /**
     * RELOCK (59 §3.3): Android didn't lock under the glance (T didn't take, a wake raced the sleep, a crash restart
     * found it open) - `lockNow`: the screen goes off, Android's PIN is the gate, the fingerprint waits for the PIN.
     */
    fun relock(context: Context, why: String) {
        relocks.updateAndGet { (it + 1).coerceAtMost(1_000) }
        Log.w(ANDROID_LOCK_TAG, "Android's keyguard didn't lock under the glance - locked it ($why)")
        lockNow(context)
    }

    /** The crash guard tripped in android mode (Q59-2): Android's PIN is the gate with SystemUI's face - never open. */
    fun crashGuardTripped(context: Context) {
        if (!faceOn) return
        Log.w(ANDROID_LOCK_TAG, "The crash guard tripped - Android's lock now")
        lockNow(context)
    }

    private fun lockNow(context: Context) {
        try {
            context.applicationContext.getSystemService(DevicePolicyManager::class.java)?.lockNow()
        } catch (e: Exception) {
            Log.w(ANDROID_LOCK_TAG, "lockNow failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- the restart line (59 §3.2) -----------------------------------------------------------------------------------

    /** `ACTION_SHUTDOWN` (BootCover's receiver, after the cover's writes, its own try): the restart words on Android's
     * face before the first unlock - only in android mode. */
    fun onShutdown(context: Context) {
        if (!faceOn) return
        val app = context.applicationContext
        owner(app)?.let { (dpm, admin) -> lockScreenInfo(dpm, admin, app.getString(R.string.android_lock_restart_line)) }
    }

    /** `setDeviceOwnerLockScreenInfo`, only when it differs. */
    private fun lockScreenInfo(dpm: DevicePolicyManager, admin: ComponentName, text: String?) {
        try {
            if (dpm.deviceOwnerLockScreenInfo?.toString() == text) return
            dpm.setDeviceOwnerLockScreenInfo(admin, text)
            Log.i(ANDROID_LOCK_TAG, "lock-screen line: ${if (text == null) "cleared" else "set"}")
        } catch (e: Exception) {
            Log.w(ANDROID_LOCK_TAG, "lock-screen line failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- the device checks' log lines --------------------------------------------------------------------------------

    /** One line of state for [event] (face mode only). */
    fun note(context: Context, event: String) {
        if (!faceOn) return
        val km = try {
            context.getSystemService(KeyguardManager::class.java)
        } catch (e: Exception) {
            null
        }
        val state = try {
            "keyguardLocked=${km?.isKeyguardLocked} deviceLocked=${km?.isDeviceLocked} secure=${km?.isDeviceSecure} interactive=${interactive(context)} present=$presentSinceLocked"
        } catch (e: Exception) {
            "state unreadable (${e.javaClass.simpleName})"
        }
        Log.i(ANDROID_LOCK_TAG, "$event: $state")
    }

    /**
     * At the glance's resume (face mode): lock task's mode and features with Android's keyguard state - no verdict
     * (P59-1: lock task's missing OVERVIEW doesn't hold Recents; Android's locked keyguard does).
     */
    fun noteLockTask(context: Context) {
        if (!faceOn) return
        val app = context.applicationContext
        try {
            val mode = app.getSystemService(ActivityManager::class.java)?.lockTaskModeState
            val features = owner(app)?.let { (dpm, admin) -> dpm.getLockTaskFeatures(admin) }
            note(app, "lock task: mode=$mode features=${features?.let { "0x" + Integer.toHexString(it) }}")
        } catch (e: Exception) {
            Log.w(ANDROID_LOCK_TAG, "lock task unreadable: ${e.javaClass.simpleName}")
        }
    }

    private fun interactive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    private fun owner(app: Context): Pair<DevicePolicyManager, ComponentName>? {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return null
        if (!dpm.isDeviceOwnerApp(app.packageName)) return null
        return dpm to ComponentName(app, MdmDeviceAdminReceiver::class.java)
    }
}
