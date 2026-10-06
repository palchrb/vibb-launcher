package com.kidslauncher.mdm.preferences

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.BuildConfig
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AbstractAppInfo
import com.kidslauncher.mdm.apps.AbstractAppInfo.Companion.INVALID_USER
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.apps.DetailedAppInfo
import com.kidslauncher.mdm.preferences.legacy.migratePreferencesFromVersion1
import com.kidslauncher.mdm.preferences.legacy.migratePreferencesFromVersion100
import com.kidslauncher.mdm.preferences.legacy.migratePreferencesFromVersion2
import com.kidslauncher.mdm.preferences.legacy.migratePreferencesFromVersion3
import com.kidslauncher.mdm.preferences.legacy.migratePreferencesFromVersion4
import com.kidslauncher.mdm.preferences.legacy.migratePreferencesFromVersionUnknown
import com.kidslauncher.mdm.ui.HomeActivity

/* Current version of the structure of preferences.
 * Increase when breaking changes are introduced and write an appropriate case in
 * `migratePreferencesToNewVersion`
 */
const val PREFERENCE_VERSION = 101
const val UNKNOWN_PREFERENCE_VERSION = -1
private const val TAG = "Launcher - Preferences"


/*
 * Tries to detect preferences written by older versions of the app
 * and migrate them to the current format.
 */
fun migratePreferencesToNewVersion(context: Context) {
    try {
        when (LauncherPreferences.internal().versionCode()) {
            // Check versions, make sure transitions between versions go well
            PREFERENCE_VERSION -> { /* the version installed and used previously are the same */
            }

            UNKNOWN_PREFERENCE_VERSION -> { /* still using the old preferences file */
                migratePreferencesFromVersionUnknown(context)
                Log.i(
                    TAG,
                    "migration of preferences  complete (${UNKNOWN_PREFERENCE_VERSION} -> ${PREFERENCE_VERSION})."
                )
            }

            1 -> {
                migratePreferencesFromVersion1(context)
                Log.i(TAG, "migration of preferences  complete (1 -> ${PREFERENCE_VERSION}).")
            }

            2 -> {
                migratePreferencesFromVersion2(context)
                Log.i(TAG, "migration of preferences  complete (2 -> ${PREFERENCE_VERSION}).")
            }

            3 -> {
                migratePreferencesFromVersion3(context)
                Log.i(TAG, "migration of preferences  complete (3 -> ${PREFERENCE_VERSION}).")
            }

            // There was a bug where instead of the preference version the app version was written.
            in 4..99 -> {
                migratePreferencesFromVersion4(context)
                Log.i(TAG, "migration of preferences  complete (4 -> ${PREFERENCE_VERSION}).")
            }

            100 -> {
                migratePreferencesFromVersion100(context)
                Log.i(TAG, "migration of preferences  complete (100 -> ${PREFERENCE_VERSION}).")
            }

            else -> {
                Log.w(
                    TAG,
                    "Shared preferences were written by a newer version of the app (${
                        LauncherPreferences.internal().versionCode()
                    })!"
                )
            }
        }
    } catch (e: Exception) {
        Log.e(TAG, "Unable to restore preferences:\n${e.stackTrace}")
        com.kidslauncher.mdm.crash.CrashReports.record(context, e)
        resetPreferences(context)
    }
}

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
    LauncherPreferences.clear()
    if (managedLast || lastRules != null) {
        prefs.edit().putBoolean(managedLastKey, managedLast).putString(lastRulesKey, lastRules).commit()
    }
    // Likewise "this phone has had time rules" (handy step 6), so a reset phone doesn't accept a
    // response without them.
    if (timeSeen) prefs.edit().putBoolean(timeSeenKey, true).commit()
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
