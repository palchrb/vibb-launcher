package com.kidslauncher.mdm.portal

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.CaptivePortal
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.view.textclassifier.TextClassifier
import android.webkit.PermissionRequest
import android.webkit.ServiceWorkerClient
import android.webkit.ServiceWorkerController
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityPortalWindowBinding
import com.kidslauncher.mdm.server.DOH_HOSTS_ASSET
import com.kidslauncher.mdm.server.classifyDomain
import com.kidslauncher.mdm.server.parseDohHosts
import java.io.ByteArrayInputStream
import java.io.File

/** The window's process (design 102 §3.1): `Application.onCreate` returns early there ([isPortalProcess]). */
const val PORTAL_PROCESS_SUFFIX = ":portal"

fun isPortalProcess(processName: String?): Boolean = processName?.endsWith(PORTAL_PROCESS_SUFFIX) == true

/** The `:portal` process's one start-up step: its WebView's own data directory, before its first WebView. */
fun preparePortalProcess() {
    try {
        WebView.setDataDirectorySuffix("portal")
    } catch (e: Exception) {
        android.util.Log.w("PortalWindow", "The WebView's directory: ${e.javaClass.simpleName}")
    }
}

private const val CHECK_EVERY_MS = 5_000L
private const val TICK_MS = 1_000L
private const val REPROBE_EVERY_MS = 3_000L
private const val LISTS_LOADING = 0
private const val LISTS_READY = 1
private const val LISTS_FAILED = 2

/**
 * Design 102 §3.3: the Wi-Fi sign-in window - a WebView of Vibb's own in the `:portal` process, bound to the portal's
 * Wi-Fi network (`bindProcessToNetwork`), so Vibb One's main process keeps tsnet, the stream and the sync on the
 * default network. Its state comes from the main process's snapshot ([PortalSnapshot]) - never from preferences - and
 * its session goes back as a file and an explicit broadcast ([PortalSessionReceiver]).
 *
 * - No address bar, no menu; Close, and Back steps back and closes on the first page.
 * - Where the page may go: [portalNavigation] and [portalFrame] over the hooks (`shouldOverrideUrlLoading` for a
 *   main-frame GET, `shouldInterceptRequest` for a POST and a sub-frame's document, R5/R6), the filter's lists
 *   ([classifyDomain]) on every request. Not seen by these hooks (§7): a sub-resource's redirect target, WebSockets,
 *   WebRTC, an app-like site's in-page moves, the parent's own DNS upstream. Service workers are off.
 * - Closes ([portalClose]): `validated` (with `reportCaptivePortalDismissed()`) or `passes` for a sign-in, `lost`,
 *   `closed`, `closed_by_vibb` (Vibb One's "close now", a pause's end), `timeout`, `budget`.
 */
class PortalWindowActivity : ComponentActivity() {
    private lateinit var binding: ActivityPortalWindowBinding
    private lateinit var snapshot: PortalSnapshot
    private lateinit var kind: PortalKind
    private var network: Network? = null
    private var portal: CaptivePortal? = null
    private var webView: WebView? = null
    private val handler = Handler(Looper.getMainLooper())
    private val startedAt = SystemClock.elapsedRealtime()

    /** The navigation state, guarded by itself: `shouldInterceptRequest` runs off the main thread. */
    private val lock = Any()
    private var sites = PortalSites()
    private var chain: PortalChain? = null
    private var firstUrl: String? = null
    private var firstHost: String? = null
    private val visited = LinkedHashSet<String>()
    private var visitedCount = 0
    private val blockedHosts = LinkedHashSet<String>()
    private val blockedFrames = LinkedHashSet<String>()

    @Volatile
    private var blocked: Map<String, String> = emptyMap()

    @Volatile
    private var dohHosts: Set<String> = emptySet()

    /** The filter's lists (code QA M1): [LISTS_LOADING] until read - the first page waits for them -, [LISTS_FAILED]
     * when they couldn't be read, and then every document is refused (fail closed). */
    @Volatile
    private var lists = LISTS_LOADING

    /** The URLs answered with a page of ours (a refused POST, a listed document): never the current site, never visited
     * (code QA L1). */
    private val substituted = HashSet<String>()

