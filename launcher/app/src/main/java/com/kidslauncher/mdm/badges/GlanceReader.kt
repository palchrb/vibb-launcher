package com.kidslauncher.mdm.badges

import android.app.Notification
import android.app.PendingIntent
import android.app.Person
import android.os.Bundle
import android.os.Parcelable
import android.os.Process
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.MessagePackages
import java.lang.ref.WeakReference

/**
 * The lock's glance page's reader (design 25 §2.2) - the only code that reads names and message
 * texts, and only of the packages the parent ticked ([GlanceStore.policy]): a package that isn't
 * ticked is never read past its package name. Called by [BadgeListenerService] on the listener's
 * main-thread callbacks (posted, removed, ranking update, connect - a sweep of the active
 * notifications - and disconnect), each notification in its own catch: a bad one is skipped, the log
 * names the package and the exception class only (QA Medium 2: nothing here can crash the process).
 *
 * Read, at [GlanceLevel.SENDER] and up: of each MessagingStyle message bundle only its sender
 * (`sender_person`'s name, else `sender`) - never MessagingStyle's parser, which copies the bodies -
 * the group flag and the conversation title, else the notification's title; at every level the
 * key, post time, `when`, shortcut id, the content intent (kept, never read) and the ranking's
 * suspended flag, importance and channel visibility (a missing ranking counts as suspended, QA
 * Medium 1); on a post also whether it passes Do Not Disturb and its only-alert-once flag (round 7
 * §3.1.5, [alerts] - the notice wake learns only that a row came). Only [readTexts], called only at
 * [GlanceLevel.TEXT], reads a text. Never read: the
 * actions, remote inputs, the delete and full-screen intents, the public version, pictures, icons,
 * a person's key or icon, the big text or text lines - and a person's URI with one exception (2026-10-25, the user's
 * decision): of a WhatsApp notification ([GLANCE_TEL_PACKAGES]) the newest sender's URI, handed straight to
 * [glanceTelContact], which uses it only when it is a `tel:` URI and keeps only our phone-book contact's id - the number
 * is never stored, logged or sent. Element X's contact comes from [ElementDmReader.glanceContact] (design 15's facts,
 * read there). LockGlanceGuardTest scans this file.
 *
 * Its one write since design r11 §3: [dismiss], a row the kid swiped away on the lock - only that conversation's kept
 * keys, through the listener's `cancelNotifications`.
 */
object GlanceReader {
    private const val LOG_TAG = "GlanceReader"

    /** `Notification.MessagingStyle.Message`'s bundle keys (hidden constants). */
    private const val SENDER_PERSON = "sender_person"
    private const val SENDER = "sender"
    private const val MESSAGE_TEXT = "text"

    /** What a failed sweep or ranking update is logged as (no package). */
    private const val SWEEP = "a sweep"

    /** The connected listener, for the re-sweep after a policy change. */
    private var service: WeakReference<NotificationListenerService>? = null

    fun connected(service: NotificationListenerService) {
        this.service = WeakReference(service)
        sweep(service)
    }

    fun disconnected() = guarded(SWEEP) {
        service = null
        GlanceStore.clearNotices()
    }

    /** The policy changed (main thread): everything is read again under it. */
    fun resweep() {
        service?.get()?.let(::sweep)
    }

    /** Design r11 §3.3: a message row can be dismissed now - the listener is connected. Checked before a row slides
     * off; without it the row springs back and nothing changes. */
    fun canDismiss(): Boolean = service?.get() != null

