package com.kidslauncher.mdm.preferences.legacy

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.theme.ColorTheme


private fun migrateBooleanPreference(
    oldPrefs: SharedPreferences,
    newPreferences: SharedPreferences.Editor,
    oldKey: String,
    newKey: String,
    default: Boolean
) {
    val s = oldPrefs.getBoolean(oldKey, default)
    newPreferences.putBoolean(newKey, s)
}

private const val TAG = "Preferences ? -> 1"

/**
 * Try to migrate from a very old preference version, where no version number was stored
 * and a different file was used.
 */
fun migratePreferencesFromVersionUnknown(context: Context) {

    Log.i(
        TAG,
        "Unknown preference version, trying to restore preferences from old version."
    )

    val oldPrefs = context.getSharedPreferences(
        "V3RYR4ND0MK3YCR4P",
        Context.MODE_PRIVATE
    )
    if (!oldPrefs.contains("startedBefore")) {
        Log.i(TAG, "No old preferences found. Probably this is a fresh installation.")
        return
    }

    LauncherPreferences.getSharedPreferences().edit {

        migrateBooleanPreference(
            oldPrefs,
            this,
            "startedBefore",
            "internal.started_before",
            false
        )

        migrateBooleanPreference(oldPrefs, this, "timeVisible", "clock.time_visible", true)
        migrateBooleanPreference(oldPrefs, this, "dateVisible", "clock.date_visible", true)
        migrateBooleanPreference(
            oldPrefs,
            this,
            "dateLocalized",
            "clock.date_localized",
            false
        )
        migrateBooleanPreference(
            oldPrefs,
            this,
            "dateTimeFlip",
            "clock.date_time_flip",
            false
        )
        migrateBooleanPreference(
            oldPrefs,
            this,
            "disableTimeout",
            "display.disable_timeout",
            false
        )
        migrateBooleanPreference(
            oldPrefs,
            this,
            "useFullScreen",
            "display.use_full_screen",
            true
        )
    }

    when (oldPrefs.getString("theme", "finn")) {
        "finn" -> {
            LauncherPreferences.theme().colorTheme(ColorTheme.DEFAULT)
        }

        "dark" -> {
            LauncherPreferences.theme().colorTheme(ColorTheme.DARK)
        }
    }
    LauncherPreferences.internal().versionCode(1)
    Log.i(TAG, "migrated preferences to version 1.")

    migratePreferencesFromVersion1(context)
}