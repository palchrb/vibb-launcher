package me.vibb.music.spotify

import android.app.ActivityManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.RemoteException
import me.vibb.music.BuildConfig
import me.vibb.music.spotify.web.ISpotifyWeb
import me.vibb.music.spotify.web.ISpotifyWebEvents

/*
 * The web player's own process, `:web` (design 120 option B, §4.2 with its design QA; revision 2). Two halves:
 *
 * - [SpotifyWebService], in `:web` (the manifest; not exported, bound only, never started or in the foreground): the
 *   one [SpotifyWebHost] exactly as before - the WebView with the application context, never on a window, WebPolicy,
 *   the page bridge's checks - one page per process. A second `open` in a process that had a page (or whose service
 *   ended) is refused (`stale`). The page goes on `destroy`, on the main process's unbind and when the main process
 *   dies (Android unbinds it): the SDK's `disconnect()` gets its moment, then the process ends itself - only after the
 *   service's `onDestroy`, so Android holds no binding it would restart (QA H1 3). The renderer dying ends it too (L8).
 *   Every event is sent from this process's main looper (the host posts there), so the binder keeps the page's order
 *   (L3). Its log lines go to the main process, whose diagnostic switch decides (no `Log.` here, §7.1).
 * - [AndroidWebConnector], in the main process: the binder glue under [RemoteSpotifyHost] - bind with
 *   `BIND_AUTO_CREATE` and `BIND_INCLUDE_CAPABILITIES` (QA M2: `:web` makes the audio and takes the focus; its importance
 *   and while-in-use capability follow the playback service's), `linkToDeath`, and the events posted to main in their
 *   order of arrival.
 *
 * Both run on Android 14 to 17 alike: `android:process`, bound services, `linkToDeath`, `Process.killProcess` of our own
 * uid's process and `ActivityManager.getRunningAppProcesses` for our own processes are unchanged there.
 */

/** The web process's service (`:web`). */
class SpotifyWebService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var host: SpotifyWebHost? = null
    private var events: ISpotifyWebEvents? = null
    private var ending = false
    private var disconnected = false
    private var destroyed = false

    private val binder = object : ISpotifyWeb.Stub() {
        override fun open(fakeBase: String?, clearFirst: Boolean, events: ISpotifyWebEvents?) {
            if (!ours()) return
            main.post { openPage(fakeBase, clearFirst, events) }
        }

        override fun token(requestId: Int, token: String?) {
            if (ours()) main.post { host?.call("token", requestId, token) }
        }

        override fun seek(positionMs: Long) {
            if (ours()) main.post { host?.call("seek", positionMs) }
        }

        override fun volume(level: Double) {
            if (ours()) main.post { host?.call("volume", level) }
        }

        override fun command(name: String?) {
            if (!ours() || name == null || name !in RemoteSpotifyHost.COMMANDS) return
            main.post { host?.call(name) }
        }

        override fun destroy() {
            if (ours()) main.post { end() }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        end()
        return false
    }

    override fun onDestroy() {
        destroyed = true
        end()
        maybeExit()
        super.onDestroy()
    }

    private fun openPage(fakeBase: String?, clearFirst: Boolean, sink: ISpotifyWebEvents?) {
        if (sink == null) return
        if (used || ending) {
            // one page per process (QA H1 4): the main process binds a fresh one once this one has ended
            send(sink) { it.stale() }
            end()
            return
        }
        used = true
        events = sink
        // the debug switch (26c §9.3): the main process's base, read only through SpotifyEndpoints' fake-homes parse;
        // a release never sets it
        if (BuildConfig.DEBUG) SpotifyEndpoints.debugBase = fakeBase
        send(sink) { it.attached(Process.myPid()) }
        val h = SpotifyWebHost(this) { line -> main.post { events?.let { e -> send(e) { it.log(line) } } } }
        h.events = { forward(it) }
        host = h
        if (clearFirst) h.clearStorage { if (host === h && !ending) h.open() } else h.open()
    }

    /** A page event to the main process (this process's main looper: the host posts here). */
    private fun forward(e: HostEvent) {
        val sink = events ?: return
        when (e) {
            is HostEvent.Ready -> send(sink) { it.ready(e.deviceId) }
            is HostEvent.NotReady -> send(sink) { it.notReady(e.deviceId) }
            is HostEvent.NeedToken -> send(sink) { it.needToken(e.requestId) }
            is HostEvent.State -> e.json?.let { json -> send(sink) { it.state(json) } }
            is HostEvent.Error -> send(sink) { it.error(e.kind, e.message) }
            HostEvent.Gone -> {
                host = null // the host dropped its view already
                send(sink) { it.gone() }
                end()
            }
        }
    }

    private fun send(sink: ISpotifyWebEvents, block: (ISpotifyWebEvents) -> Unit) {
        try {
            block(sink)
        } catch (e: RemoteException) {
            end() // the main process is gone: Android unbinds us too
        }
    }

    /** The page goes (the SDK's disconnect first) and the process ends itself after the service's onDestroy. */
    private fun end() {
        if (ending) return
        ending = true
        val h = host
        host = null
        if (h == null) {
            disconnected = true
        } else {
            h.destroy {
                disconnected = true
                maybeExit()
            }
        }
        // a belt: never a process left without a page
        main.postDelayed({
            disconnected = true
            destroyed = true
            maybeExit()
        }, END_CEILING_MS)
        maybeExit()
    }

    private fun maybeExit() {
        if (disconnected && destroyed) Process.killProcess(Process.myPid())
    }

    /** Only our own uid calls in (the service isn't exported; a belt). */
    private fun ours(): Boolean = Binder.getCallingUid() == Process.myUid()

    companion object {
        /** The process's name suffix (the manifest's `android:process`); `MusicApp` does nothing more there. */
        const val PROCESS_SUFFIX = ":web"

        /** This process had a page (or ended its service): it takes no other (one page per process). */
        @Volatile
        private var used = false

        /** The process ends itself at the latest this long after its page was asked to go. */
        private const val END_CEILING_MS = 5_000L
    }
}

