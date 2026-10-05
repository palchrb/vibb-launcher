package com.kidslauncher.mdm.ui.quickcontrols

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ItemWifiNetworkBinding
import com.kidslauncher.mdm.server.QuickControls

class WifiNetworkAdapter(
    private val onConnect: (QuickControls.WifiNetworkInfo) -> Unit,
    private val onForget: (QuickControls.WifiNetworkInfo) -> Unit,
) : RecyclerView.Adapter<WifiNetworkAdapter.ViewHolder>() {

    private val networks = mutableListOf<QuickControls.WifiNetworkInfo>()
    private var connectingSsid: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newNetworks: List<QuickControls.WifiNetworkInfo>) {
        networks.clear()
        networks.addAll(newNetworks)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setConnecting(ssid: String?) {
        connectingSsid = ssid
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemWifiNetworkBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemWifiNetworkBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val network = networks[position]
        val binding = holder.binding
        val context = binding.root.context

        binding.wifiNetworkSsid.text = network.ssid

        // No public signal-strength drawable exists on this platform (the stat_sys_wifi_signal_*
        // resources are framework-internal, not part of android.R.drawable) - a plain text bar
        // indicator avoids depending on hidden resources entirely.
        val bars = "▁▃▅█"
        val filledBars = (network.signalLevel.coerceIn(0, 4))
        binding.wifiNetworkSignalText.text = buildString {
            repeat(4) { i -> append(if (i < filledBars) bars[i] else '▁') }
        }
        binding.wifiNetworkLockIcon.visibility = if (network.secured) View.VISIBLE else View.GONE

        val statusText = when {
            network.ssid == connectingSsid -> context.getString(R.string.wifi_networks_connecting)
            network.isConnected -> context.getString(R.string.wifi_networks_connected)
            network.savedNetworkId != null -> context.getString(R.string.wifi_networks_saved)
            else -> null
        }
        binding.wifiNetworkStatus.text = statusText
        binding.wifiNetworkStatus.visibility = if (statusText == null) View.GONE else View.VISIBLE

        binding.wifiNetworkForget.visibility =
            if (network.savedNetworkId != null && !network.isConnected) View.VISIBLE else View.GONE
        binding.wifiNetworkForget.setOnClickListener { onForget(network) }

        binding.root.setOnClickListener {
            if (network.ssid != connectingSsid && !network.isConnected) onConnect(network)
        }
    }

    override fun getItemCount(): Int = networks.size
}
