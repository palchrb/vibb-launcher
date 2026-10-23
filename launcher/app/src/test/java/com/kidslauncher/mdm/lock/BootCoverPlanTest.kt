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
    fun `own mode - enabled only at shutdown, never while the phone runs, off at every event when not wanted`() {
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

    /** Design 59d step M (six-inch F1): in android mode the cover is enabled at every event (a tripped guard too, M-1) - an
     * apply and the take-over included - so a boot the shutdown didn't arm still has a direct-boot-aware HOME. */
    @Test
    fun `android mode - enabled at every event, a tripped guard and the trip itself included, so every boot has a HOME`() {
        for (event in CoverEvent.entries) {
            assertEquals("$event", true, bootCoverEnabled(event, wanted = true, androidMode = true))
            // qa-59d-m-code M-1 (a): a tripped guard makes the cover inert, never disabled - else F1's hang comes back.
            assertEquals("tripped: $event", true, bootCoverEnabled(event, wanted = true, guardTripped = true, androidMode = true))
            assertEquals("not wanted: $event", false, bootCoverEnabled(event, wanted = false, androidMode = true))
        }
        // Own mode: the trip disables it, as before.
        assertEquals(false, bootCoverEnabled(CoverEvent.CRASH_GUARD, wanted = true, guardTripped = true, androidMode = false))
        // Own mode is unchanged by the flag's default.
        assertNull(bootCoverEnabled(CoverEvent.POLICY, wanted = true, androidMode = false))
        assertEquals(false, bootCoverEnabled(CoverEvent.HANDED_OVER, wanted = true, androidMode = false))
    }

    @Test
    fun `step M - the take-over stamp counts only in its own boot, and an unreadable boot count never`() {
        assertTrue(coverTakenOver(stampBoot = 12, bootNow = 12))
        assertFalse("the last boot's stamp", coverTakenOver(stampBoot = 11, bootNow = 12))
        assertFalse("no stamp", coverTakenOver(stampBoot = null, bootNow = 12))
        assertFalse("unreadable boot count", coverTakenOver(stampBoot = -1, bootNow = -1))
    }

    @Test
    fun `step M - a cover started after the take-over starts Home at once, never while stopped or before the unlock`() {
        assertEquals(CoverStep.START_HOME, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 600_000, takenOverForMs = 0, homeStarted = false, callLive = false, lateStart = true))
        assertEquals(CoverStep.STAY, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 600_000, takenOverForMs = 0, homeStarted = false, callLive = false, lateStart = false))
        assertEquals(CoverStep.FINISH, coverWaitStep(true, takenOver = true, visible = false, unlockedForMs = 600_000, takenOverForMs = 0, homeStarted = false, callLive = false, lateStart = true))
        assertEquals(CoverStep.STAY, coverWaitStep(false, takenOver = true, visible = true, unlockedForMs = 0, takenOverForMs = 0, homeStarted = false, callLive = false, lateStart = true))
        // Once started, the grace finishes it as before.
        assertEquals(CoverStep.STAY, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 600_000, takenOverForMs = 100, homeStarted = true, callLive = false, lateStart = true))
    }

    @Test
    fun `step M - its own Home starts disable it first unless the typed HOME is HomeActivity, and only own mode disables on destroy`() {
        assertFalse(coverHomeStartDisablesFirst(resolvesToHome = true))
        assertTrue("the cover first in this boot's PPA order", coverHomeStartDisablesFirst(resolvesToHome = false))
        assertTrue("unreadable", coverHomeStartDisablesFirst(resolvesToHome = null))
        assertTrue(coverDisablesOnDestroy(waitForLauncher = false, unlocked = true))
        assertFalse("android mode stays enabled for the next unarmed boot", coverDisablesOnDestroy(waitForLauncher = true, unlocked = true))
        assertFalse(coverDisablesOnDestroy(waitForLauncher = false, unlocked = false))
        assertEquals(com.kidslauncher.mdm.ui.HomeActivity::class.java.name, HOME_ACTIVITY_CLASS)
    }

    @Test
    fun `step M - the HOME pin is re-seated only unlocked, only when HOME isn't HomeActivity, at most three times`() {
        assertTrue(homePinReseatDue(unlocked = true, resolvesToHome = false, reseats = 0))
        assertTrue(homePinReseatDue(unlocked = true, resolvesToHome = false, reseats = HOME_PIN_MAX_RESEATS - 1))
        assertFalse(homePinReseatDue(unlocked = true, resolvesToHome = false, reseats = HOME_PIN_MAX_RESEATS))
        assertFalse(homePinReseatDue(unlocked = true, resolvesToHome = true, reseats = 0))
        assertFalse("HomeActivity can't resolve before the unlock", homePinReseatDue(unlocked = false, resolvesToHome = false, reseats = 0))
        assertEquals(3, HOME_PIN_MAX_RESEATS)
    }

    /** qa-59d-m-code M-1 (a): the tripped android-mode cover is inert - no face, no ask - and own mode's goes away. */
    @Test
    fun `a tripped guard in android mode leaves an inert cover - never showWhenLocked, never an ask`() {
        assertTrue(coverInert(androidMarker = true, guardTripped = true))
        assertFalse(coverInert(androidMarker = true, guardTripped = false))
        assertFalse("own mode's tripped cover finishes and disables itself", coverInert(androidMarker = false, guardTripped = true))
        assertFalse(bootPadGate(androidMarker = true, unlocked = false, inert = true))
        assertTrue(bootPadGate(androidMarker = true, unlocked = false, inert = false))
        val pad = BootPad()
        for (event in PadEvent.entries) {
            assertEquals("$event", PadAction.NONE, bootPadStep(pad, event, gate = false, interactive = true, inCall = false).action)
        }
    }

    /** qa-59d-m-code M-4: the boot's configuration-change relaunches no longer use up the automatic asks. */
    @Test
    fun `a relaunch is no stop - the ask out is asked again uncounted, two relaunches leave one counted ask`() {
        var pad = bootPadStep(BootPad(), PadEvent.RESUMED, gate = true, interactive = true, inCall = false).also { assertEquals(PadAction.ASK, it.action) }.pad
        assertEquals(1, pad.autoAsks)
        repeat(2) {
            val step = bootPadStep(pad, PadEvent.RELAUNCHED, gate = true, interactive = true, inCall = false)
            assertEquals(PadAction.ASK, step.action)
            pad = step.pad
        }
        assertEquals(1, pad.autoAsks)
        assertTrue(pad.open)
        assertFalse(pad.armed)
        // Nothing out: the resume's rule (an armed cover asks, counted).
        val fresh = bootPadStep(BootPad(autoAsks = 1), PadEvent.RELAUNCHED, gate = true, interactive = true, inCall = false)
        assertEquals(PadAction.ASK, fresh.action)
        assertEquals(2, fresh.pad.autoAsks)
        // Not armed and nothing out (the kid's Back): no new automatic ask, the line.
        val back = bootPadStep(BootPad(autoAsks = 1, armed = false, line = true), PadEvent.RELAUNCHED, gate = true, interactive = true, inCall = false)
        assertEquals(PadAction.NONE, back.action)
        // An ask out with the screen off closes as a stop would, its count given back.
        val dark = bootPadStep(pad, PadEvent.RELAUNCHED, gate = true, interactive = false, inCall = false)
        assertEquals(PadAction.NONE, dark.action)
        assertEquals(0, dark.pad.autoAsks)
        assertTrue(dark.pad.armed)
        // Without the gate nothing.
        assertEquals(PadAction.NONE, bootPadStep(pad, PadEvent.RELAUNCHED, gate = false, interactive = true, inCall = false).action)
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
        assertTrue(bootCoverAndroidMarker(wanted = true, androidGate = true))
        assertFalse("the switch alone keeps 16b's rules", bootCoverAndroidMarker(wanted = true, androidGate = false))
        assertFalse(bootCoverAndroidMarker(wanted = false, androidGate = true))
    }

    private fun lockedBoot(
        androidMarker: Boolean = true,
        unlocked: Boolean = false,
        guardTripped: Boolean = false,
        tookOver: Boolean = false,
        callLive: Boolean = false,
    ) = coverAtLockedBoot(androidMarker, unlocked, guardTripped, tookOver, callLive)

    @Test
    fun `the locked boot starts the cover only in android mode, before the unlock, with the guard intact`() {
        assertEquals(LockedBootCover.START, lockedBoot())
        assertEquals("own mode or the switch alone: 16b's shutdown arm only", LockedBootCover.NONE, lockedBoot(androidMarker = false))
        assertEquals("already unlocked: our process hands over", LockedBootCover.NONE, lockedBoot(unlocked = true))
        assertEquals("a tripped guard keeps it off", LockedBootCover.NONE, lockedBoot(guardTripped = true))
        assertEquals("F3C-6: never after our take-over", LockedBootCover.NONE, lockedBoot(tookOver = true))
    }

    @Test
    fun `F3R-2 - the locked boot starts the cover whatever HOME resolves to, a boot the shutdown armed too`() {
        // No input for the resolution: a graceful boot (armed) and B2's chooser or B6's lost arm all get the start,
        // which brings the cover over whatever the boot started.
        assertEquals(LockedBootCover.START, lockedBoot())
    }

    @Test
    fun `f3-run B1 - decided at the locked boot, a call retried by an alarm, the screen never read`() {
        assertEquals(LockedBootCover.RETRY, lockedBoot(callLive = true))
        assertEquals(LockedBootCover.START, lockedBoot(callLive = false))
        // The NONE reasons win over the retry (nothing left to wait for): the retry chain ends at the unlock.
        assertEquals(LockedBootCover.NONE, lockedBoot(unlocked = true, callLive = true))
        assertEquals(LockedBootCover.NONE, lockedBoot(tookOver = true, callLive = true))
        assertTrue(COVER_LOCKED_BOOT_RETRY_MS in 1_000L..10_000L)
    }

    @Test
    fun `f3-run B2 - destroyed by the system while in front, it starts Home on its way out, in a call too`() {
        // F3R-1: in front when the finish came = no call screen over it; the call is no input.
        assertTrue(coverDestroyStartsHome(waitForLauncher = true, unlocked = true, ownFinish = false, visible = true, homeStarted = false, takenOver = true))
        assertFalse("its own finish", coverDestroyStartsHome(true, true, ownFinish = true, visible = true, homeStarted = false, takenOver = true))
        assertFalse("Home or a call over it", coverDestroyStartsHome(true, true, false, visible = false, homeStarted = false, takenOver = true))
        assertFalse("started already", coverDestroyStartsHome(true, true, false, true, homeStarted = true, takenOver = true))
        assertFalse("before the unlock", coverDestroyStartsHome(true, unlocked = false, ownFinish = false, visible = true, homeStarted = false, takenOver = true))
        assertFalse("own mode", coverDestroyStartsHome(waitForLauncher = false, unlocked = true, ownFinish = false, visible = true, homeStarted = false, takenOver = true))
        assertFalse("RC3-3: before the take-over", coverDestroyStartsHome(true, true, false, true, homeStarted = false, takenOver = false))
    }

    @Test
    fun `f3-run B4 and B5 - the nudge waits past the normal pass, and no hand-over's Home start in a call or while stopped`() {
        assertTrue("longer than the ~1 s normal pass (B4)", COVER_HOME_NUDGE_MS >= 2_000L)
        assertTrue(COVER_HOME_NUDGE_MS < COVER_TAKEOVER_GRACE_MS)
        assertEquals(CoverStep.STAY, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 13_000, takenOverForMs = 1_000, homeStarted = false, callLive = false))
        // B5: no hand-over's Home start in a call, nor while stopped (something over it).
        assertEquals(CoverStep.STAY, coverWaitStep(true, takenOver = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS, takenOverForMs = 0, homeStarted = false, callLive = true))
        assertEquals(CoverStep.STAY, coverWaitStep(true, takenOver = false, visible = false, unlockedForMs = COVER_LAUNCHER_WAIT_MS, takenOverForMs = 0, homeStarted = false, callLive = false))
        // The call over: the hand-over comes.
        assertEquals(CoverStep.HAND_OVER, coverWaitStep(true, takenOver = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS * 3, takenOverForMs = 0, homeStarted = false, callLive = false))
    }

    @Test
    fun `F3R-1 - taken over and in front, the cover starts Home in a call too, and the grace never leaves without it`() {
        // In front (resumed) = no call screen over it: its Home start can't bury one, and BootHome waits for the call.
        assertEquals(CoverStep.START_HOME, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 13_000, takenOverForMs = COVER_HOME_NUDGE_MS, homeStarted = false, callLive = true))
        // The grace with no Home started yet (a tick that came late): Home first, in a call or not ...
        assertEquals(CoverStep.START_HOME, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 20_000, takenOverForMs = COVER_TAKEOVER_GRACE_MS, homeStarted = false, callLive = true))
        assertEquals(CoverStep.START_HOME, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 20_000, takenOverForMs = COVER_TAKEOVER_GRACE_MS * 4, homeStarted = false, callLive = false))
        // ... then it leaves.
        assertEquals(CoverStep.FINISH, coverWaitStep(true, takenOver = true, visible = true, unlockedForMs = 20_000, takenOverForMs = COVER_TAKEOVER_GRACE_MS, homeStarted = true, callLive = true))
        // Stopped (a call screen or Home over it): leaves, never starts Home.
        assertEquals(CoverStep.FINISH, coverWaitStep(true, takenOver = true, visible = false, unlockedForMs = 20_000, takenOverForMs = COVER_TAKEOVER_GRACE_MS, homeStarted = false, callLive = true))
    }

    @Test
    fun `F3C-3 - a tripped guard gets one more try after an update or the switch's off-on edge, in every mode`() {
        val tripped = coverCrashed(coverCrashed(null, 41, versionCode = 36_000_000L), 41, versionCode = 36_000_000L)
        assertTrue(coverGuardTripped(tripped))
        assertEquals(36_000_000L, tripped.trippedVersion)
        assertEquals("a later crash keeps the tripping build", 36_000_000L, coverCrashed(tripped, 42, versionCode = 36_000_001L).trippedVersion)
        assertEquals(tripped, decodeCoverRecord(encodeCoverRecord(tripped)))
        assertFalse("the same build, no edge: stays", coverGuardClearDue(tripped, 36_000_000L, switchWas = false, switchNow = false))
        assertFalse(coverGuardClearDue(tripped, 36_000_000L, switchWas = true, switchNow = true))
        assertTrue("an update", coverGuardClearDue(tripped, 36_000_001L, switchWas = false, switchNow = false))
        assertTrue("the switch off -> on", coverGuardClearDue(tripped, 36_000_000L, switchWas = false, switchNow = true))
        assertTrue("a trip from an older build (no version)", coverGuardClearDue(CoverRecord(tripped = true), 36_000_000L, false, false))
        assertFalse("nothing to clear", coverGuardClearDue(CoverRecord(41, 1), 36_000_001L, false, true))
        assertFalse(coverGuardClearDue(null, 36_000_001L, false, true))
    }

    /** qa-rc-to-rc-code R2R-6: every RC of one version shares a code, so the name counts too. */
    @Test
    fun `a tripped guard gets one more try after an RC over an RC of the same code`() {
        val rc1 = coverCrashed(coverCrashed(null, 41, 35_999L, "0.36.0-rc.1"), 41, 35_999L, "0.36.0-rc.1")
        assertTrue(coverGuardTripped(rc1))
        assertEquals("0.36.0-rc.1", rc1.trippedVersionName)
        assertEquals("a later crash keeps the tripping build", "0.36.0-rc.1", coverCrashed(rc1, 42, 35_999L, "0.36.0-rc.2").trippedVersionName)
        assertEquals(rc1, decodeCoverRecord(encodeCoverRecord(rc1)))
        assertTrue("rc.2 over rc.1", coverGuardClearDue(rc1, 35_999L, false, false, "0.36.0-rc.2"))
        assertFalse("the same build", coverGuardClearDue(rc1, 35_999L, false, false, "0.36.0-rc.1"))
        assertFalse("no name now: by the code", coverGuardClearDue(rc1, 35_999L, false, false, null))
        assertFalse("a trip without a name: by the code", coverGuardClearDue(rc1.copy(trippedVersionName = null), 35_999L, false, false, "0.36.0-rc.2"))
        // An older record (no trip_version_name line) still decodes.
        assertEquals(null, decodeCoverRecord("v=1\nboot=41\ncrashes=2\ntripped=true\ntrip_version=35999\n")?.trippedVersionName)
    }

    @Test
    fun `F3C-7 - its own hand-over starts Home after a few failed disables`() {
        assertTrue(coverHandOverStartsHome(disabledReadBack = true, failedTries = 0))
        assertFalse(coverHandOverStartsHome(disabledReadBack = false, failedTries = COVER_DISABLE_TRIES - 1))
        assertTrue(coverHandOverStartsHome(disabledReadBack = false, failedTries = COVER_DISABLE_TRIES))
        assertTrue(COVER_DISABLE_TRIES * COVER_WAIT_POLL_MS <= 2_000L)
    }

    @Test
    fun `F3C-2 - the boot's Home waits for a call only on the first start, declined, in a call`() {
        assertTrue(bootHomeWaitsForCall(BootHomeAction.NONE, firstStartOfBoot = true, inCall = true))
        assertFalse(bootHomeWaitsForCall(BootHomeAction.NONE, firstStartOfBoot = true, inCall = false))
        assertFalse(bootHomeWaitsForCall(BootHomeAction.NONE, firstStartOfBoot = false, inCall = true))
        assertFalse(bootHomeWaitsForCall(BootHomeAction.START, firstStartOfBoot = true, inCall = true))
        assertFalse(bootHomeWaitsForCall(BootHomeAction.MARK_DONE, firstStartOfBoot = true, inCall = true))
        assertTrue("after the call screen's 1.5 s Ended", CALL_END_HOME_RETRY_MS in 1_600L..5_000L)
    }

    @Test
    fun `android mode - the cover leaves only once taken over and covered, and is never stranded (59 F3)`() {
        // BFU: stays, whatever else.
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = false, takenOver = true, visible = false, unlockedForMs = 0, takenOverForMs = 60_000, homeStarted = false, callLive = false))
        // Unlocked, our process not there yet: stays - also when stopped (the keyguard back, a call over it).
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, takenOver = false, visible = true, unlockedForMs = 12_600, takenOverForMs = 0, homeStarted = false, callLive = false))
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, takenOver = false, visible = false, unlockedForMs = 12_600, takenOverForMs = 0, homeStarted = false, callLive = false))
        // Taken over (our main process's stamp before its Home start - step M; or its own disable): leaves when Home
        // is over it ...
        assertEquals(CoverStep.FINISH, coverWaitStep(unlocked = true, takenOver = true, visible = false, unlockedForMs = 13_000, takenOverForMs = 300, homeStarted = false, callLive = false))
        // ... and not while still in front (below it may be the stock launcher): first it starts Home itself once
        // (F3C-2: a process death between the take-over and Home's start), then the grace.
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, takenOver = true, visible = true, unlockedForMs = 13_000, takenOverForMs = COVER_HOME_NUDGE_MS - 1, homeStarted = false, callLive = false))
        assertEquals(CoverStep.START_HOME, coverWaitStep(unlocked = true, takenOver = true, visible = true, unlockedForMs = 13_000, takenOverForMs = COVER_HOME_NUDGE_MS, homeStarted = false, callLive = false))
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, takenOver = true, visible = true, unlockedForMs = 13_000, takenOverForMs = COVER_TAKEOVER_GRACE_MS - 1, homeStarted = true, callLive = false))
        assertEquals(CoverStep.FINISH, coverWaitStep(unlocked = true, takenOver = true, visible = true, unlockedForMs = 18_000, takenOverForMs = COVER_TAKEOVER_GRACE_MS, homeStarted = true, callLive = false))
        assertTrue(COVER_HOME_NUDGE_MS < COVER_TAKEOVER_GRACE_MS)
        // No take-over: hands over by itself (disable + start our Home) at the wait's end.
        assertEquals(CoverStep.STAY, coverWaitStep(unlocked = true, takenOver = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS - 1, takenOverForMs = 0, homeStarted = false, callLive = false))
        assertEquals(CoverStep.HAND_OVER, coverWaitStep(unlocked = true, takenOver = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS, takenOverForMs = 0, homeStarted = false, callLive = false))
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

    // ---- Design 59d step F: the cover as the BFU face (bootPadGate, bootPadStep) ---------------------------------

    /** Runs [events] from [start] with the screen on, no call and the gate open unless a step says otherwise. */
    private fun pad(start: BootPad = BootPad(), vararg events: PadEvent, interactive: Boolean = true, inCall: Boolean = false): Pair<BootPad, List<PadAction>> {
        var state = start
        val actions = events.map { e -> bootPadStep(state, e, gate = true, interactive = interactive, inCall = inCall).also { state = it.pad }.action }
        return state to actions
    }

    @Test
    fun `the gate is the android marker with the user still locked, and nothing asks without it`() {
        assertTrue(bootPadGate(androidMarker = true, unlocked = false))
        assertFalse("own mode (16b's switch, an Android credential of its own)", bootPadGate(androidMarker = false, unlocked = false))
        assertFalse("never showWhenLocked once isUserUnlocked", bootPadGate(androidMarker = true, unlocked = true))
        val open = BootPad(open = true, retryDue = true)
        for (event in PadEvent.entries) {
            val step = bootPadStep(BootPad(), event, gate = false, interactive = true, inCall = false)
            assertEquals(event.name, PadAction.NONE, step.action)
            assertEquals(event.name, PadStep(BootPad(), PadAction.NONE), step)
            assertEquals(PadAction.NONE, bootPadStep(open, event, gate = false, interactive = true, inCall = false).action)
        }
    }

    @Test
    fun `one automatic ask per resume after a stop - a cancel waits for the next stop`() {
        val (asked, first) = pad(BootPad(), PadEvent.RESUMED)
        assertEquals(listOf(PadAction.ASK), first)
        assertEquals(BootPad(autoAsks = 1, armed = false, open = true, auto = true, silentDue = true), asked)
        // Cancelled (Back): the line, and resumes without a stop never ask again - no loop.
        val (cancelled, again) = pad(asked, PadEvent.CANCELLED, PadEvent.RESUMED, PadEvent.RESUMED)
        assertEquals(listOf(PadAction.NONE, PadAction.NONE, PadAction.NONE), again)
        assertTrue(cancelled.line)
        assertEquals(1, cancelled.autoAsks)
        // A stop arms the next resume.
        assertEquals(listOf(PadAction.NONE, PadAction.ASK), pad(cancelled, PadEvent.STOPPED, PadEvent.RESUMED).second)
        // Not with the screen off, nor with an ask open.
        assertEquals(listOf(PadAction.NONE), pad(BootPad(), PadEvent.RESUMED, interactive = false).second)
        assertEquals(PadAction.NONE, bootPadStep(BootPad(open = true), PadEvent.RESUMED, true, true, false).action)
    }

    @Test
    fun `no automatic ask in a call, the arm kept for the first resume after it - a tap still asks`() {
        val (inCall, actions) = pad(BootPad(), PadEvent.RESUMED, inCall = true)
        assertEquals(listOf(PadAction.NONE), actions)
        assertEquals("the arm kept, the hint shown (F59d-2 a)", BootPad(line = true), inCall)
        assertEquals(listOf(PadAction.ASK), pad(inCall, PadEvent.RESUMED).second)
        val (tapped, tap) = pad(inCall, PadEvent.TAPPED, inCall = true)
        assertEquals(listOf(PadAction.ASK), tap)
        assertEquals("a tap is never counted", 0, tapped.autoAsks)
        assertFalse(tapped.auto)
    }

    @Test
    fun `at most three automatic asks per process, then only taps`() {
        var state = BootPad()
        repeat(BOOT_PAD_AUTO_ASKS) {
            val (next, actions) = pad(state, PadEvent.RESUMED, PadEvent.CANCELLED, PadEvent.STOPPED)
            assertEquals(PadAction.ASK, actions.first())
            state = next
        }
        assertEquals(3, state.autoAsks)
        assertEquals(listOf(PadAction.NONE), pad(state, PadEvent.RESUMED).second)
        assertEquals(listOf(PadAction.ASK), pad(state, PadEvent.TAPPED).second)
        // The count is the process's: a new cover instance carries it in.
        assertEquals(listOf(PadAction.NONE), pad(BootPad(autoAsks = 3), PadEvent.RESUMED).second)
    }

    @Test
    fun `an error is asked again once a second later, the pair counting once`() {
        val (retrying, actions) = pad(BootPad(), PadEvent.RESUMED, PadEvent.ERROR)
        assertEquals(listOf(PadAction.ASK, PadAction.RETRY_LATER), actions)
        assertEquals(1_000L, BOOT_PAD_RETRY_MS)
        assertTrue(retrying.retryDue)
        assertFalse(retrying.line)
        val (second, retry) = pad(retrying, PadEvent.RETRY, PadEvent.ERROR)
        assertEquals(listOf(PadAction.ASK, PadAction.NONE), retry)
        assertEquals("the retry is the same ask", 1, second.autoAsks)
        assertTrue("a second error ends the ask with the line", second.line)
        // The retry needs the screen on, and an automatic ask's retry no call; a tap's goes in a call.
        assertEquals(PadAction.NONE, bootPadStep(retrying, PadEvent.RETRY, gate = true, interactive = false, inCall = false).action)
        assertEquals(PadAction.NONE, bootPadStep(retrying, PadEvent.RETRY, gate = true, interactive = true, inCall = true).action)
        val tapRetry = pad(BootPad(), PadEvent.TAPPED, PadEvent.ERROR).first
        assertEquals(PadAction.ASK, bootPadStep(tapRetry, PadEvent.RETRY, gate = true, interactive = true, inCall = true).action)
        // A stop drops a retry still due (the next resume asks anew).
        assertFalse(pad(retrying, PadEvent.STOPPED).first.retryDue)
        assertEquals(listOf(PadAction.NONE), pad(pad(retrying, PadEvent.STOPPED).first, PadEvent.RETRY).second)
    }

    @Test
    fun `a cancel with the screen off isn't counted - the screen-on's resume asks again`() {
        val asked = pad(BootPad(), PadEvent.RESUMED).first
        // Power with the pad up: the bouncer goes at the sleep, the cover stops.
        val (slept, _) = pad(asked, PadEvent.CANCELLED, PadEvent.STOPPED, interactive = false)
        assertEquals(0, slept.autoAsks)
        assertEquals(listOf(PadAction.ASK), pad(slept, PadEvent.RESUMED).second)
        // The stop first, the cancel after: the stop closes the ask uncounted, the late answer changes nothing.
        val (stopped, _) = pad(asked, PadEvent.STOPPED, PadEvent.CANCELLED, interactive = false)
        assertEquals(BootPad(autoAsks = 0, armed = true, open = false, auto = true, line = true), stopped)
        // A cancel with the screen on counts.
        assertEquals(1, pad(asked, PadEvent.CANCELLED).first.autoAsks)
        // A success closes the ask and hides the line (F59d-3; CE's unlock does the rest); a late success changes nothing.
        assertEquals(asked.copy(open = false, silentDue = false), pad(asked, PadEvent.SUCCEEDED).first)
        assertFalse(pad(asked.copy(line = true), PadEvent.SUCCEEDED).first.line)
        assertEquals(PadStep(BootPad(), PadAction.NONE), bootPadStep(BootPad(), PadEvent.SUCCEEDED, true, true, false))
    }

    @Test
    fun `the face is never left bare with no pad coming (qa-59d-f-code F59d-2)`() {
        // (a) a resume held back by a call, (c) a new instance after the cap: the hint.
        assertTrue(pad(BootPad(), PadEvent.RESUMED, inCall = true).first.line)
        assertTrue(pad(BootPad(autoAsks = BOOT_PAD_AUTO_ASKS), PadEvent.RESUMED).first.line)
        // (b) a stop that closes an open ask with the screen on: its late cancel can't show the line any more.
        val asked = pad(BootPad(), PadEvent.RESUMED).first
        val (stopped, _) = pad(asked, PadEvent.STOPPED, PadEvent.CANCELLED)
        assertTrue(stopped.line)
        // Not while an ask is out, and not before a first resume that asks.
        assertFalse(pad(BootPad(open = true), PadEvent.RESUMED).first.line)
        assertFalse(asked.line)
    }

    @Test
    fun `a tap asks over an ask still marked open, uncounted (F59d-4)`() {
        val step = bootPadStep(BootPad(autoAsks = 1, armed = false, open = true, auto = true), PadEvent.TAPPED, gate = true, interactive = true, inCall = false)
        assertEquals(PadAction.ASK, step.action)
        assertEquals(BootPad(autoAsks = 1, armed = false, open = true, auto = false, silentDue = true), step.pad)
    }

    /** RC3-3 (the rc.3 run's c1: Back, then Home, between the PIN and the take-over - the ANR dialog): the cover starts no
     * Home before our main process's stamp and holds itself in front; the 30 s hand-over stays the rescue. */
    @Test
    fun `no Home start of the cover's before the take-over, Back held until it - the 30 s hand-over still rescues`() {
        for (unlockedFor in listOf(0L, 2_500L, COVER_LAUNCHER_WAIT_MS - 1)) for (visible in listOf(true, false)) for (call in listOf(true, false)) {
            val step = coverWaitStep(true, takenOver = false, visible = visible, unlockedForMs = unlockedFor, takenOverForMs = 0, homeStarted = false, callLive = call, lateStart = true)
            assertEquals("$unlockedFor $visible $call", CoverStep.STAY, step)
        }
        assertFalse(coverDestroyStartsHome(waitForLauncher = true, unlocked = true, ownFinish = false, visible = true, homeStarted = false, takenOver = false))
        // Never stranded: no stamp after the wait, in front and no call -> the hand-over (disable + our Home).
        assertEquals(CoverStep.HAND_OVER, coverWaitStep(true, takenOver = false, visible = true, unlockedForMs = COVER_LAUNCHER_WAIT_MS, takenOverForMs = 0, homeStarted = false, callLive = false))
        // Back: held on the BFU face and, in android mode, from the unlock to the take-over; own mode as before.
        assertTrue(coverHoldsBack(face = true, waitForLauncher = true, unlocked = false, takenOver = false))
        assertTrue(coverHoldsBack(face = false, waitForLauncher = true, unlocked = true, takenOver = false))
        assertFalse("taken over: Back leaves to our Home", coverHoldsBack(face = false, waitForLauncher = true, unlocked = true, takenOver = true))
        assertFalse("the inert cover behind SystemUI's lock", coverHoldsBack(face = false, waitForLauncher = true, unlocked = false, takenOver = false))
        assertFalse("own mode", coverHoldsBack(face = false, waitForLauncher = false, unlocked = true, takenOver = false))
    }

    /** RC3-1 (the rc.3 run, Android 16): an ask SystemUI drops before its keyguard is ready gets no answer at all. */
    @Test
    fun `an ask with no answer is asked again at 3, 6 and 12 s, then every 12 s - uncounted, the same ask`() {
        val asked = bootPadStep(BootPad(), PadEvent.RESUMED, gate = true, interactive = true, inCall = false)
        assertEquals(PadAction.ASK, asked.action)
        var state = asked.pad
        var at = asked.silentInMs!!
        val times = mutableListOf<Long>()
        repeat(5) { n ->
            times += at
            val again = bootPadStep(state, PadEvent.SILENT, gate = true, interactive = true, inCall = false)
            assertEquals("re-ask ${n + 1}", PadAction.ASK, again.action)
            assertEquals("the same ask: uncounted, unarmed, no line", asked.pad.copy(reasks = n + 1), again.pad)
            state = again.pad
            at += again.silentInMs!!
        }
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 36_000L), times)
        // Every ask starts the clock anew: a tap's, a retry's, a relaunch's.
        assertEquals(3_000L, bootPadStep(BootPad(), PadEvent.TAPPED, true, true, false).silentInMs)
        assertEquals(3_000L, bootPadStep(BootPad(retryDue = true, retried = true), PadEvent.RETRY, true, true, false).silentInMs)
        assertEquals(0, bootPadStep(state, PadEvent.RELAUNCHED, true, true, false).pad.reasks)
        // Any answer, a stop or a lost gate ends it: a late silence changes nothing.
        for (end in listOf(PadEvent.SUCCEEDED, PadEvent.CANCELLED, PadEvent.ERROR, PadEvent.STOPPED)) {
            val ended = bootPadStep(state, end, true, true, false).pad
            assertFalse("$end", ended.silentDue)
            assertEquals("$end", PadStep(ended, PadAction.NONE), bootPadStep(ended, PadEvent.SILENT, true, true, false))
        }
        assertFalse(bootPadStep(state, PadEvent.SILENT, gate = false, interactive = true, inCall = false).pad.silentDue)
        // Not with the screen off, nor an automatic ask in a call (the stop that follows closes it); a tap's in a call.
        assertEquals(PadAction.NONE, bootPadStep(asked.pad, PadEvent.SILENT, true, interactive = false, inCall = false).action)
        assertEquals(PadAction.NONE, bootPadStep(asked.pad, PadEvent.SILENT, true, interactive = true, inCall = true).action)
        val tapped = bootPadStep(BootPad(), PadEvent.TAPPED, true, true, false).pad
        assertEquals(PadAction.ASK, bootPadStep(tapped, PadEvent.SILENT, true, interactive = true, inCall = true).action)
    }

    @Test
    fun `the hint line sits under the mark, but above the navigation bar (F59d-6)`() {
        // 569 dp tall at 1 px/dp, 48 dp navigation, 16 dp gap, 156 dp under the centre.
        assertEquals(284 + 156, tapLineTopPx(heightPx = 569, lineHeightPx = 25, insetBottomPx = 48, offsetPx = 156, gapPx = 16))
        // Two lines at font scale 2.0 (~98 dp) move up to stay above the bar.
        assertEquals(569 - 48 - 16 - 98, tapLineTopPx(569, 98, 48, 156, 16))
        assertEquals(0, tapLineTopPx(100, 400, 48, 156, 16))
    }
}
