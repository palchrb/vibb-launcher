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
}

/**
 * The launcher's own update at sync time:
 * - [advertisedTag] `null` = the server's list (which arrived) no longer has the launcher: a
 *   pending APK is dropped (withdrawn release);
 * - the advertised release is installed, or was refused for good ([refusedTag]): nothing (a
 *   pending leftover is dropped);
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
): LauncherUpdateStep {
    // Installed, withdrawn, or refused for good (a deterministic failure - wrong signer, older,
    // not ours: qa-11-code #4) until the server advertises another release.
    if (advertisedTag == null || advertisedTag == lastInstalledTag || advertisedTag == refusedTag) {
        return if (pending != null) LauncherUpdateStep.DROP_PENDING else LauncherUpdateStep.NOTHING
    }
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
