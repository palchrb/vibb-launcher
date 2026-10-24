package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.kidslauncher.mdm.R

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

/** Whether a Settings row can open Android's page now (the write-settings and fingerprint rows). */
enum class SystemPageAccess { CAN_OPEN, PAUSE_FIRST, WAIT, NO_PAGE }

/**
 * [pageAvailable]: one of the row's pages resolves now; [windowOpen]: the pause (or the override) is on;
 * [overrideApplied]: the last finished `AppEnforcer.apply` enforced the open window's plan
 * (`AppEnforcer.lastEnforcedOverride`), so Android's Settings is shown - only then does nothing resolving mean the
 * phone has no such page; before it the window's apply is still on its way (WAIT, code QA Q-2); [pageMissing]:
 * starting every page threw `ActivityNotFoundException` on the last tap.
 */
fun systemPageAccess(pageAvailable: Boolean, windowOpen: Boolean, overrideApplied: Boolean, pageMissing: Boolean): SystemPageAccess = when {
    pageMissing -> SystemPageAccess.NO_PAGE
    pageAvailable -> SystemPageAccess.CAN_OPEN
    !windowOpen -> SystemPageAccess.PAUSE_FIRST
    overrideApplied -> SystemPageAccess.NO_PAGE
    else -> SystemPageAccess.WAIT
}

/** The "Modify system settings" row's states: the grant, else [SystemPageAccess]'s. */
enum class WriteSettingsRow { GRANTED, UNKNOWN, CAN_OPEN, PAUSE_FIRST, WAIT, NO_PAGE }

/** [granted]: `Settings.System.canWrite` (null = unreadable); [access]: [systemPageAccess]. */
fun writeSettingsRowState(granted: Boolean?, access: SystemPageAccess): WriteSettingsRow = when {
    granted == true -> WriteSettingsRow.GRANTED
    granted == null -> WriteSettingsRow.UNKNOWN
    else -> when (access) {
        SystemPageAccess.CAN_OPEN -> WriteSettingsRow.CAN_OPEN
        SystemPageAccess.PAUSE_FIRST -> WriteSettingsRow.PAUSE_FIRST
        SystemPageAccess.WAIT -> WriteSettingsRow.WAIT
        SystemPageAccess.NO_PAGE -> WriteSettingsRow.NO_PAGE
    }
}

/** The row's summary for each state (code QA Q-3: the mapping is pinned by test). */
fun writeSettingsText(row: WriteSettingsRow): Int = when (row) {
    WriteSettingsRow.GRANTED -> R.string.settings_mdm_write_settings_granted
    WriteSettingsRow.UNKNOWN -> R.string.settings_mdm_write_settings_unknown
    WriteSettingsRow.CAN_OPEN -> R.string.settings_mdm_write_settings_open
    WriteSettingsRow.PAUSE_FIRST -> R.string.settings_mdm_write_settings_pause_first
    WriteSettingsRow.WAIT -> R.string.settings_mdm_write_settings_wait
    WriteSettingsRow.NO_PAGE -> R.string.settings_mdm_write_settings_no_page
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
