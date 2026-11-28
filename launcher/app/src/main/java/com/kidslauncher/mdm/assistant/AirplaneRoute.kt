package com.kidslauncher.mdm.assistant

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.lock.LockTaskChrome
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.BootClock
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.postReportOnly
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.security.SecureRandom
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "AirplaneRoute"

/** The one package the route's window adds (§2.2 step 3 accepts only its activity). */
const val SETTINGS_PACKAGE = "com.android.settings"

/** §2.2 step 2: the report-only post's budget. */
private const val REPORT_BUDGET_MS = 5_000L

/** §2.2 step 6: the wait for airplane mode on. M4's whole route took 270 ms. */
private const val ROUTE_WAIT_MS = 1_000L

/** §2.3: the wait for Android to end airplane mode under the restriction. */
private const val OFF_WAIT_MS = 3_000L

/** `UserManager.ACTION_USER_RESTRICTIONS_CHANGED` (a `@SystemApi` constant). */
private const val ACTION_USER_RESTRICTIONS_CHANGED = "android.os.action.USER_RESTRICTIONS_CHANGED"

/**
 * The window's steps (§2.2 steps 4-7), so the `finally` is tested on every failure ([runRouteWindow],
 * `AirplaneRouteTest`). Each may throw.
 */
interface RouteWindowSteps {
    /** The DE marker `airplane_route_open`. */
    fun mark()

    /** `LockTaskChrome.routeWindow(Settings)` while the kiosk is on; false = no window (no plan). */
    fun pin(): Boolean

    /** Unhides Settings when it is hidden; returns whether it did. */
    fun unhide(): Boolean

    /** `showSession` with the nonce; false = no service. */
    fun show(): Boolean

    /** Waits for airplane mode on, the session's end or the deadline; returns whether airplane mode is on. */
    fun await(): Boolean

    fun unpin()

    fun rehide()

    fun hideSession()

    fun clearMark()
}

/**
 * §2.2 steps 4-7: opens the window, shows the session, waits, and closes the window **always** - the pin, Settings'
 * hide (when this window unhid it), the session, the marker last - whatever step failed. Returns whether airplane mode
 * came on.
 */
fun runRouteWindow(steps: RouteWindowSteps): Boolean {
    var unhid = false
    var pinned = false
    try {
        steps.mark()
        pinned = steps.pin()
        if (!pinned) return false
        unhid = steps.unhide()
        if (!steps.show()) return false
        return steps.await()
    } finally {
        runCatching { if (pinned) steps.unpin() }.onFailure { Log.w(LOG_TAG, "Unpin failed: ${it.javaClass.simpleName}") }
        runCatching { if (unhid) steps.rehide() }.onFailure { Log.w(LOG_TAG, "Re-hide failed: ${it.javaClass.simpleName}") }
        runCatching { steps.hideSession() }
        runCatching { steps.clearMark() }
    }
}

/**
 * Design 98 §2.2-§2.3: Flymodus on through the assistant role's session and Settings' `AirplaneModeVoiceActivity`, off
 * through `DISALLOW_AIRPLANE_MODE` set and cleared. One request at a time, from a background thread. The route never
 * writes the lock-task list itself: Settings is an input of [LockTaskChrome] ([LockTaskChrome.routeWindow]), left out
 * while LOCKED. Vibb never ends airplane mode because of a time rule (§7.2).
 */
object AirplaneRoute {
    /** The session's marker: the nonce of this one request. */
    const val EXTRA_ROUTE = "me.vibb.assistant.ROUTE"

    @Volatile
    private var service: VibbAssistantService? = null

    @Volatile
    private var session: VibbSession? = null

    private class Pending(val nonce: String, val component: ComponentName, val done: CountDownLatch) {
        @Volatile
        var claimed = false
    }

    @Volatile
    private var pending: Pending? = null

    private val running = AtomicBoolean(false)

    /** `on` or `off` while a request runs (the rows' busy state), else `null`. */
    @Volatile
    var busy: String? = null
        private set

    /** Elapsed time the window opened (`null` = closed): Settings' package events are the route's then. */
    @Volatile
    private var inFlightSince: Long? = null

    /** §2.2 step 4: the instant-suspend-on-install receiver and Home's app list ignore Settings' events while true. */
    val inFlight: Boolean
        get() = inFlightSince?.let { SystemClock.elapsedRealtime() - it < 15_000 } == true

    private val random = SecureRandom()
    private val main = Handler(Looper.getMainLooper())

    fun serviceReady(vis: VibbAssistantService) {
        service = vis
        AirplaneRuntime.changed()
    }

