package me.vibb.music.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The music app's Bluetooth glue (design 52 decision 1): `suspend`, every Bluetooth call on [Dispatchers.IO], the A2DP
 * proxy awaited at most [BluetoothAudio.PROXY_WAIT_MS] without blocking a thread (its ready callback comes through the
 * main handler). Connect and make-active are `BluetoothA2dp.connect` / `setActiveDevice` by reflection (hidden,
 * `@UnsupportedAppUsage` without `maxTargetSdk`, `BLUETOOTH_CONNECT` only - §0); a failure is `false` and is logged as
 * the thrown class and its cause's class, never a device, a name or an address.
 *
 * Without `BLUETOOTH_CONNECT` (the launcher grants it while a policy exists) nothing here is called: the sheet is
 * today's. The music app never scans, pairs, turns Bluetooth on or opens a system output switcher (decision 5); it
 * opens no HEADSET proxy - it never disconnects (no "Koble fra" in the sheet), and connecting needs no HFP step.
 */
@SuppressLint("MissingPermission") // every call is behind granted(); a revoked grant's SecurityException is caught
class BluetoothLink(context: Context) {
    private val app = context.applicationContext
    private val audio: AudioManager? = app.getSystemService(AudioManager::class.java)

    private val adapter: BluetoothAdapter?
        get() = app.getSystemService(BluetoothManager::class.java)?.adapter

    /** `BLUETOOTH_CONNECT` is granted (by the launcher, never asked of the kid). */
    fun granted(): Boolean = app.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /** A proxy wait's outcome, shared with the late callback (guarded by its own lock). */
    private class Wait {
        var abandoned = false
        var proxy: BluetoothProfile? = null
    }

    /**
     * The A2DP proxy, or null (not granted, no adapter, Bluetooth off, or not ready within the wait). One per view
     * model while the sheet is open or a call runs (the caller holds it and [closeLater]s it). A proxy that arrives after
     * the wait gave up - by the timeout or by the caller's cancellation (a view model cleared mid-wait) - is closed.
     */
    suspend fun openA2dp(): BluetoothA2dp? {
        if (!granted()) return null
        val adapter = adapter ?: return null
        val wait = Wait()
        var got: BluetoothA2dp? = null
        try {
            got = withContext(Dispatchers.IO) {
                // Bluetooth off: no proxy would come (and no bonded device is listed) - don't wait for it
                if (!adapter.isEnabled) null else withTimeoutOrNull(BluetoothAudio.PROXY_WAIT_MS) { awaitA2dp(adapter, wait) }
            }
            return got
        } finally {
            if (got == null) {
                // Timed out, or cancelled (a value the callback gave may have been dropped by the cancellation): close
                // what came, and mark the wait abandoned so a later callback closes its proxy itself.
                val stray = synchronized(wait) {
                    wait.abandoned = true
                    wait.proxy.also { wait.proxy = null }
                }
                stray?.let { closeQuietly(adapter, it) }
            }
        }
    }

