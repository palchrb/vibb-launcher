package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable
import java.time.LocalTime
import java.time.ZonedDateTime

/*
 * The launcher's own update (handy step 11, design 11-kiosk-escapes.md §2(a) with
 * qa-11-design.md #8/#10 and the binding decisions): the APK is downloaded as soon as the server
 * advertises it and kept as a pending update (not re-fetched every sync), then committed only in
 * the update window - at night 02:00-05:00 with the screen off for 30 s and no call, or, once it
 * has waited more than 24 h, at any such screen-off. Pure, no Android imports - SelfUpdatePlanTest.
 * Product defaults proposed in the design, pending the user's confirmation.
 */

/** The downloaded launcher APK waiting for the update window - `TrackedAppState.pending`. */
@Serializable
data class PendingSelfUpdate(
    val releaseTag: String,
    /** File name in `noBackupFilesDir/self_update` (not the OS-reclaimable cache). */
    val fileName: String,
    val sizeBytes: Long,
    /** SHA-256 (lower-case hex) of the file as downloaded - checked again right before fencing. */
    val sha256: String,
    /** Wall clock when the download finished - the 24 h rule counts from here. */
    val downloadedAtMs: Long,
    /** The catalog name, for the install notification. */
    val name: String = "",
)

/** What a sync does about the launcher's own row in `GET /api/devices/apps`. */
enum class LauncherUpdateStep {
    NOTHING,
    /** Download the advertised release and keep it pending. */
    DOWNLOAD,
    /** The pending APK is the advertised release - wait for the window. */
    KEEP_PENDING,
    /** The pending APK is stale (a newer tag, or its file is gone): delete it, then download. */
    REPLACE_PENDING,
    /** The release is installed already, or the server withdrew it: delete the pending APK. */
    DROP_PENDING,
    /** The running build already is this release or newer (a QR/adb install the launcher has no
     * record of): record it installed, delete any pending APK, download nothing. */
    ALREADY_RUNNING,
}

private val LAUNCHER_TAG = Regex("""^launcher-v(\d{1,4})\.(\d{1,3})\.(\d{1,3})(-rc\.\d+)?$""")

/**
 * The versionCode the release workflow builds for [releaseTag] (the part before `@` of the
 * server's `release_tag`): `launcher-vX.Y.Z` -> `X*1_000_000 + Y*1_000 + Z`, `launcher-vX.Y.Z-rc.N`
 * -> that minus 1 (.github/workflows/launcher.yml); null for any other tag.
 */
fun launcherTagVersionCode(releaseTag: String): Long? {
    val m = LAUNCHER_TAG.matchEntire(releaseTag.substringBefore('@')) ?: return null
    val (x, y, z) = m.destructured
    val code = x.toLong() * 1_000_000 + y.toLong() * 1_000 + z.toLong() - if (m.groupValues[4].isEmpty()) 0 else 1
    return code.takeIf { it >= 1 }
}

/**
 * The launcher's own update at sync time:
 * - [advertisedTag] `null` = the server's list (which arrived) no longer has the launcher: a
 *   pending APK is dropped (withdrawn release);
 * - the advertised release is installed, or was refused for good ([refusedTag]): nothing (a
 *   pending leftover is dropped);
 * - the advertised tag's versionCode ([launcherTagVersionCode]) is not above the running build's
 *   ([runningVersionCode]): [LauncherUpdateStep.ALREADY_RUNNING] - a tag that doesn't parse goes on;
 * - a download or commit in flight ([attemptInFlight]): nothing;
 * - the advertised release failed less than an hour ago: nothing (the backoff), a pending APK of
 *   another release is dropped;
 * - a pending APK of the advertised release whose file is intact ([pendingFileOk]): keep waiting;
 *   of another release, or with its file gone: replace it;
 * - otherwise download.
 */
