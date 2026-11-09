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
 * Whether our message notification's post alerts - and so may wake the screen on the lock (launcher round 7 §3, as the
 * missed-call notice's alerting post; `PinLockRuntime.noticeCame`, whose gate and the parent's
 * `lock_wake_on_notification` decide the rest): only an accepted message's post that went through ([posted]), not
 * [silent] (a time rule without calls stores and notifies silently, a picture that arrived late is told again quietly),
 * with notifications on ([enabled]) and the channel at an alerting importance ([channelImportance]; `null` = unknown,
 * no). Held and blocked messages are never posted; the reply's "not sent" update isn't this post.
 */
fun smsPostAlerts(posted: Boolean, silent: Boolean, enabled: Boolean, channelImportance: Int?): Boolean =
    posted && !silent && enabled && channelImportance != null && channelImportance >= SMS_ALERT_IMPORTANCE

/** The rows the kid hasn't swiped away: [hidden] holds, per conversation id, the newest unread message's `seq` at the
 * swipe - a newer message brings the row back. */
fun smsGlanceShown(rows: List<GlanceSmsRow>, hidden: Map<String, Long>): List<GlanceSmsRow> =
    rows.filter { row -> hidden[row.conversationId]?.let { row.upToSeq > it } ?: true }

/**
 * The glance's swiped-away SMS rows (main thread, memory only - as the glance's opened marks). A swipe (or TalkBack's
 * "Fjern") hides the row until the conversation's next message and takes our notification of it away, as the shade's
 * swipe would; nothing is marked read, so Home's badge stays (as a dismissed missed call's "Pappa 1").
 */
object SmsGlance {
    private val hidden = HashMap<String, Long>()

    fun shown(rows: List<GlanceSmsRow>): List<GlanceSmsRow> = if (hidden.isEmpty()) rows else smsGlanceShown(rows, hidden)

    fun dismiss(context: Context, row: GlanceSmsRow) {
        hidden[row.conversationId] = row.upToSeq
        SmsNotifier.cancel(context.applicationContext, row.conversationId)
    }
}
