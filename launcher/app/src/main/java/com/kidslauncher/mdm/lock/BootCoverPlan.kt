package com.kidslauncher.mdm.lock

/*
 * The boot cover (design 16 option D = step 16b, QA #7-#11 and the decisions: D+A+B behind the
 * server switch `boot_cover`, off by default). Before the first unlock only direct-boot-aware HOME
 * activities resolve, so a stock launcher like Pixel's was Home for the seconds until our Home
 * could run. The cover is our own direct-boot-aware HOME (`BootCoverActivity`, own process
 * `:bootcover`, the breathing Vibb mark) with its own persistent preferred activity. In own mode it is
 * enabled only from shutdown to the next unlock; in android mode (design 59d step M) it stays enabled,
 * so a boot the shutdown didn't arm still has a direct-boot-aware HOME, and the unlocked HOME is kept on
 * HomeActivity by `HomePin` (two HOME PPAs of ours resolve in a per-boot order - QA #8).
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

    /** Unlocked: our main process takes over (own mode: Home's turn, the cover goes off; android mode: it stays on). */
    HANDED_OVER,

    /** The cover's [COVER_MAX_CRASHES]th crash in this boot. */
    CRASH_GUARD,
}

/**
 * The cover component's state after [event]: `true` enable, `false` disable, `null` leave it.
 * Off (switch, unmanaged, not device owner) = disabled at every event, and the crash guard always disables it.
 * - Own mode: enabled only at shutdown - never while the phone runs unlocked (an apply leaves it), and disabled at
 *   the hand-over. A crash reboot sends no shutdown: the cover stays off and the boot falls back to A+B (Home at boot,
 *   Home roots lock task) - with no Android PIN the boot isn't BFU.
 * - Android mode ([androidMode], the `boot_cover_android` marker - design 59d step M): enabled at every event while
 *   the guard holds, the take-over included. Before the first unlock only direct-boot-aware HOMEs resolve, and on a
 *   phone whose stock launcher isn't one (with Settings' FallbackHome hidden) a boot without a HOME never completes
 *   (six-inch F1), so the cover must already be enabled when a crash, `adb reboot` or a flat battery comes. `HomePin`
 *   keeps the unlocked HOME on HomeActivity.
 */
fun bootCoverEnabled(event: CoverEvent, wanted: Boolean, guardTripped: Boolean = false, androidMode: Boolean = false): Boolean? = when {
    !wanted -> false
    event == CoverEvent.CRASH_GUARD -> false
    // A tripped guard holds until the switch goes off (which clears it) and on again, or an update
    // (qa-16b-code #2, F3C-3): no more crashing starts at every boot.
    androidMode -> !guardTripped
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
    /** The versionName with [trippedVersion] (`null` = unknown, a trip from an older build): every RC of one version
     * shares a code, so an RC that fixes a cover crash of the RC before it has the same code (qa-rc-to-rc-code R2R-6). */
    val trippedVersionName: String? = null,
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
fun coverCrashed(record: CoverRecord?, bootNow: Int, versionCode: Long? = null, versionName: String? = null): CoverRecord {
    val crashes = if (record != null && record.bootCount == bootNow) record.crashes + 1 else 1
    val wasTripped = record?.tripped == true
    val tripped = wasTripped || crashes >= COVER_MAX_CRASHES
    val trips = tripped && !wasTripped
    return (record ?: CoverRecord()).copy(
        bootCount = bootNow,
        crashes = crashes,
        tripped = tripped,
        // F3C-3: the build that tripped it (code and name - R2R-6); a later build gets one more try.
        trippedVersion = if (trips) versionCode else record?.trippedVersion,
        trippedVersionName = if (trips) versionName else record?.trippedVersionName,
    )
}

/** The cover must not run again (until the switch is turned off and on, or the launcher is updated - F3C-3). */
fun coverGuardTripped(record: CoverRecord?): Boolean = record?.tripped == true

/**
 * F3C-3: a tripped guard is cleared (one more try) when the launcher was updated since the trip ([versionNow] differs
 * from [CoverRecord.trippedVersion], an unknown one included - or, R2R-6, the same code under another name: an RC over
 * an RC of one version, when both names are known), or on the server switch's off -> on edge ([switchWas] ->
 * [switchNow]) - in every mode, so android mode (which keeps the cover wanted) isn't stuck with it.
 */
fun coverGuardClearDue(
    record: CoverRecord?,
    versionNow: Long,
    switchWas: Boolean,
    switchNow: Boolean,
    versionNameNow: String? = null,
): Boolean {
    if (!coverGuardTripped(record)) return false
    val trippedName = record?.trippedVersionName
    val renamed = trippedName != null && versionNameNow != null && trippedName != versionNameNow
    return record?.trippedVersion != versionNow || renamed || (!switchWas && switchNow)
}

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
    record.trippedVersionName?.takeIf { '\n' !in it }?.let { append("trip_version_name=").append(it).append('\n') }
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
        trippedVersionName = fields["trip_version_name"],
    )
}

