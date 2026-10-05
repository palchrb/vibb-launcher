package com.kidslauncher.mdm.ui.quickcontrols

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityBluetoothDevicesBinding
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * Quick Controls' "Manage devices" screen - scan/pair/connect/forget, built entirely on
 * [QuickControls]' Device-Owner Bluetooth APIs rather than `Settings.ACTION_BLUETOOTH_SETTINGS` or
 * any QS panel, for the same reason the rest of Quick Controls exists.
 *
 * Note the one thing this screen genuinely can't do anything about: for devices that don't use
 * "Just Works" pairing, Android's own system pairing-confirmation dialog still appears over this
 * screen during [onPairTapped] - that's rendered by the Bluetooth stack itself, and suppressing it
 * needs `BLUETOOTH_PRIVILEGED`, a signature permission no non-preinstalled app can hold. Settings
 * hits the same dialog, so this isn't a regression, just not fully invisible like the WiFi flow.
 */
class BluetoothDevicesActivity : UIObjectActivity() {
    private lateinit var binding: ActivityBluetoothDevicesBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var adapter: BluetoothDeviceAdapter

    private var discoveryReceiver: BroadcastReceiver? = null
    private var bondStateReceiver: BroadcastReceiver? = null
    private val discovered = linkedMapOf<String, QuickControls.BluetoothDeviceInfo>()
    private var pairingAddress: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBluetoothDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setTitle(R.string.bluetooth_devices_title)
        setSupportActionBar(binding.bluetoothDevicesAppbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)

        adapter = BluetoothDeviceAdapter(
            onPair = ::onPairTapped,
            onConnect = { QuickControls.connectBluetoothDevice(it.address); refreshOne(it.address) },
            onDisconnect = { QuickControls.disconnectBluetoothDevice(it.address); refreshOne(it.address) },
            onForget = ::onForgetTapped,
        )
        binding.bluetoothDevicesList.layoutManager = LinearLayoutManager(this)
        binding.bluetoothDevicesList.adapter = adapter

        registerBondStateReceiver()
        scan()
    }

    override fun onDestroy() {
        super.onDestroy()
        QuickControls.stopBluetoothScan(this, discoveryReceiver)
        bondStateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Already unregistered - harmless.
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_bluetooth_devices, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            R.id.bluetooth_devices_menu_scan -> {
                scan()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun scan() {
        QuickControls.stopBluetoothScan(this, discoveryReceiver)
        discovered.clear()
        QuickControls.bondedBluetoothDevices().forEach { discovered[it.address] = it }
        refreshList()

        binding.bluetoothDevicesStatusMessage.text = getString(R.string.bluetooth_devices_scanning)
        binding.bluetoothDevicesStatusMessage.visibility =
            if (discovered.isEmpty()) View.VISIBLE else View.GONE

        discoveryReceiver = QuickControls.scanBluetoothDevices(
            context = this,
            dpm = dpm,
            admin = admin,
            onDeviceFound = { device ->
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    discovered[device.address] = device
                    refreshList()
                }
            },
            onFinished = {
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (discovered.isEmpty()) {
                        binding.bluetoothDevicesStatusMessage.text =
                            getString(R.string.bluetooth_devices_empty)
                        binding.bluetoothDevicesStatusMessage.visibility = View.VISIBLE
                    }
                }
            },
        )
    }

    private fun refreshList() {
        val sorted = discovered.values.sortedWith(
            compareByDescending<QuickControls.BluetoothDeviceInfo> { it.connected }
                .thenByDescending { it.bonded },
        )
        adapter.submitList(sorted)
        if (discovered.isNotEmpty()) {
            binding.bluetoothDevicesStatusMessage.visibility = View.GONE
        }
    }

    private fun refreshOne(address: String) {
        QuickControls.bluetoothDeviceInfo(address)?.let { discovered[address] = it }
        refreshList()
    }

    private fun onPairTapped(device: QuickControls.BluetoothDeviceInfo) {
        pairingAddress = device.address
        adapter.setPairing(device.address)
        val started = QuickControls.pairBluetoothDevice(device.address)
        if (!started) {
            pairingAddress = null
            adapter.setPairing(null)
            Toast.makeText(this, R.string.bluetooth_devices_pair_failed, Toast.LENGTH_LONG).show()
        }
        // The real outcome (bonded/failed) arrives asynchronously via ACTION_BOND_STATE_CHANGED,
        // handled in registerBondStateReceiver() - createBond() returning true here only means
        // pairing started, same as elsewhere in this screen's async-callback pattern.
    }

    private fun onForgetTapped(device: QuickControls.BluetoothDeviceInfo) {
        QuickControls.forgetBluetoothDevice(device.address)
        discovered.remove(device.address)
        refreshList()
    }

    private fun registerBondStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                val device = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return
                val bondState = intent.getIntExtra(
                    BluetoothDevice.EXTRA_BOND_STATE,
                    BluetoothDevice.BOND_NONE,
                )

                if (device.address == pairingAddress && bondState != BluetoothDevice.BOND_BONDING) {
                    pairingAddress = null
                    adapter.setPairing(null)
                    if (bondState != BluetoothDevice.BOND_BONDED) {
                        Toast.makeText(
                            this@BluetoothDevicesActivity,
                            R.string.bluetooth_devices_pair_failed,
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
                refreshOne(device.address)
            }
        }
        try {
            registerReceiver(
                receiver,
                IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED),
                Context.RECEIVER_NOT_EXPORTED,
            )
            bondStateReceiver = receiver
        } catch (e: Exception) {
            // Non-fatal - pairing still works, the list just won't live-update on bond changes.
        }
    }
}
