package com.kidslauncher.mdm.lock

/*
 * Design 16e: the breathing Vibb mark for 3 s at boot. At the first process start after a boot the
 * PIN lock's first showing waits until the locked Home's mark (16c) has been up for 3 s - counted
 * from the boot cover's first frame when the cover (16b) came first, so the two share one 3 s.
 * The 3 s count from the moment Home's mark is verifiably on screen: Home's first window focus after
 * its locked resume with the night ground drawn (qa-16e-fix2 #1 - on API 34 focus goes only to a
 * drawn, shown window, so the old front is gone), not from the ask: a slow boot doesn't eat them
 * (emulator 2026-10-09). The lock waits for that focus at most the pre-16e [LOCK_FALLBACK_MS] from
 * the wait's start - never longer over the stock launcher or FallbackHome; a slow emulator then
 * shows no mark at all. So the lock comes at most [LOCK_FALLBACK_MS] + [BOOT_MARK_MS] after the
 * wait began. It waits only while Home's mark is up ([homeMarkUp], qa-16e-code #1): Home resumed
 * locked and not covered since - otherwise the lock shows at once, as before 16e; a pause counts
 * only when Home doesn't resume within [HOME_PAUSE_GRACE_MS] (a HOME intent re-delivered to Home
 * pauses and resumes it). Nothing else waits: the LOCKED chrome follows the mode at the process
 * start, before the hold's reads (the wait is only in PinLockRuntime.show), calls, alarms and VoIP
 * skip it at once, every later lock shows at once, and a backstop timer shows the lock at the end.
 * Pure, tested in BootMarkPlanTest.
 */

/** The mark's 3 s at boot: the PIN lock's first showing waits this long from the mark's first
 * moment on screen (16e: Home's first focus with the night ground drawn, or the boot cover's first
 * frame), and the boot cover stays at least this long from its first frame before it hands over
 * (16b) - one 3 s for both. */
const val BOOT_MARK_MS = 3_000L

/**
 * A pause of Home ends the wait only when Home isn't resumed again this soon (16e): the system's own
 * HOME start after boot re-delivers the intent to Home, which pauses and resumes it at once - that
 * ended the mark after 0.1 s (emulator 2026-10-09). A stop or lost focus ends it at once.
 * The blind spot (qa-16e-fix2 #2): a pause that doesn't take Home's focus - a translucent activity,
 * Recents over a Home that never had focus yet - goes unseen for up to this long. Once Home has
 * focus, a focusable window over it takes the focus and a non-focusable top activity leaves none -
 * both a focus loss, at once; before the first focus the wait is in [BootMarkState.Waiting], whose
 * [LOCK_FALLBACK_MS] deadline bounds it anyway. The re-delivered intent's pause is client-side and
 * moves no focus.
 */
const val HOME_PAUSE_GRACE_MS = 300L

/** The `Boot mark over:` reason of the 3 s being up (the smoke test checks the time on screen). */
const val BOOT_MARK_TIME_UP = "the mark's 3 s are up"

/** The `Boot mark over:` reason of Home's mark never shown within [LOCK_FALLBACK_MS] (a safe end). */
const val BOOT_MARK_NEVER_SHOWN = "Home's mark never shown"

/** The wait exists only this soon after the boot (elapsed realtime, qa-16e-code #4): a process
 * started later - the first start of a new build after its update, a restart after a lost write -
 * and a first ask later - a first screen-on hours after a boot in the dark - never wait. */
const val BOOT_MARK_WINDOW_MS = 5 * 60_000L

/**
 * Whether this process's first lock showing waits for the mark: the first process start of a boot -
 * [bootCount] known (`Settings.Global.BOOT_COUNT`; -1 = unknown: never) and not [storedBootCount],
 * the one the last process start stored (synchronously), so a crash restart in the same boot never
 * waits; and [sinceBootMs] (elapsed realtime at the start) inside [BOOT_MARK_WINDOW_MS] - with the
 * lock active and its state readable at the start (unreadable: the lock at once, qa-16c-code #3).
 */
