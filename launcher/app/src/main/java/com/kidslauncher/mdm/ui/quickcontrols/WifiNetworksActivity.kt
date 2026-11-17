package com.kidslauncher.mdm.ui.quickcontrols

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityWifiNetworksBinding
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.managed
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.LEGACY_LOCATION_POLICY
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.hardeningManaged
import com.kidslauncher.mdm.server.hardeningPlan
import com.kidslauncher.mdm.server.locationStaysOn
import com.kidslauncher.mdm.ui.kidsettings.SubPage
import com.kidslauncher.mdm.ui.kidsettings.SubPageGuard
import com.kidslauncher.mdm.ui.kidsettings.WifiPageEvent
import com.kidslauncher.mdm.ui.kidsettings.wifiLocationStep
import com.kidslauncher.mdm.ui.look.KidPageActivity
import com.kidslauncher.mdm.zones.ZoneWatch

private const val CONNECT_POLL_INTERVAL_MS = 3_000L
private const val CONNECT_TIMEOUT_MS = 15_000L

/**
 * Quick Controls' "Manage networks" screen - scan/connect/forget, built entirely on
 * [QuickControls]' Device-Owner WiFi APIs rather than `Settings.ACTION_WIFI_SETTINGS` or any QS
 * panel, for the same reason the rest of Quick Controls exists (see that screen's doc comment).
 */
class WifiNetworksActivity : KidPageActivity() {
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

    /** True only if this screen itself turned Location on - so its stop knows to turn it back
     * off rather than clobbering a setting the parent already had on for their own reasons. */
    private var locationEnabledByUs = false

