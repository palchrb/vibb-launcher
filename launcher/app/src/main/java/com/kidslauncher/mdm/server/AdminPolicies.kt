package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log

/*
 * The device admin's uses-policies (res/xml/device_admin.xml). Where Android checks an admin's
 * declared policy instead of a device owner's permissions (AOSP without the policy engine's flags:
 * setKeyguardDisabledFeatures, lockNow, the wipe on 14, the password-changed callback), a missing
 * tag is a SecurityException or a callback that never comes. AdminPoliciesTest pins this map, the
 * XML and the calls in the sources against each other.
 *
 * A device owner's DeviceAdminInfo is parsed from the package at boot, so a tag an update adds may
 * only count after the phone restarts once (QA of the launcher wave, Low 4). The calls keep
 * retrying on their own (apply() sets the keyguard features every time; the sync re-checks the
 * lock), and every status report lists what isn't granted yet, so the parent is told to restart.
 */

/** The declared tags with their `DeviceAdminInfo.USES_*` values (literal: Android-free). */
val DECLARED_ADMIN_POLICIES: Map<String, Int> = linkedMapOf(
    "limit-password" to 0, // USES_POLICY_LIMIT_PASSWORD
    "force-lock" to 3, // USES_POLICY_FORCE_LOCK
    "wipe-data" to 4, // USES_POLICY_WIPE_DATA
    "disable-camera" to 8, // USES_POLICY_DISABLE_CAMERA
    "disable-keyguard-features" to 9, // USES_POLICY_DISABLE_KEYGUARD_FEATURES
)

/**
 * Tags Android checks by a device owner's permission from 15 on (`setCameraDisabled`: DPMS's
 * `MANAGE_DEVICE_POLICY_CAMERA` check), so a missing grant there needs no restart (qa-25b-25f L5).
 * On 14 it depends on the policy-engine flag, which an app can't read - listed there.
 */
val PERMISSION_CHECKED_FROM_15: Set<String> = setOf("disable-camera")

/** The declared tags Android hasn't granted yet ([granted]: tag -> `hasGrantedPolicy`, `null` =
 * unreadable, not listed), in a stable order - those that only count after a restart on [sdkInt]. */
fun pendingAdminPolicies(granted: Map<String, Boolean?>, sdkInt: Int = 34): List<String> =
    DECLARED_ADMIN_POLICIES.keys.filter { granted[it] == false && !(sdkInt >= 35 && it in PERMISSION_CHECKED_FROM_15) }

object AdminPolicies {
    private const val LOG_TAG = "AdminPolicies"

    /** The status report's `admin_policies_pending`; `null` when we aren't device owner or it
     * can't be read (left out). */
    fun pending(context: Context): List<String>? = try {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        if (dpm == null || !dpm.isDeviceOwnerApp(context.packageName)) {
            null
        } else {
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            pendingAdminPolicies(
                DECLARED_ADMIN_POLICIES.mapValues { (_, policy) -> dpm.hasGrantedPolicy(admin, policy) },
                android.os.Build.VERSION.SDK_INT,
            )
        }
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the granted admin policies", e)
        null
    }
}
