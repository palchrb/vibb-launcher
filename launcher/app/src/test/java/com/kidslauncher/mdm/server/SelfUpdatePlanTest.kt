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
        pkg: String? = "com.kidslauncher.mdm",
        code: Long? = 1_003_000L,
    ) = pendingApkCheck(pending, size, sha, pkg, code, "com.kidslauncher.mdm", 1_002_000L)

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
        assertEquals(PendingApkCheck.NOT_OURS, apk(pkg = "com.kidslauncher.mdm.debug"))
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
}
