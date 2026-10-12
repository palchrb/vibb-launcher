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
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.TextView

/**
 * Item 10: Spotify's Web Playback SDK (sdk.scdn.co/spotify-player.js, loaded at run time only) in
 * a WebView of this app, so the phone would need no Spotify app at all. The page is the asset
 * web-playback.html under an https origin; the token reaches it only through [Bridge.token].
 */
class WebPlaybackActivity : Activity() {
    private lateinit var tokenField: EditText
    private lateinit var desktopUa: CheckBox
    private lateinit var status: TextView
    private lateinit var playerWeb: WebView
    private lateinit var loginWeb: WebView
    private var deviceId: String? = null
    private var lastEvent: String = "-"
    private var userAgent: String = "-"
    private var pkce: WebApi.Pkce? = null

    /** While the full-screen login is up, Back cancels it instead of closing the probe. */
    private val cancelLogin = OnBackInvokedCallback {
        SpikeLog.log("PKCE: login cancelled (Back)")
        pkce = null
        hideLogin()
    }

    /** The token the page gets (read on a WebView thread, so not from the EditText). */
    @Volatile
    private var pageToken: String = ""

    @SuppressLint("SetJavaScriptEnabled") // The probe's whole point is running Spotify's JS SDK.
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web)
        setTitle(R.string.web_title)
        SpikeLog.log("WebPlaybackActivity onCreate")
        tokenField = findViewById(R.id.token)
        desktopUa = findViewById(R.id.desktop_ua)
        status = findViewById(R.id.web_status)
        playerWeb = findViewById(R.id.player_web)
        loginWeb = findViewById(R.id.login_web)

        playerWeb.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        // Keep the renderer important while the screen is off (the screen-off run).
        playerWeb.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(playerWeb, true)
        playerWeb.addJavascriptInterface(Bridge(), "SpikeBridge")
        playerWeb.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                val asked = request.resources.toList()
                // The default client denies everything; EME needs the protected-media grant.
                val grant = asked.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
                SpikeLog.log("web: permission request from ${request.origin} for $asked -> granting $grant")
                if (grant.isEmpty()) request.deny() else request.grant(grant.toTypedArray())
            }

            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                SpikeLog.log("web console ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
        playerWeb.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                SpikeLog.log("web: load error ${error.errorCode} '${error.description}' for ${request.url} (main frame ${request.isForMainFrame})")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                SpikeLog.log("web: renderer GONE (crashed=${detail.didCrash()}, priority ${detail.rendererPriorityAtExit()})")
                finish()
                return true
            }
        }

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

        findViewById<CheckBox>(R.id.web_keep_alive).apply {
            isChecked = KeepAliveService.running
            setOnCheckedChangeListener { _, on ->
                if (on) {
                    if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
                    }
                    KeepAliveService.start(this@WebPlaybackActivity)
                } else {
                    KeepAliveService.stop(this@WebPlaybackActivity)
                }
            }
        }
        findViewById<Button>(R.id.btn_pkce).setOnClickListener { startLogin() }
        findViewById<Button>(R.id.btn_load).setOnClickListener { loadPlayer() }
        findViewById<Button>(R.id.btn_play_here).setOnClickListener { playHere() }
        findViewById<Button>(R.id.btn_toggle).setOnClickListener { js("spikeToggle()") }
        findViewById<Button>(R.id.btn_web_state).setOnClickListener { js("spikeState()") }
        findViewById<Button>(R.id.btn_unload).setOnClickListener { unload() }
        render()
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
        if (token().isBlank()) {
            SpikeLog.log("web: no access token (paste one or use Get token)")
            return
        }
        deviceId = null
        pageToken = token()
        val settings = playerWeb.settings
        val default = WebSettings.getDefaultUserAgent(this)
        settings.userAgentString = if (desktopUa.isChecked) desktopUserAgent(default) else null
        userAgent = settings.userAgentString
        SpikeLog.log("web: loading the player page, ${if (desktopUa.isChecked) "DESKTOP" else "default WebView"} user agent: $userAgent")
        val html = assets.open("web-playback.html").bufferedReader().use { it.readText() }
        playerWeb.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "utf-8", null)
        render()
    }

    private fun desktopUserAgent(default: String): String {
        val chrome = Regex("Chrome/([0-9.]+)").find(default)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    private fun playHere() {
        val id = deviceId
        val uri = Prefs.playUri(this)
        when {
            id == null -> SpikeLog.log("web: no device id yet (wait for 'ready')")
            !uri.startsWith("spotify:") -> SpikeLog.log("web: set a spotify: URI on the main screen")
            token().isBlank() -> SpikeLog.log("web: no access token")
            else -> WebApi.playOnDevice(token(), id, uri)
        }
    }

    private fun unload() {
        js("spikeDisconnect()")
        playerWeb.loadUrl("about:blank")
        deviceId = null
        SpikeLog.log("web: player unloaded")
        render()
    }

    private fun js(code: String) = playerWeb.evaluateJavascript(code, null)

    private fun render() {
        status.text = getString(R.string.web_status_fmt, deviceId ?: "-", userAgent, lastEvent)
    }

    /** Called by the page's JS on a WebView thread. */
    inner class Bridge {
        @JavascriptInterface
        fun token(): String = pageToken

        @JavascriptInterface
        fun log(message: String) {
            SpikeLog.log("web: $message")
            runOnUiThread {
                lastEvent = SpikeLog.redact(message).take(200)
                render()
            }
        }

        @JavascriptInterface
        fun ready(id: String) {
            runOnUiThread {
                deviceId = id
                render()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        SpikeLog.log("WebPlaybackActivity window focus ${if (hasFocus) "gained" else "LOST"}")
    }

    override fun onStop() {
        // No WebView.onPause()/pauseTimers(): the player must keep running with the screen off.
        SpikeLog.log("WebPlaybackActivity onStop (player keeps running)")
        super.onStop()
    }

    override fun onDestroy() {
        SpikeLog.log("WebPlaybackActivity onDestroy: player destroyed")
        playerWeb.destroy()
        loginWeb.destroy()
        super.onDestroy()
    }
}
