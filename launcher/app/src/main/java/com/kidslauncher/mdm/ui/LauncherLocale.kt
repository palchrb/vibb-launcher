package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.ui.look.PageKind
import com.kidslauncher.mdm.ui.messages.MessagesActivity

/*
 * The launcher's language (design 05-ui-photos-i18n.md) - pure, tested in LauncherLocaleTest.
 * The parent sets it per device (`launcher_ui.language`); LauncherLocales applies it with
 * `LocaleManager.setApplicationLocales`. [LauncherLanguage] asks for it again at every unlocked start: built when
 * the app had a night mode of its own, which made Android write its record without the languages (design 78 §5.3,
 * 2026-11-11). That night mode is gone (§12, revision 3); what is left of the reason is a process alive before the
 * unlock, and whether the ask stays is its own item (§12.9 point 2).
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
 * tasks that stay alive behind Home - Kalender's (either look's class, `PageKind`) and Meldinger's. The system
 * refuses the call while the process's newest activity is of another kind of task than at the last call that got
 * through (design 78 §5.3, 2026-11-11), and a stopped page behind Home stays the newest for as long as its task
 * lives; the switch would have created its activities again anyway.
 */
fun languageClosesTask(rootClassName: String?): Boolean =
    PageKind.DAY.has(rootClassName) || rootClassName == MessagesActivity::class.java.name

/**
 * How long after a refused resume the language is asked for once more. The system refuses the call while the
 * activity just left - a page, the PIN lock - is what the process follows, and that ends when the system destroys
 * it: after the transition to Home, once Home has reported idle (well under a second on a phone; Android's own
 * fallback for a missing idle report is 10 s). Two seconds is past the first and short enough that Home is still in
 * front; too early only means refused once more, and the next resume asks again.
 */
const val LANGUAGE_RETRY_DELAY_MS = 2_000L

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

    /** `setApplicationLocales`; false when the system refused it - the language of a refused call is kept nowhere,
     * neither for this boot nor on disk. Never throws. */
    fun ask(tags: String): Boolean

    /** Calls [LauncherLanguage.onRetry] once, [LANGUAGE_RETRY_DELAY_MS] from now, in place of one that waits. */
    fun retryLater()

    /** Drops a waiting [retryLater]. */
    fun cancelRetry()
}

/**
 * The moments the launcher's language goes to the system (design 78 §5.3, 2026-11-11). One per process: it remembers
 * a close that did not help. Main thread.
 */
internal class LauncherLanguage(private val host: LanguageHost) {
    /** The wanted value for which the page tasks were closed and the retry after it was still refused: they are not
     * closed for it again. Gone with an ask that got through or another wanted value. */
    private var closedInVain: String? = null

    /** The wanted value the waiting retry's resume closed the page tasks for, if it did. */
    private var retryClosedFor: String? = null

    /**
     * Every unlocked start, right after the look has read its store: a wanted language is asked for
     * unconditionally, once. A reboot can bring the launcher back without it (the record on disk), and a process
     * started before the unlock never got the record; equal to the system's value the call changes nothing. "Follow
     * the phone" is asked for only while the system still holds a language. Never during a call, as every switch:
     * Home's resume asks then - and after a refusal here too, which closes nothing.
     */
    fun atUnlockedStart() {
        val wanted = host.wanted() ?: return
        if (wanted.isEmpty() && host.current().isEmpty()) return
        if (host.callActive()) return
        host.ask(wanted)
    }

    /**
     * Home resumed: a real change ([applyLocaleNow]) is asked for now. Refused, the page tasks are closed - Home is
     * in front, so nothing on screen goes - and it is asked for once more: a page stopped behind Home is out of the
     * process at once. A page the kid came straight from is not (the system destroys it once Home is idle), nor the
     * PIN lock just left, so a resume that ends refused asks one last time [LANGUAGE_RETRY_DELAY_MS] later
     * ([onRetry]). The tasks are closed once per wanted value: after a close whose retry was refused too, a resume
     * only asks. A switch that got through at once leaves the page tasks alone.
     */
    fun onHomeResumed() {
        host.cancelRetry()
        val wanted = host.wanted() ?: return
        if (closedInVain != wanted) closedInVain = null
        if (!applyLocaleNow(host.current(), wanted, host.callActive())) return
        if (ask(wanted)) return
        val closed = closedInVain == null && host.closePageTasks()
        if (closed && ask(wanted)) return
        retryClosedFor = wanted.takeIf { closed }
        host.retryLater()
    }

    /** Home left the front: the retry is for a Home that is still there. */
    fun onHomePaused() = host.cancelRetry()

    /** The one retry of a resume that ended refused. Still refused after a close: that close did not help. */
    fun onRetry() {
        val wanted = host.wanted() ?: return
        if (!applyLocaleNow(host.current(), wanted, host.callActive())) return
        if (ask(wanted)) return
        if (retryClosedFor == wanted) closedInVain = wanted
    }

    private fun ask(wanted: String): Boolean = host.ask(wanted).also { if (it) closedInVain = null }
}