/** How long until the cover hands over (disables itself): `null` while locked (BFU), else what is
 * left of [COVER_MIN_SHOWN_MS] since its first frame ([shownForMs] < 0 = not shown yet: the
 * whole minimum). */
fun coverHandOverDelayMs(unlocked: Boolean, shownForMs: Long): Long? =
    if (!unlocked) null else if (shownForMs < 0) COVER_MIN_SHOWN_MS else (COVER_MIN_SHOWN_MS - shownForMs).coerceAtLeast(0L)

/*
 * Design 59 F3: the first unlock after a reboot with Android's lock. Before the PIN our main process can't
 * run (CE locked), so the cover (in android mode the BFU face over the keyguard while the pad gate holds - 59d step F,
 * [bootPadGate] - else behind it) is what the PIN's dismissal
 * reveals, and it stays until our main process has taken over - right before the boot's typed Home start
 * (`BootCover.init`/`takeOver`, then `BootHome`) it writes the device-protected take-over stamp (59d step M; the
 * component stays enabled) - and our Home is over it. The main process writes
 * the device-protected marker `boot_cover_android` while this holds ([bootCoverAndroidMarker]); the cover and the
 * locked boot read it (no CE before the PIN).
 */

/** The marker's value at an apply: the cover is wanted because of, or together with, Android's PIN as the gate
 * ([androidGate]: the face, or a credential we own on a secure phone - the rollback's escrow included, F3C-4). */
fun bootCoverAndroidMarker(wanted: Boolean, androidGate: Boolean): Boolean = wanted && androidGate

/** After the unlock the cover waits this long for our main process to take over, then hands over by itself
 * and starts our Home (never stranded; the debug build's first CE start took 12.6-31 s in the emulator runs). */
const val COVER_LAUNCHER_WAIT_MS = 30_000L

/** Taken over (our main process's stamp, step M) but still in front this long: it finishes anyway (the boot's
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
    /** Nothing: not android mode, unlocked, guard tripped, or our process took over. */
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
 * - NONE once unlocked or after our process's take-over ([tookOver], F3C-6), or with a tripped guard.
 * - RETRY while a call is live (F3C-1: a HOME start would move a BFU call screen behind the keyguard). The retry runs
 *   until the call ends, the unlock or the take-over - no cap: the unlock bounds it, and the outcome while it waits is
 *   the old stock launcher (qa-f3-round2-code F3R-6).
 * - START otherwise - a boot the shutdown armed too (qa-f3-round2-code F3R-2): what HOME resolves to now (logged)
 *   needn't be what the boot started (f3-run B2's chooser came while the resolution read the cover), and a typed start
 *   to the cover's singleTask brings it over whatever is there; already in front, it is a new intent. The screen state
 *   isn't read (B1's `isInteractive` race; logged with the start, F3R-4): `LOCKED_BOOT_COMPLETED` usually comes 1-5 s
 *   after the boot completes on the emulator - no bound: one came 15 s after our Home start (f3-run o2). A keyguard
 *   emergency dialpad open at that moment goes behind the keyguard (a placed emergency call is a live call: RETRY) -
 *   no public API tells an occluding activity apart; the kid opens it again.
 */
