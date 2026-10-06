package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** Design 11 §2(a) with qa-11-design.md #8/#10: the pending APK and the update window. */
class SelfUpdatePlanTest {
    private val hour = 60 * 60_000L
    private val backoff = hour
    private val now = 1_700_000_000_000L
    private val pending = PendingSelfUpdate("launcher-v1.3.0", "launcher_1.apk", 12_345L, "ab".repeat(32), now - hour, "Launcher")

    private fun step(
        advertised: String? = "launcher-v1.3.0",
        installed: String? = "launcher-v1.2.0",
        failed: String? = null,
        failedAt: Long? = null,
        inFlight: Boolean = false,
        pending: PendingSelfUpdate? = null,
        fileOk: Boolean = true,
    ) = launcherUpdateStep(advertised, installed, failed, failedAt, inFlight, pending, fileOk, now, backoff)

    // ---- pending state machine ------------------------------------------------------------------

    @Test
    fun `a new release is downloaded once and then kept, not re-fetched every sync`() {
        assertEquals(LauncherUpdateStep.DOWNLOAD, step())
        assertEquals(LauncherUpdateStep.KEEP_PENDING, step(pending = pending))
    }

    @Test
    fun `a newer tag replaces the pending APK`() {
        assertEquals(LauncherUpdateStep.REPLACE_PENDING, step(advertised = "launcher-v1.4.0", pending = pending))
    }

    @Test
    fun `a pending APK whose file is gone is downloaded again`() {
        assertEquals(LauncherUpdateStep.REPLACE_PENDING, step(pending = pending, fileOk = false))
    }

    @Test
    fun `a withdrawn release drops the pending APK`() {
        assertEquals(LauncherUpdateStep.DROP_PENDING, step(advertised = null, pending = pending))
        assertEquals(LauncherUpdateStep.NOTHING, step(advertised = null))
    }

    @Test
    fun `an installed release needs nothing - a leftover pending APK is dropped`() {
        assertEquals(LauncherUpdateStep.NOTHING, step(installed = "launcher-v1.3.0"))
        assertEquals(LauncherUpdateStep.DROP_PENDING, step(installed = "launcher-v1.3.0", pending = pending))
    }

    @Test
    fun `nothing while a download or commit is in flight`() {
        assertEquals(LauncherUpdateStep.NOTHING, step(inFlight = true))
        assertEquals(LauncherUpdateStep.NOTHING, step(inFlight = true, advertised = "launcher-v1.4.0", pending = pending))
    }

    @Test
    fun `a release that failed less than an hour ago waits - then is retried`() {
        assertEquals(LauncherUpdateStep.NOTHING, step(failed = "launcher-v1.3.0", failedAt = now - 10 * 60_000L))
        assertEquals(LauncherUpdateStep.DROP_PENDING, step(failed = "launcher-v1.4.0", failedAt = now - 60_000L, advertised = "launcher-v1.4.0", pending = pending))
        assertEquals(LauncherUpdateStep.DOWNLOAD, step(failed = "launcher-v1.3.0", failedAt = now - backoff))
        assertEquals(LauncherUpdateStep.DOWNLOAD, step(failed = "launcher-v1.2.9", failedAt = now))
    }

    // ---- the APK right before fencing -----------------------------------------------------------

    private fun apk(
        size: Long? = pending.sizeBytes,
        sha: String? = pending.sha256.uppercase(),
        pkg: String? = "me.vibb.launcher",
        code: Long? = 1_003_000L,
    ) = pendingApkCheck(pending, size, sha, pkg, code, "me.vibb.launcher", 1_002_000L)

    @Test
    fun `the right APK passes`() {
        assertEquals(PendingApkCheck.OK, apk())
    }

    @Test
    fun `a missing, truncated or altered file is never installed`() {
        assertEquals(PendingApkCheck.MISSING, apk(size = null))
        assertEquals(PendingApkCheck.CORRUPT, apk(size = 1L))
        assertEquals(PendingApkCheck.CORRUPT, apk(sha = "00".repeat(32)))
        assertEquals(PendingApkCheck.CORRUPT, apk(sha = null))
    }

    @Test
    fun `another package is never fenced for or installed as our update`() {
        assertEquals(PendingApkCheck.NOT_OURS, apk(pkg = "me.vibb.launcher.debug"))
        assertEquals(PendingApkCheck.NOT_OURS, apk(pkg = "org.example.other"))
        assertEquals(PendingApkCheck.UNPARSEABLE, apk(pkg = null))
    }

