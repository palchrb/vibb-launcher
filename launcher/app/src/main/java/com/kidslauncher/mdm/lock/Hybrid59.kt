package com.kidslauncher.mdm.lock

import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.currentPolicyDecision
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Design 59's hybrid prototype (59 §9 step 7 (ii), docs/design/59-prototype.md): the glue around the pure
 * HybridPlan.kt. Behind `HybridDebug`'s switch - a debug-build receiver sets it; the release `HybridDebug`
 * is always false, so in a release build [switchOn] and [faceOn] stay false, nothing here reads or writes
 * anything, and every caller's gate (`faceOn && ...`) short-circuits before a binder call.
 *
 * - Switch on, no Android credential: today's own lock; the next correct kid PIN on our keypad becomes the
 *   Android PIN through our reset token ([migrateIfDue]).
 * - Switch on, a credential ([faceOn]): Android's secure keyguard is the gate; our glance (`PinLockActivity`,
 *   showWhenLocked) its face; "Lås opp" asks Android ([unlockStep] -> `requestDismissKeyguard`); the keyguard
 *   is written by [KeyguardCover] (HYBRID); no re-front while Android's keyguard is locked; no camera lock.
 * - Switch off ([rollback]): the credential cleared with the token, today's lock at the next apply.
 *
 * Everything a device run has to answer is logged under [HYBRID_TAG].
 */
object Hybrid59 {

    /** The debug switch, cached by [refresh] (an apply, the process start) - never read on the main thread. */
    @Volatile
    var switchOn = false
        private set

    /** [hybridFace] as the last apply decided: the glance is the face over Android's keyguard. */
    @Volatile
    var faceOn = false
        private set

    /** Prototype text for Android's own lock screen (the user's answer 1: "type the PIN to start the phone"
     * - whether it shows before the first unlock is a device check). Not translated: phase C moves it to strings. */
    private const val LOCK_SCREEN_INFO = "Skriv koden for å starte telefonen"

    /** Reads the switch (release: false without a read). Any thread but main. */
    fun refresh(context: Context): Boolean {
        switchOn = try {
            HybridDebug.enabled(context.applicationContext)
        } catch (e: Exception) {
            false
        }
        return switchOn
    }

    /**
     * The process start (`PinLockRuntime.init`, off the main thread), before the first apply: [faceOn] from the
     * stored lock state - [lockActive] (the last apply's: managed, a kid PIN) and a credential now - so the first
     * glance after a boot's first unlock already knows Android is the gate (59 §4.4).
     */
    fun atProcessStart(context: Context, lockActive: Boolean) {
        val app = context.applicationContext
        if (!refresh(app)) return
        faceOn = hybridFace(switchOn, managed = lockActive, hasKidPin = lockActive, deviceSecure = deviceSecure(app))
        Log.i(HYBRID_TAG, "process start: switch=on face=$faceOn (secure=${deviceSecure(app)}, androidLocked=${androidLocked(app)}, lock active=$lockActive)")
    }

