package com.kidslauncher.mdm.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.os.PowerManager
import android.telecom.Call
import android.util.Log
import com.kidslauncher.mdm.CALL_NOTIFICATION_ID
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_CALLS
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_CALLS_SILENT
import com.kidslauncher.mdm.createCallChannels

private const val LOG_TAG = "CallNotifications"

/** How the call notification is posted - see [callNotificationMode]. [fresh]: cancel first, so
 * the post is a new notification that alerts (heads-up) again. */
data class CallNotificationMode(val silent: Boolean, val onlyAlertOnce: Boolean, val fresh: Boolean = false)

/**
 * Emulator run 2026-10-06: the HIGH call notification showed as a heads-up over our own call
 * screen and hid its timer. While [InCallActivity] is visible it goes on the LOW channel (no
 * heads-up, no sound). Otherwise HIGH with the full-screen intent, as before. A re-post only
 * because the screen went away ([visibilityChange]) doesn't alert - except a ringing call left
 * with the display on (Home pressed), which comes back as a fresh heads-up so it stays answerable;
 * a screen that just went off must not be lit again.
 */
fun callNotificationMode(screenVisible: Boolean, visibilityChange: Boolean, interactive: Boolean, ringing: Boolean): CallNotificationMode =
    when {
        screenVisible -> CallNotificationMode(silent = true, onlyAlertOnce = true)
        visibilityChange -> CallNotificationMode(silent = false, onlyAlertOnce = true, fresh = interactive && ringing)
        else -> CallNotificationMode(silent = false, onlyAlertOnce = false)
    }

/**
 * The ongoing/incoming call notification (`Notification.CallStyle`, with a full-screen intent to
 * [InCallActivity]). [KidInCallService] also starts the activity directly - allowed for the device
 * owner and the HOME app - so a suppressed heads-up or full-screen intent (lock task,
 * USE_FULL_SCREEN_INTENT policy) doesn't hide an incoming call. The full-screen intent is kept on
 * both channels: Android only posts a CallStyle notification with one (or from a foreground
 * service), and below HIGH importance it is never launched.
 */
object CallNotifications {

    @Volatile
    private var last: Pair<Call, String>? = null

    /** [InCallActivity] is started (onStart..onStop). */
    @Volatile
    var screenVisible = false
        private set

    fun show(context: Context, call: Call, name: String) {
        last = call to name
        post(context, call, name, callNotificationMode(screenVisible, visibilityChange = false, interactive = true, ringing = false))
    }

    /** From [InCallActivity.onStart]/[InCallActivity.onStop]: re-post on the right channel. */
    fun screenVisibilityChanged(context: Context, visible: Boolean) {
        screenVisible = visible
        val (call, name) = last ?: return
        if (call !in OngoingCalls.calls || call.details.state == Call.STATE_DISCONNECTED) return
        val interactive = context.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        val ringing = call.details.state == Call.STATE_RINGING
        post(context, call, name, callNotificationMode(visible, visibilityChange = true, interactive = interactive, ringing = ringing))
    }

    private fun post(context: Context, call: Call, name: String, mode: CallNotificationMode) {
        val person = Person.Builder().setName(name).setImportant(true).build()
        val open = PendingIntent.getActivity(
            context, 0, InCallActivity.intent(context),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val style = if (call.details.state == Call.STATE_RINGING) {
            Notification.CallStyle.forIncomingCall(person, CallActionReceiver.hangUp(context), CallActionReceiver.answer(context))
        } else {
            Notification.CallStyle.forOngoingCall(person, CallActionReceiver.hangUp(context))
        }
        val notification = Notification.Builder(context, if (mode.silent) NOTIFICATION_CHANNEL_CALLS_SILENT else NOTIFICATION_CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setStyle(style)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .setOnlyAlertOnce(mode.onlyAlertOnce && !mode.fresh)
            .build()
        try {
            createCallChannels(context)
            val manager = context.getSystemService(NotificationManager::class.java)
            if (mode.fresh) manager.cancel(CALL_NOTIFICATION_ID)
            manager.notify(CALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the directly started activity is enough.
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't post the call notification", e)
        }
    }

    fun cancel(context: Context) {
        last = null
        context.getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }
}
