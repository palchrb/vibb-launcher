package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 37 (Install Now): the gate, the settle, the waiting words, the download override and
 * the glue's few load-bearing lines. */
class InstallNowPlanTest {
    private val record = InstallNowRecord(9, "2026-10-19 12:00:00", "launcher-v1.4.0@77", fromVersionCode = 1_003_002)

    @Test
    fun `only a call or an emergency holds it - the screen and the hour don't matter`() {
        assertEquals(UpdateWindowDecision.Commit, installNowDecision(liveCall = false, emergency = false))
        assertEquals(
            UpdateWindowDecision.Wait(UpdateWaitReason.CALL, INSTALL_NOW_CALL_RECHECK_MS),
            installNowDecision(liveCall = true, emergency = false),
        )
        assertEquals(
            UpdateWindowDecision.Wait(UpdateWaitReason.EMERGENCY, EMERGENCY_RECHECK_MS),
            installNowDecision(liveCall = false, emergency = true),
        )
        assertEquals(UpdateWaitReason.CALL, (installNowDecision(true, true) as UpdateWindowDecision.Wait).reason)
        assertEquals(2 * 60_000L, INSTALL_NOW_CALL_RECHECK_MS)
    }

    @Test
    fun `the settle answers from the running build, a failure, then the list`() {
        assertEquals(InstallNowOutcome.Installed, installNowOutcome(record, 1_004_000, LauncherRow.NO_LIST))
        // Installed beats a failure recorded before the kill.
        assertEquals(InstallNowOutcome.Installed, installNowOutcome(record.copy(failure = "install failed: 1"), 1_004_000, LauncherRow.ON_ITS_WAY))
        assertEquals(InstallNowOutcome.Failed("no space"), installNowOutcome(record.copy(failure = "no space"), 1_003_002, LauncherRow.RUNNING))
        assertEquals(InstallNowOutcome.Failed(INSTALL_NOW_NOT_OFFERED), installNowOutcome(record, 1_003_002, LauncherRow.NOT_OFFERED))
        assertEquals(InstallNowOutcome.Failed(INSTALL_NOW_REFUSED_RELEASE), installNowOutcome(record, 1_003_002, LauncherRow.REFUSED))
        assertEquals(InstallNowOutcome.AlreadyRunning, installNowOutcome(record, 1_003_002, LauncherRow.RUNNING))
        assertEquals(InstallNowOutcome.Keep, installNowOutcome(record, 1_003_002, LauncherRow.ON_ITS_WAY))
        assertEquals(InstallNowOutcome.Keep, installNowOutcome(record, 1_003_002, LauncherRow.NO_LIST))
        // An older build than at the receipt is no "installed".
        assertEquals(InstallNowOutcome.Keep, installNowOutcome(record, 1_003_001, LauncherRow.ON_ITS_WAY))
    }

    @Test
    fun `the answers are the words the server rewords`() {
        assertEquals(true to "installed: 1.4.0", InstallNowOutcome.Installed.answer("1.4.0"))
        assertEquals(true to "already running: 1.4.0", InstallNowOutcome.AlreadyRunning.answer("1.4.0"))
        assertEquals(false to "no space", InstallNowOutcome.Failed("no space").answer("1.4.0"))
        assertNull(InstallNowOutcome.Keep.answer("1.4.0"))
        // server/src/handlers/locate.rs `reword` knows each of these.
        assertEquals(
            listOf(
                "not offered", "refused: release", "download failed", "download corrupt", "no space",
                "superseded", "call never ended", "install failed: start", "install failed: 4",
                "refused: wrong_signer", "refused: not_ours", "refused: downgrade", "refused: unparseable",
            ),
            listOf(
                INSTALL_NOW_NOT_OFFERED, INSTALL_NOW_REFUSED_RELEASE, INSTALL_NOW_DOWNLOAD_FAILED,
                INSTALL_NOW_DOWNLOAD_CORRUPT, INSTALL_NOW_NO_SPACE, INSTALL_NOW_SUPERSEDED,
                INSTALL_NOW_CALL_NEVER_ENDED, INSTALL_NOW_INSTALL_FAILED_START, installNowInstallFailed(4),
                PendingApkCheck.WRONG_SIGNER.installNowAnswer, PendingApkCheck.NOT_OURS.installNowAnswer,
                PendingApkCheck.DOWNGRADE.installNowAnswer, PendingApkCheck.UNPARSEABLE.installNowAnswer,
            ),
        )
        val reword = repoRoot()?.let { File(it, "server/src/handlers/locate.rs") }?.takeIf { it.isFile }?.readText()
        if (reword != null) {
            for (word in listOf("\"not offered\"", "\"refused: wrong_signer\"", "\"superseded\"", "\"call never ended\"", "\"installed: \"", "\"already running: \"", "\"install failed: \"")) {
                assertTrue(word, word in reword)
            }
        }
    }

