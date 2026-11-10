package com.kidslauncher.mdm.ui.look

import android.app.UiModeManager
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import com.kidslauncher.mdm.ui.day.DayActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kid's look (design 78 §5.4): what is stored and read back, when a change may run, what it closes and in which
 * order, and the night value each API gets. */
class PageLookTest {
    /** A [LookHost] that records every call, over a page registry of names. */
    private class Recorder(var applied: String? = null, var choice: String? = null) : LookHost {
        val calls = ArrayList<String>()
        val pages = PageRegistry<String>()

        override fun storedChoice(): String? = choice

        override fun storedApplied(): String? = applied

        override fun store(choice: LookMode?, applied: LookMode?) {
            calls += "store choice=${choice?.wire} applied=${applied?.wire}"
            if (choice != null) this.choice = choice.wire
            if (applied != null) this.applied = applied.wire
        }

        override fun finishLivePages() {
            calls += "finish ${pages.live()}"
        }

        override fun closePageTasks() {
            calls += "close tasks"
        }

        override fun applyNightMode(mode: LookMode) {
            calls += "night mode ${mode.wire}"
        }
    }

    /** The process after its unlocked start over [host], the start's own calls forgotten. */
    private fun started(host: Recorder): LookState = LookState(host).also {
        it.atUnlockedStart()
        host.calls.clear()
    }

    @Test
    fun `a stored look reads by its name, absent or unknown as dark`() {
        assertEquals(LookMode.DARK, LookMode.parse("dark"))
        assertEquals(LookMode.LIGHT, LookMode.parse("light"))
        for (stored in listOf(null, "", "auto", "Light", "LIGHT")) assertEquals("$stored", LookMode.DARK, LookMode.parse(stored))
        assertEquals(listOf("dark", "light"), LookMode.entries.map { it.wire })
    }

    @Test
    fun `the look may change only while nothing of a page task is started`() {
        val registry = PageRegistry<String>()
        assertTrue("no page at all", mayChange(registry.started))
        // Kalender in front.
        registry.started()
        assertFalse(mayChange(registry.started))
        // A picture in front of a thread: the page stopped, the picture - no page, but of the page task - started.
        registry.started()
        registry.stopped()
        assertFalse("a picture in front", mayChange(registry.started))
        registry.stopped()
        assertTrue(mayChange(registry.started))
        // A translucent activity over a page: the page is paused, never stopped.
        registry.started()
        assertFalse("a translucent activity over a page", mayChange(registry.started))
        // Home in front, every page stopped; a stray second stop never takes the count below zero.
        registry.stopped()
        registry.stopped()
        assertEquals(0, registry.started)
        assertTrue(mayChange(registry.started))
    }

    @Test
    fun `a change finishes every live page and closes Kalender's task`() {
        val registry = PageRegistry<String>()
        registry.created("day")
        registry.created("event")
        registry.created("edit")
        registry.destroyed("edit")
        assertEquals(listOf("day", "event"), registry.live())
        // The task is known by its root: Kalender's, never Home's or the lock's.
        assertTrue(closesTask(DayActivity::class.java.name))
        for (root in listOf(null, "com.kidslauncher.mdm.ui.HomeActivity", "com.kidslauncher.mdm.lock.PinLockActivity", "com.kidslauncher.mdm.ui.day.EventActivity")) {
            assertFalse("$root", closesTask(root))
        }
    }

    @Test
    fun `a change stores the choice and the applied look before it closes anything, the system call last`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        look.choose(LookMode.LIGHT, pageTaskStarted = 0)
        assertEquals(LookMode.LIGHT to LookMode.LIGHT, look.choice to look.applied)
        assertEquals(listOf("store choice=light applied=light", "finish [day]", "close tasks", "night mode light"), host.calls)
    }

    @Test
    fun `a tap on the applied look, or while a page task is started, stores the choice only`() {
        val same = Recorder(applied = "light")
        val unchanged = started(same)
        unchanged.choose(LookMode.LIGHT, pageTaskStarted = 0)
        assertEquals(LookMode.LIGHT, unchanged.applied)
        assertEquals(listOf("store choice=light applied=null"), same.calls)
        // It waits for Home (onHomeResumed runs it then): the pages keep the applied look until.
        val waiting = Recorder(applied = "dark")
        val look = started(waiting)
        look.choose(LookMode.LIGHT, pageTaskStarted = 1)
        assertEquals(LookMode.LIGHT to LookMode.DARK, look.choice to look.applied)
        assertEquals(listOf("store choice=light applied=null"), waiting.calls)
        assertEquals("dark", waiting.applied)
    }

    @Test
    fun `every unlocked start applies the stored look, the first run included`() {
        // The first run after the update: nothing stored - dark is written first, then applied.
        val first = Recorder(applied = null)
        val fresh = LookState(first).also { it.atUnlockedStart() }
        assertEquals(LookMode.DARK to LookMode.DARK, fresh.choice to fresh.applied)
        assertEquals(listOf("store choice=null applied=dark", "night mode dark"), first.calls)
        // Every later start: no write, the system call always (it repairs a value the system lost).
        for ((stored, mode) in listOf("dark" to LookMode.DARK, "light" to LookMode.LIGHT, "auto" to LookMode.DARK)) {
            val host = Recorder(applied = stored, choice = stored)
            val look = LookState(host).also { it.atUnlockedStart() }
            assertEquals(stored, mode to mode, look.choice to look.applied)
            assertEquals(stored, listOf("night mode ${mode.wire}"), host.calls)
        }
    }

    @Test
    fun `a change that waits for Home survives a process start`() {
        // Lys tapped while a page task was started, then the process died: the stored choice is read at the next
        // unlocked start, the pages keep the applied look.
        val host = Recorder(applied = "dark", choice = "light")
        val look = LookState(host).also { it.atUnlockedStart() }
        assertEquals(LookMode.LIGHT to LookMode.DARK, look.choice to look.applied)
        assertEquals(listOf("night mode dark"), host.calls)
    }

    @Test
    fun `Home's resume runs a waiting change once, and nothing when none waits`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        look.onHomeResumed()
        assertEquals(emptyList<String>(), host.calls)
        look.choose(LookMode.LIGHT, pageTaskStarted = 1)
        host.calls.clear()
        // Not behind mayChange again: the page the kid left is stopped only after Home has resumed.
        look.onHomeResumed()
        assertEquals(listOf("store choice=light applied=light", "finish [day]", "close tasks", "night mode light"), host.calls)
        assertEquals(LookMode.LIGHT to LookMode.LIGHT, look.choice to look.applied)
        // Every later resume: the applied look is the choice, so Kalender's task is left alone.
        host.calls.clear()
        look.onHomeResumed()
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `a look is one night value per API, dark the night everywhere`() {
        val dark = nightValues(LookMode.DARK)
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, dark.appCompat)
        assertEquals(UiModeManager.MODE_NIGHT_YES, dark.system)
        assertEquals(Configuration.UI_MODE_NIGHT_YES, dark.configuration)
        val light = nightValues(LookMode.LIGHT)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, light.appCompat)
        assertEquals(UiModeManager.MODE_NIGHT_NO, light.system)
        assertEquals(Configuration.UI_MODE_NIGHT_NO, light.configuration)
    }
}
