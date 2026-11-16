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
import com.kidslauncher.mdm.server.AnchorNotice
import com.kidslauncher.mdm.server.AnchorNotification
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.MobileNetworkRuntime
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.SoundChoice
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.currentPolicyDecision
import com.kidslauncher.mdm.timerules.AskPart
import com.kidslauncher.mdm.timerules.TimeAsk
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
/** Snarveier's channel id; `.N` is added after the kid showed it again (§2.6). Design 107 reads it. */
const val SHORTCUTS_CHANNEL_BASE = "launcher:shortcuts"

/**
 * Snarveier's glue (design 99 §2.3-§2.6, design 100 §2): reads the platform, builds the panel ([shortcutsModel]) for
 * the anchor's one notification ([AnchorNotification] posts it as id 1002's view while it is shown), keeps the kid's
 * picks and the channel, and runs each tap through [shortcutTapAllowed]. It starts and stops nothing: the anchor's life
 * is design 07's.
 *
 * - The platform's signals are heard **once per process** ([init] from `Application`, Q99-L3), so a panel with nothing
 *   to show still hears the change that brings it back. Each asks the poster for a refresh ([requestRefresh]).
 * - Taps run one at a time on [taps], an app-level thread (Q99-L5): Flymodus's route can take seconds without holding
 *   up a refresh.
 */
object ShortcutsRuntime {
    private val main = Handler(Looper.getMainLooper())

    /** The taps' one thread (receiver, [ShortcutAirplaneActivity]). */
    val taps = Executors.newSingleThreadExecutor { r -> Thread(r, "shortcuts-tap") }

    @Volatile
    private var appContext: Context? = null

    /** The panel built last (the poster's thread writes). */
    @Volatile
    var panel: ShortcutsPanel = ShortcutsPanel(false, false, emptyList())
        private set

    /** The anchor notice's time part, set by the poster from the time part it posts with ([panelFor], Q100-L6);
     * `null` before the first. */
    @Volatile
    private var ask: AskPart? = null

    /** Watching Vibb music's session while the panel holds Musikk (main thread). */
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
        AnchorNotification.remember(app)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
            addAction(AudioManager.RINGER_MODE_CHANGED_ACTION)
            addAction(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            addAction(WifiManager.WIFI_STATE_CHANGED_ACTION)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            // Not the channel's block state: the long-press sheet's broadcast comes with SystemUI's uid and is dropped
            // for this not-exported receiver (design 107 gate §6ax). `notify.NotificationChannelsWatch` (exported, the
            // protected block-state actions only) calls [requestRefresh] for it.
            // The phone's language. The launcher's own (a per-app locale) comes only as a configuration change:
            // [languageChanged], from `Application.onConfigurationChanged` (C99-L1).
            addAction(Intent.ACTION_LOCALE_CHANGED)
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

    // ---- the refresh ----

    /** Any signal: the anchor's notification refreshed soon ([AnchorNotification], at most one post a second). Any
     * thread. */
    fun requestRefresh() = AnchorNotification.requestRefresh()

    /**
     * The panel for the poster's next view ([AnchorNotification]'s thread): Mer tid's state first, from the very time
     * part the view is posted with (Q100-L6), then the model. Throws what [computePanel] throws (the poster then posts
     * the plain notice, Q100-M1).
     */
    fun panelFor(context: Context, part: AskPart): ShortcutsPanel {
        val app = context.applicationContext
        ask = part
        val now = computePanel(app)
        panel = now
        main.post { watchMusic(app) }
        return now
    }

    /**
     * The launcher's language changed (C99-L1: `Application.onConfigurationChanged`, after the other channels were
     * named again; main thread): the channel in use takes its new name, and the anchor's view is posted again in the new
     * language (the post key's language differs) - the plain notice's too (design 100 §2.4).
     */
    fun languageChanged(context: Context) {
        val app = context.applicationContext
        if (appContext == null) {
            requestRefresh()
            return
        }
        try {
            val id = currentChannelId(app)
            val nm = app.getSystemService(NotificationManager::class.java)
            // Creating an existing channel again only renames it: a channel the kid turned off stays off.
            if (nm?.getNotificationChannel(id) != null) createChannel(app, id)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The channel wasn't named again: ${e.javaClass.simpleName}")
        }
        requestRefresh()
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

    /** Mer tid before the poster's first view in this process (a report, a tap): the same computation
     * (TimeAskReceiver's inputs). */
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

    /** Watch Vibb music's session while the panel holds Musikk (main thread, Q99-L3, C99-L4). The item is there whenever
     * Vibb music is installed, session or not, so the watch starts before a session does. */
    private fun watchMusic(app: Context) {
        val want = panel.let { p -> p.shown && p.items.any { it.key == ShortcutKey.MUSIC } }
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

    /** The channel in use: `launcher:shortcuts`, or `launcher:shortcuts.N` after the kid showed it again (§2.6). Read
     * only: the poster makes sure of it right before each build ([ensureChannel], design 100 M7). */
    fun currentChannelId(context: Context): String {
        val n = prefs(context).getInt(KEY_CHANNEL, 0)
        return if (n == 0) SHORTCUTS_CHANNEL_BASE else "$SHORTCUTS_CHANNEL_BASE.$n"
    }

    /** The panel's channel [id] exists afterwards (created when missing; an existing one - the kid's block included -
     * is left as it is). */
    fun ensureChannel(context: Context, id: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (nm.getNotificationChannel(id) != null) return
        createChannel(context, id)
    }

    private fun createChannel(context: Context, id: String) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
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
        val id = currentChannelId(context)
        context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(id)?.importance == NotificationManager.IMPORTANCE_NONE
    } catch (e: Exception) {
        false
    }

    /**
     * §2.6: "Vis i varsellisten igjen" - the blocked channel deleted, a new one by the next number. The kid's own
     * button only; Vibb never does this by itself. One task on the poster's thread (design 100 Q100-L8), so it is
     * ordered against every build's ensure (M7): the delete only while the plain notice is up (AOSP refuses to delete a
     * channel holding the foreground-service notification), then the bump. Waits for it (the picker calls it off the
     * main thread and reads the state afterwards).
     */
    fun showAgain(context: Context) {
        val app = context.applicationContext
        try {
            AnchorNotification.onPosterThread {
                val prefs = prefs(app)
                val n = prefs.getInt(KEY_CHANNEL, 0)
                val old = if (n == 0) SHORTCUTS_CHANNEL_BASE else "$SHORTCUTS_CHANNEL_BASE.$n"
                if (!AnchorNotification.panelUp()) {
                    runCatching { app.getSystemService(NotificationManager::class.java)?.deleteNotificationChannel(old) }
                }
                prefs.edit().putInt(KEY_CHANNEL, n + 1).commit()
                Log.i(LOG_TAG, "Shown again on a new channel (${n + 1})")
            }.get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Show again failed: ${e.javaClass.simpleName}")
        }
        requestRefresh()
        // Design 107 §3: a delete and a new channel send no block-state broadcast (design 100 M6), so the report that
        // says Snarveier is on again is asked for here.
        com.kidslauncher.mdm.push.SyncRunner.request(app, com.kidslauncher.mdm.notify.NOTIFICATION_CHANNELS_SYNC_REASON)
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

    /** Mer tid: exactly the anchor's "Be om mer tid" - the one request path (design 100 §8.4). */
    private fun askForTime(app: Context) {
        TimeAsk.askNow(app)
    }
}
