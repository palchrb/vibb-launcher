package me.vibb.music

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import me.vibb.music.bridge.Bridge
import me.vibb.music.bridge.LiveBridge
import me.vibb.music.bridge.EXTRA_REPORT_ASK
import me.vibb.music.bridge.launcherAllowed
import me.vibb.music.playback.PlayerHub
import me.vibb.music.sonos.SonosSuspension
import me.vibb.music.spotify.SpotifyWebHost
import me.vibb.music.spotify.SpotifyWebService
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
        // design 120 §4.2: the web player's own process runs its page and nothing else - no channel, config, sync or
        // receiver; and the main process makes no WebView (only `:web` uses app_webview)
        if (Application.getProcessName().endsWith(SpotifyWebService.PROCESS_SUFFIX)) return
        SpotifyWebHost.disableInThisProcess()
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

    /**
     * The one place the managed config's language is applied (S2); the player rebinds a bridge it
     * lacked (L4), and a room's own volume above a lowered cap follows it (design 26a §5.3).
     */
    private fun applyConfig(context: Context) {
        val config = Bridge.config(context)
        // the parent's Spotify diagnostic log (2026-11-01): on and off with the launcher's next write, no restart
        PlayerHub.spotifyDiagUntilS = config.spotifyDiagUntilS
        Bridge.applyLanguage(context, config)
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            LiveBridge.refresh(context)
            PlayerHub.capChanged?.invoke()
        }
    }
}

/** The launcher's nudge (`me.vibb.music.action.BRIDGE_CHANGED`): starts the sync - and only from a
 * launcher signed like this app (it shares its identity, qa-21-step3-code L7; the signer since code QA
 * L5, `launcherAllowed`). The launcher's report ask ([EXTRA_REPORT_ASK]), or any nudge with the last
 * report an hour old or none, also sends the report at once, inside the broadcast ([goAsync]): the
 * nudge's sync is WorkManager work app standby may defer for hours. */
class BridgeChangedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!launcherAllowed(sentFromPackage) { Bridge.sameSigner(context, it) }) return
        Sync.nudge(context)
        val asked = intent.getBooleanExtra(EXTRA_REPORT_ASK, false)
        val pending = goAsync()
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                withTimeoutOrNull(REPORT_BUDGET_MS) { Sync.answerNudge(app, asked) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("BridgeChanged", "The report on the nudge failed - the sync reports", e)
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /** The nudge is a background broadcast, whose receiver may run 60 s; the report needs a few seconds
         * (a bind, a binder call), so it gets well under that - the sync reports again in any case. */
        const val REPORT_BUDGET_MS = 9_000L
    }
}

/**
 * This app was updated in place (`MY_PACKAGE_REPLACED`): nothing else would wake the new process
 * until the launcher's next nudge, and Device > Music would keep the old app's report. The process
 * start this broadcast causes ([MusicApp.onCreate]) applies the managed config and runs
 * [Sync.atStart]; the nudge states the sync the update asks for (one already waiting covers it).
 */
class PackageReplacedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
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

