package com.kidslauncher.mdm.push

import android.content.Intent
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

private const val LOG_TAG = "KidFcmService"

/** The server's nonce: hex, at most 32 chars - anything else isn't ours and isn't stored. */
private val NONCE = Regex("^[0-9a-f]{1,32}$")

/**
 * Receives the server's sync nudges over FCM (handy step 7). Declared `exported="false"` in our
 * manifest, as Firebase declares its own: delivery comes through the SDK's
 * `FirebaseInstanceIdReceiver` (guarded by `c2dm.permission.SEND`), so no other app can start
 * this. Enabled only in builds with a Firebase config (manifest placeholder `fcmEnabled`), and
 * not direct-boot-aware: a nudge before the first unlock waits in Play services, and the unlock
 * sync catches up. The SDK's own direct-boot-aware fallback service is removed in the manifest.
 *
 * Every message - whatever its priority, content or sender - only triggers a sync: a forged or
 * replayed one costs one sync. The priority is recorded and reported (FCM deprioritises
 * high-priority messages that don't lead to a visible notification; ours never do).
 */
class KidFcmService : FirebaseMessagingService() {

    /** QA #5: initialise (idempotently) before the SDK touches FirebaseApp, and never let it throw
     * into the process's uncaught-exception handler - that would end the call path too. */
    override fun handleIntent(intent: Intent) {
        if (!FcmSupport.ensureInitialized(applicationContext)) {
            // A sync needs no Firebase (QA step 7 #6): never let a ring/lock wait for the backstop.
            Log.w(LOG_TAG, "Firebase isn't initialised - syncing without handling ${intent.action}")
            SyncRunner.request(applicationContext, "fcm_uninit")
            return
        }
        try {
            super.handleIntent(intent)
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Handling an FCM intent failed", t)
            SyncRunner.request(applicationContext, "fcm_error")
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val priority = priorityName(message.priority)
        val original = priorityName(message.originalPriority)
        val nonce = message.data["n"]?.takeIf { NONCE.matches(it) }
        PushState.recordNudge(applicationContext, System.currentTimeMillis(), nonce, priority, original)
        if (message.priority != RemoteMessage.PRIORITY_HIGH) {
            Log.w(LOG_TAG, "Nudge arrived with priority $priority (sent as $original)")
        } else {
            Log.i(LOG_TAG, "Nudge received")
        }
        SyncRunner.request(applicationContext, "fcm")
    }

    /** More than 100 messages were waiting (or too old): one sync gets everything. */
    override fun onDeletedMessages() {
        Log.w(LOG_TAG, "FCM dropped queued messages - syncing")
        SyncRunner.request(applicationContext, "fcm_deleted")
    }

    override fun onNewToken(token: String) {
        Log.i(LOG_TAG, "New FCM token (${fcmTokenHash(token)})")
        PushState.saveToken(applicationContext, token)
        // The next status report carries it; until the server has seen it work we stay on SSE.
        SyncRunner.request(applicationContext, "fcm_token")
    }
}
