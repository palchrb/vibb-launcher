package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.dto.KidLock

/*
 * Design 59 (docs/design/59-android-keyguard-design.md §3) - Android's own lock screen as the gate, our
 * glance its face - pure, tested in AndroidLockPlanTest. Android's secure keyguard holds the Child PIN
 * (`resetPasswordWithToken`, CE bound to it); our glance page is a `showWhenLocked` activity over the
 * occluded keyguard; "Lås opp" asks Android (`requestDismissKeyguard`, Android's PIN pad). Only the
 * per-phone policy switch `kid_lock.keyguard == "android"` turns it on ([androidRequested]); without it
 * every path is today's own lock. [AndroidLock] is the glue.
 */

/** The one logcat tag: `adb logcat -s AndroidLock`. */
const val ANDROID_LOCK_TAG = "AndroidLock"

/**
 * 59 §3.2: the keyguard-disabled features Android's lock adds - WIDGETS_ALL (0x1), SECURE_NOTIFICATIONS
 * (0x4: Android's lock screen shows no notification, only our glance does), TRUST_AGENTS (0x10: no Smart
 * Lock), FACE (0x80) and IRIS (0x100) (open question 1: fingerprint is the only biometric) and SHORTCUTS_ALL
 * (0x200). SECURE_CAMERA (0x2) is B2's, set while managed anyway and checked in the read-back
 * ([KEYGUARD_SECURE_CAMERA]); FINGERPRINT (0x20) is never set (the user's answer 4).
 */
const val ANDROID_KEYGUARD_FEATURES = 0x1 or 0x4 or 0x10 or 0x80 or 0x100 or 0x200

/** `DevicePolicyManager.KEYGUARD_DISABLE_SECURE_CAMERA` (B2's bit; the camera lock's premise as the face). */
const val KEYGUARD_SECURE_CAMERA = 0x2

/**
 * What the policy asks (P59-5): `kid_lock.keyguard` exactly `"android"`. Anything else - `"own"`, missing,
 * junk, or no `kid_lock` at all - is today's own lock.
 */
fun androidRequested(kidLock: KidLock?): Boolean = kidLock?.keyguard == "android"

/** The glance is Android's face (59 §3.1): requested, managed, a kid PIN, and an Android credential. */
fun androidFace(requested: Boolean, managed: Boolean, hasKidPin: Boolean, deviceSecure: Boolean): Boolean =
    requested && managed && hasKidPin && deviceSecure

/** 59 §3.4: the next correct Child PIN on **our** keypad becomes Android's PIN - requested, managed, no credential. */
fun migrationDue(requested: Boolean, managed: Boolean, deviceSecure: Boolean): Boolean =
    requested && managed && !deviceSecure

/** What wakes [faceStep] (59 §3.3). */
enum class FaceTrigger {
    /** 1 s after a screen-off while LOCKED, the screen still dark. */
    SLEEP_CHECK,

    /** The glance resumed with the screen on. */
    RESUME,

    /** "Lås opp" or a row. */
    UNLOCK_TAP,

    /** `USER_PRESENT` (the mark is set before the step). */
    USER_PRESENT,

    /** The end of [ANDROID_ANSWER_WAIT_MS] after a RESUME or a tap found Android open with no `USER_PRESENT`. */
    AFTER_WAIT,

    /** Android's PIN pad answered `onDismissSucceeded`. */
    PAD_SUCCEEDED,
}

/** [faceStep]'s verdict. */
enum class FaceStep {
    /** Nothing: Android is locked under the glance. */
    STAY,

    /** The glance leaves (our mode UNLOCKED; a row's target opens). */
    LEAVE,

    /** `requestDismissKeyguard`: Android's PIN pad. */
    ASK_ANDROID,

    /** Wait [ANDROID_ANSWER_WAIT_MS] for `USER_PRESENT`, then [FaceTrigger.AFTER_WAIT]. */
    WAIT,

    /** `dpm.lockNow()`: Android didn't lock under the glance. */
    RELOCK,

    /** No credential now: our keypad and hash, as today - never past a PIN. */
    OWN_KEYPAD,
}

