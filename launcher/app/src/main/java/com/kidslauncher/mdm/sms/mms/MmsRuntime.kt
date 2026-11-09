package com.kidslauncher.mdm.sms.mms

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import android.telephony.TelephonyCallback
import android.telephony.TelephonyManager
import android.util.Log
import com.kidslauncher.mdm.sms.SmsRuntime
import com.kidslauncher.mdm.sms.engine.DownloadError
import com.kidslauncher.mdm.sms.engine.MmsFlow
import com.kidslauncher.mdm.sms.engine.MmsRadio
import com.kidslauncher.mdm.sms.engine.PushOutcome
import com.kidslauncher.mdm.sms.engine.Received
import com.kidslauncher.mdm.sms.engine.RetrievedMms
import com.kidslauncher.mdm.sms.engine.RetrievedPart
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.sms.parse.MmsParser
import com.kidslauncher.mdm.sms.parse.PictureEncoder
import com.kidslauncher.mdm.sms.store.FilePartFiles
import java.io.File
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private const val LOG_TAG = "MmsRuntime"

/**
 * Design 77 step 3b on the phone: the MMS work that talks to the isolated parse and the platform - on its own thread
 * ([worker]), so a slow parse never holds the engine thread the screens and the outbox use. Each step's decision is the
 * engine's (`MmsFlow`, on the engine thread through [SmsRuntime.call]); this only carries bytes: a push to the isolated
 * parse and its answer back, the platform's download into our capped file, the fetched file to the parse, the pixels
 * to our own JPEGs. A pass runs after every push, every download result, every allow, each sync, at start, and when
 * mobile data comes back (D14). Passes are coalesced; a throw is recorded, never rethrown (§6.10).
 */
