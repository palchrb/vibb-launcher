package com.kidslauncher.mdm.notify

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_APP_INSTALL
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_CALLS
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_CALLS_SILENT
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_INSTALL_MODE
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_LISTENER
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_MISSED_CALLS
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_RING
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_VPN_FILTER
import com.kidslauncher.mdm.NOTIFICATION_CHANNEL_WINDOW
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.assistant.NOTIFICATION_CHANNEL_SETUP
import com.kidslauncher.mdm.server.SystemPageAccess
import com.kidslauncher.mdm.server.systemPageIntent
import com.kidslauncher.mdm.shortcuts.SHORTCUTS_CHANNEL_BASE
import com.kidslauncher.mdm.shortcuts.ShortcutsRuntime
import com.kidslauncher.mdm.sms.NOTIFICATION_CHANNEL_MESSAGES
import com.kidslauncher.mdm.sms.NOTIFICATION_CHANNEL_MESSAGES_SILENT
import com.kidslauncher.mdm.sms.NOTIFICATION_CHANNEL_REACTIONS
import kotlinx.serialization.Serializable

/*
 * Design 107: the Vibb One notification channels that are turned off, in every full status report
 * (`notification_channels`), so the parent's About This Phone names them. The shared contract is
 * `notification_channels.json` (byte-identical with `server/testdata/`; `NotificationChannelsTest`).
 *
 * The report is read at each report build ([NotificationChannelsReader.read], two binder calls); a block-state broadcast
 * only asks for a sync sooner ([NotificationChannelsWatch]). Vibb never raises a channel the kid turned off: the parent
 * gets text, and Settings' "Vibb One notifications" row opens Android's page during a pause (Q4).
 */

/** The capability: every full report carries `notification_channels` (the server's `notification_channels_v1`). */
const val NOTIFICATION_CHANNELS_CAPABILITY = "notification_channels_v1"

/** The sync reason of a block-state broadcast and of the Settings row's return. */
const val NOTIFICATION_CHANNELS_SYNC_REASON = "notification_channels"

/** The report's key of Snarveier's channel in use ([ShortcutsRuntime.currentChannelId]). */
const val SHORTCUTS_KEY = "shortcuts"

/** One channel the report can name: its key (the id without `launcher:`), its id and the phone's own name for it. */
enum class ReportedChannel(val key: String, val id: String, val nameRes: Int) {
    MESSAGES("messages", NOTIFICATION_CHANNEL_MESSAGES, R.string.notification_channel_messages),
    MISSED_CALLS("missed_calls", NOTIFICATION_CHANNEL_MISSED_CALLS, R.string.notification_channel_missed_calls),
    CALLS("calls", NOTIFICATION_CHANNEL_CALLS, R.string.notification_channel_calls),
    MESSAGES_SILENT("messages_silent", NOTIFICATION_CHANNEL_MESSAGES_SILENT, R.string.notification_channel_messages_silent),
    MESSAGES_REACTIONS("messages_reactions", NOTIFICATION_CHANNEL_REACTIONS, R.string.notification_channel_messages_reactions),
    CALLS_SILENT("calls_silent", NOTIFICATION_CHANNEL_CALLS_SILENT, R.string.notification_channel_calls_silent),
    COMMAND_LISTENER("command_listener", NOTIFICATION_CHANNEL_LISTENER, R.string.notification_channel_listener),
    RING("ring", NOTIFICATION_CHANNEL_RING, R.string.notification_channel_ring),
    WINDOW("window", NOTIFICATION_CHANNEL_WINDOW, R.string.notification_channel_window),
    INSTALL_MODE("install_mode", NOTIFICATION_CHANNEL_INSTALL_MODE, R.string.notification_channel_install_mode),
    APP_INSTALL("app_install", NOTIFICATION_CHANNEL_APP_INSTALL, R.string.notification_channel_app_install),
    VPN_FILTER("vpn_filter", NOTIFICATION_CHANNEL_VPN_FILTER, R.string.notification_channel_vpn_filter),
    SETUP("setup", NOTIFICATION_CHANNEL_SETUP, R.string.notification_channel_setup),

    /** Snarveier: its `.N` ids read as this one while in use, the others skipped. */
    SHORTCUTS(SHORTCUTS_KEY, SHORTCUTS_CHANNEL_BASE, R.string.shortcuts_channel),
    ;

    companion object {
        fun of(key: String): ReportedChannel? = entries.firstOrNull { it.key == key }
    }
}

/** `StatusReportRequest.notificationChannels` (§2): the app-wide switch off, and the table's channels turned off, in
 * the table's order. Pinned by `notification_channels.json`'s `report` rows. */
@Serializable
data class NotificationChannelsReport(
    val allOff: Boolean,
    val blocked: List<String>,
)

/** One channel as Android has it: its id, its importance and its group. */
data class ChannelReading(val id: String, val importance: Int, val group: String? = null)

/**
 * The report from what Android says (pure; the contract's `read` rows). A channel is off at IMPORTANCE_NONE or in a
 * blocked group. Only the table's ids count, Snarveier's only as [shortcutsId] (the channel in use): an older `.N` id,
 * an unknown id and Android 16's reserved `android.app.*` channels are skipped. A channel that doesn't exist is not
 * off. [appEnabled]: `areNotificationsEnabled()`.
 */
