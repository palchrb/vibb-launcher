package com.kidslauncher.mdm.shortcuts

import android.app.KeyguardManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.database.ContentObserver
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.assistant.AirplaneRoute
import com.kidslauncher.mdm.assistant.AirplaneRuntime
import com.kidslauncher.mdm.assistant.airplaneRow
import com.kidslauncher.mdm.lock.LockMode
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.music.MusicSessions
import com.kidslauncher.mdm.music.MusicTrust
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.AnchorNotice
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.MobileNetworkRuntime
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.SoundChoice
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.timerules.AskPart
import com.kidslauncher.mdm.timerules.TimeRequests
import com.kidslauncher.mdm.timerules.TimeRuleAlarm
import com.kidslauncher.mdm.timerules.TimeRulesRuntime
import com.kidslauncher.mdm.torch.Torch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "Shortcuts"
private const val PREFS = "shortcuts"
private const val KEY_PICKS = "picks"
private const val KEY_CHANNEL = "channel_n"
private const val CHANNEL_BASE = "launcher:shortcuts"

/**
 * Snarveier's glue (design 99 §2.3-§2.6): reads the platform, builds the panel ([shortcutsModel]), starts and feeds
 * [ShortcutsService], keeps the kid's picks and the channel, and runs each tap through [shortcutTapAllowed].
 *
 * - The platform's signals are heard **once per process** ([init] from `Application`, Q99-L3), not by the service, so a
 *   panel with nothing to show still hears the change that brings it back.
 * - A refresh runs on its own thread, posts only a changed panel, and at most one post a second ([shortcutPostDelay],
 *   Q99-L7: Android's update rate is per package, shared with the anchor and the VPN).
 * - Taps run one at a time on [taps], an app-level thread (Q99-L5) - a tap that lands while the service stops is
 *   still handled, and Flymodus's route can take seconds without holding up a refresh.
 * - Only this object starts the service; only the service stops itself (Q99-L4): nothing calls `stopService`.
 */