    /** This instance owns the window's session (written at its start): only it ends the `:portal` process (code QA
     * L6 - a stray instance, a `Forward` without the network, finishes alone). */
    private var owner = false

    @Volatile
    private var validated = false

    @Volatile
    private var passes = false
    private var checking = false
    private var lastReprobe = 0L
    private var ended = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val closeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_CLOSE_WINDOW) return
            // A pause's end closes only a window opened in it; the PIN lock and Part A off close any.
            if (intent.getStringExtra("why") == "pause" && snapshot.pauseEndMs == null) return
            end("closed_by_vibb")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val snap = PortalFiles.readSnapshot(noBackupFilesDir)
        val net = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
        // A snapshot is for one opening, written right before the start; a stale one (a restarted activity) opens nothing.
        if (snap == null || net == null || System.currentTimeMillis() - snap.openedMs !in 0..60_000L) {
            finish()
            return
        }
        snapshot = snap
        kind = PortalKind.of(snap.kind) ?: PortalKind.SIGN_IN
        network = net
        portal = intent.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL, CaptivePortal::class.java)
        val cm = getSystemService(ConnectivityManager::class.java)
        cm.bindProcessToNetwork(net)
        writeSession(null)
        owner = true

        binding = ActivityPortalWindowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            android.view.WindowInsets.CONSUMED
        }
        binding.portalWindowTitle.text = getString(R.string.portal_title) + (snap.network?.let { " · $it" } ?: "")
        binding.portalWindowClose.setOnClickListener {
            portal?.reportCaptivePortalDismissed()
            end("closed")
        }
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val view = webView
                if (view != null && view.canGoBack()) view.goBack() else {
                    portal?.reportCaptivePortalDismissed()
                    end("closed")
                }
            }
        })
        registerReceiver(closeReceiver, IntentFilter(ACTION_CLOSE_WINDOW), Context.RECEIVER_NOT_EXPORTED)
        watch(cm, net)

        val first = Uri.parse(snap.url)
        val firstSite = first.host?.let(::siteOf)
        synchronized(lock) {
            sites = PortalSites(portal = setOfNotNull(firstSite), current = firstSite, settled = false, first = firstSite)
            chain = PortalChain(ChainStart.FIRST, null, true, null)
            firstUrl = snap.url
            firstHost = first.host?.lowercase()
        }
        val view = PortalWebView(this)
        webView = view
        setUp(view)
        binding.portalWindowPage.addView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        binding.portalWindowHost.text = first.host.orEmpty()
        // The first page only once the filter's lists are in (code QA M1): its redirects are judged by them too.
        loadLists { if (!ended) view.loadUrl(snap.url) }
        handler.post(tick)
    }

    /** The filter's lists from the main process's compiled file, and the built-in secure-DNS hosts, read off the main
     * thread; [then] runs on the main thread once they are in (at once with the filter off or in a pause). A missing or
     * unreadable list leaves [lists] failed: every document is refused. */
    private fun loadLists(then: () -> Unit) {
        if (!snapshot.filterOn) {
            lists = LISTS_READY
            then()
            return
        }
        val path = snapshot.blocklistPath
        Thread {
            lists = try {
                dohHosts = parseDohHosts(assets.open(DOH_HOSTS_ASSET).bufferedReader().use { it.readText() })
                val file = path?.let(::File)?.takeIf { it.exists() } ?: error("no compiled list")
                val map = HashMap<String, String>()
                file.bufferedReader().useLines { lines ->
                    for (line in lines) {
                        val tab = line.indexOf('\t')
                        if (tab > 0) map[line.substring(0, tab)] = line.substring(tab + 1)
                    }
                }
                blocked = map
                LISTS_READY
            } catch (e: Exception) {
                android.util.Log.w("PortalWindow", "Couldn't read the filter's lists - every page refused: ${e.javaClass.simpleName}")
                LISTS_FAILED
            }
            handler.post(then)
        }.start()
    }

    /** The filter's lists say no - and, fail closed, while they aren't in or couldn't be read. */
    private fun listed(host: String): Boolean = when {
        !snapshot.filterOn -> false
        lists != LISTS_READY -> true
        else -> classifyDomain(blocked, host, snapshot.spotify, dohHosts) != null
    }

    private fun pauseOn(): Boolean = snapshot.pauseEndMs?.let { System.currentTimeMillis() < it } == true

    private fun watch(cm: ConnectivityManager, net: Network) {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (network == net && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) validated = true
            }

            override fun onLost(network: Network) {
                if (network == net) handler.post { end("lost") }
            }
        }
        try {
            cm.registerNetworkCallback(
                android.net.NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                callback,
                handler,
            )
            networkCallback = callback
        } catch (e: Exception) {
            android.util.Log.w("PortalWindow", "Couldn't watch the network: ${e.javaClass.simpleName}")
        }
    }

    /** Every second: the close rules; every [CHECK_EVERY_MS] Vibb's https check (a sign-in). */
    private val tick = object : Runnable {
        override fun run() {
            if (ended) return
            val elapsed = SystemClock.elapsedRealtime() - startedAt
            val outcome = portalClose(
                CloseInput(
                    kind = kind,
                    validated = validated,
                    passes = passes,
                    elapsedMs = elapsed,
                    minutes = snapshot.minutes,
                    codeFree = snapshot.approved == "none" || snapshot.approved == "grant",
                    budgetLeftMs = snapshot.budgetLeftMs,
                    openedInPause = snapshot.pauseEndMs != null,
                    pauseOn = pauseOn(),
                ),
            )
            if (outcome != null) {
                if (outcome == "validated" || outcome == "passes") portal?.reportCaptivePortalDismissed()
                end(outcome)
                return
            }
            if (kind == PortalKind.SIGN_IN && !checking && elapsed % CHECK_EVERY_MS < TICK_MS) {
                val net = network
                if (net != null) {
                    checking = true
                    Thread {
                        val ok = PortalCheck(net, snapshot.serverCheckUrl).passes()
                        handler.post {
                            checking = false
                            if (ok) passes = true
                        }
                    }.start()
                }
            }
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** After a main-frame page, Android re-probes - at most every [REPROBE_EVERY_MS] (best effort, E7). */
    private fun reprobe() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastReprobe < REPROBE_EVERY_MS || kind != PortalKind.SIGN_IN) return
        lastReprobe = now
        val net = network ?: return
        // `reportNetworkConnectivity` for both starts (code QA L7): `reportCaptivePortalDismissed()` is the window's end
        // only, as AOSP's login app - called on every page it could re-post Android's notification while the window is open.
        runCatching { getSystemService(ConnectivityManager::class.java).reportNetworkConnectivity(net, true) }
    }

    /** The WebView's settings, explicit (Q102-L4). */
    @SuppressLint("SetJavaScriptEnabled")
    private fun setUp(view: WebView) {
        val settings = view.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.setGeolocationEnabled(false)
        settings.mediaPlaybackRequiresUserGesture = true
        WebView.setWebContentsDebuggingEnabled(com.kidslauncher.mdm.BuildConfig.DEBUG)
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
        view.setTextClassifier(TextClassifier.NO_OP)
        view.isLongClickable = true
        view.setDownloadListener { _, _, _, _, _ -> showLine(R.string.portal_no_download) }
        try {
            ServiceWorkerController.getInstance().apply {
                serviceWorkerWebSettings.blockNetworkLoads = true
                setServiceWorkerClient(object : ServiceWorkerClient() {
                    override fun shouldInterceptRequest(request: WebResourceRequest): WebResourceResponse = refusal(403)
                })
            }
        } catch (e: Exception) {
            android.util.Log.w("PortalWindow", "Service workers: ${e.javaClass.simpleName}")
        }
        view.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) = request.deny()
            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: ValueCallback<Array<Uri>>,
                fileChooserParams: FileChooserParams,
            ): Boolean = false
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: android.webkit.GeolocationPermissions.Callback) =
                callback.invoke(origin, false, false)
        }
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (!request.isForMainFrame) return false
                val url = request.url
                when (schemeVerdict(url.scheme)) {
                    SchemeVerdict.DIAL -> {
                        dial(url)
                        return true
                    }
                    SchemeVerdict.REFUSE -> {
                        showLine(R.string.portal_leaves)
                        return true
                    }
                    SchemeVerdict.LOAD -> Unit
                }
                val host = url.host?.lowercase() ?: return true
                if (listed(host)) {
                    // A listed host is the filter's, never "showed a blocked site" (code QA L2): blocked_hosts only.
                    synchronized(lock) { if (blockedHosts.size < 10) blockedHosts += host }
                    showLine(R.string.portal_blocked)
                    return true
                }
                val step = NavStep(
                    when {
                        request.isRedirect -> StepKind.REDIRECT
                        request.hasGesture() -> StepKind.TAP
                        else -> StepKind.PAGE
                    },
                    siteOf(host),
                )
                if (!judge(step)) {
                    showLine(R.string.portal_leaves)
                    return true
                }
                return false
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val url = request.url
                val scheme = url.scheme?.lowercase()
                if (scheme != "http" && scheme != "https") return null
                val host = url.host?.lowercase() ?: return refusal(403)
                val document = request.isForMainFrame || frameDocument(request.requestHeaders["Accept"], request.isForMainFrame)
                if (listed(host)) {
                    synchronized(lock) {
                        if (blockedHosts.size < 10) blockedHosts += host
                        if (request.isForMainFrame) substituted += url.toString()
                    }
                    return if (document) page(R.string.portal_blocked) else refusal(403)
                }
                if (request.isForMainFrame && request.method.equals("POST", ignoreCase = true)) {
                    if (!judge(NavStep(StepKind.POST, siteOf(host)))) {
                        synchronized(lock) { substituted += url.toString() }
                        return page(R.string.portal_leaves)
                    }
                } else if (!request.isForMainFrame && document) {
                    if (!judge(NavStep(StepKind.FRAME, siteOf(host)))) {
                        synchronized(lock) { if (blockedFrames.size < 10) blockedFrames += host }
                        return page(R.string.portal_leaves)
                    }
                }
                return null
            }

            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val host = Uri.parse(url).host?.lowercase() ?: return
                val site = siteOf(host)
                synchronized(lock) {
                    // Our own page at a refused URL is no site of the kid's (code QA L1).
                    if (url in substituted) return
                    sites = sites.copy(current = site)
                    if (!sites.settled) {
                        firstUrl = url
                        firstHost = host
                    }
                    visitedCount += if (visited.add(site)) 1 else 0
                }
                binding.portalWindowHost.text = host
                reprobe()
            }

            override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                // TLS errors are final: a page of ours, no way past.
                handler.cancel()
                showLine(R.string.portal_tls)
            }

            override fun onRenderProcessGone(view: WebView, detail: android.webkit.RenderProcessGoneDetail): Boolean {
                end("closed")
                return true
            }
        }
    }

    /** One step through [portalNavigation], the state moved on an allowed one. */
    private fun judge(step: NavStep): Boolean = synchronized(lock) {
        val outcome = portalNavigation(kind, pauseOn(), snapshot.recordedSite, sites, chain, step)
        if (step.kind != StepKind.FRAME) {
            sites = outcome.sites
            chain = outcome.chain
        }
        outcome.allow
    }

    /** `tel:` to Vibb's own dialer, where calls stay screened by Vibb's call rules (emergency numbers work as always). */
    private fun dial(url: Uri) {
        try {
            startActivity(Intent(Intent.ACTION_DIAL, url).setPackage(packageName))
        } catch (e: Exception) {
            showLine(R.string.portal_leaves)
        }
    }

    private fun showLine(text: Int) {
        runOnUiThread {
            binding.portalWindowLine.setText(text)
            binding.portalWindowLine.visibility = View.VISIBLE
            handler.removeCallbacks(hideLine)
            handler.postDelayed(hideLine, 5_000L)
        }
    }

    private val hideLine = Runnable { binding.portalWindowLine.visibility = View.GONE }

    private fun refusal(status: Int) = WebResourceResponse("text/plain", "utf-8", status, "Refused", emptyMap(), ByteArrayInputStream(ByteArray(0)))

    /** A page of ours in place of a refused document: the kid's words, nothing to tap. */
    private fun page(text: Int): WebResourceResponse {
        val words = android.text.Html.escapeHtml(getString(text))
        val html = "<!doctype html><meta charset=utf-8><meta name=viewport content='width=device-width'>" +
            "<body style='font-family:sans-serif;padding:24px;background:#16213e;color:#f2f2f2'><p>$words</p></body>"
        return WebResourceResponse("text/html", "utf-8", 200, "OK", emptyMap(), ByteArrayInputStream(html.toByteArray()))
    }

    /** The session as it stands; [outcome] `null` while open. */
    private fun writeSession(outcome: String?) {
        val (first, host, list, count, hosts, frames) = synchronized(lock) {
            Session6(sites.first, firstHost, visited.toList(), visitedCount, blockedHosts.toList(), blockedFrames.toList())
        }
        try {
            PortalFiles.writeSession(
                noBackupFilesDir,
                PortalSessionFile(
                    openedMs = snapshot.openedMs,
                    closedMs = outcome?.let { System.currentTimeMillis() },
                    network = snapshot.network,
                    firstHost = host,
                    firstSite = first,
                    firstUrl = if (first != null) firstUrl else null,
                    outcome = outcome,
                    visited = list.take(20),
                    visitedCount = count,
                    blockedHosts = hosts.take(10),
                    blockedFrames = frames.take(10),
                    start = snapshot.start,
                    approved = snapshot.approved,
                    kind = snapshot.kind,
                    pauseEndMs = snapshot.pauseEndMs,
                ),
            )
        } catch (e: Exception) {
            android.util.Log.w("PortalWindow", "Couldn't write the session: ${e.javaClass.simpleName}")
        }
    }

    private data class Session6(
        val first: String?,
        val host: String?,
        val visited: List<String>,
        val count: Int,
        val hosts: List<String>,
        val frames: List<String>,
    )

    /** The window's end: the session file, the broadcast to the main process, the network unbound, finished. */
    private fun end(outcome: String) {
        if (ended || !::snapshot.isInitialized) return
        ended = true
        handler.removeCallbacksAndMessages(null)
        writeSession(outcome)
        try {
            sendBroadcast(Intent(ACTION_SESSION_DONE).setComponent(ComponentName(this, PortalSessionReceiver::class.java)))
        } catch (e: Exception) {
            android.util.Log.w("PortalWindow", "Couldn't tell the main process: ${e.javaClass.simpleName}")
        }
        val toast = when (outcome) {
            "timeout" -> R.string.portal_timeout
            "budget" -> R.string.portal_budget
            else -> null
        }
        if (toast != null) {
            binding.portalWindowLine.setText(toast)
            binding.portalWindowLine.visibility = View.VISIBLE
            handler.postDelayed({ finish() }, 2_500L)
        } else {
            finish()
        }
    }

    override fun onDestroy() {
        if (::snapshot.isInitialized) {
            if (!ended) end("closed")
            runCatching { unregisterReceiver(closeReceiver) }
            networkCallback?.let { cb -> runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb) } }
            webView?.let { view ->
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
            }
            webView = null
            runCatching { getSystemService(ConnectivityManager::class.java).bindProcessToNetwork(null) }
        }
        super.onDestroy()
        // The window's process ends with it: nothing of it stays bound to the portal's network. Only the instance that
        // owned the session ends it (code QA L6).
        if (isFinishing && owner) android.os.Process.killProcess(android.os.Process.myPid())
    }
}

/** The window's WebView: the text selection's menu trimmed to Copy and Select all ("Web search", the `PROCESS_TEXT`
 * items and smart selection's Open/Call would start other apps). Long-press on a link has no menu in a plain WebView. */
private class PortalWebView(context: Context) : WebView(context) {
    override fun startActionMode(callback: ActionMode.Callback, type: Int): ActionMode? =
        super.startActionMode(trimmed(callback), type)

    override fun startActionMode(callback: ActionMode.Callback): ActionMode? = super.startActionMode(trimmed(callback))

    private fun trimmed(callback: ActionMode.Callback) = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean = callback.onCreateActionMode(mode, menu).also { trim(menu) }
        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = callback.onPrepareActionMode(mode, menu).also { trim(menu) }
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean = callback.onActionItemClicked(mode, item)
        override fun onDestroyActionMode(mode: ActionMode) = callback.onDestroyActionMode(mode)
    }

    private fun trim(menu: Menu) {
        val keep = setOf(android.R.id.copy, android.R.id.selectAll)
        (menu.size() - 1 downTo 0).map { menu.getItem(it) }.filter { it.itemId !in keep }.forEach { menu.removeItem(it.itemId) }
    }
}
