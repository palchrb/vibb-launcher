package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/** The status report's `notification_listeners` ([notificationListenersReport]), read now: Android's enabled listeners,
 * each marked off while Vibb has its package hidden, and system when it came with the phone. `null` when it can't be
 * read (left out). */
object NotificationListenersReader {
    private const val TAG = "NotificationListeners"

    fun read(context: Context): List<NotificationListenerReport>? = try {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val owner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        val pm = context.packageManager
        notificationListenersReport(
            enabled,
            context.packageName,
            hidden = { pkg -> owner && runCatching { dpm!!.isApplicationHidden(admin, pkg) }.getOrDefault(false) },
            system = { pkg ->
                val flags = runCatching {
                    pm.getApplicationInfo(
                        pkg, PackageManager.ApplicationInfoFlags.of(PackageManager.MATCH_UNINSTALLED_PACKAGES.toLong()),
                    ).flags
                }.getOrDefault(0)
                flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            },
        )
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the notification listeners: ${e.javaClass.simpleName}")
        null
    }
}
