package com.kidslauncher.mdm.server

import android.content.Context

/**
 * Debug build only (src/debug, design 127 step 0, the Nett probe; throwaway branch `nett-probe`): a VPN shape forced
 * over the stored inputs, and extra packages kept outside the VPN (variant A2), both kept across reboots in their own
 * preferences file. [NettProbeReceiver] writes them.
 */
internal object NettProbe {
    private const val PREFS = "nett_probe"

    @Volatile
    private var reestablish = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun forcedShape(context: Context): VpnShape? = VpnShape.of(prefs(context).getString("shape", null))

    fun extraDisallowed(context: Context): List<String> =
        prefs(context).getString("vpn_out", null)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

    fun takeReestablish(): Boolean = reestablish.also { reestablish = false }

    fun setShape(context: Context, wire: String?) {
        prefs(context).edit().putString("shape", wire).commit()
        reestablish = true
    }

    fun setVpnOut(context: Context, packages: String?) {
        prefs(context).edit().putString("vpn_out", packages).commit()
        reestablish = true
    }
}
