package com.kidslauncher.mdm.lock

/*
 * Design 16e: the breathing Vibb mark for 3 s at boot. At the first process start after a boot the
 * PIN lock's first showing waits until the locked Home's mark (16c) has been up for 3 s - counted
 * from the boot cover's first frame when the cover (16b) came first, so the two share one 3 s.
 * The 3 s count from the mark's first drawn frame, so a slow boot doesn't eat them (emulator
 * 2026-10-09: Home drew 1.8 s after its resume); until that frame the lock waits at most 3 s for it.
 * It waits only while Home's mark is up (qa-16e-code #1): Home resumed locked and not covered since -
 * otherwise the lock shows at once, as before 16e; a pause counts only when Home doesn't resume
 * within [HOME_PAUSE_GRACE_MS] (a HOME intent re-delivered to Home pauses and resumes it). Nothing
 * else waits: the LOCKED chrome follows the mode at the process start, before the hold's reads
 * (the wait is only in PinLockRuntime.show), calls, alarms and VoIP skip it at once, every later
 * lock shows at once, and a backstop timer shows the lock at the end.
 * Pure, tested in BootMarkPlanTest.
 */

/** The mark's 3 s at boot: the PIN lock's first showing waits this long from the mark's first frame
 * (16e), and the boot cover stays at least this long from its first frame before it hands over
 * (16b) - one 3 s for both. Also the longest wait for that first frame: Home never drawing shows
 * the lock 3 s after the wait began. */
const val BOOT_MARK_MS = 3_000L

/** A pause of Home ends the wait only when Home isn't resumed again this soon (16e): the system's
 * own HOME start after boot re-delivers the intent to Home, which pauses and resumes it at once -
 * that ended the mark after 0.1 s (emulator 2026-10-09). A stop or lost focus ends it at once. */
const val HOME_PAUSE_GRACE_MS = 300L

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

    /** The wait began (a held ask with Home's mark up, or the Home-first process start), but no
     * frame of the mark is drawn yet: the lock waits for it until [frameDeadlineMs] (elapsed
     * realtime) - then the backstop shows it, Home never drew. */
    data class Waiting(val frameDeadlineMs: Long) : BootMarkState

    /** The lock waits until [untilMs] (elapsed realtime) - 3 s from the mark's first frame; the
     * backstop timer shows it then. */
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

/** The mark's first frame this boot (elapsed realtime): the earlier of the boot cover's
 * [coverFrameMs] and Home's night ground's [homeFrameMs]; `null` = neither has drawn. */
fun bootMarkFrameMs(coverFrameMs: Long?, homeFrameMs: Long?): Long? = listOfNotNull(coverFrameMs, homeFrameMs).minOrNull()

/** The wait once the mark's first frame [frameMs] is known: until 3 s after it - so a cover shown
 * 3 s already means no wait (not 3 s twice); a frame "after" [nowMs] counts as now (never later). */
fun bootMarkHeldFrom(frameMs: Long, nowMs: Long): BootMarkState {
    val until = minOf(frameMs, nowMs) + BOOT_MARK_MS
    return if (nowMs >= until) BootMarkState.Over else BootMarkState.Holding(until)
}

/** How long the mark has been on screen at [nowMs] (the end of the wait, for the log): from its
 * first frame; `null` = it never drew. */
fun bootMarkOnScreenMs(nowMs: Long, coverFrameMs: Long?, homeFrameMs: Long?): Long? =
    bootMarkFrameMs(coverFrameMs, homeFrameMs)?.let { nowMs - it }

/** An ask's result: the next [state], and whether the lock is shown now ([show]). */
data class BootMarkStep(val state: BootMarkState, val show: Boolean)

