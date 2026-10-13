// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher

/**
 * Item 10's screen: the token (PKCE login or pasted), the player's controls and its state. The
 * player itself is [WebPlayer], owned by the keep-alive service: this activity only attaches its
 * WebView while visible, so Back, the activity's destruction and the lock never stop the music.
 */
class WebPlaybackActivity : Activity() {
    private lateinit var tokenField: EditText
    private lateinit var desktopUa: CheckBox
    private lateinit var status: TextView
    private lateinit var logStatus: TextView
    private lateinit var playerContainer: ViewGroup
    private lateinit var loginWeb: WebView
    private var pkce: WebApi.Pkce? = null
    private val refresh: () -> Unit = { render() }

    /** While the full-screen login is up, Back cancels it instead of closing the probe. */
    private val cancelLogin = OnBackInvokedCallback {
        SpikeLog.log("PKCE: login cancelled (Back)")
        pkce = null
        hideLogin()
    }

    @SuppressLint("SetJavaScriptEnabled") // Spotify's login page needs JavaScript.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        setTitle(R.string.web_title)
        SpikeLog.log("WebPlaybackActivity onCreate (${WebPlayer.status()})")
        tokenField = findViewById(R.id.token)
        desktopUa = findViewById(R.id.desktop_ua)
        status = findViewById(R.id.web_status)
        logStatus = findViewById(R.id.log_status)
        playerContainer = findViewById(R.id.player_container)
        loginWeb = findViewById(R.id.login_web)

        loginWeb.settings.javaScriptEnabled = true
        loginWeb.settings.domStorageEnabled = true
        loginWeb.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (!url.toString().startsWith(Prefs.REDIRECT_URI)) return false
                onRedirect(url)
                return true
            }
        }

        findViewById<CheckBox>(R.id.web_wake_lock).apply {
            isChecked = Prefs.wakeLock(this@WebPlaybackActivity)
            setOnCheckedChangeListener { _, on ->
                Prefs.setWakeLock(this@WebPlaybackActivity, on)
                SpikeLog.log("wake lock option ${if (on) "ON" else "OFF"}")
                KeepAliveService.wakeLockChanged()
            }
        }
        findViewById<Button>(R.id.btn_pkce).setOnClickListener { startLogin() }
        findViewById<Button>(R.id.btn_load).setOnClickListener { loadPlayer() }
        findViewById<Button>(R.id.btn_play_here).setOnClickListener { playHere() }
        findViewById<Button>(R.id.btn_toggle).setOnClickListener { WebPlayer.js("spikeToggle()") }
        findViewById<Button>(R.id.btn_web_state).setOnClickListener { WebPlayer.js("spikeState()") }
        findViewById<Button>(R.id.btn_unload).setOnClickListener { WebPlayer.unload("Unload button") }
    }

    private fun token(): String = tokenField.text.toString().trim().also { SpikeLog.secret(it) }

    private fun startLogin() {
        val clientId = Prefs.clientId(this)
        if (clientId.isBlank()) {
            SpikeLog.log("PKCE: save a client ID on the main screen first")
            return
        }
        val p = WebApi.startPkce(clientId)
        pkce = p
        SpikeLog.log("PKCE: opening Spotify's login in an in-app WebView (setup step; the parent logs in)")
        loginWeb.visibility = View.VISIBLE
        loginWeb.loadUrl(p.authorizeUrl)
        onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, cancelLogin)
    }

    private fun hideLogin() {
        onBackInvokedDispatcher.unregisterOnBackInvokedCallback(cancelLogin)
        loginWeb.visibility = View.GONE
        loginWeb.loadUrl("about:blank")
    }

    private fun onRedirect(url: Uri) {
        hideLogin()
        val p = pkce ?: return
        pkce = null
        val error = url.getQueryParameter("error")
        val code = url.getQueryParameter("code")
        when {
            error != null -> SpikeLog.log("PKCE: authorization refused: $error")
            url.getQueryParameter("state") != p.state -> SpikeLog.log("PKCE: state mismatch, ignored")
            code == null -> SpikeLog.log("PKCE: redirect without a code")
            else -> WebApi.exchange(Prefs.clientId(this), code, p) { token ->
                if (token != null) tokenField.setText(token)
            }
        }
    }

    private fun loadPlayer() {
        val token = token()
        if (token.isBlank()) {
            SpikeLog.log("web: no access token (paste one or use Get token)")
            return
        }
        // The keep-alive service's notification needs this on Android 13+.
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        WebPlayer.load(this, token, desktopUa.isChecked)
        WebPlayer.attach(playerContainer)
    }

    private fun playHere() {
        val id = WebPlayer.deviceId
        val uri = Prefs.playUri(this)
        when {
            id == null -> SpikeLog.log("web: no device id yet (wait for 'ready')")
            !uri.startsWith("spotify:") -> SpikeLog.log("web: set a spotify: URI on the main screen")
            token().isBlank() -> SpikeLog.log("web: no access token")
            else -> WebApi.playOnDevice(token(), id, uri)
        }
    }

    private fun render() {
        status.text = getString(R.string.web_status_fmt, WebPlayer.status(), WebPlayer.deviceId ?: "-", WebPlayer.userAgent, WebPlayer.lastEvent)
        logStatus.text = SpikeLog.fileStatus()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        SpikeLog.log("WebPlaybackActivity window focus ${if (hasFocus) "gained" else "LOST"}")
    }

    override fun onStart() {
        super.onStart()
        SpikeLog.addListener(refresh)
        WebPlayer.addListener(refresh)
        WebPlayer.attach(playerContainer)
        render()
    }

    override fun onStop() {
        SpikeLog.removeListener(refresh)
        WebPlayer.removeListener(refresh)
        WebPlayer.detach(playerContainer)
        SpikeLog.log("WebPlaybackActivity onStop (${WebPlayer.status()})")
        super.onStop()
    }

    override fun onDestroy() {
        SpikeLog.log("WebPlaybackActivity onDestroy (${WebPlayer.status()})")
        loginWeb.destroy()
        super.onDestroy()
    }
}
