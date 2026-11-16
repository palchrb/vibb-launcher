package com.kidslauncher.mdm.server

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The Settings pages SystemUI starts from the shade, caught while Settings is hidden (the gear bug, 2026-11-16;
 * `docs/notes/launcher/hardening.md`, "SystemUI's Settings links while Settings is hidden").
 *
 * `AppEnforcer.apply` hides `com.android.settings` while managed (it can't be suspended). SystemUI's
 * `StatusBarNotificationActivityStarter` starts the notification gear's and the shade's Manage/History pages through
 * `TaskStackBuilder.startActivities`, which throws `ActivityNotFoundException` when nothing resolves - and SystemUI
 * dies, restarts and brings Android's keyguard up. This activity resolves those actions instead, at priority -1 so
 * a visible Settings (the restrictions pause) always wins without a chooser, and only finishes: it opens nothing, reads
 * nothing from the intent (no extras, no data) and validates nothing. `Theme.NoDisplay`, so it must finish before
 * `onResume`.
 */
class SettingsSinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("SettingsSink", "A Settings page while Settings is hidden: ${SettingsSink.logName(intent?.action)}")
        finish()
    }
}

/** The actions [SettingsSinkActivity] answers, pure (`SettingsSinkTest` checks the manifest's filter against it). */
object SettingsSink {
    /** SystemUI's notification pages: the long-press menu's gear (the app, a channel, a conversation, bubbles) and the
     * shade's Manage and History. */
    val ACTIONS: List<String> = listOf(
        "android.settings.APP_NOTIFICATION_SETTINGS",
        "android.settings.CHANNEL_NOTIFICATION_SETTINGS",
        "android.settings.NOTIFICATION_SETTINGS",
        "android.settings.NOTIFICATION_HISTORY",
        "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
        "android.settings.CONVERSATION_SETTINGS",
        "android.settings.APP_NOTIFICATION_BUBBLE_SETTINGS",
    )

    /** The log's name for an action: one of ours, else a fixed word (an explicit intent may carry any string). */
    fun logName(action: String?): String = if (action != null && action in ACTIONS) action else "another action"
}
