package com.kidslauncher.mdm.server

import android.content.Context
import android.content.Intent
import android.provider.Settings
import com.kidslauncher.mdm.R

/*
 * "Media on the lock screen" without a manual hunt (PLAN "Fix backlog", "Android's lock screen shows the media card",
 * the user 2026-10-24: "ødelegger litt illusjonen vi har skapt nå"): Android 13+ puts its media card on SystemUI's own
 * face whenever something plays, whatever our keyguard features say, and neither a device owner nor the music app may
 * write the switch. The PIN-gated Settings' "Media on the lock screen" row, under "Fingerprint", opens Android's Sound
 * & vibration page - there is no public action for its Media sub-page, so the summary names the rest of the way - on
 * the battery row's path ([systemPageIntent]): only an unsuspended system page, i.e. during "Pause all restrictions"
 * on a managed phone.
 *
 * Android doesn't let an app read the switch either (qa-home-round-code Q-1): `Settings.Secure.MEDIA_CONTROLS_LOCK_SCREEN`
 * came in Android 13, after S's bulk `@Readable` annotation, and has none, so SettingsProvider refuses it to any app
 * targeting S+ that isn't a system app - a device owner included. So the row has no state line: it always opens the
 * page, and the check is the switch on Android's page.
 */

/** The row's summary for [SystemPageAccess]: each names the way to the Media page's switch. */
fun mediaLockText(access: SystemPageAccess): Int = when (access) {
    SystemPageAccess.CAN_OPEN -> R.string.settings_mdm_media_lock_open
    SystemPageAccess.PAUSE_FIRST -> R.string.settings_mdm_media_lock_pause_first
    SystemPageAccess.WAIT -> R.string.settings_mdm_media_lock_wait
    SystemPageAccess.NO_PAGE -> R.string.settings_mdm_media_lock_no_page
}

object MediaLockScreen {
    /** Android's page to try, explicit to a system package ([systemPageIntent]): Sound & vibration (the Media page
     * has no public action). Empty outside a pause on a managed phone. Started without NEW_TASK, from the activity. */
    fun pageIntents(context: Context): List<Intent> = listOfNotNull(
        systemPageIntent(context, Intent(Settings.ACTION_SOUND_SETTINGS)),
    )
}
