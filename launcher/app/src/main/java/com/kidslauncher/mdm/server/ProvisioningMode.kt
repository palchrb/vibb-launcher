package com.kidslauncher.mdm.server

/** `DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE` (inlined: JVM-testable). */
const val PROVISIONING_MODE_FULLY_MANAGED_DEVICE = 1

/**
 * QR/NFC provisioning on Android 12+ (fix round 2026-10-06): ManagedProvisioning asks the DPC's
 * `GET_PROVISIONING_MODE` activity which mode to use, offering
 * `EXTRA_PROVISIONING_ALLOWED_PROVISIONING_MODES`; "if the value set to EXTRA_PROVISIONING_MODE is
 * not in the array, provisioning will fail" (AOSP `DevicePolicyManager.java`). We are only ever a
 * fully managed device: that mode when offered (or when no list came with the intent), else
 * `null` - the activity then answers RESULT_CANCELED rather than a mode the platform would refuse.
 */
fun chooseProvisioningMode(allowedModes: List<Int>?): Int? = when {
    allowedModes.isNullOrEmpty() -> PROVISIONING_MODE_FULLY_MANAGED_DEVICE
    PROVISIONING_MODE_FULLY_MANAGED_DEVICE in allowedModes -> PROVISIONING_MODE_FULLY_MANAGED_DEVICE
    else -> null
}
