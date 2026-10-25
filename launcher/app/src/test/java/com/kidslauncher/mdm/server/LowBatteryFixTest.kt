package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.LocationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-25 (PLAN "A last position at shutdown and at low battery"): one balanced fix at Android's
 * battery-low edge, once per episode, never under "Off" or with Location off, reported with that
 * edge's sync - the decision, the action, the episode's end and the wiring by source.
 */
class LowBatteryFixTest {
    private val regularly = LocationPolicy(mode = "interval", intervalMinutes = 30)

    @Test
    fun `one fix at the edge, never under Off, with Location off, outside an episode's first or as no owner`() {
        assertTrue(lowBatteryFixDue(edgeSeen = true, episodeTaken = false, deviceOwner = true, policy = regularly, systemLocationOn = true))
        assertTrue(lowBatteryFixDue(true, false, true, LocationPolicy(mode = "on_request", intervalMinutes = 30), true))
        assertFalse("no edge", lowBatteryFixDue(false, false, true, regularly, true))
        assertFalse("taken in this episode", lowBatteryFixDue(true, true, true, regularly, true))
        assertFalse("not the device owner", lowBatteryFixDue(true, false, false, regularly, true))
        assertFalse("location Off", lowBatteryFixDue(true, false, true, LocationPolicy(mode = "off", intervalMinutes = 30), true))
        assertFalse("no policy", lowBatteryFixDue(true, false, true, null, true))
        assertFalse("the Location switch off", lowBatteryFixDue(true, false, true, regularly, false))
    }

    @Test
    fun `the fix is the balanced path unless the sync takes one anyway`() {
        for (action in listOf(LocationAction.NONE, LocationAction.CACHED)) {
            assertEquals(LocationAction.FRESH_BALANCED, withLowBatteryFix(action, due = true))
            assertEquals(action, withLowBatteryFix(action, due = false))
        }
        assertEquals("a locate keeps its precise fix", LocationAction.FRESH, withLowBatteryFix(LocationAction.FRESH, true))
        assertEquals(LocationAction.FRESH_BALANCED, withLowBatteryFix(LocationAction.FRESH_BALANCED, true))
    }

    @Test
    fun `the episode ends only once the battery was charged above low`() {
        assertFalse("discharging, not low", lowEpisodeAfterReading(true, BatteryReport(30, charging = false, low = false)))
        assertTrue("still low", lowEpisodeAfterReading(true, BatteryReport(12, charging = false, low = true)))
        assertTrue("charging (Android never says low then)", lowEpisodeAfterReading(true, BatteryReport(12, charging = true, low = false)))
        assertTrue("no reading", lowEpisodeAfterReading(true, null))
        assertFalse("none taken", lowEpisodeAfterReading(false, BatteryReport(12, charging = false, low = true)))
    }

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(name: String): String = sources.walkTopDown().first { it.name == name }.readText()

    @Test
    fun `the edge asks the sync it requests, which takes the fix and reports it`() {
        // The receiver notes the edge before its sync request.
        val receiver = code("BatteryStatus.kt").substringAfter("override fun onReceive(").substringBefore("\n            }\n        }")
        val edge = receiver.indexOf("LowBatteryFix.onBatteryEdge(app, intent?.action)")
        assertTrue(edge in 0 until receiver.indexOf("SyncRunner.request(app, BATTERY_SYNC_REASON)"))
        // The sync takes the edge before its location step, which turns it into the balanced fix,
        // and every report's reading may end the episode.
        val sync = code("MdmSyncWorker.kt")
        val take = sync.indexOf("val lowBatteryFix = LowBatteryFix.take(")
        assertTrue(take in 0 until sync.indexOf("val location = currentLocationReport("))
        assertTrue("locationPolicy, lowBatteryFix)" in sync)
        assertTrue("val action = withLowBatteryFix(sync.action, lowBatteryFix)" in sync)
        assertTrue("battery = BatteryStatus.report(context).also { LowBatteryFix.observe(context, it) }" in sync)
        // The episode is marked before the fix is asked for, with a synchronous write; no coordinates logged.
        val glue = code("LowBatteryFix.kt")
        assertTrue("if (due) prefs.edit().putBoolean(TAKEN_KEY, true).commit()" in glue)
        assertFalse(".apply()" in glue)
        assertFalse(Regex("""Log\.[a-z]\(.*(latitude|longitude|lat|lng)""").containsMatchIn(glue))
    }
}
