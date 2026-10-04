package com.kidslauncher.mdm.calls

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Person
import android.content.Context
import android.telecom.Call
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_CALLS
import com.kidslauncher.mdm.R

private const val CALL_NOTIFICATION_ID = 1004

/**
 * The ongoing/incoming call notification (`Notification.CallStyle`, with a full-screen intent to
 * [InCallActivity]). [KidInCallService] also starts the activity directly - allowed for the device
 * owner and the HOME app - so a suppressed heads-up or full-screen intent (lock task,
 * USE_FULL_SCREEN_INTENT policy) doesn't hide an incoming call.
 */
object CallNotifications {

    fun show(context: Context, call: Call, name: String) {
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
        val notification = Notification.Builder(context, NOTIFICATION_CHANNEL_CALLS)
            .setSmallIcon(android.R.drawable.sym_action_call)
            .setStyle(style)
            .setContentIntent(open)
            .setFullScreenIntent(open, true)
            .setCategory(Notification.CATEGORY_CALL)
            .setOngoing(true)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java).notify(CALL_NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted: the directly started activity is enough.
        }
    }

    fun cancel(context: Context) {
        context.getSystemService(NotificationManager::class.java).cancel(CALL_NOTIFICATION_ID)
    }
}
