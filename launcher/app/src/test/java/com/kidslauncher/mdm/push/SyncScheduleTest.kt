package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.dto.InstallModeReport
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.LocationPolicy
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** When the phone syncs on its own (SyncSchedule.kt) - was PushTransportTest until design 19. */
class SyncScheduleTest {

    /** Design 19, qa-19-code #2: every reopen syncs (a nudge sent in a short gap is lost), at
     * most once per 10 min - except after a deaf stream. */
    @Test
    fun `every SSE reopen syncs, at most once per 10 minutes unless the stream was deaf`() {
        // The first reopen in a process, and a quick reconnect of a live stream 10+ min after the
        // last reopen sync (a ring right after a server restart is not lost).
        assertTrue(syncOnSseReopen(null, 5_000))
        assertTrue(syncOnSseReopen(SSE_REOPEN_SYNC_EVERY_MS, 245_000))
        // A flapping stream: within 10 min of the last reopen sync, a live stream's reopen doesn't.
        assertFalse(syncOnSseReopen(SSE_REOPEN_SYNC_EVERY_MS - 1, 5_000))
        assertFalse(syncOnSseReopen(30_000, SSE_READ_TIMEOUT_MS - 1))
        // A read timeout (the stream was deaf >= 300 s) or a staleness reconnect always syncs.
        assertTrue(syncOnSseReopen(30_000, SSE_READ_TIMEOUT_MS))
        assertTrue(syncOnSseReopen(30_000, SSE_STALE_MS))
        // Unknown: sync.
        assertTrue(syncOnSseReopen(30_000, null))
        assertTrue(syncOnSseReopen(-1, 5_000))
        assertTrue(syncOnSseReopen(30_000, -1))
    }

    @Test
    fun `a stream is stale after about two of the longest keepalives`() {
        assertFalse(sseStale(240_000))
        assertFalse(sseStale(SSE_READ_TIMEOUT_MS))
        assertFalse(sseStale(SSE_STALE_MS - 1))
        assertTrue(sseStale(SSE_STALE_MS))
        assertTrue(SSE_STALE_MS >= 2 * 240_000L)
        // Every staleness reconnect meets the reopen rule's read-timeout clock.
        assertTrue(SSE_STALE_MS >= SSE_READ_TIMEOUT_MS)
    }

    @Test
    fun `the drop wake lock is taken at most every 10 minutes`() {
        assertTrue(sseDropWakeLockDue(null, 1_000))
        assertFalse(sseDropWakeLockDue(1_000, 1_000 + SSE_DROP_WAKELOCK_EVERY_MS - 1))
        assertTrue(sseDropWakeLockDue(1_000, 1_000 + SSE_DROP_WAKELOCK_EVERY_MS))
        // Elapsed realtime never goes back in a process; if it seems to, take it.
        assertTrue(sseDropWakeLockDue(5_000, 1_000))
        // Long enough for the 5 s and 10 s retries (5 + 10 s after the drop, plus the connects).
        assertTrue(SSE_DROP_WAKELOCK_MS >= 20_000L && SSE_DROP_WAKELOCK_MS <= 60_000L)
    }

    @Test
    fun `the read timeout stays above the server's longest keepalive`() {
        // The server caps SSE_KEEPALIVE_SECS at 240 (its default since design 19).
        assertTrue(SSE_READ_TIMEOUT_MS >= 240_000L + 60_000L)
    }

