package com.kidslauncher.mdm

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Upstream's crash channel, deleted at start (crashes are reported to the server). */
private const val NOTIFICATION_CHANNEL_CRASH = "launcher:crash"
val NOTIFICATION_CHANNEL_RING = "launcher:ring"
const val RING_NOTIFICATION_ID = 1001
val NOTIFICATION_CHANNEL_LISTENER = "launcher:command_listener"
const val COMMAND_LISTENER_NOTIFICATION_ID = 1002
val NOTIFICATION_CHANNEL_VPN_FILTER = "launcher:vpn_filter"
const val VPN_FILTER_NOTIFICATION_ID = 1003
val NOTIFICATION_CHANNEL_APP_INSTALL = "launcher:app_install"
/** Incoming and ongoing calls (calls.CallNotifications) - HIGH, like any phone app's. */
const val NOTIFICATION_CHANNEL_CALLS = "launcher:calls"
/** The same call notification while our call screen is in front - LOW: no heads-up over it
 * (emulator run 2026-10-06), no sound; the full-screen intent is inert below HIGH. */
const val NOTIFICATION_CHANNEL_CALLS_SILENT = "launcher:calls_silent"
/** Was 1004 too, the same id as [INSTALL_MODE_NOTIFICATION_ID] - a call's cancel removed the
 * install-mode notification. */
const val CALL_NOTIFICATION_ID = 1005
private const val APP_INSTALL_NOTIFICATION_ID_BASE = 2000
/** Missed calls (calls.MissedCallNotifier, design 12) - DEFAULT: sound, no full-screen intent. */
const val NOTIFICATION_CHANNEL_MISSED_CALLS = "launcher:missed_calls"
const val MISSED_CALL_NOTIFICATION_ID = 1006
/** Play install mode (handy step 7, play.PlayRuntime) - LOW, ongoing, with "End now". */
const val NOTIFICATION_CHANNEL_INSTALL_MODE = "launcher:install_mode"
const val INSTALL_MODE_NOTIFICATION_ID = 1004

/**
 * The two call channels. Silent: Telecom plays the ringtone itself (we don't declare
 * IN_CALL_SERVICE_RINGING), these only carry the call UI and its full-screen intent. Also called
 * before each call notification (idempotent) - the call path runs before the first unlock, when
 * [createNotificationChannels] may not have run yet.
 */
fun createCallChannels(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
    notificationManager.createNotificationChannel(
        NotificationChannel(
            NOTIFICATION_CHANNEL_CALLS,
            context.getString(R.string.notification_channel_calls),
            NotificationManager.IMPORTANCE_HIGH
        ).apply { setSound(null, null) }
    )
    notificationManager.createNotificationChannel(
        NotificationChannel(
            NOTIFICATION_CHANNEL_CALLS_SILENT,
            context.getString(R.string.notification_channel_calls_silent),
            NotificationManager.IMPORTANCE_LOW
        ).apply { setSound(null, null) }
    )
}

/** The missed-call channel; also called before each post (idempotent), like [createCallChannels]. */
fun createMissedCallChannel(context: Context) {
    val notificationManager = context.getSystemService(NotificationManager::class.java) ?: return
    notificationManager.createNotificationChannel(
        NotificationChannel(
            NOTIFICATION_CHANNEL_MISSED_CALLS,
            context.getString(R.string.notification_channel_missed_calls),
            NotificationManager.IMPORTANCE_DEFAULT
        )
    )
}

