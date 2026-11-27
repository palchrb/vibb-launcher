package com.kidslauncher.mdm.lock

/*
 * Who roots lock task, and how the lock leaves (design 16, QA #2/#5(a)). Pure, tested in
 * LockTaskRootTest.
 *
 * With the kiosk on, the PIN lock must never be the lock-task root: a root can't finish
 * (`activityBlockedFromFinish`), and stopping it clears every locked task - Home included - which
 * surfaced the stock launcher's latent Recents task as "App is not available" (BlockedAppActivity,
 * `lockTaskMode="always"`) at the unlock. So the lock asks Home to root lock task (Home's resume
 * starts it and shows the lock again) and only starts it itself as a fallback; and it always
 * leaves through Home. With the kiosk off the lock-task is the lock's own (step 10): it starts and
 * stops it itself; since design 32 the kid lands on Home there too (decision 2: Home lies under the
 * lock as defence in depth), unless a call is live - Home must never cover a call (QA L2).
 * Design 59b R2 (decided 2026-11-02): as Android's face with its secure keyguard locked and the kiosk off, the glance
 * takes no lock task at all - Android's locked keyguard is the gate, and a helper the kid unlocks into on SystemUI's
 * face or pad (DeskClock) stays: stopping a root lock task clears every locked task, the helper's included.
 */

/** How often the lock may ask Home to root lock task; in between it waits for the fallback. */
const val HOME_ROOT_RETRY_MS = 3_000L

enum class LockTaskEntry {
    /** Running already, or not permitted (then the lock is a re-front only); kiosk off: a VoIP call, or Android's face
     * with Android locked (design 59b R2). */
    NONE,

    /** Start Home (typed HOME): its resume roots lock task and shows the lock again. */
    START_HOME,

    /** Home was asked less than [HOME_ROOT_RETRY_MS] ago: wait for the [LOCK_FALLBACK_MS] fallback. */
    WAIT_FOR_HOME,

    /** The lock starts lock task itself: the kiosk is off (the face with Android not locked), Home didn't
     * (fallback), or the face with Android locked and the kiosk on. */
    START_SELF,

    /** Design 59b R2 (code QA M1): kiosk off, as the face with Android locked, a lock task the glance roots runs -
     * started while Android read open (a resume in a RELOCK's wait: a process start with the screen on, a remote lock's
     * show before its `lockNow`, a late `USER_PRESENT`) or left from the face's kiosk (59d A). The glance stops it now,
     * not at the leave, where it would clear a helper's task. Only as the root (E7b M-B1): `stopLockTask` from a task
     * that isn't the root takes that task out and clears it (AOSP `clearLockedTask` -> `performClearTaskForReuse`) - the
     * glance is finished, Home's locked resume shows it again in a new task, which joins the lock task and stops again:
     * a loop with Android's keyguard un-occluded. Another root (Home's kiosk) stays until the leave. */
    STOP_OWN,
}

/**
 * At the lock's resume ([fallbackDue] = false) and [LOCK_FALLBACK_MS] after asking Home (true).
 * [sinceHomeAskedMs]: elapsed time since the lock last started Home for this (`null` = never).
 * [voipCall]: a VoIP call rings or lives ([VoipPhase] not NONE, design 17) - with the kiosk off the
 * lock then starts no lock task: starting it removes pinned (PiP) tasks, and Element hangs up when
 * its PiP goes (QA #10). Only then: not with the kiosk on, and not while the package is merely
 * still pinned after the exemption ended (qa-16-17-code #6).
 */