fun launcherUpdateStep(
    advertisedTag: String?,
    lastInstalledTag: String?,
    lastFailedTag: String?,
    lastFailedAtMs: Long?,
    attemptInFlight: Boolean,
    pending: PendingSelfUpdate?,
    pendingFileOk: Boolean,
    nowMs: Long,
    failedBackoffMs: Long,
    refusedTag: String? = null,
    runningVersionCode: Long? = null,
): LauncherUpdateStep {
    // Installed, withdrawn, or refused for good (a deterministic failure - wrong signer, older,
    // not ours: qa-11-code #4) until the server advertises another release.
    if (advertisedTag == null || advertisedTag == lastInstalledTag || advertisedTag == refusedTag) {
        return if (pending != null) LauncherUpdateStep.DROP_PENDING else LauncherUpdateStep.NOTHING
    }
    val tagCode = launcherTagVersionCode(advertisedTag)
    if (tagCode != null && runningVersionCode != null && tagCode <= runningVersionCode) return LauncherUpdateStep.ALREADY_RUNNING
    if (attemptInFlight) return LauncherUpdateStep.NOTHING
    val backingOff = advertisedTag == lastFailedTag && lastFailedAtMs != null && nowMs - lastFailedAtMs < failedBackoffMs
    if (backingOff) {
        return if (pending != null && pending.releaseTag != advertisedTag) LauncherUpdateStep.DROP_PENDING else LauncherUpdateStep.NOTHING
    }
    return when {
        pending == null -> LauncherUpdateStep.DOWNLOAD
        pending.releaseTag == advertisedTag && pendingFileOk -> LauncherUpdateStep.KEEP_PENDING
        else -> LauncherUpdateStep.REPLACE_PENDING
    }
}

/** The pending APK right before fencing and committing (qa-11-design.md #10). */
enum class PendingApkCheck {
    OK,
    /** The file is gone (download again). */
    MISSING,
    /** Size or hash differ from the download (download again). */
    CORRUPT,
    /** The APK isn't our package - never install it as our update (recorded as failed). */
    NOT_OURS,
    /** Older than the running build - Android would refuse it (recorded as failed). */
    DOWNGRADE,
    /** The running build already is this version - nothing to install (recorded as installed). */
    SAME_VERSION,
    /** PackageManager couldn't parse it (recorded as failed). */
    UNPARSEABLE,
    /** Signed by another key than ours - Android would refuse the update (qa-11-code #4). */
    WRONG_SIGNER,
}

/** The refusals that would refuse the same APK again: never retried until the tag changes. */
val PendingApkCheck.deterministic: Boolean
    get() = this == PendingApkCheck.NOT_OURS || this == PendingApkCheck.DOWNGRADE ||
        this == PendingApkCheck.UNPARSEABLE || this == PendingApkCheck.WRONG_SIGNER

/**
 * [ourSigners]/[archiveSigners]: SHA-256 hex of every certificate in our and the APK's signing
 * lineage (`SigningInfo`: the signers, or the certificate history). They must share one -
 * the debug key against the release APK, or a stranger's key, never do; a rotation (the new
 * lineage contains our certificate) does. Empty [ourSigners] = couldn't read ours: not checked.
 */
fun signerMatches(ourSigners: Set<String>, archiveSigners: Set<String>?): Boolean =
    ourSigners.isEmpty() || (archiveSigners != null && ourSigners.any { it in archiveSigners })

fun pendingApkCheck(
    pending: PendingSelfUpdate,
    fileSize: Long?,
    fileSha256: String?,
    archivePackage: String?,
    archiveVersionCode: Long?,
    ownPackage: String,
    ownVersionCode: Long,
    ourSigners: Set<String> = emptySet(),
    archiveSigners: Set<String>? = null,
): PendingApkCheck = when {
    fileSize == null -> PendingApkCheck.MISSING
    fileSize != pending.sizeBytes || !fileSha256.equals(pending.sha256, ignoreCase = true) -> PendingApkCheck.CORRUPT
    archivePackage == null || archiveVersionCode == null -> PendingApkCheck.UNPARSEABLE
    archivePackage != ownPackage -> PendingApkCheck.NOT_OURS
    !signerMatches(ourSigners, archiveSigners) -> PendingApkCheck.WRONG_SIGNER
    archiveVersionCode < ownVersionCode -> PendingApkCheck.DOWNGRADE
    archiveVersionCode == ownVersionCode -> PendingApkCheck.SAME_VERSION
    else -> PendingApkCheck.OK
}

/** What a failed result of our own update's session means (qa-11-code #4). */
enum class SelfUpdateFailure {
    /** Transient (storage, timeout, aborted, generic): keep the verified APK, try again after the
     * backoff in a later window - no new download. */
    RETRY_KEEP_APK,
    /** The same APK would fail again (invalid, conflicting, incompatible - a signature or version
     * clash -, blocked, or user action needed on a device-owner install): drop it, never retry
     * this release. */
    REFUSE_RELEASE,
}

