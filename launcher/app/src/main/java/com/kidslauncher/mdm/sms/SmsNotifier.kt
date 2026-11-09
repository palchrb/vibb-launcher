package com.kidslauncher.mdm.sms

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.graphics.drawable.IconCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.ContactPhotos
import com.kidslauncher.mdm.sms.engine.ConversationRow
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.ui.messages.MessagesScreens
import java.util.UUID

private const val LOG_TAG = "SmsNotifier"

/** Design 77 §6.8: the message notifications - DEFAULT, and LOW for a message stored under a time rule without
 * calls ("stored and notified silently", §6.7). */
const val NOTIFICATION_CHANNEL_MESSAGES = "launcher:messages"
const val NOTIFICATION_CHANNEL_MESSAGES_SILENT = "launcher:messages_silent"

/** One notification per conversation: this id, tagged with the conversation's id. */
const val MESSAGES_NOTIFICATION_ID = 1009

/** How many of a conversation's newest unread messages the notification shows. */
private const val SHOWN_MESSAGES = 5

/**
 * The notification of an accepted message (§6.8): `MessagingStyle` with the contact's name and photo (our own
 * cached copy, decoded by us - never an MMS picture's bytes), a public version that says only "New message", and a
 * "Reply" ([SmsReplyReceiver]) that needs the phone unlocked (`setAuthenticationRequired`, QA L6) and shows only while
 * the conversation may be answered. Only ever for messages the engine accepted; held and blocked ones are never
 * notified.
 */
