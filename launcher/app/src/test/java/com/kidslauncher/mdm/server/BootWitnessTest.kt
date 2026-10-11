package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 24b §3 (H4): boots the launcher didn't see, from `BOOT_COUNT` jumps. */
class BootWitnessTest {

    @Test
    fun `the first run of this build counts nothing`() {
        assertEquals(BootWitnessState(lastBoot = 57), witnessBoot(BootWitnessState(), 57, 1_000L))
    }

    @Test
    fun `a jump of one counts nothing, a jump of three counts two`() {
        val start = BootWitnessState(lastBoot = 10)
        assertEquals(BootWitnessState(lastBoot = 11), witnessBoot(start, 11, 1_000L))
        // The same boot (the process restarted): nothing either.
        assertEquals(start, witnessBoot(start, 10, 1_000L))
        assertEquals(BootWitnessState(lastBoot = 13, unseen = 2, lastAtMs = 2_000L), witnessBoot(start, 13, 2_000L))
    }

    @Test
    fun `only the first start in a boot is a boot - a crash or update restart isn't (QA Low 6)`() {
        assertTrue(isNewBoot(lastBoot = 10, bootCount = 11))
        assertTrue(isNewBoot(lastBoot = 10, bootCount = 13))
        assertFalse(isNewBoot(lastBoot = 10, bootCount = 10))
        // Unknown: a boot (Home is then left where it is, as before).
        assertTrue(isNewBoot(lastBoot = null, bootCount = 10))
        assertTrue(isNewBoot(lastBoot = 10, bootCount = -1))
    }

    @Test
    fun `an unknown boot count is skipped`() {
        val start = BootWitnessState(lastBoot = 10, unseen = 1, lastAtMs = 5L)
        assertEquals(start, witnessBoot(start, -1, 9_000L))
        assertEquals(BootWitnessState(), witnessBoot(BootWitnessState(), -1, 9_000L))
    }

    @Test
    fun `the count never goes down`() {
        var state = BootWitnessState(lastBoot = 10, unseen = 4, lastAtMs = 5L)
        for (boot in listOf(3, 3, 4, 12, 2, 2)) {
            val next = witnessBoot(state, boot, 7_000L)
            assertTrue("$state -> $next", next.unseen >= state.unseen)
            state = next
        }
        // 10 -> 3 (back: nothing), 3 -> 4, 4 -> 12 (7 unseen), 12 -> 2 (back), 2 -> 2.
        assertEquals(BootWitnessState(lastBoot = 2, unseen = 11, lastAtMs = 7_000L), state)
    }
}
