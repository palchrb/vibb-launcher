package com.kidslauncher.mdm.lock

/*
 * Handy's own PIN lock (handy step 10, design docs/design/10-lock-and-call-ui.md in the handy
 * workspace, with qa-10-design.md on top): the pure state machine. PinLockRuntime feeds it events
 * and carries out the step; no Android imports, tested in PinLockStateTest.
 *
 * DISABLED: no lock (no kid PIN, unmanaged, an Android credential still set, the crash guard).
 * LOCKED: the kid has to enter the PIN; the lock screen is shown whenever no call is on.
 * UNLOCKED: the kid is in. There is no persisted "unlocked": a new process starts LOCKED.
 */

enum class LockMode { DISABLED, LOCKED, UNLOCKED }

/*
 * Calls (qa-10-code #1-#3): [ourCall] = one of our calls exists (our call screen is the call UI);
 * [systemCall] = a call only the system dialer shows (emergency calls, or calls while our dialer
 * role isn't held). The lock is started over our calls - its resume brings our call screen back on
 * top, inside the lock task ([LockStep.showCall]) - but never over the system dialer's call; the
 * re-front loop brings it back when that call ends.
 */
sealed interface LockEvent {
    /** An apply decided whether the lock is active ([lockActivation]). */
    data class Configured(val active: Boolean) : LockEvent

    /** This process started (boot, a crash, an update): LOCKED if the lock was active. */
    data class ProcessStart(
        val active: Boolean,
        val interactive: Boolean,
        val ourCall: Boolean = false,
        val systemCall: Boolean = false,
    ) : LockEvent

    /**
     * The screen went off: always LOCKED, also during a call - a proximity blank doesn't change
     * interactivity, so an `ACTION_SCREEN_OFF` in a call is the power button or the timeout.
     */
    data class ScreenOff(val ourCall: Boolean = false, val systemCall: Boolean = false) : LockEvent

    /** Screen on or USER_PRESENT: a backstop when the lock isn't showing. */
    data class ScreenOn(val lockShowing: Boolean, val ourCall: Boolean = false, val systemCall: Boolean = false) : LockEvent

    /** The lock screen came to the front. */
    data class LockResumed(val ourCall: Boolean) : LockEvent

    /** The correct kid PIN, or the parent code. */
    data object Unlocked : LockEvent

    /** Our last call ended; [interactive] = the screen is on. Ended with the screen off means it
     * went off (power button/timeout) during the call - the phone locks (qa-10-code #2). */
    data class CallsEnded(val interactive: Boolean = true) : LockEvent

    /** The server's `lock` command (Locate page). */
    data class RemoteLock(val ourCall: Boolean = false, val systemCall: Boolean = false) : LockEvent

    /** A time-rule screen (LockActivity) was just started: the PIN lock goes on top of it. */
    data object TimeRuleShown : LockEvent
}

/**
 * The result of one event: the new [mode]; [showLock] = start the lock screen now (never over
 * the system dialer's call); [showCall] = bring our call screen in front of the lock (a ringing
 * call must stay answerable, an active one controllable - qa-10-code #1); [recheckTimeRules] =
 * after an unlock, show the time-rule screen if a rule is on. The lock-task/status-bar "chrome"
 * follows [mode] (LOCKED = locked chrome).
 */
data class LockStep(
    val mode: LockMode,
    val showLock: Boolean = false,
    val showCall: Boolean = false,
    val recheckTimeRules: Boolean = false,
)

