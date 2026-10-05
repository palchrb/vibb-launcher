package com.kidslauncher.mdm.preferences.legacy

import android.content.Context
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.PREFERENCE_VERSION


/**
 * Migrate preferences from version 2 (used until version 0.0.21) to the current format
 * (see [PREFERENCE_VERSION])
 */
fun migratePreferencesFromVersion2(context: Context) {
    assert(LauncherPreferences.internal().versionCode() == 2)
    LauncherPreferences.internal().versionCode(3)
    migratePreferencesFromVersion3(context)
}