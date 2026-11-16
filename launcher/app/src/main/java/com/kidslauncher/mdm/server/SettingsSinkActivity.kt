package com.kidslauncher.mdm.server

import android.app.Activity
import android.os.Bundle
import android.util.Log

/**
 * The Settings pages SystemUI starts from the shade, caught while Settings is hidden (the gear bug, 2026-11-16;
 * `docs/notes/launcher/hardening.md`, "SystemUI's Settings links while Settings is hidden").
 *
 * `AppEnforcer.apply` hides `com.android.settings` while managed (it can't be suspended). SystemUI's
 * `StatusBarNotificationActivityStarter` starts the notification menu's pages, Manage/History and (Android 16+) the
 * empty shade's Modes link through `TaskStackBuilder.startActivities`, which throws `ActivityNotFoundException` when
 * nothing resolves - and SystemUI dies, restarts and brings Android's keyguard up. This activity resolves those actions
 * instead, at priority -999 so a visible Settings (the restrictions pause) always wins without a chooser, and only
 * finishes: it opens nothing, reads nothing from the intent but its action (no extras, no data) and validates nothing.
 * `Theme.NoDisplay`, so it must finish before `onResume` - and must stay a plain [Activity] (an AppCompat one throws
 * under that theme, in the Device Owner's own process).
 */
class SettingsSinkActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("SettingsSink", "A Settings page while Settings is hidden: ${SettingsSink.logName(intent?.action)}")
        finish()
    }
}

/** The actions [SettingsSinkActivity] answers, pure (`SettingsSinkTest` checks the manifest's filters against them).
 * From AOSP 14-17's `NotificationGutsManager`, `StatusBarNotificationActivityStarter` and `SettingsIntent`. */
object SettingsSink {
    /** Without data: the long-press menu's gear (the app, a channel, a conversation, bubbles, 16 QPR2+'s bundles), the
     * shade's Manage and History, and Android 16+'s empty-shade link to Modes (a mode of Vibb's own can hide every
     * notification). */
    val ACTIONS: List<String> = listOf(
        "android.settings.APP_NOTIFICATION_SETTINGS",
        "android.settings.CHANNEL_NOTIFICATION_SETTINGS",
        "android.settings.NOTIFICATION_SETTINGS",
        "android.settings.NOTIFICATION_HISTORY",
        "android.settings.ALL_APPS_NOTIFICATION_SETTINGS",
        "android.settings.CONVERSATION_SETTINGS",
        "android.settings.APP_NOTIFICATION_BUBBLE_SETTINGS",
        "android.settings.NOTIFICATION_BUNDLES",
        "android.settings.ZEN_MODE_SETTINGS",
        "android.settings.AUTOMATIC_ZEN_RULE_SETTINGS",
    )

    /** With a `package:` URI: the long-press menu's app-ops links (an app using the camera, the microphone or an
     * overlay). */
    val PACKAGE_ACTIONS: List<String> = listOf(
        "android.settings.APPLICATION_DETAILS_SETTINGS",
        "android.settings.MANAGE_APP_OVERLAY_PERMISSION",
    )

    /** The filters' priority: below any Settings (0 or above), and far enough below 0 that another handler ties
     * with it only on purpose. */
    const val PRIORITY = -999

    /** The log's name for an action: one of ours, else a fixed word (an explicit intent may carry any string). */
    fun logName(action: String?): String =
        if (action != null && (action in ACTIONS || action in PACKAGE_ACTIONS)) action else "another action"
}
