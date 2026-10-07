package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 16b (QA #7-#10): when the boot cover is on, its crash guard and its hand-over. */
class BootCoverPlanTest {

    @Test
    fun `wanted only with the switch on a managed phone we own`() {
        assertTrue(bootCoverWanted(switchOn = true, managed = true, deviceOwner = true))
        assertFalse("off by default on the server", bootCoverWanted(switchOn = false, managed = true, deviceOwner = true))
        assertFalse(bootCoverWanted(switchOn = true, managed = false, deviceOwner = true))
        assertFalse(bootCoverWanted(switchOn = true, managed = true, deviceOwner = false))
    }

    @Test
    fun `enabled only at shutdown, never while the phone runs, off at every event when not wanted`() {
        assertEquals(true, bootCoverEnabled(CoverEvent.SHUTDOWN, wanted = true))
        assertNull("an apply never enables it - two HOME PPAs while unlocked (QA 8)", bootCoverEnabled(CoverEvent.POLICY, wanted = true))
        assertEquals(false, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted = true))
        assertEquals(false, bootCoverEnabled(CoverEvent.CRASH_GUARD, wanted = true))
        for (event in CoverEvent.entries) {
            assertEquals("switch off: $event", false, bootCoverEnabled(event, wanted = false))
        }
    }

    @Test
    fun `the second crash in a boot trips the guard, a new boot starts over`() {
        val first = coverCrashed(null, bootNow = 41)
        assertEquals(CoverCrashes(41, 1), first)
        assertFalse(coverGuardTripped(first, 41))
        val second = coverCrashed(first, 41)
        assertEquals(CoverCrashes(41, 2), second)
        assertTrue(coverGuardTripped(second, 41))
        assertEquals(2, COVER_MAX_CRASHES)
        // The next boot: the old count doesn't hold, a crash there counts from one.
        assertFalse(coverGuardTripped(second, 42))
        assertEquals(CoverCrashes(42, 1), coverCrashed(second, 42))
        assertFalse(coverGuardTripped(null, 42))
        // An unreadable boot count is one boot: the cover stays off (fail safe, A+B).
        assertTrue(coverGuardTripped(coverCrashed(coverCrashed(null, -1), -1), -1))
    }

    @Test
    fun `hands over once unlocked and shown for 1 s, never before the unlock`() {
        assertNull("BFU: stays", coverHandOverDelayMs(unlocked = false, shownForMs = 60_000))
        assertEquals(0L, coverHandOverDelayMs(unlocked = true, shownForMs = 5_000))
        assertEquals(700L, coverHandOverDelayMs(unlocked = true, shownForMs = 300))
        assertEquals("not drawn yet: the whole minimum", COVER_MIN_SHOWN_MS, coverHandOverDelayMs(unlocked = true, shownForMs = -1))
        assertEquals(1_000L, COVER_MIN_SHOWN_MS)
    }

    @Test
    fun `the cover's process is recognised by its suffix only`() {
        assertTrue(isBootCoverProcess("me.vibb.launcher:bootcover"))
        assertTrue(isBootCoverProcess("me.vibb.launcher.debug:bootcover"))
        assertFalse(isBootCoverProcess("me.vibb.launcher"))
        assertFalse(isBootCoverProcess(null))
    }
}
