package com.kidslauncher.mdm.ui.quickcontrols

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ItemBluetoothDeviceBinding
import com.kidslauncher.mdm.server.QuickControls

class BluetoothDeviceAdapter(
    private val onPair: (QuickControls.BluetoothDeviceInfo) -> Unit,
    private val onConnect: (QuickControls.BluetoothDeviceInfo) -> Unit,
    private val onDisconnect: (QuickControls.BluetoothDeviceInfo) -> Unit,
    private val onForget: (QuickControls.BluetoothDeviceInfo) -> Unit,
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<QuickControls.BluetoothDeviceInfo>()
    private var pairingAddress: String? = null

    @SuppressLint("NotifyDataSetChanged")
    fun submitList(newDevices: List<QuickControls.BluetoothDeviceInfo>) {
        devices.clear()
        devices.addAll(newDevices)
        notifyDataSetChanged()
    }

    @SuppressLint("NotifyDataSetChanged")
    fun setPairing(address: String?) {
        pairingAddress = address
        notifyDataSetChanged()
    }

    inner class ViewHolder(val binding: ItemBluetoothDeviceBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding =
            ItemBluetoothDeviceBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val device = devices[position]
        val binding = holder.binding
        val context = binding.root.context
        val isPairing = device.address == pairingAddress

        binding.bluetoothDeviceName.text = device.name

        val statusText = when {
            isPairing -> context.getString(R.string.bluetooth_devices_pairing)
            device.connected -> context.getString(R.string.bluetooth_devices_connected)
            device.bonded -> context.getString(R.string.bluetooth_devices_paired)
            else -> null
        }
        binding.bluetoothDeviceStatus.text = statusText
        binding.bluetoothDeviceStatus.visibility = if (statusText == null) View.GONE else View.VISIBLE

        binding.bluetoothDeviceForget.visibility = if (device.bonded) View.VISIBLE else View.GONE
        binding.bluetoothDeviceForget.setOnClickListener { onForget(device) }

        if (device.bonded) {
            binding.bluetoothDeviceConnectAction.visibility = View.VISIBLE
            binding.bluetoothDeviceConnectAction.text = context.getString(
                if (device.connected) R.string.bluetooth_devices_disconnect
                else R.string.bluetooth_devices_connect,
            )
            binding.bluetoothDeviceConnectAction.setOnClickListener {
                if (device.connected) onDisconnect(device) else onConnect(device)
            }
        } else {
            binding.bluetoothDeviceConnectAction.visibility = View.GONE
        }

        binding.root.setOnClickListener {
            if (!device.bonded && !isPairing) onPair(device)
        }
    }

    override fun getItemCount(): Int = devices.size
}
