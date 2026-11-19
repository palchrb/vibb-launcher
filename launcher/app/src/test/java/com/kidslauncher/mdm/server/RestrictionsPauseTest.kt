package com.kidslauncher.mdm.server

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class RestrictionsPauseTest {

    private val duration = RESTRICTIONS_PAUSE_DURATION_MS
    private val minute = 60_000L
    private val wall0 = 1_700_000_000_000L
    private val elapsed0 = 5_000_000L
    private val boot = 7
    private val start = WindowStart(untilWallMs = wall0 + duration, elapsedStartMs = elapsed0, bootCount = boot)

    /** [afterMs] of real time later, with the wall clock moved by [wallShiftMs] on top. */
    private fun active(afterMs: Long, wallShiftMs: Long = 0, bootCount: Int = boot, s: WindowStart = start) =
        timedWindowActive(s, wall0 + afterMs + wallShiftMs, elapsed0 + afterMs, bootCount, duration)

    @Test
    fun `active within the window`() {
        assertTrue(active(0))
        assertTrue(active(duration - 1))
    }

    @Test
    fun `expires at the end of the window`() {
        assertFalse(active(duration))
        assertFalse(active(duration + minute))
    }

    /** QA step 1 #4: setting the clock back by less than the remaining time, repeatedly, used to
     * keep the pause going forever. Elapsed time since boot can't be changed. */
    @Test
    fun `setting the clock back a little doesn't extend the window`() {
        assertTrue(active(110 * minute, wallShiftMs = -60 * minute))
        assertFalse(active(duration + 1, wallShiftMs = -60 * minute))
        assertFalse(active(3 * duration, wallShiftMs = -2 * duration - minute))
    }

    @Test
    fun `setting the clock back further than the window ends it`() {
        assertFalse(active(minute, wallShiftMs = -(duration + minute) - minute))
    }

    @Test
    fun `setting the clock forward ends it early`() {
        assertFalse(active(minute, wallShiftMs = duration))
    }

    @Test
    fun `a reboot ends it`() {
        assertFalse(active(minute, bootCount = boot + 1))
        // Elapsed time restarts after a reboot, so it can also be below the start.
        assertFalse(timedWindowActive(start, wall0 + minute, 1_000, boot, duration))
    }

    @Test
    fun `a window from an older build or with an unknown boot count is over`() {
        assertFalse(active(minute, s = WindowStart(0, 0, -1)))
        assertFalse(active(minute, bootCount = -1, s = start.copy(bootCount = -1)))
    }

    // The pause's length is the server's too (QA of pause-end-report M1): it treats a paused report older than it as over.

    private val contract = javaClass.classLoader!!.getResource("restrictions_pause.json")!!.readText()

    @Test
    fun `the pause lasts the shared contract's length, identical to the server's copy`() {
        assertEquals(Json.parseToJsonElement(contract).jsonObject.getValue("pause_max_ms").jsonPrimitive.long, RESTRICTIONS_PAUSE_DURATION_MS)
        val serverCopy = File("../../server/testdata/restrictions_pause.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    // Design 114 §6.1: the setup hold - the same pause with the 4-hour ceiling, pinned by the same contract.

    @Test
    fun `the setup hold lasts the shared contract's ceiling`() {
        assertEquals(Json.parseToJsonElement(contract).jsonObject.getValue("setup_hold_max_ms").jsonPrimitive.long, SETUP_HOLD_DURATION_MS)
        assertEquals(SETUP_HOLD_DURATION_MS, pauseDurationMs(hold = true))
        assertEquals(RESTRICTIONS_PAUSE_DURATION_MS, pauseDurationMs(hold = false))
    }

    @Test
    fun `the hold is on to its ceiling by both clocks, and a boot ends it`() {
        val hold = SETUP_HOLD_DURATION_MS
        val s = WindowStart(untilWallMs = wall0 + hold, elapsedStartMs = elapsed0, bootCount = boot)
        fun on(afterMs: Long, wallShiftMs: Long = 0, bootCount: Int = boot) =
            timedWindowActive(s, wall0 + afterMs + wallShiftMs, elapsed0 + afterMs, bootCount, hold)
        assertTrue(on(3 * 60 * minute + 59 * minute))
        assertFalse(on(4 * 60 * minute))
        assertFalse(on(minute, bootCount = boot + 1))
        // The wall clock set back can't stretch it past the ceiling.
        assertFalse(on(minute, wallShiftMs = -2 * hold))
        // A 2-hour pause's start read with the hold's length would last longer: the flag decides the length.
        assertFalse(timedWindowActive(start, wall0 + duration, elapsed0 + duration, boot, pauseDurationMs(hold = false)))
    }

    @Test
    fun `the hold's time left comes from the elapsed clock`() {
        val s = WindowStart(untilWallMs = wall0 + SETUP_HOLD_DURATION_MS, elapsedStartMs = elapsed0, bootCount = boot)
        assertEquals(SETUP_HOLD_DURATION_MS, holdLeftMs(s, elapsed0))
        assertEquals(SETUP_HOLD_DURATION_MS - 30 * minute, holdLeftMs(s, elapsed0 + 30 * minute))
        assertEquals(0L, holdLeftMs(s, elapsed0 + SETUP_HOLD_DURATION_MS + minute))
    }

    /** QA M6: a PIN pause never inherits the hold's length - `start()` writes the flag false, `startHold()` true, both
     * inside the window's lift before the pause itself, and `clear()` clears it (a source check). */
    @Test
    fun `the hold flag's writes`() {
        val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
        val text = File(main, "java/com/kidslauncher/mdm/server/RestrictionsPause.kt").readText()
        val start = text.substringAfter("fun start(context: Context, start: WindowStart)").substringBefore("fun startHold(")
        assertTrue(start.indexOf("restrictionsPausedHold(false)") in 0 until start.indexOf("restrictionsPaused(true)"))
        val hold = text.substringAfter("fun startHold(context: Context)").substringBefore("fun reapply(")
        // QA M4: the notification grant before the window opens; QA M1: the screen lock unknown.
        assertTrue(hold.indexOf("POST_NOTIFICATIONS") in 0 until hold.indexOf("WindowRelock.opened("))
        assertTrue("WindowRelock.opened(app, WindowKind.PAUSE, start, secureKnown = false)" in hold)
        assertTrue(hold.indexOf("restrictionsPausedHold(true)") in 0 until hold.indexOf("restrictionsPaused(true)"))
        assertTrue("restrictionsPausedHold(false)" in text.substringAfter("fun clear()"))
    }
}
