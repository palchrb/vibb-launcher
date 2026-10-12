// Vibb Spotify engine spike (docs/testing/spotify-spike.md).
// Copyright (C) 2026 palchrb. SPDX-License-Identifier: GPL-3.0-or-later

package me.vibb.spotifyspike

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Process

/** Starts the log and notes screen on/off and network changes, so a long run reads from the log. */
class SpikeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        SpikeLog.init(this)
        SpikeLog.log("--- process start (pid ${Process.myPid()}), log file ${SpikeLog.path()}")
        SpikeLog.log(PackageProbe.describe(this))

        val screen = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                SpikeLog.log(if (intent.action == Intent.ACTION_SCREEN_OFF) "screen OFF" else "screen ON")
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        registerReceiver(screen, filter, Context.RECEIVER_NOT_EXPORTED)

        getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = SpikeLog.log("network available")

            override fun onLost(network: Network) = SpikeLog.log("network LOST")

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                val transport = when {
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                    else -> "other"
                }
                if (validated != lastValidated || transport != lastTransport) {
                    lastValidated = validated
                    lastTransport = transport
                    SpikeLog.log("network: $transport validated=$validated")
                }
            }
        })
    }

    private var lastValidated: Boolean? = null
    private var lastTransport: String? = null
}
