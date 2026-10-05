package com.kidslauncher.mdm.server

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.kidslauncher.mdm.preferences.LauncherPreferences

private const val LOG_TAG = "UnifiedPushRegistrationReceiver"

// UnifiedPush Android spec (unifiedpush.org/developers/spec/android/), confirmed against the
// actual spec text field-by-field, not guessed.
const val ACTION_REGISTER = "org.unifiedpush.android.distributor.REGISTER"
const val ACTION_UNREGISTER = "org.unifiedpush.android.distributor.UNREGISTER"
const val ACTION_MESSAGE_ACK = "org.unifiedpush.android.distributor.MESSAGE_ACK"
const val ACTION_NEW_ENDPOINT = "org.unifiedpush.android.connector.NEW_ENDPOINT"
const val ACTION_UNREGISTERED = "org.unifiedpush.android.connector.UNREGISTERED"
const val ACTION_REGISTRATION_FAILED = "org.unifiedpush.android.connector.REGISTRATION_FAILED"
const val ACTION_MESSAGE = "org.unifiedpush.android.connector.MESSAGE"
const val EXTRA_TOKEN = "token"
const val EXTRA_ENDPOINT = "endpoint"
const val EXTRA_BYTES_MESSAGE = "bytesMessage"
const val EXTRA_REASON = "reason"

/**
 * Discoverable entry point for the launcher's UnifiedPush distributor role - delegates the actual
 * connection/registry work to [UnifiedPushRelay]. Manifest-registered `exported="true"` with a
 * real `<intent-filter>` for [ACTION_REGISTER]/[ACTION_UNREGISTER] - the one deliberate exception
 * to this codebase's otherwise-universal "every receiver is `exported="false"`, explicit
 * component only" rule (see [AppInstallReceiver]'s own doc comment for why that's normally
 * correct) - it has to be, since other apps' UnifiedPush connector libraries find this via an
 * implicit broadcast, with no way to know this app's component name ahead of time. No other
 * receiver in this app should ever need this same exception.
 *
 * Sender identity is resolved via [getSentFromPackage] (`BroadcastReceiver`, API 34+,
 * `FLAG_SHARE_IDENTITY` - confirmed as a real platform API, not assumed). The spec also describes
 * an older SDK<34 PendingIntent-based resolution path, deliberately not implemented at all here -
 * this app's own `minSdk` is already 34, so that path can never be reached.
 */
class UnifiedPushRegistrationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LauncherPreferences.mdm().unifiedpushDistributorEnabled()) return

        val token = intent.getStringExtra(EXTRA_TOKEN)
        if (token == null) {
            Log.w(LOG_TAG, "${intent.action} with no token extra, ignoring")
            return
        }
        val packageName = getSentFromPackage()
        if (packageName == null) {
            Log.w(LOG_TAG, "Could not resolve sender package for token $token, ignoring")
            return
        }

        when (intent.action) {
            ACTION_REGISTER -> {
                val endpoint = UnifiedPushRelay.register(context, token, packageName)
                context.sendBroadcast(
                    Intent(ACTION_NEW_ENDPOINT)
                        .setPackage(packageName)
                        .putExtra(EXTRA_TOKEN, token)
                        .putExtra(EXTRA_ENDPOINT, endpoint)
                )
            }

            ACTION_UNREGISTER -> {
                UnifiedPushRelay.unregister(context, token)
                context.sendBroadcast(
                    Intent(ACTION_UNREGISTERED)
                        .setPackage(packageName)
                        .putExtra(EXTRA_TOKEN, token)
                )
            }

            ACTION_MESSAGE_ACK -> {
                // Nothing to do - this app never sends MESSAGE with an `id` extra (the optional
                // "requires acknowledgment" field), so a well-behaved connector library should
                // never send this back unprompted either. Handled anyway, not left unreachable,
                // since the spec lists it as something a distributor "SHOULD handle" regardless.
            }

            else -> Log.w(LOG_TAG, "Unexpected action: ${intent.action}")
        }
    }
}
