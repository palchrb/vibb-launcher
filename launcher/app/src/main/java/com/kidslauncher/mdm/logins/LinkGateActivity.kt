package com.kidslauncher.mdm.logins

import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Browser
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsCallback
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent
import androidx.browser.customtabs.CustomTabsServiceConnection
import androidx.browser.customtabs.CustomTabsSession
import androidx.browser.customtabs.ExperimentalOpenInBrowser
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.map.MapLinkActivity
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Design 119 §4.1-§4.6: Vibb One's link gate - a full browser to Android (`VIEW`, `BROWSABLE`, http and https, no host)
 * and nothing to the kid. Translucent, never in Recents, `taskAffinity=""`, standard launch mode: started by an app's
 * link it lands in that app's task, and so does the tab. It decides on a worker ([LinkGateRuntime.decide], §4.3) and
 * builds a fresh intent each time - the URL, the target's package, the few extras it copies - never the one it got.
 *
 * For a login it opens a window ([LoginWindow]): Chrome unhidden, Vibb's own Custom Tabs session (H2), the tab started
 * above this activity, which stays as its invisible anchor. The anchor sees two ends itself: destroyed by the system (a
 * redirect into a `singleTask` or `CLEAR_TOP` activity, `done`) and resumed after the tab (✕ or Back, `closed`); a
 * caller that asked for a result gets Chrome's (`result`, §4.5). The session's `TAB_HIDDEN` starts the minute's grace,
 * `TAB_SHOWN` cancels it; the screen locked ends it (`locked`).
 */
