// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
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
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Item 10's player: Spotify's Web Playback SDK (sdk.scdn.co/spotify-player.js, loaded at run time
 * only) in a WebView made with the application context and owned by [KeepAliveService], like
 * Vibb's playback service owns its player. [WebPlaybackActivity] only attaches it to its screen
 * while visible ([attach]/[detach], never destroy): the player survives Back, the activity's
 * destruction and the launcher's lock. Only [unload] or the service stopping destroys it. The page
 * is the asset web-playback.html under an https origin; the token reaches it only through
 * [Bridge.token]. Main thread only (the bridge posts to it).
 */
// The WebView is made with the application context (no activity is held) and lives exactly as long
// as the keep-alive service's player: a process-wide holder is the point here.
@SuppressLint("StaticFieldLeak")
object WebPlayer {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm:ss")
    private var webView: WebView? = null
    private var appContext: Context? = null
    private var loadedAt: LocalTime? = null
    private var attached = false

    var deviceId: String? = null
        private set
    var lastEvent: String = "-"
        private set
    var userAgent: String = "-"
        private set

    /** The token the page gets (read on a WebView thread). */
    @Volatile
    private var pageToken: String = ""

    val alive: Boolean get() = webView != null

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun changed() = listeners.forEach { it() }

    /** One line for the screens: is the player alive, and is a screen showing it? */
    fun status(): String {
        val since = loadedAt ?: return "web player: none"
        val shown = if (attached) "on screen" else "headless (no screen attached)"
        val lock = if (Prefs.wakeLock(appContext ?: return "web player: alive")) ", wake lock option on" else ""
        return "web player: ALIVE since ${since.format(timeFormat)}, $shown, keep-alive ${if (KeepAliveService.running) "running" else "NOT running"}$lock"
    }

    /** Creates the player (if needed) in the keep-alive service's care and loads the page. */
    @SuppressLint("SetJavaScriptEnabled") // The probe's whole point is running Spotify's JS SDK.
    fun load(context: Context, token: String, desktopUa: Boolean) {
        val app = context.applicationContext
        appContext = app
        SpikeLog.secret(token)
        pageToken = token
        KeepAliveService.acquire(app, KeepAliveService.HOLDER_WEB_PLAYER)
        val view = webView ?: create(app).also { webView = it }
        deviceId = null
        val default = WebSettings.getDefaultUserAgent(app)
        view.settings.userAgentString = if (desktopUa) desktopUserAgent(default) else null
        userAgent = view.settings.userAgentString
        SpikeLog.log("web: loading the player page, ${if (desktopUa) "DESKTOP" else "default WebView"} user agent: $userAgent")
        val html = app.assets.open("web-playback.html").bufferedReader().use { it.readText() }
        view.loadDataWithBaseURL("https://appassets.androidplatform.net/", html, "text/html", "utf-8", null)
        loadedAt = LocalTime.now()
        changed()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(app: Context): WebView {
        val view = WebView(app)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
        }
        // Keep the renderer important while no screen shows it (the screen-off run).
        view.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.addJavascriptInterface(Bridge(), "SpikeBridge")
        view.webChromeClient = object : WebChromeClient() {
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
        view.webViewClient = object : WebViewClient() {
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                SpikeLog.log("web: load error ${error.errorCode} '${error.description}' for ${request.url} (main frame ${request.isForMainFrame})")
            }

            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                SpikeLog.log("web: renderer GONE (crashed=${detail.didCrash()}, priority ${detail.rendererPriorityAtExit()})")
                main.post { unload("renderer gone") }
                return true
            }
        }
        SpikeLog.log("web: player WebView created (application context, owned by the keep-alive service)")
        return view
    }

    private fun desktopUserAgent(default: String): String {
        val chrome = Regex("Chrome/([0-9.]+)").find(default)?.groupValues?.get(1) ?: "130.0.0.0"
        return "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$chrome Safari/537.36"
    }

    fun js(code: String) {
        val view = webView
        if (view == null) SpikeLog.log("web: no player loaded") else view.evaluateJavascript(code, null)
    }

    /** Shows the player in [container] (the activity's onStart). */
    fun attach(container: ViewGroup) {
        val view = webView ?: return
        (view.parent as? ViewGroup)?.removeView(view)
        container.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        attached = true
        SpikeLog.log("web: player attached to the screen")
        changed()
    }

    /** Takes the player off [container] (the activity's onStop); it keeps running headless. */
    fun detach(container: ViewGroup) {
        val view = webView ?: return
        if (view.parent === container) container.removeView(view)
        attached = false
        SpikeLog.log("web: player detached from the screen, still ALIVE (headless)")
        changed()
    }

    /** Unload: the player's end; the keep-alive service stops unless the checkbox holds it. */
    fun unload(reason: String) {
        if (webView == null) {
            SpikeLog.log("web: unload [$reason]: no player loaded")
            return
        }
        destroy(reason)
        appContext?.let { KeepAliveService.release(it, KeepAliveService.HOLDER_WEB_PLAYER) }
    }

    /** Destroys the WebView (Unload, or the service stopping). */
    fun destroy(reason: String) {
        val view = webView ?: return
        webView = null
        view.evaluateJavascript("spikeDisconnect()", null)
        (view.parent as? ViewGroup)?.removeView(view)
        view.destroy()
        deviceId = null
        loadedAt = null
        attached = false
        SpikeLog.log("web: player DESTROYED [$reason]")
        changed()
    }

    /** Called by the page's JS on a WebView thread. */
    class Bridge {
        @JavascriptInterface
        fun token(): String = pageToken

        @JavascriptInterface
        fun log(message: String) {
            SpikeLog.log("web: $message")
            main.post {
                lastEvent = SpikeLog.redact(message).take(200)
                changed()
            }
        }

        @JavascriptInterface
        fun ready(id: String) {
            main.post {
                deviceId = id
                changed()
            }
        }
    }
}