fun bootMarkDue(bootCount: Int, storedBootCount: Int?, lockActive: Boolean, stateReadable: Boolean, sinceBootMs: Long): Boolean =
    lockActive && stateReadable && bootCount >= 0 && bootCount != storedBootCount && sinceBootMs in 0 until BOOT_MARK_WINDOW_MS

/** The boot's wait in this process. */
sealed interface BootMarkState {
    /** The first lock showing will wait; nothing has asked for the lock yet. */
    data object Due : BootMarkState

    /** The wait began (a held ask with Home resumed locked, or the Home-first process start), but
     * Home's mark isn't verifiably on screen yet ([homeMarkShown]): the lock waits for it until
     * [deadlineMs] (elapsed realtime, [LOCK_FALLBACK_MS] after the start) - then the backstop shows
     * it. */
    data class Waiting(val deadlineMs: Long) : BootMarkState

    /** The lock waits until [untilMs] (elapsed realtime) - 3 s from the mark's start; the backstop
     * timer shows it then. */
    data class Holding(val untilMs: Long) : BootMarkState

    /** Nothing waits: not the first start of a boot, or the wait is over. */
    data object Over : BootMarkState
}

/** Who asks for the lock. */
enum class LockAsk {
    /** The boot's lock: the process start, Home's locked resume, a screen-on backstop, the re-front
     * loop - may wait for the mark. */
    BOOT,

    /** Every other ask - a screen-off, the remote lock, a call or VoIP ring starting or ending, a
     * time-rule screen, the update: the lock at once, and the wait is over. */
    AT_ONCE,
}

/** The ask of [event]'s lock showing ([LockStep.showLock]). */
fun lockAsk(event: LockEvent): LockAsk = when (event) {
    is LockEvent.ProcessStart, is LockEvent.ScreenOn -> LockAsk.BOOT
    else -> LockAsk.AT_ONCE
}

/** A call (ours or Telecom's, emergency included), the emergency dialer flow, a ringing system
 * alarm, or an allowed app's VoIP ring or call: the wait is skipped at once. */
fun bootMarkExempt(ourCall: Boolean, telecomCall: Boolean, emergencyFlow: Boolean, alarmRinging: Boolean, voip: VoipPhase): Boolean =
    ourCall || telecomCall || emergencyFlow || alarmRinging || voip != VoipPhase.NONE

/** The boot cover's first frame in boot [bootNow] (elapsed realtime) from its record; `null` when
 * it wasn't shown in this boot or the boot count is unknown. */
fun coverFrameThisBoot(record: CoverRecord?, bootNow: Int): Long? =
    record?.shownElapsedMs?.takeIf { bootNow >= 0 && record.shownBootCount == bootNow }

/** A wait for the lock: it holds the lock now ([BootMarkState.Waiting] or [BootMarkState.Holding]). */
val BootMarkState.holdsLock: Boolean get() = this is BootMarkState.Waiting || this is BootMarkState.Holding

/** When the mark was first on screen this boot (elapsed realtime): the earlier of the boot cover's
 * first frame [coverFrameMs] and Home's mark shown ([homeShownMs], [homeMarkShown]); `null` =
 * neither. */
fun bootMarkStartMs(coverFrameMs: Long?, homeShownMs: Long?): Long? = listOfNotNull(coverFrameMs, homeShownMs).minOrNull()

/** The wait once the mark's start [startMs] is known: until 3 s after it - so a cover shown 3 s
 * already means no wait (not 3 s twice); a start "after" [nowMs] counts as now (never later). */
fun bootMarkHeldFrom(startMs: Long, nowMs: Long): BootMarkState {
    val until = minOf(startMs, nowMs) + BOOT_MARK_MS
    return if (nowMs >= until) BootMarkState.Over else BootMarkState.Holding(until)
}

/** How long the mark has been on screen at [nowMs] (the end of the wait, for the log): from its
 * start ([bootMarkStartMs]); `null` = it was never shown. */
fun bootMarkOnScreenMs(nowMs: Long, coverFrameMs: Long?, homeShownMs: Long?): Long? =
    bootMarkStartMs(coverFrameMs, homeShownMs)?.let { nowMs - it }

