package com.kidslauncher.mdm.ui.quickcontrols

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityQuickControlsBinding
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControlFeature
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * The kid-facing replacement for Android's native Quick Settings shade - reachable by swiping
 * left from [com.kidslauncher.mdm.ui.HomeActivity] (see its `GestureDetector`). Only shows the
 * switches an admin turned on for this device via [com.kidslauncher.mdm.server.dto.PolicyResponse.quickControlsMask],
 * and only calls direct Device-Owner APIs ([QuickControls]) - never Android's own QS tiles or
 * `Settings.Panel.*` intents - so there's no surface a third-party app's own tile could bypass,
 * the exact problem this screen exists to route around.
 */
class QuickControlsActivity : UIObjectActivity() {
    private lateinit var binding: ActivityQuickControlsBinding
    private lateinit var dpm: DevicePolicyManager
    private lateinit var admin: ComponentName

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityQuickControlsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setTitle(R.string.quick_controls_title)
        setSupportActionBar(binding.quickControlsAppbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        admin = ComponentName(this, MdmDeviceAdminReceiver::class.java)

        // Not device owner (e.g. not provisioned yet) - nothing here can work, and every row
        // stays hidden below since the mask defaults to 0 with no cached policy.
        if (!dpm.isDeviceOwnerApp(packageName)) {
            binding.quickControlsEmptyMessage.visibility = android.view.View.VISIBLE
            return
        }

        val mask = (cachedPolicy() as? CachedPolicy.Ok)?.policy?.quickControlsMask ?: 0

        var anyShown = false

        if (mask and QuickControlFeature.WIFI != 0L) {
            anyShown = true
            binding.quickControlsWifiRow.visibility = android.view.View.VISIBLE
            val wifiOn = QuickControls.isWifiEnabled(this)
            binding.quickControlsWifiSwitch.isChecked = wifiOn
            setManageRowEnabled(binding.quickControlsWifiManage, wifiOn)
            binding.quickControlsWifiSwitch.setOnCheckedChangeListener { _, checked ->
                QuickControls.setWifiEnabled(this, checked)
                setManageRowEnabled(binding.quickControlsWifiManage, checked)
            }
            binding.quickControlsWifiManage.setOnClickListener {
                if (binding.quickControlsWifiManage.isEnabled) {
                    startActivity(Intent(this, WifiNetworksActivity::class.java))
                }
            }
        }

        if (mask and QuickControlFeature.BLUETOOTH != 0L) {
            anyShown = true
            binding.quickControlsBluetoothRow.visibility = android.view.View.VISIBLE
            val bluetoothOn = QuickControls.isBluetoothEnabled()
            binding.quickControlsBluetoothSwitch.isChecked = bluetoothOn
            setManageRowEnabled(binding.quickControlsBluetoothManage, bluetoothOn)
            binding.quickControlsBluetoothSwitch.setOnCheckedChangeListener { _, checked ->
                QuickControls.setBluetoothEnabled(this, dpm, admin, checked)
                setManageRowEnabled(binding.quickControlsBluetoothManage, checked)
            }
            binding.quickControlsBluetoothManage.setOnClickListener {
                if (binding.quickControlsBluetoothManage.isEnabled) {
                    startActivity(Intent(this, BluetoothDevicesActivity::class.java))
                }
            }
        }

        if (mask and QuickControlFeature.BRIGHTNESS != 0L) {
            anyShown = true
            binding.quickControlsBrightnessRow.visibility = android.view.View.VISIBLE
            binding.quickControlsBrightnessSeekbar.progress = QuickControls.currentBrightness(this)
            binding.quickControlsBrightnessSeekbar.setOnSeekBarChangeListener(
                object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(
                        seekBar: android.widget.SeekBar?,
                        progress: Int,
                        fromUser: Boolean,
                    ) {
                        if (fromUser) QuickControls.setBrightness(dpm, admin, progress)
                    }

                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                },
            )
        }

        binding.quickControlsEmptyMessage.visibility =
            if (anyShown) android.view.View.GONE else android.view.View.VISIBLE
    }

    /** [android.widget.TextView.isEnabled] alone doesn't change its look without a state-aware
     * color, so this dims the "manage" affordance visibly when its radio is off. */
    private fun setManageRowEnabled(view: android.widget.TextView, enabled: Boolean) {
        view.isEnabled = enabled
        view.alpha = if (enabled) 1.0f else 0.4f
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }
}
