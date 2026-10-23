package com.kidslauncher.mdm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
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

    // ---- the release the phone already runs (QR/adb install, no record) ------------------------

    private fun running(advertised: String, code: Long, pending: PendingSelfUpdate? = null) =
        launcherUpdateStep(advertised, null, null, null, false, pending, true, now, backoff, runningVersionCode = code)

    /** The shared contract (`launcher_tag_codes.json`, byte-identical with the server's `testdata/`, which
     * `install_now::launcher_tag_version_code` and `scripts/release-version.sh --selftest` read too). */
    private val tagCodes = javaClass.classLoader!!.getResource("launcher_tag_codes.json")!!.readText()

    @Test
    fun `a release tag's versionCode is the shared contract's`() {
        val rows = Json.parseToJsonElement(tagCodes).jsonObject["codes"]!!.jsonArray.map { it.jsonObject }
        assertTrue(rows.size > 20)
        for (row in rows) {
            val tag = row["tag"]!!.jsonPrimitive.content
            val code = row["code"]!!.takeUnless { it is JsonNull }?.jsonPrimitive?.longOrNull
            assertEquals(tag, code, launcherTagVersionCode(tag))
            // The name only for a tag whose code reads.
            assertEquals(tag, code != null, launcherTagVersionName(tag) != null)
        }
    }

    @Test
    fun `the tag codes are identical to the server's copy`() {
        val serverCopy = File("../../server/testdata/launcher_tag_codes.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), tagCodes)
    }

    @Test
    fun `the running version or an older one is recorded installed without a download`() {
        assertEquals("equal", LauncherUpdateStep.ALREADY_RUNNING, running("launcher-v1.3.0@5", 10_030_009))
        assertEquals("older", LauncherUpdateStep.ALREADY_RUNNING, running("launcher-v1.2.9@5", 10_030_009))
        assertEquals("an rc of the running version", LauncherUpdateStep.ALREADY_RUNNING, running("launcher-v1.3.0-rc.1@5", 10_030_009))
        assertEquals("a stale pending goes too", LauncherUpdateStep.ALREADY_RUNNING, running("launcher-v1.3.0@5", 10_030_009, pending))
        assertEquals("an older rc of the running version's", LauncherUpdateStep.ALREADY_RUNNING, running("launcher-v1.3.0-rc.1@5", 10_030_002))
    }

    // ---- RC over RC: one versionCode, another versionName ----------------------------------------

    private fun runningAs(advertised: String, code: Long, name: String?, pending: PendingSelfUpdate? = null) =
        launcherUpdateStep(advertised, null, null, null, false, pending, true, now, backoff, runningVersionCode = code, runningVersionName = name)

    @Test
    fun `a release tag's versionName is the tag minus launcher-v`() {
        assertEquals("0.36.0-rc.2", launcherTagVersionName("launcher-v0.36.0-rc.2@5"))
        assertEquals("0.36.0", launcherTagVersionName("launcher-v0.36.0"))
        for (bad in listOf("v0.36.0", "launcher-v0.36", "launcher-v0.36.0-beta@5", "launcher-v0.36.0-rc.2x")) {
            assertEquals(bad, null, launcherTagVersionName(bad))
        }
    }

    @Test
    fun `a build is ahead by a higher code, or by the same code under a newer name`() {
        assertEquals(true, launcherBuildAhead(36_000, "0.36.0", 35_999, "0.36.0-rc.2"))
        assertEquals(false, launcherBuildAhead(35_999, "0.36.0-rc.2", 36_000, "0.36.0"))
        assertEquals("rc.2 over rc.1", true, launcherBuildAhead(35_999, "0.36.0-rc.2", 35_999, "0.36.0-rc.1"))
        assertEquals("rc.10 over rc.9", true, launcherBuildAhead(35_999, "0.36.0-rc.10", 35_999, "0.36.0-rc.9"))
        assertEquals("the next version's rc over the release", true, launcherBuildAhead(36_000, "0.36.1-rc.1", 36_000, "0.36.0"))
        assertEquals("the same build", false, launcherBuildAhead(35_999, "0.36.0-rc.1", 35_999, "0.36.0-rc.1"))
        assertEquals("rc.1 over rc.2", false, launcherBuildAhead(35_999, "0.36.0-rc.1", 35_999, "0.36.0-rc.2"))
        assertEquals("the release over the next version's rc", false, launcherBuildAhead(36_000, "0.36.0", 36_000, "0.36.1-rc.1"))
        // An RC sorts before its own release (qa-rc-to-rc-code R2R-2).
        assertEquals(true, launcherBuildAhead(5, "0.36.0", 5, "0.36.0-rc.2"))
        assertEquals(false, launcherBuildAhead(5, "0.36.0-rc.2", 5, "0.36.0"))
        assertEquals("names that can't be ordered but differ", true, launcherBuildAhead(5, "0.36.0-rc.2", 5, "local"))
        assertEquals("an unknown name is the running build", false, launcherBuildAhead(35_999, null, 35_999, "0.36.0-rc.1"))
        assertEquals(false, launcherBuildAhead(35_999, "0.36.0-rc.2", 35_999, null))
    }

    @Test
    fun `an rc over an rc of the same version is downloaded, the same name is already running`() {
        assertEquals(LauncherUpdateStep.DOWNLOAD, runningAs("launcher-v0.36.0-rc.2@5", 35_999, "0.36.0-rc.1"))
        assertEquals(LauncherUpdateStep.REPLACE_PENDING, runningAs("launcher-v0.36.0-rc.2@5", 35_999, "0.36.0-rc.1", pending))
        // From launcher-v0.36.0-rc.4 on every build has its own code: the code decides (an old-rule rc.3 takes rc.4).
        assertEquals(LauncherUpdateStep.DOWNLOAD, runningAs("launcher-v0.36.0-rc.4@5", 35_999, "0.36.0-rc.3"))
        assertEquals(LauncherUpdateStep.DOWNLOAD, runningAs("launcher-v0.36.1-rc.1@5", 360_009, "0.36.0"))
        assertEquals(LauncherUpdateStep.DOWNLOAD, runningAs("launcher-v0.36.0@5", 35_999, "0.36.0-rc.2"))
        assertEquals(LauncherUpdateStep.ALREADY_RUNNING, runningAs("launcher-v0.36.0-rc.2@5", 35_999, "0.36.0-rc.2"))
        assertEquals(LauncherUpdateStep.ALREADY_RUNNING, runningAs("launcher-v0.36.0-rc.1@5", 35_999, "0.36.0-rc.2"))
        assertEquals(LauncherUpdateStep.ALREADY_RUNNING, runningAs("launcher-v0.36.0@5", 360_011, "0.36.1-rc.1"))
        assertEquals(LauncherUpdateStep.ALREADY_RUNNING, runningAs("launcher-v0.36.0-rc.3@5", 360_004, "0.36.0-rc.4"))
        assertEquals("an rc.4 phone under the 0.34.0 row", LauncherUpdateStep.ALREADY_RUNNING, running("launcher-v0.34.0@5", 360_004))
        assertEquals("no running name: by the code alone", LauncherUpdateStep.ALREADY_RUNNING, runningAs("launcher-v0.36.0-rc.2@5", 35_999, null))
    }

    @Test
    fun `a pending APK of the same code installs only under a newer name, every other check stays`() {
        fun same(archiveName: String?, ownName: String? = "0.36.0-rc.1", signers: Set<String>? = null) = pendingApkCheck(
            pending, pending.sizeBytes, pending.sha256, "me.vibb.launcher", 35_999L, "me.vibb.launcher", 35_999L,
            ourSigners = if (signers == null) emptySet() else setOf("aa"), archiveSigners = signers,
            archiveVersionName = archiveName, ownVersionName = ownName,
        )
        assertEquals(PendingApkCheck.OK, same("0.36.0-rc.2"))
        assertEquals(PendingApkCheck.SAME_VERSION, same("0.36.0-rc.1"))
        assertEquals(PendingApkCheck.SAME_VERSION, same("0.36.0-rc.0"))
        assertEquals("an unreadable name keeps refusing", PendingApkCheck.SAME_VERSION, same(null))
        assertEquals(PendingApkCheck.SAME_VERSION, same("0.36.0-rc.2", ownName = null))
        assertEquals("the signer still decides", PendingApkCheck.WRONG_SIGNER, same("0.36.0-rc.2", signers = setOf("bb")))
        assertEquals(PendingApkCheck.OK, same("0.36.0-rc.2", signers = setOf("aa")))
        assertEquals(
            "the first new-rule build over an old-rule rc",
            PendingApkCheck.OK,
            pendingApkCheck(pending, pending.sizeBytes, pending.sha256, "me.vibb.launcher", 360_004L, "me.vibb.launcher", 35_999L, archiveVersionName = "0.36.0-rc.4", ownVersionName = "0.36.0-rc.3"),
        )
        assertEquals(
            PendingApkCheck.NOT_OURS,
            pendingApkCheck(pending, pending.sizeBytes, pending.sha256, "org.example", 35_999L, "me.vibb.launcher", 35_999L, archiveVersionName = "0.36.0-rc.2", ownVersionName = "0.36.0-rc.1"),
        )
        assertEquals(
            PendingApkCheck.DOWNGRADE,
            pendingApkCheck(pending, pending.sizeBytes, pending.sha256, "me.vibb.launcher", 35_998L, "me.vibb.launcher", 35_999L, archiveVersionName = "0.36.0-rc.9", ownVersionName = "0.36.0-rc.1"),
        )
    }

    @Test
    fun `a newer release, the final over its rc, or an unparseable tag is downloaded as before`() {
        assertEquals("newer", LauncherUpdateStep.DOWNLOAD, running("launcher-v1.3.1@5", 10_030_009))
        assertEquals("the final over its rc", LauncherUpdateStep.DOWNLOAD, running("launcher-v1.3.0@5", 10_030_003))
        assertEquals("the next rc over the last", LauncherUpdateStep.DOWNLOAD, running("launcher-v1.3.0-rc.4@5", 10_030_003))
        assertEquals("unparseable", LauncherUpdateStep.DOWNLOAD, running("v26.09.4@14", 99_000_000))
        assertEquals("a debug build's low code", LauncherUpdateStep.DOWNLOAD, running("launcher-v0.34.0@5", 116))
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