/** An ask's result: the next [state], and whether the lock is shown now ([show]). */
data class BootMarkStep(val state: BootMarkState, val show: Boolean)

/**
 * An ask for the lock at [nowMs] (elapsed realtime). Shows the lock, and nothing waits any more, on
 * [BootMarkState.Over], an [LockAsk.AT_ONCE] ask, Home's mark not up ([markUp] false, [homeMarkUp]:
 * Home never resumed locked in this process, or was covered since - the lock at once, as before 16e,
 * also for the Home-first fallback after its 1 s), a first ask outside [BOOT_MARK_WINDOW_MS], the end
 * reached, or an exemption ([bootMarkExempt]). Otherwise a [LockAsk.BOOT] ask holds the lock: with
 * the mark's start known ([bootMarkStartMs]: [coverFrameMs] = the cover's first frame, `null` = none
 * or not read yet; [homeShownMs] = Home's mark shown) until 3 s after it, else
 * [BootMarkState.Waiting] for Home's mark at most [LOCK_FALLBACK_MS] from the first held ask. An end
 * once fixed is never moved later. [exempt] (binder calls) is read last, only when it would hold.
 */
fun bootMarkStep(
    state: BootMarkState,
    ask: LockAsk,
    markUp: Boolean,
    nowMs: Long,
    coverFrameMs: Long?,
    homeShownMs: Long?,
    exempt: () -> Boolean,
): BootMarkStep {
    val over = BootMarkStep(BootMarkState.Over, show = true)
    if (state == BootMarkState.Over || ask == LockAsk.AT_ONCE || !markUp) return over
    val start = bootMarkStartMs(coverFrameMs, homeShownMs)
    val next = when (state) {
        is BootMarkState.Holding -> state
        is BootMarkState.Waiting -> when {
            start != null && start < state.deadlineMs -> bootMarkHeldFrom(start, nowMs)
            nowMs >= state.deadlineMs -> BootMarkState.Over
            else -> state
        }
        BootMarkState.Due, BootMarkState.Over -> {
            if (nowMs !in 0 until BOOT_MARK_WINDOW_MS) return over
            if (start != null) bootMarkHeldFrom(start, nowMs) else BootMarkState.Waiting(nowMs + LOCK_FALLBACK_MS)
        }
    }
    if (next == BootMarkState.Over || (next is BootMarkState.Holding && nowMs >= next.untilMs) || exempt()) return over
    return BootMarkStep(next, show = false)
}

/**
 * Home was started first at the process start (design 16, `showLockLater`): the wait begins now -
 * for Home's mark at most [LOCK_FALLBACK_MS], like the pre-16e fallback; nothing is shown or held
 * here: Home's resume asks with its mark up, and without it the 1 s fallback shows the lock
 * ([bootMarkStep]). Only from [BootMarkState.Due] and inside [BOOT_MARK_WINDOW_MS]; a cover that had
 * its 3 s ends it.
 */
fun bootMarkClock(state: BootMarkState, nowMs: Long, coverFrameMs: Long?, homeShownMs: Long?): BootMarkState {
    if (state != BootMarkState.Due) return state
    if (nowMs !in 0 until BOOT_MARK_WINDOW_MS) return BootMarkState.Over
    val start = bootMarkStartMs(coverFrameMs, homeShownMs)
    return if (start != null) bootMarkHeldFrom(start, nowMs) else BootMarkState.Waiting(nowMs + LOCK_FALLBACK_MS)
}

/**
 * Home's mark was shown at [shownAtMs] ([homeMarkShown], 16e): a wait for it now holds 3 s from the
 * mark's start - Home's, or the cover's first frame when that was earlier ([bootMarkHeldFrom]); shown
 * only after the wait's deadline it's over (the backstop shows the lock). Any other state stays (an
 * end once fixed never moves).
 */
fun bootMarkShown(state: BootMarkState, shownAtMs: Long, coverFrameMs: Long?, nowMs: Long): BootMarkState = when {
    state !is BootMarkState.Waiting -> state
    shownAtMs >= state.deadlineMs && (coverFrameMs == null || coverFrameMs >= state.deadlineMs) -> BootMarkState.Over
    else -> bootMarkHeldFrom(minOf(shownAtMs, coverFrameMs ?: shownAtMs), nowMs)
}