    fun serviceGone(vis: VibbAssistantService) {
        if (service === vis) service = null
        AirplaneRuntime.changed()
    }

    val serviceIsReady: Boolean get() = service != null

    /** The session's check: the component for this request's nonce, once; anything else `null`. */
    fun claim(nonce: String?): ComponentName? {
        val p = pending ?: return null
        if (nonce == null || p.claimed || nonce != p.nonce) return null
        p.claimed = true
        return p.component
    }

    fun sessionShown(s: VibbSession) {
        session = s
    }

    fun sessionFailed() {
        pending?.done?.countDown()
    }

    fun sessionFinished() {
        pending?.done?.countDown()
    }

    /** The edge receiver saw airplane mode turn on. */
    fun airplaneEdge(on: Boolean) {
        if (on) pending?.done?.countDown()
    }

    private fun nonce(): String = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun admin(context: Context) = ComponentName(context, MdmDeviceAdminReceiver::class.java)

    /**
     * §2.2: turns airplane mode on, or refuses. Returns [AirplaneOn.GO] when it came on, else the refusal or failure
     * ([AirplaneError] words, [AirplaneOn.ALREADY_ON], or `busy`). [by] is §5.1's `on_by` of the edge: the kid's row
     * ([AirplaneBy.KID]) or the parent's Wi-Fi setup step ([AirplaneBy.OTHER], §3.5). Background thread only.
     */
    fun turnOn(context: Context, by: String = AirplaneBy.KID): String {
        if (!running.compareAndSet(false, true)) return "busy"
        busy = "on"
        AirplaneRuntime.changed()
        var streamHeld = false
        var cameOn = false
        try {
            val now = System.currentTimeMillis()
            val plan = airplaneOnPlan(
                roleHeld = AirplaneRuntime.roleHeld(context),
                serviceReady = service != null,
                restricted = AirplaneRuntime.restricted(context),
                on = AirplaneRuntime.isOn(context),
                emergency = AirplaneRuntime.emergencyNow(context, now),
                cooldown = inCooldown(AirplaneRuntime.lastOnStartMs(context), now),
            )
            if (plan != AirplaneOn.GO) {
                Log.i(LOG_TAG, "Turn-on refused: $plan")
                if (plan != AirplaneOn.ALREADY_ON) AirplaneRuntime.recordError(context, plan)
                return plan
            }
            AirplaneRuntime.recordOnStart(context, now)
            AirplaneRuntime.recordError(context, null)
            // Design 121 §4.4, step 2a: the stream closed first when airplane mode takes its network, so the server
            // sees it go at once and step 2's report on the same connection is the last it hears ("Flymodus på siden").
            streamHeld = com.kidslauncher.mdm.server.CommandListenerService.holdForAirplane(AirplaneRuntime.wifiKept(context))
            // Step 2: the parent's page says "Flymodus på", not "not heard from" - outside every lock, 5 s at most.
            val posted = try {
                runBlocking { withTimeoutOrNull(REPORT_BUDGET_MS) { postReportOnly(context, airplaneGoingOn = true) } } == true
            } catch (e: Exception) {
                false
            }
            // Code QA H1: nothing else - no stream - rides the connection the radios are about to take.
            if (streamHeld) com.kidslauncher.mdm.server.ServerClients.streamDropped()
            // Step 3.
            val component = resolveVoiceActivity(context)
            if (component == null) {
                Log.w(LOG_TAG, "No acceptable airplane-mode voice activity on this phone")
                AirplaneRuntime.recordError(context, AirplaneError.ROUTE_MISSING)
                if (posted) com.kidslauncher.mdm.push.SyncRunner.request(context, "airplane_failed")
                return AirplaneError.ROUTE_MISSING
            }
            val wifiBefore = runCatching { context.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
            val started = SystemClock.elapsedRealtime()
            // Steps 4-7 under the enforcer's monitor: no apply() interleaves.
            val on = try {
                synchronized(AppEnforcer) { runRouteWindow(WindowSteps(context.applicationContext, component, by)) }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "The route failed: ${e.javaClass.simpleName}")
                false
            }
            Log.i(LOG_TAG, "Route window ${SystemClock.elapsedRealtime() - started} ms, airplane mode ${if (on) "on" else "not on"}")
            if (!on) {
                AirplaneRuntime.recordError(context, AirplaneError.TIMEOUT)
                if (posted) com.kidslauncher.mdm.push.SyncRunner.request(context, "airplane_failed")
                return AirplaneError.TIMEOUT
            }
            recheckWifiKept(context.applicationContext, wifiBefore)
            cameOn = true
            return AirplaneOn.GO
        } finally {
            // On success the stream opens again only on another network (Wi-Fi kept, or after airplane mode); after a
            // failure at once.
            if (streamHeld) com.kidslauncher.mdm.server.CommandListenerService.releaseAirplaneHold(airplaneOn = cameOn)
            busy = null
            running.set(false)
            AirplaneRuntime.changed()
        }
    }