fun lockTaskEntry(
    running: Boolean,
    permitted: Boolean,
    kioskOn: Boolean,
    sinceHomeAskedMs: Long?,
    fallbackDue: Boolean,
    voipCall: Boolean = false,
    /** As Android's face with its secure keyguard locked (`AndroidLock.refrontHeld`). Kiosk on - design 59d step A
     * (qa-59b-code B-1): a Home start lands behind the keyguard (and un-occludes it), so the glance roots lock task
     * itself; the leave re-roots through Home (`lockLeave`'s kiosk root, `startedLockTask`). Kiosk off - design 59b R2
     * (the user, 2026-11-02): no lock task of the glance's own. Android's locked keyguard keeps every start that isn't
     * showWhenLocked behind it and refuses Recents (P59-1), the shade stays off through `setStatusBarDisabled`
     * (`lockTaskWhileLocked`), the power menu is the keyguard's; and a helper the kid unlocks into on SystemUI's face or
     * pad stays - the leave stops nothing (`lockLeave`: nothing running), where stopping the glance's root lock task
     * cleared the helper's task with it (AOSP `clearLockedTasks`, the rc.2 run's R2). A lock task the glance started
     * while Android read open (the face's RESUME/UNLOCK_TAP wait before a RELOCK: a process start with the screen on, a
     * remote lock, a late `USER_PRESENT`; or one left from a kiosk on -> off flip) is stopped at the next entry check
     * with Android locked ([LockTaskEntry.STOP_OWN]) - never during a VoIP call (its PiP, design 17 QA #10). */
    androidLocked: Boolean = false,
    /** This lock instance started the running lock task (`startedLockTask`): its task is the root. Unknown (Home rooted
     * it, an earlier instance or process did) counts as not - a STOP_OWN from a task that isn't the root clears the
     * glance's own task (E7b M-B1), so the decision must hold across every resume that follows. */
    glanceRoot: Boolean = false,
): LockTaskEntry = when {
    running && glanceRoot && !kioskOn && androidLocked && !voipCall -> LockTaskEntry.STOP_OWN
    running || !permitted || (!kioskOn && (voipCall || androidLocked)) -> LockTaskEntry.NONE
    !kioskOn || fallbackDue || androidLocked -> LockTaskEntry.START_SELF
    sinceHomeAskedMs != null && sinceHomeAskedMs in 0 until HOME_ROOT_RETRY_MS -> LockTaskEntry.WAIT_FOR_HOME
    else -> LockTaskEntry.START_HOME
}

/** What a policy sets of [lockTaskEntry]'s inputs: the kiosk (`LockTaskChrome.kioskOn`) and Android's lock as the face
 * (`AndroidLock.faceOn`). */
data class EntryPolicy(val kioskOn: Boolean, val face: Boolean)

/**
 * Whether a chrome pass that just landed (the runtime's, or a policy's `LockTaskChrome.applyPlan`) re-runs the resumed
 * lock's entry check: the pass pinned our package for a lock that found it not pinned ([waitingForPin], 16c), or the
 * policy changed what the check reads since the lock last saw it ([seen], `null` = not seen yet). E7's M2 (59b R2): the
 * kiosk turned on under the face's glance without lock task took one only at the next screen cycle; now the glance
 * roots it at once (59d A), stops its own when the kiosk goes off with Android locked ([LockTaskEntry.STOP_OWN]), and
 * follows the face going on or off the same way - from the lock itself, whether Home is in front or not.
 */
fun chromeEntryRecheckDue(waitingForPin: Boolean, seen: EntryPolicy?, now: EntryPolicy): Boolean =
    waitingForPin || (seen != null && seen != now)

/** What the lock does when it leaves (unlocked, or switched off). */
data class LockLeave(
    /** Stop lock task before finishing. */
    val stopLockTaskFirst: Boolean,
    /** Start Home before finishing, so Home is above any latent task and never finished. */
    val homeFirst: Boolean,
)

/**
 * - Kiosk on: always through Home (QA #5(a)); lock task is stopped first only when this lock
 *   instance started it ([startedLockTask]: it is the root - then stopping clears every locked
 *   task, and Home, started right after, roots it again). A root the lock doesn't know about (an
 *   earlier instance before a process restart) shows as a blocked finish; the caller retries with
 *   [rootLeave].
 * - Kiosk off: as in step 10, any running lock task is the lock's own and is stopped first. Then
 *   through Home (design 32 decision 2) unless [callLive] - our call, Telecom's or a VoIP call: the
 *   call's screen stays in front, and Element hangs up when its PiP goes (QA L2).
 * - Design 59b step 0 (Q59b-1): [glanceInFront] false - an unlock on Android's own face or pad while the glance
 *   was stopped under the app the kid unlocked into ([leaveInFront]) - no Home: the glance finishes under that app,
 *   which Home would only hide. The lock task is stopped first exactly as in the rows above (so `stopOwnLockTask`
 *   still agrees). Kept: kiosk on with the glance the lock-task root - stopping its lock task drops the kiosk, and
 *   Home re-roots it.
 * - Design 59d step H (the user's Q1, Q59d-4): with the kiosk on, no Home over a live
 *   call ([callLive] = `PinLockRuntime.callLiveNow`): the glance finishes and the call's UI (the app's own, or ours)
 *   stays in front - with the kiosk on only locked tasks can come forward. Except as the kiosk root ([startedLockTask]):
 *   stopping a root lock task clears every locked task, the call app's included, and only Home re-roots the kiosk.
 *   Kiosk off already has the rule.
 */
