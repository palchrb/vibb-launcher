// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.android.appremote.api.error.SpotifyConnectionTerminatedException
import com.spotify.android.appremote.api.error.SpotifyDisconnectedException
import com.spotify.android.appremote.api.error.SpotifyRemoteServiceException
import com.spotify.protocol.client.CallResult
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The App Remote side of the spike: one connection held in the process (not the activity), every
 * call, callback, error and reconnect logged. Main thread only.
 */
object SpotifyEngine {
    private const val POLL_MS = 60_000L
    private val RECONNECT_DELAYS_MS = longArrayOf(5_000, 15_000, 60_000, 300_000)

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    private var remote: SpotifyAppRemote? = null
    private var stateSub: Subscription<PlayerState>? = null
    private var contextSub: Subscription<PlayerContext>? = null
    private var connecting = false
    private var reconnectAttempt = 0
    private var reconnectPending: Runnable? = null

    var lastState: PlayerState? = null
        private set
    var lastContext: PlayerContext? = null
        private set

    /** System.currentTimeMillis() of the last player-state or context update (0 = none yet). */
    var lastUpdateAt: Long = 0
        private set

    val connected: Boolean get() = remote?.isConnected == true

    val busy: Boolean get() = connecting

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() = listeners.forEach { it() }

    private fun log(message: String) = SpikeLog.log(message)

