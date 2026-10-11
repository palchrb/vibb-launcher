package me.vibb.music.sonos

import android.content.Context
import android.net.Network
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.net.DatagramSocket

/*
 * The Android side of an SSDP round (design 26a §2.1, §6.5): Android drops multicast replies unless
 * a WifiManager.MulticastLock is held (CHANGE_WIFI_MULTICAST_STATE), so one is held for the round
 * only and released in `finally`; the socket is bound to the Wi-Fi [Network] (QA M7). The round
 * itself is Ssdp.scan (pure, SsdpTest). Blocking: call it off the main thread.
 */
class SsdpScanner(private val context: Context) {
    /** The speakers answering on [wifi] within [Ssdp.LISTEN_MS]; empty on any socket failure. */
    fun scan(wifi: Network): List<SsdpHit> {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        val lock = wm.createMulticastLock(TAG).apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            val socket = DatagramSocket()
            return DatagramSsdpIo(socket).use { io ->
                wifi.bindSocket(socket)
                Ssdp.scan(io, SystemClock::elapsedRealtime)
            }.also { Log.i(TAG, "ssdp: ${it.size} speaker(s) answered") }
        } catch (e: Exception) {
            Log.w(TAG, "ssdp round failed (${e.javaClass.simpleName})")
            return emptyList()
        } finally {
            lock.release()
        }
    }

    private companion object {
        const val TAG = "VibbSonos"
    }
}