    /** §3.5: every airplane mode turned on with Wi-Fi on checks Android's memory again 3 s later - still on keeps
     * `true`, gone writes `false` (and the report says so). */
    private fun recheckWifiKept(context: Context, wifiBefore: Boolean) {
        if (!wifiBefore || AssistantSetup.wifiStepOpen(context)) return
        main.postDelayed({
            Thread {
                if (!AirplaneRuntime.isOn(context)) return@Thread
                val kept = runCatching { context.getSystemService(android.net.wifi.WifiManager::class.java)?.isWifiEnabled == true }.getOrDefault(false)
                if (AirplaneRuntime.wifiKept(context) != kept) {
                    AirplaneRuntime.storeWifiKept(context, kept)
                    com.kidslauncher.mdm.push.SyncRunner.request(context, "wifi_kept")
                }
            }.start()
        }, 3_000)
    }

    /** The real steps of the window. */
    private class WindowSteps(val context: Context, val component: ComponentName, val by: String) : RouteWindowSteps {
        private val dpm = context.getSystemService(DevicePolicyManager::class.java)
        private val admin = admin(context)
        private val kioskOn = LockTaskChrome.kioskOn

        override fun mark() {
            AirplaneRuntime.markRouteOpen(context, BootClock.bootCount())
            inFlightSince = SystemClock.elapsedRealtime()
        }

        // The pause, the override, Åpne for oppsett: the kiosk is off and Settings shown - nothing to pin.
        override fun pin(): Boolean = !kioskOn || LockTaskChrome.routeWindow(context, SETTINGS_PACKAGE)

        override fun unhide(): Boolean {
            if (!dpm.isApplicationHidden(admin, SETTINGS_PACKAGE)) return false
            val ok = dpm.setApplicationHidden(admin, SETTINGS_PACKAGE, false)
            if (!ok) Log.w(LOG_TAG, "Settings didn't unhide")
            return ok
        }

        override fun show(): Boolean {
            val vis = service ?: return false
            val p = Pending(nonce(), component, CountDownLatch(1))
            pending = p
            // §5.1: the edge's attribution starts now, right before the session (code QA L2).
            AirplaneRuntime.recordKidOnStart(context, if (by == AirplaneBy.KID) System.currentTimeMillis() else null)
            vis.showSession(Bundle().apply { putString(EXTRA_ROUTE, p.nonce) }, 0)
            return true
        }

        override fun await(): Boolean {
            val p = pending ?: return AirplaneRuntime.isOn(context)
            val deadline = SystemClock.elapsedRealtime() + ROUTE_WAIT_MS
            // The edge, the session's end or the deadline; airplane mode is read at the end in any case.
            while (!AirplaneRuntime.isOn(context)) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                if (p.done.await(minOf(left, 50L), TimeUnit.MILLISECONDS) && !AirplaneRuntime.isOn(context)) {
                    // The session ended without it: one more short look, then out.
                    SystemClock.sleep(50)
                    break
                }
            }
            return AirplaneRuntime.isOn(context)
        }

        override fun unpin() {
            if (kioskOn) LockTaskChrome.routeWindow(context, null)
        }

        override fun rehide() {
            repeat(2) {
                if (dpm.setApplicationHidden(admin, SETTINGS_PACKAGE, true)) return
            }
            Log.w(LOG_TAG, "Settings didn't hide again: an apply follows")
            Thread { runCatching { AppEnforcer.applyCurrent(context) } }.start()
        }

        override fun hideSession() {
            pending = null
            val s = session
            session = null
            if (s != null) main.post { runCatching { s.hide() } }
        }