/** `PackageInstaller.STATUS_*` (public constants, literal here to stay Android-free). */
const val INSTALL_STATUS_PENDING_USER_ACTION = -1
const val INSTALL_STATUS_FAILURE_BLOCKED = 2
const val INSTALL_STATUS_FAILURE_INVALID = 4
const val INSTALL_STATUS_FAILURE_CONFLICT = 5
const val INSTALL_STATUS_FAILURE_INCOMPATIBLE = 7

fun selfUpdateFailure(status: Int): SelfUpdateFailure = when (status) {
    INSTALL_STATUS_PENDING_USER_ACTION, INSTALL_STATUS_FAILURE_BLOCKED, INSTALL_STATUS_FAILURE_INVALID,
    INSTALL_STATUS_FAILURE_CONFLICT, INSTALL_STATUS_FAILURE_INCOMPATIBLE,
    -> SelfUpdateFailure.REFUSE_RELEASE
    else -> SelfUpdateFailure.RETRY_KEEP_APK
}

/**
 * The commit step for a pending APK that passed every check: wait out the backoff after a
 * transient failure of the same release (the APK is kept), and never commit a refused one.
 */
fun pendingCommitAllowed(
    pendingTag: String,
    lastFailedTag: String?,
    lastFailedAtMs: Long?,
    refusedTag: String?,
    nowMs: Long,
    failedBackoffMs: Long,
): Boolean {
    if (pendingTag == refusedTag) return false
    if (pendingTag == lastFailedTag && lastFailedAtMs != null && nowMs - lastFailedAtMs in 0 until failedBackoffMs) return false
    return true
}

// ---- the update window ------------------------------------------------------------------------

/** Night window start (local time), inclusive. */
val UPDATE_WINDOW_START: LocalTime = LocalTime.of(2, 0)

/** Night window end (local time), exclusive. */
val UPDATE_WINDOW_END: LocalTime = LocalTime.of(5, 0)

/** The screen must have been off this long (nobody is holding the phone). */
const val UPDATE_SCREEN_OFF_MS = 30_000L

/** An update that has waited this long may go in at any quiet screen-off. */
const val UPDATE_OVERDUE_MS = 24 * 60 * 60_000L

enum class UpdateWaitReason(val wire: String) {
    CALL("call"),
    EMERGENCY("emergency"),
    SCREEN_ON("screen_on"),
    SCREEN_OFF_SHORT("screen_off_short"),
    OUTSIDE_WINDOW("outside_window"),
}

sealed interface UpdateWindowDecision {
    data object Commit : UpdateWindowDecision
    /** [recheckInMs]: when a check could pass with the screen still off (`null` = only after the
     * next screen-off). */
    data class Wait(val reason: UpdateWaitReason, val recheckInMs: Long?) : UpdateWindowDecision
}

data class UpdateWindowInputs(
    val now: ZonedDateTime,
    /** `null` = the screen is on. */
    val screenOffForMs: Long?,
    /** Any live call - ours (`OngoingCalls.hasLiveCall`, which counts CONNECTING) or Telecom's. */
    val liveCall: Boolean,
    /** An emergency call recently, the callback window, the lock's emergency flow, or ECBM. */
    val emergency: Boolean,
    /** Wall time since the pending APK was downloaded (negative = the clock went back). */
    val pendingForMs: Long,
    /** [UPDATE_OVERDUE_MS]; debug builds pass [DEBUG_UPDATE_OVERDUE_MS] (emulator A4). */
    val overdueMs: Long = UPDATE_OVERDUE_MS,
)

/** Debug builds only: a pending update is overdue after 2 minutes, so the emulator's A4 check
 * doesn't wait for the night (docs/testing/emulator.md). */
const val DEBUG_UPDATE_OVERDUE_MS = 2 * 60_000L

/**
 * The commit gate (qa-11-design.md #8: the freeze also kills our screening, redirection and
 * in-call services, so a call arriving in the ~1 min window rings unscreened in the system UI):
 * never during a call or an emergency flow; only with the screen off for [UPDATE_SCREEN_OFF_MS];
 * only inside the night window, unless the update has waited [UPDATE_OVERDUE_MS].
 */
