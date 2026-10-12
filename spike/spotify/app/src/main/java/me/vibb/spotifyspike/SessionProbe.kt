// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * The media-controls probe (checklist items 6 and 9): the active MediaSessions as the system ranks
 * them, where a tap on each session's card lands (its session activity's creator), and the pause
 * that handy's launcher would send to Spotify's session. Needs notification-listener access:
 * adb shell cmd notification allow_listener me.vibb.spotifyspike/.SpikeNotificationListener
 */
object SessionProbe {
    private var watching: MediaSessionManager.OnActiveSessionsChangedListener? = null

    private fun component(context: Context) = ComponentName(context, SpikeNotificationListener::class.java)

    fun hasAccess(context: Context): Boolean =
        context.getSystemService(NotificationManager::class.java).isNotificationListenerAccessGranted(component(context))

    private fun controllers(context: Context): List<MediaController>? {
        if (!hasAccess(context)) {
            SpikeLog.log(
                "media probe: no notification-listener access - adb shell cmd notification allow_listener " +
                    component(context).flattenToShortString(),
            )
            return null
        }
        return context.getSystemService(MediaSessionManager::class.java).getActiveSessions(component(context))
    }

    fun list(context: Context) {
        val list = controllers(context) ?: return
        SpikeLog.log("media probe: ${list.size} active session(s), highest priority first")
        list.forEachIndexed { i, c -> SpikeLog.log("  #$i ${describe(c)}") }
    }

    /** What handy's bedtime rule would do (item 6): pause Spotify's own session. */
    fun pauseSpotify(context: Context) {
        val list = controllers(context) ?: return
        val spotify = list.filter { it.packageName == PackageProbe.SPOTIFY }
        if (spotify.isEmpty()) {
            SpikeLog.log("media probe: Spotify has no active session (of ${list.size})")
            return
        }
        spotify.forEach {
            SpikeLog.log("media probe: transportControls.pause() -> ${describe(it)}")
            it.transportControls.pause()
        }
    }

    fun watch(context: Context) {
        if (watching != null || !hasAccess(context)) return
        val listener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
            val text = list.orEmpty().joinToString { "${it.packageName}:${stateName(it.playbackState)}" }
            SpikeLog.log("media sessions changed: [$text]")
        }
        context.getSystemService(MediaSessionManager::class.java)
            .addOnActiveSessionsChangedListener(listener, component(context), Handler(Looper.getMainLooper()))
        watching = listener
        SpikeLog.log("media probe: watching active sessions")
    }

    fun unwatch(context: Context) {
        val listener = watching ?: return
        context.getSystemService(MediaSessionManager::class.java).removeOnActiveSessionsChangedListener(listener)
        watching = null
    }

    private fun describe(c: MediaController): String {
        val state = c.playbackState
        return "${c.packageName} state=${stateName(state)} pos=${state?.position} " +
            "tap->${describe(c.sessionActivity)}"
    }

    fun describe(intent: PendingIntent?): String =
        if (intent == null) "none" else "${intent.creatorPackage} (activity=${intent.isActivity})"

    private val STATE_NAMES = mapOf(
        PlaybackState.STATE_NONE to "NONE",
        PlaybackState.STATE_STOPPED to "STOPPED",
        PlaybackState.STATE_PAUSED to "PAUSED",
        PlaybackState.STATE_PLAYING to "PLAYING",
        PlaybackState.STATE_FAST_FORWARDING to "FAST_FORWARDING",
        PlaybackState.STATE_REWINDING to "REWINDING",
        PlaybackState.STATE_BUFFERING to "BUFFERING",
        PlaybackState.STATE_ERROR to "ERROR",
        PlaybackState.STATE_CONNECTING to "CONNECTING",
        PlaybackState.STATE_SKIPPING_TO_PREVIOUS to "SKIPPING_TO_PREVIOUS",
        PlaybackState.STATE_SKIPPING_TO_NEXT to "SKIPPING_TO_NEXT",
        PlaybackState.STATE_SKIPPING_TO_QUEUE_ITEM to "SKIPPING_TO_QUEUE_ITEM",
    )

    private fun stateName(state: PlaybackState?): String =
        if (state == null) "none" else STATE_NAMES[state.state] ?: "state ${state.state}"

    /** Spotify's notifications: id, channel, flags, media session, tap target - never their text. */
    fun describe(sbn: StatusBarNotification): String {
        val n = sbn.notification
        val flags = buildList {
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) add("ongoing")
            if (n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0) add("fgs")
            if (n.flags and Notification.FLAG_NO_CLEAR != 0) add("no-clear")
        }
        val media = n.extras?.containsKey(Notification.EXTRA_MEDIA_SESSION) == true
        return "id=${sbn.id} channel=${n.channelId} category=${n.category} flags=$flags clearable=${sbn.isClearable} " +
            "media=$media tap->${describe(n.contentIntent)}"
    }
}

class SpikeNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() {
        SpikeLog.log("notification listener connected")
        SessionProbe.watch(applicationContext)
    }

    override fun onListenerDisconnected() {
        SpikeLog.log("notification listener disconnected")
        SessionProbe.unwatch(applicationContext)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName.startsWith("com.spotify")) SpikeLog.log("Spotify notification posted: ${SessionProbe.describe(sbn)}")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName.startsWith("com.spotify")) SpikeLog.log("Spotify notification removed: id=${sbn.id}")
    }
}