    /**
     * From `PinLockRuntime.configure` (every apply, background thread), after [refresh]: [faceOn] for these
     * inputs; with the switch on, the reset token while the migration can still come (59 §9 step 1: set while
     * no credential exists, it activates at once) and Android's lock-screen line. Returns [faceOn].
     */
    fun configured(context: Context, dpm: DevicePolicyManager, admin: ComponentName, managed: Boolean, hasKidPin: Boolean, deviceSecure: Boolean): Boolean {
        val face = hybridFace(switchOn, managed, hasKidPin, deviceSecure)
        val was = faceOn
        faceOn = face
        // The camera lock follows the face (59 §4.9, [cameraLockEngaged]).
        if (face != was) CameraLock.onLockChanged(context)
        if (!switchOn) {
            if (was) Log.i(HYBRID_TAG, "face off (the switch is off)")
            return face
        }
        val app = context.applicationContext
        if (hasKidPin && hybridMigrationDue(switchOn, managed, deviceSecure)) WindowRelock.ensureResetToken(app)
        Log.i(HYBRID_TAG, "apply: switch=on managed=$managed kidPin=$hasKidPin secure=$deviceSecure token=${WindowRelock.resetTokenActive(app)} " +
            "face=$face${if (face != was) " (changed)" else ""}${if (!face && hasKidPin && managed) " - the next correct kid PIN becomes Android's" else ""}")
        lockScreenInfo(dpm, admin, if (face) LOCK_SCREEN_INFO else null)
        return face
    }

    /** `setDeviceOwnerLockScreenInfo`, only when it differs. */
    private fun lockScreenInfo(dpm: DevicePolicyManager, admin: ComponentName, text: String?) {
        try {
            if (dpm.deviceOwnerLockScreenInfo?.toString() == text) return
            dpm.setDeviceOwnerLockScreenInfo(admin, text)
            Log.i(HYBRID_TAG, "lock-screen line: ${if (text == null) "cleared" else "\"$text\""}")
        } catch (e: Exception) {
            Log.w(HYBRID_TAG, "lock-screen line failed: ${e.javaClass.simpleName}")
        }
    }

    // ---- the live checks (main thread is fine: no prefs, short binder reads) ---------------------------------

    /** An Android credential now; unreadable = none - so the gate falls back to our keypad, never past it. */
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

    /** "Lås opp" or a row ([androidUnlockStep]); no binder call without [faceOn]. */
    fun unlockStep(context: Context): AndroidUnlock =
        if (!faceOn) AndroidUnlock.OWN_KEYPAD else androidUnlockStep(true, deviceSecure(context), androidLocked(context))

    /** Android's keyguard is gone while our face says LOCKED ([androidUnlockedUs]). */
    fun unlockedUs(context: Context, locked: Boolean): Boolean =
        faceOn && locked && androidUnlockedUs(true, deviceSecure(context), androidLocked(context), locked, interactive(context))

    /** No re-front now ([hybridRefrontHeld]); no binder call without [faceOn]. */
    fun refrontHeld(context: Context): Boolean = faceOn && hybridRefrontHeld(true, androidLocked(context))

    private fun interactive(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isInteractive == true

    // ---- the migration and the rollback (59 §9 steps 2 and 6) ------------------------------------------------

    /**
     * The kid PIN was just checked right on our keypad (background thread): with the switch on and no
     * credential, that string becomes Android's PIN through the reset token - never stored, never logged. Our
     * lock is only active on a managed phone. The phone stays unlocked; the next screen-off is the hybrid's.
     */
    fun migrateIfDue(context: Context, pin: String) {
        if (!switchOn) return
        val app = context.applicationContext
        if (!hybridMigrationDue(switchOn, managed = true, deviceSecure = deviceSecure(app))) return
        WindowRelock.ensureResetToken(app)
        val ok = WindowRelock.resetCredential(app, pin)
        Log.i(HYBRID_TAG, "migration: the kid PIN set as Android's PIN: $ok (secure now=${deviceSecure(app)}, token=${WindowRelock.resetTokenActive(app)})")
        if (ok) applySoon(app)
    }

    /**
     * The switch back to "own" (the debug receiver, after it stored the switch off): the credential cleared with
     * the token ([hybridRollback]) and the lock-screen line cleared; the caller's apply then writes DISABLED and
     * our keypad is the lock again. Without an active token Android's PIN stays the gate (design 10's runbook).
     */
    fun rollback(context: Context): HybridRollback {
        val app = context.applicationContext
        val step = hybridRollback(deviceSecure(app), WindowRelock.resetTokenActive(app))
        when (step) {
            HybridRollback.CLEAR -> Log.i(HYBRID_TAG, "rollback: Android's PIN cleared: ${WindowRelock.resetCredential(app, null)} (secure now=${deviceSecure(app)})")
            HybridRollback.NO_TOKEN -> Log.w(HYBRID_TAG, "rollback: an Android PIN and no active token - clear it in Settings in a window (design 10's runbook); Android's PIN stays the gate")
            HybridRollback.NOTHING -> Log.i(HYBRID_TAG, "rollback: no Android PIN - today's lock")
        }
        owner(app)?.let { (dpm, admin) -> lockScreenInfo(dpm, admin, null) }
        return step
    }

    // ---- the device checks' log lines --------------------------------------------------------------------------

    /** One line of state for [event] (face mode only): what a device run needs to read the hybrid's behaviour. */
    fun note(context: Context, event: String) {
        if (!faceOn) return
        val km = try {
            context.getSystemService(KeyguardManager::class.java)
        } catch (e: Exception) {
            null
        }
        val state = try {
            "keyguardLocked=${km?.isKeyguardLocked} deviceLocked=${km?.isDeviceLocked} secure=${km?.isDeviceSecure} interactive=${interactive(context)}"
        } catch (e: Exception) {
            "state unreadable (${e.javaClass.simpleName})"
        }
        Log.i(HYBRID_TAG, "$event: $state")
    }

    /**
     * At the glance's resume (face mode): Recents and the shade over the occluded keyguard are blocked by lock
     * task (59 §4.7; the user's Recents check, 2026-10-22) - lock task running, OVERVIEW (8) and NOTIFICATIONS (2)
     * out, KEYGUARD (32) in. Logged; a warning when one isn't so.
     */
    fun noteRecentsGuard(context: Context) {
        if (!faceOn) return
        val app = context.applicationContext
        try {
            val mode = app.getSystemService(ActivityManager::class.java)?.lockTaskModeState
            val features = owner(app)?.let { (dpm, admin) -> dpm.getLockTaskFeatures(admin) }
            val guarded = recentsGuarded(mode != null && mode != ActivityManager.LOCK_TASK_MODE_NONE, features)
            val line = "Recents guard: lockTask=$mode features=${features?.let { "0x" + Integer.toHexString(it) }} guarded=$guarded"
            if (guarded) Log.i(HYBRID_TAG, line) else Log.w(HYBRID_TAG, "$line - Recents or the shade may open over the glance")
        } catch (e: Exception) {
            Log.w(HYBRID_TAG, "Recents guard unreadable: ${e.javaClass.simpleName}")
        }
    }

    fun describe(context: Context): String {
        val app = context.applicationContext
        return "switch=${if (switchOn) "android" else "own"} face=$faceOn secure=${deviceSecure(app)} androidLocked=${androidLocked(app)} " +
            "token=${WindowRelock.resetTokenActive(app)} keyguard: ${KeyguardCover.describe()}"
    }

    private fun applySoon(app: Context) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppEnforcer.apply(app, currentPolicyDecision().policy)
            } catch (e: Exception) {
                Log.w(HYBRID_TAG, "apply after the migration failed", e)
            }
        }
    }

    private fun owner(app: Context): Pair<DevicePolicyManager, ComponentName>? {
        val dpm = app.getSystemService(DevicePolicyManager::class.java) ?: return null
        if (!dpm.isDeviceOwnerApp(app.packageName)) return null
        return dpm to ComponentName(app, MdmDeviceAdminReceiver::class.java)
    }
}