    /** Design 111 §2.2: a lock comes first, and the page leaves when the parent takes the Wi-Fi row away. */
    private val guard = SubPageGuard(this, SubPage.WIFI)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityWifiNetworksBinding.inflate(layoutInflater)
        setContentView(binding.root)

        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)
        com.kidslauncher.mdm.ui.KidHeader.bind(this, binding.wifiNetworksHeader, R.string.wifi_networks_title, R.string.wifi_networks_scan, back = R.string.kid_back_settings) { scan() }

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)

        adapter = WifiNetworkAdapter(onConnect = ::onNetworkTapped, onForget = ::onForgetTapped)
        binding.wifiNetworksList.layoutManager = LinearLayoutManager(this)
        binding.wifiNetworksList.adapter = adapter

        binding.wifiNetworksPortal.setOnClickListener { portalTapped() }
    }

    /**
     * The gate first ([SubPageGuard]: no radio work under a rule or without the row), then Location and the scan
     * ([wifiLocationStep]). WifiManager's scan/config APIs hard-require Location to be on system-wide, independent of
     * any app permission - see QuickControls.isLocationEnabled's doc comment. Silently enabled here (no Settings, no
     * dialog) only while this screen is shown: it can sit stopped in Kid Settings' Recents card (design 111 §2.2).
     */
    override fun onStart() {
        super.onStart()
        if (guard.start()) return
        locationStep(WifiPageEvent.START)
        com.kidslauncher.mdm.portal.PortalRuntime.addListener(portalListener)
        renderPortal()
    }

    override fun onResume() {
        super.onResume()
        guard.check()
    }

    /** The connect poll dropped first (no "couldn't connect" for a connect that worked), then Location off if ours. */
    override fun onStop() {
        guard.stop()
        locationStep(WifiPageEvent.STOP)
        com.kidslauncher.mdm.portal.PortalRuntime.removeListener(portalListener)
        super.onStop()
    }

    private fun locationStep(event: WifiPageEvent) {
        val step = wifiLocationStep(
            event, QuickControls.isLocationEnabled(this), locationEnabledByUs, polling = connectingSsid != null,
            policyKeepsOn = event == WifiPageEvent.STOP && locationEnabledByUs && policyKeepsLocationOn(),
        )
        if (step.stopPoll) {
            handler.removeCallbacksAndMessages(null)
            connectingSsid = null
            adapter.setConnecting(null)
        }
        step.location?.let { QuickControls.setLocationEnabled(dpm, admin, it) }
        locationEnabledByUs = step.byUs
        if (step.scan) scan()
    }

    /** Code QA L4: the cached policy wants system Location on now - the CONFIG_LOCATION switch (Find my device) or the
     * zone watch, as the sync's own rule (`locationStaysOn`); an error reads as no. */
    private fun policyKeepsLocationOn(): Boolean = try {
        val policy = (cachedPolicy() as? CachedPolicy.Ok)?.policy
        if (policy == null) {
            false
        } else {
            CallPolicyStore.ensureLoaded(this)
            val forcesOn = hardeningPlan(policy.hardening, hardeningManaged(policy.allowlist, CallPolicyStore.state.managed)).forceLocationOn
            locationStaysOn(policy.locationPolicy ?: LEGACY_LOCATION_POLICY, forcesOn, ZoneWatch.zonesOn(this, policy))
        }
    } catch (e: Exception) {
        false
    }

    private val portalListener: () -> Unit = { runOnUiThread { renderPortal() } }

    private var portalRow = com.kidslauncher.mdm.portal.WifiPortalRow.NONE

    /** Design 102 §3.6, §3.8: the connected Wi-Fi's sign-in row ([com.kidslauncher.mdm.portal.wifiRowState]). */
    private fun renderPortal() {
        if (isFinishing || isDestroyed) return
        val app = applicationContext
        // The row reads the budget and the records (files and preferences): off the main thread (code QA L9).
        Thread {
            val row = com.kidslauncher.mdm.portal.PortalRuntime.row(app)
            runOnUiThread { if (!isFinishing && !isDestroyed) showPortal(row) }
        }.start()
    }

    private fun showPortal(row: com.kidslauncher.mdm.portal.WifiPortalRow) {
        portalRow = row
        adapter.setCaptiveSsid(com.kidslauncher.mdm.portal.PortalRuntime.wifi().takeIf { it.captive }?.ssid)
        val (title, sub) = when (row) {
            com.kidslauncher.mdm.portal.WifiPortalRow.SIGN_IN -> R.string.wifi_portal_sign_in to R.string.wifi_portal_sign_in_sub
            com.kidslauncher.mdm.portal.WifiPortalRow.ASK -> R.string.portal_ask to null
            com.kidslauncher.mdm.portal.WifiPortalRow.REVISIT -> R.string.wifi_portal_reopen to R.string.wifi_portal_reopen_sub
            com.kidslauncher.mdm.portal.WifiPortalRow.NONE -> null to null
        }
        binding.wifiNetworksPortal.visibility = if (title == null) View.GONE else View.VISIBLE
        binding.wifiNetworksPortal.isEnabled = row != com.kidslauncher.mdm.portal.WifiPortalRow.ASK
        title?.let { binding.wifiNetworksPortalTitle.setText(it) }
        binding.wifiNetworksPortalSub.visibility = if (sub == null) View.GONE else View.VISIBLE
        sub?.let { binding.wifiNetworksPortalSub.setText(it) }
    }

    private fun portalTapped() {
        when (portalRow) {
            com.kidslauncher.mdm.portal.WifiPortalRow.SIGN_IN ->
                com.kidslauncher.mdm.portal.CaptivePortalEntry.start(this, com.kidslauncher.mdm.portal.PortalKind.SIGN_IN)
            com.kidslauncher.mdm.portal.WifiPortalRow.REVISIT ->
                com.kidslauncher.mdm.portal.CaptivePortalEntry.start(this, com.kidslauncher.mdm.portal.PortalKind.REVISIT)
            else -> Unit
        }
    }

    /** A belt to [onStop], which has already turned Location back off. */
    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        if (locationEnabledByUs) {
            QuickControls.setLocationEnabled(dpm, admin, false)
            locationEnabledByUs = false
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
