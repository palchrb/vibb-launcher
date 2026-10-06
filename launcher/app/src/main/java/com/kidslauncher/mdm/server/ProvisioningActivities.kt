package com.kidslauncher.mdm.server

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Bundle
import android.os.PersistableBundle
import android.util.Log

private const val LOG_TAG = "Provisioning"

/**
 * `android.app.action.GET_PROVISIONING_MODE` - required of a DPC for QR/NFC device-owner
 * provisioning since Android 12 ("to support Android S and later, admin apps must implement
 * activities with intent filters for ACTION_GET_PROVISIONING_MODE and
 * ACTION_ADMIN_POLICY_COMPLIANCE", AOSP `DevicePolicyManager.java`; manifest: exported, guarded by
 * `BIND_DEVICE_ADMIN`). No UI: answers [chooseProvisioningMode] at once and hands the QR's admin
 * extras on (they reach the compliance step and `onProfileProvisioningComplete`, which enrolls).
 * [needs device test: QR provisioning on a stock GMS phone]
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val allowed = intent.getIntegerArrayListExtra(DevicePolicyManager.EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES)
        val mode = chooseProvisioningMode(allowed)
        if (mode == null) {
            Log.w(LOG_TAG, "Fully managed mode not offered ($allowed) - cancelling provisioning")
            setResult(RESULT_CANCELED)
        } else {
            val result = Intent().putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, mode)
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
                ?.let { result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, it) }
            setResult(RESULT_OK, result)
        }
        finish()
    }
}

/**
 * `android.app.action.ADMIN_POLICY_COMPLIANCE` - the second activity Android 12+ requires (see
 * [ProvisioningModeActivity]). Nothing to show or accept: the policy arrives with the first sync
 * after enrolling, so it finishes RESULT_OK at once.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_OK)
        finish()
    }
}