object MmsRuntime {
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "sms-mms") }
    private val queued = AtomicBoolean(false)

    @Volatile
    private var dataListening = false

    /** The picture files (CE storage, `files/sms/pictures/`). */
    fun files(context: Context): FilePartFiles = FilePartFiles(File(context.applicationContext.filesDir, "sms/pictures"))

    /** A pass soon (coalesced: one queued at a time). */
    fun kick(context: Context) {
        val app = context.applicationContext
        if (!queued.compareAndSet(false, true)) return
        worker.execute {
            queued.set(false)
            try {
                pass(app)
            } catch (t: Throwable) {
                SmsRuntime.recordError(app, "mms: ${t.javaClass.simpleName}")
            }
        }
    }

    /** A download's or send's result: short work on its own thread (the platform's broadcast waits for it), never behind
     * a pass's parse. */
    private val results = Executors.newSingleThreadExecutor { Thread(it, "sms-mms-result") }

    fun later(context: Context, work: () -> Unit, done: () -> Unit) {
        val app = context.applicationContext
        results.execute {
            try {
                work()
            } catch (t: Throwable) {
                SmsRuntime.recordError(app, "mms: ${t.javaClass.simpleName}")
            } finally {
                done()
            }
        }
    }

    private fun pass(app: Context) {
        pushes(app)
        val told = SmsRuntime.call(app) { it.mms.drainDownloads() }.orEmpty()
        told.forEach { SmsRuntime.notifyShown(app, it) }
        parses(app)
    }

    /** D7/D10: every committed push through the isolated parse, then judged by the engine. */
    private fun pushes(app: Context) {
        val pushes = SmsRuntime.call(app) { it.mms.pendingPushes() }.orEmpty()
        for (push in pushes) {
            val answer = MmsParser.parseNotification(app, push.pdu)
            val outcome = SmsRuntime.call(app) { engine ->
                when (answer) {
                    is MmsParser.Answer.Ok -> engine.mms.judgePush(push, answer.value)
                    MmsParser.Answer.Rejected -> engine.mms.judgePush(push, null)
                    MmsParser.Answer.Crashed -> engine.mms.pushCrashed(push)
                    MmsParser.Answer.Unavailable -> null
                }
            } ?: continue
            when (outcome) {
                is PushOutcome.Shown -> if (!outcome.fetch) SmsRuntime.notifyShown(app, outcome.shown)
                is PushOutcome.Held, is PushOutcome.Blocked -> SmsRuntime.requestHeldSyncSoon(app)
                PushOutcome.Waiting, PushOutcome.Duplicate -> Unit
            }
        }
    }

    /** The fetched messages: the isolated parse, our own JPEGs from its pixels, then the engine's checks (D10, D12). */
    private fun parses(app: Context) {
        val jobs = SmsRuntime.call(app) { it.mms.parseJobs() }.orEmpty()
        val files = files(app)
        for (job in jobs) {
            val file = File(File(File(app.noBackupFilesDir, "mms"), "in"), job.messageId)
            val bytes = file.takeIf { it.isFile && it.length() <= job.maxBytes }?.readBytes()
            if (bytes == null) {
                SmsRuntime.call(app) { it.mms.onParseFailed(job.messageId) }?.let { SmsRuntime.notifyShown(app, it) }
                MmsFileProvider.deleteDownload(app, job.messageId)
                continue
            }
            val told: Received.Shown? = when (val answer = MmsParser.parseRetrieve(app, bytes, job.maxBytes, job.pictures)) {
                is MmsParser.Answer.Ok -> {
                    val content = retrieved(answer.value, files)
                    SmsRuntime.call(app) { it.mms.onRetrieved(job.messageId, content) }.also { MmsFileProvider.deleteDownload(app, job.messageId) }
                }
                MmsParser.Answer.Rejected -> SmsRuntime.call(app) { it.mms.onParseFailed(job.messageId) }.also { MmsFileProvider.deleteDownload(app, job.messageId) }
                MmsParser.Answer.Crashed -> SmsRuntime.call(app) { it.mms.onParseCrashed(job.messageId) }
                MmsParser.Answer.Unavailable -> null
            }
            told?.let { SmsRuntime.notifyShown(app, it) }
        }
    }

    /** D8: the pixels become our own JPEGs here, stored by hash; the isolated process's bitmaps are recycled. */
    private fun retrieved(r: MmsParser.Retrieved, files: FilePartFiles): RetrievedMms {
        val parts = r.media.map { media ->
            when (media) {
                is MmsParser.Media.Picture -> try {
                    val encoded = PictureEncoder.received(media.bitmap)
                    RetrievedPart.Picture(files.put(encoded.bytes), encoded.width, encoded.height, encoded.bytes.size.toLong())
                } catch (e: Exception) {
                    RetrievedPart.Undecodable
                } finally {
                    media.bitmap.recycle()
                }
                MmsParser.Media.NotShown -> RetrievedPart.NotShown
                MmsParser.Media.Undecodable -> RetrievedPart.Undecodable
                is MmsParser.Media.Other -> RetrievedPart.Other(media.mime)
            }
        }
        return RetrievedMms(r.from, r.to, r.cc, r.transactionId, r.retrieveStatus, r.texts, parts)
    }

    // ---- results (MmsResultReceiver) ----

    fun onDownloadResult(app: Context, messageId: String, resultCode: Int, httpStatus: Int) {
        MmsFileProvider.revokeDownload(app, messageId)
        if (resultCode == Activity.RESULT_OK && MmsFileProvider.downloaded(app, messageId) != null) {
            // `downloaded` is committed; the parse runs in the next pass (and after a process death, at the next start).
            if (SmsRuntime.call(app) { it.mms.onDownloaded(messageId) } == true) kick(app)
            return
        }
        MmsFileProvider.deleteDownload(app, messageId)
        val error = if (resultCode == Activity.RESULT_OK) DownloadError.OTHER else MmsFlow.downloadErrorOf(resultCode, httpStatus)
        Log.w(LOG_TAG, "A download failed: $resultCode (http $httpStatus) -> $error")
        SmsRuntime.call(app) { it.mms.onDownloadFailed(messageId, error) }?.let { SmsRuntime.notifyShown(app, it) }
    }

    /** A send's result: the MMSC's m-send-conf is read in the isolated parse - a refusal there is `FAILED`. */
    fun onSendResult(app: Context, messageId: String, token: String, resultCode: Int, conf: ByteArray?) {
        MmsFileProvider.revokeOutgoing(app, token)
        MmsFileProvider.deleteOutgoing(app, token)
        val ok = resultCode == Activity.RESULT_OK
        val status = if (ok && conf != null && conf.isNotEmpty()) (MmsParser.parseSendConf(app, conf) as? MmsParser.Answer.Ok)?.value else null
        val refused = status != null && status != com.kidslauncher.mdm.sms.vendor.pdu.PduHeaders.RESPONSE_STATUS_OK
        val code = when {
            !ok -> resultCode.toString()
            refused -> "conf:$status"
            else -> null
        }
        SmsRuntime.call(app) { it.onMmsSent(messageId, ok && !refused, code) }
    }

    fun onAnswerResult(app: Context, token: String) {
        MmsFileProvider.revokeOutgoing(app, token)
        MmsFileProvider.deleteOutgoing(app, token)
    }

    // ---- D14: mobile data back ----

    /**
     * Registers once (D14): mobile data switched on, or a data connection coming up, retries every waiting MMS (and
     * those whose five tries ran out). A passive MMS-network callback would never fire (nobody asks for that network
     * until we download), so it is these listeners plus each sync. The design's `DataEnabledListener` is a system API;
     * the public `UserMobileDataStateListener` and `DataConnectionStateListener` (`READ_PHONE_STATE`) stand in for it.
     */
    fun listenForData(context: Context) {
        if (dataListening) return
        val app = context.applicationContext
        try {
            val telephony = app.getSystemService(TelephonyManager::class.java) ?: return
            telephony.registerTelephonyCallback(results, DataListener(app))
            dataListening = true
        } catch (e: SecurityException) {
            Log.w(LOG_TAG, "No data listener: ${e.javaClass.simpleName}")
        }
    }

    private class DataListener(private val app: Context) :
        TelephonyCallback(), TelephonyCallback.UserMobileDataStateListener, TelephonyCallback.DataConnectionStateListener {
        @Volatile
        private var connected: Boolean? = null

        override fun onUserMobileDataStateChanged(enabled: Boolean) {
            if (enabled) retry()
        }

        override fun onDataConnectionStateChanged(state: Int, networkType: Int) {
            val now = state == TelephonyManager.DATA_CONNECTED
            // The first report at registration isn't a change; a connection that comes back is.
            val was = connected
            connected = now
            if (now && was == false) retry()
        }

        private fun retry() {
            SmsRuntime.call(app) { it.mms.retryWaiting(dataOn = true) }
            kick(app)
        }
    }

    /** The kid's "Trykk for å prøve igjen" (D14). */
    fun retry(context: Context, messageId: String) {
        val app = context.applicationContext
        if (SmsRuntime.call(app) { it.mms.retryDownload(messageId) } == true) kick(app)
    }
}

