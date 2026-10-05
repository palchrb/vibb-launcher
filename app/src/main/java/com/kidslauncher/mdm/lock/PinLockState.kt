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

sealed interface LockEvent {
    /** An apply decided whether the lock is active ([lockActivation]). */
    data class Configured(val active: Boolean) : LockEvent

    /** This process started (boot, a crash, an update): LOCKED if the lock was active. */
    data class ProcessStart(val active: Boolean, val interactive: Boolean, val inCall: Boolean) : LockEvent

    /**
     * The screen went off. During a call a screen-off with the proximity sensor "near" is the
     * ear, not the power button (QA 10 #5): no lock. "Far" or unknown locks - shown once the call
     * ends.
     */
    data class ScreenOff(val inCall: Boolean, val proximityNear: Boolean?) : LockEvent

    /** Screen on or USER_PRESENT: a backstop when the lock isn't showing. */
    data class ScreenOn(val lockShowing: Boolean, val inCall: Boolean) : LockEvent

    /** The correct kid PIN, or the parent code. */
    data object Unlocked : LockEvent

    /** The last call ended (answered from the lock or not). */
    data object CallsEnded : LockEvent

    /** The server's `lock` command (Locate page). */
    data class RemoteLock(val inCall: Boolean) : LockEvent

    /** A time-rule screen (LockActivity) was just started: the PIN lock goes on top of it. */
    data object TimeRuleShown : LockEvent
}

/**
 * The result of one event: the new [mode]; [showLock] = start the lock screen now (it is never
 * started over a call); [recheckTimeRules] = after an unlock, show the time-rule screen if a rule
 * is on. The lock-task/status-bar "chrome" follows [mode] (LOCKED = locked chrome).
 */
data class LockStep(
    val mode: LockMode,
    val showLock: Boolean = false,
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
            LockStep(LockMode.LOCKED, showLock = event.interactive && !event.inCall)
        }

    is LockEvent.ScreenOff -> when {
        mode == LockMode.DISABLED -> LockStep(mode)
        event.inCall && event.proximityNear == true -> LockStep(mode)
        // Started at once while the screen is off, so it is drawn before the next screen-on.
        else -> LockStep(LockMode.LOCKED, showLock = !event.inCall)
    }

    is LockEvent.ScreenOn ->
        LockStep(mode, showLock = mode == LockMode.LOCKED && !event.lockShowing && !event.inCall)

    LockEvent.Unlocked ->
        if (mode == LockMode.LOCKED) LockStep(LockMode.UNLOCKED, recheckTimeRules = true) else LockStep(mode)

    // A call answered from the lock never unlocks it: the lock comes back when it ends (QA 10 #5).
    LockEvent.CallsEnded -> LockStep(mode, showLock = mode == LockMode.LOCKED)

    is LockEvent.RemoteLock ->
        if (mode == LockMode.DISABLED) LockStep(mode) else LockStep(LockMode.LOCKED, showLock = !event.inCall)

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
