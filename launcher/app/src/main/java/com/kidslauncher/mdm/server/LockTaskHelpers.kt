package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.play.PLAY_CORE

/*
 * System helpers pinned in kiosk next to the allowlist while LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK
 * is on (handy step 9, B4 with qa-09-design.md #2/#3 on top). With that bit every activity start
 * whose package isn't a lock-task package becomes the system's BlockedAppActivity - with no
 * emergency exemption - so whatever emergency calls, runtime permission dialogs, the share sheet
 * and the pickers need must be pinned. Package names differ per device, so AppEnforcer resolves
 * them from intents at every apply; this file only decides. Pure, tested in LockTaskHelpersTest.
 */

/** `DevicePolicyManager.LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` (API 30, public). */
const val LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK = 64

enum class HelperKind {
    /** `ACTION_EMERGENCY_DIAL` (com.android.phone's EmergencyDialer on AOSP). */
    EMERGENCY_DIALER,
    /** Telecom (`com.android.server.telecom`): UserCallActivity/EmergencyCallActivity for
     * `ACTION_CALL*`, call error dialogs. */
    TELECOM,
    /** `PackageManager.permissionControllerPackageName`: runtime permission dialogs and the role
     * request dialog (our dialer/SMS role prompt). */
    PERMISSION_CONTROLLER,
    /** `ACTION_CHOOSER` - `com.android.intentresolver` on API 34+. */
    CHOOSER,
    /** `ACTION_OPEN_DOCUMENT` - DocumentsUI. */
    DOCUMENTS,
    /** `MediaStore.ACTION_PICK_IMAGES` - the photo picker. */
    PHOTO_PICKER,
    /** The receiver of `SMS_CB_RECEIVED` - emergency alerts (Nødvarsel). */
    CELL_BROADCAST,
    /** The "open with" disambiguation screen (ResolverActivity, package `android` on AOSP) for an
     * implicit intent with several handlers and no default (qa-09-code #5). */
    RESOLVER,
}

/** What an intent resolved to: the package and whether it is a system app (FLAG_SYSTEM). */
data class ResolvedHelper(val packageName: String, val system: Boolean)

/**
 * The helper packages to pin: every resolved helper that is a system app, except packages that
 * must never be reachable this way ([forbidden]: Settings, the launcher-visible camera and
 * whatever else the caller names) and Play core (Play Store, Play services, GSF). A forbidden
 * package the parent allowlisted is pinned by the allowlist anyway, not by this. The system
 * dialer is pinned by [computeEnforcementPlan] itself whenever the block is on (qa-09-code #1).
 */
fun lockTaskHelpers(resolved: Map<HelperKind, ResolvedHelper?>, forbidden: Set<String>): Set<String> =
    resolved.values.filterNotNull()
        .filter { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }
        .mapTo(mutableSetOf()) { it.packageName }

/**
 * Of the packages an intent resolves to (in the platform's order), the first that is a system app
 * and not [forbidden] - a forbidden first match (the dialer or the camera for `ACTION_CALL`) must
 * not hide the real helper behind it (qa-09-code #6).
 */
fun firstHelper(matches: List<ResolvedHelper>, forbidden: Set<String>): ResolvedHelper? =
    matches.firstOrNull { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }

/**
 * The lock-task features to set: the server's (minus the block bit - that travels as its own
 * switch, so an older launcher never gets it), keyguard always, and the block bit when the
 * server's [blockActivityStart] switch is on.
 */
fun lockTaskFeatures(serverFeatures: Long, blockActivityStart: Boolean): Int {
    val base = (serverFeatures.toInt() and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK.inv()) or LOCK_TASK_FEATURE_KEYGUARD
    return if (blockActivityStart) base or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK else base
}

/*
 * Handy's PIN lock (step 10, design 10-lock-and-call-ui.md §3 with qa-10-design.md #1/#10): while
 * the lock is LOCKED the lock screen always runs in lock task. The same pure functions serve the
 * fast path (PinLockRuntime via LockTaskChrome) and every apply(), so a sync can't undo them.
 */

/** `DevicePolicyManager.LOCK_TASK_FEATURE_*` (public constants, duplicated to stay Android-free). */
const val LOCK_TASK_FEATURE_SYSTEM_INFO = 1
const val LOCK_TASK_FEATURE_NOTIFICATIONS = 2
const val LOCK_TASK_FEATURE_HOME = 4
const val LOCK_TASK_FEATURE_OVERVIEW = 8
const val LOCK_TASK_FEATURE_GLOBAL_ACTIONS = 16

/** Kiosk off while LOCKED: the status line (time, battery), the power menu and the keyguard bit
 * (it carries AOSP's emergency-call exemption) - no shade, no Home, no Recents, no app block. */
const val PIN_LOCK_FEATURES_KIOSK_OFF =
    LOCK_TASK_FEATURE_KEYGUARD or LOCK_TASK_FEATURE_GLOBAL_ACTIONS or LOCK_TASK_FEATURE_SYSTEM_INFO

