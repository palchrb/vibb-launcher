package com.kidslauncher.mdm.assistant

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 98 §2.2-§2.3: the turn-on plan, the window's `finally` on every failure, the put-back, the periods. */
class AirplaneRouteTest {
    private val contract = ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("airplane_mode.json")!!.readText()).jsonObject

    private fun JsonObject.b(key: String) = this[key]!!.jsonPrimitive.boolean

    @Test
    fun `the turn-on plan follows every rules row - a rule in force changes nothing`() {
        for (row in contract["rules"]!!.jsonObject["on"]!!.jsonArray.map { it.jsonObject }) {
            val result = airplaneOnPlan(row.b("role"), row.b("ready"), row.b("restricted"), row.b("on"), row.b("emergency"), row.b("cooldown"))
            assertEquals(row["case"].toString(), row["result"]!!.jsonPrimitive.content, result)
        }
    }

    @Test
    fun `no time-rule edge ever produces an airplane action, and the rules never call the route`() {
        for (row in contract["rules"]!!.jsonObject["edges"]!!.jsonArray.map { it.jsonObject }) {
            assertTrue(row["action"] is JsonNull)
            assertNull(airplaneActionAtRuleEdge(row["edge"]!!.jsonPrimitive.content, row.b("on")))
        }
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        for (file in File(root, "timerules").walk().filter { it.isFile }) {
            val text = file.readText()
            assertFalse(file.name, "AirplaneRoute" in text || "DISALLOW_AIRPLANE_MODE" in text)
        }
    }

    /** Design 121 §4.4: the stream closes before the radios go unless it is on Wi-Fi that airplane mode keeps. */
    @Test
    fun `the stream is held for airplane mode unless kept Wi-Fi carries it`() {
        assertTrue(airplaneHoldsStream(streamOnWifi = false, wifiKept = true))
        assertTrue(airplaneHoldsStream(streamOnWifi = false, wifiKept = null))
        assertTrue(airplaneHoldsStream(streamOnWifi = true, wifiKept = false))
        assertTrue(airplaneHoldsStream(streamOnWifi = true, wifiKept = null))
        assertFalse(airplaneHoldsStream(streamOnWifi = true, wifiKept = true))
    }

    /** Design 121 §4.4: the hold comes before step 2's report, is released in the route's `finally`, and never takes
     * the drop's reconnect wake lock. */
    @Test
    fun `the route closes the stream before its report and releases it in finally`() {
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        val route = File(root, "assistant/AirplaneRoute.kt").readText()
        val hold = route.indexOf("CommandListenerService.holdForAirplane(")
        assertTrue(hold > 0 && hold < route.indexOf("postReportOnly(context, airplaneGoingOn = true)"))
        val turnOn = route.substringAfter("fun turnOn(").substringBefore("private fun recheckWifiKept(")
        assertTrue(turnOn.substringAfter("} finally {").contains("CommandListenerService.releaseAirplaneHold()"))
        val service = File(root, "server/CommandListenerService.kt").readText()
        val body = service.substringAfter("private fun holdForAirplane(").substringBefore("private fun releaseAirplaneHold(")
        assertFalse(body.contains("markDown()") || body.contains("takeDropWakeLock()") || body.contains("streamDropped()"))
    }

    @Test
    fun `the emergency refusal, the cool-down`() {
        assertTrue(emergencyBlocks(ecbm = true, lastEmergencyMs = null, nowMs = 1_000_000))
        assertTrue(emergencyBlocks(false, 1_000_000 - RECENT_EMERGENCY_CALL_MS + 1, 1_000_000))
        assertFalse(emergencyBlocks(false, 1_000_000 - RECENT_EMERGENCY_CALL_MS, 1_000_000))
        // The user, 2026-11-15: ECBM + 10 min only - Vibb's 60-min callback window no longer refuses.
        assertFalse(emergencyBlocks(false, null, 1_000_000))
        assertTrue(inCooldown(1_000, 1_000 + AIRPLANE_COOLDOWN_MS - 1))
        assertFalse(inCooldown(1_000, 1_000 + AIRPLANE_COOLDOWN_MS))
        assertFalse(inCooldown(null, 1_000))
    }

    @Test
    fun `the phone's attribution of an end follows every row`() {
        for (row in contract["emergency"]!!.jsonObject["off_by"]!!.jsonArray.map { it.jsonObject }) {
            val byVibb = row["by_vibb"]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }
            val got = offByOf(byVibb, row["placed_ms"]!!.jsonPrimitive.longOrNull, row["off_ms"]!!.jsonPrimitive.long, row.b("ecbm"))
            assertEquals(row["case"].toString(), row["off_by"]!!.jsonPrimitive.content, got)
        }
        assertEquals(AirplaneBy.KID, onByOf(1_000, 1_000 + KID_ON_WINDOW_MS))
        assertEquals(AirplaneBy.OTHER, onByOf(1_000, 1_000 + KID_ON_WINDOW_MS + 1))
    }

    @Test
    fun `an edge opens or closes one period, at most ten from the last week`() {
        val now = 10L * AIRPLANE_PERIODS_MAX_AGE_MS
        var periods = periodsAfterEdge(emptyList(), on = true, atMs = now, by = AirplaneBy.KID, nowMs = now)
        assertEquals(listOf(AirplanePeriod(now, AirplaneBy.KID, null, null)), periods)
        // A second on edge keeps the open one; an off closes it.
        assertEquals(periods, periodsAfterEdge(periods, true, now + 5, AirplaneBy.OTHER, now + 5))
        periods = periodsAfterEdge(periods, false, now + 60_000, AirplaneBy.EMERGENCY, now + 60_000)
        assertEquals(AirplanePeriod(now, AirplaneBy.KID, now + 60_000, AirplaneBy.EMERGENCY), periods.single())
        // An off with none open records nothing.
        assertEquals(periods, periodsAfterEdge(periods, false, now + 70_000, AirplaneBy.KID, now + 70_000))
        val many = (0 until 12).map { AirplanePeriod(now + it * 10L, AirplaneBy.KID, now + it * 10L + 5, AirplaneBy.KID) }
        assertEquals(many.takeLast(MAX_AIRPLANE_PERIODS), prunedPeriods(many, now + 200))
        assertEquals(emptyList<AirplanePeriod>(), prunedPeriods(listOf(AirplanePeriod(0, AirplaneBy.KID, 1, AirplaneBy.KID)), now))
    }

    private class FakeSteps(val failAt: String?, val pinOk: Boolean = true, val hidden: Boolean = true, val turnsOn: Boolean = true) : RouteWindowSteps {
        val calls = mutableListOf<String>()
        private fun step(name: String) {
            calls += name
            if (name == failAt) throw IllegalStateException(name)
        }
        override fun mark() = step("mark")
        override fun pin(): Boolean { step("pin"); return pinOk }
        override fun unhide(): Boolean { step("unhide"); return hidden }
        override fun show(): Boolean { step("show"); return true }
        override fun await(): Boolean { step("await"); return turnsOn }
        override fun unpin() = step("unpin")
        override fun rehide() = step("rehide")
        override fun hideSession() = step("hideSession")
        override fun clearMark() = step("clearMark")
    }

    @Test
    fun `the window closes always - the pin, Settings' hide, the session, the marker last`() {
        val ok = FakeSteps(failAt = null)
        assertTrue(runRouteWindow(ok))
        assertEquals(listOf("mark", "pin", "unhide", "show", "await", "unpin", "rehide", "hideSession", "clearMark"), ok.calls)
        for (failure in listOf("unhide", "show", "await")) {
            val steps = FakeSteps(failAt = failure)
            runCatching { runRouteWindow(steps) }
            assertEquals(failure, listOf("unpin", "hideSession", "clearMark"), steps.calls.filter { it in setOf("unpin", "hideSession", "clearMark") })
            assertEquals(failure, failure != "unhide", "rehide" in steps.calls)
            assertEquals("clearMark", steps.calls.last())
        }
        // No plan: no window, nothing unhidden, the marker cleared.
        val noPlan = FakeSteps(failAt = null, pinOk = false)
        assertFalse(runRouteWindow(noPlan))
        assertEquals(listOf("mark", "pin", "hideSession", "clearMark"), noPlan.calls)
        // Settings shown already: never hidden after.
        val shown = FakeSteps(failAt = null, hidden = false)
        runRouteWindow(shown)
        assertFalse("rehide" in shown.calls)
    }

    @Test
    fun `a marker left set is put back before the first sync`() {
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.exists() }
        val sync = File(root, "server/MdmSyncWorker.kt").readText().substringAfter("suspend fun performMdmSync(")
        assertTrue(sync.indexOf("AirplaneRoute.putBackIfMarked(context)") in 0 until sync.indexOf("fetchPolicy("))
        val app = File(root, "Application.kt").readText().substringAfter("private fun initRest() {")
        assertTrue(app.indexOf("AirplaneRoute.putBackIfMarked(this)") in 0 until app.indexOf("firstLaunchResetDue("))
        // The route never writes the lock-task list itself.
        val route = File(root, "assistant/AirplaneRoute.kt").readText()
        assertFalse("setLockTaskPackages" in route || "setLockTaskFeatures" in route || "setStatusBarDisabled" in route)
    }
}
