package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/*
 * Design 19b phase 1: the launcher on Android's power-save allowlist ("battery: Unrestricted"),
 * so the SSE stream keeps working while the phone sleeps. Every status report says whether it is
 * (`battery_optimization_exempt`), and the PIN-gated Settings' "Background connection" row opens
 * Android's own dialog - only when it resolves to an unsuspended system package, which in
 * practice means during "Pause all restrictions" (a managed phone hides and suspends Android's
 * Settings). Nothing else asks: no setup prompt in this phase, nothing on the kid's screens.
 */

/** The activity that takes `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, as resolved. */
data class ResolvedDialog(val packageName: String, val className: String, val system: Boolean, val suspended: Boolean)

/** The dialog to start explicitly, or null: only a system package that isn't ours and isn't
 * suspended (a hidden one doesn't resolve at all). */
fun dialogTarget(resolved: ResolvedDialog?, ownPackage: String): ResolvedDialog? =
    resolved?.takeIf { it.system && !it.suspended && it.packageName != ownPackage }

/** The "Background connection" row's states (19b §2). */
enum class BatteryRow { EXEMPT, CAN_ASK, PAUSE_FIRST, UNKNOWN }

/** [exempt]: `isIgnoringBatteryOptimizations` (null = unreadable); [dialogAvailable]: a
 * [dialogTarget] exists now. */
fun batteryRowState(exempt: Boolean?, dialogAvailable: Boolean): BatteryRow = when {
    exempt == true -> BatteryRow.EXEMPT
    exempt == null -> BatteryRow.UNKNOWN
    dialogAvailable -> BatteryRow.CAN_ASK
    else -> BatteryRow.PAUSE_FIRST
}

object BatteryExemption {
    /** Whether the launcher is exempt now; null when it can't be read (left out of the report). */
    fun exempt(context: Context): Boolean? = runCatching {
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrNull()

    /** Android's dialog for our package as an explicit intent, or null when it doesn't resolve to
     * an unsuspended system package ([dialogTarget]). Started without NEW_TASK, from the activity. */
    fun dialogIntent(context: Context): Intent? = systemPageIntent(
        context,
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")),
    )
}

/** [intent] made explicit to the system activity that takes it, or null when it doesn't resolve to
 * an unsuspended system package that isn't ours ([dialogTarget]) - on a managed phone Android's
 * Settings is hidden and suspended outside "Pause all restrictions". Shared by the battery dialog
 * and the "Modify system settings" page ([WriteSettingsAccess]). */
internal fun systemPageIntent(context: Context, intent: Intent): Intent? = try {
    val info = context.packageManager.resolveActivity(intent, PackageManager.MATCH_SYSTEM_ONLY)?.activityInfo
    val resolved = info?.let {
        val flags = it.applicationInfo.flags
        ResolvedDialog(
            it.packageName,
            it.name,
            system = flags and ApplicationInfo.FLAG_SYSTEM != 0,
            suspended = flags and ApplicationInfo.FLAG_SUSPENDED != 0,
        )
    }
    dialogTarget(resolved, context.packageName)?.let { intent.setClassName(it.packageName, it.className) }
} catch (e: Exception) {
    null
}
