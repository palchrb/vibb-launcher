package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.Log

private const val LOG_TAG = "QuickControls"

/**
 * Bits for [com.kidslauncher.mdm.server.dto.PolicyResponse.quickControlsMask] - which switches
 * show up on [com.kidslauncher.mdm.ui.kidsettings.KidSettingsActivity], the launcher's
 * swipe-left-from-home replacement for Android's native Quick Settings shade. Must match the
 * server's `QUICK_CONTROL_*` constants in `handlers/devices.rs`.
 */
object QuickControlFeature {
    const val WIFI: Long = 1
    const val BLUETOOTH: Long = 2
    const val BRIGHTNESS: Long = 4
}

/**
 * Direct, silent Device-Owner API calls backing the Quick Controls screen - deliberately not
 * Android's own Quick Settings tiles or panel intents (`Settings.Panel.ACTION_WIFI` etc.), since
 * this whole screen exists specifically to give a kid safe toggle access without depending on any
 * Android UI surface a third-party app could also publish into (see
 * [AppEnforcer.applyVpnRestrictions] for the concrete case - Tailscale's own Quick Settings tile
 * bypassing its managed-config restriction - that motivated this).
 */
object QuickControls {

    /** The adapter via [BluetoothManager] (`BluetoothAdapter.getDefaultAdapter()` is deprecated). */
    private fun bluetoothAdapter(context: Context): BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    /**
     * [WifiManager.setWifiEnabled] is deprecated for regular apps since Android 10, which always
     * fails it silently (returns false, does nothing) - but Device Owner apps are explicitly
     * exempted from that restriction, so this still works for us with zero prompts.
     */
    fun setWifiEnabled(context: Context, enabled: Boolean): Boolean {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiManager.setWifiEnabled(enabled)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set WiFi enabled=$enabled", e)
            false
        }
    }

    fun isWifiEnabled(context: Context): Boolean {
        return try {
            val wifiManager =
                context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiManager.isWifiEnabled
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to read WiFi enabled state", e)
            false
        }
    }

    /**
     * Same Device-Owner exemption as WiFi above, this time for the Android 13+ restriction on
     * [BluetoothAdapter.enable]/[BluetoothAdapter.disable]. Also silently self-grants
     * `BLUETOOTH_CONNECT` first (a normal runtime permission, not blocked from Device-Owner
     * self-granting the way `CAMERA` is on Android 12+) since that's required to call these on
     * API 31+, and a kid should never see a system permission dialog on this screen.
     */
    fun setBluetoothEnabled(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        enabled: Boolean,
    ): Boolean {
        return try {
            grantBluetoothConnectIfNeeded(context, dpm, admin)
            val adapter = bluetoothAdapter(context) ?: return false
            @Suppress("DEPRECATION", "MissingPermission")
            if (enabled) adapter.enable() else adapter.disable()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set Bluetooth enabled=$enabled", e)
            false
        }
    }

    @Suppress("MissingPermission")
    fun isBluetoothEnabled(context: Context): Boolean {
        return bluetoothAdapter(context)?.isEnabled == true
    }

    private fun grantBluetoothConnectIfNeeded(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        selfGrantPermission(context, dpm, admin, android.Manifest.permission.BLUETOOTH_CONNECT)
    }

    /**
     * Shared by every self-granted runtime permission this screen needs (BLUETOOTH_CONNECT above,
     * plus BLUETOOTH_SCAN and NEARBY_WIFI_DEVICES below) - Device Owner can silently flip a normal
     * runtime permission to granted with no dialog, the same mechanism used throughout this file.
     * `internal`, not `private`, so LocateCommands.kt can reuse it too - `private` on a member here
     * means private to this object, not merely file-scoped.
     *
     * Skips the DPM call entirely if already granted - confirmed live that re-issuing an
     * already-granted permission still triggers GrapheneOS/Android's "your organization allows
     * this app to access X" transparency notification every time, which turned into a real
     * every-2-minutes-forever annoyance for ACCESS_BACKGROUND_LOCATION specifically, since
     * [LocateCommands.currentLocation] (unlike the other self-grants here, which only run when a
     * kid opens the relevant Quick Controls screen) calls this on every single sync cycle.
     */
    internal fun selfGrantPermission(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        permission: String,
    ) {
        val alreadyGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            context,
            permission,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (alreadyGranted) return

        try {
            dpm.setPermissionGrantState(
                admin,
                context.packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to self-grant $permission", e)
        }
    }

    /**
     * Grants one of our own permissions by policy (POLICY_FIXED) unless the DPM already holds it
     * granted - unlike [selfGrantPermission], which skips anything `checkSelfPermission` reports
     * as granted, including a grant a role made (then a role hand-back revokes it and kills us,
     * QA 09 #6). See [shouldFixOwnPermission].
     */
    internal fun fixOwnPermission(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        permission: String,
    ) {
        try {
            val state = dpm.getPermissionGrantState(admin, context.packageName, permission)
            if (!shouldFixOwnPermission(state, selfGranted = true)) return
            dpm.setPermissionGrantState(admin, context.packageName, permission, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to fix own permission $permission", e)
        }
    }

    /**
     * [DevicePolicyManager.setSystemSetting] is a Device-Owner-only API restricted to a small
     * whitelist of settings keys - [Settings.System.SCREEN_BRIGHTNESS] is one of them, so this
     * works silently with no permission prompt (unlike a regular app, which needs the special
     * `WRITE_SETTINGS` app-op the user has to grant manually). Forces manual brightness mode
     * first, or auto-brightness would immediately override a slider drag.
     */
    fun setBrightness(dpm: DevicePolicyManager, admin: ComponentName, value: Int) {
        val clamped = value.coerceIn(0, 255)
        try {
            dpm.setSystemSetting(
                admin,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL.toString(),
            )
            dpm.setSystemSetting(admin, Settings.System.SCREEN_BRIGHTNESS, clamped.toString())
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set brightness=$clamped", e)
        }
    }

    fun currentBrightness(context: Context): Int {
        return try {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
        } catch (e: Exception) {
            128
        }
    }

    private fun wifiManager(context: Context): WifiManager =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * WifiManager's scan/connect/configured-network APIs all hard-require system-wide Location to
     * be on - confirmed live via `WifiService`'s own `SecurityException("Location mode is disabled
     * for the device")`, thrown regardless of any app permission held (including
     * NEARBY_WIFI_DEVICES with neverForLocation, which only covers the runtime-permission side of
     * this, not this separate unconditional platform check). [WifiNetworksActivity] uses these to
     * silently flip Location on for the duration of that screen only (via the dedicated
     * Device-Owner API, no Settings/dialog involved) and restore whatever it was on exit - not a
     * permanent change to the phone's Location setting.
     */
    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.applicationContext
            .getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        return lm.isLocationEnabled
    }

    fun setLocationEnabled(dpm: DevicePolicyManager, admin: ComponentName, enabled: Boolean) {
        try {
            dpm.setLocationEnabled(admin, enabled)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to set location enabled=$enabled", e)
        }
    }

    /**
     * `savedNetworkId` is non-null when this SSID is already in the on-device saved-network list
     * (from [WifiManager.getPrivilegedConfiguredNetworks]) - lets the UI show "Saved"/"Connected"
     * and skip the password prompt (reconnect via the existing saved config) instead of asking
     * for a password again on a network already joined once.
     */
    data class WifiNetworkInfo(
        val ssid: String,
        val secured: Boolean,
        val signalLevel: Int,
        val savedNetworkId: Int?,
        val isConnected: Boolean,
    )

    /**
     * Kicks off a scan and delivers results once, via [WifiManager.SCAN_RESULTS_AVAILABLE_ACTION]
     * - not a live/repeating callback. Self-grants `NEARBY_WIFI_DEVICES` first: on Android 13+
     * that's what [WifiManager.startScan]/[WifiManager.scanResults] need instead of the old
     * location-permission requirement, and Device Owner can flip it on with no dialog same as
     * every other permission in this file.
     */
    fun scanWifiNetworks(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        onResult: (List<WifiNetworkInfo>) -> Unit,
    ) {
        selfGrantPermission(context, dpm, admin, android.Manifest.permission.NEARBY_WIFI_DEVICES)
        val appContext = context.applicationContext
        val wm = wifiManager(appContext)
        val granted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.NEARBY_WIFI_DEVICES,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(LOG_TAG, "scanWifiNetworks: NEARBY_WIFI_DEVICES granted=$granted")

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                try {
                    appContext.unregisterReceiver(this)
                } catch (e: Exception) {
                    // Already unregistered - harmless.
                }
                onResult(collectWifiNetworks(wm))
            }
        }
        try {
            appContext.registerReceiver(
                receiver,
                IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                Context.RECEIVER_NOT_EXPORTED,
            )
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to register WiFi scan receiver", e)
        }

        val started = try {
            @Suppress("DEPRECATION")
            wm.startScan()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to start WiFi scan", e)
            false
        }
        Log.i(LOG_TAG, "scanWifiNetworks: startScan() returned $started")
        if (!started) {
            try {
                appContext.unregisterReceiver(receiver)
            } catch (e: Exception) {
                // Already unregistered - harmless.
            }
            onResult(collectWifiNetworks(wm))
        }
    }

    /**
     * [WifiManager.getConfiguredNetworks] is deprecated and returns an empty list for regular
     * apps targeting API 29+, but - like every other WiFi API in this file - still returns the
     * real list for Device Owner apps. There is no non-deprecated equivalent with the same scope
     * (the newer [WifiManager.getCallerConfiguredNetworks] only sees networks this app itself
     * added, not ones already saved before this feature existed or added via Settings).
     */
    @Suppress("DEPRECATION")
    private fun collectWifiNetworks(wm: WifiManager): List<WifiNetworkInfo> {
        val saved = try {
            wm.configuredNetworks ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        val savedIdBySsid = saved.associate { it.SSID?.trim('"').orEmpty() to it.networkId }
        val currentSsid = saved
            .firstOrNull { it.status == android.net.wifi.WifiConfiguration.Status.CURRENT }
            ?.SSID?.trim('"')

        val results = try {
            wm.scanResults ?: emptyList()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "getScanResults() threw", e)
            emptyList()
        }
        Log.i(
            LOG_TAG,
            "collectWifiNetworks: raw scanResults=${results.size} " +
                "(ssids=${results.take(5).map { it.SSID }}), configuredNetworks=${saved.size}",
        )

        return results
            .mapNotNull { result -> result.SSID?.takeIf { it.isNotBlank() }?.let { it to result } }
            .distinctBy { it.first }
            .map { (ssid, result) ->
                WifiNetworkInfo(
                    ssid = ssid,
                    secured = listOf("WEP", "PSK", "EAP", "SAE").any { result.capabilities.contains(it) },
                    signalLevel = wm.calculateSignalLevel(result.level),
                    savedNetworkId = savedIdBySsid[ssid],
                    isConnected = ssid == currentSsid,
                )
            }
            .sortedWith(
                compareByDescending<WifiNetworkInfo> { it.isConnected }
                    .thenByDescending { it.signalLevel },
            )
    }

    /**
     * Connects to a network, saving it first if it isn't already ([savedNetworkId] null).
     * [WifiManager.addNetworkPrivileged] (API 30+) is Device-Owner-privileged the same way
     * [setWifiEnabled] is - the same tier of access Settings itself uses. Sets both WPA2 and WPA3
     * key-management bits when a password is given (standard "transition mode" pattern) rather
     * than trying to precisely tell WPA2 from WPA3 out of the scan result's capabilities string.
     *
     * `WifiConfiguration` is deprecated as a whole since API 36 (in favour of network suggestions
     * and specifiers, which can't save a system network for every app), but
     * [WifiManager.addNetworkPrivileged] - the device-owner API this needs, not deprecated - still
     * takes one, and [WifiManager.enableNetwork]/[WifiManager.removeNetwork] work on its ids. So
     * the deprecation is suppressed here only, with the config built exactly as before.
     *
     * There is no public callback-based connect API on this platform version (the documented
     * `WifiManager.connect(int, ActionListener)` is a hidden/system-only overload, not present in
     * the public SDK this app compiles against) - [WifiManager.enableNetwork] with
     * `disableOthers = true` is the real public mechanism, and it only reports whether the
     * request itself was accepted, not whether the device actually associates. The caller is
     * expected to poll [scanWifiNetworks] afterwards and check [WifiNetworkInfo.isConnected] to
     * learn the real outcome (see `WifiNetworksActivity.pollForConnection`) - a wrong password
     * shows up as a silent connect/retry loop rather than a clean failure either way.
     */
    @Suppress("DEPRECATION")
    fun connectToWifiNetwork(
        context: Context,
        ssid: String,
        password: String?,
        secured: Boolean,
        savedNetworkId: Int?,
    ): Boolean {
        val wm = wifiManager(context)
        return try {
            val networkId = savedNetworkId ?: run {
                val config = android.net.wifi.WifiConfiguration().apply {
                    SSID = "\"$ssid\""
                    if (secured && !password.isNullOrEmpty()) {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.WPA_PSK)
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.SAE)
                        preSharedKey = "\"$password\""
                    } else {
                        allowedKeyManagement.set(android.net.wifi.WifiConfiguration.KeyMgmt.NONE)
                    }
                }
                val result = wm.addNetworkPrivileged(config)
                if (result.statusCode != WifiManager.AddNetworkResult.STATUS_SUCCESS) {
                    Log.w(LOG_TAG, "addNetworkPrivileged failed for $ssid: ${result.statusCode}")
                    return false
                }
                result.networkId
            }
            wm.enableNetwork(networkId, true)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to connect to WiFi network $ssid", e)
            false
        }
    }

    /** [WifiManager.removeNetwork] - deprecated with `WifiConfiguration`, see [connectToWifiNetwork]. */
    @Suppress("DEPRECATION")
    fun forgetWifiNetwork(context: Context, networkId: Int): Boolean {
        return try {
            wifiManager(context).removeNetwork(networkId)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to forget WiFi network id=$networkId", e)
            false
        }
    }

    data class BluetoothDeviceInfo(
        val name: String,
        val address: String,
        val bonded: Boolean,
        val connected: Boolean,
    )

    /**
     * [BluetoothDevice.isConnected] has no public equivalent, same as [connectBluetoothDevice]
     * below - reflects into the hidden method, fails soft to `false` on any error.
     */
    private fun BluetoothDevice.isConnectedReflective(): Boolean {
        return try {
            javaClass.getMethod("isConnected").invoke(this) as? Boolean ?: false
        } catch (e: Exception) {
            false
        }
    }

    @Suppress("MissingPermission")
    private fun BluetoothDevice.toInfo(): BluetoothDeviceInfo {
        val deviceName = try {
            name
        } catch (e: Exception) {
            null
        } ?: address
        val isCurrentlyConnected = isConnectedReflective()
        return BluetoothDeviceInfo(
            name = deviceName,
            address = address,
            bonded = bondState == BluetoothDevice.BOND_BONDED,
            connected = isCurrentlyConnected,
        )
    }

    @Suppress("MissingPermission")
    fun bondedBluetoothDevices(context: Context): List<BluetoothDeviceInfo> {
        val adapter = bluetoothAdapter(context) ?: return emptyList()
        return try {
            adapter.bondedDevices.map { it.toInfo() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * Re-reads a single already-known device's bonded/connected state (e.g. after
     * `ACTION_BOND_STATE_CHANGED`) without needing a full rescan.
     */
    @Suppress("MissingPermission")
    fun bluetoothDeviceInfo(context: Context, address: String): BluetoothDeviceInfo? {
        val adapter = bluetoothAdapter(context) ?: return null
        return try {
            adapter.getRemoteDevice(address).toInfo()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Starts discovery and streams newly-found devices to [onDeviceFound] as they're seen
     * (Android's own discovery mechanism is inherently incremental, unlike the one-shot WiFi scan
     * above), then calls [onFinished] once discovery naturally ends (~12s). Returns the receiver
     * so the caller can unregister it in `onStop`/`onDestroy` via [stopBluetoothScan] - unlike the
     * WiFi scan helper, this one can't self-unregister after a single event.
     */
    @Suppress("MissingPermission")
    fun scanBluetoothDevices(
        context: Context,
        dpm: DevicePolicyManager,
        admin: ComponentName,
        onDeviceFound: (BluetoothDeviceInfo) -> Unit,
        onFinished: () -> Unit,
    ): BroadcastReceiver? {
        selfGrantPermission(context, dpm, admin, android.Manifest.permission.BLUETOOTH_SCAN)
        grantBluetoothConnectIfNeeded(context, dpm, admin)
        val adapter = bluetoothAdapter(context)
        if (adapter == null) {
            Log.w(LOG_TAG, "scanBluetoothDevices: no default BluetoothAdapter")
            return null
        }
        val appContext = context.applicationContext
        val scanGranted = androidx.core.content.ContextCompat.checkSelfPermission(
            appContext,
            android.Manifest.permission.BLUETOOTH_SCAN,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        Log.i(
            LOG_TAG,
            "scanBluetoothDevices: BLUETOOTH_SCAN granted=$scanGranted, adapter.isEnabled=${adapter.isEnabled}",
        )

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device = intent.getParcelableExtra(
                            BluetoothDevice.EXTRA_DEVICE,
                            BluetoothDevice::class.java,
                        )
                        device?.let { onDeviceFound(it.toInfo()) }
                    }

                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> onFinished()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        try {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to register Bluetooth discovery receiver", e)
            return null
        }
        val discoveryStarted = try {
            adapter.startDiscovery()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to start Bluetooth discovery", e)
            false
        }
        Log.i(LOG_TAG, "scanBluetoothDevices: startDiscovery() returned $discoveryStarted")
        return receiver
    }

    fun stopBluetoothScan(context: Context, receiver: BroadcastReceiver?) {
        receiver?.let {
            try {
                context.applicationContext.unregisterReceiver(it)
            } catch (e: Exception) {
                // Already unregistered - harmless.
            }
        }
        try {
            @Suppress("MissingPermission")
            bluetoothAdapter(context)?.cancelDiscovery()
        } catch (e: Exception) {
            // Ignore - best-effort cleanup.
        }
    }

    @Suppress("MissingPermission")
    fun pairBluetoothDevice(context: Context, address: String): Boolean {
        val adapter = bluetoothAdapter(context) ?: return false
        return try {
            adapter.getRemoteDevice(address).createBond()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to pair with $address", e)
            false
        }
    }

    /**
     * [BluetoothDevice.removeBond] has no public equivalent - there is simply no other way to
     * unpair a device from app code, so this reflects into the hidden method, a long-standing,
     * widely-used pattern for exactly this gap. Fails soft (logs, returns false) rather than
     * crashing if some OS build ever removes/renames it.
     */
    @Suppress("MissingPermission")
    fun forgetBluetoothDevice(context: Context, address: String): Boolean {
        val adapter = bluetoothAdapter(context) ?: return false
        return try {
            val device = adapter.getRemoteDevice(address)
            val method = device.javaClass.getMethod("removeBond")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to forget Bluetooth device $address (hidden removeBond API)", e)
            false
        }
    }

    /**
     * [BluetoothDevice.connect]/[disconnect] are hidden methods with no public replacement -
     * Android's own Settings app calls these same methods internally (via reflection/internal
     * access) to drive a paired device's "Connect"/"Disconnect" action generically across
     * whichever profiles it supports, rather than wiring up every [android.bluetooth.BluetoothProfile]
     * (A2DP, headset, ...) individually. Same fail-soft handling as [forgetBluetoothDevice] - if
     * this breaks on some OS build, pairing/scanning/forgetting are unaffected.
     */
    @Suppress("MissingPermission")
    fun connectBluetoothDevice(context: Context, address: String): Boolean {
        val adapter = bluetoothAdapter(context) ?: return false
        return try {
            val device = adapter.getRemoteDevice(address)
            val method = device.javaClass.getMethod("connect")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to connect Bluetooth device $address (hidden connect API)", e)
            false
        }
    }

    @Suppress("MissingPermission")
    fun disconnectBluetoothDevice(context: Context, address: String): Boolean {
        val adapter = bluetoothAdapter(context) ?: return false
        return try {
            val device = adapter.getRemoteDevice(address)
            val method = device.javaClass.getMethod("disconnect")
            method.invoke(device) as? Boolean ?: false
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Failed to disconnect Bluetooth device $address (hidden disconnect API)", e)
            false
        }
    }
}
