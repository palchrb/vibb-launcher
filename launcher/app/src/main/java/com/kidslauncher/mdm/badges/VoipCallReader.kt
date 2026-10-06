package com.kidslauncher.mdm.badges

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import com.kidslauncher.mdm.lock.CALL_TYPE_INCOMING
import com.kidslauncher.mdm.lock.VoipCalls
import com.kidslauncher.mdm.lock.VoipNotice
import com.kidslauncher.mdm.lock.VoipNoticeKind
import com.kidslauncher.mdm.lock.voipNoticeKind

/**
 * The notification listener's reader for VoIP calls over the PIN lock (design 17): of every other
 * app's notification only the category, channel id, flags and whether it has a full-screen intent
 * ([voipNoticeKind]) and the CallStyle call type (an int); of a call-shaped one also the intents the lock may send - the ring's
 * full-screen intent and CallStyle decline action, and the call service notification's content
 * intent (never the ring's: that is Element's answer intent). Never a title, text, person or
 * message (`VoipCallReaderTest` scans this file); nothing is logged or stored here.
 */
object VoipCallReader {
    /**
     * `Notification.FLAG_FSI_REQUESTED_BUT_DENIED` (API 34, hidden): Android dropped the app's
     * full-screen intent - it lacks USE_FULL_SCREEN_INTENT (QA #11). [device check]
     */
    private const val FLAG_FSI_REQUESTED_BUT_DENIED = 0x00004000

    fun read(context: Context, sbn: StatusBarNotification): VoipNotice? {
        if (sbn.packageName == context.packageName) return null
        val n = sbn.notification ?: return null
        val kind = voipNoticeKind(
            category = n.category,
            channelId = n.channelId,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            hasFullScreenIntent = n.fullScreenIntent != null,
            fsiDenied = n.flags and FLAG_FSI_REQUESTED_BUT_DENIED != 0,
            // An int, not text (qa-16-17-code #8): an incoming CallStyle is never "the call".
            incomingCallStyle = n.extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) == CALL_TYPE_INCOMING,
        )
        return when (kind) {
            VoipNoticeKind.NONE -> null
            VoipNoticeKind.RINGING -> VoipNotice(
                sbn.key, sbn.packageName, kind,
                fullScreen = n.fullScreenIntent,
                decline = n.extras?.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java),
            )
            VoipNoticeKind.IN_CALL -> VoipNotice(sbn.key, sbn.packageName, kind, content = n.contentIntent)
            VoipNoticeKind.FSI_DENIED -> VoipNotice(sbn.key, sbn.packageName, kind)
        }
    }

    private fun readOrNull(context: Context, sbn: StatusBarNotification): VoipNotice? = try {
        read(context, sbn)
    } catch (e: Exception) {
        null
    }

    fun connected(context: Context, active: List<StatusBarNotification>) =
        VoipCalls.replaceAll(context, active.mapNotNull { readOrNull(context, it) })

    fun posted(context: Context, sbn: StatusBarNotification) = VoipCalls.posted(context, sbn.key, readOrNull(context, sbn))

    fun removed(context: Context, sbn: StatusBarNotification) = VoipCalls.removed(context, sbn.key)

    fun disconnected(context: Context) = VoipCalls.listenerLost(context)
}