        override fun clearMark() {
            inFlightSince = null
            AirplaneRuntime.clearRouteOpen(context)
        }
    }

    /**
     * §2.2 step 3: `ACTION_VOICE_CONTROL_AIRPLANE_MODE` in the VOICE category, system apps only (a hidden Settings
     * included). Exactly one activity, in Settings, with a translucent or floating (dialog) theme - anything else is
     * `null` (`route_missing`), never a full Settings page.
     */
    fun resolveVoiceActivity(context: Context): ComponentName? = try {
        val pm = context.packageManager
        val intent = Intent(Settings.ACTION_VOICE_CONTROL_AIRPLANE_MODE).addCategory(Intent.CATEGORY_VOICE)
        val flags = PackageManager.MATCH_SYSTEM_ONLY.toLong() or PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()
        val found = pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(flags))
        val only = found.singleOrNull()?.activityInfo
        if (only == null || only.packageName != SETTINGS_PACKAGE) {
            null
        } else {
            val res = pm.getResourcesForApplication(only.applicationInfo)
            val theme = res.newTheme().apply { applyStyle(only.themeResource, true) }
            val attrs = theme.obtainStyledAttributes(intArrayOf(android.R.attr.windowIsTranslucent, android.R.attr.windowIsFloating))
            val (translucent, floating) = try {
                attrs.getBoolean(0, false) to attrs.getBoolean(1, false)
            } finally {
                attrs.recycle()
            }
            // Measured on Android 16 (jelly36): Settings' AirplaneModeVoiceActivity uses Theme.DeviceDefault.Light.Voice, a
            // floating dialog theme that isn't windowIsTranslucent - never a full page either way.
            Log.i(LOG_TAG, "Voice activity ${only.name}: translucent $translucent, floating $floating")
            if (translucent || floating) ComponentName(only.packageName, only.name) else null
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't resolve the voice activity: ${e.javaClass.simpleName}")
        null
    }

    /**
     * §2.3: airplane mode off - `DISALLOW_AIRPLANE_MODE` set (Android ends airplane mode), then cleared once it is off
     * (3 s at most). Needs neither the role nor Settings. With the parent's switch on there is nothing to do. Background
     * thread only; returns whether airplane mode is off.
     */
    fun turnOff(context: Context): Boolean {
        if (!running.compareAndSet(false, true)) return false
        busy = "off"
        AirplaneRuntime.changed()
        try {
            return synchronized(AppEnforcer) { offUnderMonitor(context) }
        } finally {
            busy = null
            running.set(false)
            AirplaneRuntime.changed()
        }
    }

    private fun offUnderMonitor(context: Context): Boolean {
        if (!AirplaneRuntime.isOn(context)) return true
        if (AirplaneRuntime.restricted(context)) return !AirplaneRuntime.isOn(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
        if (!dpm.isDeviceOwnerApp(context.packageName)) return false
        val admin = admin(context)
        AirplaneRuntime.vibbEnding(AirplaneBy.KID)
        val changes = LinkedBlockingQueue<Unit>()
        val thread = HandlerThread("airplane-off").apply { start() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                changes.offer(Unit)
            }
        }
        try {
            val filter = IntentFilter(ACTION_USER_RESTRICTIONS_CHANGED).apply { addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED) }
            ContextCompat.registerReceiver(context, receiver, filter, null, Handler(thread.looper), ContextCompat.RECEIVER_NOT_EXPORTED)
            dpm.addUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE)
            val deadline = SystemClock.elapsedRealtime() + OFF_WAIT_MS
            while (AirplaneRuntime.isOn(context)) {
                val left = deadline - SystemClock.elapsedRealtime()
                if (left <= 0) break
                changes.poll(minOf(left, 100L), TimeUnit.MILLISECONDS)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Turn-off failed: ${e.javaClass.simpleName}")
        } finally {
            // Cleared whatever happened; the next apply() writes the parent's own switch anyway.
            runCatching { dpm.clearUserRestriction(admin, UserManager.DISALLOW_AIRPLANE_MODE) }
            runCatching { context.unregisterReceiver(receiver) }
            thread.quitSafely()
        }
        val off = !AirplaneRuntime.isOn(context)
        Log.i(LOG_TAG, "Turn-off: airplane mode ${if (off) "off" else "still on"}")
        return off
    }

    /**
     * §2.2's put-back after a process death or a reboot mid-route: a marker left set means the window may still be
     * open - an apply() puts the chrome and Settings' hide back as the plan says, then the marker goes. Before the first
     * sync (Application.initRest). Background thread.
     */
    fun putBackIfMarked(context: Context) {
        if (!AirplaneRuntime.routeMarked(context)) return
        // Under the monitor: a route of this process clears its marker before it lets go, so only a left one is seen.
        synchronized(AppEnforcer) {
            if (!AirplaneRuntime.routeMarked(context) || inFlightSince != null) return
            Log.w(LOG_TAG, "The route's window was left open: putting it back")
            try {
                LockTaskChrome.routeWindow(context, null)
                AppEnforcer.applyCurrent(context)
            } finally {
                AirplaneRuntime.clearRouteOpen(context)
            }
        }
    }
}
