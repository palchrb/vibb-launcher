package me.vibb.music.playback

import android.content.Context
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaButtonReceiver

/**
 * Whether a stored queue exists to resume (written with the now-playing row): read synchronously
 * by [KidMediaButtonReceiver] before it starts the player as a foreground service.
 */
object Resumable {
    private fun prefs(context: Context) = context.getSharedPreferences("music", Context.MODE_PRIVATE)

    fun has(context: Context): Boolean = prefs(context).getBoolean("resumable", false)

    fun set(context: Context, on: Boolean) {
        if (has(context) != on) prefs(context).edit().putBoolean("resumable", on).apply()
    }
}

/**
 * A headset or car kit's PLAY with nothing to resume (before the first play, after a queue played
 * to its end) must not start the foreground service: Media3 would never post its notification and
 * Android would kill the app (qa-21-step2-code M2, androidx/media #1528). Such a press is ignored.
 */
@OptIn(UnstableApi::class)
class KidMediaButtonReceiver : MediaButtonReceiver() {
    override fun shouldStartForegroundService(context: Context, intent: Intent): Boolean = Resumable.has(context)
}
