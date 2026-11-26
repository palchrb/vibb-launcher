package com.kidslauncher.mdm.lock

/*
 * Design 59 (docs/design/59-android-keyguard-design.md §3) - Android's own lock screen as the gate, our
 * glance its face - pure, tested in AndroidLockPlanTest. Android's secure keyguard holds the Child PIN
 * (`resetPasswordWithToken`, CE bound to it); our glance page is a `showWhenLocked` activity over the
 * occluded keyguard; "Lås opp" asks Android (`requestDismissKeyguard`, Android's PIN pad). Since design 132
 * (phase D) it is the only lock: a managed phone with a Child PIN and Android's credential has the face; without
 * the credential it has no lock at all until `set_kid_pin` sets it (`lock_state.inactive` `no_android_pin`).
 * [AndroidLock] is the glue.
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
 * The glance is Android's face (59 §3.1, design 132): managed, a Child PIN, and an Android credential. `kid_lock.keyguard`
 * is no longer read - the server sends `"android"` always.
 */
fun androidFace(managed: Boolean, hasKidPin: Boolean, deviceSecure: Boolean): Boolean =
    managed && hasKidPin && deviceSecure

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
}

/**
 * 59 §3.3, one rule. [deviceSecure] and [androidLocked] (`isDeviceLocked || isKeyguardLocked`, unreadable =
 * locked) are read now; [presentSinceLocked] = a `USER_PRESENT` (a protected broadcast SystemUI sends when its
 * keyguard is done) since our lock last went LOCKED. The glance leaves only on Android's verdict: the PIN
 * pad's success, or `USER_PRESENT` with Android open. A not-locked read alone never opens the phone - it
 * waits, then locks Android (`lockNow`). No credential at any trigger (design 132 §4.5): the glance leaves - there is
 * no lock to keep, and the apply makes ours DISABLED with `no_android_pin` (never a dead "Lås opp").
 */
