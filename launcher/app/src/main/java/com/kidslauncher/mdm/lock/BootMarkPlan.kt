package com.kidslauncher.mdm.lock

/*
 * Design 16e: the breathing Vibb mark for 3 s at boot. At the first process start after a boot the
 * PIN lock's first showing waits until the locked Home's mark (16c) has been up for 3 s - counted
 * from the boot cover's first frame when the cover (16b) came first, so the two share one 3 s.
 * It waits only while Home's mark is verifiably up (qa-16e-code #1): Home resumed locked and not
 * paused, stopped or unfocused since - otherwise the lock shows at once, as before 16e. Nothing
 * else waits: the LOCKED chrome follows the mode at the process start, before the hold's reads
 * (the wait is only in PinLockRuntime.show), calls, alarms and VoIP skip it at once, every later
 * lock shows at once, and a backstop timer shows the lock when the 3 s are up.
 * Pure, tested in BootMarkPlanTest.
 */

/** The mark's 3 s at boot: the PIN lock's first showing waits this long (16e), and the boot cover
 * stays at least this long from its first frame before it hands over (16b) - one 3 s for both. */
const val BOOT_MARK_MS = 3_000L

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

    /** The lock waits until [untilMs] (elapsed realtime); the backstop timer shows it then. */
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

/**
 * When the lock may show: 3 s from the first of the cover's first frame [coverFrameMs] and [askMs],
 * the first ask for the lock - Home's locked resume (a frame before Home's first frame) or the
 * process start that brings Home up. So the lock is never more than 3 s after that ask, whether Home
 * draws or not, and a cover shown 3 s already means no wait (not 3 s twice).
 */
fun bootMarkUntilMs(askMs: Long, coverFrameMs: Long?): Long = minOf(askMs, coverFrameMs ?: askMs) + BOOT_MARK_MS

/** An ask's result: the next [state], and whether the lock is shown now ([show]). */
data class BootMarkStep(val state: BootMarkState, val show: Boolean)

/**
 * An ask for the lock at [nowMs] (elapsed realtime). Shows the lock, and nothing waits any more, on
 * [BootMarkState.Over], an [LockAsk.AT_ONCE] ask, Home's mark not up ([markUp] false: Home never
 * resumed locked in this process, or was paused, stopped or lost focus since - the lock at once, as
 * before 16e, also for the Home-first fallback after its 1 s), a first ask outside
 * [BOOT_MARK_WINDOW_MS], the end reached, or an exemption ([bootMarkExempt]). Otherwise a
 * [LockAsk.BOOT] ask holds the lock until [BootMarkState.Holding.untilMs] - fixed by the first ask
 * or [bootMarkClock] ([bootMarkUntilMs], [coverFrameMs] = the cover's first frame, `null` = none or
 * not read yet), never moved later. [exempt] (binder calls) is read last, only when it would hold.
 */
fun bootMarkStep(
    state: BootMarkState,
    ask: LockAsk,
    markUp: Boolean,
    nowMs: Long,
    coverFrameMs: Long?,
    exempt: () -> Boolean,
): BootMarkStep {
    val over = BootMarkStep(BootMarkState.Over, show = true)
    if (state == BootMarkState.Over || ask == LockAsk.AT_ONCE || !markUp) return over
    val until = if (state is BootMarkState.Holding) {
        state.untilMs
    } else {
        if (nowMs !in 0 until BOOT_MARK_WINDOW_MS) return over
        bootMarkUntilMs(nowMs, coverFrameMs)
    }
    if (nowMs >= until || exempt()) return over
    return BootMarkStep(BootMarkState.Holding(until), show = false)
}

/**
 * Home was started first at the process start (design 16, `showLockLater`): the 3 s begin now,
 * whether Home ever draws or not - nothing is shown or held here; Home's resume asks with its mark
 * up, and without it the 1 s fallback shows the lock ([bootMarkStep]). Only from
 * [BootMarkState.Due] and inside [BOOT_MARK_WINDOW_MS]; a cover that had its 3 s ends it.
 */
fun bootMarkClock(state: BootMarkState, nowMs: Long, coverFrameMs: Long?): BootMarkState {
    if (state != BootMarkState.Due) return state
    if (nowMs !in 0 until BOOT_MARK_WINDOW_MS) return BootMarkState.Over
    val until = bootMarkUntilMs(nowMs, coverFrameMs)
    return if (nowMs >= until) BootMarkState.Over else BootMarkState.Holding(until)
}

/** What the runtime does when the wait ends without an ask - its backstop timer, Home covered
 * (paused, stopped or unfocused: an app, Recents, the assistant, the power menu, a translucent
 * activity, a call or alarm screen), a call starting. */
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
