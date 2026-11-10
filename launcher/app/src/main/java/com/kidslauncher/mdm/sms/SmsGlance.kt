package com.kidslauncher.mdm.sms

import android.content.Context
import com.kidslauncher.mdm.badges.GLANCE_TEXT_MAX
import com.kidslauncher.mdm.badges.GlanceSmsRow
import com.kidslauncher.mdm.badges.cleanGlanceText
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.sms.engine.Conversation
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.Message

/*
 * The lock glance's "Meldinger" rows (design 77 §6.8, step 5): one per conversation with unread messages, read from the
 * engine on its own thread by SmsHome's refresh - never from our notification, which GlanceReader skips. The functions
 * here are pure (SmsGlanceTest); [SmsGlance] keeps the rows the kid swiped away, in memory only.
 */

/** How many of a conversation's newest messages are looked through for its newest unread incoming one. */
const val SMS_GLANCE_LOOKBACK = 20

/** The parent's switch for the text on the lock (`call_policy.sms_lock_text`, default off): only managed rules that
 * say so - unknown or unmanaged rules show no text. */
fun smsLockText(state: CallPolicyState): Boolean = (state as? CallPolicyState.Managed)?.rules?.smsLockText == true

/**
 * [conversation]'s row, or `null` without unread messages. [newestFirst] is its newest messages (the engine's backfill
 * order); the row's time and mark are its newest unread incoming message's. The text is read only when [showText] -
 * the parent's switch - is on; with it off the row is the name and the count alone, and never says "picture" either.
 * The engine's conversations hold only accepted messages (a held or blocked one has none), so nothing here can show
 * a stranger's held message.
 */
fun smsGlanceRow(conversation: Conversation, newestFirst: List<Message>, showText: Boolean): GlanceSmsRow? {
    if (conversation.unread <= 0) return null
    val newest = newestFirst.firstOrNull { it.direction == Direction.IN && !it.read }
    val text = if (showText) newest?.body?.let { cleanGlanceText(it, GLANCE_TEXT_MAX) } else null
    return GlanceSmsRow(
        conversation = conversation,
        count = conversation.unread,
        timeMs = newest?.at ?: conversation.lastAt,
        upToSeq = newest?.seq ?: conversation.lastSeq,
        text = text,
        picture = showText && text == null && newest?.parts.orEmpty().any { it.mime.startsWith("image/") },
        sender = newest?.sender?.takeIf { conversation.participants.size > 1 },
        unknownSender = newest?.unknownSender == true,
    )
}

/** `NotificationManager.IMPORTANCE_DEFAULT`: the lowest importance that makes a sound (pure here). */
private const val SMS_ALERT_IMPORTANCE = 3

/**
 * Whether our message notification's post is a notice that came on the lock (launcher round 7 §3, as the missed-call
 * notice's alerting post; `PinLockRuntime.noticeCame`, whose gate and the parent's `lock_wake_on_notification` decide
 * the rest). Round 7's rules for an app's notice, for ours:
 * - [posted]: an accepted message's post that went through (held and blocked messages are never posted; the reply's
 *   "not sent" update and a group move's re-post aren't this post);
 * - not [silent]: a time rule without calls stores and notifies silently, a picture that arrived late is told again
 *   quietly;
 * - [passesFilter]: no Do Not Disturb at all (`INTERRUPTION_FILTER_ALL`) - fail closed: a silent rule that allows calls
 *   (our own zen rule lets no message through), the kid's own Do Not Disturb, a system mode: no wake (code QA H1);
 * - [enabled] notifications, and the channel at an alerting importance ([channelImportance]; `null` = unknown, no);
 * - [gainsRow]: the lock's rows gain a row ([smsGainsRow]) - a newer message in a row already there is an update, not
 *   a wake (code QA M1).
 */
fun smsPostAlerts(
    posted: Boolean,
    silent: Boolean,
    enabled: Boolean,
    channelImportance: Int?,
    passesFilter: Boolean,
    gainsRow: Boolean,
): Boolean =
    posted && !silent && passesFilter && enabled && channelImportance != null && channelImportance >= SMS_ALERT_IMPORTANCE &&
        gainsRow

/** Whether a message ([messageSeq]) brings its conversation's row onto the lock: it is the conversation's only unread
 * message ([unreadAfter] counts it), or the row was swiped away before it ([hiddenMark], the swipe's `upToSeq`). */
fun smsGainsRow(unreadAfter: Int, hiddenMark: Long?, messageSeq: Long): Boolean =
    unreadAfter == 1 || (hiddenMark != null && messageSeq > hiddenMark)

/** The rows the kid hasn't swiped away: [hidden] holds, per conversation id, the newest unread message's `seq` at the
 * swipe - a newer message brings the row back. */
fun smsGlanceShown(rows: List<GlanceSmsRow>, hidden: Map<String, Long>): List<GlanceSmsRow> =
    rows.filter { row -> hidden[row.conversationId]?.let { row.upToSeq > it } ?: true }

/** A swipe takes the conversation's notification away only while the swiped row is still the newest the lock knows
 * ([currentUpToSeq], `null` = no row now): a message that came during the drag stays in the shade (code QA L2). */
fun smsDismissCancels(swipedUpToSeq: Long, currentUpToSeq: Long?): Boolean =
    currentUpToSeq == null || currentUpToSeq == swipedUpToSeq

/**
 * The glance's swiped-away SMS rows (main thread, memory only - as the glance's opened marks). A swipe (or TalkBack's
 * "Fjern") hides the row until the conversation's next message and takes our notification of it away, as the shade's
 * swipe would ([smsDismissCancels]); nothing is marked read, so Home's badge stays (as a dismissed missed call's
 * "Pappa 1").
 */
object SmsGlance {
    private val hidden = HashMap<String, Long>()

    fun shown(rows: List<GlanceSmsRow>): List<GlanceSmsRow> = if (hidden.isEmpty()) rows else smsGlanceShown(rows, hidden)

    /** [current]: the conversation's row as the engine's summary has it now. */
    fun dismiss(context: Context, row: GlanceSmsRow, current: GlanceSmsRow?) {
        hidden[row.conversationId] = row.upToSeq
        if (smsDismissCancels(row.upToSeq, current?.upToSeq)) SmsNotifier.cancel(context.applicationContext, row.conversationId)
    }

    /** A newer message ([messageSeq]) came in [conversationId]: its swipe mark, if it had one (the row comes back), is
     * spent and returned - so only the first message after a swipe counts as bringing the row back. Main thread. */
    fun comeBack(conversationId: String, messageSeq: Long): Long? {
        val mark = hidden[conversationId] ?: return null
        if (messageSeq <= mark) return null
        hidden.remove(conversationId)
        return mark
    }
}
