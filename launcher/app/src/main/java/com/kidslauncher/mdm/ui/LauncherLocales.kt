package com.kidslauncher.mdm.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import com.kidslauncher.mdm.calls.OngoingCalls
import com.kidslauncher.mdm.server.dto.LauncherUi

/**
 * Applies the parent's language choice ([LauncherUi.language]) with the per-app locale API
 * (Android 13+, so always here - minSdk 34). Android persists it and recreates our activities,
 * so the switch is only made at a safe moment (QA step 5 #5): a sync just records the wish
 * ([remember]); [applyIfSafe] runs from `HomeActivity.onResume` - Home in front means no
 * in-call screen, PIN dialog or contact sheet is showing - and skips while a call is active.
 */
object LauncherLocales {
    private const val PREF = "launcher_language_wanted"

    /** After an accepted policy: what the parent wants ("" = follow the phone). */
    fun remember(context: Context, ui: LauncherUi?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF, resolveLauncherLocale(ui?.language))
        }
    }

    fun applyIfSafe(context: Context) {
        try {
            val desired = PreferenceManager.getDefaultSharedPreferences(context).getString(PREF, null) ?: return
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val current = manager.applicationLocales.toLanguageTags()
            if (!applyLocaleNow(current, desired, callActive(context))) return
            manager.applicationLocales =
                if (desired.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(desired)
            Log.i("LauncherLocales", "Launcher language: ${desired.ifEmpty { "system" }}")
        } catch (e: Exception) {
            Log.w("LauncherLocales", "Couldn't set the launcher language", e)
        }
    }

    private fun callActive(context: Context): Boolean {
        if (OngoingCalls.hasLiveCall) return true
        return try {
            context.getSystemService(TelecomManager::class.java)?.isInCall == true
        } catch (e: SecurityException) {
            false // no READ_PHONE_STATE (calls unmanaged): our in-call screen isn't used then
        }
    }
}
