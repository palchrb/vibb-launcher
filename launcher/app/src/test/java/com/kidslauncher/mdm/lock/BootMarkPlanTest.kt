package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 16e: the breathing mark for 3 s at boot - only the first lock showing of a boot's first
 * process start waits, only while Home's mark is up, the LOCKED chrome never does, calls, alarms
 * and VoIP skip it at once, and the lock comes when the 3 s are up whether Home drew or not; the
 * boot cover's minimum is the same 3 s. With the qa-16e-code fixes, and the 3 s counted from the
 * mark's first drawn frame (emulator 2026-10-09).
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
    private val shown = BootMarkStep(BootMarkState.Over, show = true)
    private val runtime by lazy { code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }
    private val home by lazy { code("java/com/kidslauncher/mdm/ui/HomeActivity.kt") }

    @Test
    fun `keyed on the boot count and the boot's first process start`() {
        assertTrue("a new boot", bootMarkDue(bootCount = 12, storedBootCount = 11, lockActive = true, stateReadable = true, sinceBootMs = 40_000L))
        assertTrue("nothing stored yet, right after a boot", bootMarkDue(12, null, lockActive = true, stateReadable = true, sinceBootMs = 40_000L))
        assertFalse("a crash restart in the same boot", bootMarkDue(12, 12, lockActive = true, stateReadable = true, sinceBootMs = 40_000L))
        assertFalse("unknown boot count", bootMarkDue(-1, 11, lockActive = true, stateReadable = true, sinceBootMs = 40_000L))
        assertFalse("unknown boot count, nothing stored", bootMarkDue(-1, null, lockActive = true, stateReadable = true, sinceBootMs = 40_000L))
        assertFalse("no lock", bootMarkDue(12, 11, lockActive = false, stateReadable = true, sinceBootMs = 40_000L))
        assertFalse("unreadable state: the lock at once (qa-16c-code #3)", bootMarkDue(12, 11, lockActive = true, stateReadable = false, sinceBootMs = 40_000L))
        // qa-16e-code #4: the first start of this build after its update (no key yet), a restart
        // after a lost write - both long after the boot - never wait.
        assertFalse("the update to this build", bootMarkDue(12, null, lockActive = true, stateReadable = true, sinceBootMs = 3 * 3_600_000L))
        assertFalse("at the window", bootMarkDue(12, 11, lockActive = true, stateReadable = true, sinceBootMs = BOOT_MARK_WINDOW_MS))
        assertTrue("just inside", bootMarkDue(12, 11, lockActive = true, stateReadable = true, sinceBootMs = BOOT_MARK_WINDOW_MS - 1))
        assertFalse("a clock that makes no sense", bootMarkDue(12, 11, lockActive = true, stateReadable = true, sinceBootMs = -1L))
        assertEquals(5 * 60_000L, BOOT_MARK_WINDOW_MS)
        // The boot count is stored synchronously (no restart finds it missing), once per process,
        // before any screen, with the time since the boot.
        val atStart = body(runtime, "bootMarkAtStart")
        assertTrue(atStart.contains("PinLockStore.swapMarkBoot(context, boot)"))
        assertTrue(atStart.contains("bootMarkDue(boot, stored, startActive, startReadable, SystemClock.elapsedRealtime())"))
        assertTrue(atStart.contains("BootMarkState.Over\n    }"))
        val swap = body(code("java/com/kidslauncher/mdm/lock/PinLockStore.kt"), "swapMarkBoot")
        assertTrue(swap.contains(".commit()"))
        assertFalse(swap.contains(".apply()"))
        val init = body(runtime, "init")
        assertTrue(init.indexOf("decide(app)") in 0 until init.indexOf("bootMark = bootMarkAtStart(app)"))
        assertTrue(init.indexOf("bootMark = bootMarkAtStart(app)") < init.indexOf("handler.post {"))
        assertTrue(body(runtime, "decide").contains("startReadable = false"))
        assertTrue(runtime.contains("private var bootMark: BootMarkState = BootMarkState.Over"))
    }

    @Test
    fun `only the first lock showing of a boot waits - 3 s from the mark's first frame`() {
        // The first held ask (Home's locked resume) comes before Home's first frame: wait for it.
        val first = bootMarkStep(BootMarkState.Due, LockAsk.BOOT, markUp = true, nowMs = 10_000L, coverFrameMs = null, homeFrameMs = null) { false }
        assertEquals(BootMarkStep(BootMarkState.Waiting(13_000L), show = false), first)
        val drawn = bootMarkDrawn(first.state, frameMs = 10_050L, coverFrameMs = null, nowMs = 10_050L)
        assertEquals(BootMarkState.Holding(13_050L), drawn)
        // Later boot asks (the process start after Home's resume, a screen-on, the re-front loop) keep
        // the same end - never later, whatever the cover or another frame says by then.
        val again = bootMarkStep(drawn, LockAsk.BOOT, markUp = true, nowMs = 13_049L, coverFrameMs = 1L, homeFrameMs = 1L) { false }
        assertEquals(BootMarkStep(BootMarkState.Holding(13_050L), show = false), again)
        assertEquals(drawn, bootMarkDrawn(drawn, frameMs = 12_000L, coverFrameMs = null, nowMs = 12_000L))
        assertEquals(shown, bootMarkStep(again.state, LockAsk.BOOT, markUp = true, nowMs = 13_050L, coverFrameMs = null, homeFrameMs = 10_050L, exempt = never))
        // Over: every ask shows at once and nothing is read; a frame changes nothing.
        for (ask in LockAsk.entries) {
            for (up in listOf(true, false)) assertEquals(shown, bootMarkStep(BootMarkState.Over, ask, up, 13_051L, null, null, never))
        }
        assertEquals(BootMarkState.Over, bootMarkDrawn(BootMarkState.Over, 13_051L, null, 13_051L))
        assertEquals(BootMarkState.Due, bootMarkDrawn(BootMarkState.Due, 13_051L, null, 13_051L))
        // A first ask after the window never waits (qa-16e-code #4: a first screen-on hours later).
        assertEquals(shown, bootMarkStep(BootMarkState.Due, LockAsk.BOOT, markUp = true, nowMs = BOOT_MARK_WINDOW_MS, coverFrameMs = null, homeFrameMs = null, exempt = never))
        assertEquals(
            BootMarkState.Waiting(BOOT_MARK_WINDOW_MS - 1 + BOOT_MARK_MS),
            bootMarkStep(BootMarkState.Due, LockAsk.BOOT, markUp = true, nowMs = BOOT_MARK_WINDOW_MS - 1, coverFrameMs = null, homeFrameMs = null) { false }.state,
        )
    }

    @Test
    fun `a slow boot doesn't eat the 3 s - they count from the mark's first drawn frame (emulator 2026-10-09)`() {
        // The run: the Home-first process start 29.591, Home resumed, the 1 s fallback, Home's first
        // frame only at 31.428 - the 3 s run from there, not from the process start.
        val clock = bootMarkClock(BootMarkState.Due, nowMs = 29_591L, coverFrameMs = null, homeFrameMs = null)
        assertEquals(BootMarkState.Waiting(32_591L), clock)
        val resumed = bootMarkStep(clock, LockAsk.BOOT, markUp = true, nowMs = 29_700L, coverFrameMs = null, homeFrameMs = null) { false }
        assertEquals(BootMarkStep(clock, show = false), resumed)
        val fallback = bootMarkStep(clock, LockAsk.BOOT, markUp = true, nowMs = 29_591L + LOCK_FALLBACK_MS, coverFrameMs = null, homeFrameMs = null) { false }
        assertEquals(BootMarkStep(clock, show = false), fallback)
        val held = bootMarkDrawn(clock, frameMs = 31_428L, coverFrameMs = null, nowMs = 31_428L)
        assertEquals(BootMarkState.Holding(34_428L), held)
        assertEquals(BootMarkStep(held, show = false), bootMarkStep(held, LockAsk.BOOT, true, 34_427L, null, 31_428L) { false })
        assertEquals(shown, bootMarkStep(held, LockAsk.BOOT, true, 34_428L, null, 31_428L, never))
        assertEquals(3_000L, bootMarkOnScreenMs(34_428L, coverFrameMs = null, homeFrameMs = 31_428L))
        // The cover drew first: its frame counts (one 3 s for both).
        assertEquals(BootMarkState.Holding(33_000L), bootMarkDrawn(clock, frameMs = 31_428L, coverFrameMs = 30_000L, nowMs = 31_428L))
        assertEquals(4_428L, bootMarkOnScreenMs(34_428L, coverFrameMs = 30_000L, homeFrameMs = 31_428L))
        // A frame already known at the first ask: 3 s from it right away.
        assertEquals(BootMarkState.Holding(13_050L), bootMarkStep(BootMarkState.Due, LockAsk.BOOT, true, 10_100L, null, 10_050L) { false }.state)
        // Home never draws: the lock 3 s after the wait began - never later.
        assertEquals(shown, bootMarkStep(clock, LockAsk.BOOT, markUp = true, nowMs = 32_591L, coverFrameMs = null, homeFrameMs = null, exempt = never))
        assertNull(bootMarkOnScreenMs(32_591L, null, null))
        assertTrue(clock.holdsLock && held.holdsLock && !BootMarkState.Due.holdsLock && !BootMarkState.Over.holdsLock)

        // The runtime: the night ground reports its first drawn frame (an OnDrawListener - only when
        // a frame is really drawn), Home hands it on, and the runtime moves the wait to Holding.
        val ground = code("java/com/kidslauncher/mdm/ui/home/NightGround.kt")
        assertTrue(ground.contains("ViewTreeObserver.OnDrawListener"))
        assertTrue(ground.contains("onFirstFrame(SystemClock.elapsedRealtime())"))
        assertTrue(body(home, "showNight").contains("NightGround(this) { PinLockRuntime.onHomeMarkDrawn(this, it) }"))
        val drawnFn = body(runtime, "onHomeMarkDrawn")
        assertTrue(drawnFn.contains("bootMarkDrawn(before, homeFrameMs ?: frameMs, coverFrameMs, now)"))
        assertTrue(drawnFn.contains("endBootMark(context.applicationContext, "))
        // The measured time on screen is logged at every end; the backstop names a Home that never drew.
        assertTrue(body(runtime, "logBootMarkOver").contains("bootMarkOnScreenMs(now, coverFrameMs, homeFrameMs)"))
        assertTrue(body(runtime, "logBootMarkOver").contains("the mark on screen"))
        assertTrue(runtime.contains("if (bootMark is BootMarkState.Waiting) \"Home's mark never drew\" else \"the mark's 3 s are up\""))
    }

    @Test
    fun `a re-delivered HOME intent's pause doesn't end the wait - a real cover does (emulator 2026-10-09)`() {
        assertEquals(300L, HOME_PAUSE_GRACE_MS)
        // The run: the system's own HOME start at 31.202 paused Home (onNewIntent) and resumed it at once.
        assertFalse("resumed again", homePauseEndsWait(pausedAtMs = 31_383L, resumedAtMs = 31_384L, nowMs = 31_683L))
        assertFalse("resumed at the same ms", homePauseEndsWait(pausedAtMs = 31_383L, resumedAtMs = 31_383L, nowMs = 31_700L))
        // A translucent activity or a dialog: Home stays paused past the grace.
        assertTrue("not resumed since", homePauseEndsWait(pausedAtMs = 31_383L, resumedAtMs = 29_700L, nowMs = 31_683L))
        assertTrue("never resumed", homePauseEndsWait(pausedAtMs = 31_383L, resumedAtMs = null, nowMs = 31_683L))
        assertFalse("the grace isn't over", homePauseEndsWait(pausedAtMs = 31_383L, resumedAtMs = null, nowMs = 31_682L))

        // The runtime: a pause posts the check after the grace; the locked resume (Home's mark up
        // again) records the resume and drops it; a stop or lost focus ends the wait at once.
        assertTrue(body(home, "onPause").contains("PinLockRuntime.onHomePaused(isChangingConfigurations)"))
        assertFalse(body(home, "onPause").contains("onHomeCovered"))
        assertTrue(body(home, "onStop").contains("PinLockRuntime.onHomeCovered(this, isChangingConfigurations, "))
        assertTrue(body(home, "onWindowFocusChanged").contains("if (!hasFocus) PinLockRuntime.onHomeCovered(this, isChangingConfigurations, "))
        val paused = body(runtime, "onHomePaused")
        assertTrue(paused.indexOf("if (changingConfigurations) return") in 0 until paused.indexOf("homePausedAtMs = "))
        assertTrue(paused.contains("handler.postDelayed(homePauseCheck, HOME_PAUSE_GRACE_MS)"))
        val up = body(runtime, "onHomeMarkUp")
        assertTrue(up.contains("homeMarkUp = true"))
        assertTrue(up.contains("homeResumedAtMs = SystemClock.elapsedRealtime()"))
        assertTrue(up.contains("handler.removeCallbacks(homePauseCheck)"))
        val check = runtime.substringAfter("private val homePauseCheck = Runnable {").substringBefore("\n    }")
        assertTrue(check.contains("if (homePauseEndsWait(homePausedAtMs, homeResumedAtMs, SystemClock.elapsedRealtime())) {"))
        assertTrue(check.contains("homeMarkUp = false"))
        assertTrue(check.contains("endBootMark(app, "))
        val covered = body(runtime, "onHomeCovered")
        assertTrue(covered.indexOf("if (changingConfigurations) return") in 0 until covered.indexOf("homeMarkUp = false"))
        assertTrue(covered.contains("endBootMark(context.applicationContext, why)"))
    }

    @Test
    fun `waits only while Home's mark is up - else the lock at once, as before 16e`() {
        // qa-16e-code #1: Home's start failed or it never resumed (the stock launcher or FallbackHome
        // in front), or it was covered since - a boot ask shows at once, exemptions unread.
        for (state in listOf(BootMarkState.Due, BootMarkState.Waiting(13_000L), BootMarkState.Holding(13_000L))) {
            assertEquals(shown, bootMarkStep(state, LockAsk.BOOT, markUp = false, nowMs = 11_000L, coverFrameMs = null, homeFrameMs = 10_100L, exempt = never))
        }
        // Home started first: the wait begins at the process start, nothing is held there; the 1 s
        // fallback holds only with Home's mark up, else shows - never later than before 16e.
        val clock = bootMarkClock(BootMarkState.Due, nowMs = 10_000L, coverFrameMs = null, homeFrameMs = null)
        assertEquals(BootMarkState.Waiting(13_000L), clock)
        assertEquals(shown, bootMarkStep(clock, LockAsk.BOOT, markUp = false, nowMs = 10_000L + LOCK_FALLBACK_MS, coverFrameMs = null, homeFrameMs = null, exempt = never))
        assertEquals(
            BootMarkStep(BootMarkState.Waiting(13_000L), show = false),
            bootMarkStep(clock, LockAsk.BOOT, markUp = true, nowMs = 10_300L, coverFrameMs = null, homeFrameMs = null) { false },
        )
        // The clock only starts from Due, inside the window, and not after a cover's full 3 s.
        assertEquals(BootMarkState.Over, bootMarkClock(BootMarkState.Over, 10_000L, null, null))
        assertEquals(BootMarkState.Holding(12_000L), bootMarkClock(BootMarkState.Holding(12_000L), 11_000L, null, null))
        assertEquals(BootMarkState.Over, bootMarkClock(BootMarkState.Due, BOOT_MARK_WINDOW_MS, null, null))
        assertEquals(BootMarkState.Over, bootMarkClock(BootMarkState.Due, 10_000L, coverFrameMs = 7_000L, homeFrameMs = null))
        assertEquals(BootMarkState.Holding(11_000L), bootMarkClock(BootMarkState.Due, 10_000L, coverFrameMs = 8_000L, homeFrameMs = null))

        // The runtime: only Home's locked resume says the mark is up; a stop or lost focus (not a
        // recreation) takes it down and ends the wait, a pause after the grace (below).
        val resume = body(home, "onResume")
        val locked = resume.substringAfter("if (!gate()) {").substringBefore("\n            return\n        }")
        assertTrue(locked.indexOf("PinLockRuntime.onHomeMarkUp()") in 0 until locked.indexOf("PinLockRuntime.show(this, ask = LockAsk.BOOT)"))
        assertEquals(1, Regex("onHomeMarkUp\\(\\)").findAll(home).count())
        assertTrue(body(runtime, "bootMarkHolds").contains("bootMarkStep(before, ask, homeMarkUp, now, coverFrameMs, homeFrameMs)"))
        assertTrue(body(runtime, "dispatch").substringAfter("if (result.showLockLater) {").substringBefore("}").contains("startBootMarkClock()"))
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
            assertEquals(shown, bootMarkStep(state, LockAsk.AT_ONCE, markUp = true, nowMs = 11_000L, coverFrameMs = null, homeFrameMs = null, exempt = never))
        }
        // A screen-off, the remote lock and a VoIP ring still lock and show as before (the step is unchanged).
        assertTrue(step(LockMode.LOCKED, LockEvent.ScreenOff()).showLock)
        assertTrue(step(LockMode.UNLOCKED, LockEvent.RemoteLock()).showLock)
        assertTrue(step(LockMode.LOCKED, LockEvent.VoipRinging()).showLock)
        assertTrue(body(runtime, "show").contains("bootMarkHolds(context, if (wake) LockAsk.AT_ONCE else ask)"))
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
        for (state in listOf(BootMarkState.Due, BootMarkState.Waiting(13_000L), BootMarkState.Holding(13_000L))) {
            assertEquals(shown, bootMarkStep(state, LockAsk.BOOT, markUp = true, nowMs = 11_000L, coverFrameMs = null, homeFrameMs = null) { true })
        }

        // The runtime: every ask that would hold reads all of them; a call starting, the re-front loop
        // yielding (system dialer, emergency, alarm, VoIP), the lock coming up and a VoIP ring's wake
        // end it.
        val holds = body(runtime, "bootMarkHolds")
        assertTrue(holds.contains("val telecom = telecomInCall(app)"))
        for (input in listOf("ourCall()", "telecom,", "emergencyFlowNow(telecom)", "alarmNow(app)", "VoipCalls.phase")) {
            assertTrue(input, holds.substringAfter("bootMarkExempt(").substringBefore("\n").contains(input))
        }
        assertTrue(runtime.contains("if (ctx != null && !callsSeen && any) endBootMark(ctx, "))
        val refront = body(runtime, "runRefrontCheck")
        assertTrue(refront.substringAfter("is RefrontAction.Yield -> {").substringBefore("}").contains("skipBootMark("))
        assertTrue(body(runtime, "onLockResumed").contains("skipBootMark("))
    }

    @Test
    fun `never without a lock after the 3 s - also when Home never draws`() {
        // Never more than 3 s without the mark drawn, never more than 3 s after its first frame -
        // whatever the cover says (a frame "after" now counts as now).
        for (cover in listOf(null, 9_000L, 10_000L, 11_000L, 50_000L)) {
            val clock = bootMarkClock(BootMarkState.Due, 10_000L, cover, null)
            val end = when (clock) {
                is BootMarkState.Waiting -> clock.frameDeadlineMs
                is BootMarkState.Holding -> clock.untilMs
                else -> 10_000L
            }
            assertTrue("clock, cover $cover", end <= 10_000L + BOOT_MARK_MS)
        }
        for (frame in listOf(10_000L, 11_500L, 12_999L)) {
            val held = bootMarkDrawn(BootMarkState.Waiting(13_000L), frame, null, frame) as BootMarkState.Holding
            assertEquals(frame + BOOT_MARK_MS, held.untilMs)
        }
        assertEquals(BootMarkState.Holding(13_000L), bootMarkHeldFrom(frameMs = 11_000L, nowMs = 10_000L))
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

        // Entering Holding (an ask or the clock) posts the backstop for the end; it runs endBootMark.
        val move = body(runtime, "moveBootMark")
        assertTrue(move.contains("handler.postDelayed(bootMarkBackstop, state.frameDeadlineMs - now)"))
        assertTrue(move.contains("handler.postDelayed(bootMarkBackstop, state.untilMs - now)"))
        assertEquals(3, Regex("handler\\.removeCallbacks\\(bootMarkBackstop\\)").findAll(move).count())
        assertTrue(body(runtime, "bootMarkHolds").contains("moveBootMark(before, next.state, now, "))
        assertTrue(body(runtime, "startBootMarkClock").contains("moveBootMark(before, bootMarkClock(before, now, coverFrameMs, homeFrameMs), now, "))
        val backstop = runtime.substringAfter("private val bootMarkBackstop = Runnable {").substringBefore("\n    }")
        assertTrue(backstop.contains("appContext?.let { endBootMark(it, why) }"))
        val end = body(runtime, "endBootMark")
        assertTrue(end.contains("BootMarkEnd.SHOW -> show(context)"))
        assertTrue(end.contains("runRefrontCheck()"))
        assertTrue(body(runtime, "skipBootMark").contains("bootMark = BootMarkState.Over"))
        // A held ask starts nothing; the re-front loop asks as the boot and keeps going.
        val show = body(runtime, "show")
        assertTrue(show.indexOf("return false") in 0 until show.indexOf("startActivity"))
        assertTrue(body(runtime, "runRefrontCheck").contains("if (show(context, ask = LockAsk.BOOT))"))
    }

    @Test
    fun `the LOCKED chrome never waits - not on the hold's binder or file reads`() {
        // The mode is LOCKED at the process start, and the chrome follows the mode, not the showing.
        assertEquals(LockMode.LOCKED, step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true)).mode)
        assertEquals(LockMode.LOCKED, step(LockMode.DISABLED, LockEvent.ProcessStart(active = true, interactive = true, homeFirst = true)).mode)
        val dispatch = body(runtime, "dispatch")
        // qa-16e-code #3: while the mark may hold, the chrome goes before show (and its reads).
        assertTrue(
            dispatch.contains(
                "val chromeFirst = lockedEdge && bootMark != BootMarkState.Over\n" +
                    "        if (chromeFirst) refreshChromeNow(context)\n" +
                    "        if (result.showLock) show(context, wake = result.wake, ask = lockAsk(event))\n" +
                    "        if (lockedEdge && !chromeFirst) refreshChromeNow(context)",
            ),
        )
        assertTrue(dispatch.contains("if (!lockedEdge) refreshChrome(context)"))
        // Only show holds; the wait touches no chrome.
        assertEquals(2, Regex("bootMarkHolds\\(").findAll(runtime).count())
        for (name in listOf(
            "bootMarkHolds", "startBootMarkClock", "moveBootMark", "skipBootMark", "endBootMark", "bootMarkAtStart",
            "onHomeCovered", "onHomePaused", "onHomeMarkDrawn", "logBootMarkOver",
        )) {
            val fn = body(runtime, name)
            for (chrome in listOf("LockTaskChrome", "refreshChrome", "healStatusBar", "CameraLock")) {
                assertFalse("$name: $chrome", fn.contains(chrome))
            }
        }
        // No file read on an ask: the cover's frame is read once, on an IO thread at init.
        for (name in listOf("bootMarkHolds", "startBootMarkClock", "moveBootMark", "onHomeMarkDrawn")) {
            for (read in listOf("BootCoverGuard", "BootClock")) assertFalse("$name: $read", body(runtime, name).contains(read))
        }
        val init = body(runtime, "init")
        val io = init.substringAfter("if (readCoverFrame) {")
        assertTrue(io.indexOf("CoroutineScope(Dispatchers.IO).launch {") in 0 until io.indexOf("coverFrameThisBoot(BootCoverGuard.read(app), markBoot)"))
        // The 16d heal triggers at the process start are untouched.
        assertTrue(init.contains("healStatusBar(app, HealTrigger.PROCESS_START)"))
    }

    @Test
    fun `the boot cover's minimum is the same 3 s, counted with Home's mark`() {
        assertEquals(BOOT_MARK_MS, COVER_MIN_SHOWN_MS)
        // The cover was up 1 s before the first ask: 2 s more.
        assertEquals(BootMarkState.Holding(12_000L), bootMarkStep(BootMarkState.Due, LockAsk.BOOT, true, 10_000L, coverFrameMs = 9_000L, homeFrameMs = null) { false }.state)
        // The cover had its 3 s already: the lock at once - not 3 s twice.
        assertEquals(shown, bootMarkStep(BootMarkState.Due, LockAsk.BOOT, true, 10_000L, coverFrameMs = 6_000L, homeFrameMs = null) { false })
        // Waiting for Home's frame, the cover's frame read meanwhile counts at the next ask.
        assertEquals(BootMarkState.Holding(12_000L), bootMarkStep(BootMarkState.Waiting(13_000L), LockAsk.BOOT, true, 10_500L, 9_000L, null) { false }.state)
        // A cover frame "after" the ask can't push the end later.
        assertEquals(BootMarkState.Holding(13_000L), bootMarkHeldFrom(11_000L, 10_000L))
        assertEquals(9_000L, bootMarkFrameMs(coverFrameMs = 9_000L, homeFrameMs = 10_050L))
        assertEquals(10_050L, bootMarkFrameMs(coverFrameMs = null, homeFrameMs = 10_050L))
        assertNull(bootMarkFrameMs(null, null))

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

        // The cover counts from the boot's first frame.
        val cover = code("java/com/kidslauncher/mdm/lock/BootCoverActivity.kt")
        assertTrue(cover.contains("BootCoverGuard.markShown(this, shownAtElapsed)?.let { shownAtElapsed = minOf(shownAtElapsed, it) }"))
    }
}
