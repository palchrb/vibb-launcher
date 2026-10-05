package com.kidslauncher.mdm.push

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.InstallModeReport
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.LocationPolicy
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.PushPolicy
import com.kidslauncher.mdm.server.dto.PushReport
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PushTransportTest {

    private val token = "fcm-token-abc"
    private val known = PushPolicy(fcmEnabled = true, fcmOk = true, fcmTokenHash = fcmTokenHash(token))
    private val good = PushInputs(fcmConfigured = true, gmsAvailable = true, playStorePresent = true, token = token, server = known)

    private fun decide(inputs: PushInputs) = decidePushTransport(inputs)

    @Test
    fun `FCM only when everything says it works`() {
        assertEquals(TransportDecision(PushTransport.FCM, null), decide(good))
    }

    @Test
    fun `any doubt lands on SSE with the reason`() {
        assertEquals(SseReason.NO_CONFIG, decide(good.copy(fcmConfigured = false)).sseReason)
        assertEquals(SseReason.NO_GMS, decide(good.copy(gmsAvailable = false)).sseReason)
        assertEquals(SseReason.NO_PLAY_STORE, decide(good.copy(playStorePresent = false)).sseReason)
        assertEquals(SseReason.NO_TOKEN, decide(good.copy(token = null)).sseReason)
        assertEquals(SseReason.NO_TOKEN, decide(good.copy(token = " ")).sseReason)
        assertEquals(SseReason.SERVER_OFF, decide(good.copy(server = null)).sseReason)
        assertEquals(SseReason.SERVER_OFF, decide(good.copy(server = known.copy(fcmEnabled = false))).sseReason)
        // fcm_ok for an old token: the server hasn't seen this one work.
        assertEquals(SseReason.TOKEN_UNKNOWN, decide(good.copy(server = known.copy(fcmTokenHash = fcmTokenHash("old")))).sseReason)
        assertEquals(SseReason.TOKEN_UNKNOWN, decide(good.copy(server = known.copy(fcmTokenHash = null))).sseReason)
        assertEquals(SseReason.NOT_PROVEN, decide(good.copy(server = known.copy(fcmOk = false))).sseReason)
        for (bad in listOf(good.copy(fcmConfigured = false), good.copy(server = null), good.copy(token = null))) {
            assertEquals(PushTransport.SSE, decide(bad).transport)
        }
    }

    @Test
    fun `token hash is the first 16 hex chars of sha256`() {
        // printf foo | sha256sum
        assertEquals(16, fcmTokenHash(token).length)
        assertEquals("2c26b46b68ffc68f", fcmTokenHash("foo"))
    }

    @Test
    fun `token requests`() {
        val day = TOKEN_RENEW_INTERVAL_MS
        val now = 10 * day
        assertEquals(TokenAction.NONE, tokenAction(false, null, known, 0, now))
        assertEquals(TokenAction.GET, tokenAction(true, null, null, 0, now))
        assertEquals(TokenAction.NONE, tokenAction(true, null, null, now - 60_000, now))
        assertEquals(TokenAction.GET, tokenAction(true, null, null, now - TOKEN_RETRY_INTERVAL_MS, now))
        assertEquals(TokenAction.GET, tokenAction(true, null, null, now + 1, now))
        // The server knows our token: nothing to do.
        assertEquals(TokenAction.NONE, tokenAction(true, token, known, now - 5 * day, now))
        // It doesn't (cleared as dead, restore): renew, at most daily.
        val forgot = known.copy(fcmTokenHash = null, fcmOk = false)
        assertEquals(TokenAction.NONE, tokenAction(true, token, forgot, now - 60_000, now))
        assertEquals(TokenAction.RENEW, tokenAction(true, token, forgot, now - day, now))
        // A server without FCM never makes us churn tokens.
        assertEquals(TokenAction.NONE, tokenAction(true, token, null, 0, now))
        assertEquals(TokenAction.NONE, tokenAction(true, token, PushPolicy(), 0, now))
    }

    @Test
    fun `a token the server knew and dropped is renewed at once`() {
        val now = 10 * TOKEN_RENEW_INTERVAL_MS
        val dropped = known.copy(fcmTokenHash = null, fcmOk = false)
        assertEquals(TokenAction.RENEW, tokenAction(true, token, dropped, now - 60_000, now, serverKnewToken = true))
        // Never known (first report not in yet): wait.
        assertEquals(TokenAction.NONE, tokenAction(true, token, dropped, now - 60_000, now, serverKnewToken = false))
        // Still known: nothing.
        assertEquals(TokenAction.NONE, tokenAction(true, token, known, now - 60_000, now, serverKnewToken = true))
    }

    @Test
    fun `a quick SSE reconnect doesn't sync, a long gap does`() {
        assertFalse(syncOnSseReopen(5_000))
        assertFalse(syncOnSseReopen(SSE_GAP_SYNC_MS - 1))
        assertTrue(syncOnSseReopen(SSE_GAP_SYNC_MS))
        assertTrue(syncOnSseReopen(null))
        assertTrue(syncOnSseReopen(-1))
    }

    @Test
    fun `priority names`() {
        assertEquals("high", priorityName(1))
        assertEquals("normal", priorityName(2))
        assertEquals("unknown", priorityName(0))
    }

    // Scheduling

    @Test
    fun `backstop is 30 minutes, 15 while SSE is down`() {
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(PushTransport.FCM, false, null, 0))
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(PushTransport.SSE, true, null, 0))
        assertEquals(BACKSTOP_SSE_DOWN_INTERVAL_MS, backstopDelayMs(PushTransport.SSE, false, null, 0))
    }

    @Test
    fun `an interval location policy pulls the backstop in, never below the Doze floor`() {
        val every10 = LocationPolicy(mode = "interval", intervalMinutes = 10)
        assertEquals(10 * 60_000L - 30_000L, backstopDelayMs(PushTransport.FCM, true, every10, 30_000L))
        assertEquals(MIN_BACKSTOP_MS, backstopDelayMs(PushTransport.FCM, true, every10, 9 * 60_000L))
        assertEquals(MIN_BACKSTOP_MS, backstopDelayMs(PushTransport.FCM, true, every10, -1))
        val every60 = LocationPolicy(mode = "interval", intervalMinutes = 60)
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(PushTransport.FCM, true, every60, 0))
        // "on_request" and "off" don't schedule anything.
        assertEquals(BACKSTOP_INTERVAL_MS, backstopDelayMs(PushTransport.FCM, true, LocationPolicy("on_request", 10), 0))
    }

    @Test
    fun `a burst of requests costs at most one extra run`() {
        val c = SyncCoalescer()
        assertTrue(c.request("fcm"))
        assertEquals(listOf("fcm"), c.takeReasons())
        assertFalse(c.request("fcm"))
        assertFalse(c.request("backstop"))
        assertFalse(c.request("fcm"))
        assertTrue(c.isRunning)
        assertTrue(c.finished())
        assertEquals(listOf("fcm", "backstop"), c.takeReasons())
        assertFalse(c.finished())
        assertFalse(c.isRunning)
        assertTrue(c.request("start"))
    }

    // Wire format

    @Test
    fun `policy push decodes and is optional`() {
        val p = ServerJson.decodeFromString(
            PolicyResponse.serializer(),
            """{"allowlist":[],"push":{"fcm_enabled":true,"fcm_ok":false,"fcm_token_hash":"0123456789abcdef"}}""",
        )
        assertEquals(PushPolicy(true, false, "0123456789abcdef"), p.push)
        assertNull(ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist":[]}""").push)
        val nullHash = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"push":{"fcm_enabled":false,"fcm_ok":false,"fcm_token_hash":null}}""")
        assertEquals(PushPolicy(), nullHash.push)
    }

    @Test
    fun `status push, install mode and installer use the server's keys`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            installedApps = listOf(InstalledApp("org.example", "Example", false, installer = "com.android.vending")),
            push = PushReport(
                fcmToken = "t", transport = "fcm", fcmConfigured = true, gmsAvailable = true,
                lastNudgeMs = 5, lastNudgeId = "abc", lastPriority = "high", lastOriginalPriority = "high", reason = null,
            ),
            installMode = InstallModeReport(untilMs = 99),
            playWindowActive = true,
        )
        val json = ServerJson.encodeToJsonElement(StatusReportRequest.serializer(), report).jsonObject
        val push = json["push"]!!.jsonObject
        for (key in listOf("fcm_token", "transport", "fcm_configured", "gms_available", "last_nudge_ms", "last_nudge_id", "last_priority", "last_original_priority")) {
            assertTrue(key, key in push)
        }
        assertEquals("99", json["install_mode"]!!.jsonObject["until_ms"].toString())
        assertEquals("true", json["play_window_active"].toString())
        assertTrue("installer" in json["installed_apps"].toString())
    }
}
