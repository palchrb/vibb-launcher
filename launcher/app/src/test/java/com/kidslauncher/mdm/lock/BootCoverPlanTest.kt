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
        // A tripped guard keeps the cover off at every shutdown until the switch goes off and on
        // (qa-16b-code #2).
        assertEquals(false, bootCoverEnabled(CoverEvent.SHUTDOWN, wanted = true, guardTripped = true))
        assertEquals(false, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted = true, guardTripped = true))
    }

    @Test
    fun `the second crash in a boot trips the guard, and the trip stays (qa-16b-code 2)`() {
        val first = coverCrashed(null, bootNow = 41)
        assertEquals(CoverRecord(41, 1, tripped = false), first)
        assertFalse(coverGuardTripped(first))
        // One crash per boot never trips: a new boot counts from one.
        assertEquals(CoverRecord(42, 1, tripped = false), coverCrashed(first, 42))
        val second = coverCrashed(first, 41)
        assertEquals(CoverRecord(41, 2, tripped = true), second)
        assertTrue(coverGuardTripped(second))
        assertEquals(2, COVER_MAX_CRASHES)
        // Sticky: the next boot keeps it (only the switch off clears the record).
        assertTrue(coverGuardTripped(coverCrashed(second, 42)))
        assertFalse(coverGuardTripped(null))
        // The shown/handed-over times survive a crash count.
        val shown = CoverRecord(41, 0, shownAtMs = 5L, handedOverAtMs = 6L)
        assertEquals(CoverRecord(41, 1, false, 5L, 6L), coverCrashed(shown, 41))
        // An unreadable boot count is one boot.
        assertTrue(coverGuardTripped(coverCrashed(coverCrashed(null, -1), -1)))
    }

    @Test
    fun `the record round-trips, and an unreadable one keeps the cover off`() {
        for (record in listOf(
            CoverRecord(), CoverRecord(41, 2, true, 1_700L, 1_800L), CoverRecord(7, 1, false, shownAtMs = 3L),
            CoverRecord(7, 0, false, shownAtMs = 3L, shownBootCount = 7, shownElapsedMs = 9_500L),
        )) {
            assertEquals(record, decodeCoverRecord(encodeCoverRecord(record)))
        }
        assertNull("no file", decodeCoverRecord(null))
        for (bad in listOf("", "garbage", "v=2\nboot=1\ncrashes=0\ntripped=false", "v=1\nboot=x\ncrashes=0\ntripped=false", "v=1\nboot=1\ncrashes=0\ntripped=maybe")) {
            assertTrue(bad, coverGuardTripped(decodeCoverRecord(bad)))
        }
    }

    @Test
    fun `hands over once unlocked and shown for 3 s (16e), never before the unlock`() {
        assertNull("BFU: stays", coverHandOverDelayMs(unlocked = false, shownForMs = 60_000))
        assertEquals(0L, coverHandOverDelayMs(unlocked = true, shownForMs = 5_000))
        assertEquals(2_700L, coverHandOverDelayMs(unlocked = true, shownForMs = 300))
        assertEquals("not drawn yet: the whole minimum", COVER_MIN_SHOWN_MS, coverHandOverDelayMs(unlocked = true, shownForMs = -1))
        assertEquals("the boot mark's 3 s, shared with Home's mark", BOOT_MARK_MS, COVER_MIN_SHOWN_MS)
        assertEquals(3_000L, COVER_MIN_SHOWN_MS)
    }

    @Test
    fun `Android's lock implies the cover on a managed phone we own (59 F3), and only then marks android mode`() {
        assertTrue(bootCoverWanted(switchOn = false, managed = true, deviceOwner = true, androidFace = true))
        assertFalse(bootCoverWanted(switchOn = false, managed = false, deviceOwner = true, androidFace = true))
        assertFalse(bootCoverWanted(switchOn = false, managed = true, deviceOwner = false, androidFace = true))
        assertTrue("own mode: the switch as before", bootCoverWanted(switchOn = true, managed = true, deviceOwner = true, androidFace = false))
        assertTrue(bootCoverAndroidMarker(wanted = true, androidFace = true))
        assertFalse("the switch alone keeps 16b's rules", bootCoverAndroidMarker(wanted = true, androidFace = false))
        assertFalse(bootCoverAndroidMarker(wanted = false, androidFace = true))
    }

    @Test
    fun `the locked boot starts the cover only in android mode, before the unlock, with the guard intact`() {
        assertTrue(coverAtLockedBoot(androidMarker = true, unlocked = false, guardTripped = false))
        assertFalse("own mode or the switch alone: 16b's shutdown arm only", coverAtLockedBoot(androidMarker = false, unlocked = false, guardTripped = false))
        assertFalse("already unlocked: our process hands over", coverAtLockedBoot(androidMarker = true, unlocked = true, guardTripped = false))
        assertFalse("a tripped guard keeps it off", coverAtLockedBoot(androidMarker = true, unlocked = false, guardTripped = true))
    }

    @Test
    fun `android mode - the cover leaves only once taken over and covered, and is never stranded (59 F3)`() {
        // BFU: stays, whatever else.
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = false, disabled = true, visible = false, unlockedForMs = 0, disabledForMs = 60_000))
        // Unlocked, our process not there yet: stays - also when stopped (the keyguard back, a call over it).
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, disabled = false, visible = true, unlockedForMs = 12_600, disabledForMs = 0))
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, disabled = false, visible = false, unlockedForMs = 12_600, disabledForMs = 0))
        // Taken over (disabled by our main process before its Home start): leaves when Home is over it ...
        assertEquals(CoverStep.FINISH, coverWaitStep(unlocked = true, disabled = true, visible = false, unlockedForMs = 13_000, disabledForMs = 300))
        // ... and not while still in front (below it may be the stock launcher), until the grace is over.
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, disabled = true, visible = true, unlockedForMs = 13_000, disabledForMs = COVER_TAKEOVER_GRACE_MS - 1))
        assertEquals(CoverStep.FINISH, coverWaitStep(unlocked = true, disabled = true, visible = true, unlockedForMs = 18_000, disabledForMs = COVER_TAKEOVER_GRACE_MS))
        // No take-over: hands over by itself (disable + start our Home) at the wait's end.
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, disabled = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS - 1, disabledForMs = 0))
        assertEquals(CoverStep.HAND_OVER, coverWaitStep(unlocked = true, disabled = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS, disabledForMs = 0))
        assertEquals("longer than the debug build's 12.6 s in the RC run", true, COVER_LAUNCHER_WAIT_MS >= 20_000L)
        assertTrue(COVER_WAIT_POLL_MS in 100L..500L)
    }

    @Test
    fun `the cover's process is recognised by its suffix only`() {
        assertTrue(isBootCoverProcess("me.vibb.launcher:bootcover"))
        assertTrue(isBootCoverProcess("me.vibb.launcher.debug:bootcover"))
        assertFalse(isBootCoverProcess("me.vibb.launcher"))
        assertFalse(isBootCoverProcess(null))
    }
}