/**
 * 59 §3.3, one rule. [deviceSecure] and [androidLocked] (`isDeviceLocked || isKeyguardLocked`, unreadable =
 * locked) are read now; [presentSinceLocked] = a `USER_PRESENT` (a protected broadcast SystemUI sends when its
 * keyguard is done) since our lock last went LOCKED. The glance leaves only on Android's verdict: the PIN
 * pad's success, or `USER_PRESENT` with Android open. A not-locked read alone never opens the phone - it
 * waits, then locks Android (`lockNow`). No credential at any trigger: our own keypad.
 */
fun faceStep(trigger: FaceTrigger, deviceSecure: Boolean, androidLocked: Boolean, presentSinceLocked: Boolean): FaceStep = when {
    !deviceSecure -> FaceStep.OWN_KEYPAD
    trigger == FaceTrigger.PAD_SUCCEEDED -> FaceStep.LEAVE
    androidLocked -> if (trigger == FaceTrigger.UNLOCK_TAP) FaceStep.ASK_ANDROID else FaceStep.STAY
    // Dark and open: Android must lock now - the next wake's RESUME decides about a USER_PRESENT.
    trigger == FaceTrigger.SLEEP_CHECK -> FaceStep.RELOCK
    presentSinceLocked -> FaceStep.LEAVE
    trigger == FaceTrigger.AFTER_WAIT -> FaceStep.RELOCK
    else -> FaceStep.WAIT
}

/** How long a not-locked read waits for `USER_PRESENT` (RESUME, a tap), and how long a `USER_PRESENT` lets an open
 * ask's answer (it carries a row's target) come first. */
const val ANDROID_ANSWER_WAIT_MS = 1_000L

/**
 * S3-3: the wait when Android read **locked** since this screen-off (the lock-up's read, the sleep check) and now reads
 * open: a locked secure keyguard with no trust agents and no face opens only on the credential or the fingerprint, so
 * this is an unlock whose `USER_PRESENT` (a background broadcast) may be slow - it gets longer to arrive. Still no
 * LEAVE without it: [FaceTrigger.AFTER_WAIT] relocks.
 */
const val ANDROID_UNLOCK_WAIT_MS = 3_000L

/** How long a [FaceStep.WAIT] waits ([lockedSeenThisSleep]: Android read locked since this screen-off). */
fun faceWaitMs(lockedSeenThisSleep: Boolean): Long = if (lockedSeenThisSleep) ANDROID_UNLOCK_WAIT_MS else ANDROID_ANSWER_WAIT_MS

/** The screen-off's check that Android locked under the glance ([FaceTrigger.SLEEP_CHECK]). */
const val ANDROID_SLEEP_CHECK_MS = 1_000L

/**
 * The run's bug 2: an ask whose answer never comes (once an empty shade instead of the PIN pad) must never
 * leave dead taps. A tap on an ask older than this asks again; a fast double tap (the pad still coming up)
 * doesn't.
 */
const val ANDROID_ASK_RETRY_MS = 1_000L

/** An ask with no answer this long while our window has the focus (no pad, no shade over us): it died - the state
 * is read again ([askRecoveryDue]). */
const val ANDROID_ASK_TIMEOUT_MS = 2_000L

/** A tap while an ask is open ([askAgeMs] `null` = none): ask (again) unless it is a double tap. */
fun askAgain(askAgeMs: Long?): Boolean = askAgeMs == null || askAgeMs >= ANDROID_ASK_RETRY_MS

/** The open ask is dead: no answer for [ANDROID_ASK_TIMEOUT_MS] and nothing of SystemUI over us ([focused]). */
fun askRecoveryDue(askAgeMs: Long?, focused: Boolean): Boolean =
    askAgeMs != null && askAgeMs >= ANDROID_ASK_TIMEOUT_MS && focused

/**
 * 59 §3.1 (Q59-2, L-4): this process start is UNLOCKED - the boot's **first** CE start with the face (CE
 * unlocked means the PIN was typed) - by the boot count against CE `android_boot_count`, which every CE
 * start writes in every mode. An unknown boot count is a later start: LOCKED.
 */
