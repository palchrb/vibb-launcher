package com.kidslauncher.mdm.ui.look

import com.kidslauncher.mdm.ui.day.DayActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kid's look (design 78 §5.4): what is stored, when a change may run, what it closes and in which order. */
class PageLookTest {
    /** A [LookHost] that records every call, over a page registry of names. */
    private class Recorder(var applied: String? = null) : LookHost {
        val calls = ArrayList<String>()
        val pages = PageRegistry<String>()

        override fun storedApplied(): String? = applied

        override fun store(choice: LookMode?, applied: LookMode?) {
            calls += "store choice=${choice?.wire} applied=${applied?.wire}"
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
        assertEquals(LookMode.LIGHT, chooseLook(host, LookMode.LIGHT, applied = LookMode.DARK, pageTaskStarted = 0))
        assertEquals(listOf("store choice=light applied=light", "finish [day]", "close tasks", "night mode light"), host.calls)
    }

    @Test
    fun `a tap on the applied look, or while a page task is started, stores the choice only`() {
        val same = Recorder(applied = "light")
        assertEquals(LookMode.LIGHT, chooseLook(same, LookMode.LIGHT, applied = LookMode.LIGHT, pageTaskStarted = 0))
        assertEquals(listOf("store choice=light applied=null"), same.calls)
        // It waits for Home (PageLook.onHomeResumed runs it then): the pages keep the applied look until.
        val waiting = Recorder(applied = "dark")
        assertEquals(LookMode.DARK, chooseLook(waiting, LookMode.LIGHT, applied = LookMode.DARK, pageTaskStarted = 1))
        assertEquals(listOf("store choice=light applied=null"), waiting.calls)
        assertEquals("dark", waiting.applied)
    }

    @Test
    fun `every unlocked start applies the stored look, the first run included`() {
        // The first run after the update: nothing stored - dark is written first, then applied.
        val first = Recorder(applied = null)
        assertEquals(LookMode.DARK, unlockedStart(first))
        assertEquals(listOf("store choice=null applied=dark", "night mode dark"), first.calls)
        // Every later start: no write, the system call always (it repairs a value the system lost).
        for ((stored, mode) in listOf("dark" to LookMode.DARK, "light" to LookMode.LIGHT, "auto" to LookMode.DARK)) {
            val host = Recorder(applied = stored)
            assertEquals(stored, mode, unlockedStart(host))
            assertEquals(stored, listOf("night mode ${mode.wire}"), host.calls)
        }
    }
}
