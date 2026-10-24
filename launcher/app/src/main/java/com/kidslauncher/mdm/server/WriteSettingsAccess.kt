package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

/*
 * "Modify system settings" without adb (PLAN "Fix backlog", 2026-10-24): the touch sounds row (design
 * r9 §4) and the clock format (design 34 P12) need the WRITE_SETTINGS app-op, which a device owner
 * can't grant itself. The PIN-gated Settings' "Modify system settings" row, next to "Background
 * connection", opens Android's own page for Vibb One - the same way the battery row opens its
 * dialog: only when it resolves to an unsuspended system package that isn't ours, which on a
 * managed phone means during "Pause all restrictions" (the pause unhides and unsuspends Android's
 * Settings and ends the kiosk, so no lock-task allow-list is involved). Every status report says
 * whether the app-op is granted (`write_settings_granted`), and the phone's About This Phone page
 * names this row while it isn't.
 */

/** The "Modify system settings" row's states. */
enum class WriteSettingsRow { GRANTED, CAN_OPEN, PAUSE_FIRST, NO_PAGE, UNKNOWN }

/**
 * [granted]: `Settings.System.canWrite` (null = unreadable); [pageAvailable]: one of
 * [WriteSettingsAccess.pageIntents] resolves now; [windowOpen]: the pause (or the override) is on,
 * so Android's Settings is shown - nothing resolving then means the phone has no such page;
 * [pageMissing]: starting every page threw `ActivityNotFoundException` on the last tap.
 */
fun writeSettingsRowState(granted: Boolean?, pageAvailable: Boolean, windowOpen: Boolean, pageMissing: Boolean): WriteSettingsRow = when {
    granted == true -> WriteSettingsRow.GRANTED
    granted == null -> WriteSettingsRow.UNKNOWN
    pageMissing -> WriteSettingsRow.NO_PAGE
    pageAvailable -> WriteSettingsRow.CAN_OPEN
    windowOpen -> WriteSettingsRow.NO_PAGE
    else -> WriteSettingsRow.PAUSE_FIRST
}

/** The data URI of Android's page for one app: `package:` + its applicationId. */
fun writeSettingsPageUri(packageName: String): String = "package:$packageName"

object WriteSettingsAccess {
    /** Whether Vibb One may write `Settings.System` now; null when it can't be read. */
    fun granted(context: Context): Boolean? = runCatching { Settings.System.canWrite(context) }.getOrNull()

    /** Android's pages to try, in order, each explicit to a system package ([systemPageIntent]):
     * Vibb One's own page (`ACTION_MANAGE_WRITE_SETTINGS` with our package), then the plain action
     * (Android's list of apps). Empty outside a pause on a managed phone. Started without NEW_TASK,
     * from the activity. */
    fun pageIntents(context: Context): List<Intent> = listOfNotNull(
        systemPageIntent(
            context,
            Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse(writeSettingsPageUri(context.packageName))),
        ),
        systemPageIntent(context, Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)),
    )
}
