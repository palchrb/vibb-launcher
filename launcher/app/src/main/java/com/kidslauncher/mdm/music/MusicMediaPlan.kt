package com.kidslauncher.mdm.music

import android.app.admin.DevicePolicyManager
import android.media.session.PlaybackState
import com.kidslauncher.mdm.lock.VoipPhase
import java.util.Locale

/*
 * The launcher's media surfaces and volume cap for Vibb music (design 21 §3, step 4): pure rules,
 * MusicMediaPlanTest. The glue is MusicSessions (the music app's live session), MusicHomeCard,
 * MusicLockPanel and VolumeCap; the Home card's and the lock's own rules are in ui/home/MusicCard.kt
 * and lock/LockMedia.kt.
 */

/** What the music app's live session does. */
enum class SessionPlay { PLAYING, PAUSED, STOPPED }

/**
 * A platform `PlaybackState` as [SessionPlay]: buffering, connecting and the skips are the intent
 * to play (Media3 reports them between items); none, stopped (a queue's end) and error are
 * [SessionPlay.STOPPED].
 */
fun sessionPlay(state: Int?): SessionPlay = when (state) {
    PlaybackState.STATE_PLAYING,
    PlaybackState.STATE_BUFFERING,
    PlaybackState.STATE_CONNECTING,
    PlaybackState.STATE_FAST_FORWARDING,
    PlaybackState.STATE_REWINDING,
    PlaybackState.STATE_SKIPPING_TO_NEXT,
    PlaybackState.STATE_SKIPPING_TO_PREVIOUS,
    PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM -> SessionPlay.PLAYING
    PlaybackState.STATE_PAUSED -> SessionPlay.PAUSED
    else -> SessionPlay.STOPPED
}

/**
 * The music app's live session as the launcher reads it (`MediaSessionManager.getActiveSessions`
 * through the badge listener): [entry] is the library entry (the session's album), [item] the
 * episode, chapter or track (its title). [positionMs] was the position at elapsed realtime
 * [positionAtElapsedMs], moving at [speed] while playing.
 */
data class MusicSession(
    val packageName: String,
    val play: SessionPlay,
    val entry: String?,
    val item: String?,
    val positionMs: Long,
    val positionAtElapsedMs: Long,
    val speed: Float,
    val durationMs: Long,
) {
    /** Playing or paused: there is something to show and control. */
    val live: Boolean get() = play != SessionPlay.STOPPED
    val playing: Boolean get() = play == SessionPlay.PLAYING
}

/** The position now: carried forward while playing, never past the end or below 0. */
fun positionNow(session: MusicSession, nowElapsedMs: Long): Long {
    val moved = if (session.playing && session.positionAtElapsedMs > 0) {
        ((nowElapsedMs - session.positionAtElapsedMs).coerceAtLeast(0) * session.speed.coerceIn(0f, 4f)).toLong()
    } else {
        0L
    }
    val at = (session.positionMs + moved).coerceAtLeast(0)
    return if (session.durationMs > 0) at.coerceAtMost(session.durationMs) else at
}

/**
 * The session the media surfaces show: none without notification-listener access (21 §3: no
 * access = no media surfaces), while the music app can't be used (not installed, suspended by a
 * time rule or the allowlist), or when nothing is playing or paused.
 */
fun shownSession(access: Boolean, usable: Boolean, session: MusicSession?): MusicSession? =
    session?.takeIf { access && usable && it.live }

/** The last `setNowPlaying` (CE prefs, never sent to the server): Home shows it paused when no
 * session is live - after a restart at once. */
data class NowPlayingSnapshot(val entry: String?, val item: String?, val positionMs: Long, val durationMs: Long)

/** "7:46", "18:30", "1:02:03". */
fun clockText(ms: Long): String {
    val total = ms.coerceAtLeast(0) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
}

/**
 * The card's line under the entry (HomeCard mockup): "Kapittel 3 · 7:46 av 18:30". [timeOf] makes
 * "7:46 av 18:30" in the launcher's language; an unknown duration shows the position alone, a
 * missing item the times alone.
 */
fun progressLine(item: String?, positionMs: Long, durationMs: Long, timeOf: (String, String) -> String): String {
    val times = if (durationMs > 0) timeOf(clockText(positionMs), clockText(durationMs)) else clockText(positionMs)
    val title = item?.trim()?.takeIf { it.isNotEmpty() }
    return if (title == null) times else "$title · $times"
}

