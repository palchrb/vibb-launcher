package com.kidslauncher.mdm

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.LauncherApps
import android.content.pm.ShortcutInfo
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Bundle
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
                    AppEnforcer.enforceOnNewPackage(this@Application, packageName)
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
        // TODO  Error: Invalid resource ID 0x00000000.
        // DynamicColors.applyToActivitiesIfAvailable(this)

        Thread.setDefaultUncaughtExceptionHandler { _, throwable ->
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
    private var unlockActivityCallbacks: ActivityLifecycleCallbacks? = null

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
        unlockActivityCallbacks?.let { unregisterActivityLifecycleCallbacks(it) }
        unlockActivityCallbacks = null
        try {
            LauncherPreferences.init(PreferenceManager.getDefaultSharedPreferences(this), this.resources)
            initRest()
        } catch (e: Exception) {
            android.util.Log.e("Application", "Setup failed, continuing", e)
            sendCrashNotification(this, e)
        }
    }

    /**
     * Runs [initUnlocked] on the first of: ACTION_USER_UNLOCKED (only delivered to registered
     * receivers), an activity being created after the unlock (Home can start in this process
     * before that broadcast arrives), or the user already being unlocked once both are
     * registered (no missed-broadcast race). Refreshes the call rules from CE storage first.
     */
    private fun deferUntilUnlocked() {
        val runIfUnlocked = {
            val store = com.kidslauncher.mdm.calls.CallPolicyStore
            if (!unlockedInitDone && store.userUnlocked(this)) {
                store.refresh(this)
                initUnlocked()
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = runIfUnlocked()
        }
        unlockReceiver = receiver
        ContextCompat.registerReceiver(
            this, receiver, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        val callbacks = object : ActivityLifecycleCallbacks {
            override fun onActivityPreCreated(activity: Activity, savedInstanceState: Bundle?) = runIfUnlocked()
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        }
        unlockActivityCallbacks = callbacks
        registerActivityLifecycleCallbacks(callbacks)
        runIfUnlocked()
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

        // CommandListenerService both holds the SSE connection and drives the periodic backstop
        // sync directly off its own timer - see that class's doc comment for why this replaced a
        // separate WorkManager-based schedule() call here.
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