fun lockLeave(
    kioskOn: Boolean,
    startedLockTask: Boolean,
    lockTaskRunning: Boolean,
    callLive: Boolean,
    glanceInFront: Boolean = true,
): LockLeave =
    if (kioskOn) {
        LockLeave(stopLockTaskFirst = startedLockTask, homeFirst = startedLockTask || (glanceInFront && !callLive))
    } else {
        LockLeave(stopLockTaskFirst = startedLockTask || lockTaskRunning, homeFirst = glanceInFront && !callLive)
    }

/**
 * Design 59b step 0: whether the leave counts the glance as in front - only when it is resumed at the leave ("Lås opp", a row via the pad, a
 * fingerprint from dark that resumed it). Stopped under an app (`USER_PRESENT` after SystemUI's face or pad): not.
 * qa-59b-code B-4: the pad's success ([fromAsk]) is always the glance's own ask - in front whatever the lifecycle reads
 * while the bouncer or the keyguard's going-away covers it, so a row's target is kept.
 */
fun leaveInFront(resumed: Boolean, fromAsk: Boolean = false): Boolean = resumed || fromAsk

/** The finish was refused (`isFinishing` still false) with the kiosk on: the lock is the root. */
val rootLeave = LockLeave(stopLockTaskFirst = true, homeFirst = true)

/*
 * Design 141: the kiosk ends from its root. The kiosk's end (`LockTaskChrome.writeLockTask`, the unpin) first has Home
 * stop lock task from its own task ([HomeRootStop]): from the root, AOSP's `clearLockedTask` clears every other locked
 * task - a leftover `BlockedAppActivity` task (`LAUNCHABLE_PRIV`, which `setLockTaskPackages` never removes) included -
 * and empties the locked list, so the emptying that follows finds nothing locked. Pure, tested in LockTaskRootTest.
 */

/** `ActivityManager.getLockTaskModeState`, as the kiosk's end reads it. PINNED is screen pinning - the kid's or
 * Android's, never the kiosk's. */
enum class LockTaskState { NONE, LOCKED, PINNED }

/** `ActivityManager.LOCK_TASK_MODE_*` (0 NONE, 1 LOCKED, 2 PINNED - public constants, duplicated to stay Android-free);
 * anything else reads as NONE. */
fun lockTaskStateOf(mode: Int): LockTaskState = when (mode) {
    1 -> LockTaskState.LOCKED
    2 -> LockTaskState.PINNED
    else -> LockTaskState.NONE
}

/** What Home's stop at the kiosk's end did ([homeRootStopAnswer]). */
enum class HomeRootStopAnswer {
    /** Lock task isn't LOCKED (NONE, or screen pinning): nothing to stop, no call. */
    NOT_LOCKED,

    /** Vibb's chrome is LOCKED: its lock task isn't ours to stop (a guard - the unpin never runs then). */
    LOCKED_CHROME,

    /** No Home in this process (design 141 §3.4). */
    NO_HOME,

    /** Home's `stopLockTask()` went through. */
    ASKED,

    /** Home's stop answered ASKED (or Home stopped lock task itself) earlier in this kiosk-off spell: not again (code QA
     * M1) - from a task that is no longer the root AOSP's `clearLockedTask` clears Home's own task (its `mLockTaskUid` is
     * never reset), and a root that joined in the gap would stay. LOCKED now is the stuck check's retry's job. */
    ASKED_BEFORE,

    /** Home's task was never locked (`SecurityException`, "Invalid uid") - the stuck state of §1. */
    REFUSED,

    /** Any other exception: a record without a task, a dead binder. */
    FAILED,
}

/**
 * Design 141 §3.2: whether Home stops lock task at the kiosk's end, and what came of it. [stop] (Home's
 * `stopLockTask()`, mapped to [HomeRootStopAnswer.ASKED], [HomeRootStopAnswer.REFUSED] or [HomeRootStopAnswer.FAILED])
 * runs only when lock task is LOCKED, Vibb's chrome isn't, Home hasn't stopped it already in this kiosk-off spell
 * ([askedThisSpell], [KioskEndSpell.homeAsked]) and a Home is registered. NOT_LOCKED is read first: the boot cover's
 * process and a process start's pass have no Home and nothing locked (§4) - no bring for them.
 */
fun homeRootStopAnswer(
    registered: Boolean,
    lockTaskState: LockTaskState,
    chromeLocked: Boolean,
    askedThisSpell: Boolean,
    stop: () -> HomeRootStopAnswer,
): HomeRootStopAnswer = when {
    lockTaskState != LockTaskState.LOCKED -> HomeRootStopAnswer.NOT_LOCKED
    chromeLocked -> HomeRootStopAnswer.LOCKED_CHROME
    askedThisSpell -> HomeRootStopAnswer.ASKED_BEFORE
    !registered -> HomeRootStopAnswer.NO_HOME
    else -> stop()
}

