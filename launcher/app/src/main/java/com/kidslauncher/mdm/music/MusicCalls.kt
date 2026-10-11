package com.kidslauncher.mdm.music

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.telecom.TelecomManager
import android.util.Log
import com.kidslauncher.mdm.badges.BadgeListenerService
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.lock.VoipCalls
import com.kidslauncher.mdm.lock.managedCallActive

private const val LOG_TAG = "MusicCalls"

/**
 * Calls pause the music (design 26a §6.7; user 2026-10-11: the speaker pauses on a call and resumes
 * afterwards only if we paused it - Element X voice and video calls included). The launcher sees
 * every call: a VoIP ring or call from the notification listener ([VoipCalls]), its own dialer's
 * ([OngoingCalls]) and a system call (Telecom, the audio mode). The rule is [callHoldStep], the same
 * for local and Sonos playback (a Sonos room holds no audio focus; the phone's own player also
 * pauses for a system call through its focus). The music app's session is looked up at each edge,
 * and watched only during a call. Without notification-listener access there is no session to see:
 * the music app's own audio-mode backstop covers a system call then. Main thread.
 */
object MusicCalls {
    private var app: Context? = null
    private var state = CallHold()
    private var watched: MediaController? = null

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) = evaluate()
        override fun onSessionDestroyed() {
            watch(null)
            evaluate()
        }
    }

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
        if (!inCall && !state.inCall) return
        val session = musicSession(ctx)
        val play = session?.let { sessionPlay(it.playbackState?.state) }
        val (next, action) = callHoldStep(state, inCall, play, session != null && MusicSessions.usable(ctx, session.packageName))
        if (next.inCall != state.inCall) Log.i(LOG_TAG, "call ${if (next.inCall) "began" else "ended"}: music $play -> $action")
        state = next
        watch(if (next.inCall) session else null)
        try {
            when (action) {
                CallMusicAction.PAUSE -> session?.transportControls?.pause()
                CallMusicAction.PLAY -> session?.transportControls?.play()
                CallMusicAction.NONE -> Unit
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't $action the music", e)
        }
    }

    /** During a call the session's own changes are looked at too: the kid's play during the call. */
    private fun watch(session: MediaController?) {
        if (session?.sessionToken == watched?.sessionToken) return
        watched?.let { runCatching { it.unregisterCallback(callback) } }
        watched = session
        session?.let { runCatching { it.registerCallback(callback) } }
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