fun createNotificationChannels(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Crashes go to the server since the 2026-10-06 cleanup; drop the old crash channel.
        notificationManager.deleteNotificationChannel(NOTIFICATION_CHANNEL_CRASH)
        createCallChannels(context)
        createMissedCallChannel(context)
        // HIGH importance + own channel so this reliably heads-up/appears even over the lock
        // screen while Find My Device's ring is playing - the whole point is to give the kid an
        // obvious, immediate way to silence it once they unlock the device, not something that
        // silently sits in the shade.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_RING,
                context.getString(R.string.notification_channel_ring),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
        // MIN importance, silent - this is the mandatory persistent notification for
        // CommandListenerService's foreground service (Android requires one for any foreground
        // service, no way around it), not something meant to draw attention the way the ring
        // channel above deliberately does.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_LISTENER,
                context.getString(R.string.notification_channel_listener),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        // MIN importance, silent - same reasoning as the listener channel above: this is the
        // mandatory persistent notification for KidVpnService's foreground service, not something
        // meant to draw attention.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_VPN_FILTER,
                context.getString(R.string.notification_channel_vpn_filter),
                NotificationManager.IMPORTANCE_MIN
            )
        )
        // LOW importance, not MIN - unlike the two foreground-service channels above, this one is
        // meant to actually be seen (a parent glancing at the shade should be able to tell an app
        // install/update - including the launcher's own silent self-update, which otherwise "just
        // happens" with zero visible indication - is in progress), just without sound/heads-up
        // interruption for something this routine.
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_INSTALL_MODE,
                context.getString(R.string.notification_channel_install_mode),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        notificationManager.createNotificationChannel(
            NotificationChannel(
                NOTIFICATION_CHANNEL_APP_INSTALL,
                context.getString(R.string.notification_channel_app_install),
                NotificationManager.IMPORTANCE_LOW
            )
        )
    }
}

/** Shown while [MdmSyncWorker] downloads+installs one tracked app's update (including the
 * launcher's own self-update) - cancelled by [notifyAppInstallResult] once the real result is
 * known. One notification per app (keyed by [appId], the server's stable tracked-app id) so
 * several updates queued in the same sync cycle don't clobber each other's progress notification. */
fun notifyAppInstalling(context: Context, appId: Long, appName: String) {
    val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_APP_INSTALL)
        .setSmallIcon(android.R.drawable.stat_sys_download)
        .setContentTitle(context.getString(R.string.notification_app_installing_title, appName))
        .setOngoing(true)
        .setAutoCancel(false)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    try {
        NotificationManagerCompat.from(context).notify(appInstallNotificationId(appId), builder.build())
    } catch (e: SecurityException) {
        Log.w("Notifications", "Could not show app-install notification for $appName", e)
    }
}

/** On success, just cancels the ongoing "Installing..." notification - it disappearing is enough
 * signal, and a lingering "Installed" toast isn't worth the extra notification. On failure, swaps
 * it for a dismissible one, since a silently-failed background install/update is exactly the kind
 * of thing worth surfacing (same reasoning as this channel's own doc comment above). */
fun notifyAppInstallResult(context: Context, appId: Long, appName: String, success: Boolean) {
    val notificationManager = NotificationManagerCompat.from(context)
    val id = appInstallNotificationId(appId)
    if (success) {
        try {
            notificationManager.cancel(id)
        } catch (e: SecurityException) {
            // Nothing to clean up if we can't reach the notification manager anyway.
        }
        return
    }

    val builder = NotificationCompat.Builder(context, NOTIFICATION_CHANNEL_APP_INSTALL)
        .setSmallIcon(android.R.drawable.stat_sys_warning)
        .setContentTitle(context.getString(R.string.notification_app_install_failed_title, appName))
        .setOngoing(false)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_LOW)

    try {
        notificationManager.notify(id, builder.build())
    } catch (e: SecurityException) {
        Log.w("Notifications", "Could not show app-install-failed notification for $appName", e)
    }
}

private fun appInstallNotificationId(appId: Long): Int = APP_INSTALL_NOTIFICATION_ID_BASE + appId.toInt()

fun requestNotificationPermission(activity: Activity) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
        return
    }

    val permission =
        (activity.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)

    if (!permission) {
        ActivityCompat.requestPermissions(
            activity,
            arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
            1
        )
    }
}
