package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 16e: the breathing mark for 3 s at boot - only the first lock showing of a boot waits,
 * the LOCKED chrome never does, calls, alarms and VoIP skip it at once, and the lock comes when the
 * 3 s are up whether Home drew or not; the boot cover's minimum is the same 3 s.
 */
class BootMarkPlanTest {
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    /** Code only: comments may name what the code must never do. */
    private fun code(path: String) = file(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    /** The body of `fun <name>(` up to the next member at the object's indentation. */
    private fun body(text: String, name: String): String {
        val start = text.indexOf("fun $name(")
        assertTrue("fun $name not found", start >= 0)
        val rest = text.substring(start)
        val end = Regex("\\n    (override |private |internal |fun |val |var |/\\*\\*|companion)").find(rest, 1)?.range?.first ?: rest.length
        return rest.substring(0, end)
    }

    private val never: () -> Boolean = { error("exemptions read when nothing can wait") }
    private val noCover: () -> Long? = { null }

    @Test
    fun `keyed on the boot count - only the first process start of a boot waits`() {
        assertTrue("a new boot", bootMarkDue(bootCount = 12, storedBootCount = 11, lockActive = true, stateReadable = true))
        assertTrue("nothing stored yet", bootMarkDue(12, null, lockActive = true, stateReadable = true))
        assertFalse("a crash restart in the same boot", bootMarkDue(12, 12, lockActive = true, stateReadable = true))
        assertFalse("unknown boot count", bootMarkDue(-1, 11, lockActive = true, stateReadable = true))
        assertFalse("unknown boot count, nothing stored", bootMarkDue(-1, null, lockActive = true, stateReadable = true))
        assertFalse("no lock", bootMarkDue(12, 11, lockActive = false, stateReadable = true))
        assertFalse("unreadable state: the lock at once (qa-16c-code #3)", bootMarkDue(12, 11, lockActive = true, stateReadable = false))
    }

    @Test
    fun `only the first lock showing of a boot waits - 3 s from the first ask`() {
        val first = bootMarkStep(BootMarkState.Due, LockAsk.BOOT, 10_000L, exempt = { false }, coverFrameMs = noCover)
        assertEquals(BootMarkStep(BootMarkState.Holding(13_000L), show = false), first)
        // Later boot asks (the process start after Home's resume, a screen-on, the re-front loop) keep
        // the same end - never later - and the cover isn't read again.
        val again = bootMarkStep(first.state, LockAsk.BOOT, 12_999L, exempt = { false }) { error("cover read twice") }
        assertEquals(BootMarkStep(BootMarkState.Holding(13_000L), show = false), again)
        assertEquals(BootMarkStep(BootMarkState.Over, show = true), bootMarkStep(again.state, LockAsk.BOOT, 13_000L, { false }, noCover))
        // Over: every ask shows at once and nothing is read.
        for (ask in LockAsk.entries) {
            assertEquals(BootMarkStep(BootMarkState.Over, show = true), bootMarkStep(BootMarkState.Over, ask, 13_001L, never) { error("cover") })
        }
    }

    @Test
    fun `every other lock shows at once and ends the wait`() {
        val boot = listOf(
            LockEvent.ProcessStart(active = true, interactive = true),
            LockEvent.ScreenOn(lockShowing = false),
        )
        val atOnce = listOf(
            LockEvent.ScreenOff(), LockEvent.RemoteLock(), LockEvent.CallsEnded(), LockEvent.VoipRinging(), LockEvent.VoipEnded(),
            LockEvent.TimeRuleShown, LockEvent.Configured(true), LockEvent.Unlocked, LockEvent.LockResumed(ourCall = false),
        )
        for (event in boot) assertEquals("$event", LockAsk.BOOT, lockAsk(event))
        for (event in atOnce) assertEquals("$event", LockAsk.AT_ONCE, lockAsk(event))
        for (state in listOf(BootMarkState.Due, BootMarkState.Holding(13_000L))) {
            assertEquals(BootMarkStep(BootMarkState.Over, show = true), bootMarkStep(state, LockAsk.AT_ONCE, 11_000L, never, noCover))
        }
        // A screen-off, the remote lock and a VoIP ring still lock and show as before (the step is unchanged).
        assertTrue(step(LockMode.LOCKED, LockEvent.ScreenOff()).showLock)
        assertTrue(step(LockMode.UNLOCKED, LockEvent.RemoteLock()).showLock)
        assertTrue(step(LockMode.LOCKED, LockEvent.VoipRinging()).showLock)
    }

    @Test
    fun `calls, alarms and VoIP skip the wait at once`() {
        assertFalse(bootMarkExempt(ourCall = false, telecomCall = false, emergencyFlow = false, alarmRinging = false, voip = VoipPhase.NONE))
        val exempt = listOf(
            bootMarkExempt(ourCall = true, telecomCall = false, emergencyFlow = false, alarmRinging = false, voip = VoipPhase.NONE),
            bootMarkExempt(ourCall = false, telecomCall = true, emergencyFlow = false, alarmRinging = false, voip = VoipPhase.NONE),
            bootMarkExempt(ourCall = false, telecomCall = false, emergencyFlow = true, alarmRinging = false, voip = VoipPhase.NONE),
            bootMarkExempt(ourCall = false, telecomCall = false, emergencyFlow = false, alarmRinging = true, voip = VoipPhase.NONE),
            bootMarkExempt(ourCall = false, telecomCall = false, emergencyFlow = false, alarmRinging = false, voip = VoipPhase.RINGING),
            bootMarkExempt(ourCall = false, telecomCall = false, emergencyFlow = false, alarmRinging = false, voip = VoipPhase.IN_CALL),
        )
        assertTrue(exempt.all { it })
        for (state in listOf(BootMarkState.Due, BootMarkState.Holding(13_000L))) {
            assertEquals(BootMarkStep(BootMarkState.Over, show = true), bootMarkStep(state, LockAsk.BOOT, 11_000L, { true }, noCover))
        }

        // The runtime: every ask while something may wait reads all of them; a call starting, the
        // re-front loop yielding (system dialer, emergency, alarm, VoIP) and a VoIP ring's wake end it.
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val holds = body(runtime, "bootMarkHolds")
        assertTrue(holds.contains("val telecom = telecomInCall(app)"))
        for (input in listOf("ourCall()", "telecom,", "emergencyFlowNow(telecom)", "alarmNow(app)", "VoipCalls.phase")) {
            assertTrue(input, holds.substringAfter("bootMarkExempt(").substringBefore("\n").contains(input))
        }
        assertTrue(runtime.contains("if (ctx != null && !callsSeen && any) endBootMark(ctx, "))
        val refront = body(runtime, "runRefrontCheck")
        assertTrue(refront.substringAfter("is RefrontAction.Yield -> {").substringBefore("}").contains("skipBootMark("))
        assertTrue(body(runtime, "show").contains("bootMarkHolds(context, if (wake) LockAsk.AT_ONCE else ask)"))
        // Home covered by anything (an app, Recents, a call or alarm screen) ends it too.
        val home = code("java/com/kidslauncher/mdm/ui/HomeActivity.kt")
        assertTrue(body(home, "onStop").contains("PinLockRuntime.onHomeStopped(this, isChangingConfigurations)"))
        assertTrue(body(runtime, "onHomeStopped").contains("if (!changingConfigurations) endBootMark("))
        assertTrue(body(runtime, "onLockResumed").contains("skipBootMark("))
    }

    @Test
    fun `never without a lock after the 3 s - also when Home never draws`() {
        // The end is fixed by the first ask: never more than 3 s after it, whatever the cover says.
        for (cover in listOf(null, 9_000L, 10_000L, 11_000L, 50_000L)) {
            assertTrue("cover $cover", bootMarkUntilMs(10_000L, cover) <= 10_000L + BOOT_MARK_MS)
        }
        assertEquals(3_000L, BOOT_MARK_MS)
        // When the 3 s are up without an ask (the backstop, Home covered, a call): the lock comes.
        assertEquals(BootMarkEnd.RECHECK, bootMarkEnd(wasHolding = true, locked = true, lockResumed = false, ourCall = false))
        assertEquals("our call: the lock under the call screen", BootMarkEnd.SHOW, bootMarkEnd(true, locked = true, lockResumed = false, ourCall = true))
        assertEquals(BootMarkEnd.NOTHING, bootMarkEnd(wasHolding = false, locked = true, lockResumed = false, ourCall = false))
        assertEquals(BootMarkEnd.NOTHING, bootMarkEnd(wasHolding = true, locked = false, lockResumed = false, ourCall = false))
        assertEquals(BootMarkEnd.NOTHING, bootMarkEnd(wasHolding = true, locked = true, lockResumed = true, ourCall = true))
        // The re-front check it hands to shows the lock unless an exempt screen is up or the screen
        // is off (then the screen-on shows it - nothing waits any more).
        val inputs = RefrontInputs(
            locked = true, lockResumed = false, interactive = true, ourCall = false, telecomInCall = false,
            emergencyFlow = false, alarmRinging = false,
        )
        assertTrue(refrontAction(inputs, 0) is RefrontAction.Refront)

        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        // Entering Holding posts the backstop for the end; it runs endBootMark.
        val holds = body(runtime, "bootMarkHolds")
        assertTrue(holds.contains("if (state is BootMarkState.Holding && before !is BootMarkState.Holding) {"))
        assertTrue(holds.contains("handler.postDelayed(bootMarkBackstop, state.untilMs - now)"))
        assertTrue(runtime.contains("private val bootMarkBackstop = Runnable { appContext?.let { endBootMark(it, "))
        val end = body(runtime, "endBootMark")
        assertTrue(end.contains("BootMarkEnd.SHOW -> show(context)"))
        assertTrue(end.contains("runRefrontCheck()"))
        // The backstop is only taken away when nothing waits any more.
        assertEquals(2, Regex("handler\\.removeCallbacks\\(bootMarkBackstop\\)").findAll(holds).count())
        assertTrue(body(runtime, "skipBootMark").contains("bootMark = BootMarkState.Over"))
        // Home started first: the 3 s run from the process start, not from a Home frame that may never come.
        val dispatch = body(runtime, "dispatch")
        val later = dispatch.substringAfter("if (result.showLockLater) {").substringBefore("}")
        assertTrue(later.contains("bootMarkHolds(context, LockAsk.BOOT)"))
        // A held ask starts nothing; the re-front loop asks as the boot and keeps going.
        val show = body(runtime, "show")
        assertTrue(show.indexOf("return false") in 0 until show.indexOf("startActivity"))
        assertTrue(body(runtime, "runRefrontCheck").contains("if (show(context, ask = LockAsk.BOOT))"))
    }

    @Test
    fun `the LOCKED chrome never waits`() {
        // The mode is LOCKED at the process start, and the chrome follows the mode, not the showing.
        assertEquals(LockMode.LOCKED, step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true)).mode)
        assertEquals(LockMode.LOCKED, step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, homeFirst = true)).mode)
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val dispatch = body(runtime, "dispatch")
        assertTrue(dispatch.contains("if (result.showLock) show(context, wake = result.wake, ask = lockAsk(event))\n        if (lockedEdge) refreshChromeNow(context)"))
        assertTrue(dispatch.contains("if (!lockedEdge) refreshChrome(context)"))
        // Only show holds (and the Home-first start begins the clock); the wait touches no chrome.
        assertEquals(3, Regex("bootMarkHolds\\(").findAll(runtime).count())
        for (name in listOf("bootMarkHolds", "skipBootMark", "endBootMark", "bootMarkAtStart")) {
            val fn = body(runtime, name)
            for (chrome in listOf("LockTaskChrome", "refreshChrome", "healStatusBar", "CameraLock")) {
                assertFalse("$name: $chrome", fn.contains(chrome))
            }
        }
        // The 16d heal triggers at the process start are untouched.
        assertTrue(body(runtime, "init").contains("healStatusBar(app, HealTrigger.PROCESS_START)"))
    }

    @Test
    fun `the boot cover's minimum is the same 3 s, counted with Home's mark`() {
        assertEquals(BOOT_MARK_MS, COVER_MIN_SHOWN_MS)
        // The cover was up 1 s before the first ask: 2 s more.
        assertEquals(BootMarkState.Holding(12_000L), bootMarkStep(BootMarkState.Due, LockAsk.BOOT, 10_000L, { false }) { 9_000L }.state)
        // The cover had its 3 s already: the lock at once - not 3 s twice.
        assertEquals(BootMarkStep(BootMarkState.Over, show = true), bootMarkStep(BootMarkState.Due, LockAsk.BOOT, 10_000L, { false }) { 6_000L })
        // A cover frame "after" the ask can't push the end later.
        assertEquals(13_000L, bootMarkUntilMs(10_000L, 11_000L))

        // Only a frame of this boot counts.
        val record = CoverRecord(bootCount = 12, shownBootCount = 12, shownElapsedMs = 9_000L)
        assertEquals(9_000L, coverFrameThisBoot(record, 12))
        assertNull("an earlier boot", coverFrameThisBoot(record, 13))
        assertNull("unknown boot count", coverFrameThisBoot(record.copy(shownBootCount = -1), -1))
        assertNull(coverFrameThisBoot(null, 12))
        assertNull("an unreadable record", coverFrameThisBoot(decodeCoverRecord("garbage"), 12))
        // The boot's first frame is kept over a recreation; a new boot starts over; the report's time moves on.
        val first = coverShown(CoverRecord(bootCount = 12), bootNow = 13, wallMs = 100L, elapsedMs = 8_000L)
        assertEquals(CoverRecord(bootCount = 12, shownAtMs = 100L, shownBootCount = 13, shownElapsedMs = 8_000L), first)
        assertEquals(CoverRecord(bootCount = 12, shownAtMs = 200L, shownBootCount = 13, shownElapsedMs = 8_000L), coverShown(first, 13, 200L, 9_500L))
        assertEquals(CoverRecord(bootCount = 12, shownAtMs = 300L, shownBootCount = 14, shownElapsedMs = 4_000L), coverShown(first, 14, 300L, 4_000L))
        // An older record (no frame fields) still reads, with no frame.
        assertEquals(CoverRecord(41, 0, false, 5L), decodeCoverRecord("v=1\nboot=41\ncrashes=0\ntripped=false\nshown=5\n"))

        // The cover counts from the boot's first frame; the runtime reads it only for the first ask.
        val cover = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertTrue(cover.contains("BootCoverGuard.markShown(this, shownAtElapsed)?.let { shownAtElapsed = minOf(shownAtElapsed, it) }"))
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        assertTrue(body(runtime, "bootMarkHolds").contains("coverFrameMs = { coverFrameThisBoot(BootCoverGuard.read(app), BootClock.bootCount()) }"))
    }

    @Test
    fun `the boot count is checked and stored once per process, before any screen`() {
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val init = body(runtime, "init")
        assertTrue(init.indexOf("decide(app)") in 0 until init.indexOf("bootMark = bootMarkAtStart(app)"))
        assertTrue(init.indexOf("bootMark = bootMarkAtStart(app)") < init.indexOf("handler.post {"))
        val atStart = body(runtime, "bootMarkAtStart")
        assertTrue(atStart.contains("PinLockStore.swapMarkBoot(context, boot)"))
        assertTrue(atStart.contains("bootMarkDue(boot, stored, startActive, startReadable)"))
        assertTrue(atStart.contains("BootMarkState.Over\n    }"))
        // Unreadable lock state: no wait.
        assertTrue(body(runtime, "decide").contains("startReadable = false"))
        // Starts with nothing waiting (a process that never ran init holds nothing).
        assertTrue(runtime.contains("private var bootMark: BootMarkState = BootMarkState.Over"))
    }
}
