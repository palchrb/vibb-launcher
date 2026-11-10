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
 * that follow from it. The rules are pure - [LookState] over a [LookHost] - and in PageLookTest; [PageLook] keeps the
 * Android host and the registry of pages.
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

/** A look as each API that takes a night value wants it: AppCompat's local mode ([appCompat]), the app's night mode
 * ([system]) and a configuration's night bit ([configuration]). */
internal class NightValues(val appCompat: Int, val system: Int, val configuration: Int)

/** The one place a look becomes a night constant. */
internal fun nightValues(mode: LookMode): NightValues = when (mode) {
    LookMode.DARK ->
        NightValues(AppCompatDelegate.MODE_NIGHT_YES, UiModeManager.MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_YES)
    LookMode.LIGHT ->
        NightValues(AppCompatDelegate.MODE_NIGHT_NO, UiModeManager.MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_NO)
}

/** What [LookState] reads and touches - a change's four in the order it calls them; the test's stand-in records the
 * calls. */
internal interface LookHost {
    /** `kid_look.choice` as stored, null when absent. */
    fun storedChoice(): String?

    /** `kid_look.applied` as stored, null when absent. */
    fun storedApplied(): String?

    /** One synchronous write: [choice] when given, [applied] when given. */
    fun store(choice: LookMode?, applied: LookMode?)

    /** Finishes every live page ([PageRegistry.live]). */
    fun finishLivePages()

    /** Finishes and removes the page tasks ([closesTask]). */
    fun closePageTasks()

    /** The app's night mode, `setApplicationNightMode`. Never throws: a call that failed is logged. */
    fun applyNightMode(mode: LookMode)
}

/** §5.4.1: the look may change only while no activity of a page task is started. */
fun mayChange(pageTaskStarted: Int): Boolean = pageTaskStarted <= 0

/** A task a change closes, by its root activity's class name: Kalender's (step 1; Meldinger's joins in step 2). */
fun closesTask(rootClassName: String?): Boolean = rootClassName == DayActivity::class.java.name

/** The kid's look as this process holds it, and the three moments it moves. Main thread. */
internal class LookState(private val host: LookHost) {
    /** What the kid picked; differs from [applied] only while a change waits for Home. */
    var choice = LookMode.DARK
        private set

    /** The look the pages are drawn in. [LookMode.DARK] until [atUnlockedStart] has read the stored one - nothing
     * reads `kid_look` before the first unlock (§5.4.3), and no page exists before it. */
    var applied = LookMode.DARK
        private set

    /**
     * §5.4.3, every unlocked start: the applied mode goes to the system unconditionally, the first run after the
     * update included (`applied = dark` written first when absent). Equal to the system's value it costs nothing;
     * it repairs a value the system lost or a crash between [change]'s write and its system call. Both stored values
     * are read before that call - a change that was waiting for Home still waits after a process start.
     */
    fun atUnlockedStart() {
        val stored = host.storedApplied()
        if (stored == null) host.store(choice = null, applied = LookMode.DARK)
        applied = LookMode.parse(stored)
        choice = host.storedChoice()?.let(LookMode::parse) ?: applied
        host.applyNightMode(applied)
    }

    /**
     * The kid's tap. The look already applied changes nothing (and withdraws a waiting change); while a page task is
     * started ([mayChange]) only the choice is stored and the change waits for Home's `onResume` ([onHomeResumed]).
     */
    fun choose(mode: LookMode, pageTaskStarted: Int) {
        choice = mode
        if (mode == applied || !mayChange(pageTaskStarted)) {
            host.store(choice = mode, applied = null)
            return
        }
        change(mode)
    }

    /**
     * Home resumed: a change that had to wait ([choose]) runs now, once. Not behind [mayChange] again - the page the
     * kid just left gets its `onStop` only after Home's `onResume`, so the count can still be one here; Home is in
     * front, which is the rule itself.
     */
    fun onHomeResumed() {
        if (choice != applied) change(choice)
    }

    /**
     * §5.4.2, the change itself: the choice and the applied mode are stored first, so a process that dies anywhere
     * after comes back in the new look ([atUnlockedStart] repairs the system's value); then nothing of the old look
     * is left to come back - the live pages finished, the page tasks removed; the system call last.
     */
    private fun change(mode: LookMode) {
        host.store(choice = mode, applied = mode)
        applied = mode
        host.finishLivePages()
        host.closePageTasks()
        host.applyNightMode(mode)
    }
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

    /** Made at the first call that brings a context: the unlocked start's, before any page exists. */
    private var state: LookState? = null

    /** [LookState.choice]. */
    val choice: LookMode get() = state?.choice ?: LookMode.DARK

    /** [LookState.applied]. */
    val applied: LookMode get() = state?.applied ?: LookMode.DARK

    /** `Application.initUnlocked`, before any activity of this process. */
    fun atUnlockedStart(context: Context) = state(context).atUnlockedStart()

    /** The kid's tap in kid Settings. */
    fun choose(context: Context, mode: LookMode) = state(context).choose(mode, pages.started)

    /** `HomeActivity.onResume`. */
    fun onHomeResumed(context: Context) = state(context).onHomeResumed()

    /** A page's AppCompat night mode ([KidPageActivity]): the applied look. */
    fun pageNightMode(): Int = nightValues(applied).appCompat

    /**
     * [context] as a page in [mode] would resolve it: a configuration context with that night bit under the page's
     * theme and colours, so what is inflated from it uses the real page tokens (kid Settings' preview, §7).
     */
    fun wrap(context: Context, mode: LookMode): Context {
        val config = Configuration(context.resources.configuration)
        config.uiMode = (config.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or nightValues(mode).configuration
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

    private fun state(context: Context): LookState =
        state ?: LookState(host(context.applicationContext)).also { state = it }

    private fun host(app: Context): LookHost = object : LookHost {
        private fun prefs() = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        override fun storedChoice(): String? = prefs().getString(KEY_CHOICE, null)

        override fun storedApplied(): String? = prefs().getString(KEY_APPLIED, null)

        override fun store(choice: LookMode?, applied: LookMode?) {
            prefs().edit(commit = true) {
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
            try {
                app.getSystemService(UiModeManager::class.java).setApplicationNightMode(nightValues(mode).system)
            } catch (e: RuntimeException) {
                // The pages draw in the stored look all the same (their own AppCompat mode); only a starting window
                // can be the other look's, until the next unlocked start asks again.
                Log.e(LOG_TAG, "The app's night mode wasn't set", e)
            }
        }
    }
}
