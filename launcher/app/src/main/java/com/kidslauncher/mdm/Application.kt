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
import com.kidslauncher.mdm.preferences.migratePreferencesToNewVersion
import com.kidslauncher.mdm.preferences.resetPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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

    // TODO: only update specific apps
    private val launcherAppsCallback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackageAdded(p0: String?, p1: UserHandle?) {
            loadApps()
            // Reacts to this specific install immediately (no network round-trip, no waiting on
            // the next sync) - see AppEnforcer.enforceOnNewPackage's own doc comment. Called back
            // on the main thread by default; AppEnforcer.enforceOnNewPackage does DevicePolicyManager
            // Binder calls, which don't belong there (see the ANR this app already hit once from a
            // similar main-thread AppEnforcer call in SettingsFragmentLauncher).
            p0?.let { packageName ->
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        val blocked = AppEnforcer.enforceOnNewPackage(this@Application, packageName)
                        // A new app the parent hasn't allowed (e.g. from Play): report it now, so
                        // the device page lists it for allowlisting (handy step 7).
                        if (blocked) com.kidslauncher.mdm.push.SyncRunner.request(this@Application, "package_added")
                    } catch (e: Exception) {
                        android.util.Log.w("Application", "New-package enforcement failed for $packageName", e)
                    }
                }
            }
        }

        override fun onPackageChanged(p0: String?, p1: UserHandle?) {
            loadApps()
        }

        override fun onPackagesAvailable(p0: Array<out String>?, p1: UserHandle?, p2: Boolean) {
            // TODO
        }

        override fun onPackagesSuspended(packageNames: Array<out String>?, user: UserHandle?) {
            loadApps()
        }

        override fun onPackagesUnsuspended(packageNames: Array<out String>?, user: UserHandle?) {
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
            sendCrashNotification(this@Application, throwable)
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
        unlockReceiver?.let { runCatching { unregisterReceiver(it) } }
        unlockReceiver = null
        try {
            LauncherPreferences.init(PreferenceManager.getDefaultSharedPreferences(this), this.resources)
            initRest()
        } catch (e: Exception) {
            android.util.Log.e("Application", "Setup failed, continuing", e)
            sendCrashNotification(this, e)
        }
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
        // (the rest of first-launch setup happens in HomeActivity#onStart)
        if (!LauncherPreferences.internal().started()) {
            resetPreferences(this)
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

        loadApps()

        createNotificationChannels(this)

        // Time rules (handy step 6): the call path's lifts/override-aware answer, screen-time
        // counting, and the first re-check of this process - it also arms the boundary alarm
        // (alarms don't survive a reboot or a process kill by an update).
        com.kidslauncher.mdm.timerules.TimeRulesRuntime.init(this)
        com.kidslauncher.mdm.timerules.ScreenTimeTracker.init(this)
        com.kidslauncher.mdm.timerules.TimeRulesRuntime.recheck(this)

        // The update fence (step 11): a fence recorded by this or an older build is checked now
        // (process start = boot, update, crash) - before the PIN lock, whose first chrome refresh
        // reads it. Then the self-update's screen state and leftover files.
        try {
            com.kidslauncher.mdm.server.UpdateFence.init(this)
            com.kidslauncher.mdm.server.SelfUpdate.init(this)
        } catch (e: Exception) {
            android.util.Log.e("Application", "Update fence/self-update init failed, continuing", e)
        }

        // Handy's own PIN lock (step 10): starts LOCKED when it was active (a crashed or killed
        // process fails closed, and with the screen on the lock is shown at once), and holds the
        // screen on/off/unlock receiver for the whole process (QA 10 #4) - screen time, time
        // rules and the Play window use it too.
        com.kidslauncher.mdm.lock.PinLockRuntime.init(this)

        // The anchor service: screen signals, every background sync, the SSE stream when FCM isn't
        // in use; it arms the backstop alarm and syncs once at start - see its doc comment.
        CommandListenerService.start(this)

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

    private fun loadApps() {
        CoroutineScope(Dispatchers.Default).launch {
            apps.postValue(getApps(packageManager, applicationContext))
        }
    }
}
