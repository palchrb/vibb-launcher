// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.format.DateUtils
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.text.DateFormat
import java.util.Date

/**
 * The spike's one screen: settings, the App Remote calls, the probes, the live player state and the
 * log. Its own lifecycle is logged too: a window-focus loss right after a kid-mode connect means
 * something (Spotify, BlockedAppActivity) came up over it.
 */
class MainActivity : Activity() {
    private lateinit var clientId: EditText
    private lateinit var playUri: EditText
    private lateinit var skipIndex: EditText
    private lateinit var seekMs: EditText
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var logStatus: TextView
    private val main = Handler(Looper.getMainLooper())
    private val refreshLog: () -> Unit = {
        logView.text = SpikeLog.recentText().lines().asReversed().joinToString("\n")
        logStatus.text = SpikeLog.fileStatus()
    }
    private val refreshState: () -> Unit = { renderState() }
    private val tick = object : Runnable {
        override fun run() {
            renderState()
            logStatus.text = SpikeLog.fileStatus()
            main.postDelayed(this, 1_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        SpikeLog.log("MainActivity onCreate")
        clientId = findViewById(R.id.client_id)
        playUri = findViewById(R.id.play_uri)
        skipIndex = findViewById(R.id.skip_index)
        seekMs = findViewById(R.id.seek_ms)
        stateView = findViewById(R.id.state)
        logView = findViewById(R.id.log)
        logStatus = findViewById(R.id.log_status)
        findViewById<TextView>(R.id.redirect).text = getString(R.string.redirect_fmt, Prefs.REDIRECT_URI)

        clientId.setText(Prefs.clientId(this))
        playUri.setText(Prefs.playUri(this))
        skipIndex.setText(Prefs.skipIndex(this))
        seekMs.setText(Prefs.seekMs(this))

        findViewById<CheckBox>(R.id.auto_reconnect).apply {
            isChecked = Prefs.autoReconnect(this@MainActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setAutoReconnect(this@MainActivity, on)
                SpikeLog.log("auto-reconnect ${if (on) "ON" else "OFF"}")
            }
        }
        findViewById<CheckBox>(R.id.keep_alive).apply {
            isChecked = KeepAliveService.running
            setOnCheckedChangeListener { _, on -> keepAlive(on) }
        }

        click(R.id.btn_save) {
            save()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
        click(R.id.btn_authorize) { SpotifyEngine.connect(this, showAuthView = true, reason = "setup button") }
        click(R.id.btn_connect) { SpotifyEngine.connect(this, showAuthView = false, reason = "connect button") }
        click(R.id.btn_reconnect) {
            SpikeLog.log("reconnect after force-stop requested")
            SpotifyEngine.connect(this, showAuthView = false, reason = "reconnect-after-kill button")
        }
        click(R.id.btn_disconnect) { SpotifyEngine.disconnect("disconnect button") }
        click(R.id.btn_play) { uri()?.let { SpotifyEngine.play(it) } }
        click(R.id.btn_skip) {
            val u = uri() ?: return@click
            index()?.let { SpotifyEngine.skipToIndex(u, it) }
        }
        click(R.id.btn_seek) { ms()?.let { SpotifyEngine.seekTo(it) } }
        click(R.id.btn_pause) { SpotifyEngine.pause() }
        click(R.id.btn_resume) { SpotifyEngine.resume() }
        click(R.id.btn_caps) { SpotifyEngine.capabilities() }
        click(R.id.btn_resume_a) {
            val u = uri() ?: return@click
            val i = index() ?: return@click
            ms()?.let { SpotifyEngine.resumeA(u, i, it) }
        }
        click(R.id.btn_resume_b) {
            val u = uri() ?: return@click
            val i = index() ?: return@click
            ms()?.let { SpotifyEngine.resumeB(u, i, it) }
        }
        click(R.id.btn_spotify_state) { SpikeLog.log(PackageProbe.describe(this)) }
        click(R.id.btn_sessions) { SessionProbe.list(this) }
        click(R.id.btn_pause_session) { SessionProbe.pauseSpotify(this) }
        click(R.id.btn_web) { startActivity(Intent(this, WebPlaybackActivity::class.java)) }
        click(R.id.btn_copy_log) { copyLog() }
    }

    private fun click(id: Int, action: () -> Unit) {
        findViewById<Button>(id).setOnClickListener(View.OnClickListener { action() })
    }

    private fun save() {
        Prefs.save(this, clientId.text.toString(), playUri.text.toString(), skipIndex.text.toString(), seekMs.text.toString())
        SpikeLog.log("settings saved (client ID ${if (Prefs.clientId(this).isBlank()) "empty" else "set"})")
    }

    private fun uri(): String? {
        save()
        return Prefs.playUri(this).takeIf { it.startsWith("spotify:") }
            ?: null.also { SpikeLog.log("no spotify: URI in the URI field") }
    }

    private fun index(): Int? = Prefs.skipIndex(this).toIntOrNull()?.takeIf { it >= 0 }
        ?: null.also { SpikeLog.log("index must be a number >= 0") }

    private fun ms(): Long? = Prefs.seekMs(this).toLongOrNull()?.takeIf { it >= 0 }
        ?: null.also { SpikeLog.log("seek must be a number of ms >= 0") }

    private fun keepAlive(on: Boolean) {
        if (on) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
            }
            KeepAliveService.start(this)
        } else {
            KeepAliveService.stop(this)
        }
    }

    private fun copyLog() {
        SpikeLog.readFile(3_000) { text ->
            val lines = if (text.isEmpty()) 0 else text.lines().size
            getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("spike log", text))
            SpikeLog.log("log copied to the clipboard ($lines lines)")
            Toast.makeText(this, getString(R.string.log_copied, lines), Toast.LENGTH_SHORT).show()
        }
    }

    private fun renderState() {
        val state = SpotifyEngine.lastState
        val track = state?.track
        val ctx = SpotifyEngine.lastContext
        val at = SpotifyEngine.lastUpdateAt
        stateView.text = getString(
            R.string.state_fmt,
            SpotifyEngine.connected.toString(),
            if (SpotifyEngine.busy) getString(R.string.state_busy) else "",
            state?.isPaused?.toString() ?: "-",
            state?.let { DateUtils.formatElapsedTime(it.playbackPosition / 1000) } ?: "-",
            track?.let { "${it.name} - ${it.artist?.name} (${it.uri})" } ?: "-",
            ctx?.let { "${it.title} (${it.uri})" } ?: "-",
            if (at == 0L) "-" else DateFormat.getTimeInstance(DateFormat.MEDIUM).format(Date(at)),
            if (at == 0L) "-" else DateUtils.formatElapsedTime((System.currentTimeMillis() - at) / 1000),
        )
    }

    override fun onStart() {
        super.onStart()
        SpikeLog.log("MainActivity onStart")
        SpikeLog.addListener(refreshLog)
        SpotifyEngine.addListener(refreshState)
        refreshLog()
        main.post(tick)
    }

    override fun onResume() {
        super.onResume()
        SpikeLog.log("MainActivity onResume")
    }

    override fun onPause() {
        SpikeLog.log("MainActivity onPause")
        super.onPause()
    }

    override fun onStop() {
        SpikeLog.log("MainActivity onStop")
        SpikeLog.removeListener(refreshLog)
        SpotifyEngine.removeListener(refreshState)
        main.removeCallbacks(tick)
        super.onStop()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        SpikeLog.log("MainActivity window focus ${if (hasFocus) "gained" else "LOST"}")
    }
}