fun processStartUnlocked(face: Boolean, bootCount: Int, storedBootCount: Int?): Boolean =
    face && bootCount >= 0 && storedBootCount != bootCount

/** The boot's first CE start (any mode): the restart line goes ([processStartUnlocked]'s boot test). */
fun firstCeStart(bootCount: Int, storedBootCount: Int?): Boolean = bootCount >= 0 && storedBootCount != bootCount

/** No re-front while Android's secure keyguard is locked under the face (59 §3.9, P59-3: a credential too). */
fun androidRefrontHeld(face: Boolean, deviceSecure: Boolean, androidLocked: Boolean): Boolean =
    face && deviceSecure && androidLocked

/**
 * The camera lock (25b) while LOCKED - off only while the face is on **and** the keyguard write read B2's
 * SECURE_CAMERA back ([androidGuardsCamera], P59-4): then Android's secure keyguard refuses the gesture.
 */
fun cameraLockEngaged(locked: Boolean, androidGuardsCamera: Boolean): Boolean = locked && !androidGuardsCamera

/** P59-4: the written features (`null` = the write or read failed) hold SECURE_CAMERA on a managed phone. */
fun secureCameraHeld(managed: Boolean, features: Int?): Boolean =
    managed && features != null && (features and KEYGUARD_SECURE_CAMERA) != 0

/**
 * 59 §3.8 (Q59-4, M-1, L-1): the edge out of android mode - we own Android's credential ([owned], CE
 * `keyguard_android`), the policy doesn't ask for it, and the policy carries the parent's choice ([parentChoice]:
 * a fresh one or the `Ok` cache - never a `Fallback`, which has no `kid_lock`, nor `Apply(null)`).
 */
fun rollbackDue(owned: Boolean, requested: Boolean, parentChoice: Boolean): Boolean = owned && !requested && parentChoice

/**
 * S3-1 (qa-59-step3-code): an apply without the parent's choice (the last-enforced plan, a corrupt cache - no
 * `kid_lock`) keeps Android mode's keyguard write (features and T) while we own Android's live credential: no
 * notification content, face unlock, trust agents or lock delay come back from a fallback. The face may be off.
 */
fun androidKeyguardHeld(owned: Boolean, deviceSecure: Boolean, parentChoice: Boolean): Boolean =
    owned && deviceSecure && !parentChoice

/** S3-1: `keyguard_requested` is written only from an apply with the parent's choice; `null` keeps the stored one. */
fun requestToStore(requested: Boolean, parentChoice: Boolean): Boolean? = if (parentChoice) requested else null

/**
 * L-4 (S3-4): the boot count to commit as `android_boot_count` at this CE start - [boot] when known and new, else
 * `null` (nothing to write).
 */
fun bootSwap(stored: Int?, boot: Int): Int? = if (boot >= 0 && boot != stored) boot else null

/** The rollback's step once due. */
enum class AndroidRollback {
    /** No credential left: `keyguard_android` false (verified). */
    NOTHING,

    /** `resetPasswordWithToken(null)`, then the apply writes DISABLED. */
    CLEAR,

    /** A credential and no active token: Android's PIN stays the gate; the token is kept running (escrow). */
    NO_TOKEN,
}

fun androidRollback(deviceSecure: Boolean, tokenActive: Boolean): AndroidRollback = when {
    !deviceSecure -> AndroidRollback.NOTHING
    tokenActive -> AndroidRollback.CLEAR
    else -> AndroidRollback.NO_TOKEN
}

/** The reset token in android mode (59 §3.6, replacing 24b's `resetTokenNeeded` there). */
enum class TokenStep {
    NOTHING,

    /** Store 32 new bytes and `setResetPasswordToken`: no credential -> active at once; a credential -> escrowed. */
    SET,

    /** Stored, not active, a credential: escrowed - it activates at the kid's next unlock. */
    WAIT,
}

/** [stored]: a token readable from `WindowRelockStore.token()` (r10's `Unreadable` = none). */
fun androidTokenStep(stored: Boolean, active: Boolean, deviceSecure: Boolean): TokenStep = when {
    stored && active -> TokenStep.NOTHING
    !stored -> TokenStep.SET
    !deviceSecure -> TokenStep.SET
    else -> TokenStep.WAIT
}

