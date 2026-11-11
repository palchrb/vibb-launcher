package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.ui.day.DayActivity
import com.kidslauncher.mdm.ui.messages.MessagesActivity

/*
 * The launcher's language (design 05-ui-photos-i18n.md) - pure, tested in LauncherLocaleTest.
 * The parent sets it per device (`launcher_ui.language`); LauncherLocales applies it with
 * `LocaleManager.setApplicationLocales`. Android keeps it while the phone is up, but writes it to disk without
 * the languages once the app has a night mode too (design 78 §5.3, 2026-11-11), so [LauncherLanguage] asks for it
 * again at every unlocked start.
 */

/** Languages the launcher ships besides the English default. */
val SUPPORTED_LAUNCHER_LANGUAGES = setOf("nb", "en")

/** The language tag to set: "" = follow the phone ("system", missing or unknown values). */
fun resolveLauncherLocale(setting: String?): String =
    setting?.trim()?.lowercase()?.takeIf { it in SUPPORTED_LAUNCHER_LANGUAGES } ?: ""

/** [currentTags] = `LocaleList.toLanguageTags()` of the app locales now ("" = system). Only a real change is a
 * switch: it recreates every activity. */
fun localeChangeNeeded(currentTags: String, desired: String): Boolean = currentTags != desired

/**
 * Whether to switch now: only a real change, and never during a call - switching recreates every
 * activity, including the in-call screen (QA step 5 #5). LauncherLocales calls this only when Home
 * comes to the front, so no PIN dialog or contact sheet is open either; otherwise it waits.
 */
fun applyLocaleNow(currentTags: String, desired: String, callActive: Boolean): Boolean =
    localeChangeNeeded(currentTags, desired) && !callActive

/**
 * A task a refused switch closes before it asks once more, by its root activity's class name: the launcher's own
 * tasks that stay alive behind Home - Kalender's and Meldinger's. The system refuses the call while the process's
 * newest activity is of another kind of task than at the last call that got through (design 78 §5.3, 2026-11-11),
 * and a stopped page behind Home stays the newest for as long as its task lives; the switch would have created
 * its activities again anyway.
 */
fun languageClosesTask(rootClassName: String?): Boolean =
    rootClassName == DayActivity::class.java.name || rootClassName == MessagesActivity::class.java.name

/** What [LauncherLanguage] reads and touches; the test's stand-in records the calls. */
internal interface LanguageHost {
    /** The parent's wish as stored: a tag, "" = follow the phone, null before any policy has said. */
    fun wanted(): String?

    /** The app's languages as the system holds them now (`LocaleList.toLanguageTags()`, "" = the phone's). */
    fun current(): String

    /** A call is live. */
    fun callActive(): Boolean

    /** Finishes and removes the tasks [languageClosesTask] names; whether there was one. */
    fun closePageTasks(): Boolean

    /** `setApplicationLocales`; false when the system refused it - it keeps nothing of a refused call. Never
     * throws. */
    fun ask(tags: String): Boolean
}

/** The two moments the launcher's language goes to the system (design 78 §5.3, 2026-11-11). Main thread. */
internal class LauncherLanguage(private val host: LanguageHost) {
    /**
     * Every unlocked start, right after the look's `setApplicationNightMode`: a wanted language is asked for
     * unconditionally. A reboot can bring the launcher back without it (the record on disk), and a process started
     * before the unlock never got the record; equal to the system's value the call changes nothing. "Follow the
     * phone" is asked for only while the system still holds a language. Never during a call, as every switch:
     * Home's resume asks then.
     */
    fun atUnlockedStart() {
        val wanted = host.wanted() ?: return
        if (wanted.isEmpty() && host.current().isEmpty()) return
        if (host.callActive()) return
        host.ask(wanted)
    }

    /**
     * Home resumed: a real change ([applyLocaleNow]) is asked for now. Refused, the page tasks are closed - Home is
     * in front, so nothing on screen goes - and it is asked for once more; with no such task, or refused again,
     * [LanguageHost.current] is as it was and the next resume starts over. A switch that got through at once leaves
     * the page tasks alone.
     */
    fun onHomeResumed() {
        val wanted = host.wanted() ?: return
        if (!applyLocaleNow(host.current(), wanted, host.callActive())) return
        if (host.ask(wanted)) return
        if (host.closePageTasks()) host.ask(wanted)
    }
}
