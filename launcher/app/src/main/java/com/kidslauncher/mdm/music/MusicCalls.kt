package com.kidslauncher.mdm.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import com.kidslauncher.mdm.badges.BadgeListenerService
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.lock.VoipCalls
import com.kidslauncher.mdm.lock.managedCallActive

private const val LOG_TAG = "MusicCalls"

/**
 * Calls pause the music (design 26a §6.7; user 2026-10-11: the music pauses on a call and resumes
 * afterwards only if we paused it - Element X voice and video calls included). The launcher sees
 * every call: a VoIP ring or call from the notification listener ([VoipCalls]), its own dialer's
 * ([OngoingCalls]) and a system call (Telecom, the audio mode). At the call's edges ([callHoldStep])
 * it tells the music app's player through its session's custom actions - [MUSIC_CALL_HOLD] when a
 * call begins while the music app has a session (playing or not: emulator round 2026-10-14 N3),
 * [MUSIC_CALL_RELEASE] when that call is over - and the player pauses what it plays as its own pause,
 * starts nothing during the call and decides whether to play again (qa-26a-step3b-code H1: a plain session pause
 * is the kid's and would end the sleep timer). The same for local and Sonos playback. Without
 * notification-listener access there is no session to see: the music app's own audio-mode backstop
 * covers a system call on a Sonos room then. Main thread.
 */
object MusicCalls {
    private var app: Context? = null
    private var state = CallHold()

    /** Others that follow the same call edges ([callEdge]): the flashlight's off rule (design 41
     * §1.4). Main thread, like [evaluate]. */
    private val edgeListeners = mutableListOf<(CallEdge) -> Unit>()

    fun addCallEdgeListener(listener: (CallEdge) -> Unit) { edgeListeners += listener }

    /** From [MusicMedia.init] (process start, main thread). */
    fun init(context: Context) {
        val ctx = context.applicationContext
        app = ctx
        VoipCalls.addListener { evaluate() }
        OngoingCalls.addListener { evaluate() }
        try {
            ctx.getSystemService(AudioManager::class.java)?.addOnModeChangedListener(ctx.mainExecutor) { evaluate() }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "No audio mode listener", e)
        }
    }

    private fun evaluate() {
        val ctx = app ?: return
        val inCall = musicCallActive(VoipCalls.phase, OngoingCalls.hasLiveCall, systemCall(ctx))
        val edge = callEdge(state.inCall, inCall)
        if (edge == CallEdge.NONE) return
        for (listener in edgeListeners.toList()) {
            try {
                listener(edge)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A call-edge listener failed", e)
            }
        }
        val session = musicSession(ctx)
        // the log only: the hold goes whether or not the music plays (N3) - the music app decides
        val playing = session?.let { sessionPlay(it.playbackState?.state) } == SessionPlay.PLAYING
        val (next, action) = callHoldStep(state, inCall, session != null)
        state = next
        Log.i(LOG_TAG, "call ${if (inCall) "began" else "ended"}: music ${if (session == null) "no session" else if (playing) "playing" else "not playing"} -> $action")
        val command = when (action) {
            CallMusicAction.HOLD -> MUSIC_CALL_HOLD
            CallMusicAction.RELEASE -> MUSIC_CALL_RELEASE
            CallMusicAction.NONE -> return
        }
        try {
            session?.transportControls?.sendCustomAction(command, Bundle())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't send $action to the music app", e)
        }
    }

    private fun musicSession(ctx: Context): MediaController? = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(ctx, BadgeListenerService::class.java))
            ?.firstOrNull { it.packageName in MUSIC_PACKAGES }
    } catch (e: SecurityException) {
        null // no listener access: no media surfaces, and no call rule here (the music app's backstop)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the media sessions", e)
        null
    }

    /** A call the system dialer may show (as the lock decides it, `managedCallActive`). */
    private fun systemCall(ctx: Context): Boolean {
        val managed = try {
            ctx.getSystemService(TelecomManager::class.java)?.isInManagedCall
        } catch (e: Exception) {
            null
        }
        val mode = try {
            ctx.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
        } catch (e: Exception) {
            AudioManager.MODE_NORMAL
        }
        return managedCallActive(managed, mode)
    }
}
