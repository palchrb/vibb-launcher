package com.kidslauncher.mdm.ui

import android.app.ActivityManager
import android.app.LocaleManager
import android.content.Context
import android.os.Handler
import android.os.LocaleList
import android.os.Looper
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
 * and Meldinger's tasks and asks once more, then one last time a little later while Home is
 * still in front (design 78 §5.3, 2026-11-11). The rules are the pure [LauncherLanguage], one
 * for the process; this is its Android host. Main thread, but for [remember].
 */
object LauncherLocales {
    private const val LOG_TAG = "LauncherLocales"
    private const val PREF = "launcher_language_wanted"

    private val main = Handler(Looper.getMainLooper())
    private var language: LauncherLanguage? = null
    private val retry = Runnable {
        try {
            language?.onRetry()
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't set the launcher language", e)
        }
    }

    /** After an accepted policy: what the parent wants ("" = follow the phone). */
    fun remember(context: Context, ui: LauncherUi?) {
        PreferenceManager.getDefaultSharedPreferences(context).edit {
            putString(PREF, resolveLauncherLocale(ui?.language))
        }
    }

    /** `Application.initUnlocked`, right after `PageLook.atUnlockedStart` (which asks nothing of the system since
     * design 78's revision 3, so the order no longer matters). */
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

    /** `HomeActivity.onPause`: a retry that waits is for a Home still in front. */
    fun onHomePaused() {
        language?.onHomePaused()
    }

    private fun language(context: Context): LauncherLanguage? {
        language?.let { return it }
        val app = context.applicationContext
        val manager = app.getSystemService(LocaleManager::class.java) ?: return null
        return LauncherLanguage(host(app, manager)).also { language = it }
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
                // task than at its last such call that got through ("Can't change activity type once set"), and
                // keeps the language nowhere.
                Log.w(LOG_TAG, "Couldn't set the launcher language", e)
                false
            }

        override fun retryLater() {
            main.removeCallbacks(retry)
            main.postDelayed(retry, LANGUAGE_RETRY_DELAY_MS)
        }

        override fun cancelRetry() = main.removeCallbacks(retry)
    }
}
