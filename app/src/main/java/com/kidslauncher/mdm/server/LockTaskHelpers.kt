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
}

/** What an intent resolved to: the package and whether it is a system app (FLAG_SYSTEM). */
data class ResolvedHelper(val packageName: String, val system: Boolean)

/**
 * The helper packages to pin: every resolved helper that is a system app, except packages that
 * must never be reachable this way ([forbidden]: Settings, the launcher-visible camera, the system
 * dialer - whose pinning stays under the call rules, QA 09 #3 - and whatever else the caller
 * names) and Play core (Play Store, Play services, GSF). A forbidden package the parent allowlisted
 * is pinned by the allowlist anyway, not by this.
 */
fun lockTaskHelpers(resolved: Map<HelperKind, ResolvedHelper?>, forbidden: Set<String>): Set<String> =
    resolved.values.filterNotNull()
        .filter { it.system && it.packageName !in forbidden && it.packageName !in PLAY_CORE }
        .mapTo(mutableSetOf()) { it.packageName }

/**
 * The lock-task features to set: the server's (minus the block bit - that travels as its own
 * switch, so an older launcher never gets it), keyguard always, and the block bit when the
 * server's [blockActivityStart] switch is on.
 */
fun lockTaskFeatures(serverFeatures: Long, blockActivityStart: Boolean): Int {
    val base = (serverFeatures.toInt() and LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK.inv()) or LOCK_TASK_FEATURE_KEYGUARD
    return if (blockActivityStart) base or LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK else base
}