    @Test
    fun `a downgrade is refused before fencing, the same version is taken as installed`() {
        assertEquals(PendingApkCheck.DOWNGRADE, apk(code = 1_001_000L))
        assertEquals(PendingApkCheck.SAME_VERSION, apk(code = 1_002_000L))
    }

    // ---- the window -----------------------------------------------------------------------------

    private val oslo = ZoneId.of("Europe/Oslo")
    private fun at(h: Int, m: Int = 0, day: Int = 7, month: Int = 10) = ZonedDateTime.of(2026, month, day, h, m, 0, 0, oslo)

    private fun gate(
        time: ZonedDateTime = at(3),
        screenOff: Long? = 60_000L,
        call: Boolean = false,
        emergency: Boolean = false,
        pendingFor: Long = hour,
    ) = updateWindowDecision(UpdateWindowInputs(time, screenOff, call, emergency, pendingFor))

    @Test
    fun `night, screen off 30 s, no call - commit`() {
        assertEquals(UpdateWindowDecision.Commit, gate())
        assertEquals(UpdateWindowDecision.Commit, gate(time = at(2, 0), screenOff = 30_000L))
        assertEquals(UpdateWindowDecision.Commit, gate(time = at(4, 59)))
    }

    @Test
    fun `screen on - never`() {
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.SCREEN_ON, null), gate(screenOff = null))
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.SCREEN_ON, null), gate(screenOff = null, pendingFor = 3 * UPDATE_OVERDUE_MS))
    }

    @Test
    fun `screen off for less than 30 s - look again when it would be 30 s`() {
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.SCREEN_OFF_SHORT, 20_000L), gate(screenOff = 10_000L))
    }

    @Test
    fun `a live call, also one still connecting, or an emergency flow - never`() {
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.CALL, null), gate(call = true))
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.EMERGENCY, EMERGENCY_RECHECK_MS), gate(emergency = true))
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.CALL, null), gate(call = true, pendingFor = 2 * UPDATE_OVERDUE_MS))
    }

    @Test
    fun `outside the night window it waits for 02-00 - or until it is overdue`() {
        val evening = gate(time = at(22))
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.OUTSIDE_WINDOW, 4 * hour), evening)
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.OUTSIDE_WINDOW, 21 * hour), gate(time = at(5, 0), pendingFor = 0))
        // Overdue in 2 h, window in 4 h: the earlier one.
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.OUTSIDE_WINDOW, 2 * hour), gate(time = at(22), pendingFor = UPDATE_OVERDUE_MS - 2 * hour))
    }

    @Test
    fun `waited more than 24 h - any quiet screen-off`() {
        assertEquals(UpdateWindowDecision.Commit, gate(time = at(14), pendingFor = UPDATE_OVERDUE_MS))
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.SCREEN_OFF_SHORT, 25_000L), gate(time = at(14), screenOff = 5_000L, pendingFor = UPDATE_OVERDUE_MS + 1))
    }

    @Test
    fun `debug builds count an update overdue after 2 minutes (emulator A4), release builds after 24 h`() {
        val debug = UpdateWindowInputs(at(14), 60_000L, liveCall = false, emergency = false, pendingForMs = DEBUG_UPDATE_OVERDUE_MS, overdueMs = DEBUG_UPDATE_OVERDUE_MS)
        assertEquals(UpdateWindowDecision.Commit, updateWindowDecision(debug))
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.OUTSIDE_WINDOW, 12 * hour), updateWindowDecision(debug.copy(overdueMs = UPDATE_OVERDUE_MS)))
        assertEquals(UPDATE_SCREEN_OFF_MS, commitCheckDelayMs(at(14), DEBUG_UPDATE_OVERDUE_MS - 1_000L, DEBUG_UPDATE_OVERDUE_MS))
        assertEquals(DEBUG_UPDATE_OVERDUE_MS, commitCheckDelayMs(at(14), 60_000L, DEBUG_UPDATE_OVERDUE_MS + 60_000L))
    }

    @Test
    fun `a clock set back never makes an update overdue`() {
        val decision = gate(time = at(14), pendingFor = -5 * UPDATE_OVERDUE_MS)
        assertEquals(UpdateWindowDecision.Wait(UpdateWaitReason.OUTSIDE_WINDOW, 12 * hour), decision)
    }

    @Test
    fun `the window start is found across a DST change`() {
        // 2026-03-29: Oslo springs forward at 02:00 -> 03:00; the window then starts at 03:00 CEST.
        val beforeSpring = ZonedDateTime.of(2026, 3, 29, 1, 0, 0, 0, oslo)
        assertEquals(hour, msUntilUpdateWindow(beforeSpring))
        // 2026-10-25: back from 03:00 CEST to 02:00 CET - the first 02:00 (CEST) is one hour after 01:00.
        val beforeFall = ZonedDateTime.of(2026, 10, 25, 1, 0, 0, 0, oslo)
        assertEquals(hour, msUntilUpdateWindow(beforeFall))
        assertEquals(0L, msUntilUpdateWindow(at(3)))
    }

    @Test
    fun `after a screen-off the next look is 30 s later inside the window, else at its start`() {
        assertEquals(UPDATE_SCREEN_OFF_MS, commitCheckDelayMs(at(3), hour))
        assertEquals(UPDATE_SCREEN_OFF_MS, commitCheckDelayMs(at(15), UPDATE_OVERDUE_MS))
        assertEquals(4 * hour, commitCheckDelayMs(at(22), hour))
        assertEquals(UPDATE_SCREEN_OFF_MS, commitCheckDelayMs(at(1, 59).plusSeconds(50), hour))
        assertEquals(hour, commitCheckDelayMs(at(22), UPDATE_OVERDUE_MS - hour))
    }

    @Test
    fun `Home comes back after the update only on a managed phone and never over a call (qa-11 9)`() {
        assertEquals(true, bringHomeAfterUpdate(appsManaged = true, kioskOn = true, liveCall = false, telecomInCall = false))
        assertEquals(true, bringHomeAfterUpdate(appsManaged = true, kioskOn = false, liveCall = false, telecomInCall = false))
        assertEquals(true, bringHomeAfterUpdate(appsManaged = false, kioskOn = true, liveCall = false, telecomInCall = false))
        assertEquals(false, bringHomeAfterUpdate(appsManaged = false, kioskOn = false, liveCall = false, telecomInCall = false))
        assertEquals(false, bringHomeAfterUpdate(appsManaged = true, kioskOn = true, liveCall = true, telecomInCall = false))
        assertEquals(false, bringHomeAfterUpdate(appsManaged = true, kioskOn = true, liveCall = false, telecomInCall = true))
        assertEquals(false, bringHomeAfterUpdate(appsManaged = true, kioskOn = true, liveCall = false, telecomInCall = null))
        // Design 16 QA #6: calls managed + the PIN lock only (no allowlist, kiosk off) counts too.
        assertEquals(true, bringHomeAfterUpdate(appsManaged = false, kioskOn = false, liveCall = false, telecomInCall = false, pinLockActive = true))
        assertEquals(false, bringHomeAfterUpdate(appsManaged = false, kioskOn = false, liveCall = true, telecomInCall = false, pinLockActive = true))
    }

    @Test
    fun `Home at boot - only the first start of a boot, under the update's gate (design 16)`() {
        fun boot(count: Int, stored: Int?, managed: Boolean = true, lock: Boolean = false, call: Boolean = false, telecom: Boolean? = false) =
            bringHomeAtBoot(count, stored, appsManaged = managed, kioskOn = false, pinLockActive = lock, liveCall = call, telecomInCall = telecom)
        assertEquals(true, boot(12, 11))
        assertEquals("first boot after install", true, boot(12, null))
        assertEquals("a crash restart in the same boot", false, boot(12, 12))
        assertEquals("unknown boot count", false, boot(-1, 11))
        assertEquals(false, boot(12, 11, managed = false))
        assertEquals("PIN lock only", true, boot(12, 11, managed = false, lock = true))
        assertEquals("never over a call", false, boot(12, 11, call = true))
        assertEquals(false, boot(12, 11, telecom = true))
        assertEquals("unknown Telecom state counts as a call", false, boot(12, 11, telecom = null))
    }

    // ---- qa-11-code #2-#4 ----------------------------------------------------------------------

    @Test
    fun `a refused release is never downloaded or kept again until the tag changes`() {
        assertEquals(LauncherUpdateStep.NOTHING, launcherUpdateStep("t1", "t0", "t1", now, false, null, false, now + 5 * backoff, backoff, refusedTag = "t1"))
        assertEquals(LauncherUpdateStep.DROP_PENDING, launcherUpdateStep("t1", "t0", null, null, false, pending.copy(releaseTag = "t1"), true, now, backoff, refusedTag = "t1"))
        assertEquals(LauncherUpdateStep.DOWNLOAD, launcherUpdateStep("t2", "t0", "t1", now, false, null, false, now, backoff, refusedTag = "t1"))
    }

    @Test
    fun `deterministic refusals stick, a missing or corrupt file is downloaded again`() {
        assertEquals(
            setOf(PendingApkCheck.NOT_OURS, PendingApkCheck.DOWNGRADE, PendingApkCheck.UNPARSEABLE, PendingApkCheck.WRONG_SIGNER),
            PendingApkCheck.entries.filter { it.deterministic }.toSet(),
        )
    }

    @Test
    fun `the APK must share a signing certificate with us - a rotation does, the debug key doesn't`() {
        val release = "aa".repeat(32)
        val debug = "bb".repeat(32)
        val rotated = "cc".repeat(32)
        assertEquals(true, signerMatches(setOf(release), setOf(release)))
        assertEquals(true, signerMatches(setOf(release), setOf(release, rotated)))
        assertEquals(false, signerMatches(setOf(debug), setOf(release)))
        assertEquals(false, signerMatches(setOf(release), null))
        assertEquals(false, signerMatches(setOf(release), emptySet()))
        // Ours unreadable: not checked (PackageInstaller still refuses a wrong key).
        assertEquals(true, signerMatches(emptySet(), null))
        assertEquals(
            PendingApkCheck.WRONG_SIGNER,
            pendingApkCheck(pending, pending.sizeBytes, pending.sha256, "me.vibb.launcher", 1_003_000L, "me.vibb.launcher", 1_002_000L, setOf(release), setOf(debug)),
        )
        assertEquals(
            PendingApkCheck.OK,
            pendingApkCheck(pending, pending.sizeBytes, pending.sha256, "me.vibb.launcher", 1_003_000L, "me.vibb.launcher", 1_002_000L, setOf(release), setOf(release)),
        )
    }

    @Test
    fun `failed results - transient ones keep the APK, deterministic ones refuse the release`() {
        for (status in listOf(1, 3, 6, 8)) assertEquals("status $status", SelfUpdateFailure.RETRY_KEEP_APK, selfUpdateFailure(status))
        for (status in listOf(-1, 2, 4, 5, 7)) assertEquals("status $status", SelfUpdateFailure.REFUSE_RELEASE, selfUpdateFailure(status))
    }

    @Test
    fun `a kept APK waits out the backoff, a refused one is never committed`() {
        assertEquals(true, pendingCommitAllowed("t1", null, null, null, now, backoff))
        assertEquals(false, pendingCommitAllowed("t1", "t1", now - 60_000L, null, now, backoff))
        assertEquals(true, pendingCommitAllowed("t1", "t1", now - backoff, null, now, backoff))
        assertEquals(true, pendingCommitAllowed("t1", "t0", now, null, now, backoff))
        assertEquals(false, pendingCommitAllowed("t1", null, null, "t1", now, backoff))
    }

    @Test
    fun `screen-off time - a SCREEN_ON with the SCREEN_OFF still queued counts 0, not since the process start`() {
        // Off since the process started (no screen event yet).
        assertEquals(50_000L, screenOffForMs(null, screenEventSeen = false, processStartElapsed = 10_000L, nowElapsed = 60_000L))
        // Off since a SCREEN_OFF.
        assertEquals(5_000L, screenOffForMs(55_000L, screenEventSeen = true, processStartElapsed = 10_000L, nowElapsed = 60_000L))
        // A SCREEN_ON came, the screen reads off but its SCREEN_OFF hasn't arrived: 0 (qa-11-code #3).
        assertEquals(0L, screenOffForMs(null, screenEventSeen = true, processStartElapsed = 10_000L, nowElapsed = 60_000L))
        assertEquals(0L, screenOffForMs(70_000L, screenEventSeen = true, processStartElapsed = 10_000L, nowElapsed = 60_000L))
    }
}