    /**
     * Design r11 §3.3: a message row swiped away on the lock - the glance's one cancel. Every kept key of
     * [conversation] posted up to the row's newest ([upToPostTimeMs], [GlanceStore.keysOf]: clearable keys only) is
     * cancelled through the listener, as the shade's own swipe does - NMS cancels a listener's keys with
     * `REASON_LISTENER_CANCEL` and sends their delete intents, so the app learns of it as from the shade (F3); the message
     * stays in the app. Then [GlanceStore.hide] keeps the row gone until a newer post. False with no listener connected
     * or on any exception (logged by class only): the row comes back. No keys left (the app removed them): true.
     */
    fun dismiss(conversation: GlanceConversation, upToPostTimeMs: Long): Boolean {
        var done = false
        guarded(conversation.packageName) {
            val listener = service?.get() ?: return@guarded
            val keys = GlanceStore.keysOf(conversation, upToPostTimeMs)
            if (keys.isNotEmpty()) listener.cancelNotifications(keys.toTypedArray())
            GlanceStore.hide(conversation, upToPostTimeMs)
            done = true
        }
        return done
    }

    fun posted(service: NotificationListenerService, sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?) =
        guarded(sbn.packageName) {
            val known = GlanceStore.has(sbn.key)
            val entry = read(service, sbn, rankingMap)
            if (entry == null) GlanceStore.remove(sbn.key) else GlanceStore.putPosted(entry.first, entry.second, alerts(sbn, rankingMap, known))
        }

    /** Round 7 §3.1.5: the post alerts - its channel at DEFAULT or above, through Do Not Disturb, and
     * not an only-alert-once update of a key already kept ([glanceAlerts]; no ranking = no alert). */
    private fun alerts(sbn: StatusBarNotification, rankingMap: NotificationListenerService.RankingMap?, known: Boolean): Boolean {
        val ranking = NotificationListenerService.Ranking()
        val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
        return glanceAlerts(
            GlanceAlertFacts(
                importance = if (ranked) ranking.importance else null,
                passesFilter = ranked && ranking.matchesInterruptionFilter(),
                onlyAlertOnce = (sbn.notification?.flags ?: 0) and Notification.FLAG_ONLY_ALERT_ONCE != 0,
                known = known,
            ),
        )
    }

    fun removed(sbn: StatusBarNotification) = guarded(sbn.packageName) { GlanceStore.remove(sbn.key) }

    /** Suspension is a ranking matter for a listener targeting P+ (§2.2): the flag of every kept
     * notification is read again. */
    fun rankingUpdate(rankingMap: NotificationListenerService.RankingMap?) = guarded(SWEEP) {
        val ranking = NotificationListenerService.Ranking()
        GlanceStore.updateSuspended { key -> glanceSuspended(rankingMap?.getRanking(key, ranking) == true, ranking.isSuspended) }
    }

    private fun sweep(service: NotificationListenerService) = guarded(SWEEP) {
        val active = service.activeNotifications.orEmpty()
        val rankingMap = service.currentRanking
        val entries = mutableListOf<Pair<GlanceNotice, PendingIntent?>>()
        for (sbn in active) guarded(sbn.packageName) { read(service, sbn, rankingMap)?.let(entries::add) }
        GlanceStore.replaceAll(entries)
    }

