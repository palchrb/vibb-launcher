package com.kidslauncher.mdm.badges

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * Counts unread notifications per app for the home-grid badges ([badgeCounts]) and, since handy
 * step 11, applies the notification auto-cancel rule ([nagVerdict]): another app's nag whose tap
 * would open a screen outside the kiosk is cancelled (snoozed past the re-post budget). For those
 * it reads only the package, channel id, category, flags and `number`. Since design 15 one more
 * reader, [ElementDmReader], looks at Element X's notifications only: their tag (the room ID), the
 * messaging person's key and the senders' keys (MXIDs) and the group flag, to learn a phone-book
 * contact's DM room - kept only in CE prefs ([com.kidslauncher.mdm.calls.ElementRoomStore]).
 * Since design 17 [VoipCallReader] reads call-shaped notifications (category, channel, flags and
 * the call intents, never text) for VoIP calls over the PIN lock.
 * Never titles, names or message bodies, and no key, tag or MXID is ever logged or reported
 * ([NotificationRuleRuntime] keeps package + channel counts only). Needs notification-listener
 * access, granted with adb at provisioning (see [BadgeStore.accessGranted]); without it Android
 * never binds this service: no badges, no auto-cancel, no learned rooms. Not direct-boot-aware.
 */
class BadgeListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        NotificationRuleRuntime.attach(this)
        sweep()
    }

    override fun onListenerDisconnected() {
        NotificationRuleRuntime.detach(this)
        BadgeStore.update(emptyMap())
        VoipCallReader.disconnected(this)
    }

    override fun onDestroy() {
        NotificationRuleRuntime.detach(this)
        super.onDestroy()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn?.let {
            ElementDmReader.learn(this, it)
            VoipCallReader.posted(this, it)
            applyRule(it, null)
        }
        recount()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        sbn?.let { VoipCallReader.removed(this, it) }
        recount()
    }

    /** Learns from and applies the rule to every active notification (connect, and after a
     * policy change), then recounts. */
    internal fun sweep() {
        val active = activeOrNull() ?: return
        active.forEach { ElementDmReader.learn(this, it) }
        VoipCallReader.connected(this, active)
        if (NotificationRuleRuntime.policy != null) active.forEach { applyRule(it, active) }
        recount()
    }

    /** Main thread: only the cached [NotificationRuleRuntime.policy], no PackageManager work.
     * [active]: the active notifications if already read (a summary needs its group's children). */
    private fun applyRule(sbn: StatusBarNotification, active: List<StatusBarNotification>?) {
        val facts = try {
            facts(sbn)
        } catch (e: Exception) {
            return
        }
        val policy = NotificationRuleRuntime.policy
        if (nagVerdict(facts, policy) != NagVerdict.Cancel) return
        if (sbn.notification.flags and Notification.FLAG_GROUP_SUMMARY != 0) {
            // Cancelling a summary takes its non-ongoing children with it (qa-11-code #6). The
            // group key stays in memory here - never logged or reported.
            val children = (active ?: activeOrNull() ?: return)
                .filter { it.key != sbn.key && it.groupKey == sbn.groupKey && it.notification.flags and Notification.FLAG_GROUP_SUMMARY == 0 }
                .mapNotNull { child -> runCatching { facts(child) }.getOrNull()?.let { it to nagVerdict(it, policy) } }
            if (!groupSummaryCancellable(children)) return
        }
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