fun updateWindowDecision(i: UpdateWindowInputs): UpdateWindowDecision {
    if (i.liveCall) return UpdateWindowDecision.Wait(UpdateWaitReason.CALL, null)
    if (i.emergency) return UpdateWindowDecision.Wait(UpdateWaitReason.EMERGENCY, EMERGENCY_RECHECK_MS)
    val off = i.screenOffForMs ?: return UpdateWindowDecision.Wait(UpdateWaitReason.SCREEN_ON, null)
    val overdue = i.pendingForMs >= i.overdueMs
    if (!overdue && !inUpdateWindow(i.now.toLocalTime())) {
        val untilWindow = msUntilUpdateWindow(i.now)
        val untilOverdue = if (i.pendingForMs >= 0) i.overdueMs - i.pendingForMs else Long.MAX_VALUE
        return UpdateWindowDecision.Wait(UpdateWaitReason.OUTSIDE_WINDOW, minOf(untilWindow, untilOverdue))
    }
    if (off < UPDATE_SCREEN_OFF_MS) return UpdateWindowDecision.Wait(UpdateWaitReason.SCREEN_OFF_SHORT, UPDATE_SCREEN_OFF_MS - off)
    return UpdateWindowDecision.Commit
}

/** A quiet phone after an emergency is checked again this often. */
const val EMERGENCY_RECHECK_MS = 10 * 60_000L

fun inUpdateWindow(time: LocalTime): Boolean = !time.isBefore(UPDATE_WINDOW_START) && time.isBefore(UPDATE_WINDOW_END)

/** Milliseconds until the next window start (0 inside the window), DST-aware. */
fun msUntilUpdateWindow(now: ZonedDateTime): Long {
    if (inUpdateWindow(now.toLocalTime())) return 0
    var start = now.with(UPDATE_WINDOW_START)
    if (!start.isAfter(now)) start = now.plusDays(1).with(UPDATE_WINDOW_START)
    return java.time.Duration.between(now, start).toMillis().coerceAtLeast(0)
}

/**
 * When to look again after a screen-off with a pending update: 30 s later when it could go in
 * then, else at the window start (or when it becomes overdue) - never sooner than 30 s.
 */
fun commitCheckDelayMs(now: ZonedDateTime, pendingForMs: Long, overdueMs: Long = UPDATE_OVERDUE_MS): Long {
    val overdue = pendingForMs >= overdueMs
    if (overdue || inUpdateWindow(now.toLocalTime())) return UPDATE_SCREEN_OFF_MS
    val untilOverdue = if (pendingForMs >= 0) overdueMs - pendingForMs else Long.MAX_VALUE
    return maxOf(UPDATE_SCREEN_OFF_MS, minOf(msUntilUpdateWindow(now), untilOverdue))
}

/**
 * After our package was replaced (MY_PACKAGE_REPLACED) or a self-update failed in a restarted
 * process: Home is brought to the front - then the PIN lock on top if LOCKED (qa-11-design.md #9) -
 * when apps are managed, the kiosk is on or the PIN lock is active (design 16, QA #6: a phone with
 * only calls managed and the lock would keep the stock launcher under it), and never over a call
 * ([liveCall]: ours or a VoIP call the lock yields to; [telecomInCall] `null` = unknown, counts as
 * a call). Nothing else brings Home back: lock task doesn't re-enter by itself.
 */
fun bringHomeAfterUpdate(
    appsManaged: Boolean,
    kioskOn: Boolean,
    liveCall: Boolean,
    telecomInCall: Boolean?,
    pinLockActive: Boolean = false,
): Boolean = (appsManaged || kioskOn || pinLockActive) && !liveCall && telecomInCall == false

/**
 * Design 16 (A, QA #6): the first process start of a boot ([bootCount] differs from the one stored
 * after the last boot start, [storedBootCount]; an unknown count, -1, never is) brings our Home to
 * the front under the same gate as an update - before the CE unlock only a direct-boot-aware HOME
 * resolves, so a stock launcher like Pixel's is Home until something of ours covers it.
 */
fun bringHomeAtBoot(
    bootCount: Int,
    storedBootCount: Int?,
    appsManaged: Boolean,
    kioskOn: Boolean,
    pinLockActive: Boolean,
    liveCall: Boolean,
    telecomInCall: Boolean?,
): Boolean = bootCount >= 0 && bootCount != storedBootCount &&
    bringHomeAfterUpdate(appsManaged, kioskOn, liveCall, telecomInCall, pinLockActive)

/**
 * How long the screen has been off (the screen is off now): since this process's last SCREEN_OFF;
 * before any screen event in this process since its start (it was off when we started); after a
 * SCREEN_ON with the SCREEN_OFF not yet delivered, 0 (qa-11-code #3 - a power-button press must
 * not read as "off for hours").
 */
