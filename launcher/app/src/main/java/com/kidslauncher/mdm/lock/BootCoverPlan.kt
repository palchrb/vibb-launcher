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

/** After the unlock the cover stays at least this long from its first frame, then hands over - the
 * boot mark's 3 s (16e), which Home's mark finishes when the main process takes over earlier. */
const val COVER_MIN_SHOWN_MS = BOOT_MARK_MS

/** The cover's crash in a boot that disables it for the rest of that boot (QA #10). */
const val COVER_MAX_CRASHES = 2

/** Whether the cover may be used at all: the server switch on a managed phone of which we are
 * device owner (the switch is off by default - "test on this phone first"), or Android's lock as the
 * face ([androidFace], design 59 F3): there the PIN's dismissal would show the stock launcher until
 * our process is up, so android mode implies the cover - no server switch or contract change. */
fun bootCoverWanted(switchOn: Boolean, managed: Boolean, deviceOwner: Boolean, androidFace: Boolean = false): Boolean =
    (switchOn || androidFace) && managed && deviceOwner

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
 * [tripped] (sticky), and when it was last shown and handed over (status report). 16e: its first
 * frame in boot [shownBootCount] as elapsed realtime ([shownElapsedMs]) - Home's mark counts on from
 * it ([coverFrameThisBoot]).
 */
data class CoverRecord(
    val bootCount: Int = -1,
    val crashes: Int = 0,
    val tripped: Boolean = false,
    val shownAtMs: Long? = null,
    val handedOverAtMs: Long? = null,
    val shownBootCount: Int? = null,
    val shownElapsedMs: Long? = null,
    /** F3C-3: the launcher's versionCode when the guard tripped (`null` = unknown, a trip from an older build). */
    val trippedVersion: Long? = null,
)

/** The cover was shown at [wallMs]/[elapsedMs] in boot [bootNow]: the last-shown time for the
 * report, and the boot's first frame - kept over a recreation in the same boot, new in a new one. */
fun coverShown(record: CoverRecord, bootNow: Int, wallMs: Long, elapsedMs: Long): CoverRecord {
    val earlier = coverFrameThisBoot(record, bootNow)
    return record.copy(
        shownAtMs = wallMs,
        shownBootCount = bootNow,
        shownElapsedMs = if (earlier != null) minOf(earlier, elapsedMs) else elapsedMs,
    )
}

/** One more crash of the cover; a new boot (or an unknown count changing) counts from one. The
 * [COVER_MAX_CRASHES]th in a boot trips the guard, and a trip stays (qa-16b-code #2). An
 * unreadable boot count (-1) counts as one boot. */
fun coverCrashed(record: CoverRecord?, bootNow: Int, versionCode: Long? = null): CoverRecord {
    val crashes = if (record != null && record.bootCount == bootNow) record.crashes + 1 else 1
    val wasTripped = record?.tripped == true
    val tripped = wasTripped || crashes >= COVER_MAX_CRASHES
    return (record ?: CoverRecord()).copy(
        bootCount = bootNow,
        crashes = crashes,
        tripped = tripped,
        // F3C-3: the build that tripped it; a later build gets one more try.
        trippedVersion = if (tripped && !wasTripped) versionCode else record?.trippedVersion,
    )
}

/** The cover must not run again (until the switch is turned off and on, or the launcher is updated - F3C-3). */
fun coverGuardTripped(record: CoverRecord?): Boolean = record?.tripped == true

/**
 * F3C-3: a tripped guard is cleared (one more try) when the launcher was updated since the trip ([versionNow] differs
 * from [CoverRecord.trippedVersion], an unknown one included), or on the server switch's off -> on edge
 * ([switchWas] -> [switchNow]) - in every mode, so android mode (which keeps the cover wanted) isn't stuck with it.
 */
fun coverGuardClearDue(record: CoverRecord?, versionNow: Long, switchWas: Boolean, switchNow: Boolean): Boolean =
    coverGuardTripped(record) && (record?.trippedVersion != versionNow || (!switchWas && switchNow))