/**
 * Kiosk on: the plan's features minus the shade/heads-up alerts, Home and Recents while LOCKED.
 * SYSTEM_INFO (the mock shows Android's status line), GLOBAL_ACTIONS as the server set it,
 * KEYGUARD and the app-block bit stay.
 */
fun featuresWhileLocked(base: Int, locked: Boolean): Int =
    if (!locked) {
        base
    } else {
        (base and (LOCK_TASK_FEATURE_NOTIFICATIONS or LOCK_TASK_FEATURE_HOME or LOCK_TASK_FEATURE_OVERVIEW).inv()) or
            LOCK_TASK_FEATURE_KEYGUARD
    }

/**
 * What to set on the platform: lock-task [packages] (`null` = none, kiosk off and unlocked),
 * [features], `setStatusBarDisabled` ([statusBarDisabled]: a backstop while LOCKED - ineffective
 * while pinned - and the shade block of the update fence, step 11) and `DISALLOW_CREATE_WINDOWS`
 * ([createWindowsBlocked]: no chat heads or other overlays over the lock, QA 10 #10).
 */
data class LockTaskSetting(
    val packages: Set<String>?,
    val features: Int,
    val statusBarDisabled: Boolean,
    val createWindowsBlocked: Boolean,
)

/**
 * The lock-task setting for the plan's [kioskPackages]/[baseFeatures]/[restrictCreateWindows]
 * and the PIN lock state:
 * - unlocked (or no lock): the plan as it is;
 * - LOCKED, kiosk on: the kiosk list is **never** touched (removing a package would clear its
 *   locked task - the kid's app would lose its state at every screen-off), only the features;
 * - LOCKED, kiosk off: our package plus [lockHelpers] (emergency dialer, Telecom, the system
 *   dialer, the system clock app - no third-party app) with [PIN_LOCK_FEATURES_KIOSK_OFF]; the
 *   lock screen starts lock task itself and stops it on unlock.
 * The status bar is disabled while LOCKED **or** while the update fence is up ([fenced], step 11,
 * qa-11-design.md #4): the fence is an input here, so LockTaskChrome stays the only owner of the
 * status bar and a release always re-enables it when nothing else wants it off.
 */
fun lockTaskWhileLocked(
    kioskPackages: Set<String>?,
    baseFeatures: Int,
    restrictCreateWindows: Boolean,
    locked: Boolean,
    ownPackage: String,
    lockHelpers: Set<String>,
    fenced: Boolean = false,
): LockTaskSetting = when {
    !locked -> LockTaskSetting(kioskPackages, baseFeatures, statusBarDisabled = fenced, createWindowsBlocked = restrictCreateWindows)
    kioskPackages != null -> LockTaskSetting(
        kioskPackages, featuresWhileLocked(baseFeatures, true), statusBarDisabled = true, createWindowsBlocked = true,
    )
    else -> LockTaskSetting(
        setOf(ownPackage) + (lockHelpers - PLAY_CORE), PIN_LOCK_FEATURES_KIOSK_OFF, statusBarDisabled = true, createWindowsBlocked = true,
    )
}

/**
 * LockTaskChrome's memory of the status-bar state it set, so it doesn't call
 * `setStatusBarDisabled` on every pass. [invalidate] (process start, every fence and release)
 * makes the next pass write whatever it wants - a release must re-enable the bar even if the
 * memory says it already is (qa-11-design.md #4).
 */
class StatusBarLatch {
    private var applied: Boolean? = null

    /** The value to write, or `null` when [wanted] is already set. */
    @Synchronized
    fun toWrite(wanted: Boolean): Boolean? = if (applied == wanted) null else wanted

    /** The platform accepted [value]. */
    @Synchronized
    fun written(value: Boolean) {
        applied = value
    }

    @Synchronized
    fun invalidate() {
        applied = null
    }
}

/**
 * The system packages the lock pins with the kiosk off: whatever handles the emergency dialer and
 * `ACTION_CALL` (Telecom), the system dialer - only while our dialer role isn't held, when it is
 * the in-call UI of every call (qa-10-code #5; with the role held its emergency in-call UI runs
 * under AOSP's KEYGUARD exemption, and pinning it would let its full UI - call log, contacts,
 * keypad - stay over the lock) - and the clock app (its alarm screen shows over the lock). Only system apps, never a
 * forbidden one (Settings, the camera) or Play.
 */
fun pinLockHelpers(
    emergencyDialer: ResolvedHelper?,
    telecom: ResolvedHelper?,
    systemDialer: ResolvedHelper?,
    alarmApp: ResolvedHelper?,
    forbidden: Set<String>,
    ourDialerHeld: Boolean = false,
): Set<String> = listOfNotNull(emergencyDialer, telecom, systemDialer.takeIf { !ourDialerHeld }, alarmApp)
    .filter { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }
    .mapTo(mutableSetOf()) { it.packageName }
