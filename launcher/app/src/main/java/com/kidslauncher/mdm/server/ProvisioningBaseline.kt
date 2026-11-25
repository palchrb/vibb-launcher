package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.UserManager
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.AppEnforcer.setRestriction

private const val LOG_TAG = "ProvisioningBaseline"

/**
 * A phone set up from the QR is locked down from its provisioning, before it has talked to the server
 * ([PROVISIONING_BASELINE]: no USB debugging, no new users, no safe mode, no user certificates; and no unknown
 * sources). Armed by the two provisioning entries ([PolicyComplianceActivity] and
 * [MdmDeviceAdminReceiver.onProfileProvisioningComplete]); adb's `dpm set-device-owner` runs neither, so the dev
 * emulator's adb provisioning keeps adb. The flag lives in device-protected storage (one boolean, written at once), so
 * it holds before the first unlock too; [AppEnforcer.apply] keeps the set until a policy was ever applied
 * ([provisioningBaselineHolds]), then the server's switches decide. Factory reset is only blocked from the first
 * policy; Android's own lock is never touched. Koble til with a new code needs none of these.
 */
object ProvisioningBaseline {
    private const val PREFS = "provisioning_baseline"
    private const val KEY_ARMED = "armed"

    private fun prefs(context: Context) =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun armed(context: Context): Boolean = try {
        prefs(context).getBoolean(KEY_ARMED, false)
    } catch (e: Exception) {
        false
    }

    /** Whether [AppEnforcer.apply] keeps the baseline now. A failed read of whether a policy was applied keeps it
     * (fail closed: the first policy's own switches lift it at the next apply). */
    fun holds(context: Context): Boolean {
        if (!armed(context)) return false
        val everManaged = try {
            everManaged(LauncherPreferences.mdm().policyEverApplied(), EnrolmentWitness.policyApplied(context))
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read whether a policy was applied - the baseline stays: ${e.javaClass.simpleName}")
            false
        }
        return provisioningBaselineHolds(armed = true, everManaged = everManaged)
    }

    /** At a provisioning: armed and set at once. Never throws; a failed set is retried by the next apply. */
    fun arm(context: Context, source: String) {
        try {
            if (!prefs(context).edit().putBoolean(KEY_ARMED, true).commit()) Log.w(LOG_TAG, "$source: couldn't keep the baseline")
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            if (!dpm.isDeviceOwnerApp(context.packageName)) return
            val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
            for (restriction in PROVISIONING_BASELINE) {
                with(AppEnforcer) { restriction.userManagerKey() }?.let { setRestriction(dpm, admin, it, true) }
            }
            setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, true)
            setRestriction(dpm, admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES_GLOBALLY, true)
            Log.i(LOG_TAG, "$source: baseline set")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "$source: the baseline couldn't be set: ${e.javaClass.simpleName}")
        }
    }
}
