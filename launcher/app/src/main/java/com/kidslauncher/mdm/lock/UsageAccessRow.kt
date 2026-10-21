package com.kidslauncher.mdm.lock

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.kidslauncher.mdm.server.BatteryExemption
import com.kidslauncher.mdm.server.ResolvedDialog
import com.kidslauncher.mdm.server.dialogTarget

/*
 * Design 57 §6: the PIN-gated Settings' "Calls over the lock" row - Android's Usage access page
 * for Vibb One, opened only when it resolves to an unsuspended system package that isn't ours
 * (`dialogTarget`, as design 19b's battery row), which on a managed phone means during a pause.
 */

/** The row's states (design 57 §6, like `batteryRowState`). */
enum class UsageAccessRow { ON, CAN_OPEN, PAUSE_FIRST, NEEDS_ANDROID_15, UNKNOWN }

/** [granted]: [UsageAccess.read] (null = unreadable); [pageAvailable]: [usageAccessPageChoice]
 * found a page. Below API 35 the access is of no use (design 57 Q-C), so the row says so first. */
fun usageAccessRowState(granted: Boolean?, sdkInt: Int, pageAvailable: Boolean): UsageAccessRow = when {
    sdkInt < VOIP_OVER_LOCK_MIN_SDK -> UsageAccessRow.NEEDS_ANDROID_15
    granted == true -> UsageAccessRow.ON
    granted == null -> UsageAccessRow.UNKNOWN
    pageAvailable -> UsageAccessRow.CAN_OPEN
    else -> UsageAccessRow.PAUSE_FIRST
}

/** Which of Android's two Usage access pages the row opens. */
enum class UsageAccessPage { FOR_PACKAGE, LIST }

data class UsageAccessPageChoice(val page: UsageAccessPage, val target: ResolvedDialog)

/** The `package:<ours>` page first, else the plain list (a `package:` intent never falls back to
 * the list's filter, QA 57-15) - each only as a [dialogTarget]. */
fun usageAccessPageChoice(forPackage: ResolvedDialog?, list: ResolvedDialog?, ownPackage: String): UsageAccessPageChoice? =
    dialogTarget(forPackage, ownPackage)?.let { UsageAccessPageChoice(UsageAccessPage.FOR_PACKAGE, it) }
        ?: dialogTarget(list, ownPackage)?.let { UsageAccessPageChoice(UsageAccessPage.LIST, it) }

object UsageAccessSettings {
    /** Android's page as an explicit intent, or null ([usageAccessPageChoice]). Started without
     * NEW_TASK, from the Settings activity. */
    fun pageIntent(context: Context): Intent? = try {
        val own = context.packageName
        val forPackage = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS, Uri.parse("package:$own"))
        val list = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)
        usageAccessPageChoice(
            BatteryExemption.resolveSystemOnly(context, forPackage),
            BatteryExemption.resolveSystemOnly(context, list),
            own,
        )?.let { choice ->
            val intent = if (choice.page == UsageAccessPage.FOR_PACKAGE) forPackage else list
            intent.setClassName(choice.target.packageName, choice.target.className)
        }
    } catch (e: Exception) {
        null
    }
}
