package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.play.PLAY_CORE
import com.kidslauncher.mdm.server.ownPackageFamily

/*
 * The camera while handy's PIN lock is up (user report from the emulator run: the double-press
 * power gesture started com.android.camera2 for about a second at screen-on before the lock came
 * back). `KEYGUARD_DISABLE_SECURE_CAMERA` does nothing without an Android keyguard (step 10: the
 * screen lock is "None"), and the gesture setting is a secure setting a device owner can't write -
 * so while LOCKED the allowed camera apps are suspended (a suspended app's start becomes Android's
 * "app paused" dialog, and the lock re-fronts), and unsuspended at the unlock. Record before act,
 * like the update fence; released idempotently at process start when not LOCKED. Pure - tested in
 * CameraLockTest; CameraLock.kt is the glue.
 */

/** Own CE prefs file; keys pinned by CameraLockTest - only ever add keys. */
const val CAMERA_LOCK_PREFS = "camera_lock"

object CameraLockKeys {
    const val VERSION = "v"
    /** The packages the camera lock suspended (string set) - the only ones it ever unsuspends. */
    const val SUSPENDED = "suspended"
}

const val CAMERA_LOCK_V1 = 1

/**
 * The camera apps to suspend while LOCKED: the resolved camera handlers ([cameraHandlers] - the
 * still-image/video camera actions the gesture uses, plus system apps answering
 * `ACTION_IMAGE_CAPTURE`) that enforcement owns ([controllable]: an app with a launcher icon or
 * a third-party one - never a headless system component, the boot-loop class), minus ours, the
 * [protected] packages (dialers, emergency dialer, Telecom, keyboards, the lock's and the kiosk
 * block's helpers) and Play core.
 */
fun cameraLockTargets(
    cameraHandlers: Set<String>,
    controllable: Set<String>,
    ownPackage: String,
    protected: Set<String>,
): Set<String> = (cameraHandlers intersect controllable) - ownPackageFamily(ownPackage) - protected - PLAY_CORE

sealed interface CameraLockStep {
    /** Write [record] first (it already holds [packages]), then suspend [packages]. */
    data class Suspend(val packages: Set<String>, val record: Set<String>) : CameraLockStep

    /** Unsuspend [packages], then clear the record. */
    data class Release(val packages: Set<String>) : CameraLockStep

    data object Nothing : CameraLockStep
}

/**
 * - LOCKED: suspend every target that isn't suspended yet - one that enforcement already
 *   suspended (not allowed, a time-rule lock) is never recorded, so the unlock never lifts
 *   enforcement's suspension; our earlier ones stay recorded.
 * - not LOCKED (unlocked, lock off, process start without the lock): release everything recorded,
 *   except what enforcement's last plan suspends itself ([enforcementSuspends]; `null` = no plan
 *   in this process yet - then all, and the caller asks `apply()` to settle it).
 */
fun cameraLockStep(
    locked: Boolean,
    recorded: Set<String>,
    targets: Set<String>,
    suspendedNow: Set<String>,
    enforcementSuspends: Set<String>?,
): CameraLockStep {
    if (locked) {
        val toSuspend = targets - suspendedNow
        if (toSuspend.isEmpty()) return CameraLockStep.Nothing
        return CameraLockStep.Suspend(toSuspend, recorded + toSuspend)
    }
    if (recorded.isEmpty()) return CameraLockStep.Nothing
    return CameraLockStep.Release(recorded - enforcementSuspends.orEmpty())
}

/** The recorded set from the prefs ([values] = `SharedPreferences.getAll()`); unreadable = whatever
 * string set is there under [CameraLockKeys.SUSPENDED] (always released when not LOCKED). */
fun decodeCameraLockRecord(values: Map<String, *>): Set<String> =
    (values[CameraLockKeys.SUSPENDED] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
