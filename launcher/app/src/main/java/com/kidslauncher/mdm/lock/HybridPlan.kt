package com.kidslauncher.mdm.lock

/*
 * Design 59's hybrid, as a prototype (59 §9 step 7 (ii), docs/design/59-prototype.md) - pure, tested in
 * HybridPlanTest. Android's secure keyguard is the gate, our glance page its face: a `showWhenLocked`
 * activity over the occluded keyguard, "Lås opp" asks Android (`requestDismissKeyguard`, Android's PIN
 * pad). Only `HybridDebug`'s switch (src/debug; the release one is always false) turns it on, so with it
 * off nothing here changes anything. [Hybrid59] is the glue.
 */

/** The prototype's one logcat tag: `adb logcat -s Hybrid59`. */
const val HYBRID_TAG = "Hybrid59"

/**
 * 59 §3.1: the keyguard-disabled features the hybrid adds - WIDGETS_ALL (0x1), SECURE_NOTIFICATIONS
 * (0x4: Android's lock screen shows no notification, only our glance does), TRUST_AGENTS (0x10: no Smart
 * Lock) and SHORTCUTS_ALL (0x200). SECURE_CAMERA (0x2) is B2's, set while managed anyway
 * ([keyguardDisabledFeatures]); fingerprint stays allowed (the user's answer 4, 2026-10-22).
 */
const val HYBRID_KEYGUARD_FEATURES = 0x1 or 0x4 or 0x10 or 0x200

/**
 * The glance is the face over Android's keyguard: the switch on, managed, a kid PIN, and an Android
 * credential (the migration's, [hybridMigrationDue]). Before that it is today's own lock.
 */
fun hybridFace(switchOn: Boolean, managed: Boolean, hasKidPin: Boolean, deviceSecure: Boolean): Boolean =
    switchOn && managed && hasKidPin && deviceSecure

/** 59 §9 step 2: the next correct kid PIN on **our** keypad becomes Android's PIN - with the switch on,
 * managed, and no credential yet. */
fun hybridMigrationDue(switchOn: Boolean, managed: Boolean, deviceSecure: Boolean): Boolean =
    switchOn && managed && !deviceSecure

/** What "Lås opp" (or a row) on the glance does ([androidUnlockStep]). */
enum class AndroidUnlock {
    /** Today's keypad: no face, or no credential now (it went since the last apply). */
    OWN_KEYPAD,

    /** Android's keyguard isn't locked (the kid already unlocked it): the glance leaves. */
    UNLOCK_NOW,

    /** `requestDismissKeyguard`: Android's PIN pad. */
    ASK_ANDROID,
}

/**
 * "Lås opp" or a row: [face] as the last apply decided, [deviceSecure] and [androidLocked]
 * (`isDeviceLocked || isKeyguardLocked`, unreadable = locked) read now. The keypad is never skipped
 * without a live credential.
 */
fun androidUnlockStep(face: Boolean, deviceSecure: Boolean, androidLocked: Boolean): AndroidUnlock = when {
    !face || !deviceSecure -> AndroidUnlock.OWN_KEYPAD
    androidLocked -> AndroidUnlock.ASK_ANDROID
    else -> AndroidUnlock.UNLOCK_NOW
}

/**
 * `USER_PRESENT`, or the glance resumed with the screen on: Android's keyguard is gone (its PIN pad, a
 * fingerprint from dark, the first unlock after a boot - 59 §4.4, §4.9), so our face goes too. Only in
 * face mode, with a live credential, while LOCKED.
 */
fun androidUnlockedUs(face: Boolean, deviceSecure: Boolean, androidLocked: Boolean, locked: Boolean, interactive: Boolean): Boolean =
    face && deviceSecure && !androidLocked && locked && interactive

/**
 * 59 §4.2: no re-front while Android's keyguard is locked in face mode - the platform shows nothing that
 * isn't `showWhenLocked` then, and a re-front would fight Android's PIN pad. The glance comes back at the
 * next screen-off (the lock-up) and when our call ends. Unlocked, the loop runs as today.
 */
fun hybridRefrontHeld(face: Boolean, androidLocked: Boolean): Boolean = face && androidLocked

/** The camera lock (25b) while LOCKED - in face mode `KEYGUARD_DISABLE_SECURE_CAMERA` is the guard instead (59 §4.9). */
fun cameraLockEngaged(locked: Boolean, face: Boolean): Boolean = locked && !face

/**
 * Lock task holds Recents and the shade off over the glance (59 §4.7, the user's Recents check 2026-10-22):
 * running, OVERVIEW (8) and NOTIFICATIONS (2) out, KEYGUARD (32) in - `LockTaskChrome` sets exactly that while
 * LOCKED. Unreadable features: not guarded.
 */
fun recentsGuarded(lockTaskRunning: Boolean, features: Int?): Boolean =
    lockTaskRunning && features != null && (features and 8) == 0 && (features and 2) == 0 && (features and 32) != 0

/** An ask for Android's PIN pad counts as open this long (its answer may never come - the activity went, a
 * SystemUI restart); a new "Lås opp" after it asks again. */
const val ANDROID_ASK_OPEN_MS = 30_000L

/** `USER_PRESENT` while an ask is open: its answer (with a glance row's target) gets this long to come first. */
const val ANDROID_ANSWER_WAIT_MS = 1_000L

/** The switch back to "own" (59 §9 step 6). */
enum class HybridRollback {
    /** No credential: today's lock at the next apply. */
    NOTHING,

    /** `resetPasswordWithToken(null)`, then an apply: `setKeyguardDisabled(true)`, our keypad. */
    CLEAR,

    /** A credential and no active token: design 10's runbook (clear it in Settings, in a window). Android's PIN stays the gate. */
    NO_TOKEN,
}

fun hybridRollback(deviceSecure: Boolean, tokenActive: Boolean): HybridRollback = when {
    !deviceSecure -> HybridRollback.NOTHING
    tokenActive -> HybridRollback.CLEAR
    else -> HybridRollback.NO_TOKEN
}
