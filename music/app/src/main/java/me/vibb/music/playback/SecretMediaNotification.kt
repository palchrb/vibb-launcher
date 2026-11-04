package me.vibb.music.playback

import android.app.Notification
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.CommandButton
import androidx.media3.session.MediaNotification
import androidx.media3.session.MediaSession
import com.google.common.collect.ImmutableList

/**
 * The playback notification kept off a secure lock screen (2026-11-04, the user: Android's own media player showed at
 * screen-on even with "Vis medier på låseskjermen" off). Media3's [DefaultMediaNotificationProvider][androidx.media3.session.DefaultMediaNotificationProvider]
 * sets `VISIBILITY_PUBLIC` as the last step of its build, so the notification it returns - and every one it hands back
 * later through the callback, once the artwork has loaded - gets `VISIBILITY_SECRET` here, with no public version.
 * The shade, the session and its controllers are untouched: the launcher's lock tile reads the media session, never
 * this notification. Nothing else changes.
 */
@OptIn(UnstableApi::class)
class SecretMediaNotification(private val inner: MediaNotification.Provider) : MediaNotification.Provider {
    override fun createNotification(
        mediaSession: MediaSession,
        mediaButtonPreferences: ImmutableList<CommandButton>,
        actionFactory: MediaNotification.ActionFactory,
        onNotificationChangedCallback: MediaNotification.Provider.Callback,
    ): MediaNotification = secret(
        inner.createNotification(mediaSession, mediaButtonPreferences, actionFactory) { changed ->
            onNotificationChangedCallback.onNotificationChanged(secret(changed))
        },
    )

    override fun handleCustomCommand(session: MediaSession, action: String, extras: Bundle): Boolean =
        inner.handleCustomCommand(session, action, extras)

    private fun secret(media: MediaNotification): MediaNotification {
        media.notification.visibility = Notification.VISIBILITY_SECRET
        media.notification.publicVersion = null
        return media
    }
}
