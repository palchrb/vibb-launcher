package com.kidslauncher.mdm.server

import android.app.KeyguardManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.ProxyInfo
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_WINDOW
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.createWindowChannel
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.lock.VoipCalls
import com.kidslauncher.mdm.play.PLAY_STORE
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.dto.RelockReport
import com.kidslauncher.mdm.timerules.TimeRuleAlarm
import com.kidslauncher.mdm.ui.LockActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Date
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "WindowRelock"
private const val PREFS = "window_relock"
private const val ACTION_LOCK_AGAIN = "com.kidslauncher.mdm.action.LOCK_AGAIN"
private const val OVERRIDE_NOTIFICATION_ID = 1007
private const val PAUSE_NOTIFICATION_ID = 1008

/**
 * Parent-PIN windows that lock again by themselves (design 24b §1, M9): an override (the time
 * lock's "Enter unlock code") or a pause (Settings) opens the phone completely for up to 2 hours;
 * when it ends - the boundary alarm, a reboot, screen on, the sync that ends an override, "Lock
 * again", the Settings switch, the PWA's `end_windows` - this resets what the device owner can and
 * records the rest for the status report ([runRelock], WindowRelockPlan.kt).
 *
 * - [opened]: the two starters (`OfflineOverride.activate`, `RestrictionsPause.start`, only ever
 *   called by `ParentPin.check`) call it before anything is lifted.
 * - [check] never blocks: it hands off to one IO worker (a conflated channel: one relock at a
 *   time, a request during one runs once more after it).
 * - [relockInSync]: the sync's own, before its status report, so the report carries the relock.
 * - [lockAgain]: no PIN - it only takes access away.
 */
object WindowRelock {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requests = Channel<RelockTrigger>(Channel.CONFLATED)
    private val consumerStarted = AtomicBoolean(false)
    private val relockMutex = Mutex()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val handler by lazy { Handler(Looper.getMainLooper()) }

    /** Who asked for the next relock ("Lock again", a command, Settings): the next run reports it
     * whoever runs it - the worker or the sync. */
    private val askedTrigger = AtomicReference<RelockTrigger?>(null)

    fun store(context: Context) =
        WindowRelockStore(context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    /** Home's banner and others: called on the main thread after a window opened or relocked. */
    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }

    private fun notifyListeners() {
        handler.post { listeners.forEach { it() } }
    }

    /** The windows open now. */
    fun openWindows(): Set<WindowKind> = buildSet {
        if (OfflineOverride.isActive()) add(WindowKind.OVERRIDE)
        if (RestrictionsPause.isActive()) add(WindowKind.PAUSE)
    }

    /** The latest end of an open window (wall clock) for Home's banner; `null` = none open. */
    fun openUntilMs(): Long? {
        val mdm = LauncherPreferences.mdm()
        return listOfNotNull(
            mdm.offlineOverrideExpiresAt().takeIf { OfflineOverride.isActive() },
            mdm.restrictionsPausedUntil().takeIf { RestrictionsPause.isActive() },
        ).maxOrNull()
    }

    /** `relock_blocked` (§1.6): treated as not allowlisted by every apply(). */
    fun blockedPackages(context: Context): Set<String> = try {
        store(context).blocked().keys
    } catch (e: Exception) {
        emptySet()
    }

    /** The last finished relock, for the status report. */
    fun lastReport(context: Context): RelockReport? = try {
        store(context).lastReport()
    } catch (e: Exception) {
        null
    }

    // ---- a window opens -------------------------------------------------------------------------

