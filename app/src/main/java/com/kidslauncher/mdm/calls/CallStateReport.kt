package com.kidslauncher.mdm.calls

import android.content.Context
import android.os.UserManager
import com.kidslauncher.mdm.server.dto.CallState
import com.kidslauncher.mdm.server.systemDialerPackage

/** Status-report capability: this launcher enforces the server's `call_policy` (screening,
 * redirection, in-call backstop, restrictions). The server warns when a managed phone lacks it. */
const val CALL_POLICY_CAPABILITY = "call_policy_v1"

/** What the status report says about calls (`StatusReportRequest.callState`). */
object CallStateReport {
    fun build(context: Context): CallState {
        val state = CallPolicyStore.state
        val userManager = context.getSystemService(UserManager::class.java)
        fun restricted(restriction: String) = try {
            userManager?.hasUserRestriction(restriction) == true
        } catch (e: Exception) {
            false
        }
        val now = System.currentTimeMillis()
        return CallState(
            state = when (state) {
                CallPolicyState.Unmanaged -> "unmanaged"
                is CallPolicyState.Managed -> "managed"
                CallPolicyState.UnknownFailClosed -> "fail_closed"
            },
            dialerRoleHeld = CallSystem.dialerRoleHeld(context),
            redirectionRoleHeld = CallSystem.redirectionRoleHeld(context),
            defaultDialer = CallSystem.defaultDialer(context),
            systemDialer = systemDialerPackage(context),
            smsRestricted = restricted(UserManager.DISALLOW_SMS),
            outgoingRestricted = restricted(UserManager.DISALLOW_OUTGOING_CALLS),
            defaultSmsPackage = CallSystem.defaultSmsPackage(context),
            lastError = CallPrefs.lastError(context),
            lastEmergencyCallAt = CallSystem.isoOrNull(CallSystem.lastEmergencyCallMs(context, now)),
            callbackWindowUntil = CallSystem.isoOrNull(CallSystem.callbackWindowUntil(context, now)),
        )
    }
}
