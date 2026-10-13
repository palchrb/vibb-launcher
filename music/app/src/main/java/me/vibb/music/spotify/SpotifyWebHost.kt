package me.vibb.music.spotify

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import me.vibb.music.BuildConfig
import me.vibb.music.media.MediaCache
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

/*
 * The phone's one web player (design 26c §5.2, §5.3, §7.2): Spotify's Web Playback SDK in a WebView that
 * is never shown. The renderer talks to it only through [SpotifyHost] (SpotifyRendererTest scripts one);
 * [SpotifyWebHost] is the Android one, kept thin: create, settings, the request interceptor (WebPolicy -
 * every frame's requests and the service workers'), the page bridge both ways, destroy, clear.
 *
 * - Made with the application context and owned by the playback service's renderer, never an Activity
 *   (the spike's lesson, spike §7b), and never added to a view hierarchy: the kid's screens open no web
 *   views (SourceScanTest: `WebView` only in this file, no `addView`).
 * - The page is `assets/spotify/player.html` loaded under `https://appassets.androidplatform.net/spotify/`
 *   (the spike's secure origin) with `loadDataWithBaseURL`, and `player.js` from the same origin through
 *   the interceptor - the platform WebView only, no `androidx.webkit` (§11: no new dependency).
 * - Every request passes [WebPolicy.decide]: our two assets, https to Spotify's hosts, the fake's https
 *   name through the debug proxy (the debug switch only, §9.3), anything else an empty 403 (the host
 *   counted for the debug log, never the URL). A WebSocket and a redirect's target aren't seen here
 *   (`shouldInterceptRequest` sees only a redirect's first URL): the DNS filter and the CSP bound them (§5.2,
 *   qa-26c-step5a-code L2). No navigation, no new windows, no file or content access, no geolocation; the
 *   protected-media permission only, for Spotify's SDK origin or ours.
 * - The bridge object's methods run on a WebView thread: each validates and caps its input and posts
 *   to the main thread; a destroyed page's late calls are dropped (its generation).
 * - Logs: fixed words, host names and statuses through [log] (the service passes a debug-only logger),
 *   never a URL, a token, a state or the SDK's message in a release (§7.1).
 */

/** What the page tells the renderer (main thread). */
sealed interface HostEvent {
    /** The SDK's `ready`: this player is Spotify Connect device [deviceId]. */
    data class Ready(val deviceId: String) : HostEvent

    /** The SDK's `not_ready`: device [deviceId] went offline. */
    data class NotReady(val deviceId: String) : HostEvent

    /** The SDK's `getOAuthToken`: answer with `vibb.token(requestId, token or null)`. */
    data class NeedToken(val requestId: Int) : HostEvent

    /** A posted state, parsed ([SdkState.parse]; junk never arrives here). */
    data class State(val snapshot: SdkSnapshot) : HostEvent

    /** `error(kind, message)`: [kind] one of [SpotifyPage.ERROR_KINDS]; [message] for the debug log only. */
    data class Error(val kind: String, val message: String) : HostEvent

    /** The page's renderer process died (`onRenderProcessGone`): the host is gone. */
    data object Gone : HostEvent
}

/** The web player as the renderer sees it (design 26c §5.2): one page, one Connect device, main thread only. */
interface SpotifyHost {
    /** Where [HostEvent]s go (the renderer; main thread). */
    var events: ((HostEvent) -> Unit)?

    /** A page exists (created, not destroyed). */
    val alive: Boolean

    /** Create the page if there is none and connect its player as [SpotifyPage.PLAYER_NAME]; the SDK answers `ready`. */
    fun open()

    /** One host -> page call ([SpotifyPage.call]: `vibb.<name>(<quoted args>)`); nothing without a page. */
    fun call(name: String, vararg args: Any?)

    /** Disconnect and destroy the page: the Connect device leaves the account's list (its `disconnect()` gets its moment). */
    fun destroy()

    /**
     * Clear the web player's cookies, storage and HTTP cache - the old account's state must not linger
     * (§5.2) - and call [done] on the main thread once the cookies are gone (the next page waits for it).
     */
    fun clearStorage(done: () -> Unit)
}

/**
 * The Android web player. [log] gets fixed words and host names only. Main thread for everything but
 * the interceptor and the bridge, which post here.
 */
class SpotifyWebHost(context: Context, private val log: (String) -> Unit) : SpotifyHost {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private var view: WebView? = null

    /** Bumped at every create and destroy: a page's late bridge calls and loads carry their own. */
    @Volatile
    private var generation = 0
    private var connected = -1

