package com.kidslauncher.mdm.portal

import android.content.Context
import android.content.Intent
import android.net.CaptivePortal
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Bundle
import androidx.appcompat.app.AlertDialog
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityPortalEntryBinding
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.ParentPinDialog
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Design 102 §3.2: Vibb's handler of Android's Wi-Fi sign-in intent, in the main process - and the entry of Vibb's own
 * ways in (the Wi-Fi page, Home's line, the reopen), started by an explicit intent from this process.
 *
 * Its guard is the manifest's `android:permission="android.permission.NETWORK_STACK"`: system_server's start passes,
 * another app's is refused (measured, §1b R2/R4); our own uid passes too. `getLaunchedFromUid()` is not used (it is -1
 * for the system's start, R3). The component is enabled only while Part A is on ([PortalRuntime.setEntryEnabled]).
 *
 * The order ([portalApprove]): the network, Vibb's https check, then the parent code (on `ParentPin` with the one-shot
 * [ParentPinEntry.WIFI_SIGNIN], asked here - never in `:portal`), a grant or a pause, the budget, one window at a time.
 * Then the opening is charged, the snapshot written and [PortalWindowActivity] started. Cancel tells Android
 * (`reportCaptivePortalDismissed`), so its notification stays. Closes on `onStop`: a parent handing the phone back
 * leaves no keypad a kid can finish.
 */
class CaptivePortalEntry : UIObjectActivity() {
    private lateinit var binding: ActivityPortalEntryBinding
    private var portal: CaptivePortal? = null
    private var network: Network? = null
    private var url: String? = null
    private var userAgent: String? = null
    private var kind = PortalKind.SIGN_IN
    private var start = "system"
    private var record: RevisitRecord? = null
    private var checked: Pair<Boolean, Boolean>? = null
    private var dialog: AlertDialog? = null
    private var leaving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPortalEntryBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        binding.portalEntryClose.setOnClickListener { cancel() }
        if (intent.action == ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN) {
            portal = intent.getParcelableExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL, CaptivePortal::class.java)
            network = intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK, Network::class.java)
            url = intent.getStringExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL_URL)
            userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
        } else {
            kind = PortalKind.of(intent.getStringExtra(EXTRA_KIND)) ?: PortalKind.SIGN_IN
            start = if (kind == PortalKind.REVISIT) "revisit" else "vibb"
            network = PortalRuntime.wifi().network
            if (kind == PortalKind.REVISIT) {
                record = PortalRuntime.revisitRecord(this)
                url = record?.url
            } else {
                url = ANDROID_PROBE_URL
            }
        }
        lifecycleScope.launch { decide(null) }
    }

    override fun onStop() {
        dialog?.dismiss()
        dialog = null
        super.onStop()
        if (!isChangingConfigurations && !leaving) finish()
    }

    /** The network as the order wants it: a sign-in on a Wi-Fi that is `CAPTIVE_PORTAL` and not `VALIDATED`, a reopen
     * on the network of today's record. */
    private fun networkOk(): Boolean {
        val net = network ?: return false
        if (kind == PortalKind.REVISIT) return record != null && url != null
        val caps = getSystemService(ConnectivityManager::class.java).getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) &&
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private suspend fun decide(pin: PinOutcome?) {
        val (networkOk, passes) = checked ?: withContext(Dispatchers.IO) {
            val ok = networkOk()
            // Vibb's own check, afresh at each opening (R2-M3): a pass means the network works and no code is asked.
            val works = ok && kind == PortalKind.SIGN_IN && network?.let { net -> PortalCheck(net, PortalRuntime.serverCheckUrl()).passes() } == true
            ok to works
        }.also { checked = it }
        if (isFinishing || isDestroyed) return
        val input = withContext(Dispatchers.IO) { PortalRuntime.approveInput(applicationContext, kind, networkOk, passes) }
        when (val approval = portalApprove(input, pin)) {
            Approval.Forward -> {
                leaving = true
                startActivity(Intent(this, PortalWindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
                finish()
            }
            Approval.Keypad -> askCode()
            is Approval.Open -> open(approval.approved)
            is Approval.Refuse -> refuse(approval.reason)
        }
    }

    /** The parent code's keypad (R3-M4): the shared lockout counts these tries, every try is a PIN event. */
    private fun askCode() {
        binding.portalEntryMessage.setText(R.string.portal_code_needed)
        dialog?.dismiss()
        dialog = ParentPinDialog.show(
            this, lifecycleScope, ParentPinEntry.WIFI_SIGNIN, R.string.portal_code_title, R.string.portal_no_code,
            onCancel = { cancel() },
        ) {
            dialog = null
            lifecycleScope.launch { decide(PinOutcome.OK) }
        }
    }

    private fun refuse(reason: String) {
        if (reason == "works") portal?.reportCaptivePortalDismissed()
        binding.portalEntryMessage.setText(
            when (reason) {
                "works" -> R.string.portal_works
                "budget" -> R.string.portal_ask
                "no_code" -> R.string.portal_no_code
                "network" -> R.string.portal_no_sign_in
                else -> R.string.portal_not_now
            },
        )
    }

    /** Charged before the start, so a killed window still counts; then the snapshot and the window. */
    private fun open(approved: String) {
        val net = network ?: return refuse("network")
        val first = url ?: return refuse("network")
        lifecycleScope.launch {
            val ssid = PortalRuntime.wifi().takeIf { it.network == net }?.ssid
            withContext(Dispatchers.IO) {
                PortalRuntime.charge(applicationContext, approved)
                PortalRuntime.writeSnapshot(
                    applicationContext,
                    PortalRuntime.snapshotFor(applicationContext, kind, start, approved, first, record?.site, ssid ?: record?.ssid, userAgent),
                )
            }
            if (isFinishing || isDestroyed) return@launch
            leaving = true
            startActivity(
                Intent(this@CaptivePortalEntry, PortalWindowActivity::class.java)
                    .putExtra(ConnectivityManager.EXTRA_NETWORK, net)
                    .apply { portal?.let { putExtra(ConnectivityManager.EXTRA_CAPTIVE_PORTAL, it) } },
            )
            finish()
        }
    }

    /** Cancel: nothing opened; Android re-probes and keeps its notification. */
    private fun cancel() {
        portal?.reportCaptivePortalDismissed()
        finish()
    }

    companion object {
        const val EXTRA_KIND = "com.kidslauncher.mdm.portal.KIND"

        /** `ConnectivityManager.EXTRA_CAPTIVE_PORTAL_USER_AGENT`, a system API's name (M7). */
        private const val EXTRA_USER_AGENT = "android.net.extra.CAPTIVE_PORTAL_USER_AGENT"

        /** Vibb's own ways in (§3.6): a sign-in from the Wi-Fi page or Home, or the reopen (§3.8). */
        fun start(context: Context, kind: PortalKind) {
            try {
                context.startActivity(Intent(context, CaptivePortalEntry::class.java).putExtra(EXTRA_KIND, kind.wire))
            } catch (e: android.content.ActivityNotFoundException) {
                // Part A turned off meanwhile: the entry is disabled.
            }
        }
    }
}