/**
 * Whether Home's mark counts as up for the boot's wait ([bootMarkStep]'s `markUp`): Home's last
 * resume was locked - the night ground, [resumedLocked]; a stop or lost focus since clears it - and
 * Home isn't paused past [HOME_PAUSE_GRACE_MS] without a resume ([homePauseEndsWait]; [pausedAtMs]
 * `null` = no pause).
 */
fun homeMarkUp(resumedLocked: Boolean, pausedAtMs: Long?, resumedAtMs: Long?, nowMs: Long): Boolean =
    resumedLocked && (pausedAtMs == null || !homePauseEndsWait(pausedAtMs, resumedAtMs, nowMs))

/**
 * Whether Home's mark is verifiably on screen, so the 3 s may start (qa-16e-fix2 #1): Home resumed
 * locked ([resumedLocked]), its night ground drawn ([groundDrawn]) and its window focused
 * ([focused]) - on API 34 focus goes only to a drawn, shown window, so the old front (the stock
 * launcher, FallbackHome) is gone. The first time it holds is the mark's start.
 */
fun homeMarkShown(resumedLocked: Boolean, groundDrawn: Boolean, focused: Boolean): Boolean = resumedLocked && groundDrawn && focused

/** Home paused at [pausedAtMs] (not a recreation): the wait ends at [nowMs] only when Home hasn't
 * resumed since ([resumedAtMs], `null` = never) and [HOME_PAUSE_GRACE_MS] have passed. */
fun homePauseEndsWait(pausedAtMs: Long, resumedAtMs: Long?, nowMs: Long): Boolean =
    (resumedAtMs == null || resumedAtMs < pausedAtMs) && nowMs - pausedAtMs >= HOME_PAUSE_GRACE_MS

/**
 * The `Boot mark over:` reason when an ask ends a held wait ([before] = Waiting or Holding; the
 * smoke test sorts on it): an [LockAsk.AT_ONCE] ask, Home's mark not up, the 3 s up
 * ([BOOT_MARK_TIME_UP], also from a cover frame read late), Home's mark never shown
 * ([BOOT_MARK_NEVER_SHOWN]), else an exemption.
 */
fun bootMarkOverWhy(before: BootMarkState, ask: LockAsk, markUp: Boolean, nowMs: Long, startMs: Long?): String = when {
    ask == LockAsk.AT_ONCE -> "AT_ONCE ask"
    !markUp -> "Home's mark not up"
    before is BootMarkState.Holding && nowMs >= before.untilMs -> BOOT_MARK_TIME_UP
    startMs != null && nowMs >= minOf(startMs, nowMs) + BOOT_MARK_MS -> BOOT_MARK_TIME_UP
    before is BootMarkState.Waiting && nowMs >= before.deadlineMs -> BOOT_MARK_NEVER_SHOWN
    else -> "exempt: a call, the emergency flow, an alarm or VoIP"
}

/** What the runtime does when the wait ends without an ask - its backstop timer, Home covered
 * (stopped, unfocused, or paused past [HOME_PAUSE_GRACE_MS]: an app, Recents, the assistant, the
 * power menu, a translucent activity, a call or alarm screen), a call starting. */
enum class BootMarkEnd {
    /** Nothing was held, the mode isn't LOCKED, or the lock is up. */
    NOTHING,

    /** Our call: the lock now, the call screen comes over it (as at a process start in a call). */
    SHOW,

    /** The re-front check: it yields to the system dialer, the emergency flow, an alarm or a VoIP
     * call, leaves a screen-off to the screen-on, and shows the lock otherwise. */
    RECHECK,
}

fun bootMarkEnd(wasHolding: Boolean, locked: Boolean, lockResumed: Boolean, ourCall: Boolean): BootMarkEnd = when {
    !wasHolding || !locked || lockResumed -> BootMarkEnd.NOTHING
    ourCall -> BootMarkEnd.SHOW
    else -> BootMarkEnd.RECHECK
}
