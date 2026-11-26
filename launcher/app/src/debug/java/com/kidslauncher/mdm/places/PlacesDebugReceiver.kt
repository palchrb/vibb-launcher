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
 * `bssid,ssid,psk|sae|eap|open,networkId,MHz[,dBm[,mld]]` (unmetered; -45 dBm unless given - design 134 revision 4's level;
 * design 138: the AP's multi-link address);
 * `--ez clear true` clears both. Every scan, attempt and teach
 * reads them (heard now) until cleared or the process restarts. Exported for the shell, guarded by DUMP.
 *
 * Design 134 §7: `--ei scan_age_s N` - the made-up results were heard N s before each read (0 now); `--ez refuse_scan
 * true` - every `startScan` counts as throttled until `false` or `clear`; `--es motion sensor|fire|off` - a fake
 * significant-motion trigger the motion gate arms as the real one ([PlacesDebugHooks]; none of it in a release build).
 *
 * Satellite fixes (the 131 gate, 2026-11-25): the emulator's GNSS marks no satellite as used in a fix (`satellites=0`
 * in the extras of every `geo fix`, whatever its count), so none of its fixes teaches. `--es gps "lat,lng,accuracy,
 * satellites[,seconds]"` makes `gps` a test provider (after `adb shell appops set <package> android:mock_location
 * allow`) and feeds it that fix once a second for the seconds given (20), the satellites in its extras; a new `gps`
 * ends the running feed, and with no feed running the provider is silent. `--ez gps_off true` removes the provider.
 */
class PlacesDebugReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("motion")?.let { m ->
            resultData = motion(m)
            return
        }
        if (intent.getBooleanExtra("gps_off", false)) {
            resultData = gpsFeed(context, null)
            return
        }
        intent.getStringExtra("gps")?.let { g ->
            resultData = gpsFeed(context, g.split(',').mapNotNull { it.trim().toDoubleOrNull() })
            return
        }
        if (intent.getBooleanExtra("clear", false)) {
            PlacesDebugHooks.madeUpAps = null
            PlacesDebugHooks.madeUpConnection = null
            PlacesDebugHooks.scanAgeS = 0
            PlacesDebugHooks.refuseScan = false
            Log.i(TAG, "made-up scan and connection cleared")
            resultData = "cleared"
            return
        }
        intent.getStringExtra("aps")?.let { aps ->
            PlacesDebugHooks.madeUpAps = aps.split(';').mapNotNull { ap ->
                val p = ap.split(',').map { it.trim() }
                if (p.size < 4) return@mapNotNull null
                ScanAp(p[0], p[1].ifEmpty { null }, null, p[2].toIntOrNull() ?: return@mapNotNull null, p[3].toIntOrNull() ?: return@mapNotNull null, 0L)
            }
        }
        intent.getStringExtra("connect")?.let { c ->
            val p = c.split(',').map { it.trim() }
            PlacesDebugHooks.madeUpConnection = if (p.size < 5) {
                null
            } else {
                val security = when (p[2]) {
                    "psk" -> LinkSecurity.PSK
                    "sae" -> LinkSecurity.SAE
                    "eap" -> LinkSecurity.EAP
                    else -> LinkSecurity.OTHER
                }
                Connected(
                    p[0], p[1].ifEmpty { null }, p.getOrNull(5)?.toIntOrNull() ?: -45, p[4].toIntOrNull() ?: 2437, true, security,
                    p[3].toIntOrNull() ?: -1,
                    // Design 138: a seventh field, the AP's multi-link address.
                    mld = p.getOrNull(6)?.ifEmpty { null },
                )
            }
        }
        if (intent.hasExtra("scan_age_s")) PlacesDebugHooks.scanAgeS = intent.getIntExtra("scan_age_s", 0).coerceAtLeast(0).toLong()
        if (intent.hasExtra("refuse_scan")) PlacesDebugHooks.refuseScan = intent.getBooleanExtra("refuse_scan", false)
        val h = PlacesDebugHooks
        val line = "made-up scan aps=${h.madeUpAps?.size ?: 0} connected=${h.madeUpConnection?.security ?: "none"} " +
            "age=${h.scanAgeS}s refuse=${h.refuseScan} motion=${h.fakeMotion ?: "real"}"
        Log.i(TAG, line)
        resultData = line
    }

    private companion object {
        const val TAG = "PlacesDebug"

        /** Design 134 §7: `sensor` - the fake trigger stands in for the sensor (armed at the next sync or try), `fire` -
         * it fires as the real one would, `off` - the real sensor (none on the emulator) again. */
        fun motion(m: String): String {
            val line = when (m) {
                "sensor" -> {
                    PlacesDebugHooks.fakeMotion = "sensor"
                    "fake motion trigger on (armed at the next sync)"
                }
                "fire" -> if (PlacesDebugHooks.fireTrigger()) "fake motion trigger fired" else "fake motion trigger not armed"
                "off" -> {
                    PlacesDebugHooks.fakeMotion = null
                    PlacesDebugHooks.disarmTrigger()
                    "fake motion trigger off"
                }
                else -> "motion needs sensor|fire|off"
            }
            Log.i(TAG, line)
            return line
        }

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
