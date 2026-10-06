package com.kidslauncher.mdm.badges

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Counts unread notifications per app for the home-grid badges ([badgeCounts]) and, since handy
 * step 11, applies the notification auto-cancel rule ([nagVerdict]): another app's nag whose tap
 * would open a screen outside the kiosk is cancelled (snoozed past the re-post budget). Reads only
 * the package, channel id, category, flags and `number` - never titles or text - and never logs a
 * key or tag ([NotificationRuleRuntime] keeps package + channel counts only). Needs
 * notification-listener access, granted with adb at provisioning (see
 * [BadgeStore.accessGranted]); without it Android never binds this service: no badges, no
 * auto-cancel. Not direct-boot-aware.
 */
class BadgeListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationRuleRuntime.attach(this)
        sweep()
    }

    override fun onListenerDisconnected() {
        NotificationRuleRuntime.detach(this)
        BadgeStore.update(emptyMap())
    }

    override fun onDestroy() {
        NotificationRuleRuntime.detach(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let { applyRule(it) }
        recount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    /** Applies the rule to every active notification (connect, and after a policy change), then recounts. */
    internal fun sweep() {
        val active = activeOrNull() ?: return
        if (NotificationRuleRuntime.policy != null) active.forEach { applyRule(it) }
        recount()
    }

    /** Main thread: only the cached [NotificationRuleRuntime.policy], no PackageManager work. */
    private fun applyRule(sbn: StatusBarNotification) {
        val facts = try {
            facts(sbn)
        } catch (e: Exception) {
            return
        }
        if (nagVerdict(facts, NotificationRuleRuntime.policy) != NagVerdict.Cancel) return
        try {
            when (NotificationRuleRuntime.act(facts)) {
                NagAction.CANCEL -> cancelNotification(sbn.key)
                NagAction.SNOOZE -> snoozeNotification(sbn.key, NAG_SNOOZE_MS)
            }
        } catch (e: Exception) {
            // Unbound in between, or the platform refused (lifetime-extended). Never the key in the log.
            Log.w("BadgeListener", "Couldn't remove a notification of ${facts.packageName}: ${e.javaClass.simpleName}")
        }
    }

    private fun facts(sbn: StatusBarNotification): NotificationFacts {
        val notification = sbn.notification
        return NotificationFacts(
            packageName = sbn.packageName,
            // The app's own channel - never the Ranking's conversation channel or a shortcut id.
            channelId = notification.channelId,
            category = notification.category,
            ongoing = sbn.isOngoing,
            clearable = sbn.isClearable,
            fullScreenIntent = notification.fullScreenIntent != null,
            insistent = notification.flags and Notification.FLAG_INSISTENT != 0,
        )
    }

    private fun activeOrNull(): List<StatusBarNotification>? = try {
        activeNotifications.orEmpty().toList()
    } catch (e: Exception) {
        // Thrown when called before the listener is connected or after it was unbound.
        Log.w("BadgeListener", "Couldn't read active notifications", e)
        null
    }

    private fun recount() {
        val active = activeOrNull() ?: return
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
