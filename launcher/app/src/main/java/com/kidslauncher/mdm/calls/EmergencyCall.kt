package com.kidslauncher.mdm.calls

import android.Manifest
import android.app.Activity
import android.app.Dialog
import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.util.Log
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.server.MdmDeviceAdminReceiver
import com.kidslauncher.mdm.server.QuickControls

/**
 * "Emergency call" on the time-rule screen, handy's PIN lock (its keypad, its glance, its VoIP ring card) and the
 * phone book's fail-closed 112 tile. Since design 73 it shows the parent's emergency menu ([emergencyMenu], from
 * `call_policy.emergency_menu`): with one number - the default, 112 - exactly the old confirmation ("Call 112?"),
 * with more the sheet ([EmergencySheet]), where one tap calls. A real emergency number goes through Telecom -
 * emergency calls are exempt from every call restriction; CALL_PHONE is self-granted first (it's only held while
 * calls are managed otherwise). If Telecom still refuses, the platform's emergency dialer opens with the number
 * typed in - an explicit intent to the resolved system component, which kiosk and the PIN lock pin as a lock-task
 * helper (QA 09 #1). Never ACTION_DIAL: the default dialer isn't pinned while calls are managed or a rule blocks
 * calls, so with the kiosk app block it would be blocked. Any other number is placed like a phone-book call, after
 * the same rules ([emergencyRows]); it never falls back to the emergency dialer.
 */
object EmergencyCall {
    /** An emergency number on every GSM phone, also the ones without a SIM. */
    const val NUMBER = "112"

    /** Returns the shown dialog or sheet (the PIN lock sends no VoIP ring screen over it, qa-17b-code #3). Vibb
     * night in both system modes, like the phone book's confirm (design 34 step 5's `KidAlertDialog`; emulator
     * round 2026-10-14 N10: it was a white panel on the lock). [onCalling] runs just before an emergency call and
     * once another call was placed. [locked]: the caller is a locked screen (the PIN lock); the phone's own lock
     * state counts too ([phoneLocked]) - while locked only real emergency numbers call. */
    fun confirm(activity: Activity, locked: Boolean = false, onCalling: () -> Unit = {}): Dialog {
        CallPolicyStore.ensureLoaded(activity)
        val isEmergency = { number: String -> CallSystem.isEmergencyOutgoing(activity, number) }
        val menu = emergencyMenu(CallPolicyStore.emergencyMenuRaw, CallPolicyStore.defaultCc, isEmergency)
        val busy = !secondCallAllowed(OngoingCalls.states, emergency = false)
        val rows = emergencyRows(menu, CallPolicyStore.effectiveState(), busy, isEmergency, locked || phoneLocked(activity))
        return when (val shape = emergencyMenuShape(rows)) {
            is EmergencyMenuShape.Dialog -> AlertDialog.Builder(activity, R.style.KidAlertDialog)
                .setTitle(activity.getString(R.string.calls_confirm_title, shape.row.entry.number))
                .setPositiveButton(R.string.calls_call) { _, _ -> place(activity, emergencyTap(shape.row), onCalling) }
                .setNegativeButton(R.string.calls_cancel, null)
                .show()
            is EmergencyMenuShape.Sheet -> EmergencySheet.show(activity, shape.rows) { tap -> place(activity, tap, onCalling) }
        }
    }

    /** One tap's call: nothing for a blocked row, the emergency path for a real emergency number, the phone book's
     * path for any other (the lock's yield, [onCalling], only once Telecom took it - code QA L1). CALL_PHONE is
     * self-granted first for both (code QA M2: an unmanaged phone may not hold it). */
    fun place(activity: Activity, tap: EmergencyTap, onCalling: () -> Unit) {
        if (tap == EmergencyTap.Nothing) return
        grantCallPhone(activity)
        when (tap) {
            EmergencyTap.Nothing -> Unit
            is EmergencyTap.Emergency -> {
                onCalling()
                callEmergency(activity, tap.number)
            }
            is EmergencyTap.ByRules -> if (CallSystem.placeCall(activity, tap.number)) onCalling()
        }
    }

    /** Whether the phone is locked now: our PIN lock is LOCKED, or Android's keyguard is up (design 59's face, a
     * screen shown over it). Any doubt reads as locked. */
    private fun phoneLocked(activity: Activity): Boolean {
        if (PinLockRuntime.chromeLocked) return true
        return try {
            activity.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked ?: true
        } catch (e: Exception) {
            true
        }
    }

    private fun grantCallPhone(activity: Activity) {
        val dpm = activity.getSystemService(DevicePolicyManager::class.java)
        if (dpm?.isDeviceOwnerApp(activity.packageName) == true) {
            QuickControls.selfGrantPermission(
                activity, dpm, ComponentName(activity, MdmDeviceAdminReceiver::class.java), Manifest.permission.CALL_PHONE,
            )
        }
    }

    /** A real emergency number: Telecom, else the platform's emergency dialer - which only places
     * emergency calls, so opening it counts as a placing (its provisional window and call-state watch,
     * [CallSystem.emergencyPlaced]; placeCall records its own). */
    fun callEmergency(activity: Activity, number: String = NUMBER) {
        grantCallPhone(activity)
        if (CallSystem.placeCall(activity, number)) return
        val openedAtMs = System.currentTimeMillis()
        if (!EmergencyDialer.open(activity, number)) {
            Log.w("EmergencyCall", "Neither Telecom nor the emergency dialer took the emergency call")
        } else if (Emergency.platformConfirms(number, CallSystem.platformEmergency(activity))) {
            CallSystem.emergencyPlaced(activity, openedAtMs)
        }
    }
}