/**
 * An ask for the lock at [nowMs] (elapsed realtime). Shows the lock, and nothing waits any more, on
 * [BootMarkState.Over], an [LockAsk.AT_ONCE] ask, Home's mark not up ([markUp] false: Home never
 * resumed locked in this process, or was covered since - the lock at once, as before 16e, also for
 * the Home-first fallback after its 1 s), a first ask outside [BOOT_MARK_WINDOW_MS], the end
 * reached, or an exemption ([bootMarkExempt]). Otherwise a [LockAsk.BOOT] ask holds the lock: with
 * the mark's first frame known ([bootMarkFrameMs]: [coverFrameMs] = the cover's, `null` = none or
 * not read yet; [homeFrameMs] = Home's night ground's) until 3 s after it, else
 * [BootMarkState.Waiting] for that frame at most 3 s from the first held ask. An end once fixed is
 * never moved later. [exempt] (binder calls) is read last, only when it would hold.
 */
fun bootMarkStep(
    state: BootMarkState,
    ask: LockAsk,
    markUp: Boolean,
    nowMs: Long,
    coverFrameMs: Long?,
    homeFrameMs: Long?,
    exempt: () -> Boolean,
): BootMarkStep {
    val over = BootMarkStep(BootMarkState.Over, show = true)
    if (state == BootMarkState.Over || ask == LockAsk.AT_ONCE || !markUp) return over
    val frame = bootMarkFrameMs(coverFrameMs, homeFrameMs)
    val next = when (state) {
        is BootMarkState.Holding -> state
        is BootMarkState.Waiting -> when {
            frame != null -> bootMarkHeldFrom(frame, nowMs)
            nowMs >= state.frameDeadlineMs -> BootMarkState.Over
            else -> state
        }
        BootMarkState.Due, BootMarkState.Over -> {
            if (nowMs !in 0 until BOOT_MARK_WINDOW_MS) return over
            if (frame != null) bootMarkHeldFrom(frame, nowMs) else BootMarkState.Waiting(nowMs + BOOT_MARK_MS)
        }
    }
    if (next == BootMarkState.Over || (next is BootMarkState.Holding && nowMs >= next.untilMs) || exempt()) return over
    return BootMarkStep(next, show = false)
}

/**
 * Home was started first at the process start (design 16, `showLockLater`): the wait begins now,
 * for the mark's first frame at most 3 s - whether Home ever draws or not; nothing is shown or held
 * here: Home's resume asks with its mark up, and without it the 1 s fallback shows the lock
 * ([bootMarkStep]). Only from [BootMarkState.Due] and inside [BOOT_MARK_WINDOW_MS]; a cover that had
 * its 3 s ends it.
 */
fun bootMarkClock(state: BootMarkState, nowMs: Long, coverFrameMs: Long?, homeFrameMs: Long?): BootMarkState {
    if (state != BootMarkState.Due) return state
    if (nowMs !in 0 until BOOT_MARK_WINDOW_MS) return BootMarkState.Over
    val frame = bootMarkFrameMs(coverFrameMs, homeFrameMs)
    return if (frame != null) bootMarkHeldFrom(frame, nowMs) else BootMarkState.Waiting(nowMs + BOOT_MARK_MS)
}

/**
 * Home's night ground drew its first frame at [frameMs] (16e): a wait for it now holds 3 s from the
 * mark's first frame - Home's, or the cover's when that was earlier ([bootMarkHeldFrom]). Any other
 * state stays (an end once fixed never moves).
 */
fun bootMarkDrawn(state: BootMarkState, frameMs: Long, coverFrameMs: Long?, nowMs: Long): BootMarkState =
    if (state is BootMarkState.Waiting) bootMarkHeldFrom(minOf(frameMs, coverFrameMs ?: frameMs), nowMs) else state

/** Home paused at [pausedAtMs] (not a recreation): the wait ends at [nowMs] only when Home hasn't
 * resumed since ([resumedAtMs], `null` = never) and [HOME_PAUSE_GRACE_MS] have passed. */
fun homePauseEndsWait(pausedAtMs: Long, resumedAtMs: Long?, nowMs: Long): Boolean =
    (resumedAtMs == null || resumedAtMs < pausedAtMs) && nowMs - pausedAtMs >= HOME_PAUSE_GRACE_MS

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
