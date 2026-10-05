package com.kidslauncher.mdm.preferences.legacy

import android.content.Context
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.PREFERENCE_VERSION

fun migratePreferencesFromVersion100(context: Context) {
    assert(PREFERENCE_VERSION == 101)
    assert(LauncherPreferences.internal().versionCode() == 100)

    LauncherPreferences.internal().versionCode(101)
}