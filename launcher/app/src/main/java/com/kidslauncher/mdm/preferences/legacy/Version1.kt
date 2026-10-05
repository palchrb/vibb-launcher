package com.kidslauncher.mdm.preferences.legacy

import android.content.Context
import androidx.core.content.edit
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.PREFERENCE_VERSION
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.json.JSONException
import org.json.JSONObject


@Serializable
@Suppress("unused")
private class LegacyMapEntry(val key: AppInfo, val value: String)

private fun serializeMapAppInfo(value: Map<AppInfo, String>?): Set<String>? {
    return value?.map { (key, value) ->
        Json.encodeToString(LegacyMapEntry(key, value))
    }?.toSet()
}

private fun AppInfo.Companion.legacyDeserialize(serialized: String): AppInfo {
    val values = serialized.split(";")
    val packageName = values[0]
    val user = Integer.valueOf(values[1])
    val activityName = values.getOrNull(2) ?: "" // TODO
    return AppInfo(packageName, activityName, user)
}

private fun migrateAppInfoStringMap(key: String) {
    val preferences = LauncherPreferences.getSharedPreferences()
    serializeMapAppInfo(
        preferences.getStringSet(key, setOf())?.mapNotNull { entry ->
            try {
                val obj = JSONObject(entry)
                val info = AppInfo.legacyDeserialize(obj.getString("key"))
                val value = obj.getString("value")
                Pair(info, value)
            } catch (_: JSONException) {
                null
            }
        }?.toMap(HashMap())
    )?.let {
        preferences.edit { putStringSet(key, it) }
    }
}

private fun migrateAppInfoSet(key: String) {
    (LauncherPreferences.getSharedPreferences().getStringSet(key, setOf()) ?: return)
        .map(AppInfo.Companion::legacyDeserialize)
        .map(AppInfo::serialize)
        .toSet()
        .let { LauncherPreferences.getSharedPreferences().edit { putStringSet(key, it) } }
}

/**
 * Migrate preferences from version 1 (used until version j-0.0.18) to the current format
 * (see [PREFERENCE_VERSION])
 */
fun migratePreferencesFromVersion1(context: Context) {
    assert(LauncherPreferences.internal().versionCode() == 1)
    migrateAppInfoSet(LauncherPreferences.apps().keys().hidden())
    migrateAppInfoStringMap(LauncherPreferences.apps().keys().customNames())
    LauncherPreferences.internal().versionCode(2)

    migratePreferencesFromVersion2(context)
}