object ShortcutsRuntime {
    private val main = Handler(Looper.getMainLooper())
    private val refresher = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "shortcuts-refresh") }

    /** The taps' one thread (receiver, [ShortcutAirplaneActivity]). */
    val taps = Executors.newSingleThreadExecutor { r -> Thread(r, "shortcuts-tap") }

    @Volatile
    private var appContext: Context? = null

    /** The panel built last (refresh thread writes; the service reads it at its start). */
    @Volatile
    var panel: ShortcutsPanel = ShortcutsPanel(false, false, emptyList())
        private set

    /** What was posted last, with its locale and channel (refresh thread). */
    private var posted: Triple<ShortcutsPanel, String, String>? = null
    private var lastPostMs: Long? = null
    private var refreshQueued = false
    private val queueLock = Any()

    /** A `startForegroundService` went out and its `onStartCommand` hasn't run (elapsed time; refresh thread). */
    @Volatile
    private var startAskedAtMs: Long? = null

    /** The anchor notice's time part, kept from [AnchorNotice]'s every recompute ([noticeAsk]); `null` before the first. */
    @Volatile
    private var ask: AskPart? = null

    /** Watching Vibb music's session while the picks hold Musikk (main thread). */
    private var musicWatched = false
    private val musicListener: () -> Unit = { requestRefresh() }

    private var initialised = false

    // ---- start-up and the platform's signals ----

    /** From `Application.initRest`'s tail (main thread), after the torch. */
    fun init(context: Context) {
        if (initialised) return
        initialised = true
        val app = context.applicationContext
        appContext = app
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            addAction(Intent.ACTION_LOCALE_CHANGED)
            // The font scale and the display size change the names' measured size (emulator run, 2026-11-15).
            addAction(Intent.ACTION_CONFIGURATION_CHANGED)
        }
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = requestRefresh()
            },
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS),
                false,
                object : ContentObserver(main) {
                    override fun onChange(selfChange: Boolean) = requestRefresh()
                },
            )
        }
        Torch.addListener { requestRefresh() }
        AirplaneRuntime.addListener { requestRefresh() }
        LauncherPreferences.getSharedPreferences().registerOnSharedPreferenceChangeListener(prefsListener)
        requestRefresh()
    }

    /** The cached policy (a sync, `apply()`) and the kid's Mobildata switch live in the launcher's preferences. */
    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        val keys = LauncherPreferences.mdm().keys()
        if (key == keys.kidModePolicy() || key == keys.kidMobileOff()) requestRefresh()
    }

    /** [AnchorNotice]'s notice was computed (a tap, the server's answer, the screen-time minute): Mer tid follows. */
    fun noticeAsk(part: AskPart) {
        if (part == ask) return
        ask = part
        requestRefresh()
    }

    // ---- the refresh ----

    /** Any signal: one refresh soon, at most one post a second. Any thread. */
    fun requestRefresh() {
        synchronized(queueLock) {
            if (refreshQueued) return
            refreshQueued = true
        }
        val delay = shortcutPostDelay(SystemClock.elapsedRealtime(), lastPostMs)
        refresher.schedule({ refreshNow() }, delay, TimeUnit.MILLISECONDS)
    }

    private fun refreshNow() {
        synchronized(queueLock) { refreshQueued = false }
        val app = appContext ?: return
        try {
            val now = computePanel(app)
            panel = now
            main.post { watchMusic(app) }
            val service = ShortcutsService.instance
            when {
                !now.shown -> {
                    if (service != null) main.post { ShortcutsService.instance?.stopNow("not shown") }
                    posted = null
                }
                service == null -> startService(app)
                else -> post(app, now)
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Refresh failed: ${e.javaClass.simpleName}")
        }
    }

    private fun startService(app: Context) {
        val asked = startAskedAtMs
        val now = SystemClock.elapsedRealtime()
        if (asked != null && now - asked < 10_000) return
        startAskedAtMs = now
        try {
            ContextCompat.startForegroundService(app, Intent(app, ShortcutsService::class.java))
            Log.i(LOG_TAG, "Starting the panel")
        } catch (e: Exception) {
            startAskedAtMs = null
            Log.w(LOG_TAG, "Couldn't start the panel: ${e.javaClass.simpleName}")
        }
    }

    /** The service's `onStartCommand` ran (main thread). */
    fun serviceStarted() {
        startAskedAtMs = null
    }

    /** The service is gone: forget what was posted, and decide again (it may be wanted back). */
    fun serviceGone() {
        refresher.execute { posted = null }
        requestRefresh()
    }

    /** Posts [now] when it, the locale or the channel changed (refresh thread). */
    private fun post(app: Context, now: ShortcutsPanel) {
        val key = Triple(now, locale(app), channelId(app))
        if (key == posted) return
        try {
            app.getSystemService(NotificationManager::class.java)
                ?.notify(SHORTCUTS_NOTIFICATION_ID, ShortcutsNotification.build(app, key.third, now))
            posted = key
            lastPostMs = SystemClock.elapsedRealtime()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the panel: ${e.javaClass.simpleName}")
        }
    }

    /** The notification for the service's `startForeground` (main thread): the panel built last. */
    fun notificationForStart(context: Context) = context.applicationContext.let { app ->
        ShortcutsNotification.build(app, channelId(app), panel).also {
            refresher.execute {
                posted = Triple(panel, locale(app), channelId(app))
                lastPostMs = SystemClock.elapsedRealtime()
            }
        }
    }

    /** What the posted views depend on besides the panel: the language, the font scale and the width (the names'
     * size, §3.2). */
    private fun locale(context: Context): String = context.resources.configuration.let { c ->
        "${c.locales[0]?.toLanguageTag().orEmpty()} ${c.fontScale} ${c.screenWidthDp}"
    }

    // ---- what the panel shows ----

    private fun mask(): Long? = (cachedPolicy() as? CachedPolicy.Ok)?.policy?.quickControlsMask

    private fun owner(context: Context): Boolean = try {
        context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
    } catch (e: Exception) {
        false
    }

    /** The panel now: the policy, the picks, the channel and the platform (any thread but main). */
    fun computePanel(context: Context): ShortcutsPanel {
        val app = context.applicationContext
        val mask = mask()
        val platform = if (mask != null && mask and QuickControlFeature.SHORTCUTS != 0L) platform(app, mask) else ShortcutsPlatform()
        return shortcutsModel(owner(app), mask, storedPicks(app), hidden(app), platform)
    }

    /** §4.1's reads, each caught: an unreadable state shows the item off or absent, never a guess that acts. */
    fun platform(context: Context, mask: Long): ShortcutsPlatform {
        val app = context.applicationContext
        val now = System.currentTimeMillis()
        val airplaneOn = AirplaneRuntime.isOn(app)
        val wifiOn = QuickControls.isWifiEnabled(app)
        val full = mask and QuickControlFeature.AIRPLANE != 0L && AirplaneRuntime.roleHeld(app)
        val restricted = AirplaneRuntime.restricted(app)
        val busy = AirplaneRoute.busy
        val airplane = AirplaneRuntime.rowState(app, now) { error ->
            airplaneRow(full, full, restricted, airplaneOn, wifiOn, busy, error?.first, error?.let { now - it.second })
        }
        val sound = QuickControls.soundState(app)?.let { (it.selected == SoundChoice.SOUND) to it.enabled }
        val bluetooth = try {
            app.getSystemService(BluetoothManager::class.java)?.adapter?.let { QuickControls.isBluetoothEnabled(app) }
        } catch (e: Exception) {
            null
        }
        val policy = (cachedPolicy() as? CachedPolicy.Ok)?.policy
        val mobile = runCatching { MobileNetworkRuntime.kidRowNow(app, policy) }.getOrDefault(com.kidslauncher.mdm.server.KidMobileRow.HIDDEN)
        return ShortcutsPlatform(
            torchLit = Torch.state?.lit,
            sound = sound,
            airplane = airplane,
            airplaneOn = airplaneOn,
            wifiOn = wifiOn,
            bluetoothOn = bluetooth,
            mobile = mobile,
            brightness = QuickControls.currentBrightness(app),
            ask = ask ?: askNow(app),
            music = music(app),
        )
    }

    /** Mer tid before the anchor's first notice in this process: the same computation (TimeAskReceiver's inputs). */
    private fun askNow(app: Context): AskPart = try {
        val snap = TimeRulesRuntime.snapshot(app, currentPolicyDecision().policy)
        AnchorNotice.noticeFor(app, snap.budget, snap.policy, snap.ledger?.day, snap.clocks.wallMs).ask.also { ask = it }
    } catch (e: Exception) {
        AskPart.None
    }

    /** Vibb music (Musikk): only Vibb music's own session (as Home's card), never another app's (§4.1). */
    private fun music(app: Context): ShortcutMusic? {
        val pkg = runCatching { MusicTrust.installed(app).firstOrNull() }.getOrNull() ?: return null
        val reading = MusicSessions.reading
        val session = reading.session?.takeIf { it.packageName == pkg }
        return ShortcutMusic(session = session != null, playing = session?.playing == true, usable = MusicSessions.usable(app, pkg))
    }

    /** Watch Vibb music's session while the kid's picks hold Musikk and the panel can show (main thread, Q99-L3). */
    private fun watchMusic(app: Context) {
        val want = panel.shown && ShortcutKey.MUSIC in shortcutPicks(storedPicks(app))
        if (want == musicWatched) return
        musicWatched = want
        if (want) MusicSessions.addListener(app, musicListener) else MusicSessions.removeListener(musicListener)
    }

    // ---- the picks and the channel ----

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private val picksSerializer = ListSerializer(String.serializer())

    /** The kid's picks as stored, `null` = the default (§4.2). Unreadable counts as the default. */
    fun storedPicks(context: Context): List<String>? = try {
        prefs(context).getString(KEY_PICKS, null)?.let { Json.decodeFromString(picksSerializer, it) }
    } catch (e: Exception) {
        null
    }

    fun storePicks(context: Context, picks: List<String>) {
        prefs(context).edit().putString(KEY_PICKS, Json.encodeToString(picksSerializer, picks)).apply()
        requestRefresh()
    }

    /** The channel in use: `launcher:shortcuts`, or `launcher:shortcuts.N` after the kid showed it again (§2.6). */
    fun channelId(context: Context): String {
        val n = prefs(context).getInt(KEY_CHANNEL, 0)
        val id = if (n == 0) CHANNEL_BASE else "$CHANNEL_BASE.$n"
        ensureChannel(context, id)
        return id
    }

    private fun ensureChannel(context: Context, id: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) != null) return
        nm.createNotificationChannel(
            NotificationChannel(id, context.getString(com.kidslauncher.mdm.R.string.shortcuts_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_SECRET
            },
        )
    }

    /** §2.6: the kid turned the channel off from the long-press menu. */
    fun hidden(context: Context): Boolean = try {
        val n = prefs(context).getInt(KEY_CHANNEL, 0)
        val id = if (n == 0) CHANNEL_BASE else "$CHANNEL_BASE.$n"
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
    } catch (e: Exception) {
        false
    }

    /** §2.6: "Vis i varsellisten igjen" - the blocked channel deleted, a new one by the next number. The kid's own
     * button only; Vibb never does this by itself. */
    fun showAgain(context: Context) {
        val prefs = prefs(context)
        val n = prefs.getInt(KEY_CHANNEL, 0)
        val old = if (n == 0) CHANNEL_BASE else "$CHANNEL_BASE.$n"
        runCatching { context.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(old) }
        prefs.edit().putInt(KEY_CHANNEL, n + 1).commit()
        Log.i(LOG_TAG, "Shown again on a new channel (${n + 1})")
        requestRefresh()
    }

    /** §6.1: the report's `shortcuts` (the sync's thread); `null` when it can't be read (left out). */
    fun report(context: Context): ShortcutsReport? = try {
        shortcutsReport(computePanel(context))
    } catch (e: Exception) {
        null
    }

    // ---- a tap ----

    /**
     * One tap ([taps] thread): the model built now, the lock state, the time rules, the keyguard and Musikk's app, through
     * [shortcutTapAllowed]; then the item's own path. [window]: from [ShortcutAirplaneActivity] with its focus - the
     * only way Flymodus turns on.
     */
    fun tap(context: Context, key: String?, window: Boolean) {
        val app = context.applicationContext
        val panel = computePanel(app)
        val lockKnown = PinLockRuntime.decided && runCatching { TimeRulesRuntime.snapshot(app, currentPolicyDecision().policy) }.isSuccess
        val keyguard = runCatching { app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked }.getOrNull()
        val musicUsable = if (key == ShortcutKey.MUSIC.wire) panel.items.firstOrNull { it.key == ShortcutKey.MUSIC }?.usable else null
        val verdict = shortcutTapAllowed(key, panel, lockKnown, PinLockRuntime.mode == LockMode.LOCKED, keyguard != false, musicUsable)
        if (verdict != ShortcutTap.ALLOW) {
            Log.i(LOG_TAG, "Tap on $key refused: $verdict")
            requestRefresh()
            return
        }
        val item = panel.items.first { it.wire() == key }
        Log.i(LOG_TAG, "Tap on $key")
        try {
            act(app, item, window)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Tap on $key failed: ${e.javaClass.simpleName}")
        }
        requestRefresh()
    }

    private fun ShortcutItem.wire() = key.wire

    private fun act(app: Context, item: ShortcutItem, window: Boolean) {
        val dpm = app.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(app, MdmDeviceAdminReceiver::class.java)
        when (item.key) {
            ShortcutKey.TORCH -> main.post { Torch.toggle(app) }
            ShortcutKey.SOUND -> QuickControls.setSound(app, if (item.on) SoundChoice.SILENT_VIBRATE else SoundChoice.SOUND)
            ShortcutKey.AIRPLANE -> when {
                AirplaneRuntime.isOn(app) -> AirplaneRoute.turnOff(app)
                // On only through the window that closed the shade first (§4.1): a broadcast for On is stale.
                window -> AirplaneRoute.turnOn(app)
                else -> Log.i(LOG_TAG, "Flymodus on without its window: ignored")
            }
            ShortcutKey.WIFI -> QuickControls.setWifiEnabled(app, !item.on)
            ShortcutKey.BLUETOOTH -> QuickControls.setBluetoothEnabled(app, dpm, admin, !item.on)
            ShortcutKey.MOBILE_DATA -> MobileNetworkRuntime.setKidOff(app, item.on)
            ShortcutKey.BRIGHTNESS -> QuickControls.setBrightness(dpm, admin, (item.level ?: BrightnessStep.MEDIUM).next.value)
            ShortcutKey.MORE_TIME -> askForTime(app)
            ShortcutKey.MUSIC -> main.post {
                val reading = MusicSessions.reading
                MusicSessions.playPause(app, reading.controller, reading.session, vibb = true)
            }
        }
    }

    /** Mer tid: exactly the anchor's "Be om mer tid" (`TimeAskReceiver`). */
    private fun askForTime(app: Context) {
        val snap = TimeRulesRuntime.snapshot(app, currentPolicyDecision().policy)
        val windowOpen = OfflineOverride.isActive() || RestrictionsPause.isActive()
        val asked = TimeRequests.ask(app, LauncherPreferences.mdm().enrolled(), windowOpen, snap)
        AnchorNotice.refresh(app, snap)
        if (asked) {
            TimeRuleAlarm.schedule(app)
            SyncRunner.request(app, "time_request")
        }
    }
}
