package com.kidslauncher.mdm.ui

/*
 * The launcher's language (design 05-ui-photos-i18n.md) - pure, tested in LauncherLocaleTest.
 * The parent sets it per device (`launcher_ui.language`); LauncherLocales applies it with
 * `LocaleManager.setApplicationLocales`, which Android persists.
 */

/** Languages the launcher ships besides the English default. */
val SUPPORTED_LAUNCHER_LANGUAGES = setOf("nb", "en")

/** The language tag to set: "" = follow the phone ("system", missing or unknown values). */
fun resolveLauncherLocale(setting: String?): String =
    setting?.trim()?.lowercase()?.takeIf { it in SUPPORTED_LAUNCHER_LANGUAGES } ?: ""

/** [currentTags] = `LocaleList.toLanguageTags()` of the app locales now ("" = system). Only a
 * real change is applied: setting the same list again would still recreate every activity. */
fun localeChangeNeeded(currentTags: String, desired: String): Boolean = currentTags != desired

/**
 * Whether to switch now: only a real change, and never during a call - switching recreates every
 * activity, including the in-call screen (QA step 5 #5). LauncherLocales calls this only when Home
 * comes to the front, so no PIN dialog or contact sheet is open either; otherwise it waits.
 */
fun applyLocaleNow(currentTags: String, desired: String, callActive: Boolean): Boolean =
    localeChangeNeeded(currentTags, desired) && !callActive
