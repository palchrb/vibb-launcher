package com.kidslauncher.mdm.preferences.legacy

import android.content.Context
import com.kidslauncher.mdm.preferences.LauncherPreferences

fun migratePreferencesFromVersion4(context: Context) {
    assert(LauncherPreferences.internal().versionCode() < 100)

    LauncherPreferences.internal().versionCode(100)
    migratePreferencesFromVersion100(context)
}