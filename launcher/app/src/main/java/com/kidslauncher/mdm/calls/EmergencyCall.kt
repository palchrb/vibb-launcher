package com.kidslauncher.mdm.calls

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls

/**
 * "Emergency call" on the time-rule screen and on handy's PIN lock (shared since step 10): a
 * confirmation ("Call 112?"), then 112 through Telecom - emergency calls are exempt from every
 * call restriction; CALL_PHONE is self-granted first (it's only held while calls are managed
 * otherwise). If Telecom still refuses, the platform's emergency dialer opens with 112 typed in -
 * an explicit intent to the resolved system component, which kiosk and the PIN lock pin as a
 * lock-task helper (QA 09 #1). Never ACTION_DIAL: the default dialer isn't pinned while calls are
 * managed or a rule blocks calls, so with the kiosk app block it would be blocked.
 */
object EmergencyCall {
    /** An emergency number on every GSM phone, also the ones without a SIM. */
    const val NUMBER = "112"

    /** Returns the shown dialog (the PIN lock sends no VoIP ring screen over it, qa-17b-code #3). */
    fun confirm(activity: Activity, onCalling: () -> Unit = {}): AlertDialog =
        AlertDialog.Builder(activity, R.style.AlertDialogCustom)
            .setTitle(activity.getString(R.string.calls_confirm_title, NUMBER))
            .setPositiveButton(R.string.calls_call) { _, _ ->
                onCalling()
                call(activity)
            }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()

    fun call(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(activity.packageName) == true) {
            QuickControls.selfGrantPermission(
                activity, dpm, ComponentName(activity, MdmDeviceAdminReceiver::class.java), Manifest.permission.CALL_PHONE,
            )
        }
        if (CallSystem.placeCall(activity, NUMBER)) return
        if (!EmergencyDialer.open(activity, NUMBER)) {
            Log.w("EmergencyCall", "Neither Telecom nor the emergency dialer took the emergency call")
        }
    }
}
