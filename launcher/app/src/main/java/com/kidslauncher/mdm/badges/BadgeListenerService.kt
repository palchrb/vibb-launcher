package com.kidslauncher.mdm.badges

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Counts unread notifications per app for the home-grid badges ([badgeCounts]). Reads only the
 * package, flags and `number` - never titles or text - and keeps nothing but the counts, in
 * memory ([BadgeStore]). Needs notification-listener access, granted with adb at provisioning
 * (see [BadgeStore.accessGranted]); without it Android never binds this service and Home has no
 * badges. Not direct-boot-aware.
 */
class BadgeListenerService : NotificationListenerService() {

    override fun onListenerConnected() = recount()

    override fun onListenerDisconnected() = BadgeStore.update(emptyMap())

    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val active = try {
            activeNotifications.orEmpty()
        } catch (e: Exception) {
            // Thrown when called before the listener is connected or after it was unbound.
            Log.w("BadgeListener", "Couldn't read active notifications", e)
            return
        }
        BadgeStore.update(
            badgeCounts(
                active.map {
                    NotificationInfo(
                        packageName = it.packageName,
                        ongoing = it.isOngoing,
                        clearable = it.isClearable,
                        groupSummary = it.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                        number = it.notification.number,
                    )
                },
                ownPackage = packageName,
            )
        )
    }
}