    @Test
    fun `backstop is 30 minutes, 15 while the stream is down`() {
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(true, null, 0))
        assertEquals(BACKSTOP_SSE_DOWN_INTERVAL_MS, backstopDelayMs(false, null, 0))
    }

    @Test
    fun `an interval location policy pulls the backstop in, never below the Doze floor`() {
        val every10 = LocationPolicy(mode = "interval", intervalMinutes = 10)
        assertEquals(10 * 60_000L - 30_000L, backstopDelayMs(true, every10, 30_000L))
        assertEquals(MIN_BACKSTOP_MS, backstopDelayMs(true, every10, 9 * 60_000L))
        assertEquals(MIN_BACKSTOP_MS, backstopDelayMs(true, every10, -1))
        val every60 = LocationPolicy(mode = "interval", intervalMinutes = 60)
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(true, every60, 0))
        assertEquals(BACKSTOP_SSE_DOWN_INTERVAL_MS, backstopDelayMs(false, every60, 0))
        // "on_request" and "off" don't schedule anything.
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(true, LocationPolicy("on_request", 10), 0))
    }

    @Test
    fun `a burst of requests costs at most one extra run`() {
        val c = SyncCoalescer()
        assertTrue(c.request("sse"))
        assertEquals(listOf("sse"), c.takeReasons())
        assertFalse(c.request("sse"))
        assertFalse(c.request("backstop"))
        assertFalse(c.request("sse"))
        assertTrue(c.isRunning)
        assertTrue(c.finished())
        assertEquals(listOf("sse", "backstop"), c.takeReasons())
        assertFalse(c.finished())
        assertFalse(c.isRunning)
        assertTrue(c.request("start"))
    }

    // Wire format

    /** Design 19 (QA #6): no `push` object and no `fcm_push_v1` any more. */
    @Test
    fun `the status report carries neither push nor fcm_push_v1`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            installedApps = listOf(InstalledApp("org.example", "Example", false, installer = "com.android.vending")),
            capabilities = STATUS_CAPABILITIES,
            installMode = InstallModeReport(untilMs = 99),
            playWindowActive = true,
        )
        val json = ServerJson.encodeToJsonElement(StatusReportRequest.serializer(), report).jsonObject
        assertFalse("push" in json)
        assertFalse("fcm_push_v1" in json["capabilities"].toString())
        assertTrue("play_policy_v1" in json["capabilities"].toString())
        assertTrue("battery_v1" in json["capabilities"].toString()) // design 35 step 3
        assertTrue("power_v1" in json["capabilities"].toString()) // design 38
        assertFalse(STATUS_CAPABILITIES.any { "fcm" in it })
        assertEquals("99", json["install_mode"]!!.jsonObject["until_ms"].toString())
        assertEquals("true", json["play_window_active"].toString())
        assertTrue("installer" in json["installed_apps"].toString())
    }

    /** qa-launcher-small-a-code Q-5: the status body with the installed versions is the shared contract
     * `installed_apps_report.json` (byte-identical with `server/testdata/`, which the server's `app_updates` test posts). */
    @Test
    fun `the installed versions encode exactly the shared contract, identical to the servers copy`() {
        val contract = javaClass.classLoader!!.getResource("installed_apps_report.json")!!.readText()
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            installedApps = listOf(
                InstalledApp(
                    "org.thoughtcrime.securesms", "Signal", false, installer = "me.vibb.launcher",
                    versionCode = 1_500L, versionName = "7.2.0",
                ),
                InstalledApp("org.example.older", "Older", true),
            ),
        )
        assertEquals(contract.trimEnd(), ServerJson.encodeToString(StatusReportRequest.serializer(), report))
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/installed_apps_report.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    /** PLAN "Installed versionCode per catalog app in the status report": each listed app's versionCode and
     * versionName (the server's `app_updated` for every catalog app); both left out when unknown. */
    @Test
    fun `an installed app carries its versionCode and versionName, left out when unknown`() {
        val apps = listOf(
            InstalledApp("org.example", "Example", false, versionCode = 1_234_567_890_123L, versionName = "7.2.0"),
            InstalledApp("org.other", "Other", true),
        )
        val json = ServerJson.encodeToJsonElement(
            StatusReportRequest.serializer(),
            StatusReportRequest(lockReason = "NONE", kioskEngaged = false, installedApps = apps),
        ).jsonObject
        val listed = json["installed_apps"].toString()
        assertTrue(listed, "\"version_code\":1234567890123" in listed)
        assertTrue(listed, "\"version_name\":\"7.2.0\"" in listed)
        assertEquals("the second entry has neither", 1, Regex("version_code").findAll(listed).count())
        assertEquals("7.2.0", com.kidslauncher.mdm.server.dto.installedVersionName("  7.2.0 \n"))
        assertEquals(null, com.kidslauncher.mdm.server.dto.installedVersionName("   "))
        assertEquals(null, com.kidslauncher.mdm.server.dto.installedVersionName(null))
        assertEquals(60, com.kidslauncher.mdm.server.dto.installedVersionName("1".repeat(100))!!.length)
        val emoji = com.kidslauncher.mdm.server.dto.installedVersionName("😀".repeat(70))!!
        assertEquals("never half a pair", 60, emoji.codePointCount(0, emoji.length))
        // The report reads them from PackageManager for the apps it already lists.
        val worker = listOf("src/main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt", "app/src/main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val collect = worker.substringAfter("private fun collectInstalledApps(").substringBefore("\n}\n")
        assertTrue(collect.contains("versionCode = pkg.longVersionCode,"))
        assertTrue(collect.contains("versionName = com.kidslauncher.mdm.server.dto.installedVersionName(pkg.versionName),"))
    }
}