    override var events: ((HostEvent) -> Unit)? = null
    override val alive: Boolean get() = view != null

    init {
        useAssets(app)
    }

    @SuppressLint("SetJavaScriptEnabled") // the page runs Spotify's SDK; it loads nothing but §5.2's allowlist
    override fun open() {
        if (view != null) return
        installServiceWorkerClient(log)
        val gen = ++generation
        val v = WebView(app)
        val s = v.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true // the SDK keeps its device state there
        s.mediaPlaybackRequiresUserGesture = false // the spike played on a Web API command, no gesture
        s.setAllowFileAccess(false)
        s.setAllowContentAccess(false)
        s.setGeolocationEnabled(false)
        s.setSupportMultipleWindows(false)
        s.safeBrowsingEnabled = false // only Spotify's hosts and ours; no Google lookups (the manifest says so too)
        // the default cache mode and user agent: the spike's pass
        v.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false) // important with no screen
        CookieManager.getInstance().setAcceptThirdPartyCookies(v, true) // the SDK's iframe is on sdk.scdn.co
        v.addJavascriptInterface(PageBridge(gen), SpotifyPage.BRIDGE)
        v.webChromeClient = Chrome()
        v.webViewClient = Client(gen)
        view = v
        val page = SpotifyPage.html(template(), SpotifyEndpoints.sdkUrl())
        v.loadDataWithBaseURL(SpotifyPage.BASE_URL, page, "text/html", "utf-8", null)
        log("spotify: web player created")
    }

    override fun call(name: String, vararg args: Any?) {
        val v = view ?: return
        v.evaluateJavascript(SpotifyPage.call(name, *args), null)
    }

    override fun destroy() {
        val v = view ?: return
        // out at once: the page's late bridge calls and loads carry the old generation and are dropped
        view = null
        generation++
        connected = -1
        // the SDK's disconnect() tells Spotify the device leaves; the view goes when it has run, or after
        // DISCONNECT_WAIT_MS (qa-26c-step5a-code L5)
        var gone = false
        val kill = Runnable {
            if (gone) return@Runnable
            gone = true
            v.removeJavascriptInterface(SpotifyPage.BRIDGE)
            v.stopLoading()
            v.destroy()
        }
        try {
            v.evaluateJavascript(SpotifyPage.call("disconnect")) { main.postDelayed(kill, DISCONNECT_SETTLE_MS) }
        } catch (e: RuntimeException) {
            main.post(kill)
        }
        main.postDelayed(kill, DISCONNECT_WAIT_MS)
        log("spotify: web player destroyed")
    }

    override fun clearStorage(done: () -> Unit) {
        // the music app has no other web view: the process-wide stores are only Spotify's (also with no page
        // alive - an earlier page's account left them on disk); the HTTP cache is per app, cleared through a
        // throwaway view when no page is alive (qa-26c-step5a-code L6)
        WebStorage.getInstance().deleteAllData()
        val v = view
        if (v != null) {
            v.clearCache(true)
        } else {
            val tmp = WebView(app)
            tmp.clearCache(true)
            tmp.destroy()
        }
        val cookies = CookieManager.getInstance()
        cookies.removeAllCookies {
            cookies.flush()
            main.post(done)
        }
        log("spotify: web player's cookies, storage and cache cleared")
    }

    private fun template(): String = app.assets.open(TEMPLATE).bufferedReader().use { it.readText() }

    private fun post(gen: Int, event: HostEvent) {
        main.post { if (gen == generation && view != null) events?.invoke(event) }
    }

    /** `VibbSpotify`: the page's only way out (§5.3). Each method runs on a WebView thread. */
    private inner class PageBridge(private val gen: Int) {
        @JavascriptInterface
        fun ready(deviceId: String?) {
            if (deviceId != null && SpotifyPage.DEVICE_ID.matches(deviceId)) post(gen, HostEvent.Ready(deviceId))
        }

        @JavascriptInterface
        fun notReady(deviceId: String?) {
            if (deviceId != null && SpotifyPage.DEVICE_ID.matches(deviceId)) post(gen, HostEvent.NotReady(deviceId))
        }

        @JavascriptInterface
        fun needToken(requestId: Int) {
            if (requestId in 1..MAX_REQUEST_ID) post(gen, HostEvent.NeedToken(requestId))
        }

        @JavascriptInterface
        fun state(json: String?) {
            // the cap counts UTF-16 characters, as SdkState.MAX_BYTES does (qa-26c-step4-code's note)
            if (json == null || json.length > SdkState.MAX_BYTES) return
            val snapshot = SdkState.parse(json) ?: return
            post(gen, HostEvent.State(snapshot))
        }

        @JavascriptInterface
        fun error(kind: String?, message: String?) {
            if (kind == null || kind !in SpotifyPage.ERROR_KINDS) return
            val text = message.orEmpty().let { if (it.length > SpotifyPage.MESSAGE_MAX) it.substring(0, SpotifyPage.MESSAGE_MAX) else it }
            post(gen, HostEvent.Error(kind, text))
        }
    }

    private inner class Client(private val gen: Int) : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
            intercept(request, log)

        /** The page never navigates (§5.2). */
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true

        /** The page and its scripts are in: connect the player (once per page). */
        override fun onPageFinished(view: WebView, url: String?) {
            if (gen != generation || connected == gen || view !== this@SpotifyWebHost.view) return
            connected = gen
            view.evaluateJavascript(SpotifyPage.call("connect", SpotifyPage.PLAYER_NAME), null)
        }

        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
            log("spotify: the web player's renderer is gone (crashed=${detail.didCrash()})")
            if (view === this@SpotifyWebHost.view) {
                this@SpotifyWebHost.view = null
                generation++
                connected = -1
                runCatching { view.destroy() }
                main.post { events?.invoke(HostEvent.Gone) }
            }
            return true // the app goes on; the renderer shows "Spotify stoppet"
        }
    }

    private inner class Chrome : WebChromeClient() {
        /** EME only (the spike's grant), only for Spotify's SDK origin or ours; everything else denied. */
        override fun onPermissionRequest(request: PermissionRequest) {
            val origin = request.origin
            val host = origin?.host
            val allowed = origin?.scheme == "https" && (host == SDK_HOST || host == SpotifyPage.ORIGIN_HOST)
            val grant = request.resources.filter { it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID }
            // which origin asks is step 5b's device check (§5.2)
            log("spotify: permission asked by ${host ?: "?"}: ${if (allowed && grant.isNotEmpty()) "protected media granted" else "denied"}")
            if (allowed && grant.isNotEmpty()) request.grant(grant.toTypedArray()) else request.deny()
        }

        /**
         * The page's console (ours and Spotify's iframe) never reaches logcat (§7.1, qa-26c-step5b-code M2): handled
         * here, always - the default writes every message with its source URL, and a failed WebSocket's message names
         * its URL (the SDK's dealer connection may carry the token in its query). A debug build notes the level and
         * the source's host only - never the message or the URL.
         */
        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
            if (BuildConfig.DEBUG) log("spotify: console ${consoleMessage.messageLevel()} from ${consoleMessage.sourceId()?.toHttpUrlOrNull()?.host ?: "?"}")
            return true
        }
    }

    companion object {
        /** The page's template in the app's assets. */
        const val TEMPLATE = "spotify/player.html"

        /** The SDK's origin (§5.2: the protected-media grant). */
        const val SDK_HOST = "sdk.scdn.co"

        /** `needToken`'s request ids are the page's counter. */
        private const val MAX_REQUEST_ID = 1_000_000_000

        /** The debug proxy's call: the stub's long-poll waits 20 s. */
        private const val PROXY_TIMEOUT_S = 45L

        /** The answers the proxy passes back (qa-26c-step4-code: the tone's seek and the stub's fetches need them). */
        private val PROXY_HEADERS = listOf("Content-Range", "Accept-Ranges", "Access-Control-Allow-Origin")

        /** The SDK's `disconnect()` has this long after it ran, and the view at most [DISCONNECT_WAIT_MS], before it goes. */
        private const val DISCONNECT_SETTLE_MS = 300L
        private const val DISCONNECT_WAIT_MS = 1_000L

        @Volatile
        private var swInstalled = false

        /** Requests refused since the process started (the debug log's counter; hosts only). */
        @Volatile
        var blocked = 0
            private set

        /**
         * Service workers' requests don't pass a page's `shouldInterceptRequest` (§5.2, QA L11): the
         * process-wide client (platform API 24, no `androidx.webkit`) applies the same policy. The music app
         * has no other web view.
         */
        private fun installServiceWorkerClient(log: (String) -> Unit) {
            if (swInstalled) return
            swInstalled = true
            val controller = ServiceWorkerController.getInstance()
            // their own settings: no file or content access either (L6; WebPolicy refuses those schemes anyway)
            val sw = controller.serviceWorkerWebSettings
            sw.setAllowFileAccess(false)
            sw.setAllowContentAccess(false)
            controller.setServiceWorkerClient(object : ServiceWorkerClient() {
                override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse? = intercept(request, log)
            })
        }

        /** One request of the page, a frame of it, or a service worker (a WebView thread). */
        private fun intercept(request: WebResourceRequest, log: (String) -> Unit): WebResourceResponse? {
            val url = request.url?.toString() ?: return refuse(null, log)
            return when (val d = WebPolicy.current().decide(url)) {
                is WebDecision.Asset -> asset(d.path)
                WebDecision.Pass -> null
                is WebDecision.Proxy -> proxy(d, request, log)
                WebDecision.NotFound -> empty(404, "Not Found")
                WebDecision.Block -> refuse(request.url?.host, log)
            }
        }

        private fun refuse(host: String?, log: (String) -> Unit): WebResourceResponse {
            blocked++
            log("spotify: blocked a request to ${host ?: "?"} ($blocked so far)")
            return empty(403, "Forbidden")
        }

        private fun empty(status: Int, reason: String): WebResourceResponse =
            WebResourceResponse("text/plain", "utf-8", status, reason, emptyMap(), ByteArrayInputStream(ByteArray(0)))

        /** Our two assets: the page (filled as it was loaded) and `player.js`. */
        private fun asset(path: String): WebResourceResponse {
            val assets = appAssets ?: return empty(404, "Not Found")
            return if (path.endsWith(".js")) {
                WebResourceResponse("text/javascript", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), assets(path))
            } else {
                val page = assets(path).bufferedReader().use { SpotifyPage.html(it.readText(), SpotifyEndpoints.sdkUrl()) }
                WebResourceResponse("text/html", "utf-8", 200, "OK", mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(page.toByteArray()))
            }
        }

        /** How the interceptor opens an asset (set by the first host; the assets are the application's). */
        @Volatile
        private var appAssets: ((String) -> InputStream)? = null

        /**
         * The debug switch's proxy (§9.3): `https://fake-spotify.invalid/<path>` fetched from the debug base
         * over the LAN client - GET only (the interceptor sees no body; the stub uses GETs), `Range`
         * forwarded, and the status, `Content-Type`, `Content-Range`, `Accept-Ranges` and
         * `Access-Control-Allow-Origin` passed back. The host is logged, never the URL (the stub's query
         * carries a fake token).
         */
        private fun proxy(d: WebDecision.Proxy, request: WebResourceRequest, log: (String) -> Unit): WebResourceResponse {
            if (request.method != "GET") return empty(405, "Method Not Allowed")
            val b = Request.Builder().url(d.url).get()
            request.requestHeaders?.entries?.firstOrNull { it.key.equals("Range", ignoreCase = true) }?.let { b.header("Range", it.value) }
            val client = MediaCache.httpClient(publicOnly = false).newBuilder()
                .followRedirects(false).followSslRedirects(false)
                .callTimeout(PROXY_TIMEOUT_S, TimeUnit.SECONDS).readTimeout(PROXY_TIMEOUT_S, TimeUnit.SECONDS)
                .build()
            return try {
                val response = client.newCall(b.build()).execute()
                val status = response.code
                if (status !in 200..299 && status !in 400..599) {
                    response.close()
                    log("spotify: the fake answered $status (proxy)")
                    return empty(502, "Bad Gateway")
                }
                val type = response.header("Content-Type") ?: "application/octet-stream"
                val mime = type.substringBefore(';').trim()
                val charset = Regex("charset=([^;]+)", RegexOption.IGNORE_CASE).find(type)?.groupValues?.get(1)?.trim()
                val headers = PROXY_HEADERS.mapNotNull { h -> response.header(h)?.let { h to it } }.toMap()
                val reason = response.message.ifBlank { if (status < 400) "OK" else "Error" }
                val body = response.body?.byteStream() ?: ByteArrayInputStream(ByteArray(0))
                WebResourceResponse(mime, charset, status, reason, headers, body)
            } catch (e: Exception) {
                log("spotify: the fake at ${d.url.host} unreachable (${e.javaClass.simpleName})")
                empty(502, "Bad Gateway")
            }
        }

        /** The assets the interceptor serves (the first host's application). */
        internal fun useAssets(context: Context) {
            if (appAssets == null) {
                val assets = context.applicationContext.assets
                appAssets = { path -> assets.open(path) }
            }
        }
    }
}
