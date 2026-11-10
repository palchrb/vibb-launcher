package com.kidslauncher.mdm.ui.look

import android.app.Activity
import android.app.ActivityManager
import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.edit
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.day.DayActivity

/*
 * The kid's look, Mørk or Lys (design 78): the kid's own pages - Kalender in step 1 - are drawn in the look the kid
 * picked in kid Settings; every other screen stays night. The mode reaches a page three ways, all from here: the
 * app's night mode (`UiModeManager.setApplicationNightMode`, which the system keeps per package and builds a page's
 * starting window from), the page's own AppCompat mode ([KidPageActivity]) and the `values-notnight` page tokens
 * that follow from it. The rules are pure and in PageLookTest; [PageLook] is the Android half.
 */

/** The two looks. Stored by [wire]; absent or unknown reads as [DARK], today's look. */
enum class LookMode(val wire: String) {
    DARK("dark"),
    LIGHT("light"),
    ;

    companion object {
        fun parse(stored: String?): LookMode = entries.firstOrNull { it.wire == stored } ?: DARK
    }
}

/** What a change of look touches, in the order [runChange] calls it; the test's stand-in records the calls. */
internal interface LookHost {
    /** `kid_look.applied` as stored, null when absent. */
    fun storedApplied(): String?

    /** One synchronous write: [choice] when given, [applied] when given. */
    fun store(choice: LookMode?, applied: LookMode?)

    /** Finishes every live page ([PageRegistry.live]). */
    fun finishLivePages()

    /** Finishes and removes the page tasks ([closesTask]). */
    fun closePageTasks()

    /** The app's night mode, `setApplicationNightMode`. */
    fun applyNightMode(mode: LookMode)
}

/** §5.4.1: the look may change only while no activity of a page task is started. */
fun mayChange(pageTaskStarted: Int): Boolean = pageTaskStarted <= 0

/** A task a change closes, by its root activity's class name: Kalender's (step 1; Meldinger's joins in step 2). */
fun closesTask(rootClassName: String?): Boolean = rootClassName == DayActivity::class.java.name

/**
 * §5.4.2, the change itself: the choice and the applied mode are stored first, so a process that dies anywhere after
 * comes back in the new look ([unlockedStart] repairs the system's value); then nothing of the old look is left to
 * come back - the live pages finished, the page tasks removed; the system call last.
 */
internal fun runChange(host: LookHost, mode: LookMode) {
    host.store(choice = mode, applied = mode)
    host.finishLivePages()
    host.closePageTasks()
    host.applyNightMode(mode)
}

/**
 * The kid's tap: the applied mode after it. The look already applied changes nothing (and withdraws a waiting
 * change); while a page task is started ([mayChange]) only the choice is stored and the change waits for Home's
 * `onResume` ([PageLook.onHomeResumed]).
 */
internal fun chooseLook(host: LookHost, mode: LookMode, applied: LookMode, pageTaskStarted: Int): LookMode {
    if (mode == applied || !mayChange(pageTaskStarted)) {
        host.store(choice = mode, applied = null)
        return applied
    }
    runChange(host, mode)
    return mode
}

/**
 * §5.4.3, every unlocked start: the applied mode goes to the system unconditionally, the first run after the update
 * included (`applied = dark` written first when absent). Equal to the system's value it costs nothing; it repairs a
 * value the system lost or a crash between [runChange]'s write and its system call.
 */
internal fun unlockedStart(host: LookHost): LookMode {
    val stored = host.storedApplied()
    if (stored == null) host.store(choice = null, applied = LookMode.DARK)
    val applied = LookMode.parse(stored)
    host.applyNightMode(applied)
    return applied
}

/** The live pages (what a change finishes) and the count of started activities of the page tasks ([mayChange]). */
internal class PageRegistry<P> {
    private val live = LinkedHashSet<P>()

    var started = 0
        private set

    fun created(page: P) {
        live += page
    }

    fun destroyed(page: P) {
        live -= page
    }

    fun started() {
        started++
    }

    fun stopped() {
        if (started > 0) started--
    }

