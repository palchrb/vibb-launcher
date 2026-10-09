package me.vibb.music

import android.app.Application
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.Sync

/** Process start: the Nytt channel, and a sync if none is pending (design 21 §4.5, 21b §6). */
class MusicApp : Application() {
    override fun onCreate() {
        super.onCreate()
        NewNotifier.createChannel(this)
        Sync.atStart(this)
    }
}
