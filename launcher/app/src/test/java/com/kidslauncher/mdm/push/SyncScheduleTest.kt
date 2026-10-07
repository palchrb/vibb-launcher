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

    @Test
    fun `a quick SSE reconnect doesn't sync, a long gap does`() {
        assertFalse(syncOnSseReopen(5_000))
        assertFalse(syncOnSseReopen(SSE_GAP_SYNC_MS - 1))
        assertTrue(syncOnSseReopen(SSE_GAP_SYNC_MS))
        assertTrue(syncOnSseReopen(null))
        assertTrue(syncOnSseReopen(-1))
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
        assertFalse(STATUS_CAPABILITIES.any { "fcm" in it })
        assertEquals("99", json["install_mode"]!!.jsonObject["until_ms"].toString())
        assertEquals("true", json["play_window_active"].toString())
        assertTrue("installer" in json["installed_apps"].toString())
    }
}
