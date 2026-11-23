package com.kidslauncher.mdm.server

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.core.app.NotificationManagerCompat

/** The status report's `notification_listeners` ([notificationListenersReport]), read now: Android's enabled listeners,
 * each marked off while Vibb has its package hidden or suspended. `null` when it can't be read (left out). */
object NotificationListenersReader {
    private const val TAG = "NotificationListeners"

    fun read(context: Context): List<NotificationListenerReport>? = try {
        val enabled = NotificationManagerCompat.getEnabledListenerPackages(context)
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val owner = dpm?.isDeviceOwnerApp(context.packageName) == true
        val admin = ComponentName(context, MdmDeviceAdminReceiver::class.java)
        val pm = context.packageManager
        notificationListenersReport(enabled, context.packageName) { pkg ->
            val hidden = owner && runCatching { dpm!!.isApplicationHidden(admin, pkg) }.getOrDefault(false)
            hidden || runCatching { pm.isPackageSuspended(pkg) }.getOrDefault(false)
        }
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the notification listeners: ${e.javaClass.simpleName}")
        null
    }
}