    @Test
    fun `the list's row`() {
        val tag = "launcher-v1.4.0@77"
        assertEquals(LauncherRow.NOT_OFFERED, launcherRow(false, tag, null, null, null))
        assertEquals(LauncherRow.NOT_OFFERED, launcherRow(true, null, null, null, LauncherUpdateStep.NOTHING))
        assertEquals(LauncherRow.RUNNING, launcherRow(true, tag, null, null, LauncherUpdateStep.ALREADY_RUNNING))
        assertEquals(LauncherRow.RUNNING, launcherRow(true, tag, tag, null, LauncherUpdateStep.NOTHING))
        assertEquals(LauncherRow.REFUSED, launcherRow(true, tag, null, tag, LauncherUpdateStep.NOTHING))
        assertEquals(LauncherRow.ON_ITS_WAY, launcherRow(true, tag, "launcher-v1.3.2@70", null, LauncherUpdateStep.DOWNLOAD))
    }

    @Test
    fun `the waiting words`() {
        assertEquals("install_now_download", installNowWait(true, pendingPresent = false, lastWait = null))
        assertEquals("install_now_call", installNowWait(true, true, UpdateWaitReason.CALL))
        assertEquals("install_now_emergency", installNowWait(true, true, UpdateWaitReason.EMERGENCY))
        assertEquals("install_now_ready", installNowWait(true, true, null))
        assertEquals("install_now_ready", installNowWait(true, true, UpdateWaitReason.OUTSIDE_WINDOW))
        // No record: the night gate's word, only with a pending APK.
        assertEquals("outside_window", installNowWait(false, true, UpdateWaitReason.OUTSIDE_WINDOW))
        assertNull(installNowWait(false, false, UpdateWaitReason.CALL))
        for (word in listOf("install_now_download", "install_now_call", "install_now_emergency", "install_now_ready")) {
            assertTrue(word.length <= 32)
        }
    }

    @Test
    fun `the alarm keeps the call's two minutes over a screen-off`() {
        assertEquals(INSTALL_NOW_CALL_RECHECK_MS, installNowRecheckMs(UpdateWaitReason.CALL))
        assertEquals(EMERGENCY_RECHECK_MS, installNowRecheckMs(UpdateWaitReason.EMERGENCY))
        assertEquals(UPDATE_SCREEN_OFF_MS, installNowRecheckMs(null))
    }

    @Test
    fun `a call that stays on for 6 hours ends it`() {
        val hour = 60 * 60_000L
        assertEquals(1_000L, callWaitSince(null, waitingOnCall = true, nowElapsed = 1_000))
        assertEquals(1_000L, callWaitSince(1_000, true, 5 * hour))
        assertNull(callWaitSince(1_000, waitingOnCall = false, nowElapsed = 2_000))
        // Elapsed time behind the stored start: a reboot - the wait starts again.
        assertEquals(500L, callWaitSince(9 * hour, true, 500))
        assertFalse(callWaitExpired(1_000, 1_000 + 6 * hour - 1))
        assertTrue(callWaitExpired(1_000, 1_000 + 6 * hour))
        assertFalse(callWaitExpired(null, 7 * hour))
        assertFalse(callWaitExpired(9 * hour, 500))
    }

    @Test
    fun `with a record the hour's backoff is skipped, a refusal is not`() {
        val now = 1_700_000_000_000L
        fun step(failedBackoffMs: Long, refused: String? = null) = launcherUpdateStep(
            "launcher-v1.4.0", "launcher-v1.3.2", "launcher-v1.4.0", now - 60_000L, false, null, false, now,
            failedBackoffMs, refusedTag = refused,
        )
        assertEquals(LauncherUpdateStep.NOTHING, step(60 * 60_000L))
        assertEquals(LauncherUpdateStep.DOWNLOAD, step(0))
        assertEquals(LauncherUpdateStep.NOTHING, step(0, refused = "launcher-v1.4.0"))
        assertTrue("failedBackoffMs = if (installNow) 0 else FAILED_RETRY_BACKOFF_MS," in source("MdmSyncWorker.kt"))
    }

