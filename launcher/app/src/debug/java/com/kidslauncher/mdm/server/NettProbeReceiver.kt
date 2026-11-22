package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.app.admin.PreferentialNetworkServiceConfig
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.util.Log
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL

/**
 * Debug build only (src/debug, design 127 step 0, the Nett probe; throwaway branch `nett-probe`, never merged). Exported
 * for `adb shell am broadcast -n me.vibb.launcher.debug/com.kidslauncher.mdm.server.NettProbeReceiver --es step <step>`,
 * guarded by DUMP. Logcat `NettProbe`. Steps:
 * - `cut` `--es pkgs a,b` [`--ei netid 5`]: one enabled preferential-network config for those packages' uids, no
 *   fallback, non-matching networks blocked (variant A1).
 * - `clear`: a disabled config (`PreferentialNetworkServiceConfig.DEFAULT` is @hide); `clearempty`: an empty list.
 * - `get`: the configs Android holds.
 * - `vpnout` `--es pkgs a,b` (empty to clear): those packages outside our VPN as well (variant A2); the tunnel is
 *   established again.
 * - `shape` `--es shape filter|sink|pass|none` (or `auto`): the VPN's shape forced over the stored inputs.
 * - `self`: the launcher's own network - a TCP connect to a literal IP, a name, an HTTP fetch.
 */
class NettProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val step = intent.getStringExtra("step")
        val pending = goAsync()
        Thread {
            val result = try {
                when (step) {
                    "cut" -> cut(app, intent.getStringExtra("pkgs").orEmpty(), intent.getIntExtra("netid", PreferentialNetworkServiceConfig.PREFERENTIAL_NETWORK_ID_5))
                    // PreferentialNetworkServiceConfig.DEFAULT is @hide: a disabled config (or `clearempty`, an empty list).
                    "clear" -> {
                        dpm(app).setPreferentialNetworkServiceConfigs(listOf(PreferentialNetworkServiceConfig.Builder().setEnabled(false).build()))
                        "cleared; now ${dpm(app).preferentialNetworkServiceConfigs}"
                    }
                    "clearempty" -> {
                        dpm(app).setPreferentialNetworkServiceConfigs(emptyList())
                        "cleared (empty); now ${dpm(app).preferentialNetworkServiceConfigs}"
                    }
                    "get" -> dpm(app).preferentialNetworkServiceConfigs.toString()
                    "vpnout" -> {
                        NettProbe.setVpnOut(app, intent.getStringExtra("pkgs"))
                        KidVpnService.start(app)
                        "vpn_out=${NettProbe.extraDisallowed(app)}"
                    }
                    "shape" -> {
                        val wire = intent.getStringExtra("shape")?.takeIf { it != "auto" }
                        NettProbe.setShape(app, wire)
                        KidVpnService.start(app)
                        "shape=${NettProbe.forcedShape(app)}"
                    }
                    "self" -> netCheck(app)
                    else -> "unknown step $step"
                }
            } catch (e: Throwable) {
                "FAILED ${e.javaClass.name}: ${e.message}"
            }
            Log.i(TAG, "$step -> $result")
            pending.resultData = result
            pending.finish()
        }.start()
    }

    private fun dpm(context: Context) = context.getSystemService(DevicePolicyManager::class.java)

    private fun cut(context: Context, pkgs: String, netId: Int): String {
        val uids = pkgs.split(",").map { it.trim() }.filter { it.isNotEmpty() }
            .map { context.packageManager.getPackageUid(it, PackageManager.PackageInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong())) }
            .distinct().sorted().toIntArray()
        val config = PreferentialNetworkServiceConfig.Builder()
            .setEnabled(true)
            .setNetworkId(netId)
            .setFallbackToDefaultConnectionAllowed(false)
            .setShouldBlockNonMatchingNetworks(true)
            .setIncludedUids(uids)
            .build()
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        Log.i(TAG, "cut: device owner ${dpm(context).isDeviceOwnerApp(context.packageName)}, admin $admin, uids ${uids.toList()}")
        dpm(context).setPreferentialNetworkServiceConfigs(listOf(config))
        return "set; now ${dpm(context).preferentialNetworkServiceConfigs}"
    }

    private fun netCheck(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        val parts = mutableListOf("active=$active caps=${active?.let { cm.getNetworkCapabilities(it) }?.let { transports(it.toString()) }}")
        parts += "tcp=" + attempt {
            Socket().use { it.connect(InetSocketAddress("1.1.1.1", 443), 5_000) }
            "ok"
        }
        parts += "dns=" + attempt { InetAddress.getAllByName("example.org").joinToString { it.hostAddress ?: "?" } }
        parts += "http=" + attempt {
            val c = URL("http://example.com/").openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            try { c.responseCode.toString() } finally { c.disconnect() }
        }
        return parts.joinToString(" ")
    }

    private fun transports(caps: String) = Regex("Transports: [A-Z|]+").find(caps)?.value ?: caps.take(60)

    private fun attempt(block: () -> String): String = try {
        block()
    } catch (e: Throwable) {
        "FAIL(${e.javaClass.simpleName}: ${e.message?.take(60)})"
    }

    private companion object {
        const val TAG = "NettProbe"
    }
}
