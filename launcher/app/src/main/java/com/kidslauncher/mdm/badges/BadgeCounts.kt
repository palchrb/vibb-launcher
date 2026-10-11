package com.kidslauncher.mdm.badges

/*
 * Unread counts per app from the active notifications (design 05-ui-photos-i18n.md) - pure, tested
 * in BadgeCountsTest. BadgeListenerService feeds it.
 */

/** What we need from a `StatusBarNotification`. [number] is `Notification.number`; [mediaSession]:
 * it carries `Notification.EXTRA_MEDIA_SESSION` (a player's media notification). */
data class NotificationInfo(
    val packageName: String,
    val ongoing: Boolean,
    val clearable: Boolean,
    val groupSummary: Boolean,
    val number: Int,
    val mediaSession: Boolean = false,
)

/**
 * Per package: the sum over its notifications of `number` when the app set one (> 0), else 1.
 * Ongoing or non-clearable notifications (calls, our own VPN/sync notices), media notifications
 * (a paused player's is neither ongoing nor unread - design 21 QA #6: the Vibb music badge is its
 * "N nye" notification's `number` alone) and group summaries (which repeat their children) don't
 * count, nor does anything from [ownPackage]. Packages with nothing left are absent.
 */
fun badgeCounts(notifications: List<NotificationInfo>, ownPackage: String): Map<String, Int> =
    notifications
        .filter { !it.ongoing && it.clearable && !it.groupSummary && !it.mediaSession && it.packageName != ownPackage }
        .groupBy { it.packageName }
        .mapValues { (_, list) -> list.sumOf { if (it.number > 0) it.number else 1 } }
        .filterValues { it > 0 }
