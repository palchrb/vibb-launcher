package com.kidslauncher.mdm.lock

/*
 * Design 16e: the breathing Vibb mark for 3 s at boot. At the first process start after a boot the
 * PIN lock's first showing waits until the locked Home's mark (16c) has been up for 3 s - counted
 * from the boot cover's first frame when the cover (16b) came first, so the two share one 3 s.
 * Nothing else waits: the LOCKED chrome follows the mode at the process start as before (the wait
 * is only in PinLockRuntime.show), calls, alarms and VoIP skip it at once, every later lock shows
 * at once, and a backstop timer shows the lock when the 3 s are up whether Home drew or not.
 * Pure, tested in BootMarkPlanTest.
 */

/** The mark's 3 s at boot: the PIN lock's first showing waits this long (16e), and the boot cover
 * stays at least this long from its first frame before it hands over (16b) - one 3 s for both. */
const val BOOT_MARK_MS = 3_000L

/**
 * Whether this process's first lock showing waits for the mark: the first process start of a boot -
 * [bootCount] known (`Settings.Global.BOOT_COUNT`; -1 = unknown: never) and not [storedBootCount],
 * the one the last process start stored, so a crash restart in the same boot never waits - with the
 * lock active and its state readable at the start (unreadable: the lock at once, qa-16c-code #3).
 */
fun bootMarkDue(bootCount: Int, storedBootCount: Int?, lockActive: Boolean, stateReadable: Boolean): Boolean =
    lockActive && stateReadable && bootCount >= 0 && bootCount != storedBootCount

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
 * An ask for the lock at [nowMs] (elapsed). [BootMarkState.Over], an [LockAsk.AT_ONCE] ask or an
 * exemption ([bootMarkExempt]): show, and nothing waits any more. A [LockAsk.BOOT] ask holds the lock
 * until [BootMarkState.Holding.untilMs] - fixed by the first ask ([bootMarkUntilMs]), never moved
 * later - and shows it from then on. [exempt] and [coverFrameMs] are read only while something may
 * wait.
 */
fun bootMarkStep(state: BootMarkState, ask: LockAsk, nowMs: Long, exempt: () -> Boolean, coverFrameMs: () -> Long?): BootMarkStep {
    if (state == BootMarkState.Over || ask == LockAsk.AT_ONCE || exempt()) return BootMarkStep(BootMarkState.Over, show = true)
    val until = (state as? BootMarkState.Holding)?.untilMs ?: bootMarkUntilMs(nowMs, coverFrameMs())
    return if (nowMs >= until) BootMarkStep(BootMarkState.Over, show = true) else BootMarkStep(BootMarkState.Holding(until), show = false)
}

/** What the runtime does when the wait ends without an ask - its backstop timer, Home covered (an
 * app, Recents, a call or alarm screen), a call starting. */
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
