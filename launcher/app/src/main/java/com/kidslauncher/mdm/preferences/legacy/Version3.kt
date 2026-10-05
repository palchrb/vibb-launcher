package com.kidslauncher.mdm.preferences.legacy

import android.content.Context
import android.content.SharedPreferences
import android.content.SharedPreferences.Editor
import androidx.core.content.edit
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.preferences.PREFERENCE_VERSION
import com.kidslauncher.mdm.preferences.serialization.MapAbstractAppInfoStringPreferenceSerializer
import com.kidslauncher.mdm.preferences.serialization.SetAbstractAppInfoPreferenceSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Migrate preferences from version 3 (used until version 0.0.23) to the current format
 * (see [PREFERENCE_VERSION])
 */


fun deserializeSet(value: Set<String>?): Set<AppInfo>? {
    return value?.map {
        Json.decodeFromString<AppInfo>(it)
    }?.toHashSet()
}

fun deserializeMap(value: Set<String>?): HashMap<AppInfo, String>? {
    return value?.associateTo(HashMap()) {
        val entry = Json.decodeFromString<MapEntry>(it)
        Pair(entry.key, entry.value)
    }
}

@Serializable
private class MapEntry(val key: AppInfo, val value: String)

private fun migrateSetAppInfo(key: String, preferences: SharedPreferences, editor: Editor) {
    try {
        val serializer = SetAbstractAppInfoPreferenceSerializer()
        val set = HashSet<AbstractAppInfo>()

        deserializeSet(preferences.getStringSet(key, null))?.let {
            set.addAll(it)
        }
        @Suppress("UNCHECKED_CAST")
        editor.putStringSet(
            key,
            serializer.serialize(set as java.util.Set<AbstractAppInfo>) as Set<String>?
        )
    } catch (e: Exception) {
        e.printStackTrace()
        editor.putStringSet(key, null)
    }

}

private fun migrateMapAppInfoString(key: String, preferences: SharedPreferences, editor: Editor) {
    try {
        val serializer = MapAbstractAppInfoStringPreferenceSerializer()
        val map = HashMap<AbstractAppInfo, String>()

        deserializeMap(preferences.getStringSet(key, null))?.let {
            map.putAll(it)
        }
        @Suppress("UNCHECKED_CAST")
        editor.putStringSet(key, serializer.serialize(map) as Set<String>?)
    } catch (e: Exception) {
        e.printStackTrace()
        editor.putStringSet(key, null)
    }
}

fun migratePreferencesFromVersion3(context: Context) {
    assert(LauncherPreferences.internal().versionCode() == 3)

    val preferences = LauncherPreferences.getSharedPreferences()
    preferences.edit {
        migrateSetAppInfo(LauncherPreferences.apps().keys().hidden(), preferences, this)
        migrateMapAppInfoString(LauncherPreferences.apps().keys().customNames(), preferences, this)
    }

    LauncherPreferences.internal().versionCode(4)
    migratePreferencesFromVersion4(context)
}