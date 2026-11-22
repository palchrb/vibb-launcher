package com.kidslauncher.mdm.server

import android.content.Context

/**
 * Release build: no design 127 step 0 probe. The debug build's version (src/debug) lets the emulator force a VPN shape
 * and keep extra packages outside the VPN. Never add anything to this file.
 */
internal object NettProbe {
    @Suppress("UNUSED_PARAMETER")
    fun forcedShape(context: Context): VpnShape? = null

    @Suppress("UNUSED_PARAMETER")
    fun extraDisallowed(context: Context): List<String> = emptyList()

    fun takeReestablish(): Boolean = false
}
