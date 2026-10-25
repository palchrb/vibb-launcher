package com.kidslauncher.mdm.server

import android.content.Intent
import com.kidslauncher.mdm.server.dto.BatteryReport
import com.kidslauncher.mdm.server.dto.LocationPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-10-25 (PLAN "A last position at shutdown and at low battery"): one balanced fix at Android's
 * battery-low edge, once per episode, never under "Off" or with Location off, a recent fix reported
 * instead of a new one, reported with that edge's sync - the decision, the action, the episode
 * (driven through [LowBatteryEpisode] over a field, qa-last-position-code Q-2) and the wiring by source.
 */
class LowBatteryFixTest {
    private val regularly = LocationPolicy(mode = "interval", intervalMinutes = 30)
    private val off = LocationPolicy(mode = "off", intervalMinutes = 30)
    private val low = Intent.ACTION_BATTERY_LOW
    private val okay = Intent.ACTION_BATTERY_OKAY

    /** The episode's flag as a field, with how often it was written. */
    private class Flag(var value: Boolean = false) : LowEpisodeFlag {
        var writes = 0

        override fun taken() = value

        override fun set(taken: Boolean) {
            value = taken
            writes++
        }
    }

    private fun LowBatteryEpisode.takeNow(policy: LocationPolicy? = regularly, location: Boolean = true, ownFixAgeMs: Long? = null) =
        take(deviceOwner = true, policy = policy, systemLocationOn = location, ownFixAgeMs = ownFixAgeMs)

    @Test
    fun `one fix at the edge, never under Off, with Location off, outside an episode's first or as no owner`() {
        assertTrue(lowBatteryFixDue(edgeSeen = true, episodeTaken = false, deviceOwner = true, policy = regularly, systemLocationOn = true))
        assertTrue(lowBatteryFixDue(true, false, true, LocationPolicy(mode = "on_request", intervalMinutes = 30), true))
        assertFalse("no edge", lowBatteryFixDue(false, false, true, regularly, true))
        assertFalse("taken in this episode", lowBatteryFixDue(true, true, true, regularly, true))
        assertFalse("not the device owner", lowBatteryFixDue(true, false, false, regularly, true))
        assertFalse("location Off", lowBatteryFixDue(true, false, true, off, true))
        assertFalse("no policy", lowBatteryFixDue(true, false, true, null, true))
        assertFalse("the Location switch off", lowBatteryFixDue(true, false, true, regularly, false))
    }

    @Test
    fun `the fix is the balanced path unless the sync takes one anyway, a recent one is reported as held`() {
        for (action in listOf(LocationAction.NONE, LocationAction.CACHED)) {
            assertEquals(LocationAction.FRESH_BALANCED, withLowBatteryFix(action, LowFix.FIX))
            assertEquals(action, withLowBatteryFix(action, LowFix.NONE))
        }
        assertEquals("a locate keeps its precise fix", LocationAction.FRESH, withLowBatteryFix(LocationAction.FRESH, LowFix.FIX))
        assertEquals(LocationAction.FRESH_BALANCED, withLowBatteryFix(LocationAction.FRESH_BALANCED, LowFix.FIX))
        // Q-7: "Only when asked" reports the held fix; a sync that reports or takes one keeps its own.
        assertEquals(LocationAction.CACHED, withLowBatteryFix(LocationAction.NONE, LowFix.CACHED))
        assertEquals(LocationAction.CACHED, withLowBatteryFix(LocationAction.CACHED, LowFix.CACHED))
        assertEquals(LocationAction.FRESH, withLowBatteryFix(LocationAction.FRESH, LowFix.CACHED))
        assertEquals(LowFix.CACHED, lowFixKind(LOW_FIX_RECENT_MS - 1))
        assertEquals(LowFix.FIX, lowFixKind(LOW_FIX_RECENT_MS))
        assertEquals("no fix held", LowFix.FIX, lowFixKind(null))
        assertEquals("the clock went back", LowFix.FIX, lowFixKind(-1))
    }

    @Test
    fun `the episode ends only once the battery was charged up to the okay level`() {
        assertFalse("discharging, not low", lowEpisodeAfterReading(true, BatteryReport(30, charging = false, low = false)))
        assertTrue("still low", lowEpisodeAfterReading(true, BatteryReport(12, charging = false, low = true)))
        assertTrue("charging, not yet okay", lowEpisodeAfterReading(true, BatteryReport(12, charging = true, low = false)))
        assertTrue("no reading", lowEpisodeAfterReading(true, null))
        assertFalse("none taken", lowEpisodeAfterReading(false, BatteryReport(12, charging = false, low = true)))
        assertFalse(lowEpisodeAfterEdge(okay, true))
        assertTrue(lowEpisodeAfterEdge(low, true))
        assertFalse(lowEpisodeAfterEdge(low, false))
    }

