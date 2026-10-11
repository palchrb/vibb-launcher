package me.vibb.music.logic

import android.media.AudioManager

/*
 * Calls while a Sonos room plays (design 26a §6.7; user 2026-10-11: the speaker pauses on a call and
 * resumes afterwards only if we paused it). The launcher holds the rule for every call it sees -
 * a VoIP ring or call, its own dialer's, a system call - for local and Sonos playback alike. This is
 * the music app's backstop for a system call when the launcher has no notification-listener access
 * (design 21 QA #8's listener-free fallbacks): the phone's own player has audio focus for that, a
 * Sonos room holds none. vibb has no phone. Pure; CallPauseTest.
 */

/**
 * The audio mode says a call rings or is up: MODE_IN_CALL or MODE_RINGTONE only - never
 * MODE_IN_COMMUNICATION, which any app can hold (design 17 QA #5).
 */
fun callMode(mode: Int): Boolean = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_RINGTONE

/** [pausedByCall]: we paused for this call; [pauseSent]: and the pause hasn't shown yet. */
data class CallPause(val inCall: Boolean = false, val pausedByCall: Boolean = false, val pauseSent: Boolean = false)

enum class CallAction { NONE, PAUSE, PLAY }

/**
 * One look at the call and the player. A call starting while it plays -> PAUSE, remembered. During
 * the call a play the kid makes (seen after our pause showed) forgets it: their later pause stays.
 * The call ending -> PLAY only when we paused, it is still paused and [mayPlay] (a time rule in
 * between keeps it quiet). A pause made before the call is never ours to undo.
 */
fun callPauseStep(state: CallPause, inCall: Boolean, playing: Boolean, paused: Boolean, mayPlay: Boolean): Pair<CallPause, CallAction> = when {
    !state.inCall && inCall ->
        if (playing) CallPause(inCall = true, pausedByCall = true, pauseSent = true) to CallAction.PAUSE else CallPause(inCall = true) to CallAction.NONE
    state.inCall && inCall -> when {
        state.pauseSent && !playing -> state.copy(pauseSent = false) to CallAction.NONE
        state.pausedByCall && !state.pauseSent && playing -> state.copy(pausedByCall = false) to CallAction.NONE
        else -> state to CallAction.NONE
    }
    state.inCall && !inCall -> CallPause() to (if (state.pausedByCall && paused && !playing && mayPlay) CallAction.PLAY else CallAction.NONE)
    else -> state to CallAction.NONE
}
