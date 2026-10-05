package com.kidslauncher.mdm.ui

import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import android.util.Log
import com.kidslauncher.mdm.server.dto.LauncherUi

/**
 * Applies the parent's language choice ([LauncherUi.language]) with the per-app locale API
 * (Android 13+, so always here - minSdk 34). Android persists it and recreates our activities;
 * only a real change is applied ([localeChangeNeeded]). A server without `launcher_ui` means
 * "follow the phone".
 */
object LauncherLocales {
    fun apply(context: Context, ui: LauncherUi?) {
        try {
            val manager = context.getSystemService(LocaleManager::class.java) ?: return
            val desired = resolveLauncherLocale(ui?.language)
            val current = manager.applicationLocales.toLanguageTags()
            if (!localeChangeNeeded(current, desired)) return
            manager.applicationLocales =
                if (desired.isEmpty()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(desired)
            Log.i("LauncherLocales", "Launcher language: ${desired.ifEmpty { "system" }}")
        } catch (e: Exception) {
            Log.w("LauncherLocales", "Couldn't set the launcher language", e)
        }
    }
}
