package com.kidslauncher.mdm.server

import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.lang.reflect.InvocationTargetException
import kotlin.coroutines.resume

/*
 * Bluetooth headphones (design 52): connect a paired device through the A2DP proxy. The pure rules
 * (which device is audio, its state, what a tap does) and the glue (the A2DP and HEADSET proxies,
 * awaited off the main thread; connect, disconnect and setActiveDevice by reflection). The music app
 * has its own copy of the rules (not byte-identical: this one also classifies non-audio devices).
 *
 * Names and addresses never leave memory: no log line here interpolates a device value, and a
 * failure logs only the exception's class and its cause's class (design 52 decision 4).
 */

/** A2DP sink: a device that plays music over classic Bluetooth. */
const val A2DP_SINK_UUID = "0000110b-0000-1000-8000-00805f9b34fb"

/** LE Audio's ASCS (`BluetoothUuid.LE_AUDIO` is not public). */
const val LE_AUDIO_ASCS_UUID = "0000184e-0000-1000-8000-00805f9b34fb"

/** What a bonded device is, for Kid Settings' row: [A2DP] can be connected through the proxy; an
 * [LE_ONLY] headset can't (`BluetoothLeAudio.connect` is blocked), so it is shown like [OTHER]. */
enum class BtKind { A2DP, LE_ONLY, OTHER }

enum class BtLink { OFF, CONNECTING, CONNECTED }

/** What a "connect" tap on an audio device does: [MAKE_ACTIVE] for one A2DP holds but doesn't play
 * to (several sinks may be connected, only the active one is routed), [NONE] when it is routed. */
enum class BtTap { CONNECT, MAKE_ACTIVE, NONE }

fun btKind(uuids: Collection<String>): BtKind {
    val lower = uuids.map { it.lowercase() }
    return when {
        A2DP_SINK_UUID in lower -> BtKind.A2DP
        LE_AUDIO_ASCS_UUID in lower -> BtKind.LE_ONLY
        else -> BtKind.OTHER
    }
}

fun hasLeAudio(uuids: Collection<String>): Boolean = uuids.any { it.equals(LE_AUDIO_ASCS_UUID, ignoreCase = true) }

/**
 * A device's state. An A2DP device's comes from the A2DP proxy ([a2dpState] its
 * `getConnectionState`; null = no proxy, then the ACL link stands in); one that also speaks LE Audio
 * counts as connected on the ACL link too. Everything else is the ACL link ([aclConnected],
 * `BluetoothDevice.isConnected`), so a watch or a keyboard still reads connected.
 */
fun btLink(kind: BtKind, leAudio: Boolean, a2dpState: Int?, aclConnected: Boolean): BtLink {
    val acl = if (aclConnected) BtLink.CONNECTED else BtLink.OFF
    if (kind != BtKind.A2DP || a2dpState == null) return acl
    return when {
        a2dpState == BluetoothProfile.STATE_CONNECTED -> BtLink.CONNECTED
        leAudio && aclConnected -> BtLink.CONNECTED
        a2dpState == BluetoothProfile.STATE_CONNECTING -> BtLink.CONNECTING
        else -> BtLink.OFF
    }
}

/** Whether [address] is among the routed outputs' addresses (`AudioDeviceInfo.getAddress`, the real
 * MAC with `BLUETOOTH_CONNECT`; case is not significant). */
fun isRouted(address: String, routedAddresses: Collection<String>): Boolean =
    routedAddresses.any { it.equals(address, ignoreCase = true) }

fun btTap(a2dpState: Int?, routed: Boolean): BtTap = when {
    routed -> BtTap.NONE
    a2dpState == BluetoothProfile.STATE_CONNECTED -> BtTap.MAKE_ACTIVE
    else -> BtTap.CONNECT
}

/** One event of a connect attempt (Kid Settings' "Kobler til…", design 52 decision 2). */
sealed interface BtAttemptEvent {
    /** The call's answer: `false` = not asked or refused. */
    data class Called(val asked: Boolean) : BtAttemptEvent

    /** A re-read of the device's state. */
    data class Read(val link: BtLink) : BtAttemptEvent

    /** `BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED` for the device. */
    data class A2dp(val previous: Int, val state: Int) : BtAttemptEvent

    data object Timeout : BtAttemptEvent
}

enum class BtAttemptEnd { SUCCESS, FAILURE }

