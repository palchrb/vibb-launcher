package com.kidslauncher.mdm.sms

import android.app.Activity
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Telephony
import android.telephony.SmsMessage
import android.telephony.SubscriptionManager
import android.util.Log
import android.widget.Toast
import androidx.core.app.RemoteInput
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.IncomingSms
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.PendingPushRow
import com.kidslauncher.mdm.sms.engine.RandomIds
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsRules
import com.kidslauncher.mdm.sms.engine.SmsSenderType
import java.util.UUID

private const val LOG_TAG = "SmsReceivers"

/** D7: the raw m-notification-ind kept at most this big; a larger push is malformed. */
private const val PUSH_MAX = 2048

/**
 * `SMS_DELIVER` (design 77 §6.1, `permission=BROADCAST_SMS`): only the default SMS app gets it, and the platform deletes
 * its raw rows once the broadcast is done, whatever we did - so this commits first, within D7's budget: classify (the
 * type from the raw PDUs, D4 rule 1), judge (the rules are in memory), commit, `finish()`. All on the receive executor;
 * the notification and the rest come after. Catches every `Throwable` (§6.10).
 */
class SmsDeliverReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return
        val pending = goAsync()
        try {
            val sms = parse(intent, System.currentTimeMillis())
            SmsRuntime.receive(context, sms) { pending.finish() }
        } catch (t: Throwable) {
            SmsRuntime.recordError(context, "deliver: ${t.javaClass.simpleName}")
            pending.finish()
        }
    }

    companion object {
        /** The message as the platform joined it, with its sender's kind from every part's raw PDU (§6.3 step 1). */
        fun parse(intent: Intent, now: Long): IncomingSms {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent).orEmpty().filterNotNull()
            @Suppress("DEPRECATION")
            val pdus = (intent.extras?.get("pdus") as? Array<*>)?.map { it as? ByteArray }.orEmpty()
            val format = intent.getStringExtra("format")
            val kinds = pdus.map { SmsSenderType.of(it, format) }
            val addresses = messages.map { it.originatingAddress }
            val kind = if (kinds.size == messages.size) SmsSenderType.ofParts(kinds, addresses) else SenderKind.MALFORMED
            val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
            return IncomingSms(
                kind = kind,
                address = addresses.firstOrNull(),
                body = messages.joinToString("") { it.displayMessageBody.orEmpty() },
                sentAt = messages.firstOrNull()?.timestampMillis,
                subId = subId,
                receivedAt = now,
            )
        }
    }
}

/**
 * `WAP_PUSH_DELIVER` for MMS (`permission=BROADCAST_WAP_PUSH`), required for the SMS role. Step 1 only commits the raw
 * push (at most 2 KB) to `pending_push` and finishes (D7); parsing, the sender and host gate and the download are step
 * 3b's, which takes the rows from there. Nothing is fetched or shown before it.
 */
class MmsPushReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION) return
        val pending = goAsync()
        val app = context.applicationContext
        SmsRuntime.receiveExecutor.execute {
            try {
                val pdu = intent.getByteArrayExtra("data")
                if (pdu == null || pdu.size > PUSH_MAX || !CallPolicyStore.userUnlocked(app)) {
                    Log.w(LOG_TAG, "An MMS push that can't be kept (${pdu?.size} bytes)")
                } else {
                    val subId = intent.getIntExtra(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX, -1).takeIf { it >= 0 }
                    SmsRuntime.keepPush(app, PendingPushRow(RandomIds.hex(), pdu, subId, System.currentTimeMillis()))
                }
            } catch (t: Throwable) {
                SmsRuntime.recordError(app, "push: ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** The platform's sent and delivery results for our own sends (§6.2): manifest-registered and explicit, so a result
 * that comes after a process death is still applied. Not exported. */
class SmsSentReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(EXTRA_MESSAGE) ?: return
        try {
            when (intent.action) {
                ACTION_SENT -> SmsRuntime.onSent(
                    context, id, intent.getIntExtra(EXTRA_PART, 0), intent.getIntExtra(EXTRA_TOTAL, 1),
                    ok = resultCode == Activity.RESULT_OK, code = intent.getIntExtra("errorCode", resultCode),
                )
                ACTION_DELIVERED -> deliveryOk(intent)?.let { SmsRuntime.onDelivered(context, id, it) }
            }
        } catch (t: Throwable) {
            SmsRuntime.recordError(context, "result: ${t.javaClass.simpleName}")
        }
    }

    /** A status report's TP-ST: 0-31 done (delivered), 32-63 still trying (`null`: wait), 64+ failed for good. */
    private fun deliveryOk(intent: Intent): Boolean? {
        val pdu = intent.getByteArrayExtra("pdu") ?: return null
        val status = SmsMessage.createFromPdu(pdu, intent.getStringExtra("format"))?.status ?: return null
        return when {
            status < 0x20 -> true
            status < 0x40 -> null
            else -> false
        }
    }

    companion object {
        private const val ACTION_SENT = "me.vibb.launcher.sms.SENT"
        private const val ACTION_DELIVERED = "me.vibb.launcher.sms.DELIVERED"
        private const val EXTRA_MESSAGE = "message"
        private const val EXTRA_PART = "part"
        private const val EXTRA_TOTAL = "total"

        /** One per message and part, told apart by the data URI (FM's `SmsSender` did the same with the message's). */
        fun sentIntent(context: Context, messageId: String, part: Int, total: Int): PendingIntent {
            val intent = Intent(ACTION_SENT, Uri.parse("vibb-sms://sent/$messageId/$part"), context, SmsSentReceiver::class.java)
                .putExtra(EXTRA_MESSAGE, messageId).putExtra(EXTRA_PART, part).putExtra(EXTRA_TOTAL, total)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun deliveredIntent(context: Context, messageId: String): PendingIntent {
            val intent = Intent(ACTION_DELIVERED, Uri.parse("vibb-sms://delivered/$messageId"), context, SmsSentReceiver::class.java)
                .putExtra(EXTRA_MESSAGE, messageId)
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
    }
}

/** The notification's "Reply" (§6.8): not exported; the gate is the engine's, the `clientId` the notification's own,
 * so a double tap sends once. A refusal turns the notification into "Not sent". */
class SmsReplyReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val conversation = intent.getStringExtra(SmsNotifier.EXTRA_CONVERSATION) ?: return
        val clientId = intent.getStringExtra(SmsNotifier.EXTRA_CLIENT_ID) ?: return
        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(SmsNotifier.KEY_REPLY)?.toString()?.trim().orEmpty()
        val pending = goAsync()
        val app = context.applicationContext
        SmsRuntime.engineExecutor.execute {
            try {
                val result = SmsRuntime.send(app, SendCommand(clientId, ConversationId(conversation), null, text, origin = Origin.NOTIFICATION))
                if (result is SendResult.Accepted) SmsNotifier.cancel(app, conversation) else SmsNotifier.notSent(app, conversation)
            } catch (t: Throwable) {
                SmsRuntime.recordError(app, "reply: ${t.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}

/** `RESPOND_VIA_MESSAGE` (the call screen's quick answers; `permission=SEND_RESPOND_VIA_MESSAGE`), required for the
 * SMS role: [SmsRuntime.send] - the gate applies, nothing else is sent. */
class RespondViaMessageService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val number = intent?.data?.schemeSpecificPart?.substringBefore('?')
        val text = intent?.getStringExtra(Intent.EXTRA_TEXT)?.trim().orEmpty()
        val app = applicationContext
        if (number != null && text.isNotEmpty()) {
            SmsRuntime.engineExecutor.execute {
                try {
                    SmsRuntime.send(app, SendCommand(UUID.randomUUID().toString(), null, number, text, origin = Origin.KID_SCREEN))
                } catch (t: Throwable) {
                    SmsRuntime.recordError(app, "respond: ${t.javaClass.simpleName}")
                }
            }
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }
}

/**
 * `SENDTO`/`VIEW` for `sms: smsto: mms: mmsto:` (required for the SMS role). A number the kid may not text says so
 * ("You can't send a message to this number"); an allowed one opens its thread with any `sms_body` as a draft in step
 * 2 (the kid's screens) - never sent without a tap. Until then it closes.
 */
class ComposeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val number = intent?.data?.schemeSpecificPart?.substringBefore('?')?.substringBefore(',')
        val allowed = number != null && SmsRules.decideSend(number, SmsRuntime.judge(this)) is SmsRules.SendVerdict.Allow
        if (!allowed) Toast.makeText(this, R.string.sms_compose_refused, Toast.LENGTH_LONG).show()
        finish()
    }
}
