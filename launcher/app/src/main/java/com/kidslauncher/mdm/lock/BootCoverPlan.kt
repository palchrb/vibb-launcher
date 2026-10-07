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

/** Status-report capability: this launcher has the boot cover and reads `boot_cover`. */
const val BOOT_COVER_CAPABILITY = "boot_cover_v1"

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
fun bootCoverEnabled(event: CoverEvent, wanted: Boolean, guardTripped: Boolean = false): Boolean? = when {
    !wanted -> false
    // A tripped guard holds until the switch goes off (which clears it) and on again
    // (qa-16b-code #2): no more crashing starts at every boot.
    event == CoverEvent.SHUTDOWN -> !guardTripped
    event == CoverEvent.POLICY -> null
    else -> false
}

/**
 * What the cover's process keeps in device-protected storage (it runs before the first unlock),
 * one small file read fresh by both processes: its crashes in [bootCount], whether the guard
 * [tripped] (sticky), and when it was last shown and handed over (status report).
 */
data class CoverRecord(
    val bootCount: Int = -1,
    val crashes: Int = 0,
    val tripped: Boolean = false,
    val shownAtMs: Long? = null,
    val handedOverAtMs: Long? = null,
)

/** One more crash of the cover; a new boot (or an unknown count changing) counts from one. The
 * [COVER_MAX_CRASHES]th in a boot trips the guard, and a trip stays (qa-16b-code #2). An
 * unreadable boot count (-1) counts as one boot. */
fun coverCrashed(record: CoverRecord?, bootNow: Int): CoverRecord {
    val crashes = if (record != null && record.bootCount == bootNow) record.crashes + 1 else 1
    return (record ?: CoverRecord()).copy(
        bootCount = bootNow,
        crashes = crashes,
        tripped = record?.tripped == true || crashes >= COVER_MAX_CRASHES,
    )
}

/** The cover must not run again (until the switch is turned off and on). */
fun coverGuardTripped(record: CoverRecord?): Boolean = record?.tripped == true

/** The record as stored (`key=value` lines, version 1). */
fun encodeCoverRecord(record: CoverRecord): String = buildString {
    append("v=1\n")
    append("boot=").append(record.bootCount).append('\n')
    append("crashes=").append(record.crashes).append('\n')
    append("tripped=").append(record.tripped).append('\n')
    record.shownAtMs?.let { append("shown=").append(it).append('\n') }
    record.handedOverAtMs?.let { append("handed=").append(it).append('\n') }
}

/** `null` = no record. A file that exists but can't be read counts as tripped: the cover stays
 * off (fail safe - A+B). */
fun decodeCoverRecord(text: String?): CoverRecord? {
    if (text == null) return null
    val fields = text.lines().mapNotNull { line -> line.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    if (fields["v"] != "1") return CoverRecord(tripped = true)
    return CoverRecord(
        bootCount = fields["boot"]?.toIntOrNull() ?: return CoverRecord(tripped = true),
        crashes = fields["crashes"]?.toIntOrNull() ?: return CoverRecord(tripped = true),
        tripped = fields["tripped"]?.toBooleanStrictOrNull() ?: return CoverRecord(tripped = true),
        shownAtMs = fields["shown"]?.toLongOrNull(),
        handedOverAtMs = fields["handed"]?.toLongOrNull(),
    )
}

/** How long until the cover hands over (disables itself): `null` while locked (BFU), else what is
 * left of [COVER_MIN_SHOWN_MS] since its first frame ([shownForMs] < 0 = not shown yet: the
 * whole minimum). */
fun coverHandOverDelayMs(unlocked: Boolean, shownForMs: Long): Long? =
    if (!unlocked) null else if (shownForMs < 0) COVER_MIN_SHOWN_MS else (COVER_MIN_SHOWN_MS - shownForMs).coerceAtLeast(0L)
