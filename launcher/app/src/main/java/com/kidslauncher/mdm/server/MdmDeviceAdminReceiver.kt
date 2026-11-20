package com.kidslauncher.mdm.server

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.PersistableBundle
import android.util.Log
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
 * flow isn't available at all. On a phone with Google Play services (Android 13+) a role holder
 * provisions and this callback doesn't come: [PolicyComplianceActivity] delivers the extras then.
 */
class MdmDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(LOG_TAG, "Device admin enabled")
        // The provisioning storm (2026-10-25): the system's package callbacks around becoming device owner fold.
        com.kidslauncher.mdm.ui.home.AppLoadHold.provisioning(android.os.SystemClock.elapsedRealtime())
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.i(LOG_TAG, "Device admin disabled")
        // Our silent rule's Do Not Disturb goes (server 0121). The device owner may already be cleared here, but the admin
        // is still active and the notification listener's access lets the removal through; an uninstall drops the
        // package's rules anyway.
        com.kidslauncher.mdm.timerules.ZenRuleRuntime.release(context)
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
                // Design 59 §3.7: our own reset consumes its one-shot flag; any other change makes the rev unknown.
                com.kidslauncher.mdm.lock.AndroidLock.onPasswordChanged(app)
                AppEnforcer.apply(app, currentPolicyDecision())
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
        // The one apply (2026-11-20): shared with PolicyComplianceActivity, which is the only one that runs when a
        // role holder (CloudDPC on a GMS phone) provisions - applied once, kept for a retry at the next sync.
        val job = QrExtras.deliver(context, bundle, "Provisioning complete") ?: return

        // goAsync() keeps this receiver's process alive long enough for the enroll network call
        // to finish - DeviceAdminReceiver callbacks follow the same short-lived-broadcast-receiver
        // lifecycle as any other BroadcastReceiver, so the process could otherwise be torn down
        // the moment this callback returns (the kept extras still retry at the next sync).
        val pendingResult = goAsync()
        job.invokeOnCompletion { pendingResult.finish() }
    }
}