class LinkGateActivity : AppCompatActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var url: String? = null

    /** The tab was started above this activity: it is the window's anchor now. */
    private var anchor = false

    /** The tab came to the front (this activity paused after starting it). */
    private var tabShown = false

    /** The window ended by its own rule (the anchor finishes, no `done`). */
    private var ended = false

    private var stopped = false
    private var dialog: AlertDialog? = null
    private var connection: CustomTabsServiceConnection? = null
    private var screenOff: BroadcastReceiver? = null

    /** §4.5: the tab's result for a caller that asked for one - relayed as a fresh intent (only the redirect's URI). */
    private val tabResult = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        setResult(result.resultCode, result.data?.data?.let { Intent().setData(it) })
        ended = true
        LoginWindow.event(applicationContext, WindowEvent.RESULT)
        finishGate()
    }

    /** Not a page: night as a constant, as every such AppCompat activity (`PageLookScanTest`) - the kid's dialog look. */
    override fun attachBaseContext(newBase: Context) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A restore: after a process death that window ended with the process (L5, `stale`); an anchor recreated by a
        // configuration change it doesn't handle (a SIM swap, say) comes back only once its tab is gone - the tab
        // closed (code QA L7), so the window ends now, not by the grace or the ceiling.
        if (savedInstanceState != null) {
            if (savedInstanceState.getBoolean(STATE_ANCHOR)) LoginWindow.event(applicationContext, WindowEvent.ANCHOR_RESUMED)
            return finishGate()
        }
        val link = intent?.data?.toString()?.takeIf { it.length <= MAX_LINK }
        val scheme = intent?.data?.scheme?.lowercase()
        if (link == null || (scheme != "http" && scheme != "https")) return finishGate()
        url = link
        val app = applicationContext
        val keyguard = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        Thread {
            val step = try {
                LinkGateRuntime.decide(app, link, fromMessages = false, keyguardLocked = keyguard)
            } catch (e: Exception) {
                Log.w(TAG, "The link gate couldn't decide: ${e.javaClass.simpleName}")
                GateStep.Note(refused = false)
            }
            if (step == GateStep.Login) openWindow(app, link) else main.post { act(step, link) }
        }.start()
    }

    private fun act(step: GateStep, link: String) {
        if (isFinishing || isDestroyed) return
        when (step) {
            GateStep.Nothing -> finishGate()
            is GateStep.Browser -> startFresh(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(step.pkg))
            GateStep.Map -> startFresh(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setClass(this, MapLinkActivity::class.java), ownTask = false)
            is GateStep.App -> startFresh(
                Intent(Intent.ACTION_VIEW, Uri.parse(link)).addCategory(Intent.CATEGORY_BROWSABLE).setPackage(step.pkg),
            )
            is GateStep.Note -> {
                if (step.refused) LoginWindow.refuse(applicationContext, link)
                note()
            }
            GateStep.Login -> Unit
        }
    }

    /** A fresh intent to the target: its own task ([ownTask]), or the caller's for Vibb's own map handler. */
    private fun startFresh(intent: Intent, ownTask: Boolean = true) {
        try {
            startActivity(if (ownTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else intent)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't open the link's target: ${e.javaClass.simpleName}")
            return note()
        }
        finishGate()
    }

    /** §4.6: the kid's note, Meldinger's words. */
    private fun note() {
        if (stopped || isFinishing) return finishGate()
        dialog = AlertDialog.Builder(this, R.style.KidAlertDialog)
            .setMessage(R.string.message_link_cant_open)
            .setPositiveButton(android.R.string.ok) { _, _ -> finishGate() }
            .setOnCancelListener { finishGate() }
            .show()
    }

    // --- the login window (§4.4) ---

    /** On the gate's worker: the window opened (the record, Chrome unhidden), Vibb's session within 2 s, then the tab. */
    private fun openWindow(app: Context, link: String) {
        val role = LinkGateRuntime.loginRole()
        if (!LoginWindow.open(app, session = true) { LinkGateRuntime.windowStillAllowed(app, role) }) {
            main.post { if (!isFinishing) note() }
            return
        }
        val latch = CountDownLatch(1)
        var session: CustomTabsSession? = null
        val callback = object : CustomTabsCallback() {
            override fun onNavigationEvent(navigationEvent: Int, extras: Bundle?) {
                when (navigationEvent) {
                    TAB_HIDDEN -> LoginWindow.event(app, WindowEvent.TAB_HIDDEN)
                    TAB_SHOWN -> LoginWindow.event(app, WindowEvent.TAB_SHOWN)
                }
            }

            override fun onMinimized(extras: Bundle) = LoginWindow.event(app, WindowEvent.TAB_HIDDEN)

            override fun onUnminimized(extras: Bundle) = LoginWindow.event(app, WindowEvent.TAB_SHOWN)
        }
        val conn = object : CustomTabsServiceConnection() {
            override fun onCustomTabsServiceConnected(name: ComponentName, client: CustomTabsClient) {
                session = try {
                    client.newSession(callback)
                } catch (e: Exception) {
                    null
                }
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        val bound = try {
            CustomTabsClient.bindCustomTabsService(app, CHROME_PACKAGE, conn)
        } catch (e: Exception) {
            false
        }
        if (bound) latch.await(SESSION_WAIT_MS, TimeUnit.MILLISECONDS)
        val held = session
        LoginWindow.sessionHeld(app, held != null)
        main.post {
            if (bound) connection = conn
            if (isFinishing || isDestroyed) {
                LoginWindow.event(app, WindowEvent.RULE)
                unbind()
                return@post
            }
            startTab(link, held)
        }
    }

    /** §4.4 step 3: Chrome's tab, by package, with Vibb's session when there is one, the extras that hide download,
     * bookmark and "Open in Chrome browser" (code QA L5, where Chrome honours it), and only the caller's safe extras (L8)
     * and Auth Tab's keys (§4.5). No `NEW_TASK`. */
    @androidx.annotation.OptIn(markerClass = [ExperimentalOpenInBrowser::class])
    private fun startTab(link: String, session: CustomTabsSession?) {
        val builder = if (session != null) CustomTabsIntent.Builder(session) else CustomTabsIntent.Builder()
        builder.setShareState(CustomTabsIntent.SHARE_STATE_OFF)
            .setBookmarksButtonEnabled(false)
            .setDownloadButtonEnabled(false)
            .setOpenInBrowserButtonState(CustomTabsIntent.OPEN_IN_BROWSER_STATE_OFF)
        val scheme = callerExtra { intent?.getIntExtra(CustomTabsIntent.EXTRA_COLOR_SCHEME, -1) } ?: -1
        if (scheme in CustomTabsIntent.COLOR_SCHEME_SYSTEM..CustomTabsIntent.COLOR_SCHEME_DARK) builder.setColorScheme(scheme)
        val tab = builder.build().intent.setPackage(CHROME_PACKAGE).setData(Uri.parse(link))
        tab.flags = tab.flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv()
        callerExtra { copyHeaders(tab) }
        callerExtra { copyAuthTab(tab) }
        anchor = true
        LoginWindow.anchorStarted(this)
        watchScreen()
        try {
            if (callingActivity != null) tabResult.launch(tab) else startActivity(tab)
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start Chrome's tab: ${e.javaClass.simpleName}")
            return tabFailed()
        }
        // Fail closed: a tab that never came up (refused by lock task, say) must not leave this invisible activity over
        // the app - the window ends and the kid gets the note.
        main.postDelayed({ if (!tabShown && !ended && !isFinishing) tabFailed() }, TAB_WAIT_MS)
    }

    private fun tabFailed() {
        ended = true
        anchor = false
        // No anchor any more: the window's end leaves this activity to its note.
        LoginWindow.anchorGone(this)
        LoginWindow.event(applicationContext, WindowEvent.RULE)
        note()
    }

    /**
     * Code QA L3: the caller's extras are untrusted - a Parcelable class Vibb doesn't have throws
     * (`BadParcelableException`) on the main thread while they are read. Such an extra is left out; the tab opens
     * without it.
     */
    private inline fun <T> callerExtra(read: () -> T): T? = try {
        read()
    } catch (e: RuntimeException) {
        Log.w(TAG, "Left out a caller's extra: ${e.javaClass.simpleName}")
        null
    }

    private fun copyHeaders(tab: Intent) {
        val given = intent?.getBundleExtra(Browser.EXTRA_HEADERS) ?: return
        // getString: null for a value that isn't a string, never another type read.
        val headers = given.keySet().mapNotNull { key -> given.getString(key)?.let { key to it } }.toMap()
        val safe = safeHeaders(headers)
        if (safe.isNotEmpty()) tab.putExtra(Browser.EXTRA_HEADERS, Bundle().apply { safe.forEach { (k, v) -> putString(k, v) } })
    }

    /** §4.5: only Auth Tab's own keys - the launch flag, the redirect scheme, or the https redirect host and path. */
    private fun copyAuthTab(tab: Intent) {
        val given = intent ?: return
        if (!given.getBooleanExtra(AuthTabIntent.EXTRA_LAUNCH_AUTH_TAB, false)) return
        // Every key read before any is written: an extra that throws leaves the tab a plain one, never half an Auth Tab.
        val keys = listOf(AuthTabIntent.EXTRA_REDIRECT_SCHEME, AuthTabIntent.EXTRA_HTTPS_REDIRECT_HOST, AuthTabIntent.EXTRA_HTTPS_REDIRECT_PATH)
            .mapNotNull { key -> given.getStringExtra(key)?.let { key to it } }
        tab.putExtra(AuthTabIntent.EXTRA_LAUNCH_AUTH_TAB, true)
        keys.forEach { (key, value) -> tab.putExtra(key, value) }
    }

    /** The screen off with a secure lock ends the window (`locked`). */
    private fun watchScreen() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val keyguard = context.getSystemService(KeyguardManager::class.java)
                if (keyguard?.isDeviceSecure == true || keyguard?.isKeyguardLocked == true) {
                    LoginWindow.event(context, WindowEvent.SCREEN_LOCKED)
                }
            }
        }
        ContextCompat.registerReceiver(this, receiver, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED)
        screenOff = receiver
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ANCHOR, anchor && !ended)
    }

    override fun onPause() {
        super.onPause()
        if (anchor) tabShown = true
    }

    override fun onResume() {
        super.onResume()
        stopped = false
        if (anchor && tabShown && !ended) {
            // The tab closed (✕, Back): the app comes back.
            ended = true
            LoginWindow.event(applicationContext, WindowEvent.ANCHOR_RESUMED)
            finishGate()
        }
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        // Not the anchor (deciding or the note): leaving ends it. The anchor stops under its tab and stays.
        if (!anchor) {
            dialog?.dismiss()
            finishGate()
        }
    }

    /** [LoginWindow] ended the window: finish without an end of our own. */
    fun endedByWindow() {
        ended = true
    }

    override fun onDestroy() {
        if (anchor && !ended && !isChangingConfigurations) {
            // Destroyed under the tab by the system: the login's redirect cleared the task above its activity.
            LoginWindow.event(applicationContext, WindowEvent.ANCHOR_DESTROYED)
        }
        screenOff?.let { runCatching { unregisterReceiver(it) } }
        screenOff = null
        unbind()
        LoginWindow.anchorGone(this)
        super.onDestroy()
    }

    private fun unbind() {
        connection?.let { runCatching { applicationContext.unbindService(it) } }
        connection = null
    }

    /** Finish; the root of its own task (a `NEW_TASK` caller, L3) takes the task with it. */
    private fun finishGate() {
        if (isFinishing) return
        if (isTaskRoot) finishAndRemoveTask() else finish()
    }

    private companion object {
        const val TAG = "LinkGate"
        const val MAX_LINK = 4096
        const val SESSION_WAIT_MS = 2_000L
        const val TAB_WAIT_MS = 8_000L
        const val STATE_ANCHOR = "anchor"
    }
}
