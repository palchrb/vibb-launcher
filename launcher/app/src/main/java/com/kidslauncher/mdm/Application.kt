package com.kidslauncher.mdm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.UserHandle
import androidx.core.content.ContextCompat
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.CommandListenerService
import com.kidslauncher.mdm.server.KidVpnService
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.firstLaunchResetDue
import com.kidslauncher.mdm.preferences.migratePreferencesToNewVersion
import com.kidslauncher.mdm.preferences.resetPreferences
import com.kidslauncher.mdm.ui.home.nextAppLoad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlin.system.exitProcess


class Application : android.app.Application() {
    companion object {
        /** For KidAppComponentFactory; set first thing in onCreate. */
        @Volatile
        var instance: Application? = null
            private set
    }

    val apps = MutableLiveData<List<AbstractDetailedAppInfo>>()

    private val profileAvailabilityBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            // TODO: only update specific apps
            // use Intent.EXTRA_USER
            loadApps()
        }
    }

    /** Design 42: the catalog's clock app installed, updated or removed - its signer is checked again
     * off the main thread (`ClockApp.verified`: its timer on the lock, its alarm over the lock). */
    private fun clockAppChanged(packageName: String?) {
        if (packageName != com.kidslauncher.mdm.lock.ClockApp.PACKAGE) return
        CoroutineScope(Dispatchers.IO).launch { com.kidslauncher.mdm.lock.ClockApp.refresh(this@Application) }
    }

    // TODO: only update specific apps
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p0: String?, p1: UserHandle?) {
            evictIcons(p0)
            loadApps()
            clockAppChanged(p0)
        }

        override fun onPackageAdded(p0: String?, p1: UserHandle?) {
            evictIcons(p0)
            loadApps()
            clockAppChanged(p0)
            // Reacts to this specific install immediately (no network round-trip, no waiting on
            // the next sync) - see AppEnforcer.enforceOnNewPackage's own doc comment. Called back
            // on the main thread by default; AppEnforcer.enforceOnNewPackage does DevicePolicyManager
            // Binder calls, which don't belong there (see the ANR this app already hit once from a
            // similar main-thread AppEnforcer call in SettingsFragmentLauncher).
            p0?.let { packageName ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // Design 24b (fix round QA Low 3): a block set or cleared by this install
                        // first - enforceOnNewPackage reads it.
                        val relockBlock = com.kidslauncher.mdm.server.WindowRelock.onPackageInstalled(this@Application, packageName)
                        val blocked = AppEnforcer.enforceOnNewPackage(this@Application, packageName) || relockBlock
                        // A new app the parent hasn't allowed (e.g. from Play): report it now, so
                        // the device page lists it for allowlisting (handy step 7). The music app
                        // gets its managed config and library at once (design 21 §3).
                        val music = packageName in com.kidslauncher.mdm.music.MUSIC_PACKAGES
                        if (blocked || music) com.kidslauncher.mdm.push.SyncRunner.request(this@Application, "package_added")
                    } catch (e: Exception) {
                        android.util.Log.w("Application", "New-package enforcement failed for $packageName", e)
                    }
                }
            }
        }

        override fun onPackageChanged(p0: String?, p1: UserHandle?) {
            evictIcons(p0)
            loadApps()
            clockAppChanged(p0)
            // An update (a replaced package) lands here: design 24b's install check (fix round QA
            // Low 3), and a re-apply when it set or cleared a block.
            p0?.let { packageName ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        // The music app updated (a catalog install, Play, adb): it syncs and reports now.
                        com.kidslauncher.mdm.music.musicNudgeOnChange(packageName)?.let {
                            com.kidslauncher.mdm.music.MusicSync.nudge(this@Application, it)
                        }
                        if (com.kidslauncher.mdm.server.WindowRelock.onPackageInstalled(this@Application, packageName)) {
                            AppEnforcer.apply(this@Application, com.kidslauncher.mdm.server.currentPolicyDecision())
                            // The block set or cleared reaches the device page now (relock_blocks).
                            com.kidslauncher.mdm.push.SyncRunner.request(this@Application, "relock_block")
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("Application", "Install check failed for $packageName", e)
                    }
                }
            }
        }

        override fun onPackagesAvailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO
        }

        // The PIN lock's camera lock suspends/unsuspends at every screen-off and unlock; the app
        // list doesn't change for it (AppFilter counts those cameras as usable), so no reload.
        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
            // Vibb music's card and lock view look the app's state up again (design 21 step 4).
            com.kidslauncher.mdm.music.MusicSessions.packagesChanged(packageNames?.toList())
            if (com.kidslauncher.mdm.lock.CameraLock.ownChange(packageNames?.toList())) return
            loadApps()
        }

        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) {
            com.kidslauncher.mdm.music.MusicSessions.packagesChanged(packageNames?.toList())
            if (com.kidslauncher.mdm.lock.CameraLock.ownChange(packageNames?.toList())) return
            loadApps()
        }

        override fun onPackagesUnavailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO
        }

        override fun onPackageLoadingProgressChanged(
            packageName: String,
            user: UserHandle,
            progress: Float
        ) {
            // TODO
        }

        override fun onShortcutsChanged(
            packageName: String,
            shortcuts: MutableList<ShortcutInfo>,
            user: UserHandle
        ) {
            // TODO
        }
    }

    private var customAppNames: HashMap<AbstractAppInfo, String>? = null
    private val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, pref ->
        if (pref == getString(R.string.settings_apps_custom_names_key)) {
            customAppNames = LauncherPreferences.apps().customNames()
        }
    }

    override fun onCreate() {
        super.onCreate()
        // The boot cover's own process (design 16b, QA #10): nothing else runs there - no call
        // path, no preferences, no enforcement, no crash reports of ours - only its crash counter
        // (device-protected storage), installed first so a crash anywhere in that process counts
        // (qa-16b-code #2). `instance` stays null, so KidAppComponentFactory runs no unlocked
        // setup there either.
        if (com.kidslauncher.mdm.lock.isBootCoverProcess(getProcessName())) {
            com.kidslauncher.mdm.lock.BootCoverGuard.install(this)
            return
        }
        instance = this
        // First, and on its own: the call services (screening, redirection, in-call) run in this
        // process and read the rules from memory. If anything below throws, they must still have
        // the real rules - and CallPolicyStore starts fail-closed, never open (QA #10). Before the
        // first unlock after a reboot this reads the device-protected copy (task 15).
        com.kidslauncher.mdm.calls.CallPolicyStore.refresh(this)
        // Before anything can ask whether an override/pause is active - see server.BootClock.
        // Guarded like everything below: an exception here must not take the call services down
        // with the process (QA step 2 #2). Without it every override/pause window counts as over.
        try {
            com.kidslauncher.mdm.server.BootClock.init(this)
        } catch (e: Exception) {
            android.util.Log.e("Application", "BootClock.init failed", e)
        }
        // Design 24b §3: a boot the launcher didn't see (safe mode, a start cut short) - early, so a
        // crash later in onCreate doesn't make the next start look like one. Never throws.
        com.kidslauncher.mdm.server.BootWitness.record(this)
        // Design 38 §4.3: this boot's start for the parent ("Anna was turned on"), right after the
        // witness and in direct boot too (device-protected prefs). Never throws.
        com.kidslauncher.mdm.server.PowerEvents.recordBoot(this)
        // A call notification a dead process left behind (emulator run 2026-10-06): posted to the
        // main thread so a bind that is already queued (onCallAdded) goes first.
        try {
            val app = this
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                try {
                    com.kidslauncher.mdm.calls.OngoingCalls.reconcileAtStart(app)
                } catch (e: Exception) {
                    android.util.Log.w("Application", "Call UI reconcile failed", e)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("Application", "Call UI reconcile not scheduled", e)
        }
        // TODO  Error: Invalid resource ID 0x00000000.
        // DynamicColors.applyToActivitiesIfAvailable(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
            // Handy's PIN lock crash guard (step 10): only crashes while the lock screen exists.
            if (com.kidslauncher.mdm.lock.PinLockActivity.instances > 0) {
                try {
                    com.kidslauncher.mdm.lock.PinLockStore.recordCrash(this@Application)
                } catch (t: Throwable) {
                    // Never let the guard stop the crash report.
                }
            }
            // Recorded for the server (cleanup 2026-10-06) - no crash screen, notification, share
            // or email on the phone.
            com.kidslauncher.mdm.crash.CrashReports.record(this@Application, throwable)
            exitProcess(1)
        }

        // Before the first unlock (only our direct-boot-aware call components can have started the
        // process) credential-encrypted storage can't be read: everything else - preferences,
        // enforcement, kiosk, services, tsnet - waits for the unlock. Nothing here may start lock
        // task or an activity before unlock (the BFU lockout incidents in CLAUDE.md).
        if (com.kidslauncher.mdm.calls.CallPolicyStore.userUnlocked(this)) {
            initUnlocked()
        } else {
            android.util.Log.i("Application", "Started before the first unlock: call path only")
            deferUntilUnlocked()
        }
    }

    private var unlockedInitDone = false

    /** Design 59 F3: when the unlocked setup began (elapsed realtime) - the unlock reaching this process; `BootHome`
     * logs its Home start against it. */
    @Volatile
    var unlockedAtElapsed: Long? = null
        private set
    private var unlockReceiver: BroadcastReceiver? = null

    /**
     * The setup that needs credential-encrypted storage; runs once per process, on the main thread.
     * An exception from it used to reach the handler above and kill the process - taking the call
     * screening/in-call services with it, so calls would ring unscreened (screening times out
     * open, Telecom falls back to the preloaded dialer). Now it's reported and the process stays up.
     */
    private fun initUnlocked() {
        if (unlockedInitDone) return
        unlockedInitDone = true
        unlockedAtElapsed = android.os.SystemClock.elapsedRealtime()
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        // First, on its own (design 16c): the PIN lock's mode for this process, before anything
        // else can fail and before any activity of ours exists - Home renders nothing while it
        // says LOCKED. One small preferences file; showing the lock comes later (PinLockRuntime.init).
        try {
            com.kidslauncher.mdm.lock.PinLockRuntime.decide(this)
        } catch (e: Exception) {
            android.util.Log.e("Application", "PIN lock state unreadable - Home stays dark while a PIN may be set", e)
        }
        // Design 59 §3.9: the boot's first CE start (decide's boot-count test) stamps this boot's start with the
        // unlock, before the anchor's first sync can send it. Never throws.
        com.kidslauncher.mdm.server.PowerEvents.recordUnlock(this, com.kidslauncher.mdm.lock.AndroidLock.firstCeStartOfBoot)
        val setupStart = android.os.SystemClock.elapsedRealtime()
        try {
            LauncherPreferences.init(PreferenceManager.getDefaultSharedPreferences(this), this.resources)
            initRest()
        } catch (e: Exception) {
            android.util.Log.e("Application", "Setup failed, continuing", e)
            com.kidslauncher.mdm.crash.CrashReports.record(this, e)
        }
        // Design 59 F3: the main thread's share of the gap between the unlock and our Home (the boot's Home start
        // is posted behind this) - the timing recipe in the design reads this line.
        android.util.Log.i("Application", "Unlocked setup took ${android.os.SystemClock.elapsedRealtime() - setupStart} ms")
    }

    /**
     * For a process started before the first unlock: refreshes the call rules from CE storage and
     * runs [initUnlocked]. Called by KidAppComponentFactory right before a component that isn't
     * direct-boot-aware is created (proof that CE storage is unlocked, even while the user is
     * still "unlocking" and isUserUnlocked() is false), and on ACTION_USER_UNLOCKED. Main thread.
     */
    fun ensureUnlockedInit() {
        if (unlockedInitDone) return
        com.kidslauncher.mdm.calls.CallPolicyStore.refresh(this, ceReadable = true)
        initUnlocked()
    }

    /**
     * ACTION_USER_UNLOCKED (only delivered to registered receivers) covers the case where no
     * other component of ours starts after the unlock; the component factory covers the rest.
     * Checked once more after registering, so an unlock in between isn't missed.
     */
    private fun deferUntilUnlocked() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = ensureUnlockedInit()
        }
        unlockReceiver = receiver
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (com.kidslauncher.mdm.calls.CallPolicyStore.userUnlocked(this)) ensureUnlockedInit()
    }

    private fun initRest() {

        // Try to restore old preferences
        migratePreferencesToNewVersion(this)

        // First time opening the app: set defaults
        // (the rest of first-launch setup happens in HomeActivity#onStart). Never on a phone that
        // enrolled before Home's first start - the reset would drop its token and policy (design 59d §8.17).
        val mdm = LauncherPreferences.mdm()
        val startedBefore = LauncherPreferences.internal().started()
        if (firstLaunchResetDue(startedBefore, mdm.enrolled(), mdm.deviceToken())) {
            resetPreferences(this)
        } else if (!startedBefore) {
            android.util.Log.i("Application", "Home hasn't started yet, but the phone is enrolled - keeping its preferences")
        }


        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(listener)


        val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
        launcherApps.registerCallback(launcherAppsCallback)

        if (Build.VERSION.SDK_INT >= VERSION_CODES.N) {
            val filter = IntentFilter().also {
                if (Build.VERSION.SDK_INT >= VERSION_CODES.VANILLA_ICE_CREAM) {
                    it.addAction(Intent.ACTION_PROFILE_AVAILABLE)
                    it.addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
                } else {
                    it.addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
                    it.addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
                }
            }
            ContextCompat.registerReceiver(
                this, profileAvailabilityBroadcastReceiver, filter,
                ContextCompat.RECEIVER_EXPORTED
            )
        }

        // The first loadApps also loads the parent's app names and icons (design 14), off the main
        // thread (qa-14-code #3).
        loadApps()
        // The drawer lists the Play Store while the override or the pause is open (design 28): a
        // window's open, its relock and "Lock again" reload the list - the flags are written
        // before WindowRelock notifies. Play's own suspension edge can't be the trigger: on a GMS
        // phone the store is never suspended (QA M1).
        com.kidslauncher.mdm.server.WindowRelock.addListener { loadApps() }

        createNotificationChannels(this)

        // Time rules (handy step 6): the call path's lifts/override-aware answer, screen-time
        // counting, and the first re-check of this process - it also arms the boundary alarm
        // (alarms don't survive a reboot or a process kill by an update).
        com.kidslauncher.mdm.timerules.TimeRulesRuntime.init(this)
        com.kidslauncher.mdm.timerules.ScreenTimeTracker.init(this)
        // A reboot ends every parent-PIN window: it locks again now (design 24b). A restart within
        // the same boot (a crash, LMK, an update) is a window whose time ran out: Home comes to the
        // front then (QA Low 6) - a real boot brings Home itself.
        com.kidslauncher.mdm.timerules.TimeRulesRuntime.recheck(
            this,
            if (com.kidslauncher.mdm.server.BootWitness.newBoot) {
                com.kidslauncher.mdm.server.RelockTrigger.BOOT
            } else {
                com.kidslauncher.mdm.server.RelockTrigger.TIMER
            },
        )
        // The parent PIN's one-time vault migration (Keystore keygen, commits) off the main thread,
        // before a Settings visit needs it (QA Low 11).
        val app = this
        CoroutineScope(Dispatchers.IO).launch { com.kidslauncher.mdm.server.ParentPin.prepareVault(app) }
        // Design 24b's fix round (QA High 1): an app that could read the parent PIN, at every start.
        com.kidslauncher.mdm.server.ForeignAccess.checkInBackground(this)

        // The update fence (step 11): a fence recorded by this or an older build is checked now
        // (process start = boot, update, crash) - before the PIN lock, whose first chrome refresh
        // reads it. Then the self-update's screen state and leftover files.
        try {
            com.kidslauncher.mdm.server.UpdateFence.init(this)
            com.kidslauncher.mdm.server.SelfUpdate.init(this)
            // Catalog downloads (design 13): sweep what no record needs, resume what waits.
            com.kidslauncher.mdm.server.AppDownloads.init(this)
            com.kidslauncher.mdm.lock.CameraLock.init(this)
        } catch (e: Exception) {
            android.util.Log.e("Application", "Update fence/self-update init failed, continuing", e)
        }

        // Handy's own PIN lock (step 10): starts LOCKED when it was active (a crashed or killed
        // process fails closed, and with the screen on the lock is shown at once), and holds the
        // screen on/off/unlock receiver for the whole process (QA 10 #4) - screen time, time
        // rules and the Play window use it too.
        com.kidslauncher.mdm.lock.PinLockRuntime.init(this)
        // Design 59 F3 (f3-run B3): the rest is its own main-thread message, posted after this one - behind the boot
        // pass PinLockRuntime.init just posted (the cover's take-over and Home's start) and behind what the system
        // queued meanwhile (the always-on VPN's service create, the listener's bind). One long message starved
        // KidVpnService into a service ANR on a slow first start (the process killed, the PIN typed twice). Where an
        // activity's launch started the process (the factory path), Home may render before this: its readers notify
        // when they come (the torch's listeners, GlanceStore's policy, the music surfaces). The head before this post
        // still runs inside a create the system queued and still counts on its ANR clock (F3R-5): `Unlocked setup
        // took` is the head alone now, and the tail logs its own time. Caught like initRest is.
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            val tailStart = android.os.SystemClock.elapsedRealtime()
            try {
                // Vibb music (design 21 step 4): the volume cap, and the player's "in front" ends at screen-off.
                com.kidslauncher.mdm.music.MusicMedia.init(this)
                // The flashlight (design 41): its camera, the platform's torch callback, the call-edge rule.
                com.kidslauncher.mdm.torch.Torch.init(this)
                // The lock's glance page (design 25): the parent's choice from the cached policy (off the
                // main thread), and our missed-call notification known again if it is still on screen.
                com.kidslauncher.mdm.badges.GlanceStore.loadAtStart()
                com.kidslauncher.mdm.calls.MissedCallNotifier.refreshAtStart(this)

                // The anchor service: every background sync and the SSE stream (the only nudge since
                // design 19); it arms the backstop alarm and syncs once at start - see its doc comment.
                CommandListenerService.start(this)
                // Design 35 §2.5: a report at once on Android's battery-low/okay edges (everything caught).
                com.kidslauncher.mdm.server.BatteryStatus.watch(this)
                // Design 38 §4.2: the shutdown's client built in advance (off the main thread), and again
                // whenever the tsnet proxy comes up. Everything caught.
                com.kidslauncher.mdm.server.PowerEvents.init(this)
                // Design 19: what the FCM era left (push_state with the Firebase installation ID, the
                // SDK's files and jobs) is deleted once, in the background.
                com.kidslauncher.mdm.push.FirebaseCleanup.runOnceInBackground(this)

                // The on-device DNS filter is the device's baseline network path now, not an
                // admin-configurable feature - see CLAUDE.md's on-device-filtering migration writeup.
                // Fails soft if not ready yet (no VPN consent granted) and gets retried via Android's own
                // always-on-VPN management once AppEnforcer.apply grants consent - see KidVpnService's own
                // doc comment. Gated on the cached vpnFilterEnabled preference (PolicyResponse.vpnFilterEnabled,
                // see AppEnforcer.applyVpnRestrictions) so a device a parent has turned filtering off for
                // doesn't flash it back on for a moment on every launch before the first sync corrects it.
                if (LauncherPreferences.mdm().vpnFilterEnabled()) {
                    KidVpnService.start(this)
                }
            } catch (e: Exception) {
                android.util.Log.e("Application", "Setup (after the lock) failed, continuing", e)
                com.kidslauncher.mdm.crash.CrashReports.record(app, e)
            }
            // Design 59 F3 (F3R-5): the tail's own share, next to "Unlocked setup took" (the head).
            android.util.Log.i("Application", "Unlocked setup tail took ${android.os.SystemClock.elapsedRealtime() - tailStart} ms")
        }
        // The embedded tailnet connection deliberately does NOT kick off here - TsnetClient.connect
        // runs tsnet's native Go/cgo runtime, a real crash surface (see that class's own doc
        // comment on the GrapheneOS hardened_malloc risk and the prior real SIGABRT incident this
        // app already hit once). Application.onCreate() is the earliest possible point in the
        // process's life, racing the very first UI paint after unlock - a crash here has no chance
        // to have shown anything yet. HomeActivity's first onResume() is the trigger instead, after
        // the launcher itself has actually rendered; MdmSyncWorker's regular sync cycle is the
        // retry-until-connected backstop either way, same as before.
    }

    fun getCustomAppNames(): HashMap<AbstractAppInfo, String> {
        return (customAppNames ?: LauncherPreferences.apps().customNames() ?: HashMap())
            .also { customAppNames = it }
    }

    /** Reloads the app list (Home and the drawer re-render and re-sort) - e.g. when the parent's
     * app names and icons changed ([com.kidslauncher.mdm.appdisplay.AppDisplay], design 14). */
    fun reloadApps() = loadApps()

    /** The parent's app names and icons were read from the cached policy in this process. */
    @Volatile
    private var appDisplayLoaded = false

    /**
     * Launcher round 7 §4: an installed, updated or removed app's rendered icons go before the list
     * reloads, so an update's new icon shows on Home, the glance page and the time-rule screen. The
     * package is evicted again right before the reloaded list is posted ([appLoads], qa-l7-a-code #1):
     * until then a render from the old list (its `DetailedAppInfo` holds the old APK's drawable) could
     * put the old bitmap back under the same key.
     */
    private fun evictIcons(packageName: String?) {
        packageName ?: return
        synchronized(iconsToEvict) { iconsToEvict += packageName }
        com.kidslauncher.mdm.ui.home.KidAvatars.evictPackage(packageName)
    }

    /** Packages whose icons go again when the next list lands ([evictIcons]). */
    private val iconsToEvict = mutableSetOf<String>()

    /**
     * One app-list load at a time (launcher round 7 §4): a request while a load runs is folded into
     * one more load after it (a conflated channel), so a burst of suspend/unsuspend callbacks from
     * `AppEnforcer` can never post an older list last. Started on the first request.
     *
     * Once per burst (PLAN "Provisioning storm", qa-home-round-code Q-4): the consumer loads when
     * [com.kidslauncher.mdm.ui.home.appLoadDue] says so - 300 ms with no new request, at most 2 s after
     * the burst's first - so the enforcer's hide/suspend storm at provisioning is one load and one Home
     * render, not one per package. The process's first request loads at once (Home's cold start).
     */
    private val appLoads: Channel<Unit> by lazy {
        Channel<Unit>(Channel.CONFLATED).also { requests ->
            CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
                com.kidslauncher.mdm.ui.home.runAppLoads(
                    next = { timeoutMs -> requests.nextAppLoad(timeoutMs) },
                    now = { android.os.SystemClock.elapsedRealtime() },
                ) {
                    try {
                        // Once per process, before the first list (it decodes the cached policy -
                        // never on the main thread, qa-14-code #3); later changes come from the
                        // sync's refresh.
                        if (!appDisplayLoaded) {
                            appDisplayLoaded = true
                            com.kidslauncher.mdm.appdisplay.AppDisplay.refresh(applicationContext, reload = false)
                        }
                        // The packages asked for before this load: evicted again once it is done.
                        val evict = synchronized(iconsToEvict) { iconsToEvict.toSet() }
                        val list = getApps(packageManager, applicationContext)
                        synchronized(iconsToEvict) { iconsToEvict -= evict }
                        evict.forEach { com.kidslauncher.mdm.ui.home.KidAvatars.evictPackage(it) }
                        apps.postValue(list)
                    } catch (e: Exception) {
                        android.util.Log.w("Application", "Couldn't load the app list: ${e.javaClass.simpleName}")
                    }
                }
            }
        }
    }

    private fun loadApps() {
        appLoads.trySend(Unit)
    }
}