fun screenOffForMs(offSinceElapsed: Long?, screenEventSeen: Boolean, processStartElapsed: Long, nowElapsed: Long): Long {
    val since = offSinceElapsed ?: if (!screenEventSeen) processStartElapsed else return 0
    return (nowElapsed - since).coerceAtLeast(0)
}

// ---- Install Now (design 37) --------------------------------------------------------------------

/** Status-report capability of a launcher that runs Install Now. */
const val INSTALL_NOW_CAPABILITY = "install_now_v1"

/** The command's kind (`pending_command.command`). */
const val INSTALL_NOW_COMMAND = "install_update"

/** A call the phone can't see the end of (VoIP, Telecom-only) is checked again this often. */
const val INSTALL_NOW_CALL_RECHECK_MS = 2 * 60_000L

/** A call that stays on this long ends the command (qa-37-design R-L4: a voice app holding the
 * audio mode for hours must not keep the override and the install waiting for good). */
const val INSTALL_NOW_CALL_LIMIT_MS = 6 * 60 * 60_000L

/** The answers (`command-result` messages; the server's `locate::reword` words them). */
const val INSTALL_NOW_NOT_OFFERED = "not offered"
const val INSTALL_NOW_REFUSED_RELEASE = "refused: release"
const val INSTALL_NOW_DOWNLOAD_FAILED = "download failed"
const val INSTALL_NOW_DOWNLOAD_CORRUPT = "download corrupt"
const val INSTALL_NOW_NO_SPACE = "no space"
const val INSTALL_NOW_SUPERSEDED = "superseded"
const val INSTALL_NOW_CALL_NEVER_ENDED = "call never ended"
const val INSTALL_NOW_INSTALL_FAILED_START = "install failed: start"

fun installNowInstalled(versionName: String) = "installed: $versionName"
fun installNowAlreadyRunning(versionName: String) = "already running: $versionName"
fun installNowInstallFailed(status: Int) = "install failed: $status"

/** A deterministic pre-commit refusal as Install Now's answer: "refused: wrong_signer", ... */
val PendingApkCheck.installNowAnswer: String
    get() = "refused: " + name.lowercase()

/**
 * The parent's Install Now while it runs (DE prefs `install_now`, `InstallNow.kt`): the command
 * ([commandId] + [requestedAt] name it for the answer), the tag the row showed, and the running
 * versionCode at the receipt - "Done" is a running build above it (no wall clock across the
 * command, design 37 §1.10). [failure] is set by every failure and answered by the next sync's
 * settle; [callWaitSinceElapsed] is when the commit began waiting for a call (elapsed realtime).
 */
@Serializable
data class InstallNowRecord(
    val commandId: Long,
    val requestedAt: String?,
    val tag: String?,
    val fromVersionCode: Long,
    val failure: String? = null,
    val callWaitSinceElapsed: Long? = null,
)

/** What the receipt of Install Now does: [answerOld] = a record of another command and its
 * answer (its own failure, else "superseded", failed - QA L5), [store] = the record to keep (the
 * same command delivered again keeps its record, failure and call wait included). */
data class InstallNowReceipt(val answerOld: Pair<InstallNowRecord, String>?, val store: InstallNowRecord)

fun installNowOnReceipt(
    old: InstallNowRecord?,
    commandId: Long,
    requestedAt: String?,
    tag: String?,
    runningVersionCode: Long,
): InstallNowReceipt {
    if (old != null && old.commandId == commandId && old.requestedAt == requestedAt) return InstallNowReceipt(null, old)
    val fresh = InstallNowRecord(commandId, requestedAt, tag, runningVersionCode)
    return InstallNowReceipt(old?.let { it to (it.failure ?: INSTALL_NOW_SUPERSEDED) }, fresh)
}

/** The commit gate for Install Now: only a live call or an emergency holds it - no night window,
 * no screen-off, whatever the screen. */
fun installNowDecision(liveCall: Boolean, emergency: Boolean): UpdateWindowDecision = when {
    liveCall -> UpdateWindowDecision.Wait(UpdateWaitReason.CALL, INSTALL_NOW_CALL_RECHECK_MS)
    emergency -> UpdateWindowDecision.Wait(UpdateWaitReason.EMERGENCY, EMERGENCY_RECHECK_MS)
    else -> UpdateWindowDecision.Commit
}