    private fun read(
        service: NotificationListenerService,
        sbn: StatusBarNotification,
        rankingMap: NotificationListenerService.RankingMap?,
    ): Pair<GlanceNotice, PendingIntent?>? {
        val policy = GlanceStore.policy
        // Not ticked (or another user's): never read past the package name.
        if (sbn.packageName !in policy.apps || sbn.user != Process.myUserHandle()) return null
        val n = sbn.notification ?: return null
        val ranking = NotificationListenerService.Ranking()
        val ranked = rankingMap?.getRanking(sbn.key, ranking) == true
        val candidate = glanceCandidate(
            GlanceCandidateFacts(
                packageName = sbn.packageName,
                groupSummary = n.flags and Notification.FLAG_GROUP_SUMMARY != 0,
                ongoing = sbn.isOngoing,
                clearable = sbn.isClearable,
                media = isMediaNotification(n),
                importance = if (ranked) ranking.importance else null,
                category = n.category,
            ),
            service.packageName,
        )
        if (!candidate) return null
        val secret = n.visibility == Notification.VISIBILITY_SECRET ||
            (ranked && ranking.channel?.lockscreenVisibility == Notification.VISIBILITY_SECRET)
        val extras = n.extras ?: Bundle.EMPTY
        val messages = extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java).orEmpty()
            .filterIsInstance<Bundle>()
        // The kid's own messages have no sender: they are not counted, and never "the newest".
        val fromOthers = messages.filter { it.containsKey(SENDER_PERSON) || it.containsKey(SENDER) }
        val level = glanceLevel(ticked = true, secret = secret, showText = policy.showsText(sbn.packageName), messaging = messages.isNotEmpty())
        val newest = fromOthers.lastOrNull()
        val group = extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION)
        val sender = if (level >= GlanceLevel.SENDER) newest?.let(::senderOf) else null
        val conversationTitle = if (level >= GlanceLevel.SENDER) {
            cleanGlanceText(extras.getCharSequence(Notification.EXTRA_CONVERSATION_TITLE), GLANCE_TITLE_MAX)
        } else {
            null
        }
        val title = if (level >= GlanceLevel.SENDER) {
            glanceTitle(
                group = group,
                conversationTitle = conversationTitle,
                newestSender = sender,
                title = cleanGlanceText(extras.getCharSequence(Notification.EXTRA_TITLE), GLANCE_TITLE_MAX),
            )
        } else {
            null
        }
        val text = if (level == GlanceLevel.TEXT) readTexts(extras, newest, messages.isNotEmpty(), group, sender) else null
        val notice = GlanceNotice(
            key = sbn.key,
            packageName = sbn.packageName,
            conversationKey = n.shortcutId?.takeIf { it.isNotEmpty() } ?: sbn.key,
            level = level,
            title = title,
            text = text,
            count = glanceCount(messages.size, fromOthers.size, n.number),
            whenMs = n.`when`,
            postTimeMs = sbn.postTime,
            suspended = glanceSuspended(ranked, ranking.isSuspended),
            missedCall = glanceMissedCall(n.category),
            shape = glanceShape(messaging = messages.isNotEmpty(), group = group, conversationTitle = conversationTitle),
            sender = sender,
            contactId = if (level >= GlanceLevel.SENDER) contactOf(service, sbn, newest) else null,
        )
        return notice to n.contentIntent
    }

    /** The newest sender's phone-book contact (2026-10-25): Element X by design 15's facts ([ElementDmReader]), WhatsApp
     * by the newest sender person's `tel:` URI ([glanceTelContact] - the one URI read); no other app. */
    private fun contactOf(service: NotificationListenerService, sbn: StatusBarNotification, newest: Bundle?): Long? = when (sbn.packageName) {
        MessagePackages.ELEMENT_X -> ElementDmReader.glanceContact(service, sbn)
        in GLANCE_TEL_PACKAGES -> glanceTelContact(newest?.getParcelable(SENDER_PERSON, Person::class.java)?.uri, CallPolicyStore.state)
        else -> null
    }

    /** A message bundle's sender: `sender_person`'s name, else `sender` - cleaned. */
    private fun senderOf(message: Bundle): String? =
        cleanGlanceText(message.getParcelable(SENDER_PERSON, Person::class.java)?.name ?: message.getCharSequence(SENDER), GLANCE_TITLE_MAX)

    /**
     * The one reader of a text (only at [GlanceLevel.TEXT]): the newest message from another sender
     * (MessagingStyle; a group's gets "Sender: " in front), else the notification's text.
     */
    private fun readTexts(extras: Bundle, newest: Bundle?, messaging: Boolean, group: Boolean, sender: String?): String? =
        if (messaging) {
            glanceGroupText(group, sender, cleanGlanceText(newest?.getCharSequence(MESSAGE_TEXT), GLANCE_TEXT_MAX))
        } else {
            cleanGlanceText(extras.getCharSequence(Notification.EXTRA_TEXT), GLANCE_TEXT_MAX)
        }

    /** Every entry point and every notification: caught, logged by package ([source]) and
     * exception class only. */
    private inline fun guarded(source: String, work: () -> Unit) {
        try {
            work()
        } catch (e: Throwable) {
            Log.w(LOG_TAG, "Glance: skipped $source: ${e.javaClass.simpleName}")
        }
    }
}
