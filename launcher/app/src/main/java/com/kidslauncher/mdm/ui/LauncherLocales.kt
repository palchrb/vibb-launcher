package com.kidslauncher.mdm.ui

import android.app.ActivityManager
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
 * (Android 13+, so always here - minSdk 34). Android recreates our activities at a switch,
 * so it is only made at a safe moment (QA step 5 #5): a sync just records the wish
 * ([remember]); [applyIfSafe] runs from `HomeActivity.onResume` - Home in front means no
 * in-call screen, PIN dialog or contact sheet is showing - and skips while a call is active.
 * [atUnlockedStart] asks for the recorded wish again at every unlocked start, since Android
 * does not always keep it across a reboot, and a switch the system refused closes Kalender's
 * and Meldinger's tasks and asks once more (design 78 §5.3, 2026-11-11). The rules are the
 * pure [LauncherLanguage]; this is its Android host.
 */
object LauncherLocales {
    private const val LOG_TAG = "LauncherLocales"
    private const val PREF = "launcher_language_wanted"

    /** After an accepted policy: what the parent wants ("" = follow the phone). */
    fun remember(context: Context, ui: LauncherUi?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF, resolveLauncherLocale(ui?.language))
        }
    }

    /** `Application.initUnlocked`, right after `PageLook.atUnlockedStart`: the night mode first is the order in
     * which Android writes both to disk. */
    fun atUnlockedStart(context: Context) {
        try {
            language(context)?.atUnlockedStart()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the launcher language", e)
        }
    }

    fun applyIfSafe(context: Context) {
        try {
            language(context)?.onHomeResumed()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the launcher language", e)
        }
    }

    private fun language(context: Context): LauncherLanguage? {
        val app = context.applicationContext
        val manager = app.getSystemService(LocaleManager::class.java) ?: return null
        return LauncherLanguage(host(app, manager))
    }

    private fun host(app: Context, manager: LocaleManager): LanguageHost = object : LanguageHost {
        override fun wanted(): String? = PreferenceManager.getDefaultSharedPreferences(app).getString(PREF, null)

        override fun current(): String = manager.applicationLocales.toLanguageTags()

        override fun callActive(): Boolean {
            if (OngoingCalls.hasLiveCall) return true
            return try {
                app.getSystemService(TelecomManager::class.java)?.isInCall == true
            } catch (e: SecurityException) {
                false // no READ_PHONE_STATE (calls unmanaged): our in-call screen isn't used then
            }
        }

        override fun closePageTasks(): Boolean {
            val tasks = app.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
            var closed = false
            for (task in tasks) {
                try {
                    if (languageClosesTask(task.taskInfo.baseIntent.component?.className)) {
                        task.finishAndRemoveTask()
                        closed = true
                    }
                } catch (e: RuntimeException) {
                    // A task that went away between the list and the call.
                    Log.w(LOG_TAG, "A page task didn't close", e)
                }
            }
            return closed
        }

        override fun ask(tags: String): Boolean =
            try {
                manager.applicationLocales =
                    if (tags.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tags)
                Log.i(LOG_TAG, "Launcher language: ${tags.ifEmpty { "system" }}")
                true
            } catch (e: RuntimeException) {
                // The system server refuses the call while the process's newest activity is of another kind of
                // task than at its last such call ("Can't change activity type once set"), and keeps nothing.
                Log.w(LOG_TAG, "Couldn't set the launcher language", e)
                false
            }
    }
}
