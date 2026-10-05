package com.kidslauncher.mdm.server

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
import com.kidslauncher.mdm.server.dto.ProvisioningExtras
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val LOG_TAG = "MdmDeviceAdminReceiver"

/**
 * Minimal device-admin/device-owner receiver - enough for `adb shell dpm set-device-owner` to
 * accept this component. [onProfileProvisioningComplete] additionally auto-enrolls when Android's
 * native zero-touch QR flow was used (not available on GrapheneOS, which has no
 * ManagedProvisioning trigger - see kid-phone-server's `handlers::provisioning`) - the same
 * server URL/Tailscale key/enrollment code the in-app QR scanner applies for devices where that
 * flow isn't available at all.
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(LOG_TAG, "Device admin enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(LOG_TAG, "Device admin disabled")
    }

    /**
     * The Android screen lock changed - typically the migration runbook removing an old Android
     * PIN (handy step 10): run the lock check at once instead of waiting for the next sync, so the
     * phone isn't left with no lock at all in between (QA 10 #15). Also when a PIN was set again
     * (handy's lock then switches off - no double lock).
     */
    override fun onPasswordChanged(context: Context, intent: Intent, user: android.os.UserHandle) {
        super.onPasswordChanged(context, intent, user)
        Log.i(LOG_TAG, "Screen lock changed - re-checking handy's lock")
        val pendingResult = goAsync()
        val app = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                AppEnforcer.apply(app, currentPolicyDecision().policy)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Apply after a screen-lock change failed", e)
            } finally {
                pendingResult.finish()
            }
        }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(LOG_TAG, "Provisioning complete")

        val bundle = intent.getParcelableExtra(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
            PersistableBundle::class.java,
        )
        val extras = ProvisioningExtras.fromAdminExtrasBundle(bundle)
        if (extras == null) {
            Log.i(LOG_TAG, "No admin extras bundle in provisioning intent - skipping auto-enroll")
            return
        }

        // goAsync() keeps this receiver's process alive long enough for the enroll network call
        // to finish - a plain unscoped coroutine launch here risks the process being torn down
        // the moment this callback returns, since DeviceAdminReceiver callbacks follow the same
        // short-lived-broadcast-receiver lifecycle as any other BroadcastReceiver.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                applyProvisioningExtras(context, extras)
                    .onSuccess { Log.i(LOG_TAG, "Auto-enrolled from provisioning QR") }
                    .onFailure { e -> Log.w(LOG_TAG, "Auto-enroll from provisioning QR failed", e) }
            } finally {
                pendingResult.finish()
            }
        }
    }
}