fun step(mode: LockMode, event: LockEvent): LockStep = when (event) {
    is LockEvent.Configured -> when {
        !event.active -> LockStep(LockMode.DISABLED)
        // Switched on (the parent set a PIN, an Android PIN was removed, the crash guard re-armed):
        // the kid isn't thrown out of what he's doing - the lock starts at the next screen-off.
        mode == LockMode.DISABLED -> LockStep(LockMode.UNLOCKED)
        else -> LockStep(mode)
    }

    is LockEvent.ProcessStart ->
        if (!event.active) {
            LockStep(LockMode.DISABLED)
        } else {
            // Fail closed: nothing says the kid had unlocked. With the screen on (a crash while
            // the kid was in an app) the lock is shown at once (QA 10 #4).
            LockStep(LockMode.LOCKED, showLock = event.interactive && !event.systemCall)
        }

    is LockEvent.ScreenOff ->
        if (mode == LockMode.DISABLED) {
            LockStep(mode)
        } else {
            // Started at once while the screen is off, so it is drawn before the next screen-on.
            LockStep(LockMode.LOCKED, showLock = !event.systemCall)
        }

    is LockEvent.ScreenOn ->
        LockStep(mode, showLock = mode == LockMode.LOCKED && !event.lockShowing && !event.systemCall)

    is LockEvent.LockResumed -> LockStep(mode, showCall = mode == LockMode.LOCKED && event.ourCall)

    LockEvent.Unlocked ->
        if (mode == LockMode.LOCKED) LockStep(LockMode.UNLOCKED, recheckTimeRules = true) else LockStep(mode)

    // A call answered from the lock never unlocks it: the lock comes back when it ends (QA 10 #5).
    is LockEvent.CallsEnded -> when {
        mode == LockMode.LOCKED -> LockStep(mode, showLock = true)
        mode == LockMode.UNLOCKED && !event.interactive -> LockStep(LockMode.LOCKED, showLock = true)
        else -> LockStep(mode)
    }

    is LockEvent.RemoteLock ->
        if (mode == LockMode.DISABLED) LockStep(mode) else LockStep(LockMode.LOCKED, showLock = !event.systemCall)

    LockEvent.TimeRuleShown -> LockStep(mode, showLock = mode == LockMode.LOCKED)
}

/** Why the lock is off (status report `lock_state.inactive`). */
enum class LockInactive(val wire: String) {
    NO_PIN("no_pin"),
    UNMANAGED("unmanaged"),
    ANDROID_CREDENTIAL("android_credential"),
    KEYGUARD_NOT_DISABLED("keyguard_not_disabled"),
    CRASH_GUARD("crash_guard"),

    /** Not "off": the lock is on, but only the parent code opens it. */
    BAD_HASH("bad_hash"),
}

/**
 * Whether to switch Android's keyguard off (`setKeyguardDisabled(true)`): only for an active
 * lock - managed, a kid PIN, and no Android credential (with one the call fails anyway, and our
 * lock stays off so there is no double lock, design §7).
 */
fun wantKeyguardDisabled(managed: Boolean, hasKidPin: Boolean, deviceSecure: Boolean): Boolean =
    managed && hasKidPin && !deviceSecure

/**
 * Design §3: active only if managed, the policy has a kid PIN, no Android credential, and
 * `setKeyguardDisabled(true)` worked ([keyguardDisabled]); the crash guard switches it off until
 * it re-arms. An unreadable hash keeps the lock on - only the parent code opens it then.
 */
fun lockActivation(
    managed: Boolean,
    hasKidPin: Boolean,
    deviceSecure: Boolean,
    keyguardDisabled: Boolean,
    hashUsable: Boolean,
    guardTripped: Boolean,
): Pair<Boolean, LockInactive?> = when {
    !hasKidPin -> false to LockInactive.NO_PIN
    !managed -> false to LockInactive.UNMANAGED
    deviceSecure -> false to LockInactive.ANDROID_CREDENTIAL
    !keyguardDisabled -> false to LockInactive.KEYGUARD_NOT_DISABLED
    guardTripped -> false to LockInactive.CRASH_GUARD
    !hashUsable -> true to LockInactive.BAD_HASH
    else -> true to null
}

/** Kid PINs are 4-6 digits; anything else from the server means 4 (the keypad's dots). */
fun kidPinLength(length: Int?): Int = if (length != null && length in 4..6) length else 4

/** The entered digits after a key: digits only, never longer than [length]. */
fun appendDigit(entered: String, digit: Char, length: Int): String =
    if (digit in '0'..'9' && entered.length < length) entered + digit else entered

fun deleteDigit(entered: String): String = entered.dropLast(1)