/** The answers that found lock task LOCKED with our chrome not LOCKED: the stuck check follows them (§3.3). */
fun stuckCheckDue(answer: HomeRootStopAnswer): Boolean =
    answer != HomeRootStopAnswer.NOT_LOCKED && answer != HomeRootStopAnswer.LOCKED_CHROME

/**
 * Design 141 §3.3: lock task is still on after the kiosk's end and isn't ours. With the kiosk off a lock task exists only
 * while Vibb's chrome is LOCKED (`lockTaskWhileLocked`) or a VoIP call's app is pinned ([voipPinned]); screen pinning
 * (PINNED) is never the kiosk's.
 */
fun lockTaskStuck(state: LockTaskState, kioskOn: Boolean, chromeLocked: Boolean, voipPinned: Boolean): Boolean =
    state == LockTaskState.LOCKED && !kioskOn && !chromeLocked && !voipPinned

/** How long §3.4 waits for the Home it brought before a pass empties the list regardless. */
const val KIOSK_END_HOME_WAIT_MS = 3_000L

/** When the stuck check runs after a kiosk-off pass that found lock task LOCKED, and again after its retry. */
const val KIOSK_END_STUCK_CHECK_MS = 1_000L

/**
 * Design 141 §3.5: how long the unpin waits, after Home's stop, for lock task to read not LOCKED before it empties the
 * list. On Android 14 and 15 `stopLockTask()` is a oneway binder call (`IActivityClientController`'s
 * `stopLockTaskModeByToken`; plain `void` from 16): it returns before system_server has run the stop, and the emptying
 * could get there first (emulator.md §6cg F1). The stop landed 0-19 ms ahead of an unwaited emptying when it won, and
 * its `stopLockTaskModeInternal` 11 ms behind one when it lost; 300 ms is over 15 times that for a busy system_server at
 * the kiosk's end, and short enough under the chrome monitor: a main-thread taker (the LOCKED edge's pass, the "sos"
 * yield) waits at most this once per kiosk-off spell, far from an ANR, and it ends well before the 1 s stuck check.
 */
const val KIOSK_END_STOP_SETTLE_MS = 300L

/** How often the wait of [KIOSK_END_STOP_SETTLE_MS] reads `lockTaskModeState`: at most 30 binder reads. */
const val KIOSK_END_STOP_POLL_MS = 10L

/** One read of the wait for Home's stop to land ([stopSettle]). */
enum class StopSettle {
    /** No stop of Home's within [KIOSK_END_STOP_SETTLE_MS], or an older one (a stuck spell's later passes, §3.3's job):
     * no wait, no read. */
    NOT_ASKED,

    /** Lock task isn't LOCKED: the stop landed (its `performStopLockTask` runs only once the locked list is empty). */
    ENDED,

    /** LOCKED, the stop asked less than [KIOSK_END_STOP_SETTLE_MS] ago: read again in [KIOSK_END_STOP_POLL_MS]. */
    WAIT,

    /** Still LOCKED when the wait ran out: today's emptying (one log line per spell). On 14/15 also Home's REFUSED,
     * which a oneway call can't report. */
    TIMED_OUT,
}

/**
 * Design 141 §3.5: one read of the unpin's wait before the emptying. [sinceAskedMs]: elapsed ms since Home's stop went
 * through in this spell (the pass's ASKED or Home's own stop, [KioskEndSpell.askedAtMs]; `null` = not asked); [waited]:
 * an earlier read of this wait answered [StopSettle.WAIT]. [state] is read only when a stop is pending.
 */
fun stopSettle(sinceAskedMs: Long?, waited: Boolean, state: () -> LockTaskState): StopSettle = when {
    sinceAskedMs == null || sinceAskedMs < 0 -> StopSettle.NOT_ASKED
    !waited && sinceAskedMs >= KIOSK_END_STOP_SETTLE_MS -> StopSettle.NOT_ASKED
    state() != LockTaskState.LOCKED -> StopSettle.ENDED
    sinceAskedMs < KIOSK_END_STOP_SETTLE_MS -> StopSettle.WAIT
    else -> StopSettle.TIMED_OUT
}

/** What the unpin does after Home's stop ([unpinStep]). */
enum class UnpinStep {
    /** Empty the list (`setLockTaskPackages(admin, [])`) - as before design 141. */
    EMPTY,

    /** No Home in this process, screen on: bring Home (typed HOME, restarted in its root task - our package is still
     * listed) and leave the list for its root stop (§3.4). */
    BRING_HOME,

