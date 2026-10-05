package com.kidslauncher.mdm.ui.quickcontrols

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityWifiNetworksBinding
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.ui.UIObjectActivity

private const val CONNECT_POLL_INTERVAL_MS = 3_000L
private const val CONNECT_TIMEOUT_MS = 15_000L

/**
 * Quick Controls' "Manage networks" screen - scan/connect/forget, built entirely on
 * [QuickControls]' Device-Owner WiFi APIs rather than `Settings.ACTION_WIFI_SETTINGS` or any QS
 * panel, for the same reason the rest of Quick Controls exists (see that screen's doc comment).
 */
class WifiNetworksActivity : UIObjectActivity() {
    private lateinit var binding: ActivityWifiNetworksBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName
    private lateinit var adapter: WifiNetworkAdapter
    // Looper.getMainLooper() is a static call, unlike Activity.mainLooper (a Context method) -
    // using the latter here would crash, since this property initializer runs in the Activity's
    // constructor, before it's attached to a base Context.
    private val handler = Handler(Looper.getMainLooper())

    /** Non-null exactly while a connect attempt (including its poll loop) is in flight. */
    private var connectingSsid: String? = null

    /** True only if this screen itself turned Location on - so onDestroy knows to turn it back
     * off rather than clobbering a setting the parent already had on for their own reasons. */
    private var locationEnabledByUs = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityWifiNetworksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setTitle(R.string.wifi_networks_title)
        setSupportActionBar(binding.wifiNetworksAppbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)

        // WifiManager's scan/config APIs hard-require Location to be on system-wide, independent
        // of any app permission - see QuickControls.isLocationEnabled's doc comment. Silently
        // enabled here (no Settings, no dialog) only for this screen's lifetime.
        if (!QuickControls.isLocationEnabled(this)) {
            QuickControls.setLocationEnabled(dpm, admin, true)
            locationEnabledByUs = true
        }

        adapter = WifiNetworkAdapter(onConnect = ::onNetworkTapped, onForget = ::onForgetTapped)
        binding.wifiNetworksList.layoutManager = LinearLayoutManager(this)
        binding.wifiNetworksList.adapter = adapter

        scan()
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (locationEnabledByUs) {
            QuickControls.setLocationEnabled(dpm, admin, false)
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_wifi_networks, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }

            R.id.wifi_networks_menu_scan -> {
                scan()
                true
            }

            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun scan() {
        binding.wifiNetworksStatusMessage.text = getString(R.string.wifi_networks_scanning)
        binding.wifiNetworksStatusMessage.visibility = View.VISIBLE
        QuickControls.scanWifiNetworks(this, dpm, admin) { networks ->
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                adapter.submitList(networks)
                if (networks.isEmpty()) {
                    binding.wifiNetworksStatusMessage.text = getString(R.string.wifi_networks_empty)
                    binding.wifiNetworksStatusMessage.visibility = View.VISIBLE
                } else {
                    binding.wifiNetworksStatusMessage.visibility = View.GONE
                }
            }
        }
    }

    private fun onNetworkTapped(network: QuickControls.WifiNetworkInfo) {
        if (network.isConnected || network.ssid == connectingSsid) return
        // Already-saved networks (including ones we just connected to before) reconnect straight
        // away via their existing config - only ask for a password the first time.
        if (network.savedNetworkId != null || !network.secured) {
            connect(network, password = null)
            return
        }

        val input = EditText(this).apply {
            hint = getString(R.string.wifi_networks_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.wifi_networks_password_title)
            .setView(input)
            .setPositiveButton(R.string.wifi_networks_connect) { _, _ ->
                connect(network, password = input.text.toString())
            }
            .setNegativeButton(R.string.wifi_networks_cancel, null)
            .show()
    }

    /**
     * [QuickControls.connectToWifiNetwork] only reports whether the connect *attempt* was
     * accepted, not that the device actually associated - a wrong password shows up as a silent
     * auth/retry loop rather than a clean failure. So an accepted attempt starts a short poll loop
     * that re-scans and checks real connected state, falling back to a "check the password"
     * message after [CONNECT_TIMEOUT_MS] with no confirmed connection.
     */
    private fun connect(network: QuickControls.WifiNetworkInfo, password: String?) {
        connectingSsid = network.ssid
        adapter.setConnecting(network.ssid)

        val accepted = QuickControls.connectToWifiNetwork(
            this,
            network.ssid,
            password,
            network.secured,
            network.savedNetworkId,
        )
        if (accepted) {
            pollForConnection(network.ssid, elapsedMs = 0)
        } else {
            finishConnectAttempt(network.ssid, connected = false)
        }
    }

    private fun pollForConnection(ssid: String, elapsedMs: Long) {
        if (connectingSsid != ssid) return
        QuickControls.scanWifiNetworks(this, dpm, admin) { networks ->
            runOnUiThread {
                if (isFinishing || isDestroyed || connectingSsid != ssid) return@runOnUiThread
                val connected = networks.any { it.ssid == ssid && it.isConnected }
                if (connected) {
                    adapter.submitList(networks)
                    finishConnectAttempt(ssid, connected = true)
                } else if (elapsedMs + CONNECT_POLL_INTERVAL_MS >= CONNECT_TIMEOUT_MS) {
                    finishConnectAttempt(ssid, connected = false)
                } else {
                    handler.postDelayed(
                        { pollForConnection(ssid, elapsedMs + CONNECT_POLL_INTERVAL_MS) },
                        CONNECT_POLL_INTERVAL_MS,
                    )
                }
            }
        }
    }

    private fun finishConnectAttempt(ssid: String, connected: Boolean) {
        if (connectingSsid != ssid) return
        connectingSsid = null
        adapter.setConnecting(null)
        if (!connected) {
            Toast.makeText(this, R.string.wifi_networks_connect_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun onForgetTapped(network: QuickControls.WifiNetworkInfo) {
        val networkId = network.savedNetworkId ?: return
        QuickControls.forgetWifiNetwork(this, networkId)
        scan()
    }
}
