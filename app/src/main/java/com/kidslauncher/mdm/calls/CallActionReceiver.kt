package com.kidslauncher.mdm.calls

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The Answer / Decline / Hang up buttons of the call notification. Not exported: only our own
 * PendingIntents reach it. */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val call = OngoingCalls.current ?: return
        when (intent.action) {
            ACTION_ANSWER -> {
                OngoingCalls.answer(call)
                context.startActivity(InCallActivity.intent(context))
            }
            ACTION_HANG_UP -> OngoingCalls.hangUp(call)
        }
    }

    companion object {
        private const val ACTION_ANSWER = "com.kidslauncher.mdm.calls.ANSWER"
        private const val ACTION_HANG_UP = "com.kidslauncher.mdm.calls.HANG_UP"

        private fun pending(context: Context, action: String, requestCode: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context, requestCode,
                Intent(context, CallActionReceiver::class.java).setAction(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun answer(context: Context) = pending(context, ACTION_ANSWER, 1)

        fun hangUp(context: Context) = pending(context, ACTION_HANG_UP, 2)
    }
}
