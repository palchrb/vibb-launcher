package com.kidslauncher.mdm.ui.look

import android.app.Activity
import android.app.ActivityManager
import android.content.Context
import android.content.res.Configuration
import android.util.Log
import android.view.ContextThemeWrapper
import androidx.appcompat.app.AppCompatDelegate
import com.kidslauncher.mdm.R

/*
 * The kid's look, Mørk or Lys (design 78): the kid's own pages - Kalender in step 1 - are drawn in the look the kid
 * picked in kid Settings; every other screen stays night. Nothing is asked of the system (§12, revision 3: no app
 * night mode). A page that can top a task of its own has a night and a light manifest activity ([PageKind]) whose
 * themes carry the window's ground, so the starting window Android draws is in the look; the page's own AppCompat
 * mode ([KidPageActivity]) follows its class, and the `values-notnight` page tokens follow from that. The rules are
 * pure - [LookState] over a [LookHost] - and in PageLookTest; [PageLook] keeps the Android host and the registry of
 * pages.
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

/** A look as each API that takes a night value wants it: AppCompat's local mode ([appCompat]) and a configuration's
 * night bit ([configuration]). */
internal class NightValues(val appCompat: Int, val configuration: Int)

/** The one place a look becomes a night constant. */
internal fun nightValues(mode: LookMode): NightValues = when (mode) {
    LookMode.DARK -> NightValues(AppCompatDelegate.MODE_NIGHT_YES, Configuration.UI_MODE_NIGHT_YES)
    LookMode.LIGHT -> NightValues(AppCompatDelegate.MODE_NIGHT_NO, Configuration.UI_MODE_NIGHT_NO)
}

/** What [LookState] reads and touches; the test's stand-in records the calls. */
internal interface LookHost {
    /** `kid_look.choice` as stored, null when absent. */
    fun storedChoice(): String?

    /** `kid_look.applied` as stored, null when absent. */
    fun storedApplied(): String?

    /** One synchronous write: [choice] when given, [applied] when given. Whether it reached the disk (`commit()`). */
    fun store(choice: LookMode?, applied: LookMode?): Boolean

    /** Finishes every live page ([PageRegistry.live]). */
    fun finishLivePages()

    /** Finishes and removes the page tasks ([closesTask]). */
    fun closePageTasks()

    /** A second read of the app's tasks, after [closePageTasks]: whether a page task is still there. */
    fun pageTaskLeft(): Boolean

    /** A refused change's log line (`E PageLook`). */
    fun refused(why: String)
}

/** §5.4.1: the look may change only while no activity of a page task is started. */
fun mayChange(pageTaskStarted: Int): Boolean = pageTaskStarted <= 0

/** A task a change closes, by its root activity's class name: Kalender's, whichever of its two classes is the root
 * (step 1; Meldinger's joins in step 2). */
fun closesTask(rootClassName: String?): Boolean = PageKind.DAY.has(rootClassName)

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
     * Every unlocked start (§5.4.3 as §12.4 leaves it): the store is read - `applied = dark` written first when
     * absent, the first run after the update - and nothing is asked of the system. A change that was waiting for Home
     * still waits after a process start.
     */
    fun atUnlockedStart() {
        val stored = host.storedApplied()
        if (stored == null) host.store(choice = null, applied = LookMode.DARK)
        applied = LookMode.parse(stored)
        choice = host.storedChoice()?.let(LookMode::parse) ?: applied
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
     * The change itself, in §12.2 R6's order, so that a living page task is always of the stored look: the live pages
     * finished and the page tasks removed first, the look stored after, memory last. A process that dies in between
     * leaves no page task and the old look. Two refusals keep the rule by not changing: a page task still there at a
     * second read (a removal that failed - the next open would put the new class on the old root), and a write that
     * did not reach the disk.
     */
    private fun change(mode: LookMode) {
        host.finishLivePages()
        host.closePageTasks()
        if (host.pageTaskLeft()) return refuse("a page task is still there after its removal")
        if (!host.store(choice = mode, applied = mode)) return refuse("the new look was not written")
        applied = mode
    }

    /**
     * A refused change: nothing has changed but the page tasks that closed. The kid's tap is lost - the choice goes
     * back to the applied look, and both are written again, so neither a waiting choice nor the values of a failed
     * write stay behind for a later start to carry out. Nothing tries again; the kid's next tap does.
     */
    private fun refuse(why: String) {
        host.refused("The look stays ${applied.wire}: $why")
        choice = applied
        host.store(choice = applied, applied = applied)
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

    /** A page's AppCompat night mode ([KidPageActivity]): the look its class is the variant of, so a window and its
     * content never disagree (§12.2 R4); the applied look for a page with one class (R9). */
    fun pageNightMode(page: Class<*>): Int = nightValues(PageKind.lookOf(page) ?: applied).appCompat

    /**
     * §12.2 R6's check, from [KidPageActivity]'s `onCreate`: a page whose class is the other look's closes its whole
     * task before it sets any content. A route, not only an assertion - an old card back after a power cut, or a
     * waiting change that ran between a page's start and its creation. The kid sees one starting window of the other
     * look, the task closes, and the next tap makes a right one. The page is finishing when this returns.
     */
    internal fun closeIfOtherLook(page: Activity) {
        val look = PageKind.lookOf(page.javaClass) ?: return
        if (look == applied) return
        Log.e(LOG_TAG, "${page.javaClass.simpleName} was created in the ${applied.wire} look: its task is closed")
        try {
            // The whole task, also when this page is not its root: the activity's own call removes the task only
            // from its root.
            page.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()
                .firstOrNull { it.taskInfo.taskId == page.taskId }?.finishAndRemoveTask()
        } catch (e: RuntimeException) {
            Log.w(LOG_TAG, "The other look's task didn't close as a task", e)
        }
        page.finishAndRemoveTask()
    }

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

        private fun tasks() = app.getSystemService(ActivityManager::class.java)?.appTasks.orEmpty()

        override fun storedChoice(): String? = prefs().getString(KEY_CHOICE, null)

        override fun storedApplied(): String? = prefs().getString(KEY_APPLIED, null)

        override fun store(choice: LookMode?, applied: LookMode?): Boolean {
            val edit = prefs().edit()
            if (choice != null) edit.putString(KEY_CHOICE, choice.wire)
            if (applied != null) edit.putString(KEY_APPLIED, applied.wire)
            return edit.commit()
        }

        override fun finishLivePages() {
            for (page in pages.live()) page.finish()
        }

        override fun closePageTasks() {
            for (task in tasks()) {
                try {
                    if (closesTask(task.taskInfo.baseIntent.component?.className)) task.finishAndRemoveTask()
                } catch (e: RuntimeException) {
                    // A task that went away between the list and the call - or one that didn't close: the second
                    // read tells the two apart.
                    Log.w(LOG_TAG, "A page task didn't close", e)
                }
            }
        }

        override fun pageTaskLeft(): Boolean = tasks().any { task ->
            try {
                closesTask(task.taskInfo.baseIntent.component?.className)
            } catch (e: RuntimeException) {
                Log.w(LOG_TAG, "A task couldn't be read; it is gone", e)
                false
            }
        }

        override fun refused(why: String) {
            Log.e(LOG_TAG, why)
        }
    }
}
