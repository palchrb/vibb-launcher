package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.music.MusicSession

/*
 * The PIN lock's media view (design 21 §3, design 20's two-stage lock, mockup Lock.dc.html v12):
 * pure, LockMediaTest. The music package's MusicLockPanel draws it inside PinLockActivity.
 *
 * It is part of the lock and weakens nothing: its only actions are the music app's play/pause,
 * previous and next (the session's transport controls), "Lås opp" to the keypad, the keypad's back
 * arrow and the lock's own emergency call. No app, session activity, settings or notification is
 * ever opened from it (LockMediaGuardTest scans the glue).
 */

/** The lock's two stages: the media view, or the keypad. */
enum class LockStage { MEDIA, KEYPAD }

/**
 * The stage when the lock comes up (shown, or back in front - the screen turning on included):
 * the media view only while the music app's session plays; anything else is the keypad as before.
 * [session] is what the surfaces may show (`shownSession`).
 */
fun lockStageAtLockUp(session: MusicSession?): LockStage =
    if (session?.playing == true) LockStage.MEDIA else LockStage.KEYPAD

/**
 * Whether the lock "comes up" now (its onStart/onResume) and decides its stage again: only with the
 * screen on ([interactive] - the lock is started at every screen-off and seen at the next
 * screen-on), the first time, and after every screen-off since the last decision ([screenOffs],
 * `PinLockRuntime.screenOffs`). Coming back from the emergency dialer or an alarm with the screen
 * on keeps the stage the kid was on.
 */
fun lockStageDue(interactive: Boolean, decidedAtScreenOffs: Int?, screenOffs: Int): Boolean =
    interactive && decidedAtScreenOffs != screenOffs

/** While shown: the media view stays when the kid pauses, and gives way to the keypad when the
 * session ends (a queue's end, the player gone, the app suspended). */
fun lockStageAfterChange(stage: LockStage, session: MusicSession?): LockStage =
    if (stage == LockStage.MEDIA && session == null) LockStage.KEYPAD else stage

/**
 * What shows: the media view; or on the keypad, while a session is live, the back arrow to the
 * media view and the compact row (art, entry, play/pause) - the keypad then gives up its clock and
 * date (as the mockup's keypad stage). Without a session the keypad is exactly the old one.
 */
data class LockMediaUi(val mediaView: Boolean, val backArrow: Boolean, val compactRow: Boolean)

fun lockMediaUi(stage: LockStage, session: MusicSession?): LockMediaUi = when {
    session == null -> LockMediaUi(mediaView = false, backArrow = false, compactRow = false)
    stage == LockStage.MEDIA -> LockMediaUi(mediaView = true, backArrow = false, compactRow = false)
    else -> LockMediaUi(mediaView = false, backArrow = true, compactRow = true)
}

/** The media view's progress ticks once a second only while it shows a playing session. */
fun lockMediaTicks(ui: LockMediaUi, session: MusicSession?): Boolean = ui.mediaView && session?.playing == true
