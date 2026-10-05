package com.kidslauncher.mdm.calls

import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log

private const val LOG_TAG = "EmergencyDialer"

/**
 * The platform's emergency dialer (handy step 9, qa-09-design.md #1): the lock screens' fallback
 * when Telecom refuses our own 112 call. Always an explicit intent to the resolved **system**
 * component - which AppEnforcer pins as a lock-task helper - never `ACTION_DIAL` to the default
 * dialer, which isn't pinned while calls are managed or a rule blocks calls, so with
 * `LOCK_TASK_FEATURE_BLOCK_ACTIVITY_START_IN_TASK` it would end on the system's "app blocked" screen.
 */
object EmergencyDialer {
    /** `Intent.ACTION_DIAL_EMERGENCY` (system API) and the older action of com.android.phone's
     * EmergencyDialer - the keyguard's Emergency button uses the latter. Neither is public SDK, so
     * the strings are spelled out; which one a phone handles is a device check. */
    val ACTIONS = listOf("android.intent.action.DIAL_EMERGENCY", "com.android.phone.EmergencyDialer.DIAL")

    /** Every system activity handling one of [ACTIONS], in that order. */
    private fun candidates(context: Context): List<EmergencyCandidate> {
        val pm = context.packageManager
        return ACTIONS.flatMap { action ->
            try {
                pm.queryIntentActivities(Intent(action), PackageManager.MATCH_SYSTEM_ONLY).mapNotNull { info ->
                    info.activityInfo?.let {
                        EmergencyCandidate(
                            action,
                            ResolvedComponent(it.packageName, it.name, (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0),
                        )
                    }
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't resolve $action", e)
                emptyList()
            }
        }
    }

    /**
     * Opens the emergency dialer with [number] typed in. Only a system component that kiosk lets
     * start ([emergencyTargets]: pinned while lock task is on - an intercepted start doesn't
     * throw, it just shows the "app blocked" screen, qa-09-code #2), checked to resolve as an
     * explicit intent; a refused start falls through to the next candidate. False if none worked.
     */
    fun open(context: Context, number: String): Boolean {
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val lockTask = try {
            context.getSystemService(ActivityManager::class.java)?.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } catch (e: Exception) {
            true
        }
        val targets = emergencyTargets(candidates(context), lockTask) { pkg ->
            try {
                dpm?.isLockTaskPermitted(pkg) == true
            } catch (e: Exception) {
                false
            }
        }
        for (target in targets) {
            val intent = Intent(target.action, Uri.fromParts("tel", number, null))
                .setComponent(ComponentName(target.component.packageName, target.component.className))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try {
                if (context.packageManager.resolveActivity(intent, 0) == null) continue
                context.startActivity(intent)
                return true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Emergency dialer ${target.component} refused ${target.action}", e)
            }
        }
        return false
    }
}

/** One handler of one of [EmergencyDialer.ACTIONS]. */
data class EmergencyCandidate(val action: String, val component: ResolvedComponent)

/**
 * Which emergency-dial handlers to try, in order: system apps only (a third-party app claiming the
 * action is never used), and while lock task is on only packages kiosk lets start ([permitted],
 * `DevicePolicyManager.isLockTaskPermitted`) - else the start would land on the "app blocked"
 * screen without an error. Pure, tested in EmergencyDialerTest.
 */
fun emergencyTargets(candidates: List<EmergencyCandidate>, lockTaskActive: Boolean, permitted: (String) -> Boolean): List<EmergencyCandidate> =
    candidates.filter { it.component.system && (!lockTaskActive || permitted(it.component.packageName)) }

/** An activity a helper intent resolved to. */
data class ResolvedComponent(val packageName: String, val className: String, val system: Boolean)

