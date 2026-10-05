package com.kidslauncher.mdm.calls

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

    /** The first system activity handling one of [ACTIONS], or `null`. */
    fun resolve(context: Context): ComponentName? {
        val pm = context.packageManager
        val candidates = ACTIONS.map { action ->
            try {
                pm.queryIntentActivities(Intent(action), PackageManager.MATCH_SYSTEM_ONLY).firstOrNull()?.activityInfo?.let {
                    ResolvedComponent(it.packageName, it.name, (it.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0)
                }
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't resolve $action", e)
                null
            }
        }
        return chooseEmergencyDialer(candidates)?.let { ComponentName(it.packageName, it.className) }
    }

    /** Opens the emergency dialer with [number] typed in. False if there is none or it failed. */
    fun open(context: Context, number: String): Boolean {
        val component = resolve(context) ?: return false
        for (action in ACTIONS) {
            try {
                context.startActivity(
                    Intent(action, Uri.fromParts("tel", number, null))
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                return true
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Emergency dialer refused $action", e)
            }
        }
        return false
    }
}

/** An activity a helper intent resolved to. */
data class ResolvedComponent(val packageName: String, val className: String, val system: Boolean)

/** The first candidate that is a system app - a third-party app claiming the emergency-dial
 * action is never used (or pinned). Pure, tested in EmergencyDialerTest. */
fun chooseEmergencyDialer(candidates: List<ResolvedComponent?>): ResolvedComponent? =
    candidates.firstOrNull { it != null && it.system }
