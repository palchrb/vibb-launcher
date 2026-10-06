package com.kidslauncher.mdm.calls

/*
 * Our missed-call notification (design 12-missed-calls.md, "Decisions after QA review" on top) -
 * pure, tested in MissedCallNoticeTest. MissedCallNotifier reads the call log, posts and clears.
 *
 * As the default dialer we have a receiver for Telecom's SHOW_MISSED_CALLS_NOTIFICATION, so
 * Telecom sends us that broadcast instead of posting its own notification (whose tap opens the
 * call log - nothing, or the system dialer's full UI). The broadcast is only a trigger: what the
 * notification says comes from the call log alone (QA #2).
 */

/**
 * What our notification says: [count] missed calls from the contacts [names] (newest first).
 * [contactNumber] only when they all came from one contact - the tap then opens that contact's
 * sheet. [lastAtMs] is the newest call's time, [newestId] its call-log `_id` (a newer one alerts).
 */
data class MissedCallNotice(
    val count: Int,
    val names: List<String>,
    val contactNumber: String?,
    val lastAtMs: Long,
    val newestId: Long,
)

/**
 * The notification's content, `null` = nothing to show. Counted: missed calls from [viewContacts]
 * (`phoneBookView(effectiveState())` - contacts the kid sees and may call back; never inbound-only
 * ones, QA #2) that still count for the badges ([unseenMissedCalls]: after the contact's seen mark
 * and after any call with it) **and** are still unread in the call log (`new = 1`) with an `_id` up
 * to [unreadUpToId] - the newest unread missed call when this pass started, so everything shown
 * can later be marked read exactly ([NoticeStep.Clear], [MissedCallNotifier.dismiss]). A dismissed
 * notification is marked read, so it never comes back; the badges keep their own seen model.
 */
fun missedCallNotice(
    log: List<CallLogEntry>,
    viewContacts: List<RuleContact>,
    defaultCc: String,
    seenMs: Map<String, Long>,
    unreadUpToId: Long,
): MissedCallNotice? {
    val perContact = unseenMissedCalls(log, viewContacts, defaultCc, seenMs)
        .mapValues { (_, missed) -> missed.filter { it.unread && it.id in 1..unreadUpToId } }
        .filterValues { it.isNotEmpty() }
    if (perContact.isEmpty()) return null
    val names = viewContacts.associate { it.number to it.name }
    val newestFirst = perContact.entries.sortedByDescending { (_, missed) -> missed.maxOf { it.atMs } }
    val all = perContact.values.flatten()
    return MissedCallNotice(
        count = all.size,
        names = newestFirst.map { (number, _) -> names[number]?.takeIf { it.isNotBlank() } ?: number },
        contactNumber = newestFirst.singleOrNull()?.key,
        lastAtMs = all.maxOf { it.atMs },
        newestId = all.maxOf { it.id },
    )
}

/** Which title the notification gets (nb + en strings, MissedCallNotifier). */
enum class NoticeTitle {
    /** "Tapt anrop fra Pappa" */
    ONE_CALL_FROM,

    /** "2 tapte anrop fra Pappa" */
    CALLS_FROM,

    /** "3 tapte anrop", with the names "Pappa, Mamma" as the text */
    CALLS,
}

data class NoticeText(val title: NoticeTitle, val count: Int, val name: String?, val text: String?)

fun noticeText(notice: MissedCallNotice): NoticeText {
    val one = notice.names.singleOrNull()
    return when {
        one != null && notice.count == 1 -> NoticeText(NoticeTitle.ONE_CALL_FROM, 1, one, null)
        one != null -> NoticeText(NoticeTitle.CALLS_FROM, notice.count, one, null)
        else -> NoticeText(NoticeTitle.CALLS, notice.count, null, notice.names.joinToString(", "))
    }
}

/** What one recompute does. */
sealed interface NoticeStep {
    /** Post (or update) ours; [alert]: sound/heads-up - only for a call newer than any we alerted for. */
    data class Post(val notice: MissedCallNotice, val alert: Boolean) : NoticeStep

    /** Nothing to show: cancel ours. [markReadUpToId] (when not `null`): mark the log's unread
     * missed calls up to that `_id` read, then `TelecomManager.cancelMissedCallsNotification()` -
     * Telecom doesn't mark the log for a dialer with the receiver, so they would come back at
     * every boot (QA #1). */
    data class Clear(val markReadUpToId: Long?) : NoticeStep

    /** Leave everything as it is. */
    data object Keep : NoticeStep
}

/**
 * One recompute (QA #3/#4): after a broadcast ([mayPost]) or after our call ended (not
 * [mayPost]: only update or clear a notification that is [shown], never post a new one).
 * [rulesKnown]: the call rules are managed and readable - without them nothing is marked read
 * (a failure must not swallow missed calls), ours is only cancelled. [unreadUpToId]: the newest
 * unread missed call in the log (0 = none). [alertedUpToId]: the newest `_id` a post alerted for;
 * [newestLoggedId]: the newest `_id` read at all (0 = none) - lower than [alertedUpToId] means the
 * call log was cleared and its ids started again. [lastPosted]: what ours shows now, if [shown].
 */
fun noticeStep(
    notice: MissedCallNotice?,
    rulesKnown: Boolean,
    mayPost: Boolean,
    shown: Boolean,
    lastPosted: MissedCallNotice?,
    unreadUpToId: Long,
    alertedUpToId: Long,
    newestLoggedId: Long,
): NoticeStep {
    if (notice == null) return NoticeStep.Clear(unreadUpToId.takeIf { rulesKnown && it > 0 })
    if (!shown && !mayPost) return NoticeStep.Keep
    if (shown && notice == lastPosted) return NoticeStep.Keep
    val alerted = if (newestLoggedId in 1 until alertedUpToId) 0L else alertedUpToId
    return NoticeStep.Post(notice, alert = notice.newestId > alerted)
}

/** The contact whose sheet the notification's tap opens - only one in the phone book now (QA #8). */
fun missedCallContact(number: String?, view: PhoneBookView): RuleContact? =
    number?.let { n -> view.contacts.firstOrNull { it.number == n } }

/** `Calls.CONTENT_TYPE`: the call log, which Telecom's own notification and other apps open. */
const val CALL_LOG_TYPE = "vnd.android.cursor.dir/calls"

/**
 * An intent to view the call log (QA #6): `VIEW` with the call log's type, or with its URI
 * (`content://call_log/calls`, the type then comes from the provider). The phone book shows
 * itself for it - or, while calls are unmanaged, passes it on to the system dialer.
 */
fun isCallLogView(action: String?, type: String?, data: String?): Boolean =
    action == "android.intent.action.VIEW" &&
        (type == CALL_LOG_TYPE || data == "content://call_log/calls" || data?.startsWith("content://call_log/calls?") == true)
