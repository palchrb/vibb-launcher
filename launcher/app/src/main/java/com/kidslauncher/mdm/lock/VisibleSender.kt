package com.kidslauncher.mdm.lock

import android.app.ActivityOptions
import android.os.Build
import android.os.Bundle

/**
 * The options a PendingIntent's *sender* passes to start an activity from its visible window
 * (design 17 QA #3, design 21 step 4, design 25 §1.4): the device-owner and HOME exemptions are the
 * caller's (the intent's creator), not the sender's, so our send needs the sender's opt-in -
 * `MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE` on 36, `MODE_BACKGROUND_ACTIVITY_START_ALLOWED`
 * on 34/35 [AOSP `BackgroundActivityStartController`]. One place for VoipCalls, MusicSessions and
 * GlanceOpener; their guard tests pin the sends, not this helper.
 */
object VisibleSender {
    fun options(): Bundle {
        val options = ActivityOptions.makeBasic()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_IF_VISIBLE)
        } else {
            allowLegacy(options)
        }
        return options.toBundle()
    }

    /** API 34/35 have no ALLOW_IF_VISIBLE; ALLOWED is the same opt-in there (deprecated in 36). */
    @Suppress("DEPRECATION")
    private fun allowLegacy(options: ActivityOptions) {
        options.setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
    }
}