fun coverAtLockedBoot(
    androidMarker: Boolean,
    unlocked: Boolean,
    guardTripped: Boolean,
    tookOver: Boolean,
    callLive: Boolean,
): LockedBootCover = when {
    !androidMarker || unlocked || guardTripped || tookOver -> LockedBootCover.NONE
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
 * it stops. [takenOver]: our main process took over in this boot (design 59d step M: its device-protected stamp,
 * [coverTakenOver] - the component stays enabled in android mode) or the cover reads disabled (its own
 * [CoverStep.HAND_OVER]); [takenOverForMs]: since the cover first saw it; [visible]: not stopped (stopped while taken
 * over = our Home or a call is over it - the finish then reveals nothing); [homeStarted]: it started our Home itself
 * already; [callLive]: a call (Telecom's view, the cover's process can't see ours); [lateStart]: this cover was created
 * after the take-over (the system resolved HOME to it once unlocked - step M: it starts Home at once, not after the
 * nudge). Never leaves before the unlock, never leaves on a stop before the take-over (the keyguard coming back, a
 * call), never starts Home while stopped.
 *
 * Taken over and still in front, it starts our Home after [COVER_HOME_NUDGE_MS] **in a call too** (qa-f3-round2-code
 * F3R-1): this step runs only while the cover is resumed, so no call screen is over it, and its Home start can't bury
 * one - while leaving with no Home started would reveal the stock launcher a non-graceful boot left below, kiosk off,
 * for the whole call. So the grace ([COVER_TAKEOVER_GRACE_MS]) finishes it only once it started Home. Its own
 * [CoverStep.HAND_OVER] (no take-over) needs it in front and still no call (f3-run B5, as before): there the call's
 * end or the take-over moves it on. Never stays past [COVER_LAUNCHER_WAIT_MS] + [COVER_TAKEOVER_GRACE_MS] in front
 * after the unlock once no call is live.
 */
fun coverWaitStep(
    unlocked: Boolean,
    takenOver: Boolean,
    visible: Boolean,
    unlockedForMs: Long,
    takenOverForMs: Long,
    homeStarted: Boolean,
    callLive: Boolean,
    lateStart: Boolean = false,
): CoverStep = when {
    !unlocked -> CoverStep.STAY
    takenOver && !visible -> CoverStep.FINISH
    takenOver && !homeStarted && (lateStart || takenOverForMs >= COVER_HOME_NUDGE_MS) -> CoverStep.START_HOME
    takenOver && takenOverForMs >= COVER_TAKEOVER_GRACE_MS -> CoverStep.FINISH
    takenOver -> CoverStep.STAY
    visible && !callLive && unlockedForMs >= COVER_LAUNCHER_WAIT_MS -> CoverStep.HAND_OVER
    else -> CoverStep.STAY
}

/** Design 59d step M: the main process's take-over stamp ([stampBoot], the boot count it was written in; `null` = none)
 * is this boot's ([bootNow]). An unreadable boot count (-1) is never a take-over - the cover then waits for its 30 s
 * hand-over, which disables it and starts Home (never stranded). */
fun coverTakenOver(stampBoot: Int?, bootNow: Int): Boolean = bootNow >= 0 && stampBoot == bootNow

/** HomeActivity's class name, for the cover's process (which never loads our Home's class) - pinned to the class in
 * BootCoverPlanTest. */
const val HOME_ACTIVITY_CLASS = "com.kidslauncher.mdm.ui.HomeActivity"

/**
 * Design 59d step M: one of the cover's own Home starts ([CoverStep.START_HOME], the start on its way out). The typed
 * HOME restricted to our package resolves to the first PPA of ours in the boot's order; with the cover enabled (android
 * mode) that can be the cover itself. So it disables itself first unless the HOME resolves to HomeActivity now
 * ([resolvesToHome]; `null` = unreadable: disable). The next apply or take-over enables it again.
 */
fun coverHomeStartDisablesFirst(resolvesToHome: Boolean?): Boolean = resolvesToHome != true

/** f3-run B2 as amended by step M: once unlocked, a destroyed own-mode cover disables itself (its component is only
 * armed from shutdown to the unlock); an android-mode cover ([waitForLauncher]) stays enabled for the next unarmed boot. */
fun coverDisablesOnDestroy(waitForLauncher: Boolean, unlocked: Boolean): Boolean = unlocked && !waitForLauncher

/** Re-seats of our PPAs per main process ([homePinReseatDue]): a HOME that still doesn't resolve to HomeActivity after
 * these (something else holds it) isn't fought at every apply. */
const val HOME_PIN_MAX_RESEATS = 3

/**
 * Design 59d step M (`HomePin`): our PPAs are cleared and added again in order - HomeActivity's HOME first, the cover's
 * last - when the system's HOME, unlocked, doesn't resolve to HomeActivity ([resolvesToHome]: the per-boot PPA order
 * put the cover first, or a PPA is missing - six-inch F6), at most [HOME_PIN_MAX_RESEATS] times per process
 * ([reseats]). Never before the unlock: HomeActivity isn't direct-boot aware, so it can't resolve then.
 */
fun homePinReseatDue(unlocked: Boolean, resolvesToHome: Boolean, reseats: Int): Boolean =
    unlocked && !resolvesToHome && reseats < HOME_PIN_MAX_RESEATS

/** The cover's own hand-over starts Home once its disable reads back, or anyway after [COVER_DISABLE_TRIES] failed
 * tries (F3C-7: the PPA may still pick HomeActivity; "never stranded" mustn't hang on the read-back). */
fun coverHandOverStartsHome(disabledReadBack: Boolean, failedTries: Int): Boolean =
    disabledReadBack || failedTries >= COVER_DISABLE_TRIES

/**
 * f3-run B2: the cover is destroyed - its own finish, or the system's (AMS finishes a disabled component's activities
 * on `PACKAGE_CHANGED`). Unlocked, an own-mode cover disables itself ([coverDisablesOnDestroy]). In android mode,
 * destroyed by the system while still in front ([visible]: it was
 * resumed when the finish came, so no call screen was over it - F3R-1, in a call too) with no Home of its own started,
 * it starts the typed HOME on its way out, so the stock launcher a non-graceful boot left below never comes up (a death
 * between our take-over and Home's start).
 */
fun coverDestroyStartsHome(
    waitForLauncher: Boolean,
    unlocked: Boolean,
    ownFinish: Boolean,
    visible: Boolean,
    homeStarted: Boolean,
): Boolean = waitForLauncher && unlocked && !ownFinish && visible && !homeStarted

/** How often the cover looks at [coverWaitStep] while in front. */
const val COVER_WAIT_POLL_MS = 250L

/*
 * Design 59d step F (§5 as amended by §8.3): the cover as the BFU face. While the pad gate holds ([bootPadGate]) the
 * cover is showWhenLocked (runtime only - the manifest has no flag, BootCoverManifestTest), so it occludes the BFU
 * keyguard, and it only *asks* for Android's PIN pad (`requestDismissKeyguard`: the BFU bouncer) - it dismisses
 * nothing itself. The PIN unlocks CE, the cover drops the flag and runs F3's wait ([coverWaitStep]). The ask rule is
 * [bootPadStep]. If a runtime-flagged HOME doesn't occlude the BFU keyguard on a device (59d §8.4 item 6 (a)), the
 * cover is never resumed while locked: no ask, F3's behaviour.
 */

/** Automatic asks per `:bootcover` process (in memory; a new process may ask this many again - the cover's crash
 * guard bounds a loop). After them only taps ask. */
const val BOOT_PAD_AUTO_ASKS = 3

/** An ask answered with an error is asked again once, this long later - the same ask (the pair counts once). */
const val BOOT_PAD_RETRY_MS = 1_000L

/**
 * The pad gate (Q59d-8): showWhenLocked and every ask only with Android's PIN as the gate ([androidMarker]: the
 * `boot_cover_android` marker) and the user still locked; read again before every ask and at every resume. An
 * own-mode cover (16b's `boot_cover` switch, an Android credential of its own) never gets it, and once [unlocked] it
 * is false for good - never showWhenLocked once `isUserUnlocked`.
 */
fun bootPadGate(androidMarker: Boolean, unlocked: Boolean): Boolean = androidMarker && !unlocked

/** What the cover's pad rule hears: its lifecycle, a tap, the ask's answer, the retry's time. */
enum class PadEvent { RESUMED, STOPPED, TAPPED, SUCCEEDED, CANCELLED, ERROR, RETRY }

enum class PadAction {
    NONE,

    /** `requestDismissKeyguard` now: the BFU bouncer over the cover. */
    ASK,

    /** Post [PadEvent.RETRY] [BOOT_PAD_RETRY_MS] from now. */
    RETRY_LATER,
}

/**
 * The cover's pad state. [autoAsks]: automatic asks counted in this process (the activity keeps it process-wide);
 * [armed]: an automatic ask may come at the next resume (a new cover, and after every stop); [open]: an ask is out
 * with no answer; [auto]: that ask is automatic (counted); [retried]: it used its one retry; [retryDue]: the retry is
 * posted; [line]: "Trykk for å låse opp" shows under the mark (an ask ended without the PIN).
 */
data class BootPad(
    val autoAsks: Int = 0,
    val armed: Boolean = true,
    val open: Boolean = false,
    val auto: Boolean = false,
    val retried: Boolean = false,
    val retryDue: Boolean = false,
    val line: Boolean = false,
)

data class PadStep(val pad: BootPad, val action: PadAction)

/**
 * The ask rule (§5 rules 2-4, §8.3, §8.5 "F"), pure:
 * - Nothing without the gate ([bootPadGate]): no ask, no retry.
 * - **Resumed**: one automatic ask per resume after a stop (the arm), with the screen on ([interactive]), no ask open
 *   and fewer than [BOOT_PAD_AUTO_ASKS] counted - **never while [inCall]** (Telecom's `isInManagedCall`; the pad over a
 *   live call would hide its screen). A resume held back by a call keeps the arm, so the first resume after the call
 *   ended asks. No new automatic ask until a stop: a kid who keeps cancelling never gets a loop. A resume that doesn't
 *   ask, with no ask open, shows the line (qa-59d-f-code F59d-2: a call that ends with the cover in front, the cap, a
 *   new cover instance after it) - the face is bare only while its first ask is coming or an ask is out.
 * - **A tap** asks whatever the call or the cap say (the kid chose it), uncounted. A tap reaching the cover means no
 *   pad is over it, so an ask still marked open is given up (the activity drops its late answer by the ask's id).
 * - **Cancelled**: the line shows. A cancel with the screen off (power with the pad up, §5 rule 4: the bouncer goes at
 *   the sleep) isn't counted - the stop re-arms and the next screen-on's resume asks.
 * - **Error**: asked again once [BOOT_PAD_RETRY_MS] later (the keyguard not ready right after the locked boot), the
 *   same ask: the pair counts once. The retry needs the screen on and, for an automatic ask, no call; a second error,
 *   or a retry that can't go, ends the ask with the line.
 * - **Stopped**: arms the next resume; a retry still due is dropped; an ask still open is closed, uncounted when the
 *   screen is off (the power press) - its late answer is then no current ask's - and the line shows (F59d-2: its late
 *   cancel can't show it any more).
 * - **Succeeded**: the ask closes and the line goes (F59d-3); CE's unlock does the rest (the gate goes false, F3's
 *   wait).
 * An answer with no ask open (an earlier ask's) changes nothing.
 */
fun bootPadStep(pad: BootPad, event: PadEvent, gate: Boolean, interactive: Boolean, inCall: Boolean): PadStep {
    if (!gate) return PadStep(pad.copy(open = false, retryDue = false), PadAction.NONE)
    val none = PadStep(pad, PadAction.NONE)
    // The open ask closes now: an automatic one with the screen off gives its count back.
    val closedCount = if (pad.open && pad.auto && !interactive) (pad.autoAsks - 1).coerceAtLeast(0) else pad.autoAsks
    return when (event) {
        PadEvent.RESUMED ->
            if (pad.armed && !pad.open && interactive && !inCall && pad.autoAsks < BOOT_PAD_AUTO_ASKS) {
                PadStep(
                    pad.copy(autoAsks = pad.autoAsks + 1, armed = false, open = true, auto = true, retried = false, retryDue = false),
                    PadAction.ASK,
                )
            } else {
                // F59d-2: a resume that doesn't ask (a call, the cap, the screen off, no stop since the last ask) shows
                // the hint, so the face is never bare with no pad coming - unless an ask is out.
                if (pad.open) none else PadStep(pad.copy(line = true), PadAction.NONE)
            }
        PadEvent.TAPPED -> PadStep(pad.copy(open = true, auto = false, retried = false, retryDue = false), PadAction.ASK)
        PadEvent.STOPPED ->
            PadStep(pad.copy(autoAsks = closedCount, armed = true, open = false, retryDue = false, line = pad.line || pad.open), PadAction.NONE)
        PadEvent.SUCCEEDED -> if (!pad.open) none else PadStep(pad.copy(open = false, line = false), PadAction.NONE)
        PadEvent.CANCELLED -> if (!pad.open) none else PadStep(pad.copy(autoAsks = closedCount, open = false, line = true), PadAction.NONE)
        PadEvent.ERROR -> when {
            !pad.open -> none
            !pad.retried -> PadStep(pad.copy(open = false, retried = true, retryDue = true), PadAction.RETRY_LATER)
            else -> PadStep(pad.copy(open = false, line = true), PadAction.NONE)
        }
        PadEvent.RETRY -> when {
            !pad.retryDue || pad.open -> none
            interactive && !(pad.auto && inCall) -> PadStep(pad.copy(open = true, retryDue = false), PadAction.ASK)
            else -> PadStep(pad.copy(retryDue = false, line = true), PadAction.NONE)
        }
    }
}

/**
 * F59d-6: the hint line's top in the cover, px: [offsetPx] below the screen's centre (under the 288 dp mark), but never
 * lower than [gapPx] above the bottom inset ([insetBottomPx], the navigation bar - the window is edge-to-edge), so a
 * line wrapped at a large font scale stays readable. Never above the top.
 */
fun tapLineTopPx(heightPx: Int, lineHeightPx: Int, insetBottomPx: Int, offsetPx: Int, gapPx: Int): Int =
    minOf(heightPx / 2 + offsetPx, heightPx - insetBottomPx - gapPx - lineHeightPx).coerceAtLeast(0)
