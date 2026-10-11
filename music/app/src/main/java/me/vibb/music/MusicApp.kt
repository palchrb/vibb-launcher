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
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.LiveBridge
import me.vibb.music.bridge.LAUNCHER_PACKAGES
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sonos.SonosSuspension
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

    /** The one place the managed config's language is applied (S2); the player rebinds a bridge it lacked (L4). */
    private fun applyConfig(context: Context) {
        Bridge.applyLanguage(context, Bridge.config(context))
        android.os.Handler(android.os.Looper.getMainLooper()).post { LiveBridge.refresh(context) }
    }
}

/** The launcher's nudge (`me.vibb.music.action.BRIDGE_CHANGED`): carries nothing, only starts the
 * sync - and only from a launcher (it shares its identity, qa-21-step3-code L7). */
class BridgeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (sentFromPackage !in LAUNCHER_PACKAGES) return
        Sync.nudge(context)
    }
}

/**
 * The launcher suspended this app (a time rule, 21 §3): stop the sound at once. The launcher pauses
 * the session itself first where it can; this is the fallback without its notification access
 * (QA #8). A Sonos room is stopped too, with or without the player service (design 26a §6.1, QA
 * H4): through the service when it is up ([PlayerHub.suspendStop]), else the standalone path
 * ([SonosSuspension]) - one live classify, the bookmark, Stop and the park, renderer.json back to
 * the phone - all within the broadcast's budget ([goAsync]).
 */
class SuspendedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_SUSPENDED) return
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).launch {
            try {
                withTimeoutOrNull(SUSPEND_BUDGET_MS) {
                    val service = PlayerHub.suspendStop
                    if (service != null) service().join() else SonosSuspension.standalone(app)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** A broadcast receiver has ~10 s; finish inside it (the service's stop carries on in its own scope). */
        const val SUSPEND_BUDGET_MS = 9_000L
    }
}