    /**
     * Called by the starters before anything is lifted (§1.2): commits the marker (`pending`,
     * `gen + 1`, the window) and - while no window was pending - the start snapshot (credential,
     * packages, disabled apps), then runs [lift] (the window's own flags) under the same lock, so a
     * relock never sees a marker without its window. Then the window's end alarm, the
     * reset-password token and - as device owner - the "Lock again" notification. Never throws: the parent's PIN was right, the window opens whatever.
     * Background thread (inside ParentPin's lock).
     */
    fun opened(context: Context, kind: WindowKind, start: WindowStart, lift: () -> Unit) {
        val app = context.applicationContext
        var lifted = false
        try {
            val store = store(app)
            synchronized(WindowRelockStore.LOCK) {
                val marker = store.marker()
                val startState = if (takesStartSnapshot(marker)) startState(app) else null
                // A failed commit still holds in memory (SharedPreferences): this process relocks.
                if (!store.saveOpened(markerOpened(marker, kind), startState)) Log.w(LOG_TAG, "Couldn't commit the relock marker")
                lift()
                lifted = true
            }
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Recording the window failed", e)
        }
        if (!lifted) lift()
        // The window's end alarm right after the lift, whatever the starter's apply() does (QA Low 5).
        try {
            TimeRuleAlarm.schedule(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The window's alarm failed", e)
        }
        try {
            ensureResetToken(app)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Reset-password token failed", e)
        }
        // Only a device owner relocks, so only it offers "Lock again" (QA Low 7).
        if (deviceOwner(app)) postNotification(app, kind, start.untilWallMs)
        notifyListeners()
    }

    private fun deviceOwner(app: Context): Boolean = try {
        dpm(app)?.isDeviceOwnerApp(app.packageName) == true
    } catch (e: Exception) {
        false
    }

    @Suppress("DEPRECATION") // WifiConfiguration's proxy and SSID
    private fun startState(app: Context): RelockStart {
        val packages = try {
            installedPackages(app, withInstaller = false).associate { it.name to PackageState(it.signers, it.updatedAt) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Package snapshot failed", e)
            null
        }
        val disabled = try {
            disabledSystemPackages(app)
        } catch (e: Exception) {
            null
        }
        val dpm = dpm(app)?.takeIf { it.isDeviceOwnerApp(app.packageName) }
        val caCerts = try {
            dpm?.getInstalledCaCerts(admin(app))?.map { sha256Hex(it) }?.toSet()
        } catch (e: Exception) {
            null
        }
        val proxies = try {
            // Wi-Fi configs need location (QA #3): only for the first window, and it is put back.
            dpm?.let { d -> withWifiConfigs(app, d) { _, configs -> configs.mapNotNull { c -> c.httpProxy?.let { c.SSID.orEmpty() to proxyKey(it) } }.toMap() } }
        } catch (e: Exception) {
            null
        }
        return RelockStart(
            secure = deviceSecure(app),
            packages = packages,
            disabled = disabled,
            atMs = System.currentTimeMillis(),
            caCerts = caCerts,
            proxies = proxies,
        )
    }

    private fun sha256Hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    /** A proxy as compared with the start's: the PAC URL, or host:port and the exclusions. */
    private fun proxyKey(proxy: ProxyInfo): String {
        val pac = proxy.pacFileUrl
        return if (pac != null && pac != Uri.EMPTY) "pac $pac" else "${proxy.host}:${proxy.port} ${proxy.exclusionList.orEmpty().joinToString(",")}"
    }

    /**
     * Runs [block] on the saved Wi-Fi networks. The Wi-Fi config APIs need location on and fine
     * location granted (QA #3), so both are switched on around it and location restored; no fix
     * is taken. A permission granted here stays granted - revoking a runtime permission kills our
     * process - only its policy state goes back. Device owner only.
     */
    @Suppress("DEPRECATION")
    private fun <T> withWifiConfigs(app: Context, dpm: DevicePolicyManager, block: (WifiManager, List<android.net.wifi.WifiConfiguration>) -> T): T? {
        val wm = app.getSystemService(WifiManager::class.java) ?: return null
        val admin = admin(app)
        val fine = android.Manifest.permission.ACCESS_FINE_LOCATION
        val locationWasOn = QuickControls.isLocationEnabled(app)
        val fineGranted = ContextCompat.checkSelfPermission(app, fine) == PackageManager.PERMISSION_GRANTED
        val fineState = dpm.getPermissionGrantState(admin, app.packageName, fine)
        try {
            if (!fineGranted) QuickControls.selfGrantPermission(app, dpm, admin, fine)
            if (!locationWasOn) QuickControls.setLocationEnabled(dpm, admin, true)
            return block(wm, wm.configuredNetworks.orEmpty())
        } finally {
            if (!locationWasOn) QuickControls.setLocationEnabled(dpm, admin, false)
            if (!fineGranted && fineState != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                try {
                    dpm.setPermissionGrantState(admin, app.packageName, fine, fineState)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't put the location permission's state back", e)
                }
            }
        }
    }

    /** §1.5: 32 random bytes, stored first (CE prefs), set only while it isn't active and the phone
     * has no credential - that activates it at once (QA #9). */
    private fun ensureResetToken(app: Context) {
        val dpm = dpm(app) ?: return
        if (!dpm.isDeviceOwnerApp(app.packageName)) return
        val admin = admin(app)
        val store = store(app)
        val active = try {
            dpm.isResetPasswordTokenActive(admin)
        } catch (e: Exception) {
            false
        }
        if (!resetTokenNeeded(active, store.token() != null, deviceSecure(app))) return
        val token = ByteArray(32).also { SecureRandom().nextBytes(it) }
        if (!store.saveToken(token.toHex())) return
        if (!dpm.setResetPasswordToken(admin, token)) Log.w(LOG_TAG, "setResetPasswordToken refused")
    }

    // ---- relocking ------------------------------------------------------------------------------

    /** Never blocks (QA #2): the worker relocks when a window opened and none is open any more. */
    fun check(context: Context, trigger: RelockTrigger) {
        val app = context.applicationContext
        if (consumerStarted.compareAndSet(false, true)) {
            scope.launch {
                for (t in requests) {
                    try {
                        relockMutex.withLock { relock(app, t) }
                    } catch (e: Exception) {
                        Log.w(LOG_TAG, "Relock failed", e)
                    }
                }
            }
        }
        requests.trySend(trigger)
    }

    /** In the sync, right after its apply() and before its status report (§1.2). */
    suspend fun relockInSync(context: Context) {
        relockMutex.withLock { relock(context.applicationContext, RelockTrigger.SYNC) }
    }

    private fun relock(app: Context, trigger: RelockTrigger) {
        val dpm = dpm(app) ?: return
        if (!dpm.isDeviceOwnerApp(app.packageName)) {
            // Not device owner (provisioning failed): no relock, and no notification left behind (QA Low 7).
            cancelNotifications(app, emptySet())
            return
        }
        val asked = askedTrigger.getAndSet(null) ?: trigger
        val outcome = runRelock(store(app), AndroidPlatform(app, dpm), asked)
        val report = outcome.report ?: return
        Log.i(LOG_TAG, "Relocked (${report.trigger}, ${report.windows}): cleared=${outcome.cleared}, failures=${report.failures}")
        // A device admin or accessibility service the window let in: the parent PIN is refused (QA High 1).
        ForeignAccess.checkInBackground(app)
        notifyListeners()
    }

    /**
     * "Lock again" (the notification, Home's banner), the Settings pause switch turned off and the
     * PWA's `end_windows`: ends both windows, then relocks. No PIN - it only takes access away. A
     * window an older build opened is adopted first, so it relocks too. Returns the windows it
     * ended. Any thread (briefly takes the marker lock).
     */
    fun lockAgain(context: Context, trigger: RelockTrigger): Set<WindowKind> {
        val app = context.applicationContext
        val ended = synchronized(WindowRelockStore.LOCK) {
            val store = store(app)
            val open = openWindows()
            adoptedMarker(store.marker(), open)?.let { store.saveMarker(it) }
            if (WindowKind.OVERRIDE in open) OfflineOverride.clear()
            if (WindowKind.PAUSE in open) RestrictionsPause.clear()
            open
        }
        askedTrigger.set(trigger)
        check(app, trigger)
        notifyListeners()
        return ended
    }

    /** [lockAgain] off the calling thread (the UI's). */
    fun lockAgainAsync(context: Context, trigger: RelockTrigger) {
        val app = context.applicationContext
        scope.launch {
            try {
                lockAgain(app, trigger)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Lock again failed", e)
            }
        }
    }

    /** The PWA's `allow_blocked`: the `relock_blocked` entries it [listed] go (all with `null`,
     * from an older server) - each only while its key is the one the PWA showed, and never an app
     * signed with another key than the catalog's pin ([allowBlocked], fix round QA Medium 2, QA of
     * cmd-expiry Low 1); the sync's apply() right after the command shows them again. Returns how
     * many were let out. */
    fun allowBlocked(context: Context, listed: Map<String, String?>?): Int {
        val app = context.applicationContext
        val store = store(app)
        synchronized(WindowRelockStore.LOCK) {
            val before = store.blocked()
            val packages = installedPackages(app, withInstaller = false)
            val after = allowBlocked(before, packages.associate { it.name to it.signers }, lockedSigners(store), listed)
            val letOut = packages.filter { it.name in before && it.name !in after }.associate { it.name to it.updatedAt }
            store.saveAllowed(after, letOut)
            return before.size - after.size
        }
    }

    private fun lockedSigners(store: WindowRelockStore): Map<String, Set<String>> = store.catalogPins().mapValues { setOf(it.value) }

    /** The catalog's pinned signers from the server's app list (M10's `signer_sha256`): the relock
     * and the install check below compare against them. */
    fun rememberCatalogPins(context: Context, apps: List<com.kidslauncher.mdm.server.dto.TrackedAppUpdate>) {
        try {
            val pins = apps.mapNotNull { app ->
                val signer = app.signerSha256?.lowercase()?.replace(":", "")
                if (app.packageName.isBlank() || signer == null || !isSignerHex(signer)) null else app.packageName to signer
            }.toMap()
            store(context).saveCatalogPins(pins)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't keep the catalog's signers", e)
        }
    }

    /**
     * A package installed or replaced while no window is open (fix round QA Low 3): the real app
     * back from Play or the catalog clears its block; something installed just now from elsewhere
     * (it can't, outside a window - but an install racing the relock can) or with another key than
     * the catalog's pin is blocked. During a window the relock decides. Background thread.
     */
    fun onPackageInstalled(context: Context, packageName: String): Boolean {
        try {
            val app = context.applicationContext
            if (openWindows().isNotEmpty() || store(app).marker().pending) return false
            val pkg = installedPackages(app, withInstaller = true, only = packageName).firstOrNull() ?: return false
            val store = store(app)
            synchronized(WindowRelockStore.LOCK) {
                val before = store.blocked()
                val pins = lockedSigners(store)
                val now = System.currentTimeMillis()
                val after = blockAfterInstall(pkg, before, pins, setOf(PLAY_STORE, app.packageName), now, store.allowed()) ?: return false
                val note = if (packageName in after && packageName !in before) {
                    mapOf(packageName to BlockNote(blockReason(pkg, pins[packageName], null), BlockFound.INSTALL, now))
                } else {
                    emptyMap()
                }
                store.saveBlocked(after, note)
                Log.i(LOG_TAG, "$packageName ${if (packageName in after) "blocked" else "no longer blocked"} after its install")
                return true
            }
        } catch (e: PackageManager.NameNotFoundException) {
            // A static shared library's versioned name (Chrome's trichromelibrary_<version>), or
            // a package gone again: nothing to check, nothing to warn about.
            Log.d(LOG_TAG, "Install check skipped for $packageName: not an installed package")
            return false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Install check failed for $packageName", e)
            return false
        }
    }

    /**
     * The status report's `relock_blocks` (fix of 2026-10-12): every current block with its app's
     * signing key now and its [BlockNote] - an install-time block too, which no relock report
     * carries - with how many there are in all (`relock_blocks_total`, the list stops at
     * [RELOCK_BLOCKS_MAX]; QA of cmd-expiry, Low 2). `null` when not device owner (nothing is
     * blocked then). Never throws.
     */
    fun reportBlocks(context: Context): Pair<List<com.kidslauncher.mdm.server.dto.RelockBlockReport>, Int>? = try {
        val app = context.applicationContext
        val dpm = dpm(app)
        if (dpm == null || !dpm.isDeviceOwnerApp(app.packageName)) {
            null
        } else {
            val store = store(app)
            val blocked = store.blocked()
            val signers = blocked.keys.mapNotNull { name ->
                try {
                    installedPackages(app, withInstaller = false, only = name).firstOrNull()?.let { name to it.signers }
                } catch (e: PackageManager.NameNotFoundException) {
                    null
                }
            }.toMap()
            currentBlocks(blocked, store.blockNotes(), signers) to currentBlocksTotal(blocked, signers)
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't list the blocks for the report", e)
        null
    }

    // ---- notifications ----------------------------------------------------------------------------

    private fun notificationId(kind: WindowKind) = when (kind) {
        WindowKind.OVERRIDE -> OVERRIDE_NOTIFICATION_ID
        WindowKind.PAUSE -> PAUSE_NOTIFICATION_ID
    }

    /** §1.3: ongoing (a device owner's can't be swiped away, AOSP 14 `canBeNonDismissible`), silent,
     * one per window, with "Lock again". */
    private fun postNotification(app: Context, kind: WindowKind, untilMs: Long) {
        try {
            createWindowChannel(app)
            val lockAgain = PendingIntent.getBroadcast(
                app, notificationId(kind),
                Intent(app, WindowRelockReceiver::class.java).setAction(ACTION_LOCK_AGAIN),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val time = android.text.format.DateFormat.getTimeFormat(app).format(Date(untilMs))
            val title = when (kind) {
                WindowKind.OVERRIDE -> R.string.window_override_open
                WindowKind.PAUSE -> R.string.window_pause_open
            }
            val notification = NotificationCompat.Builder(app, NOTIFICATION_CHANNEL_WINDOW)
                .setSmallIcon(R.drawable.ic_window_open)
                .setContentTitle(app.getString(title, time))
                .setContentText(app.getString(R.string.window_notification_text))
                .setOngoing(true)
                .setSilent(true)
                .addAction(0, app.getString(R.string.window_lock_again), lockAgain)
                .build()
            app.getSystemService(NotificationManager::class.java)?.notify(notificationId(kind), notification)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't show the window notification", e)
        }
    }

    private fun cancelNotifications(app: Context, open: Set<WindowKind>) {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return
        for (kind in WindowKind.entries) if (kind !in open) nm.cancel(notificationId(kind))
    }

    // ---- the platform -------------------------------------------------------------------------------

    private fun dpm(app: Context) = app.getSystemService(DevicePolicyManager::class.java)

    private fun admin(app: Context) = ComponentName(app, MdmDeviceAdminReceiver::class.java)

    private fun deviceSecure(app: Context): Boolean = try {
        app.getSystemService(KeyguardManager::class.java)?.isDeviceSecure == true
    } catch (e: Exception) {
        // Unknown counts as secure: nothing is removed on a guess.
        true
    }

    private fun isSystem(info: ApplicationInfo?) = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0

    private fun isInstalled(info: ApplicationInfo?) = info != null && (info.flags and ApplicationInfo.FLAG_INSTALLED) != 0

    /** Every installed non-system package but ours (or only [only]), with its signers, update time
     * and - for the relock, not the start snapshot (it runs inside the PIN check) - its installer. */
    private fun installedPackages(app: Context, withInstaller: Boolean, only: String? = null): List<InstalledPackage> {
        val pm = app.packageManager
        val flags = PackageManager.PackageInfoFlags.of((PackageManager.GET_SIGNING_CERTIFICATES or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong())
        val packages = if (only != null) listOf(pm.getPackageInfo(only, flags)) else pm.getInstalledPackages(flags)
        return packages
            .filter { !isSystem(it.applicationInfo) && isInstalled(it.applicationInfo) && it.packageName != app.packageName }
            .map { info ->
                InstalledPackage(
                    name = info.packageName,
                    signers = SelfUpdate.signers(info.signingInfo).orEmpty(),
                    updatedAt = info.lastUpdateTime,
                    installer = if (!withInstaller) null else try {
                        pm.getInstallSourceInfo(info.packageName).installingPackageName
                    } catch (e: Exception) {
                        null
                    },
                )
            }
    }

    /** System packages disabled now (keyboards, dialer, clock, cell broadcast, anything). */
    private fun disabledSystemPackages(app: Context): Set<String> {
        val flags = PackageManager.ApplicationInfoFlags.of(
            (PackageManager.MATCH_DISABLED_COMPONENTS or PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS or PackageManager.MATCH_UNINSTALLED_PACKAGES).toLong(),
        )
        return app.packageManager.getInstalledApplications(flags)
            .filter { isSystem(it) && isInstalled(it) && !it.enabled }
            .mapTo(mutableSetOf()) { it.packageName }
    }

    private fun systemPackage(app: Context, pkg: String): Boolean = try {
        isSystem(app.packageManager.getApplicationInfo(pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())))
    } catch (e: Exception) {
        false
    }

    /** The self-update gate's view of a call (§1.2 step 6, QA #11): ours, Telecom's, a VoIP call,
     * the lock's emergency flow - Home then stays where it is. */
    private fun liveCall(app: Context): Boolean =
        OngoingCalls.hasLiveCall || SelfUpdate.telecomInCall(app) != false || VoipCalls.liveCall || PinLockRuntime.emergencyFlowActive

    private class AndroidPlatform(private val app: Context, private val dpm: DevicePolicyManager) : RelockPlatform {
        private val admin = admin(app)

        override fun now() = System.currentTimeMillis()

        override fun openWindows() = WindowRelock.openWindows()

        override fun secureNow() = deviceSecure(app)

        override fun tokenUsable(): Boolean = store(app).token() != null && dpm.isResetPasswordTokenActive(admin)

        override fun removeCredential(): Boolean {
            val token = store(app).token()?.fromHex() ?: return false
            val ok = dpm.resetPasswordWithToken(admin, null, token, 0)
            Log.i(LOG_TAG, "Removing the Android screen lock set in the window: $ok")
            return ok && !deviceSecure(app)
        }

        override fun installedPackages() = installedPackages(app, withInstaller = true)

        override fun allowlist(): List<String>? = currentPolicyDecision().policy?.allowlist

        override fun lockedSigners() = lockedSigners(store(app))

        override fun trustedInstallers() = setOf(PLAY_STORE, app.packageName)

        override fun apply() = AppEnforcer.apply(app, currentPolicyDecision().policy)

        override fun permittedListsOk() = AppEnforcer.permittedListsOk

        /** The Wi-Fi proxies that are fine on a Wi-Fi network after [clearProxies]: the start's,
         * and those just cleared - the network's link may not show the clear yet (QA Low 8). */
        private val wifiProxiesOk = mutableSetOf<String>()

        /**
         * A saved Wi-Fi network's proxy (manual or PAC) set or changed in the window goes:
         * `setHttpProxy(null)` + `updateNetwork` (a device owner may, AOSP
         * `canModifyProxySettings`); one it had at the window's start stays (QA Low 4).
         */
        @Suppress("DEPRECATION")
        override fun clearProxies(atStart: Map<String, String>?): ResetCount = withWifiConfigs(app, dpm) { wm, configs ->
            var cleared = 0
            var left = 0
            for (config in configs) {
                val proxy = config.httpProxy?.let { proxyKey(it) } ?: continue
                if (proxyStays(config.SSID.orEmpty(), proxy, atStart)) {
                    wifiProxiesOk += proxy
                    continue
                }
                config.httpProxy = null
                if (wm.updateNetwork(config) != -1) {
                    cleared++
                    wifiProxiesOk += proxy
                } else {
                    left++
                    Log.w(LOG_TAG, "Couldn't clear a Wi-Fi proxy")
                }
            }
            ResetCount(cleared, left)
        } ?: ResetCount(0, 0)

        /** `uninstallCaCert` per certificate added in the window (QA Low 4); KeyChain errors are
         * swallowed by the platform, so the list is read again (QA Low 9). */
        override fun removeCaCerts(atStart: Set<String>?): ResetCount {
            val before = dpm.getInstalledCaCerts(admin).associateBy { sha256Hex(it) }
            val remove = caCertsToRemove(before.keys, atStart)
            if (remove.isEmpty()) return ResetCount(0, 0)
            for (hash in remove) dpm.uninstallCaCert(admin, before.getValue(hash))
            val after = dpm.getInstalledCaCerts(admin).map { sha256Hex(it) }.toSet()
            val left = remove.count { it in after }
            return ResetCount(remove.size - left, left)
        }

        override fun regrantNotifications() =
            QuickControls.fixOwnPermission(app, dpm, admin, android.Manifest.permission.POST_NOTIFICATIONS)

        override fun detect(disabledAtStart: Set<String>?): RelockDetections {
            val ours = app.packageName
            val cm = app.getSystemService(ConnectivityManager::class.java)
            // Any network's proxy but a Wi-Fi one that is fine (QA Low 8): mobile data's (an APN
            // proxy) can't be cleared, a Wi-Fi one that couldn't be is here too.
            @Suppress("DEPRECATION")
            val proxyLeft = cm?.allNetworks.orEmpty().any { network ->
                val proxy = cm?.getLinkProperties(network)?.httpProxy ?: return@any false
                val wifi = cm?.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
                !(wifi && proxyKey(proxy) in wifiProxiesOk)
            }
            val accessibility = Settings.Secure.getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                .orEmpty().split(':').mapNotNull { ComponentName.unflattenFromString(it)?.packageName }
                .filter { it != ours && !systemPackage(app, it) }
            val admins = dpm.activeAdmins.orEmpty().map { it.packageName }.filter { it != ours }
            val listeners = NotificationManagerCompat.getEnabledListenerPackages(app).filter { it != ours && !systemPackage(app, it) }
            val nm = app.getSystemService(NotificationManager::class.java)
            val notificationsOff = nm != null &&
                (!nm.areNotificationsEnabled() || nm.notificationChannels.any { it.importance == NotificationManager.IMPORTANCE_NONE })
            return RelockDetections(
                proxyLeft = proxyLeft,
                credentialLeft = false,
                accessibilityEnabled = accessibility,
                adminsEnabled = admins,
                appsDisabled = newlyDisabled(disabledAtStart, disabledSystemPackages(app)),
                notificationListeners = listeners,
                ownNotificationsOff = notificationsOff,
            )
        }

        /** The lock re-check (the time-rule screen if a rule is on), the alarm (the window's end
         * leaves it), Home to the front unless a call, a newer window or a process start (the boot
         * brings Home itself, design 16), the notifications. */
        override fun finish(windowReopened: Boolean, processStart: Boolean) {
            val changedTo = reevaluateLockReasonFromCache(app)
            TimeRuleAlarm.schedule(app)
            val call = liveCall(app)
            if (!windowReopened && !processStart && !call) com.kidslauncher.mdm.lock.HomeFront.bring(app, "the window locked again")
            if (changedTo != null && changedTo != LockReason.NONE && !call) handler.post { LockActivity.start(app) }
            cancelNotifications(app, openWindows())
        }
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

private fun String.fromHex(): ByteArray? =
    if (length % 2 != 0) null else try {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    } catch (e: Exception) {
        null
    }

/** "Lock again" on a window's notification - our own explicit PendingIntent only (not exported). */
class WindowRelockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_LOCK_AGAIN) return
        WindowRelock.lockAgainAsync(context, RelockTrigger.LOCK_AGAIN)
    }
}
