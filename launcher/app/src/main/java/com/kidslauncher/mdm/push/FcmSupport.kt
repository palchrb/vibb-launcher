package com.kidslauncher.mdm.push

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.installations.FirebaseInstallations
import com.google.firebase.messaging.FirebaseMessaging
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.play.PLAY_SERVICES
import com.kidslauncher.mdm.play.PLAY_STORE
import com.kidslauncher.mdm.server.dto.PushPolicy
import java.util.concurrent.TimeUnit

private const val LOG_TAG = "FcmSupport"
private const val TOKEN_TIMEOUT_S = 30L

/**
 * Firebase, by hand (handy step 7, design 07 §1): no google-services plugin, no
 * FirebaseInitProvider (removed in the manifest - it would initialise before the first unlock,
 * and Firebase keeps its state in credential-encrypted storage), no analytics, no auto-init. The
 * config comes from BuildConfig (Gradle properties / CI variables); a build without one never
 * touches Firebase and uses the SSE stream.
 *
 * [ensureInitialized] is idempotent and never throws; it's called when the anchor service starts
 * and by [KidFcmService] before it handles anything (QA #5: a message between the unlock and our
 * init would otherwise hit `FirebaseApp.getInstance()` and the uncaught-exception handler would
 * take the call path down with the process).
 */
object FcmSupport {

    val configured: Boolean
        get() = BuildConfig.FCM_APPLICATION_ID.isNotEmpty() && BuildConfig.FCM_PROJECT_ID.isNotEmpty() &&
            BuildConfig.FCM_API_KEY.isNotEmpty() && BuildConfig.FCM_SENDER_ID.isNotEmpty()

    @Volatile
    private var initialized = false

    @Synchronized
    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        if (!configured) return false
        // CE storage: before the first unlock nothing of ours that uses Firebase may run.
        if (!CallPolicyStore.userUnlocked(context)) return false
        return try {
            val app = context.applicationContext
            if (FirebaseApp.getApps(app).isEmpty()) {
                val options = FirebaseOptions.Builder()
                    .setApplicationId(BuildConfig.FCM_APPLICATION_ID)
                    .setApiKey(BuildConfig.FCM_API_KEY)
                    .setProjectId(BuildConfig.FCM_PROJECT_ID)
                    .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                    .build()
                FirebaseApp.initializeApp(app, options)
            }
            FirebaseMessaging.getInstance().setDeliveryMetricsExportToBigQuery(false)
            initialized = true
            true
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Firebase init failed - staying on SSE", t)
            false
        }
    }

    /** Installed, enabled and not hidden: without MATCH_UNINSTALLED_PACKAGES, PackageManager
     * reports a package hidden by a device owner as not installed. */
    private fun packageEnabled(context: Context, packageName: String): Boolean = try {
        context.packageManager.getApplicationInfo(packageName, 0).enabled
    } catch (e: PackageManager.NameNotFoundException) {
        false
    } catch (e: Exception) {
        false
    }

    /** Play services installed and enabled - FCM runs in it. */
    fun gmsAvailable(context: Context): Boolean = packageEnabled(context, PLAY_SERVICES)

    /** The Play Store installed, enabled and not hidden (our suspension is fine). */
    fun playStorePresent(context: Context): Boolean = packageEnabled(context, PLAY_STORE)

    /** Decides the transport from the enforced policy's `push` and stores it in [PushState]. */
    fun decide(context: Context, server: PushPolicy?): TransportDecision {
        val decision = decidePushTransport(
            PushInputs(
                fcmConfigured = configured && ensureInitialized(context),
                gmsAvailable = gmsAvailable(context),
                playStorePresent = playStorePresent(context),
                token = PushState.token(context),
                server = server,
            )
        )
        if (decision != PushState.lastDecision) {
            Log.i(LOG_TAG, "Push transport: ${decision.transport.wire}${decision.sseReason?.let { " ($it)" } ?: ""}")
        }
        PushState.lastDecision = decision
        return decision
    }

    /**
     * Gets or renews the FCM token as [tokenAction] says. Blocking (Tasks.await, at most
     * [TOKEN_TIMEOUT_S] per call) - only from a background thread inside a sync. Never throws.
     */
    fun maintainToken(context: Context, server: PushPolicy?) {
        val now = System.currentTimeMillis()
        val token = PushState.token(context)
        val ownHash = token?.takeIf { it.isNotBlank() }?.let { fcmTokenHash(it) }
        if (ownHash != null && server?.fcmTokenHash == ownHash) PushState.setServerKnewHash(context, ownHash)
        val serverKnew = ownHash != null && PushState.serverKnewHash(context) == ownHash
        val action = tokenAction(configured, token, server, PushState.tokenRequestedAt(context), now, serverKnew)
        if (action == TokenAction.NONE) return
        if (!ensureInitialized(context) || !gmsAvailable(context)) return
        PushState.markTokenRequested(context, now)
        try {
            // FID registration (firebase-messaging 25.1+; getToken/deleteToken are deprecated):
            // the "token" we report is the Firebase installation ID, which the server's HTTP v1
            // `token` field accepts. Renewing deletes the installation, so the ID really changes
            // (the server ignores re-reports of one it saw rejected). [needs device test]
            val messaging = FirebaseMessaging.getInstance()
            val installations = FirebaseInstallations.getInstance()
            if (action == TokenAction.RENEW) {
                try {
                    Tasks.await(messaging.unregister(), TOKEN_TIMEOUT_S, TimeUnit.SECONDS)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "FCM unregister failed - deleting the installation anyway", e)
                }
                Tasks.await(installations.delete(), TOKEN_TIMEOUT_S, TimeUnit.SECONDS)
                PushState.saveToken(context, null)
                PushState.setServerKnewHash(context, null)
            }
            Tasks.await(messaging.register(), TOKEN_TIMEOUT_S, TimeUnit.SECONDS)
            val token = Tasks.await(installations.id, TOKEN_TIMEOUT_S, TimeUnit.SECONDS)
            if (!token.isNullOrBlank()) {
                PushState.saveToken(context, token)
                Log.i(LOG_TAG, "FCM registration ${if (action == TokenAction.RENEW) "renewed" else "obtained"} (${fcmTokenHash(token)})")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't get an FCM token", e)
        }
    }
}