    /**
     * [showAuthView] = true only in setup mode: App Remote may then start Spotify's consent screen.
     * The kid-mode connect passes false and must never start a Spotify activity.
     */
    fun connect(context: Context, showAuthView: Boolean, reason: String) {
        val app = context.applicationContext
        val clientId = Prefs.clientId(app)
        if (clientId.isBlank()) {
            log("connect refused: no client ID saved")
            return
        }
        if (connecting) {
            log("connect ignored: a connect is already in flight")
            return
        }
        cancelReconnect()
        dropConnection("before a new connect")
        log(PackageProbe.describe(app))
        if (showAuthView) {
            log("SETUP connect(showAuthView=true) [$reason]: Spotify's consent screen may open now")
        } else {
            log("connect(showAuthView=false) [$reason]: watch logcat ActivityTaskManager for any com.spotify start")
        }
        connecting = true
        changed()
        val started = SystemClock.elapsedRealtime()
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(Prefs.REDIRECT_URI)
            .showAuthView(showAuthView)
            .build()
        SpotifyAppRemote.connect(app, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                connecting = false
                remote = appRemote
                reconnectAttempt = 0
                log("connected after ${SystemClock.elapsedRealtime() - started} ms [$reason]")
                subscribe(appRemote)
                schedulePoll()
                changed()
            }

            override fun onFailure(error: Throwable) {
                // App Remote reports a later loss of the connection here too.
                val wasConnected = remote != null
                connecting = false
                remote = null
                cancelSubscriptions()
                main.removeCallbacks(poll)
                val ms = SystemClock.elapsedRealtime() - started
                if (wasConnected) {
                    log("connection LOST after ${ms / 1000} s: ${SpikeLog.describe(error)}")
                } else {
                    log("connect FAILED after $ms ms [$reason]: ${SpikeLog.describe(error)}")
                }
                log(PackageProbe.describe(app))
                changed()
                maybeReconnect(app, error)
            }
        })
    }

    private fun maybeReconnect(context: Context, error: Throwable) {
        if (!Prefs.autoReconnect(context)) return
        val retryable = error is SpotifyDisconnectedException || error is SpotifyConnectionTerminatedException ||
            error is SpotifyRemoteServiceException
        if (!retryable) {
            log("auto-reconnect: not retrying after ${error.javaClass.simpleName}")
            return
        }
        if (reconnectAttempt >= RECONNECT_DELAYS_MS.size) {
            log("auto-reconnect: gave up after $reconnectAttempt attempts")
            return
        }
        val delay = RECONNECT_DELAYS_MS[reconnectAttempt++]
        log("auto-reconnect: attempt $reconnectAttempt in ${delay / 1000} s (showAuthView=false)")
        val task = Runnable {
            reconnectPending = null
            connect(context, showAuthView = false, reason = "auto-reconnect #$reconnectAttempt")
        }
        reconnectPending = task
        main.postDelayed(task, delay)
    }

    private fun cancelReconnect() {
        reconnectPending?.let {
            main.removeCallbacks(it)
            log("auto-reconnect: pending attempt cancelled")
        }
        reconnectPending = null
    }

    private fun subscribe(appRemote: SpotifyAppRemote) {
        cancelSubscriptions()
        stateSub = appRemote.playerApi.subscribeToPlayerState().apply {
            setEventCallback { state ->
                lastState = state
                lastUpdateAt = System.currentTimeMillis()
                log("state: ${describe(state)}")
                changed()
            }
            setErrorCallback { log("player-state subscription error: ${SpikeLog.describe(it)}") }
        }
        contextSub = appRemote.playerApi.subscribeToPlayerContext().apply {
            setEventCallback { ctx ->
                lastContext = ctx
                lastUpdateAt = System.currentTimeMillis()
                log("context: uri=${ctx.uri} title='${ctx.title}' type=${ctx.type}")
                changed()
            }
            setErrorCallback { log("player-context subscription error: ${SpikeLog.describe(it)}") }
        }
        log("subscribed to player state and context")
    }

    private fun cancelSubscriptions() {
        stateSub?.cancel()
        contextSub?.cancel()
        stateSub = null
        contextSub = null
    }

    /** Every minute while connected: the position, so a screen-off run reads from the log. */
    private val poll = object : Runnable {
        override fun run() {
            val r = remote ?: return
            track("poll getPlayerState", r.playerApi.playerState, quiet = true) { state ->
                lastState = state
                lastUpdateAt = System.currentTimeMillis()
                log("poll: ${describe(state)}")
                changed()
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    private fun schedulePoll() {
        main.removeCallbacks(poll)
        main.postDelayed(poll, POLL_MS)
    }

    fun disconnect(reason: String) {
        cancelReconnect()
        if (remote == null && !connecting) {
            log("disconnect [$reason]: not connected")
            return
        }
        dropConnection(reason)
        changed()
    }

    private fun dropConnection(reason: String) {
        val r = remote ?: return
        cancelSubscriptions()
        main.removeCallbacks(poll)
        SpotifyAppRemote.disconnect(r)
        remote = null
        log("disconnected [$reason]")
    }

    fun play(uri: String) {
        val r = requireRemote("play") ?: return
        log("call play($uri)")
        track("play", r.playerApi.play(uri))
    }

    fun skipToIndex(uri: String, index: Int) {
        val r = requireRemote("skipToIndex") ?: return
        log("call skipToIndex($uri, $index)")
        track("skipToIndex", r.playerApi.skipToIndex(uri, index))
    }

    fun seekTo(ms: Long) {
        val r = requireRemote("seekTo") ?: return
        log("call seekTo($ms)")
        track("seekTo", r.playerApi.seekTo(ms))
    }

    fun pause() {
        val r = requireRemote("pause") ?: return
        log("call pause()")
        track("pause", r.playerApi.pause())
    }

    fun resume() {
        val r = requireRemote("resume") ?: return
        log("call resume()")
        track("resume", r.playerApi.resume())
    }

    fun capabilities() {
        val r = requireRemote("getCapabilities") ?: return
        log("call getCapabilities()")
        track("getCapabilities", r.userApi.capabilities) { log("capabilities: canPlayOnDemand=${it.canPlayOnDemand}") }
    }

    /**
     * Item 4 (resume accuracy), variant A: play(uri), then skipToIndex(uri, index), then
     * seekTo(ms), each after the previous one answered. Listen for a blip of the first track.
     */
    fun resumeA(uri: String, index: Int, ms: Long) {
        val r = requireRemote("resume A") ?: return
        log("RESUME A: play -> skipToIndex($index) -> seekTo($ms) on $uri")
        track("A play", r.playerApi.play(uri)) {
            track("A skipToIndex", r.playerApi.skipToIndex(uri, index)) {
                track("A seekTo", r.playerApi.seekTo(ms))
            }
        }
    }

    /** Item 4, variant B: skipToIndex(uri, index) starts the context at the track, then seekTo(ms). */
    fun resumeB(uri: String, index: Int, ms: Long) {
        val r = requireRemote("resume B") ?: return
        log("RESUME B: skipToIndex($index) -> seekTo($ms) on $uri")
        track("B skipToIndex", r.playerApi.skipToIndex(uri, index)) {
            track("B seekTo", r.playerApi.seekTo(ms))
        }
    }

    private fun requireRemote(call: String): SpotifyAppRemote? {
        val r = remote
        if (r == null || !r.isConnected) log("$call: not connected (press Connect first)")
        return r?.takeIf { it.isConnected }
    }

    private fun <T> track(name: String, result: CallResult<T>, quiet: Boolean = false, then: ((T) -> Unit)? = null) {
        val started = SystemClock.elapsedRealtime()
        result.setResultCallback { data ->
            if (!quiet) log("$name ok (${SystemClock.elapsedRealtime() - started} ms)")
            then?.invoke(data)
        }
        result.setErrorCallback { log("$name ERROR after ${SystemClock.elapsedRealtime() - started} ms: ${SpikeLog.describe(it)}") }
    }

    fun describe(state: PlayerState): String {
        val t = state.track
        val trackText = if (t == null) "no track" else "${t.uri} '${t.name}' - ${t.artist?.name} (${t.duration} ms)"
        val options = state.playbackOptions
        val mode = if (options == null) "" else " shuffle=${options.isShuffling} repeat=${options.repeatMode}"
        return "paused=${state.isPaused} pos=${state.playbackPosition} ms$mode track=$trackText"
    }
}