fun faceStep(trigger: FaceTrigger, deviceSecure: Boolean, androidLocked: Boolean, presentSinceLocked: Boolean): FaceStep = when {
    !deviceSecure -> FaceStep.LEAVE
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

/** Design 59b §3 step 3: an ask's answer this fresh still counts as ours for the face's loss rule ([androidLossOurs]) -
 * the pad going away (or its Emergency dialer coming up) may stop the glance just after it. */
const val ANDROID_ASK_SETTLE_MS = 1_000L

/** A tap while an ask is open ([askAgeMs] `null` = none): ask (again) unless it is a double tap. */
fun askAgain(askAgeMs: Long?): Boolean = askAgeMs == null || askAgeMs >= ANDROID_ASK_RETRY_MS

/** The open ask is dead: no answer for [ANDROID_ASK_TIMEOUT_MS] and nothing of SystemUI over us ([focused]). */
fun askRecoveryDue(askAgeMs: Long?, focused: Boolean): Boolean =
    askAgeMs != null && askAgeMs >= ANDROID_ASK_TIMEOUT_MS && focused

/**
 * The empty shade over the pad (2026-11-17 check, item 4; Android 16 and up): SystemUI 16 reads a swipe down on the
 * glance as a status-bar long press that ignores the disabled shade and leaves its expansion waiting; the pad our ask
 * brings up releases it, and SystemUI drops the pad again (`PRIMARY_BOUNCER -> OCCLUDED`, no answer to the ask) - the
 * glance's window has the focus back within ~0.4 s, with an empty shade over it. A second ask then brings the pad over
 * the shade. So, as the gesture ask's one retry ([gestureAskRetryDue]): the focus back within [PAD_DROP_WINDOW_MS] of
 * an open, unanswered ask ([askAgeMs]), the same screen-on ([sameScreenOn]) and the keyguard still locked
 * ([keyguardLocked]) asks once more after [PAD_DROP_RETRY_DELAY_MS] - never twice ([retried]), never before Android 16
 * ([sdkInt]: 14 and 15 have no such long press and never showed it).
 */
fun padDropRetryDue(sdkInt: Int, retried: Boolean, askAgeMs: Long?, sameScreenOn: Boolean, keyguardLocked: Boolean): Boolean =
    sdkInt >= PAD_DROP_MIN_SDK && !retried && askAgeMs != null && askAgeMs in 0 until PAD_DROP_WINDOW_MS && sameScreenOn &&
        keyguardLocked

/**
 * [padDropRetryDue]'s ask, when its delay is up: only while it is still that ask, open with no answer ([sameAsk]), and
 * still nothing of SystemUI over the glance ([focused] - a pad that is up holds the focus, so a second ask never goes
 * over it), the keyguard locked, the screen on, the glance LOCKED in front and asking ([lockAsking]) and no call. Any
 * of them false or unreadable: no retry - the old 2 s recovery ([askRecoveryDue]) as before.
 */
fun padDropReaskNow(sameAsk: Boolean, focused: Boolean, keyguardLocked: Boolean, interactive: Boolean, lockAsking: Boolean, callLive: Boolean): Boolean =
    sameAsk && focused && keyguardLocked && interactive && lockAsking && !callLive

/** [padDropRetryDue]: Android 16 (`Build.VERSION_CODES.BAKLAVA`). */
const val PAD_DROP_MIN_SDK = 36

/** [padDropRetryDue]: the dropped pad gave the focus back ~0.4 s after the ask on `jelly36`. */
const val PAD_DROP_WINDOW_MS = 1_000L

/** [padDropRetryDue]: the drop's transition is over by then. */
const val PAD_DROP_RETRY_DELAY_MS = 500L

/**
 * 59 §3.1 (Q59-2, L-4): this process start is UNLOCKED - the boot's **first** CE start with the face (CE
 * unlocked means the PIN was typed) - by the boot count against CE `android_boot_count`, which every CE
 * start writes in every mode. An unknown boot count is a later start: LOCKED.
 */
fun processStartUnlocked(face: Boolean, bootCount: Int, storedBootCount: Int?): Boolean =
    face && bootCount >= 0 && storedBootCount != bootCount

/** The boot's first CE start (any mode): the restart line goes ([processStartUnlocked]'s boot test). */
fun firstCeStart(bootCount: Int, storedBootCount: Int?): Boolean = bootCount >= 0 && storedBootCount != bootCount

/** Android's secure keyguard is locked under the face (59 §3.9, P59-3: a credential too) - since design 59b no longer
 * "no re-front" but the face's loss rule ([androidLoss], [androidLossStep]) and the platform's ring ([voipPlatformRing]). */
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
 * L-4 (S3-4): the boot count to commit as `android_boot_count` at this CE start - [boot] when known and new, else
 * `null` (nothing to write).
 */
fun bootSwap(stored: Int?, boot: Int): Int? = if (boot >= 0 && boot != stored) boot else null

/** The reset token (59 §3.6; design 132: at enrolment and every apply, in every state). */
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
 * 59 §3.7: `onPasswordChanged` - our own reset ([expected], [OwnChange]) keeps the rev Android's PIN was set from, or
 * takes the rev a reset was setting when its own commit never came ([settingRev]: the process died between the reset
 * and the rev - S4-1); any other change (the kid in Settings) makes it unknown (`null`: the PWA warns).
 */
fun androidPinRevAfterChange(expected: Boolean, rev: Long?, settingRev: Long? = null): Long? =
    if (expected) settingRev ?: rev else null

/** How long our own reset waits for its `onPasswordChanged` (a lost broadcast never consumes a later change). */
const val OWN_CHANGE_MS = 60_000L

/**
 * 59 §3.7 (S4-1): our own credential changes still expected - [count] resets whose `onPasswordChanged` hasn't come,
 * in boot [boot] until [untilElapsed] (elapsed realtime); [settingRev] the rev the newest one sets (`null`: its reset
 * failed - the rev is no one's, or none was stored). Every own reset sets a PIN (design 132: none clears it).
 * Kept in CE `pin_lock_state` beside the owned flag, so a new process (the broadcast starts one after a death) still
 * knows the change was ours, and two resets close together are two.
 */
data class OwnChange(val count: Int, val boot: Int, val untilElapsed: Long, val settingRev: Long?)

/** [change] still counts: some left, this boot (known), inside its window. */
fun ownChangeLive(change: OwnChange?, boot: Int, elapsed: Long): Boolean =
    change != null && change.count > 0 && boot >= 0 && change.boot == boot && elapsed < change.untilElapsed

/** Before our reset: one more expected, the window from now, the rev it sets. */
fun ownChangeArmed(change: OwnChange?, boot: Int, elapsed: Long, settingRev: Long): OwnChange =
    OwnChange((change?.takeIf { ownChangeLive(it, boot, elapsed) }?.count ?: 0) + 1, boot, elapsed + OWN_CHANGE_MS, settingRev)

/** One expected change done - its broadcast came, or its reset failed (then [failed]: its rev is no one's). `null`
 * once none is left or it went stale. */
fun ownChangeConsumed(change: OwnChange?, boot: Int, elapsed: Long, failed: Boolean = false): OwnChange? {
    val live = change?.takeIf { ownChangeLive(it, boot, elapsed) && it.count > 1 } ?: return null
    return live.copy(count = live.count - 1, settingRev = if (failed) null else live.settingRev)
}

/**
 * S4-2: right after the command set Android's PIN and the apply turned the face on, while our lock is LOCKED and the
 * screen dark (the phone in a pocket): Android is locked at once - its keyguard was off at the last screen-off, so the
 * next wake would find it open and RELOCK under the kid's thumb. Not a relock (nothing was wrong).
 */
fun lockAfterPinSet(face: Boolean, locked: Boolean, interactive: Boolean): Boolean = face && locked && !interactive

/** The status report's capability (59 §1, step 4): this launcher runs `set_kid_pin` and can hold Android's lock. */
const val ANDROID_KEYGUARD_CAPABILITY = "android_keyguard_v1"

/** The one-shot command that hands this phone the Child PIN for Android's lock (`pending_command.pin`, 59 §1). */
const val SET_KID_PIN_COMMAND = "set_kid_pin"

/** `set_kid_pin`'s answers (59 §1): the command result's `message` and its `success`. */
enum class PinCommandAnswer(val message: String, val success: Boolean) {
    PIN_SET("pin set", true),
    NO_TOKEN("refused: no_token", false),
    MISMATCH("refused: mismatch", false),
    NO_PIN("refused: no_pin", false),
    FAILED("refused: failed", false),
}

/**
 * 59 §3.5: `set_kid_pin` against the **fresh** policy (the one its PIN came in): no PIN -> no_pin; the PIN doesn't verify against that policy's hash ([verifies]: `verifyCommandPin`, no
 * backoff, no failure counted - Q59-1) -> mismatch; no active reset token ([tokenActive], which sets one for the
 * escrow when none is stored) -> no_token. `null` = set it now. [verifies] runs only with a PIN, [tokenActive] only
 * for a PIN that verified.
 */
fun pinCommandStep(pin: String?, verifies: (String) -> Boolean, tokenActive: () -> Boolean): PinCommandAnswer? = when {
    pin.isNullOrEmpty() -> PinCommandAnswer.NO_PIN
    !verifies(pin) -> PinCommandAnswer.MISMATCH
    !tokenActive() -> PinCommandAnswer.NO_TOKEN
    else -> null
}

/** A PIN the command takes: exactly [length] digits (the policy's `pin_length`). */
fun commandPinShape(pin: String, length: Int): Boolean = pin.length == length && pin.all { it in '0'..'9' }

/**
 * The rev written ahead of the command's reset (M-1: owned first): the rev of the PIN Android holds **now** - the one
 * we set before ([owned], [storedRev]), else none. A rev is never claimed before its PIN is set: a death between the
 * reset and the rev's commit reports the older rev (the server sends the PIN again), never a newer one.
 */
fun commandWriteAheadRev(owned: Boolean, storedRev: Long?): Long? = if (owned) storedRev else null

/**
 * Android's PIN is the gate (59 §1): our glance is its face ([face]), or we own Android's live credential ([owned] &&
 * [deviceSecure]: a fallback apply, an unmanaged phone).
 */
fun androidGate(face: Boolean, owned: Boolean, deviceSecure: Boolean): Boolean = face || (owned && deviceSecure)

/** `lock_state.keyguard` and `pin_rev` (59 §1). */
data class KeyguardReport(val keyguard: String, val pinRev: Long?)

/**
 * Design 132 §2.1a: the gate is always Android's (`"android"`), with the rev Android's PIN was set from - known only for
 * a credential we set ([owned]; `null` = unknown, a credential not ours, or none - the latter reported as
 * `inactive: no_android_pin`).
 */
fun keyguardReport(owned: Boolean, androidRev: Long?): KeyguardReport = KeyguardReport("android", androidRev.takeIf { owned })

/** `DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED` and `PASSWORD_QUALITY_NUMERIC` (design 132 §4.5). */
const val PASSWORD_QUALITY_UNSPECIFIED = 0
const val PASSWORD_QUALITY_NUMERIC = 0x20000

/**
 * Design 132 §4.5 (QA H1): the password quality every apply writes. NUMERIC once Android has the Child PIN - we set it
 * ([owned], `keyguard_android`) and the credential is there ([deviceSecure]) - on a managed phone: Settings › Screen lock
 * then offers PIN and password only, never "None", "Swipe" or a pattern. Never before (a quality the current
 * credential doesn't meet makes Android ask for a new screen lock, racing `set_kid_pin`). A fallback apply (no
 * `kid_lock`) keeps it: the Child PIN is changed, never removed (Q1 (a)). Else UNSPECIFIED.
 */
fun passwordQuality(managed: Boolean, owned: Boolean, deviceSecure: Boolean): Int =
    if (managed && owned && deviceSecure) PASSWORD_QUALITY_NUMERIC else PASSWORD_QUALITY_UNSPECIFIED

/**
 * Code QA M2: the apply's quality step with its reads as read - [owned] (CE `keyguard_android`) and [deviceSecure]
 * (`isDeviceSecure`), `null` = unreadable. Any unreadable input writes nothing (`null`): whatever is in force stays - an
 * unreadable read never drops NUMERIC.
 */
fun passwordQualityToWrite(managed: Boolean, owned: Boolean?, deviceSecure: Boolean?): Int? =
    if (owned == null || deviceSecure == null) null else passwordQuality(managed, owned, deviceSecure)

/** Code QA M2: the owned flag goes only on a definite "no credential" while it reads owned - never on an unreadable read. */
fun ownedGone(owned: Boolean?, deviceSecure: Boolean?): Boolean = owned == true && deviceSecure == false