/**
 * 59 §3.7: `onPasswordChanged` - our own reset (the one-shot "ours" flag, [expected]) keeps the rev Android's PIN
 * was set from; any other change (the kid in Settings) makes it unknown (`null`: the PWA warns).
 */
fun androidPinRevAfterChange(expected: Boolean, rev: Long?): Long? = if (expected) rev else null

/** The status report's capability (59 §1, step 4): this launcher runs `set_kid_pin` and can hold Android's lock. */
const val ANDROID_KEYGUARD_CAPABILITY = "android_keyguard_v1"

/** The one-shot command that hands this phone the Child PIN for Android's lock (`pending_command.pin`, 59 §1). */
const val SET_KID_PIN_COMMAND = "set_kid_pin"

/** `set_kid_pin`'s answers (59 §1): the command result's `message` and its `success`. */
enum class PinCommandAnswer(val message: String, val success: Boolean) {
    PIN_SET("pin set", true),

    /** The fresh policy doesn't ask for Android's lock: the hash in it is the gate (also with no Child PIN). */
    OWN_LOCK("own lock", true),
    NO_TOKEN("refused: no_token", false),
    MISMATCH("refused: mismatch", false),
    NO_PIN("refused: no_pin", false),
    FAILED("refused: failed", false),
}

/**
 * 59 §3.5: `set_kid_pin` against the **fresh** policy (the one its PIN came in): its keyguard isn't android -> own
 * lock; no PIN -> no_pin; the PIN doesn't verify against that policy's hash ([verifies]: `verifyCommandPin`, no
 * backoff, no failure counted - Q59-1) -> mismatch; no active reset token ([tokenActive], which sets one for the
 * escrow when none is stored) -> no_token. `null` = set it now. [verifies] runs only with a PIN, [tokenActive] only
 * for a PIN that verified.
 */
fun pinCommandStep(requested: Boolean, pin: String?, verifies: (String) -> Boolean, tokenActive: () -> Boolean): PinCommandAnswer? = when {
    !requested -> PinCommandAnswer.OWN_LOCK
    pin.isNullOrEmpty() -> PinCommandAnswer.NO_PIN
    !verifies(pin) -> PinCommandAnswer.MISMATCH
    !tokenActive() -> PinCommandAnswer.NO_TOKEN
    else -> null
}

/** A PIN Android's pad and our keypad both take: exactly [length] digits (the policy's `pin_length`). */
fun commandPinShape(pin: String, length: Int): Boolean = pin.length == length && pin.all { it in '0'..'9' }

/**
 * The rev written ahead of the command's reset (M-1: owned first): the rev of the PIN Android holds **now** - the one
 * we set before ([owned], [storedRev]), else none. A rev is never claimed before its PIN is set: a death between the
 * reset and the rev's commit reports the older rev (the server sends the PIN again), never a newer one.
 */
fun commandWriteAheadRev(owned: Boolean, storedRev: Long?): Long? = if (owned) storedRev else null

/**
 * Android's PIN is the gate (59 §1): our glance is its face ([face]), or we own Android's live credential ([owned] &&
 * [deviceSecure]: a rollback waiting for the token, a fallback apply, an unmanaged phone).
 */
fun androidGate(face: Boolean, owned: Boolean, deviceSecure: Boolean): Boolean = face || (owned && deviceSecure)

/** `lock_state.keyguard` and `pin_rev` (59 §1). */
data class KeyguardReport(val keyguard: String, val pinRev: Long?)

/**
 * Which gate the phone runs, and the rev it enforces (59 §1): `"android"` while [androidGate] - with the rev Android's
 * PIN was set from ([androidRev]; `null` = unknown, or a credential not ours) -, else `"own"` with the rev committed
 * with the hash in use ([ownRev]; `null` = no Child PIN).
 */
fun keyguardReport(face: Boolean, owned: Boolean, deviceSecure: Boolean, androidRev: Long?, ownRev: Long?): KeyguardReport =
    if (androidGate(face, owned, deviceSecure)) KeyguardReport("android", androidRev.takeIf { owned }) else KeyguardReport("own", ownRev)
