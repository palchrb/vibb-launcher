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
}