/** What the server's list says about our own row, for the settle. */
enum class LauncherRow {
    /** No list this sync (a failed fetch, tsnet not up yet). */
    NO_LIST,
    /** The list has no launcher row, or it names another package (debug vs release). */
    NOT_OFFERED,
    /** The advertised release is running already (or recorded installed). */
    RUNNING,
    /** The advertised release was refused for good on this phone. */
    REFUSED,
    /** Downloading, pending or committing. */
    ON_ITS_WAY,
}

/** [LauncherRow] from what `handleLauncherUpdate` saw: [rowOurs] = a launcher row for our
 * package, [step] = the step it took (null when it took none). */
fun launcherRow(
    rowOurs: Boolean,
    advertisedTag: String?,
    lastInstalledTag: String?,
    refusedTag: String?,
    step: LauncherUpdateStep?,
): LauncherRow = when {
    !rowOurs || advertisedTag == null -> LauncherRow.NOT_OFFERED
    step == LauncherUpdateStep.ALREADY_RUNNING || advertisedTag == lastInstalledTag -> LauncherRow.RUNNING
    advertisedTag == refusedTag -> LauncherRow.REFUSED
    else -> LauncherRow.ON_ITS_WAY
}

sealed interface InstallNowOutcome {
    data object Installed : InstallNowOutcome
    data object AlreadyRunning : InstallNowOutcome
    data class Failed(val reason: String) : InstallNowOutcome
    /** Still on its way: no answer yet. */
    data object Keep : InstallNowOutcome
}

/** The settle: a running build above the one at the receipt is "installed" (beats a failure
 * recorded before the kill); then a recorded failure; then what the list says. */
fun installNowOutcome(record: InstallNowRecord, runningVersionCode: Long, row: LauncherRow): InstallNowOutcome = when {
    runningVersionCode > record.fromVersionCode -> InstallNowOutcome.Installed
    record.failure != null -> InstallNowOutcome.Failed(record.failure)
    row == LauncherRow.NOT_OFFERED -> InstallNowOutcome.Failed(INSTALL_NOW_NOT_OFFERED)
    row == LauncherRow.REFUSED -> InstallNowOutcome.Failed(INSTALL_NOW_REFUSED_RELEASE)
    row == LauncherRow.RUNNING -> InstallNowOutcome.AlreadyRunning
    else -> InstallNowOutcome.Keep
}

/** The command-result for an outcome: (succeeded, message), `null` = no answer yet. */
fun InstallNowOutcome.answer(versionName: String): Pair<Boolean, String>? = when (this) {
    InstallNowOutcome.Installed -> true to installNowInstalled(versionName)
    InstallNowOutcome.AlreadyRunning -> true to installNowAlreadyRunning(versionName)
    is InstallNowOutcome.Failed -> false to reason
    InstallNowOutcome.Keep -> null
}

/** `update_fence.waiting_for` while Install Now runs: no APK yet -> downloading; a wait for a
 * call or an emergency; else installing. Without a record the ordinary gate's word. */
fun installNowWait(recordPresent: Boolean, pendingPresent: Boolean, lastWait: UpdateWaitReason?): String? = when {
    !recordPresent -> if (pendingPresent) lastWait?.wire else null
    !pendingPresent -> "install_now_download"
    lastWait == UpdateWaitReason.CALL -> "install_now_call"
    lastWait == UpdateWaitReason.EMERGENCY -> "install_now_emergency"
    else -> "install_now_ready"
}

/** The alarm while Install Now waits (qa-37-design R-L1: a screen-off must not replace it with
 * the night window's delay). */
fun installNowRecheckMs(lastWait: UpdateWaitReason?): Long = when (lastWait) {
    UpdateWaitReason.CALL -> INSTALL_NOW_CALL_RECHECK_MS
    UpdateWaitReason.EMERGENCY -> EMERGENCY_RECHECK_MS
    else -> UPDATE_SCREEN_OFF_MS
}

/** When the wait for a call began: kept while it waits on a call (a reboot - elapsed time behind
 * the stored start - starts it again), cleared otherwise. */
fun callWaitSince(previous: Long?, waitingOnCall: Boolean, nowElapsed: Long): Long? = when {
    !waitingOnCall -> null
    previous == null || previous > nowElapsed -> nowElapsed
    else -> previous
}

/** The call has held Install Now for [INSTALL_NOW_CALL_LIMIT_MS]. */
fun callWaitExpired(sinceElapsed: Long?, nowElapsed: Long): Boolean =
    sinceElapsed != null && nowElapsed >= sinceElapsed && nowElapsed - sinceElapsed >= INSTALL_NOW_CALL_LIMIT_MS
