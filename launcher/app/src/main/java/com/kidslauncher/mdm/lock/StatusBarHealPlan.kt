package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.LOCK_TASK_FEATURE_SYSTEM_INFO
import com.kidslauncher.mdm.server.LockTaskSetting

/*
 * Design 16d, decision 1 (experiment 2 §2): after a boot SystemUI can miss lock task's status-bar
 * disable - `dumpsys statusbar` had the LOCKED flags (mDisabled1=0x7260000 mDisabled2=0x15) but
 * SystemUI's TaskbarDelegate had 0 - and over the PIN lock the shade opened with Quick Settings and
 * the hold opened the Overview, until the next change of the disable flags.
 *
 * `setStatusBarDisabled` can't force that change in lock task: DPMS only records it there
 * (LockTaskController owns the status bar during lock task) and a same-value call is a no-op. The
 * heal changes the lock-task features for a moment instead: SYSTEM_INFO flipped changes only the
 * clock and the system icons (DISABLE_CLOCK, DISABLE2_SYSTEM_ICONS) - never the shade, Quick
 * Settings, Home, Recents or the power menu - so StatusBarManagerService's net flags change and
 * SystemUI is sent the whole set, twice (flipped, then back). Pure - StatusBarHealTest.
 *
 * An app can't verify the result: SystemUI's copy is only in its dumpsys (DUMP) and
 * `StatusBarManager.getDisableInfo` is a system API (STATUS_BAR). smoke-test.sh REBOOT=1 checks it.
 */

/**
 * How long the flipped features stay before a normal pass writes them back. LockTaskController
 * posts its status-bar update and reads the features when it runs: two writes before it runs would
 * leave the net flags unchanged and send SystemUI nothing.
 */
const val STATUS_BAR_HEAL_FLIP_MS = 250L

/**
 * The heals after each process start, in ms after `PinLockRuntime.init` (the boot's user unlock,
 * or our restart after a self-update or a crash). In experiment 2 quickstep's TIS init - the
 * likely moment SystemUI loses the flags - came 2.4-5.2 s after the unlock.
 */
val STATUS_BAR_HEALS_AFTER_START_MS: List<Long> = listOf(1_000L, 3_000L, 6_000L, 10_000L, 20_000L, 40_000L)

/** Why a heal runs (logged). */
enum class HealTrigger(val label: String) {
    /** One of [STATUS_BAR_HEALS_AFTER_START_MS] after the process start. */
    PROCESS_START("process start"),
    BOOT_COMPLETED("boot completed"),
    /** The first screen-on of this process. */
    FIRST_SCREEN_ON("first screen-on"),
    /** Every screen-off (invisible): a SystemUI or quickstep restart can't be detected by an app,
     * so this puts the flags back before the next screen-on. */
    SCREEN_OFF("screen off"),
}

/**
 * The lock-task features of a heal's first write: [setting]'s with SYSTEM_INFO flipped. `null` when
 * nothing is pinned ([LockTaskSetting.packages] `null`: kiosk off and not LOCKED - no lock task, so
 * lock task's status-bar flags aren't in force and there is nothing to heal).
 */
fun statusBarHealFeatures(setting: LockTaskSetting): Int? =
    if (setting.packages == null) null else setting.features xor LOCK_TASK_FEATURE_SYSTEM_INFO

/** A heal's first write ([flipped], from [original]) that no pass has written over yet. */
data class HealFlip(val original: Int, val flipped: Int)

/**
 * The base features a pass without a plan reads back from the platform (LockTaskChrome's
 * fallback): while a heal's flip is up the platform shows [HealFlip.flipped], which counts as
 * [HealFlip.original] - so that pass writes the original back, never keeps the flip.
 */
fun healBase(platformFeatures: Int, flip: HealFlip?): Int =
    if (flip != null && platformFeatures == flip.flipped) flip.original else platformFeatures
