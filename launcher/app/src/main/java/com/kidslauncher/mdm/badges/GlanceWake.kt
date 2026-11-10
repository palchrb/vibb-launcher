package com.kidslauncher.mdm.badges

/*
 * Launcher round 7 §3.1.5: when a posted notification may wake the screen on the lock (the parent's
 * switch and the lock's own gate, `PinLockRuntime.noticeWakeAllowed`, decide after this). Pure,
 * tested in GlanceRowsTest. Only facts, never a name or a text: the trigger carries nothing but "a
 * row came".
 */

/** `NotificationManager.IMPORTANCE_DEFAULT`: a channel that makes a sound - the glance keeps LOW too. */
const val GLANCE_WAKE_IMPORTANCE_MIN = 3

/**
 * The ranking and flag facts of one post: its channel's [importance] (`null` = no ranking found),
 * whether it passes Do Not Disturb ([passesFilter], `Ranking.matchesInterruptionFilter`), whether
 * it is [onlyAlertOnce] (`FLAG_ONLY_ALERT_ONCE`) and whether its key was [known] before this post.
 */
data class GlanceAlertFacts(
    val importance: Int?,
    val passesFilter: Boolean,
    val onlyAlertOnce: Boolean,
    val known: Boolean,
)

/** The post alerts: a channel at DEFAULT or above, through DND, and not an only-alert-once update of
 * a notification already there. No ranking = no alert (fail closed: never wake for what we can't see). */
fun glanceAlerts(facts: GlanceAlertFacts): Boolean =
    facts.importance != null && facts.importance >= GLANCE_WAKE_IMPORTANCE_MIN && facts.passesFilter &&
        !(facts.onlyAlertOnce && facts.known)

/**
 * A kept post wakes: it [alerts], its app is not [suspended] (a time rule's suspension: its
 * notifications still arrive, marked suspended) and the glance page's visible rows gained one -
 * a conversation in [after] that wasn't in [before] (a newer message in a row already shown is an
 * update, not a wake).
 */
fun glanceWakes(alerts: Boolean, suspended: Boolean, before: Set<GlanceConversation>, after: Set<GlanceConversation>): Boolean =
    alerts && !suspended && (after - before).isNotEmpty()

/**
 * A kept post that gains no row buzzes the locked phone all the same (design 82 W3, the user on 2026-11-10: one buzz
 * for every new message; `PinLockRuntime.noticeUpdated` - the buzz alone, never the wake): it [alerts], its app is
 * not [suspended], and a visible row counts more in [after] than it did in [before] ([glanceRowCounts]). The count is
 * the notification's own word that a message more is in it ([glanceCount]: a `MessagingStyle`'s messages from others,
 * else `Notification.number`). A post that counts the same - an edit, the same notification posted again, an app that
 * counts nothing, a `MessagingStyle` that has dropped its oldest line for the new one - is no buzz: a new message is
 * never guessed.
 */
fun glanceRowGrew(alerts: Boolean, suspended: Boolean, before: Map<GlanceConversation, Int>, after: Map<GlanceConversation, Int>): Boolean =
    alerts && !suspended && after.any { (conversation, count) -> before[conversation]?.let { count > it } == true }

/** The visible message rows' counts, by conversation ([glanceRows] without the missed calls). */
fun glanceRowCounts(rows: List<GlanceRow>): Map<GlanceConversation, Int> =
    rows.filterIsInstance<GlanceMessageRow>().associate { it.conversation to it.count }

/** The visible message rows' conversations ([glanceRows] without the missed calls). */
fun glanceRowConversations(rows: List<GlanceRow>): Set<GlanceConversation> =
    rows.mapNotNullTo(mutableSetOf()) { (it as? GlanceMessageRow)?.conversation }
