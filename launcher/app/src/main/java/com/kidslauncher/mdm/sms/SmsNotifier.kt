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
import com.kidslauncher.mdm.sms.engine.Direction
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

    /** Posts (or updates) [conversation]'s notification after [message] arrived; [silent] under a time rule. */
    fun notify(context: Context, engine: SmsEngine, conversation: ConversationRow, message: Message, silent: Boolean) {
        try {
            createChannels(context)
            val unread = engine.messages(com.kidslauncher.mdm.sms.engine.ConversationId(conversation.id), null, SHOWN_MESSAGES * 2)
                .filter { it.direction == Direction.IN && !it.read }
                .take(SHOWN_MESSAGES)
                .reversed()
                .ifEmpty { listOf(message) }
            val me = Person.Builder().setName(context.getString(R.string.sms_notification_me)).build()
            val sender = senderPerson(context, conversation)
            val style = NotificationCompat.MessagingStyle(me)
            for (m in unread) {
                val text = if (m.unknownSender) context.getString(R.string.sms_unknown_sender_line, m.body.orEmpty()) else m.body.orEmpty()
                style.addMessage(text, m.at, sender)
            }
            val canSend = engine.conversations().firstOrNull { it.id.value == conversation.id }?.canSend == true
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
            NotificationManagerCompat.from(context).notify(conversation.id, MESSAGES_NOTIFICATION_ID, builder.build())
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "No permission to notify")
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the message notification", e)
        }
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

    private fun senderPerson(context: Context, conversation: ConversationRow): Person {
        val participant = conversation.participants.singleOrNull()
        val name = conversation.displayName ?: participant?.address?.removePrefix(SmsEngine.NAME_PREFIX).orEmpty()
        val contact = participant?.contactId?.let { id ->
            (CallPolicyStore.state as? CallPolicyState.Managed)?.rules?.contacts?.firstOrNull { it.id == id }
        }
        val builder = Person.Builder().setName(name).setKey(participant?.address)
        ContactPhotos.cached(context, contact?.photo)?.let { builder.setIcon(IconCompat.createWithBitmap(it)) }
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
