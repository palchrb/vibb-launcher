package com.kidslauncher.mdm.places

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Debug build only (src/debug, design 131 §7; `checkReleaseHasNoDebugHook`, `DebugHookAbsentTest`): the emulator hears
 * no other Wi-Fi network and its own is open, so the gate hands the Wi-Fi places a made-up scan and connection.
 *
 * `adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.places.PlacesDebugReceiver
 * --es aps "00:00:5e:00:53:01,Hjemme,5180,-50;00:00:5e:00:53:02,Nabo,2437,-70"
 * [--es connect "00:00:5e:00:53:01,Hjemme,psk,7,5180"]` - each AP `bssid,ssid,MHz,dBm`, the connection
 * `bssid,ssid,psk|sae|eap|open,networkId,MHz` (unmetered); `--ez clear true` clears both. Every scan, attempt and teach
 * reads them (heard now) until cleared or the process restarts. Exported for the shell, guarded by DUMP.
 */
class PlacesDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("clear", false)) {
            WifiPlaces.debugResults = null
            WifiPlaces.debugConnected = null
            Log.i(TAG, "made-up scan and connection cleared")
            resultData = "cleared"
            return
        }
        intent.getStringExtra("aps")?.let { aps ->
            WifiPlaces.debugResults = aps.split(';').mapNotNull { ap ->
                val p = ap.split(',').map { it.trim() }
                if (p.size < 4) return@mapNotNull null
                ScanAp(p[0], p[1].ifEmpty { null }, null, p[2].toIntOrNull() ?: return@mapNotNull null, p[3].toIntOrNull() ?: return@mapNotNull null, 0L)
            }
        }
        intent.getStringExtra("connect")?.let { c ->
            val p = c.split(',').map { it.trim() }
            WifiPlaces.debugConnected = if (p.size < 5) {
                null
            } else {
                val security = when (p[2]) {
                    "psk" -> LinkSecurity.PSK
                    "sae" -> LinkSecurity.SAE
                    "eap" -> LinkSecurity.EAP
                    else -> LinkSecurity.OTHER
                }
                Connected(p[0], p[1].ifEmpty { null }, -45, p[4].toIntOrNull() ?: 2437, true, security, p[3].toIntOrNull() ?: -1)
            }
        }
        val line = "made-up scan aps=${WifiPlaces.debugResults?.size ?: 0} connected=${WifiPlaces.debugConnected?.security ?: "none"}"
        Log.i(TAG, line)
        resultData = line
    }

    private companion object {
        const val TAG = "PlacesDebug"
    }
}