    fun live(): List<P> = live.toList()
}

object PageLook {
    private const val LOG_TAG = "PageLook"
    private const val PREFS = "kid_look"
    private const val KEY_CHOICE = "choice"
    private const val KEY_APPLIED = "applied"

    private val pages = PageRegistry<Activity>()

    /** What the kid picked; differs from [applied] only while a change waits for Home. Main thread, like the rest. */
    var choice = LookMode.DARK
        private set

    /** The look the pages are drawn in. [LookMode.DARK] until [atUnlockedStart] has read the stored one - nothing
     * reads `kid_look` before the first unlock (§5.4.3), and no page exists before it. */
    var applied = LookMode.DARK
        private set

    /** `Application.initUnlocked`, before any activity of this process: [unlockedStart]. */
    fun atUnlockedStart(context: Context) {
        val host = host(context)
        applied = unlockedStart(host)
        choice = prefs(context).getString(KEY_CHOICE, null)?.let(LookMode::parse) ?: applied
    }

    /** The kid's tap in kid Settings. */
    fun choose(context: Context, mode: LookMode) {
        applied = chooseLook(host(context), mode, applied, pages.started)
        choice = mode
    }

    /**
     * Home resumed: a change that had to wait ([chooseLook]) runs now. Not behind [mayChange] again - the page the
     * kid just left gets its `onStop` only after Home's `onResume`, so the count can still be one here; Home is in
     * front, which is the rule itself.
     */
    fun onHomeResumed(context: Context) {
        if (choice == applied) return
        runChange(host(context), choice)
        applied = choice
    }

    /** A page's AppCompat night mode ([KidPageActivity]): the applied look. */
    fun pageNightMode(): Int =
        if (applied == LookMode.LIGHT) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES

    /**
     * [context] as a page in [mode] would resolve it: a configuration context with that night bit under the page's
     * theme and colours, so what is inflated from it uses the real page tokens (kid Settings' preview, §7).
     */
    fun wrap(context: Context, mode: LookMode): Context {
        val config = Configuration(context.resources.configuration)
        val night = if (mode == LookMode.LIGHT) Configuration.UI_MODE_NIGHT_NO else Configuration.UI_MODE_NIGHT_YES
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
        val wrapped = ContextThemeWrapper(context.createConfigurationContext(config), R.style.KidPageTheme)
        wrapped.theme.applyStyle(R.style.PageColors, true)
        return wrapped
    }

    internal fun pageCreated(page: Activity) = pages.created(page)

    internal fun pageDestroyed(page: Activity) = pages.destroyed(page)

    /** An activity of a page task started or stopped. Public for the one that is no page but counts: the picture
     * viewer in Meldinger's task (step 2). */
    fun pageTaskStarted() = pages.started()

    fun pageTaskStopped() = pages.stopped()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun host(context: Context): LookHost {
        val app = context.applicationContext
        return object : LookHost {
            override fun storedApplied(): String? = prefs(app).getString(KEY_APPLIED, null)

            override fun store(choice: LookMode?, applied: LookMode?) {
                prefs(app).edit(commit = true) {
                    if (choice != null) putString(KEY_CHOICE, choice.wire)
                    if (applied != null) putString(KEY_APPLIED, applied.wire)
                }
            }

            override fun finishLivePages() {
                for (page in pages.live()) page.finish()
            }

            override fun closePageTasks() {
                val tasks = app.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
                for (task in tasks) {
                    try {
                        if (closesTask(task.taskInfo.baseIntent.component?.className)) task.finishAndRemoveTask()
                    } catch (e: RuntimeException) {
                        // A task that went away between the list and the call.
                        Log.w(LOG_TAG, "A page task didn't close", e)
                    }
                }
            }

            override fun applyNightMode(mode: LookMode) {
                val night = if (mode == LookMode.LIGHT) UiModeManager.MODE_NIGHT_NO else UiModeManager.MODE_NIGHT_YES
                app.getSystemService(UiModeManager::class.java).setApplicationNightMode(night)
            }
        }
    }
}
