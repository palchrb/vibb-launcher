package me.vibb.music.logic

import android.media.AudioManager

/*
 * Calls (design 26a §6.7; user 2026-10-11: the music pauses on a call and resumes afterwards only if
 * we paused it). The launcher sees every call - a VoIP ring or call, its own dialer's, a system call -
 * and tells the player service (CALL_HOLD / CALL_RELEASE, local and Sonos playback alike); the
 * service decides here, so its pause is its own and the sleep timer runs on (qa-26a-step3b-code H1).
 * The audio mode is the backstop for a system call on a Sonos room when the launcher has no
 * notification-listener access (design 21 QA #8): the phone's own player has audio focus for that,
 * a room holds none. vibb has no phone. Pure; CallPauseTest.
 */

/**
 * The audio mode says a call rings or is up: MODE_IN_CALL or MODE_RINGTONE only - never
 * MODE_IN_COMMUNICATION, which any app can hold (design 17 QA #5).
 */
fun callMode(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE

/**
 * [pausedByCall]: we paused for this call; [pauseSent]: and the pause hasn't shown yet;
 * [timerEnded]: the sleep timer ended while we held it (nothing plays again after the call).
 */
data class CallPause(val inCall: Boolean = false, val pausedByCall: Boolean = false, val pauseSent: Boolean = false, val timerEnded: Boolean = false)

/** The sleep timer ended (qa-26a-step3b-code H1): a pause we hold for a call is not undone after it. */
fun callTimerEnded(state: CallPause): CallPause = if (state.pausedByCall) state.copy(timerEnded = true) else state

enum class CallAction { NONE, PAUSE, PLAY }

/**
 * One look at the call and the player. A call starting while it plays -> PAUSE, remembered. During
 * the call a play the kid makes (seen after our pause showed) forgets it: their later pause stays.
 * The call ending -> PLAY only when we paused, it is still paused, the sleep timer didn't end
 * meanwhile ([callTimerEnded]) and [mayPlay] (a time rule in between keeps it quiet). A pause made
 * before the call is never ours to undo.
 */
fun callPauseStep(state: CallPause, inCall: Boolean, playing: Boolean, paused: Boolean, mayPlay: Boolean): Pair<CallPause, CallAction> = when {
    !state.inCall && inCall ->
        if (playing) CallPause(inCall = true, pausedByCall = true, pauseSent = true) to CallAction.PAUSE else CallPause(inCall = true) to CallAction.NONE
    state.inCall && inCall -> when {
        state.pauseSent && !playing -> state.copy(pauseSent = false) to CallAction.NONE
        state.pausedByCall && !state.pauseSent && playing -> state.copy(pausedByCall = false) to CallAction.NONE
        else -> state to CallAction.NONE
    }
    state.inCall && !inCall -> CallPause() to (if (state.pausedByCall && !state.timerEnded && paused && !playing && mayPlay) CallAction.PLAY else CallAction.NONE)
    else -> state to CallAction.NONE
}
