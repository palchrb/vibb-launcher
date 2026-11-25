package com.kidslauncher.mdm.places

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.location.provider.ProviderProperties
import android.os.Bundle
import android.os.SystemClock
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
 *
 * Satellite fixes (the 131 gate, 2026-11-25): the emulator's GNSS marks no satellite as used in a fix (`satellites=0`
 * in the extras of every `geo fix`, whatever its count), so none of its fixes teaches. `--es gps "lat,lng,accuracy,
 * satellites[,seconds]"` makes `gps` a test provider (after `adb shell appops set <package> android:mock_location
 * allow`) and feeds it that fix once a second for the seconds given (20), the satellites in its extras; a new `gps`
 * ends the running feed, and with no feed running the provider is silent. `--ez gps_off true` removes the provider.
 */
class PlacesDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.getBooleanExtra("gps_off", false)) {
            resultData = gpsFeed(context, null)
            return
        }
        intent.getStringExtra("gps")?.let { g ->
            resultData = gpsFeed(context, g.split(',').mapNotNull { it.trim().toDoubleOrNull() })
            return
        }
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

        /** The running feed's number: a new command ends the one before. */
        @Volatile var feed = 0

        /** [p] `lat, lng, accuracy, satellites[, seconds]`, or `null` to remove the test provider. */
        fun gpsFeed(context: Context, p: List<Double>?): String {
            val lm = context.getSystemService(LocationManager::class.java) ?: return "no location service"
            val mine = ++feed
            if (p == null) {
                runCatching { lm.removeTestProvider(LocationManager.GPS_PROVIDER) }
                Log.i(TAG, "gps test provider removed")
                return "gps test provider removed"
            }
            if (p.size < 4) return "gps needs lat,lng,accuracy,satellites[,seconds]"
            try {
                val props = ProviderProperties.Builder()
                    .setHasSatelliteRequirement(true)
                    .setHasAltitudeSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_HIGH)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                runCatching { lm.addTestProvider(LocationManager.GPS_PROVIDER, props) }
                lm.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            } catch (e: Exception) {
                return "gps test provider refused: ${e.javaClass.simpleName}"
            }
            val seconds = p.getOrNull(4)?.toInt() ?: 20
            Thread {
                repeat(seconds) {
                    if (feed != mine) return@Thread
                    val fix = Location(LocationManager.GPS_PROVIDER).apply {
                        latitude = p[0]
                        longitude = p[1]
                        accuracy = p[2].toFloat()
                        altitude = 20.0
                        time = System.currentTimeMillis()
                        elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
                        extras = Bundle().apply {
                            putInt("satellites", p[3].toInt())
                            putInt("maxCn0", 38)
                            putInt("meanCn0", 32)
                        }
                    }
                    runCatching { lm.setTestProviderLocation(LocationManager.GPS_PROVIDER, fix) }
                        .onFailure { Log.w(TAG, "gps fix not set: ${it.javaClass.simpleName}") }
                    SystemClock.sleep(1_000)
                }
            }.apply { isDaemon = true }.start()
            val line = "gps feed acc=${p[2].toInt()} satellites=${p[3].toInt()} for ${seconds}s"
            Log.i(TAG, line)
            return line
        }
    }
}
