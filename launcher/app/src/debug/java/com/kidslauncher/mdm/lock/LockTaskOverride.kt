package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LockTaskSetting

/*
 * Debug build only (src/debug - never in a release APK, checked by `checkReleaseHasNoDebugHook`
 * and `DebugHookAbsentTest`): an emulator experiment hook for design 16d (gesture navigation's
 * "App is not available" in kiosk, docs/design/16d-recents.md). `LockTaskOverrideReceiver` stores
 * an override; [LockTaskChrome] - still the only writer - applies it on top of the computed plan.
 * Pure, tested in LockTaskOverrideTest (src/testDebug). How to use: docs/testing/emulator.md §6e.
 */

/** Feature bits cleared from and added to the computed lock-task features, and packages pinned
 * on top of the computed list. */
data class LockTaskOverride(
    val clearFeatures: Int = 0,
    val addFeatures: Int = 0,
    val extraPackages: Set<String> = emptySet(),
)

/** `DevicePolicyManager.LOCK_TASK_FEATURE_*`, duplicated to stay Android-free. */
const val LOCK_TASK_FEATURE_ALL = 0x7F
private const val FEATURE_NOTIFICATIONS = 1 shl 1
private const val FEATURE_HOME = 1 shl 2
private const val FEATURE_OVERVIEW = 1 shl 3
private const val FEATURE_KEYGUARD = 1 shl 5

private val PACKAGE_NAME = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

/** The override from the broadcast's extras (`clear_features`, `add_features`,
 * `extra_lock_task_packages` - comma separated); unknown bits and invalid names are dropped.
 * `null` when it would change nothing. */
fun lockTaskOverride(clearFeatures: Int, addFeatures: Int, packages: String?): LockTaskOverride? {
    val names = packages.orEmpty().split(',').map { it.trim() }.filter { PACKAGE_NAME.matches(it) }.toSortedSet()
    val override = LockTaskOverride(clearFeatures and LOCK_TASK_FEATURE_ALL, addFeatures and LOCK_TASK_FEATURE_ALL, names)
    return override.takeUnless { it.clearFeatures == 0 && it.addFeatures == 0 && it.extraPackages.isEmpty() }
}

/**
 * The computed [setting] with [override] on top: features = (computed - clear) + add, but the
 * keyguard bit is never cleared (the boot-lockout incident, launcher CLAUDE.md), and OVERVIEW and
 * NOTIFICATIONS are dropped without HOME (AOSP's setLockTaskFeatures refuses them - LockTaskChrome
 * would then refuse to pin). Extra packages only join an existing list: with no list (kiosk off and
 * unlocked) nothing is pinned. Status bar and DISALLOW_CREATE_WINDOWS are untouched.
 */
fun applyLockTaskOverride(setting: LockTaskSetting, override: LockTaskOverride?): LockTaskSetting {
    if (override == null) return setting
    var features = ((setting.features and override.clearFeatures.inv()) or override.addFeatures) and LOCK_TASK_FEATURE_ALL
    features = features or (setting.features and FEATURE_KEYGUARD)
    if ((features and FEATURE_HOME) == 0) features = features and (FEATURE_OVERVIEW or FEATURE_NOTIFICATIONS).inv()
    return setting.copy(features = features, packages = setting.packages?.let { it + override.extraPackages })
}

/** Readable feature bits for the log, e.g. `113 [SYSTEM_INFO, GLOBAL_ACTIONS, KEYGUARD, BLOCK_ACTIVITY_START_IN_TASK]`. */
fun describeLockTaskFeatures(features: Int): String {
    val names = listOf("SYSTEM_INFO", "NOTIFICATIONS", "HOME", "OVERVIEW", "GLOBAL_ACTIONS", "KEYGUARD", "BLOCK_ACTIVITY_START_IN_TASK")
    return "$features " + names.filterIndexed { bit, _ -> features and (1 shl bit) != 0 }.joinToString(", ", "[", "]")
}