    @Test
    fun `the parent's override lifts Wi-Fi only and roaming, never the network or the space`() {
        val metered = NetCaps(internet = true, notMetered = false, notRoaming = true, physical = true)
        val roaming = metered.copy(notRoaming = false)
        fun gate(caps: NetCaps?, override: Boolean, allocatable: Long? = null) =
            downloadGate(caps, wifiOnly = true, anyNetworkAt = null, nowMs = 0, haveBytes = 0, totalBytes = 1_000L, allocatableBytes = allocatable, parentOverride = override)
        assertEquals(DownloadGate.WAIT_WIFI, gate(metered, false))
        assertEquals(DownloadGate.GO, gate(metered, true))
        assertEquals(DownloadGate.WAIT_ROAMING, gate(roaming, false))
        assertEquals(DownloadGate.GO, gate(roaming, true))
        assertEquals(DownloadGate.WAIT_NETWORK, gate(null, true))
        assertEquals(DownloadGate.WAIT_NETWORK, gate(metered.copy(physical = false), true))
        assertEquals(DownloadGate.WAIT_SPACE, gate(metered, true, allocatable = 10L))
    }

    /** QA M3: one helper behind every gate site, the override read live from the record. */
    @Test
    fun `every gate in AppDownloads goes through gate`() {
        val downloads = source("AppDownloads.kt")
        assertEquals(1, Regex("""\bdownloadGate\(""").findAll(downloads).count())
        val helper = downloads.substringAfter("private fun gate(").substringBefore("\n    }\n")
        assertTrue("parentOverride = record.isLauncher && InstallNow.active(app) != null" in downloads.substringAfter("private fun gate("))
        assertTrue(helper.isNotEmpty())
        assertTrue(Regex("""\bgate\(app, """).findAll(downloads).count() >= 5)
    }

    /** R-L1 and §4.2: the screen-off arms Install Now's re-check, the screen-on keeps it, and the
     * call's end asks for a sync. */
    @Test
    fun `the alarm and the call's end`() {
        val selfUpdate = source("SelfUpdate.kt")
        val arm = selfUpdate.substringAfter("private fun armIfPending(").substringBefore("\n    }\n")
        assertTrue("installNowRecheckMs(lastWait)" in arm)
        val screenOn = selfUpdate.substringAfter("fun onScreenOn(").substringBefore("\n    }\n")
        assertTrue("if (InstallNow.active(app) == null) cancelAlarm(app)" in screenOn)
        assertTrue("OngoingCalls.addListener { onCallsChanged(app) }" in selfUpdate)
        val wait = source("MdmSyncWorker.kt").substringAfter("private fun waitForWindow(").substringBefore("\n}\n")
        assertTrue("(installNow || SelfUpdate.screenOffForMs(context) != null)" in wait)
    }

    @Test
    fun `the record keeps its fields and reads an older shape`() {
        val full = record.copy(failure = "no space", callWaitSinceElapsed = 42)
        assertEquals(full, ServerJson.decodeFromString(InstallNowRecord.serializer(), ServerJson.encodeToString(InstallNowRecord.serializer(), full)))
        assertEquals(
            InstallNowRecord(3, null, null, 7),
            ServerJson.decodeFromString(InstallNowRecord.serializer(), """{"command_id":3,"requested_at":null,"tag":null,"from_version_code":7}"""),
        )
    }

    @Test
    fun `the capability is announced`() {
        assertTrue(INSTALL_NOW_CAPABILITY in STATUS_CAPABILITIES)
        assertEquals("install_now_v1", INSTALL_NOW_CAPABILITY)
        assertEquals("install_update", INSTALL_NOW_COMMAND)
    }

    private fun repoRoot(): File? = listOf("..", "../..", ".").map(::File).firstOrNull { File(it, "server").isDirectory && File(it, "launcher").isDirectory }

    private fun source(name: String): String {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        return File(src, "main/java/com/kidslauncher/mdm/server/$name").readText()
    }
}