/** The platform's MMS calls (§6.1): `SmsManager` on the message's SIM (else the default SMS subscription), our provider's
 * URIs, results to [MmsResultReceiver]. AOSP Messaging's `MmsSender` pattern. */
class PlatformMmsRadio(private val app: Context) : MmsRadio {
    private val random = SecureRandom()

    private fun manager(subId: Int?): SmsManager {
        val base = app.getSystemService(SmsManager::class.java)
        val id = subId?.takeIf { it >= 0 } ?: SubscriptionManager.getDefaultSmsSubscriptionId()
        return if (id >= 0) base.createForSubscriptionId(id) else base
    }

    override fun download(messageId: String, location: String, subId: Int?, maxBytes: Int) {
        val uri = MmsFileProvider.expect(app, messageId, maxBytes)
        manager(subId).downloadMultimediaMessage(app, location, uri, null, MmsResultReceiver.downloadedIntent(app, messageId))
    }

    override fun send(messageId: String, pdu: ByteArray, subId: Int?) {
        val token = token()
        val uri = MmsFileProvider.outgoing(app, token, pdu)
        manager(subId).sendMultimediaMessage(app, uri, null, null, MmsResultReceiver.sentIntent(app, messageId, token))
    }

    override fun answer(pdu: ByteArray, subId: Int?) {
        val token = token()
        val uri = MmsFileProvider.outgoing(app, token, pdu)
        manager(subId).sendMultimediaMessage(app, uri, null, null, MmsResultReceiver.answeredIntent(app, token))
    }

    private fun token(): String = ByteArray(16).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 0xff) }
}

/** The platform's MMS results (§6.1): explicit, manifest-registered (a result after a process death still lands), not
 * exported. The ids come from the data URI we set, never from extras a sender could add. */
class MmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val segments = intent.data?.pathSegments.orEmpty()
        val host = intent.data?.host
        if (intent.data?.scheme != SCHEME) return
        val app = context.applicationContext
        val resultCode = resultCode
        val pending = goAsync()
        MmsRuntime.later(app, {
            when {
                host == DOWNLOADED && segments.size == 1 && intent.action == ACTION_DOWNLOADED ->
                    MmsRuntime.onDownloadResult(app, segments[0], resultCode, intent.getIntExtra(SmsManager.EXTRA_MMS_HTTP_STATUS, 0))
                host == SENT && segments.size == 2 && intent.action == ACTION_SENT ->
                    MmsRuntime.onSendResult(app, segments[0], segments[1], resultCode, intent.getByteArrayExtra(SmsManager.EXTRA_MMS_DATA))
                host == ANSWERED && segments.size == 1 && intent.action == ACTION_ANSWERED -> MmsRuntime.onAnswerResult(app, segments[0])
            }
        }) { pending.finish() }
    }

    companion object {
        private const val SCHEME = "vibb-mms"
        private const val DOWNLOADED = "downloaded"
        private const val SENT = "sent"
        private const val ANSWERED = "answered"
        private const val ACTION_DOWNLOADED = "me.vibb.launcher.mms.DOWNLOADED"
        private const val ACTION_SENT = "me.vibb.launcher.mms.SENT"
        private const val ACTION_ANSWERED = "me.vibb.launcher.mms.ANSWERED"

        private fun intent(context: Context, action: String, uri: String): PendingIntent {
            val intent = Intent(action, Uri.parse(uri), context, MmsResultReceiver::class.java)
            // Mutable: the platform adds its result extras (the http status, the m-send-conf); explicit, so nobody else.
            return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }

        fun downloadedIntent(context: Context, messageId: String) = intent(context, ACTION_DOWNLOADED, "$SCHEME://$DOWNLOADED/$messageId")

        fun sentIntent(context: Context, messageId: String, token: String) = intent(context, ACTION_SENT, "$SCHEME://$SENT/$messageId/$token")

        fun answeredIntent(context: Context, token: String) = intent(context, ACTION_ANSWERED, "$SCHEME://$ANSWERED/$token")
    }
}
