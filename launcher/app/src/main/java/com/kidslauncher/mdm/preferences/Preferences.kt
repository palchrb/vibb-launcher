package com.kidslauncher.mdm.preferences

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.apps.AbstractAppInfo.Companion.INVALID_USER
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.apps.DetailedAppInfo
import com.kidslauncher.mdm.ui.HomeActivity

/* The version of the preferences' structure, written by [resetPreferences]. Raise it with a migration when a
 * breaking change comes. */
const val PREFERENCE_VERSION = 101
private const val TAG = "Launcher - Preferences"

fun resetPreferences(context: Context) {
    Log.i(TAG, "Resetting preferences")
    // The call-policy memory survives a reset (QA direct-boot note 3): without it a reset phone
    // read as "calls unmanaged" and allowed every call. CallPolicyStore's DE witness backs this up.
    val prefs = LauncherPreferences.getSharedPreferences()
    val managedLastKey = context.getString(R.string.settings_mdm_calls_managed_last_key)
    val lastRulesKey = context.getString(R.string.settings_mdm_last_call_rules_key)
    val timeSeenKey = context.getString(R.string.settings_mdm_time_policy_seen_key)
    val managedLast = prefs.getBoolean(managedLastKey, false)
    val lastRules = prefs.getString(lastRulesKey, null)
    val timeSeen = prefs.getBoolean(timeSeenKey, false)
    val modesSeenKey = context.getString(R.string.settings_mdm_channel_modes_seen_key)
    val modesSeen = prefs.getBoolean(modesSeenKey, false)
    // Design 77 (QA L3, L8): whether we took the SMS role, from whom, and which grants we took away.
    val smsKept = com.kidslauncher.mdm.sms.SmsPrefs.KEPT_ON_RESET.mapNotNull { key -> prefs.all[key]?.let { key to it } }
    LauncherPreferences.clear()
    if (smsKept.isNotEmpty()) {
        val editor = prefs.edit()
        for ((key, value) in smsKept) {
            @Suppress("UNCHECKED_CAST")
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value as Set<String>)
            }
        }
        editor.commit()
    }
    if (managedLast || lastRules != null) {
        prefs.edit().putBoolean(managedLastKey, managedLast).putString(lastRulesKey, lastRules).commit()
    }
    // Likewise "this phone has had time rules" (handy step 6), so a reset phone doesn't accept a
    // response without them.
    if (timeSeen) prefs.edit().putBoolean(timeSeenKey, true).commit()
    // And "this phone has had channel modes" (design 77 D2).
    if (modesSeen) prefs.edit().putBoolean(modesSeenKey, true).commit()
    LauncherPreferences.internal().versionCode(PREFERENCE_VERSION)

    val hidden: MutableSet<AbstractAppInfo> = mutableSetOf()

    if (!BuildConfig.DEBUG) {
        val launcher = DetailedAppInfo.fromAppInfo(
            AppInfo(
                BuildConfig.APPLICATION_ID,
                HomeActivity::class.java.name,
                INVALID_USER
            ), context
        )
        launcher?.getRawInfo()?.let { hidden.add(it) }
        Log.i(TAG, "Hiding ${launcher?.getRawInfo()}")
    }
    LauncherPreferences.apps().hidden(hidden)
}