/** The record as stored (`key=value` lines, version 1). */
fun encodeCoverRecord(record: CoverRecord): String = buildString {
    append("v=1\n")
    append("boot=").append(record.bootCount).append('\n')
    append("crashes=").append(record.crashes).append('\n')
    append("tripped=").append(record.tripped).append('\n')
    record.shownAtMs?.let { append("shown=").append(it).append('\n') }
    record.handedOverAtMs?.let { append("handed=").append(it).append('\n') }
    record.shownBootCount?.let { append("shown_boot=").append(it).append('\n') }
    record.shownElapsedMs?.let { append("shown_elapsed=").append(it).append('\n') }
    record.trippedVersion?.let { append("trip_version=").append(it).append('\n') }
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
        shownBootCount = fields["shown_boot"]?.toIntOrNull(),
        shownElapsedMs = fields["shown_elapsed"]?.toLongOrNull(),
        trippedVersion = fields["trip_version"]?.toLongOrNull(),
    )
}

/** How long until the cover hands over (disables itself): `null` while locked (BFU), else what is
 * left of [COVER_MIN_SHOWN_MS] since its first frame ([shownForMs] < 0 = not shown yet: the
 * whole minimum). */
fun coverHandOverDelayMs(unlocked: Boolean, shownForMs: Long): Long? =
    if (!unlocked) null else if (shownForMs < 0) COVER_MIN_SHOWN_MS else (COVER_MIN_SHOWN_MS - shownForMs).coerceAtLeast(0L)

/*
 * Design 59 F3: the first unlock after a reboot with Android's lock. Before the PIN our main process can't
 * run (CE locked), so the cover (behind Android's keyguard, never showWhenLocked) is what the PIN's dismissal
 * reveals, and it stays until our main process has taken over - it disables the cover right before the boot's
 * typed Home start (`BootCover.init`/`takeOver`, then `BootHome`) - and our Home is over it. The main process writes
 * the device-protected marker `boot_cover_android` while this holds ([bootCoverAndroidMarker]); the cover and the
 * locked boot read it (no CE before the PIN).
 */

/** The marker's value at an apply: the cover is wanted because of, or together with, Android's PIN as the gate
 * ([androidGate]: the face, or a credential we own on a secure phone - the rollback's escrow included, F3C-4). */
fun bootCoverAndroidMarker(wanted: Boolean, androidGate: Boolean): Boolean = wanted && androidGate

/** After the unlock the cover waits this long for our main process to take over, then hands over by itself
 * and starts our Home (never stranded; the debug build's first CE start took 12.6-31 s in the emulator runs). */
const val COVER_LAUNCHER_WAIT_MS = 30_000L

/** Taken over (disabled by our main process) but still in front this long: it finishes anyway (the boot's
 * Home start didn't come, or it was refused). Normally Home comes over it first ([coverWaitStep]). */
const val COVER_TAKEOVER_GRACE_MS = 5_000L

/** Taken over but still in front this long: the cover starts our Home itself (typed HOME, now HomeActivity) - a
 * process death between the take-over and Home's start leaves nothing else to start it (F3C-2). 3 s, not 500 ms:
 * the normal pass took up to ~1 s between the take-over and Home's start on the debug build (f3-run B4); the
 * system finishing the cover first is covered by [coverDestroyStartsHome]. */
const val COVER_HOME_NUDGE_MS = 3_000L

/** Its own hand-over's disable that doesn't read back this many times: Home is started anyway (F3C-7). */
const val COVER_DISABLE_TRIES = 4

/** A locked-boot start held back by a live call is tried again this long later, by an alarm to our manifest
 * receiver - delivered to a cached, frozen BFU process, unlike a runtime receiver's broadcast (f3-run B1). */
const val COVER_LOCKED_BOOT_RETRY_MS = 5_000L

/** What the locked boot does with the cover in android mode. */
enum class LockedBootCover {
    /** Nothing: not android mode, unlocked, guard tripped, our process took over, or the boot's HOME is the cover. */
    NONE,

    /** Enable it and start it now. */
    START,

    /** A call is live: try again in [COVER_LOCKED_BOOT_RETRY_MS] (an alarm, never a runtime receiver). */
    RETRY,
}

/**
 * Android mode, at `LOCKED_BOOT_COMPLETED` (main process, before the PIN) and at its retry alarm: start the cover as
 * the typed HOME, so also a boot without the shutdown's arm (`adb reboot`, a crash or a hard reset, or a shutdown
 * broadcast our cached process never got - f3-run B6) has it behind the keyguard. Decided at that moment (f3-run B1:
 * a runtime receiver's later broadcast is deferred while the BFU process is frozen):
 * - NONE once unlocked or after our process's take-over ([tookOver], F3C-6), with a tripped guard, or when the
 *   boot's HOME already resolves to the cover ([armedAsHome]: the shutdown armed it; a HOME that resolves to the
 *   chooser or the stock launcher is not armed - f3-run B2 - and gets the start).
 * - RETRY while a call is live (F3C-1: a HOME start would move a BFU call screen behind the keyguard).
 * - START otherwise. The screen state isn't read (B1's `isInteractive` race): `LOCKED_BOOT_COMPLETED` comes 1-5 s
 *   after the boot completes, before the kid can type the PIN. A keyguard emergency dialer opened in those seconds
 *   would go behind the keyguard - no public API tells an occluding activity apart; the kid opens it again.
 */
