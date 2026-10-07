package com.kidslauncher.mdm.lock

/*
 * The boot cover (design 16 option D = step 16b, QA #7-#11 and the decisions: D+A+B behind the
 * server switch `boot_cover`, off by default). Before the first unlock only direct-boot-aware HOME
 * activities resolve, so a stock launcher like Pixel's was Home for the seconds until our Home
 * could run. The cover is our own direct-boot-aware HOME (`BootCoverActivity`, own process
 * `:bootcover`, the breathing Vibb mark) with its own persistent preferred activity, enabled only
 * from shutdown to the next unlock - two HOME PPAs while unlocked would make Home unstable (QA #8).
 * Pure, tested in BootCoverPlanTest.
 */

/** The cover's process (`android:process=":bootcover"`): Application.onCreate does nothing there. */
const val BOOT_COVER_PROCESS_SUFFIX = ":bootcover"

fun isBootCoverProcess(processName: String?): Boolean = processName?.endsWith(BOOT_COVER_PROCESS_SUFFIX) == true

/** The extra category of the cover's persistent preferred activity: the DPM policy engine keys PPAs
 * by IntentFilter, so a distinct filter adds the cover's next to HomeActivity's instead of
 * replacing it (QA #7). A HOME intent still matches it (an intent's categories must be in the
 * filter, not the other way round). */
const val BOOT_COVER_CATEGORY = "com.kidslauncher.mdm.category.BOOT_COVER"

/** After the unlock the cover stays at least this long from its first frame, then hands over. */
const val COVER_MIN_SHOWN_MS = 1_000L

/** The cover's crash in a boot that disables it for the rest of that boot (QA #10). */
const val COVER_MAX_CRASHES = 2

/** Whether the cover may be used at all: the server switch on a managed phone of which we are
 * device owner (the switch is off by default - "test on this phone first"). */
fun bootCoverWanted(switchOn: Boolean, managed: Boolean, deviceOwner: Boolean): Boolean = switchOn && managed && deviceOwner

enum class CoverEvent {
    /** An apply with the current policy. */
    POLICY,

    /** `ACTION_SHUTDOWN` (also a reboot): the next boot's BFU window comes. */
    SHUTDOWN,

    /** Unlocked: the cover has been shown long enough, or our main process runs (Home's turn). */
    HANDED_OVER,

    /** The cover's [COVER_MAX_CRASHES]th crash in this boot. */
    CRASH_GUARD,
}

/**
 * The cover component's state after [event]: `true` enable, `false` disable, `null` leave it.
 * Off (switch, unmanaged, not device owner) = disabled at every event. On: enabled only at
 * shutdown - never while the phone runs unlocked (an apply leaves it), and disabled at the
 * hand-over and by the crash guard. A crash reboot sends no shutdown: the cover stays off and the
 * boot falls back to A+B (Home at boot, Home roots lock task).
 */
fun bootCoverEnabled(event: CoverEvent, wanted: Boolean): Boolean? = when {
    !wanted -> false
    event == CoverEvent.SHUTDOWN -> true
    event == CoverEvent.POLICY -> null
    else -> false
}

/** The cover's crash counter, in device-protected storage (it runs before the first unlock). */
data class CoverCrashes(val bootCount: Int, val crashes: Int)

/** One more crash of the cover; a new boot (or an unknown count changing) starts at one. */
fun coverCrashed(record: CoverCrashes?, bootNow: Int): CoverCrashes =
    if (record != null && record.bootCount == bootNow) record.copy(crashes = record.crashes + 1) else CoverCrashes(bootNow, 1)

/** The cover must not run again this boot. An unreadable boot count (-1) counts as one boot:
 * the cover then stays off (fail safe - A+B). */
fun coverGuardTripped(record: CoverCrashes?, bootNow: Int): Boolean =
    record != null && record.bootCount == bootNow && record.crashes >= COVER_MAX_CRASHES

/** How long until the cover hands over (disables itself): `null` while locked (BFU), else what is
 * left of [COVER_MIN_SHOWN_MS] since its first frame ([shownForMs] < 0 = not shown yet: the
 * whole minimum). */
fun coverHandOverDelayMs(unlocked: Boolean, shownForMs: Long): Long? =
    if (!unlocked) null else if (shownForMs < 0) COVER_MIN_SHOWN_MS else (COVER_MIN_SHOWN_MS - shownForMs).coerceAtLeast(0L)