    /** Q-2: the drain guard - Android sends LOW again at every unplug at a low level. */
    @Test
    fun `one fix per episode, however often the edge comes, and again after the battery was okay`() {
        val flag = Flag()
        val episode = LowBatteryEpisode(flag)
        assertEquals("no edge, no fix", LowFix.NONE, episode.takeNow())
        episode.onEdge(low)
        assertEquals(LowFix.FIX, episode.takeNow())
        assertTrue("marked before the fix", flag.value)
        assertEquals("the edge is taken once", LowFix.NONE, episode.takeNow())
        // Plugged in and out at 14 %: a reading while charging, then LOW again - no second fix.
        episode.observe(BatteryReport(14, charging = true, low = true))
        episode.observe(BatteryReport(14, charging = true, low = false))
        episode.onEdge(low)
        assertEquals(LowFix.NONE, episode.takeNow())
        episode.observe(BatteryReport(13, charging = false, low = true))
        episode.onEdge(low)
        assertEquals(LowFix.NONE, episode.takeNow())
        assertTrue(flag.value)
        // OKAY ends it: the next LOW takes one again.
        episode.onEdge(okay)
        assertFalse(flag.value)
        episode.onEdge(low)
        assertEquals(LowFix.FIX, episode.takeNow())
        // A reading discharging above low (OKAY missed while the process was dead) ends it too.
        episode.observe(BatteryReport(40, charging = false, low = false))
        assertFalse(flag.value)
        episode.onEdge(low)
        assertEquals(LowFix.FIX, episode.takeNow())
    }

    @Test
    fun `no fix under Off or with Location off, and the episode stays open for a later edge`() {
        val flag = Flag()
        val episode = LowBatteryEpisode(flag)
        episode.onEdge(low)
        assertEquals(LowFix.NONE, episode.takeNow(policy = off))
        episode.onEdge(low)
        assertEquals(LowFix.NONE, episode.takeNow(policy = null))
        episode.onEdge(low)
        assertEquals(LowFix.NONE, episode.takeNow(location = false))
        assertEquals("nothing marked", 0, flag.writes)
        episode.onEdge(low)
        assertEquals(LowFix.FIX, episode.takeNow())
    }

    /** Q-7: a fix of its own under 2 minutes old is reported, and the episode is still marked. */
    @Test
    fun `a recent own fix is reported instead of a new one`() {
        val flag = Flag()
        val episode = LowBatteryEpisode(flag)
        episode.onEdge(low)
        assertEquals(LowFix.CACHED, episode.takeNow(ownFixAgeMs = 60_000))
        assertTrue(flag.value)
        episode.onEdge(low)
        assertEquals(LowFix.NONE, episode.takeNow(ownFixAgeMs = 60_000))
    }

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(name: String): String = sources.walkTopDown().first { it.name == name }.readText()

    @Test
    fun `the edge asks the sync it requests, which takes the fix and reports it`() {
        // The receiver notes the edge before its sync request.
        val receiver = code("BatteryStatus.kt").substringAfter("override fun onReceive(").substringBefore("\n            }\n        }")
        val edge = receiver.indexOf("LowBatteryFix.onBatteryEdge(app, intent?.action)")
        assertTrue(edge in 0 until receiver.indexOf("SyncRunner.request(app, BATTERY_SYNC_REASON)"))
        // The sync takes the edge before its location step, which turns it into the fix, and every
        // report's reading may end the episode.
        val sync = code("MdmSyncWorker.kt")
        val take = sync.indexOf("val lowBatteryFix = LowBatteryFix.take(")
        assertTrue(take in 0 until sync.indexOf("val location = currentLocationReport("))
        assertTrue("ownFixAgeMs = LocateCommands.cachedQueued()?.let { System.currentTimeMillis() - it.timeMs }," in sync)
        assertTrue("locationPolicy, lowBatteryFix)" in sync)
        assertTrue("val action = withLowBatteryFix(sync.action, lowBatteryFix)" in sync)
        assertTrue("battery = BatteryStatus.report(context).also { LowBatteryFix.observe(context, it) }" in sync)
        // The glue only binds the episode; the flag is written synchronously; no coordinates logged.
        val glue = code("LowBatteryFix.kt")
        assertTrue("if (!edit.commit())" in glue)
        assertFalse(".apply()" in glue)
        assertEquals(3, Regex("""episode\.(onEdge|take|observe)\(""").findAll(glue.substringAfter("object LowBatteryFix")).count())
        assertFalse(Regex("""Log\.[a-z]\(.*(latitude|longitude|lat|lng)""").containsMatchIn(glue))
    }
}