/**
 * How an event ends an attempt, or null (still "Kobler til…"). Connected is a success whenever it
 * comes (a late one clears the failure line); a refused call, CONNECTING -> DISCONNECTED or the
 * 10 s timeout is a failure.
 */
fun btAttemptStep(event: BtAttemptEvent): BtAttemptEnd? = when (event) {
    is BtAttemptEvent.Called -> if (event.asked) null else BtAttemptEnd.FAILURE
    is BtAttemptEvent.Read -> if (event.link == BtLink.CONNECTED) BtAttemptEnd.SUCCESS else null
    is BtAttemptEvent.A2dp -> when {
        event.state == BluetoothProfile.STATE_CONNECTED -> BtAttemptEnd.SUCCESS
        event.previous == BluetoothProfile.STATE_CONNECTING &&
            event.state == BluetoothProfile.STATE_DISCONNECTED -> BtAttemptEnd.FAILURE
        else -> null
    }
    BtAttemptEvent.Timeout -> BtAttemptEnd.FAILURE
}

const val BT_ATTEMPT_TIMEOUT_MS = 10_000L

object BluetoothAudio {
    private const val TAG = "BluetoothAudio"
    private const val PROXY_WAIT_MS = 2_000L

    /** An exception as its class and its cause's class - never its message, which may name the device. */
    internal fun failure(e: Throwable): String {
        val t = (e as? InvocationTargetException)?.targetException ?: e
        val cause = t.cause?.javaClass?.simpleName
        return t.javaClass.simpleName + (cause?.let { " (cause $it)" } ?: "")
    }

    private fun adapter(context: Context): BluetoothAdapter? =
        context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    /** The A2DP and HEADSET proxies of one screen. Either may be null (Bluetooth off, the 2 s wait over). */
    class Proxies internal constructor(
        private val adapter: BluetoothAdapter,
        val a2dp: BluetoothProfile?,
        val headset: BluetoothProfile?,
    ) {
        fun close() {
            try {
                a2dp?.let { adapter.closeProfileProxy(BluetoothProfile.A2DP, it) }
                headset?.let { adapter.closeProfileProxy(BluetoothProfile.HEADSET, it) }
            } catch (e: Exception) {
                Log.w(TAG, "Closing the proxies failed: ${failure(e)}")
            }
        }
    }

    /**
     * One screen's proxies: opened on the first call after the screen started, closed by [close] when
     * it stops (an open still in flight is closed when it lands). A failed open (Bluetooth off, the
     * wait over) is tried again on the next call.
     */
    class Session(context: Context) {
        private val app = context.applicationContext
        private val opening = Mutex()
        private val lock = Any()
        private var open: Proxies? = null
        private var closed = false

        suspend fun proxies(): Proxies? = opening.withLock {
            synchronized(lock) {
                if (closed) return@withLock null
                open?.let { return@withLock it }
            }
            val fresh = openProxies(app) ?: return@withLock null
            val keep = synchronized(lock) { (!closed).also { if (it) open = fresh } }
            if (!keep) fresh.close()
            if (keep) fresh else null
        }

        fun close() {
            val p = synchronized(lock) {
                closed = true
                open.also { open = null }
            }
            p?.close()
        }
    }

    private suspend fun openProxies(context: Context): Proxies? = withContext(Dispatchers.IO) {
        val adapter = adapter(context) ?: return@withContext null
        if (!adapter.isEnabled) return@withContext null
        val a2dp = awaitProxy(context, adapter, BluetoothProfile.A2DP)
        val headset = awaitProxy(context, adapter, BluetoothProfile.HEADSET)
        if (a2dp == null && headset == null) null else Proxies(adapter, a2dp, headset)
    }

