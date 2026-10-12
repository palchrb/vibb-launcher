package me.vibb.music.sonos

import android.content.Context
import android.net.wifi.WifiManager
import android.os.SystemClock
import android.util.Log
import java.net.DatagramSocket

/*
 * The Android side of an SSDP round (design 26a §2.1, §6.5): Android drops multicast replies unless
 * a WifiManager.MulticastLock is held (CHANGE_WIFI_MULTICAST_STATE), so one is held for the round
 * only and released in `finally`. The caller runs it only on Wi-Fi; the socket is NOT bound to the
 * Wi-Fi `Network` (§6.5: the launcher's always-on VPN allows no bypass, so the bind is EPERM, and
 * the M-SEARCH to 239.255.255.250 leaves on the default route because the VPN routes only
 * 192.0.2.2/32). The round itself is Ssdp.scan (pure, SsdpTest). Blocking: call it off the main thread.
 */
class SsdpScanner(private val context: Context) {
    /** The speakers answering within [Ssdp.LISTEN_MS]; empty on any socket failure. */
    fun scan(): List<SsdpHit> {
        val wm = context.applicationContext.getSystemService(WifiManager::class.java) ?: return emptyList()
        val lock = wm.createMulticastLock(TAG).apply { setReferenceCounted(false) }
        lock.acquire()
        try {
            val socket = DatagramSocket()
            return DatagramSsdpIo(socket).use { io ->
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