fun coverAtLockedBoot(
    androidMarker: Boolean,
    unlocked: Boolean,
    guardTripped: Boolean,
    tookOver: Boolean,
    armedAsHome: Boolean,
    callLive: Boolean,
): LockedBootCover = when {
    !androidMarker || unlocked || guardTripped || tookOver || armedAsHome -> LockedBootCover.NONE
    callLive -> LockedBootCover.RETRY
    else -> LockedBootCover.START
}

enum class CoverStep {
    /** Stay up (BFU, or waiting for our main process). */
    STAY,

    /** Leave now: our main process took over, and our Home is over the cover (or the grace is over). */
    FINISH,

    /** Taken over, still in front: start our Home (typed HOME) - nothing else may (F3C-2, a process death). */
    START_HOME,

    /** Our main process didn't take over in time: disable itself and start our Home (typed HOME). */
    HAND_OVER,
}

/**
 * The cover's step in android mode (F3), checked every [COVER_WAIT_POLL_MS] while it is in front and once when
 * it stops. [disabled]: its component reads disabled (our main process's take-over, or its own
 * [CoverStep.HAND_OVER]); [visible]: not stopped (stopped while disabled = our Home or a call is over it - the
 * finish then reveals nothing); [homeStarted]: it started our Home itself already; [callLive]: a call (Telecom's view,
 * the cover's process can't see ours) - no Home start of its own then (f3-run B5). Never leaves before the unlock,
 * never leaves on a stop while still enabled (the keyguard coming back, a call), never starts Home while stopped or in
 * a call, and never stays past [COVER_LAUNCHER_WAIT_MS] + [COVER_TAKEOVER_GRACE_MS] in front after the unlock once no
 * call is live.
 */
fun coverWaitStep(
    unlocked: Boolean,
    disabled: Boolean,
    visible: Boolean,
    unlockedForMs: Long,
    disabledForMs: Long,
    homeStarted: Boolean,
    callLive: Boolean,
): CoverStep = when {
    !unlocked -> CoverStep.STAY
    disabled && (!visible || disabledForMs >= COVER_TAKEOVER_GRACE_MS) -> CoverStep.FINISH
    disabled && !homeStarted && !callLive && disabledForMs >= COVER_HOME_NUDGE_MS -> CoverStep.START_HOME
    disabled -> CoverStep.STAY
    visible && !callLive && unlockedForMs >= COVER_LAUNCHER_WAIT_MS -> CoverStep.HAND_OVER
    else -> CoverStep.STAY
}

/** The cover's own hand-over starts Home once its disable reads back, or anyway after [COVER_DISABLE_TRIES] failed
 * tries (F3C-7: the PPA may still pick HomeActivity; "never stranded" mustn't hang on the read-back). */
fun coverHandOverStartsHome(disabledReadBack: Boolean, failedTries: Int): Boolean =
    disabledReadBack || failedTries >= COVER_DISABLE_TRIES

/**
 * f3-run B2: the cover is destroyed - its own finish, or the system's (AMS finishes a disabled component's activities
 * on `PACKAGE_CHANGED`). Unlocked, it always disables itself (idempotent; the one HOME PPA of ours that may resolve
 * once unlocked is HomeActivity's). In android mode, destroyed by the system while still in front with no Home of
 * its own started and no call, it starts the typed HOME on its way out, so the stock launcher a non-graceful boot left
 * below never comes up (a death between our take-over and Home's start).
 */
fun coverDestroyStartsHome(
    waitForLauncher: Boolean,
    unlocked: Boolean,
    ownFinish: Boolean,
    visible: Boolean,
    homeStarted: Boolean,
    callLive: Boolean,
): Boolean = waitForLauncher && unlocked && !ownFinish && visible && !homeStarted && !callLive

/** How often the cover looks at [coverWaitStep] while in front. */
const val COVER_WAIT_POLL_MS = 250L