object SmsNotifier {
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL_MESSAGES, context.getString(R.string.notification_channel_messages), NotificationManager.IMPORTANCE_DEFAULT),
        )
        manager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_MESSAGES_SILENT, context.getString(R.string.notification_channel_messages_silent), NotificationManager.IMPORTANCE_LOW,
            ).apply { setSound(null, null) },
        )
    }

    /** Posts (or updates) [conversation]'s notification after [message] arrived; [silent] under a time rule. [wake]:
     * this post may be a notice on the lock (a message's own post; not a group move's re-post, [afterMove]). */
    fun notify(context: Context, engine: SmsEngine, conversation: ConversationRow, message: Message, silent: Boolean, wake: Boolean = true) {
        try {
            createChannels(context)
            val id = com.kidslauncher.mdm.sms.engine.ConversationId(conversation.id)
            val unread = smsNotifiedMessages(engine.messages(id, null, SHOWN_MESSAGES * 2), SHOWN_MESSAGES).ifEmpty { listOf(message) }
            val me = Person.Builder().setName(context.getString(R.string.sms_notification_me)).build()
            val sender = senderPerson(context, conversation)
            val style = NotificationCompat.MessagingStyle(me)
            val group = conversation.participants.size > 1
            for (m in unread) {
                // Step 3b: a picture without text says "Bilde" - never its pixels (the large icon stays the contact's).
                val body = m.body?.takeIf { it.isNotBlank() } ?: context.getString(R.string.messages_picture).takeIf { m.parts.isNotEmpty() }.orEmpty()
                val text = if (m.unknownSender) context.getString(R.string.sms_unknown_sender_line, body) else body
                // D12: in a group each message names who in it wrote it.
                val from = if (group) m.sender?.let { groupMember(context, it) } ?: sender else sender
                style.addMessage(text, m.at, from)
            }
            if (group) style.setGroupConversation(true).setConversationTitle(groupTitle(context, conversation))
            val now = engine.conversation(id)
            val canSend = now?.canSend == true
            val channel = if (silent) NOTIFICATION_CHANNEL_MESSAGES_SILENT else NOTIFICATION_CHANNEL_MESSAGES
            val public = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.sms_notification_public))
                .build()
            val builder = NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setStyle(style)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                // Design 77 §6.7 (code QA of step 2, M3): no "Open link" / "Call" / "Directions" / "Copy" chips the
                // system's assistant would add from the text - nothing in a message opens another app, here either.
                .setAllowSystemGeneratedContextualActions(false)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .setPublicVersion(public)
                .setAutoCancel(true)
                .setOnlyAlertOnce(false)
                .setSilent(silent)
                .setContentIntent(openIntent(context, conversation.id))
            if (canSend) builder.addAction(replyAction(context, conversation.id))
            val manager = NotificationManagerCompat.from(context)
            manager.notify(conversation.id, MESSAGES_NOTIFICATION_ID, builder.build())
            if (wake) wakeIfAlerts(context, manager, channel, silent, conversation.id, message.seq, unreadAfter = now?.unread ?: 0)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "No permission to notify")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the message notification", e)
        }
    }

    /**
     * Launcher round 7 §3: an accepted message's alerting post that brings its row onto the lock is a notice that came -
     * the parent's switch and the lock's gate decide the rest (as the missed-call notice's). Only that it came, nothing
     * of it. The swipe mark is read and spent on the main thread, where the glance keeps it ([SmsGlance.comeBack]).
     */
    private fun wakeIfAlerts(
        context: Context,
        manager: NotificationManagerCompat,
        channel: String,
        silent: Boolean,
        conversationId: String,
        seq: Long,
        unreadAfter: Int,
    ) {
        val importance = manager.getNotificationChannel(channel)?.importance
        val enabled = manager.areNotificationsEnabled()
        // Code QA H1: under any Do Not Disturb no wake - fail closed (an unknown filter counts as one).
        val passesFilter = context.getSystemService(NotificationManager::class.java)?.currentInterruptionFilter ==
            NotificationManager.INTERRUPTION_FILTER_ALL
        main.post {
            val mark = SmsGlance.comeBack(conversationId, seq)
            val gainsRow = smsGainsRow(unreadAfter, mark, seq)
            if (smsPostAlerts(posted = true, silent = silent, enabled = enabled, channelImportance = importance, passesFilter = passesFilter, gainsRow = gainsRow)) {
                com.kidslauncher.mdm.lock.PinLockRuntime.noticeCame()
            }
        }
    }

    /**
     * A group MMS left [conversationId] (D12; step 3b's code QA L8): that conversation's notification is rebuilt from
     * what is still unread there, quietly and without a wake - or goes when nothing is. Engine thread.
     */
    fun afterMove(context: Context, engine: SmsEngine, conversationId: String) {
        val id = com.kidslauncher.mdm.sms.engine.ConversationId(conversationId)
        val row = engine.conversationRow(id)
        val newest = smsNotifiedMessages(engine.messages(id, null, SHOWN_MESSAGES * 2), SHOWN_MESSAGES).lastOrNull()
        if (row == null || newest == null) cancel(context, conversationId) else notify(context, engine, row, newest, silent = true, wake = false)
    }

    /** After a reply the engine refused: the conversation's notification says it wasn't sent (no reply then). */
    fun notSent(context: Context, conversationId: String) {
        try {
            createChannels(context)
            val notification = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_MESSAGES_SILENT)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(context.getString(R.string.sms_reply_not_sent))
                .setAllowSystemGeneratedContextualActions(false)
                .setAutoCancel(true)
                .setContentIntent(openIntent(context, conversationId))
                .build()
            NotificationManagerCompat.from(context).notify(conversationId, MESSAGES_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "No permission to notify")
        }
    }

    fun cancel(context: Context, conversationId: String) {
        NotificationManagerCompat.from(context).cancel(conversationId, MESSAGES_NOTIFICATION_ID)
    }

    /** A group's member as a person: the contact's name now, else the number. */
    private fun groupMember(context: Context, address: String): Person {
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules
        val contact = rules?.contacts?.firstOrNull { it.number == address }
        val builder = Person.Builder().setName(contact?.name?.takeIf { it.isNotBlank() } ?: address).setKey(address)
        contact?.let { ContactPhotos.cachedShown(context, com.kidslauncher.mdm.ui.home.KidAvatars.shownPhotoOf(context, it, false)) }
            ?.let { builder.setIcon(IconCompat.createWithBitmap(it)) }
        return builder.build()
    }

    private fun groupTitle(context: Context, conversation: ConversationRow): String =
        conversation.participants.joinToString(", ") { groupMember(context, it.address).name.toString() }

    private fun senderPerson(context: Context, conversation: ConversationRow): Person {
        val participant = conversation.participants.singleOrNull()
        val name = conversation.displayName ?: participant?.address?.removePrefix(SmsEngine.NAME_PREFIX).orEmpty()
        val contact = participant?.contactId?.let { id ->
            (CallPolicyStore.state as? CallPolicyState.Managed)?.rules?.contacts?.firstOrNull { it.id == id }
        }
        val builder = Person.Builder().setName(name).setKey(participant?.address)
        contact?.let { ContactPhotos.cachedShown(context, com.kidslauncher.mdm.ui.home.KidAvatars.shownPhotoOf(context, it, false)) }
            ?.let { builder.setIcon(IconCompat.createWithBitmap(it)) }
        return builder.build()
    }

    /** The tap (§6.8): the conversation's thread over the list, in the messages task - Back goes to the list. Told
     * apart by the thread intent's identifier (part of the intent's identity, not of its resolution; the last intent is
     * the PendingIntent's key), never by a hashCode (QA L1). The thread checks the time rule itself. */
    private fun openIntent(context: Context, conversationId: String): PendingIntent {
        val thread = MessagesScreens.threadIntent(context, conversationId, null).setIdentifier(conversationId)
        return PendingIntent.getActivities(
            context, 0, arrayOf(MessagesScreens.listUnder(context), thread), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    /**
     * The reply carries its own `clientId`, made when the notification is posted: a double tap sends once (§6.8). Both
     * ids are in the data URI (QA L1): `FLAG_MUTABLE` is what `RemoteInput` needs to fill the text in, and a fill-in can
     * add extras but never change a set data URI - so whoever holds the PendingIntent can't point the reply elsewhere,
     * and two conversations never share one PendingIntent. Explicit, so only [SmsReplyReceiver] gets it.
     */
    private fun replyAction(context: Context, conversationId: String): NotificationCompat.Action {
        val intent = Intent(ACTION_REPLY, replyUri(conversationId, UUID.randomUUID().toString()), context, SmsReplyReceiver::class.java)
        val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val input = RemoteInput.Builder(KEY_REPLY).setLabel(context.getString(R.string.sms_notification_reply)).build()
        return NotificationCompat.Action.Builder(0, context.getString(R.string.sms_notification_reply), pending)
            .addRemoteInput(input)
            .setAllowGeneratedReplies(false)
            .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
            .setShowsUserInterface(false)
            .setAuthenticationRequired(true)
            .build()
    }

    private fun replyUri(conversationId: String, clientId: String): android.net.Uri = android.net.Uri.parse(replyData(conversationId, clientId))

    const val KEY_REPLY = "me.vibb.launcher.sms.REPLY"
    const val ACTION_REPLY = "me.vibb.launcher.sms.REPLY_ACTION"
}