    /** Asks for the proxy and suspends until its callback (main handler) or a refusal; never blocks a thread. */
    private suspend fun awaitA2dp(adapter: BluetoothAdapter, wait: Wait): BluetoothA2dp? =
        suspendCancellableCoroutine { cont ->
            val listener = object : BluetoothProfile.ServiceListener {
                override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                    val late = synchronized(wait) {
                        if (!wait.abandoned) wait.proxy = proxy
                        wait.abandoned
                    }
                    if (late) closeQuietly(adapter, proxy) else if (cont.isActive) cont.resume(proxy as? BluetoothA2dp)
                }

                override fun onServiceDisconnected(profile: Int) = Unit
            }
            val asked = try {
                adapter.getProfileProxy(app, listener, BluetoothProfile.A2DP)
            } catch (e: Exception) {
                Log.w(TAG, BluetoothAudio.failureWords("proxy", e))
                false
            }
            if (!asked && cont.isActive) cont.resume(null)
        }

    /** Closes the proxy on IO, without waiting (also from `onCleared`, whose scope is gone). */
    fun closeLater(a2dp: BluetoothA2dp) {
        val adapter = adapter ?: return
        closer.launch { closeQuietly(adapter, a2dp) }
    }

    private fun closeQuietly(adapter: BluetoothAdapter, proxy: BluetoothProfile) {
        try {
            adapter.closeProfileProxy(BluetoothProfile.A2DP, proxy)
        } catch (e: Exception) {
            Log.w(TAG, BluetoothAudio.failureWords("close", e))
        }
    }

    /**
     * The routed Bluetooth outputs now: their address (upper case; the real MAC with `BLUETOOTH_CONNECT`) ->
     * `AudioDeviceInfo.id` (the `dev:` key). Only the active A2DP sink is an output (§0).
     */
    suspend fun routed(): Map<String, Int> = withContext(Dispatchers.IO) {
        try {
            audio?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
                .filter { it.type in BLUETOOTH_OUTPUTS && !it.address.isNullOrBlank() }
                .associate { BluetoothAudio.key(it.address) to it.id }
        } catch (e: Exception) {
            Log.w(TAG, BluetoothAudio.failureWords("routed", e))
            emptyMap()
        }
    }

    /**
     * The paired audio devices not routed now ([routedAddresses]), with their A2DP state (decision 1). Bluetooth off:
     * no bonded devices, so nothing.
     */
    suspend fun pairedAudio(a2dp: BluetoothA2dp, routedAddresses: Set<String>): List<PairedAudio> = withContext(Dispatchers.IO) {
        try {
            val adapter = adapter
            if (adapter == null || !granted() || !adapter.isEnabled) {
                emptyList()
            } else {
                val devices = adapter.bondedDevices.orEmpty().toList()
                val bonded = devices.map { d -> Bonded(d.address, d.alias ?: d.name, d.uuids?.map { it.toString() }.orEmpty()) }
                val states = devices.filter { BluetoothAudio.isAudio(it.uuids?.map { u -> u.toString() }.orEmpty()) }
                    .associate { BluetoothAudio.key(it.address) to a2dp.getConnectionState(it) }
                BluetoothAudio.pairedAudioDevices(bonded, states, routedAddresses)
            }
        } catch (e: Exception) {
            Log.w(TAG, BluetoothAudio.failureWords("read", e))
            emptyList()
        }
    }

    /** "Koble til": `BluetoothA2dp.connect` - true = asked (the broadcast and the routed output are the truth). */
    suspend fun connect(a2dp: BluetoothA2dp, address: String): Boolean = callA2dp(BluetoothAudio.A2DP_CONNECT, a2dp, address)

    /** "Bytt til": `BluetoothA2dp.setActiveDevice` for a connected device that is not routed. */
    suspend fun makeActive(a2dp: BluetoothA2dp, address: String): Boolean = callA2dp(BluetoothAudio.A2DP_SET_ACTIVE, a2dp, address)

    private suspend fun callA2dp(method: String, a2dp: BluetoothA2dp, address: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val device = adapter?.getRemoteDevice(address)
            if (device == null || !granted()) {
                false
            } else {
                BluetoothA2dp::class.java.getMethod(method, BluetoothDevice::class.java).invoke(a2dp, device) as? Boolean ?: false
            }
        } catch (e: Exception) {
            Log.w(TAG, BluetoothAudio.failureWords(method, e))
            false
        }
    }

    companion object {
        const val TAG = "VibbBluetooth"

        /** Where proxies are closed: outlives a view model's scope. */
        private val closer = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /** The output types a Bluetooth headset is routed as (as `PlaybackService.outputKind`). */
        val BLUETOOTH_OUTPUTS = setOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET, AudioDeviceInfo.TYPE_BLE_SPEAKER)
    }
}
