package com.kidslauncher.mdm.ui.quickcontrols

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ItemBluetoothDeviceBinding
import com.kidslauncher.mdm.server.BtLink
import com.kidslauncher.mdm.server.QuickControls

/** What a row offers (design 52 decision 2). */
data class BtRowActions(val pair: Boolean, val connect: Boolean, val disconnect: Boolean, val forget: Boolean)

/**
 * A row's actions: a device not paired only pairs (a tap on the row); a paired audio device
 * Connect or Disconnect by its state, and Forget; anything else (a watch, a keyboard, an LE-only
 * headset) Forget only. [busy] = pairing or our connect attempt running: no second tap.
 */
fun bluetoothRowActions(info: QuickControls.BluetoothDeviceInfo, busy: Boolean): BtRowActions {
    if (!info.bonded) return BtRowActions(pair = !busy, connect = false, disconnect = false, forget = false)
    val audio = info.audio && !busy
    return BtRowActions(
        pair = false,
        connect = audio && info.state == BtLink.OFF,
        disconnect = audio && info.state == BtLink.CONNECTED,
        forget = true,
    )
}

enum class BtRowStatus { PAIRING, CONNECTED, CONNECTING, NO_CONTACT, PAIRED, NONE }

/** A row's status line: "Tilkoblet" wins over a failure line a late success left behind. */
fun bluetoothRowStatus(
    info: QuickControls.BluetoothDeviceInfo,
    pairing: Boolean,
    attempting: Boolean,
    failed: Boolean,
): BtRowStatus = when {
    pairing -> BtRowStatus.PAIRING
    info.connected -> BtRowStatus.CONNECTED
    attempting || info.state == BtLink.CONNECTING -> BtRowStatus.CONNECTING
    failed -> BtRowStatus.NO_CONTACT
    info.bonded -> BtRowStatus.PAIRED
    else -> BtRowStatus.NONE
}

class BluetoothDeviceAdapter(
    private val onPair: (QuickControls.BluetoothDeviceInfo) -> Unit,
    private val onConnect: (QuickControls.BluetoothDeviceInfo) -> Unit,
    private val onDisconnect: (QuickControls.BluetoothDeviceInfo) -> Unit,
    private val onForget: (QuickControls.BluetoothDeviceInfo) -> Unit,
) : RecyclerView.Adapter<BluetoothDeviceAdapter.ViewHolder>() {

    private val devices = mutableListOf<QuickControls.BluetoothDeviceInfo>()
    private var pairingAddress: String? = null
    private var attempting: Set<String> = emptySet()
    private var failed: Set<String> = emptySet()

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

    /** The connect attempts running and the ones that failed ("Fikk ikke kontakt"). */
    @SuppressLint("NotifyDataSetChanged")
    fun setAttempts(attempting: Set<String>, failed: Set<String>) {
        this.attempting = attempting
        this.failed = failed
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
        val isAttempting = device.address in attempting
        val actions = bluetoothRowActions(device, busy = isPairing || isAttempting)

        binding.bluetoothDeviceName.text = device.name

        val statusText = when (bluetoothRowStatus(device, isPairing, isAttempting, device.address in failed)) {
            BtRowStatus.PAIRING -> context.getString(R.string.bluetooth_devices_pairing)
            BtRowStatus.CONNECTED -> context.getString(R.string.bluetooth_devices_connected)
            BtRowStatus.CONNECTING -> context.getString(R.string.bluetooth_devices_connecting)
            BtRowStatus.NO_CONTACT -> context.getString(R.string.bluetooth_devices_no_contact, device.name)
            BtRowStatus.PAIRED -> context.getString(R.string.bluetooth_devices_paired)
            BtRowStatus.NONE -> null
        }
        binding.bluetoothDeviceStatus.text = statusText
        binding.bluetoothDeviceStatus.visibility = if (statusText == null) View.GONE else View.VISIBLE

        binding.bluetoothDeviceForget.visibility = if (actions.forget) View.VISIBLE else View.GONE
        binding.bluetoothDeviceForget.setOnClickListener { onForget(device) }

        if (actions.connect || actions.disconnect) {
            binding.bluetoothDeviceConnectAction.visibility = View.VISIBLE
            binding.bluetoothDeviceConnectAction.text = context.getString(
                if (actions.disconnect) R.string.bluetooth_devices_disconnect
                else R.string.bluetooth_devices_connect,
            )
            binding.bluetoothDeviceConnectAction.setOnClickListener {
                if (actions.disconnect) onDisconnect(device) else onConnect(device)
            }
        } else {
            binding.bluetoothDeviceConnectAction.visibility = View.GONE
        }

        binding.root.setOnClickListener {
            if (actions.pair) onPair(device)
        }
    }

    override fun getItemCount(): Int = devices.size
}