/** The main process's binder glue under [RemoteSpotifyHost]. Main thread. */
internal class AndroidWebConnector(context: Context) : WebConnector {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val webProcess = app.packageName + SpotifyWebService.PROCESS_SUFFIX

    override fun bind(sink: WebBindingSink): WebBinding? {
        val conn = Connection(sink)
        val bound = try {
            app.bindService(Intent(app, SpotifyWebService::class.java), conn, Context.BIND_AUTO_CREATE or Context.BIND_INCLUDE_CAPABILITIES)
        } catch (e: SecurityException) {
            false
        }
        if (!bound) {
            runCatching { app.unbindService(conn) }
            return null
        }
        return WebBinding {
            runCatching { app.unbindService(conn) }
        }
    }

    override fun webRunning(): Boolean = webPid() != null

    override fun kill() {
        webPid()?.let { Process.killProcess(it) }
    }

    private fun webPid(): Int? =
        runCatching { app.getSystemService(ActivityManager::class.java)?.runningAppProcesses }.getOrNull()
            ?.firstOrNull { it.processName == webProcess && it.uid == Process.myUid() }?.pid

    private inner class Connection(private val sink: WebBindingSink) : ServiceConnection {
        private var gone = false
        private val death = IBinder.DeathRecipient { main.post { died() } }

        private fun died() {
            if (gone) return
            gone = true
            sink.died()
        }

        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            if (service == null || gone) return died()
            try {
                service.linkToDeath(death, 0)
            } catch (e: RemoteException) {
                return died()
            }
            sink.connected(Link(ISpotifyWeb.Stub.asInterface(service), Events(sink)))
        }

        override fun onServiceDisconnected(name: ComponentName?) = died()

        override fun onBindingDied(name: ComponentName?) = died()

        override fun onNullBinding(name: ComponentName?) = died()
    }

    /** main -> web; a failed call is the process's death, which the death recipient reports. */
    private class Link(private val web: ISpotifyWeb, private val events: ISpotifyWebEvents) : WebLink {
        private fun send(block: () -> Unit) {
            try {
                block()
            } catch (e: RemoteException) {
                // its death comes through the recipient
            }
        }

        override fun open(fakeBase: String?, clearFirst: Boolean) = send { web.open(fakeBase, clearFirst, events) }
        override fun token(requestId: Int, token: String?) = send { web.token(requestId, token) }
        override fun seek(positionMs: Long) = send { web.seek(positionMs) }
        override fun volume(level: Double) = send { web.volume(level) }
        override fun command(name: String) = send { web.command(name) }
        override fun destroy() = send { web.destroy() }
    }

    /** web -> main: each event posted to main in its order of arrival (one-way calls to one binder come in order). */
    private inner class Events(private val sink: WebBindingSink) : ISpotifyWebEvents.Stub() {
        private fun post(s: WebSignal) {
            if (Binder.getCallingUid() != Process.myUid()) return
            main.post { sink.signal(s) }
        }

        override fun attached(pid: Int) = post(WebSignal.Attached(pid))

        override fun ready(deviceId: String?) {
            if (deviceId != null && SpotifyPage.DEVICE_ID.matches(deviceId)) post(WebSignal.Page(HostEvent.Ready(deviceId)))
        }

        override fun notReady(deviceId: String?) {
            if (deviceId != null && SpotifyPage.DEVICE_ID.matches(deviceId)) post(WebSignal.Page(HostEvent.NotReady(deviceId)))
        }

        override fun needToken(requestId: Int) = post(WebSignal.Page(HostEvent.NeedToken(requestId)))

        override fun state(json: String?) {
            if (json == null || json.length > SdkState.MAX_BYTES) return
            val snapshot = SdkState.parse(json) ?: return
            post(WebSignal.Page(HostEvent.State(snapshot, json)))
        }

        override fun error(kind: String?, message: String?) {
            if (kind == null || kind !in SpotifyPage.ERROR_KINDS) return
            post(WebSignal.Page(HostEvent.Error(kind, message.orEmpty().take(SpotifyPage.MESSAGE_MAX))))
        }

        override fun log(line: String?) {
            if (line != null) post(WebSignal.Log(line.take(LOG_MAX)))
        }

        override fun gone() = post(WebSignal.Gone)

        override fun stale() = post(WebSignal.Stale)
    }

    private companion object {
        /** A web process's log line is cut to this. */
        const val LOG_MAX = 300
    }
}
