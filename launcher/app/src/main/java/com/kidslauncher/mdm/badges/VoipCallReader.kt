package com.kidslauncher.mdm.badges

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.service.notification.StatusBarNotification
import com.kidslauncher.mdm.lock.CALL_TYPE_INCOMING
import com.kidslauncher.mdm.lock.CallAction
import com.kidslauncher.mdm.lock.KEY_CALL_STYLE_ACTION
import com.kidslauncher.mdm.lock.VoipCalls
import com.kidslauncher.mdm.lock.VoipNotice
import com.kidslauncher.mdm.lock.VoipNoticeKind
import com.kidslauncher.mdm.lock.intentOfApp
import com.kidslauncher.mdm.lock.pickAnswerIntent
import com.kidslauncher.mdm.lock.voipNoticeKind

/**
 * The notification listener's reader for VoIP calls over the PIN lock (design 17): of every other
 * app's notification only the category, channel id, flags and whether it has a full-screen intent
 * ([voipNoticeKind]) and the CallStyle call type (an int); of a call-shaped one also the intents
 * the lock may send (only those the app itself created, S-L1) - the ring's full-screen intent and CallStyle decline and answer actions (17b:
 * [pickAnswerIntent] from `EXTRA_ANSWER_INTENT` or the actions' intents and CallStyle marker - never
 * an action's title), and the call service notification's content intent (never the ring's).
 * Never a title, text, person or message (`VoipCallGuardTest` scans this file); nothing is logged
 * or stored here.
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
        // An int, not text (qa-16-17-code #8): an incoming CallStyle is never "the call", and only
        // an incoming one may be answered from its actions or ring without an FSI (qa-17b-code #2).
        val incoming = n.extras?.getInt(Notification.EXTRA_CALL_TYPE, 0) == CALL_TYPE_INCOMING
        val kind = voipNoticeKind(
            category = n.category,
            channelId = n.channelId,
            foregroundService = n.flags and Notification.FLAG_FOREGROUND_SERVICE != 0,
            hasFullScreenIntent = n.fullScreenIntent != null,
            fsiDenied = n.flags and FLAG_FSI_REQUESTED_BUT_DENIED != 0,
            incomingCallStyle = incoming,
        )
        return when (kind) {
            VoipNoticeKind.NONE -> null
            VoipNoticeKind.RINGING, VoipNoticeKind.FSI_DENIED -> ring(sbn, n, kind, incoming)
            VoipNoticeKind.IN_CALL -> VoipNotice(sbn.key, sbn.packageName, kind, content = n.contentIntent.ofApp(sbn.packageName))
        }
    }

    /** A ring: its full-screen intent (none when Android dropped it) and the CallStyle actions. */
    private fun ring(sbn: StatusBarNotification, n: Notification, kind: VoipNoticeKind, incoming: Boolean): VoipNotice {
        val pkg = sbn.packageName
        val decline = n.extras?.getParcelable(Notification.EXTRA_DECLINE_INTENT, PendingIntent::class.java).ofApp(pkg)
        val answer = pickAnswerIntent(
            answerExtra = n.extras?.getParcelable(Notification.EXTRA_ANSWER_INTENT, PendingIntent::class.java).ofApp(pkg),
            decline = decline,
            actions = n.actions.orEmpty().map { CallAction(it.actionIntent.ofApp(pkg), it.extras?.getBoolean(KEY_CALL_STYLE_ACTION) == true) },
            incoming = incoming,
        )
        return VoipNotice(
            sbn.key, pkg, kind,
            fullScreen = n.fullScreenIntent.ofApp(pkg), decline = decline, answer = answer, incoming = incoming,
        )
    }

    /** Security review S-L1: only an intent the notification's own app created ([intentOfApp]) - never another
     * app's PendingIntent carried in its ring. */
    private fun PendingIntent?.ofApp(pkg: String): PendingIntent? = this?.takeIf { intentOfApp(it.creatorPackage, pkg) }

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
