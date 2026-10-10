package me.vibb.music

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import me.vibb.music.bridge.Bridge
import me.vibb.music.data.Store
import me.vibb.music.sync.NewNotifier
import me.vibb.music.sync.Sync

/**
 * Process start: the Nytt channel, the managed config (language, mobile data), a sync if none is
 * pending (design 21 §4.5, 21b §6), and - while the process runs - a sync whenever the launcher
 * changes the managed config (21 §2: only a running app gets that broadcast).
 */
class MusicApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        NewNotifier.createChannel(this)
        scope.launch { applyConfig(this@MusicApp) }
        Sync.atStart(this)
        registerReceiver(
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    scope.launch { applyConfig(context.applicationContext) }
                    Sync.nudge(context)
                }
            },
            IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED),
            Context.RECEIVER_NOT_EXPORTED, // a system broadcast still arrives
        )
    }

    private fun applyConfig(context: Context) {
        val config = Bridge.config(context)
        Store.get(context).restrictedMobileData = if (config.managed) config.mobileData else null
        Bridge.applyLanguage(context, config)
    }
}

/** The launcher's nudge (`me.vibb.music.action.BRIDGE_CHANGED`): carries nothing, only starts the sync. */
class BridgeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Sync.nudge(context)
    }
}

/**
 * The launcher suspended this app (a time rule, 21 §3): stop the sound at once. The launcher pauses
 * the session itself first where it can; this is the fallback without its notification access
 * (QA #8).
 */
class SuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        android.os.Handler(android.os.Looper.getMainLooper()).post { me.vibb.music.playback.PlayerHub.pause?.invoke() }
    }
}