fun channelsReport(
    channels: List<ChannelReading>,
    blockedGroups: Set<String>,
    appEnabled: Boolean,
    shortcutsId: String,
): NotificationChannelsReport {
    val off = channels
        .filter { it.importance == NotificationManager.IMPORTANCE_NONE || (it.group != null && it.group in blockedGroups) }
        .mapNotNull { reading ->
            if (reading.id == shortcutsId) {
                ReportedChannel.SHORTCUTS
            } else {
                ReportedChannel.entries.firstOrNull { it.id == reading.id && it != ReportedChannel.SHORTCUTS }
            }
        }
        .toSet()
    return NotificationChannelsReport(
        allOff = !appEnabled,
        blocked = ReportedChannel.entries.filter { it in off }.map { it.key },
    )
}

/** Settings' "Vibb One notifications" row (§6): what is off (never Snarveier - its own "Vis i varsellisten igjen" says
 * that) and what the tap does now. */
data class NotificationsRow(
    val allOff: Boolean,
    val off: List<ReportedChannel>,
    /** The summary's second part, by [SystemPageAccess]. */
    val action: Int,
)

/**
 * The row from the report and the page's access (pure); `null` hides it: nothing read, or nothing but Snarveier off.
 * [dialerRoleHeld]: Vibb is the dialer, so every `launcher:` channel is locked and Android's page greys a switch that is
 * off (gate §6ax step 3) - the tap's words then say to hand the role back first, as the PWA's ⓘ does.
 */
fun notificationsRowText(report: NotificationChannelsReport?, access: SystemPageAccess, dialerRoleHeld: Boolean = false): NotificationsRow? {
    report ?: return null
    val off = report.blocked.mapNotNull { ReportedChannel.of(it) }.filter { it != ReportedChannel.SHORTCUTS }
    if (!report.allOff && off.isEmpty()) return null
    val action = when (access) {
        SystemPageAccess.CAN_OPEN -> if (dialerRoleHeld) R.string.settings_notifications_open_role else R.string.settings_notifications_open
        SystemPageAccess.PAUSE_FIRST -> R.string.settings_notifications_pause_first
        SystemPageAccess.WAIT -> R.string.settings_notifications_wait
        SystemPageAccess.NO_PAGE -> R.string.settings_notifications_no_page
    }
    return NotificationsRow(report.allOff, off, action)
}

object NotificationChannelsReader {
    private const val LOG_TAG = "NotificationChannels"

    /** §2's one read (off the main thread): the channels, the blocked groups, the app-wide switch. `null` when any of it
     * throws - the report then leaves the field out and the PWA shows nothing (never a false "All on"). */
    fun read(context: Context): NotificationChannelsReport? = try {
        val app = context.applicationContext
        val nm = app.getSystemService(NotificationManager::class.java)!!
        val channels = nm.notificationChannels.map { ChannelReading(it.id, it.importance, it.group) }
        val blockedGroups = nm.notificationChannelGroups.filter { it.isBlocked }.map { it.id }.toSet()
        channelsReport(
            channels,
            blockedGroups,
            NotificationManagerCompat.from(app).areNotificationsEnabled(),
            ShortcutsRuntime.currentChannelId(app),
        )
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't read the channels: ${e.javaClass.simpleName}")
        null
    }

    /** Android's notification page for Vibb One, explicit to a system package ([systemPageIntent]): empty outside a
     * pause on a managed phone (Android's Settings hidden), so a tap in the kiosk opens nothing. Started without
     * NEW_TASK, from the activity. */
    fun pageIntents(context: Context): List<Intent> = listOfNotNull(
        systemPageIntent(
            context,
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        ),
    )
}

/**
 * §3: a channel, a channel group or the whole app turned off or on by the user asks for a sync, so the parent's page
 * follows within seconds; the report's read is the truth. Registered once per process from `Application.initRest`.
 * [SyncRunner] coalesces the burst of one Apply in the long-press sheet (one broadcast per changed channel).
 *
 * `RECEIVER_EXPORTED` on purpose (gate §6ax, Android 14 and 16): a change from the long-press sheet is sent with
 * SystemUI's uid, not the system's, and Android drops it at enqueue for a `RECEIVER_NOT_EXPORTED` receiver ("Exported
 * Denial"); only a change in Settings' own page arrived. The three actions are protected broadcasts (only the system
 * and privileged apps may send them), and the receiver only asks for a sync, so nothing else can use it.
 */
object NotificationChannelsWatch {
    private const val LOG_TAG = "NotificationChannels"
    private var registered = false

    fun init(context: Context) {
        if (registered) return
        registered = true
        val app = context.applicationContext
        val filter = IntentFilter().apply {
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED)
            addAction(NotificationManager.ACTION_APP_BLOCK_STATE_CHANGED)
        }
        ContextCompat.registerReceiver(
            app,
            object : BroadcastReceiver() {
                // No extra is read on purpose (L5): a change the platform refused still sends the broadcast, so
                // EXTRA_BLOCKED_STATE can claim a block that never happened. The report's own read is the only truth.
                override fun onReceive(context: Context, intent: Intent) {
                    Log.i(LOG_TAG, "Block state changed (${intent.action?.substringAfterLast('.')}): a sync")
                    // Snarveier's panel reads its channel's block too (hidden, design 99 §2.6); its own receiver isn't
                    // exported, so this one asks for its refresh.
                    ShortcutsRuntime.requestRefresh()
                    com.kidslauncher.mdm.push.SyncRunner.request(context.applicationContext, NOTIFICATION_CHANNELS_SYNC_REASON)
                }
            },
            filter,
            ContextCompat.RECEIVER_EXPORTED,
        )
    }
}
