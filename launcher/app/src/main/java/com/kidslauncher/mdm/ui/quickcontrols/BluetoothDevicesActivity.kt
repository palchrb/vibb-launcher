package com.kidslauncher.mdm.ui.quickcontrols

import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityBluetoothDevicesBinding
import com.kidslauncher.mdm.server.BT_ATTEMPT_TIMEOUT_MS
import com.kidslauncher.mdm.server.BluetoothAudio
import com.kidslauncher.mdm.server.BtAttemptEnd
import com.kidslauncher.mdm.server.BtAttemptEvent
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.btAttemptStep
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Quick Controls' "Manage devices" screen - scan/pair/connect/forget, built entirely on
 * [QuickControls]' Device-Owner Bluetooth APIs rather than `Settings.ACTION_BLUETOOTH_SETTINGS` or
 * any QS panel, for the same reason the rest of Quick Controls exists.
 *
 * Connect/Disconnect go through the A2DP (and HEADSET) proxies of [BluetoothAudio] (design 52): one
 * [BluetoothAudio.Session] per start, closed on stop; every Bluetooth read and call runs off the main
 * thread. The rows follow the A2DP and ACL broadcasts live; after a Connect tap a row reads "Kobler
 * til…" until it connects, the call is refused, A2DP gives up (CONNECTING -> DISCONNECTED) or 10 s
 * pass - then "Fikk ikke kontakt med <name> - er den på?" ([btAttemptStep]).
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
    private var stateReceiver: BroadcastReceiver? = null
    private val discovered = linkedMapOf<String, QuickControls.BluetoothDeviceInfo>()
    private var pairingAddress: String? = null

    /** This start's proxies; null while stopped. */
    private var session: BluetoothAudio.Session? = null
    private var firstStart = true

    /** Connect attempts running (their 10 s timeouts) and the ones that failed. Main thread only. */
    private val attempts = mutableMapOf<String, Job>()
    private val failed = mutableSetOf<String>()
    private val refreshes = mutableMapOf<String, Job>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityBluetoothDevicesBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)
        com.kidslauncher.mdm.ui.KidHeader.bind(this, binding.bluetoothDevicesHeader, R.string.bluetooth_devices_title, R.string.bluetooth_devices_scan) { scan() }

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)
        // Before the first list read: the bonded list and the proxies need it (design 52).
        QuickControls.grantBluetoothConnectIfNeeded(this, dpm, admin)

        adapter = BluetoothDeviceAdapter(
            onPair = ::onPairTapped,
            onConnect = ::onConnectTapped,
            onDisconnect = ::onDisconnectTapped,
            onForget = ::onForgetTapped,
        )
        binding.bluetoothDevicesList.layoutManager = LinearLayoutManager(this)
        binding.bluetoothDevicesList.adapter = adapter

        registerStateReceiver()
    }

    override fun onStart() {
        super.onStart()
        session = BluetoothAudio.Session(this)
        if (firstStart) {
            firstStart = false
            scan()
        } else {
            loadBonded()
        }
    }

    override fun onStop() {
        session?.close()
        session = null
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        QuickControls.stopBluetoothScan(this, discoveryReceiver)
        stateReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (e: Exception) {
                // Already unregistered - harmless.
            }
        }
    }

    private fun scan() {
        QuickControls.stopBluetoothScan(this, discoveryReceiver)
        discovered.clear()
        refreshList()
        loadBonded()

        binding.bluetoothDevicesStatusMessage.text = getString(R.string.bluetooth_devices_scanning)
        binding.bluetoothDevicesStatusMessage.visibility =
            if (discovered.isEmpty()) View.VISIBLE else View.GONE

        discoveryReceiver = QuickControls.scanBluetoothDevices(
            context = this,
            dpm = dpm,
            admin = admin,
            onDeviceFound = { device ->
                // The receiver runs on main; the device's row (UUIDs, ACL link) is read on IO.
                lifecycleScope.launch {
                    val info = QuickControls.foundBluetoothDevice(device)
                    // A bonded device keeps its proxy-read row.
                    if (discovered[info.address]?.bonded != true) {
                        discovered[info.address] = info
                        refreshList()
                    }
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

    /** The bonded devices with their states, read off the main thread. */
    private fun loadBonded() {
        val s = session
        lifecycleScope.launch {
            val bonded = QuickControls.bondedBluetoothDevices(this@BluetoothDevicesActivity, s)
            for (info in bonded) {
                discovered[info.address] = info
                settle(info)
            }
            refreshList()
        }
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

    /** Re-reads one row off the main thread; a newer refresh of the same row replaces this one. */
    private fun refreshOne(address: String) {
        val s = session
        refreshes[address]?.cancel()
        refreshes[address] = lifecycleScope.launch {
            val info = QuickControls.bluetoothDeviceInfo(this@BluetoothDevicesActivity, s, address)
            refreshes.remove(address)
            // A forgotten device that the scan didn't find stays off the list.
            if (info != null && (info.bonded || address in discovered)) {
                discovered[address] = info
                settle(info)
                refreshList()
            }
        }
    }

    /** A row read connected ends its attempt (and clears a failure a late success left). */
    private fun settle(info: QuickControls.BluetoothDeviceInfo) {
        btAttemptStep(BtAttemptEvent.Read(info.state))?.let { endAttempt(info.address, it) }
    }

    private fun onConnectTapped(device: QuickControls.BluetoothDeviceInfo) {
        val s = session ?: return
        val address = device.address
        failed.remove(address)
        attempts.remove(address)?.cancel()
        attempts[address] = lifecycleScope.launch {
            delay(BT_ATTEMPT_TIMEOUT_MS)
            endAttempt(address, btAttemptStep(BtAttemptEvent.Timeout))
        }
        showAttempts()
        lifecycleScope.launch {
            val asked = QuickControls.connectBluetoothDevice(this@BluetoothDevicesActivity, s, address)
            btAttemptStep(BtAttemptEvent.Called(asked))?.let { endAttempt(address, it) }
            refreshOne(address)
        }
    }

    private fun onDisconnectTapped(device: QuickControls.BluetoothDeviceInfo) {
        val s = session ?: return
        failed.remove(device.address)
        showAttempts()
        lifecycleScope.launch {
            QuickControls.disconnectBluetoothDevice(this@BluetoothDevicesActivity, s, device.address)
            refreshOne(device.address)
        }
    }

    /** Ends [address]'s attempt; a failure counts only while one runs, a success always clears. */
    private fun endAttempt(address: String, end: BtAttemptEnd?) {
        when (end) {
            BtAttemptEnd.SUCCESS -> {
                val job = attempts.remove(address)
                job?.cancel()
                val hadFailed = failed.remove(address)
                if (job != null || hadFailed) showAttempts()
            }
            BtAttemptEnd.FAILURE -> {
                val job = attempts.remove(address) ?: return
                job.cancel()
                failed.add(address)
                showAttempts()
            }
            null -> Unit
        }
    }

    private fun showAttempts() = adapter.setAttempts(attempts.keys.toSet(), failed.toSet())

    private fun onPairTapped(device: QuickControls.BluetoothDeviceInfo) {
        pairingAddress = device.address
        adapter.setPairing(device.address)
        val started = QuickControls.pairBluetoothDevice(this, device.address)
        if (!started) {
            pairingAddress = null
            adapter.setPairing(null)
            Toast.makeText(this, R.string.bluetooth_devices_pair_failed, Toast.LENGTH_LONG).show()
        }
        // The real outcome (bonded/failed) arrives asynchronously via ACTION_BOND_STATE_CHANGED,
        // handled in registerStateReceiver() - createBond() returning true here only means
        // pairing started, same as elsewhere in this screen's async-callback pattern.
    }

    private fun onForgetTapped(device: QuickControls.BluetoothDeviceInfo) {
        QuickControls.forgetBluetoothDevice(this, device.address)
        discovered.remove(device.address)
        attempts.remove(device.address)?.cancel()
        failed.remove(device.address)
        showAttempts()
        refreshList()
    }

    /** Bonds, A2DP's connection state and the ACL link: each refreshes its row (design 52). */
    private fun registerStateReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                val device = intent?.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE,
                    BluetoothDevice::class.java,
                ) ?: return
                when (intent.action) {
                    BluetoothDevice.ACTION_BOND_STATE_CHANGED -> onBondChanged(device, intent)
                    BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> {
                        val event = BtAttemptEvent.A2dp(
                            previous = intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE, -1),
                            state = intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1),
                        )
                        btAttemptStep(event)?.let { endAttempt(device.address, it) }
                    }
                }
                refreshOne(device.address)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try {
            // EXPORTED: the Bluetooth app (uid 1002) sends these; AOSP's BroadcastSkipPolicy skips a NOT_EXPORTED receiver for any sender but root/system/us ("Exported Denial"), and they are <protected-broadcast>s, so no other app can send them.
            registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            stateReceiver = receiver
        } catch (e: Exception) {
            // Non-fatal - pairing and connecting still work, the list just won't live-update.
        }
    }

    private fun onBondChanged(device: BluetoothDevice, intent: Intent) {
        val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
        if (device.address == pairingAddress && bondState != BluetoothDevice.BOND_BONDING) {
            pairingAddress = null
            adapter.setPairing(null)
            if (bondState != BluetoothDevice.BOND_BONDED) {
                Toast.makeText(this, R.string.bluetooth_devices_pair_failed, Toast.LENGTH_LONG).show()
            }
        }
    }
}