    /** Home was brought this spell and [KIOSK_END_HOME_WAIT_MS] hasn't passed: leave the list for Home, as at the bring. */
    WAIT_FOR_HOME,
}

/**
 * Design 141 §3.4. [broughtThisSpell]: Home was brought in this kiosk-off spell; [timerDue]: [KIOSK_END_HOME_WAIT_MS]
 * has passed since. One bring per spell; with the screen off (Home wouldn't resume behind the keyguard), any other
 * answer, or the wait over: today's emptying.
 */
fun unpinStep(answer: HomeRootStopAnswer, interactive: Boolean, broughtThisSpell: Boolean, timerDue: Boolean): UnpinStep = when {
    answer != HomeRootStopAnswer.NO_HOME || !interactive -> UnpinStep.EMPTY
    !broughtThisSpell -> UnpinStep.BRING_HOME
    timerDue -> UnpinStep.EMPTY
    else -> UnpinStep.WAIT_FOR_HOME
}

/**
 * Design 141 §3.2's log: one line per answer, with fixed words; [HomeRootStopAnswer.REFUSED],
 * [HomeRootStopAnswer.NO_HOME] and [HomeRootStopAnswer.ASKED_BEFORE] (the passes repeat while stuck) and the stuck line and the wait's timeout (§3.5) at
 * most once per kiosk-off spell, which a kiosk-on pass ends ([kioskOn]). NOT_LOCKED is never logged: a phone with the kiosk off and nothing locked logs
 * nothing new. Not thread-safe on its own; [HomeRootStop] holds it under its own monitor.
 */
class KioskEndLogOnce {
    private val logged = mutableSetOf<HomeRootStopAnswer>()
    private var stuckLogged = false
    private var settleLogged = false

    /** Whether to log [answer] now. */
    fun answer(answer: HomeRootStopAnswer): Boolean = when (answer) {
        HomeRootStopAnswer.NOT_LOCKED -> false
        HomeRootStopAnswer.REFUSED, HomeRootStopAnswer.NO_HOME, HomeRootStopAnswer.ASKED_BEFORE -> logged.add(answer)
        else -> true
    }

    /** Whether to log the stuck line now. */
    fun stuck(): Boolean = !stuckLogged.also { stuckLogged = true }

    /** Whether to log the wait's [StopSettle.TIMED_OUT] now (§3.5). */
    fun settleTimedOut(): Boolean = !settleLogged.also { settleLogged = true }

    /** A kiosk-on pass: the next spell logs again. */
    fun kioskOn() {
        logged.clear()
        stuckLogged = false
        settleLogged = false
    }
}

/**
 * Design 141's state of one kiosk-off spell (from a kiosk-on pass to the next): whether Home's stop went through, the
 * bring of §3.4 and whether the list was emptied since, and the stuck check's one retry. Pure; [HomeRootStop] holds it
 * under its own monitor.
 */
class KioskEndSpell {
    /** Home's stop went through in this spell (its answer ASKED, or Home's own stop at a resume): never again (code QA M1). */
    var homeAsked = false
        private set

    /** When Home's stop last went through in this spell (elapsed ms, §3.5's wait), `null` = not yet. */
    var askedAtMs: Long? = null
        private set

    /** When Home was brought (§3.4, elapsed ms), `null` = not brought in this spell. */
    var broughtAtMs: Long? = null
        private set

    private var emptiedSinceBring = false
    private var retried = false

    /** The retry's answer in this spell, `null` = no retry yet. */
    var retryAnswer: HomeRootStopAnswer? = null
        private set

    /** A brought Home is awaited: brought in this spell and the list not emptied since. Only then does Home's own stop at
     * a resume ask for the pass that empties it (code QA M1: else that pass is a second unpin with no use). */
    val awaitingHome: Boolean get() = broughtAtMs != null && !emptiedSinceBring

    /** Home's stop went through at [atMs] (the pass's ASKED, or Home's own stop). */
    fun asked(atMs: Long) {
        homeAsked = true
        askedAtMs = atMs
    }

    fun brought(atMs: Long) {
        broughtAtMs = atMs
        emptiedSinceBring = false
    }

    /** A pass emptied the list. */
    fun emptied() {
        emptiedSinceBring = true
    }

    /** Whether the stuck check may retry now - once per spell (code QA L1). */
    fun takeRetry(): Boolean = !retried.also { retried = true }

    fun retried(answer: HomeRootStopAnswer) {
        retryAnswer = answer
    }

    /** A kiosk-on pass: a new spell. */
    fun kioskOn() {
        homeAsked = false
        askedAtMs = null
        broughtAtMs = null
        emptiedSinceBring = false
        retried = false
        retryAnswer = null
    }
}