    /**
     * `getProfileProxy` hands the proxy over on the main thread's handler, so it is awaited by
     * suspending, never by blocking a thread. The callback may come again after Bluetooth restarts
     * (ignored), or after the wait gave up (then the proxy is closed).
     */
    private suspend fun awaitProxy(context: Context, adapter: BluetoothAdapter, profile: Int): BluetoothProfile? {
        val lock = Any()
        var state = WAITING
        var delivered: BluetoothProfile? = null
        var result: BluetoothProfile? = null
        try {
            result = withTimeoutOrNull(PROXY_WAIT_MS) {
                suspendCancellableCoroutine<BluetoothProfile?> { cont ->
                    val listener = object : BluetoothProfile.ServiceListener {
                        override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                            val action = synchronized(lock) {
                                when (state) {
                                    WAITING -> { state = DELIVERED; delivered = proxy; DELIVERED }
                                    GAVE_UP -> { state = CLOSED; CLOSED }
                                    else -> IGNORED
                                }
                            }
                            when (action) {
                                DELIVERED -> cont.resume(proxy)
                                CLOSED -> adapter.closeProfileProxy(p, proxy)
                            }
                        }

                        override fun onServiceDisconnected(p: Int) = Unit
                    }
                    val asked = try {
                        adapter.getProfileProxy(context.applicationContext, listener, profile)
                    } catch (e: Exception) {
                        Log.w(TAG, "getProfileProxy($profile) failed: ${failure(e)}")
                        false
                    }
                    if (!asked) cont.resume(null)
                }
            }
            return result
        } finally {
            if (result == null) {
                val late = synchronized(lock) {
                    if (state == DELIVERED) delivered.also { state = CLOSED } else { state = GAVE_UP; null }
                }
                late?.let { adapter.closeProfileProxy(profile, it) }
            }
        }
    }

    private const val WAITING = 0
    private const val DELIVERED = 1
    private const val GAVE_UP = 2
    private const val CLOSED = 3
    private const val IGNORED = 4

    /** The addresses of the Bluetooth outputs media is routed to now. */
    fun routedAddresses(context: Context): Set<String> = try {
        context.getSystemService(AudioManager::class.java)
            ?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            ?.filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
            ?.map { it.address }
            ?.toSet()
            .orEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "Reading the routed outputs failed: ${failure(e)}")
        emptySet()
    }

    /** The device's A2DP state from the proxy, or null without one. */
    @Suppress("MissingPermission")
    fun a2dpState(proxies: Proxies?, device: BluetoothDevice): Int? = try {
        proxies?.a2dp?.getConnectionState(device)
    } catch (e: Exception) {
        Log.w(TAG, "Reading the A2DP state failed: ${failure(e)}")
        null
    }

    /**
     * Connects a paired audio device (or makes a connected one active, [btTap]). The answer is only
     * "asked": the A2DP broadcast and the routed output are the truth. `false` = not asked or refused.
     */
    suspend fun connect(context: Context, session: Session, address: String): Boolean {
        val proxies = session.proxies() ?: return false
        val a2dp = proxies.a2dp ?: return false
        return withContext(Dispatchers.IO) {
            val device = device(context, address) ?: return@withContext false
            val tap = btTap(a2dpState(proxies, device), isRouted(address, routedAddresses(context)))
            when (tap) {
                BtTap.NONE -> true
                BtTap.MAKE_ACTIVE -> call(BluetoothA2dp::class.java.getMethod("setActiveDevice", BluetoothDevice::class.java), a2dp, device, "A2DP setActiveDevice")
                BtTap.CONNECT -> call(BluetoothA2dp::class.java.getMethod("connect", BluetoothDevice::class.java), a2dp, device, "A2DP connect")
            }
        }
    }

    /**
     * Disconnects the whole headset: A2DP and HFP, so a call doesn't ring in it either. HFP's
     * `false` (no HFP, or not connected) is no failure; A2DP's answer is the result.
     */
    suspend fun disconnect(context: Context, session: Session, address: String): Boolean {
        val proxies = session.proxies() ?: return false
        return withContext(Dispatchers.IO) {
            val device = device(context, address) ?: return@withContext false
            proxies.headset?.let {
                call(BluetoothHeadset::class.java.getMethod("disconnect", BluetoothDevice::class.java), it, device, "HFP disconnect")
            }
            val a2dp = proxies.a2dp ?: return@withContext false
            call(BluetoothA2dp::class.java.getMethod("disconnect", BluetoothDevice::class.java), a2dp, device, "A2DP disconnect")
        }
    }

    private fun device(context: Context, address: String): BluetoothDevice? = try {
        adapter(context)?.getRemoteDevice(address)
    } catch (e: Exception) {
        Log.w(TAG, "No such device: ${failure(e)}")
        null
    }

    private fun call(method: java.lang.reflect.Method, proxy: BluetoothProfile, device: BluetoothDevice, what: String): Boolean = try {
        method.invoke(proxy, device) as? Boolean ?: false
    } catch (e: Exception) {
        Log.w(TAG, "$what failed: ${failure(e)}")
        false
    }
}