/** How far the progress line is filled (0..1000). */
fun progressPermille(positionMs: Long, durationMs: Long): Int =
    if (durationMs <= 0) 0 else (positionMs.coerceIn(0, durationMs) * 1000 / durationMs).toInt()

/**
 * The parent's volume cap in force (21 §3, user decision: off by default, Off/90/80/70/60 % for all
 * outputs): only while the phone is managed and no offline override or pause is active.
 */
fun capPctInForce(policyCapPct: Int?, managed: Boolean, overrideActive: Boolean): Int? =
    policyCapPct.takeIf { managed && !overrideActive }

/**
 * The highest `STREAM_MUSIC` index the cap allows: [pct] of [maxIndex] rounded down, never below
 * [minIndex] or 1 (a cap never mutes). Null - no cap - when it is off, at least 100 % or not a
 * percentage the server sends (a value of 0 or below is a server fault, not "mute"), or the stream
 * reports no range.
 */
fun volumeCapIndex(maxIndex: Int, minIndex: Int, pct: Int?): Int? {
    if (pct == null || pct !in 1..99 || maxIndex <= 0) return null
    return (maxIndex * pct / 100).coerceAtLeast(maxOf(minIndex, 1)).coerceAtMost(maxIndex)
}

/** The index to set the stream back to, or null when [current] is within [capIndex]. */
fun volumeToSet(current: Int, capIndex: Int?): Int? = capIndex?.takeIf { current > it }

/** Before suspending [pkg], pause the music app's session there if it plays (21 §3). */
fun pauseBeforeSuspend(pkg: String, shouldBeSuspended: Boolean, currentlySuspended: Boolean): Boolean =
    shouldBeSuspended && !currentlySuspended && pkg in MUSIC_PACKAGES

/** `POST_NOTIFICATIONS` for the music app (21 QA #6): granted by the device owner while managed,
 * else back to the phone's own choice (the default state keeps the current grant). */
fun notificationGrantState(managed: Boolean): Int =
    if (managed) DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED else DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT

/**
 * A call the music session pauses for (design 26a §6.7; user 2026-10-11: Element X voice and video
 * calls included): a VoIP call ringing or up (`VoipCalls.phase`: its ring is a CALL notification, no
 * audio mode changes until it connects - design 17), a call of our own dialer, or a system call
 * (Telecom's managed call, else the audio mode IN_CALL - `managedCallActive`; never
 * MODE_IN_COMMUNICATION alone, design 17 QA #5). The same for local and Sonos playback: a Sonos
 * room holds no audio focus.
 */
fun musicCallActive(voip: VoipPhase, ourCall: Boolean, systemCall: Boolean): Boolean =
    voip != VoipPhase.NONE || ourCall || systemCall

/** [pausedByCall]: the launcher paused the music for this call; [pauseSent]: and the pause hasn't shown yet. */
data class CallHold(val inCall: Boolean = false, val pausedByCall: Boolean = false, val pauseSent: Boolean = false)

enum class CallMusicAction { NONE, PAUSE, PLAY }

/**
 * One look at the call and the music session ([play]: null without one). A call starting while it
 * plays -> PAUSE, remembered. During the call a play the kid makes (seen after our pause showed)
 * forgets it: their own pause afterwards stays. The call ending -> PLAY only when we paused, the
 * session is still paused and the app [usable] (a time rule in between keeps it quiet). A pause the
 * kid made before the call is never ours to undo.
 */
fun callHoldStep(state: CallHold, inCall: Boolean, play: SessionPlay?, usable: Boolean): Pair<CallHold, CallMusicAction> {
    val playing = play == SessionPlay.PLAYING
    return when {
        !state.inCall && inCall ->
            if (playing) CallHold(inCall = true, pausedByCall = true, pauseSent = true) to CallMusicAction.PAUSE else CallHold(inCall = true) to CallMusicAction.NONE
        state.inCall && inCall -> when {
            state.pauseSent && !playing -> state.copy(pauseSent = false) to CallMusicAction.NONE
            state.pausedByCall && !state.pauseSent && playing -> state.copy(pausedByCall = false) to CallMusicAction.NONE
            else -> state to CallMusicAction.NONE
        }
        state.inCall && !inCall ->
            CallHold() to (if (state.pausedByCall && play == SessionPlay.PAUSED && usable) CallMusicAction.PLAY else CallMusicAction.NONE)
        else -> state to CallMusicAction.NONE
    }
}
