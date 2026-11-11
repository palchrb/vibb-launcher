package com.kidslauncher.mdm.ui.look

import androidx.appcompat.app.AppCompatDelegate
import com.kidslauncher.mdm.ui.day.DayActivity
import com.kidslauncher.mdm.ui.day.EventActivity
import com.kidslauncher.mdm.ui.day.LightDayActivity
import com.kidslauncher.mdm.ui.day.LightEventActivity
import com.kidslauncher.mdm.ui.messages.LightMessagesActivity
import com.kidslauncher.mdm.ui.messages.LightThreadActivity
import com.kidslauncher.mdm.ui.messages.MessagesActivity
import com.kidslauncher.mdm.ui.messages.ThreadActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kid's look (design 78 §5.4, §12.2): what is stored and read back, when a change may run, what it closes and in
 * which order, when it is refused, and the table of a page's two classes. */
class PageLookTest {
    /** A [LookHost] that records every call, over a page registry of names. */
    private class Recorder(var applied: String? = null, var choice: String? = null) : LookHost {
        val calls = ArrayList<String>()
        val pages = PageRegistry<String>()

        /** A page task that is still there after its removal. */
        var taskLeft = false

        /** A write that doesn't reach the disk. */
        var writes = true

        override fun storedChoice(): String? = choice

        override fun storedApplied(): String? = applied

        override fun store(choice: LookMode?, applied: LookMode?): Boolean {
            calls += "store choice=${choice?.wire} applied=${applied?.wire}"
            if (!writes) return false
            if (choice != null) this.choice = choice.wire
            if (applied != null) this.applied = applied.wire
            return true
        }

        override fun finishLivePages() {
            calls += "finish ${pages.live()}"
        }

        override fun closePageTasks() {
            calls += "close tasks"
        }

        override fun pageTaskLeft(): Boolean {
            calls += "task left?"
            return taskLeft
        }

        override fun refused(why: String) {
            calls += "refused"
        }
    }

    /** The process after its unlocked start over [host], the start's own calls forgotten. */
    private fun started(host: Recorder): LookState = LookState(host).also {
        it.atUnlockedStart()
        host.calls.clear()
    }

    private val change = listOf("finish [day]", "close tasks", "task left?", "store choice=light applied=light")

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
    fun `a page is two classes, one per look, and its look is its class's`() {
        for (kind in PageKind.entries) {
            val night = kind.of(LookMode.DARK)
            val light = kind.of(LookMode.LIGHT)
            // The light twin is the night page itself under another name.
            assertEquals("$kind", night, light.superclass)
            assertEquals("$kind", LookMode.DARK, PageKind.lookOf(night))
            assertEquals("$kind", LookMode.LIGHT, PageKind.lookOf(light))
            assertTrue("$kind", kind.has(night.name) && kind.has(light.name))
            for (other in PageKind.entries - kind) assertFalse("$kind $other", other.has(night.name) || other.has(light.name))
        }
        assertEquals(DayActivity::class.java, PageKind.DAY.of(LookMode.DARK))
        assertEquals(LightEventActivity::class.java, PageKind.EVENT.of(LookMode.LIGHT))
        assertEquals(LightMessagesActivity::class.java, PageKind.MESSAGES.of(LookMode.LIGHT))
        assertEquals(ThreadActivity::class.java, PageKind.THREAD.of(LookMode.DARK))
        // A page with one class has no row: it takes the applied look (dark before any unlocked start, as here).
        assertNull(PageKind.lookOf(KidPageActivity::class.java))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, PageLook.pageNightMode(KidPageActivity::class.java))
        // A twin's content is its class's look whatever look is applied, so a window and its content never disagree.
        assertEquals(LookMode.DARK, PageLook.applied)
        assertEquals(AppCompatDelegate.MODE_NIGHT_NO, PageLook.pageNightMode(LightDayActivity::class.java))
        assertEquals(AppCompatDelegate.MODE_NIGHT_YES, PageLook.pageNightMode(DayActivity::class.java))
    }

    @Test
    fun `a change finishes every live page and closes Kalender's and Meldinger's tasks, whichever class is the root`() {
        val registry = PageRegistry<String>()
        registry.created("day")
        registry.created("event")
        registry.created("edit")
        registry.destroyed("edit")
        assertEquals(listOf("day", "event"), registry.live())
        // The task is known by its root: Kalender's and Meldinger's in either look, never Home's or the lock's - nor
        // a page that is no root (a thread, a picture, an event) or one in Home's task (the phone book, the lists).
        for (root in listOf(DayActivity::class.java, LightDayActivity::class.java, MessagesActivity::class.java, LightMessagesActivity::class.java)) {
            assertTrue(root.name, closesTask(root.name))
        }
        val others = listOf(
            null,
            "com.kidslauncher.mdm.ui.HomeActivity",
            "com.kidslauncher.mdm.lock.PinLockActivity",
            EventActivity::class.java.name,
            LightEventActivity::class.java.name,
            ThreadActivity::class.java.name,
            LightThreadActivity::class.java.name,
            "com.kidslauncher.mdm.ui.messages.PictureActivity",
            "com.kidslauncher.mdm.calls.PhoneBookActivity",
            "com.kidslauncher.mdm.ui.quickcontrols.WifiNetworksActivity",
        )
        for (root in others) assertFalse("$root", closesTask(root))
    }

    @Test
    fun `a change closes the pages and their task first and stores the look after`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        look.choose(LookMode.LIGHT, pageTaskStarted = 0)
        assertEquals(LookMode.LIGHT to LookMode.LIGHT, look.choice to look.applied)
        assertEquals(change, host.calls)
    }

    @Test
    fun `a page task still there after its removal refuses the change, and the tap is lost`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        host.taskLeft = true
        look.choose(LookMode.LIGHT, pageTaskStarted = 0)
        // Nothing of the new look was stored; the choice is the applied look again, in the store too.
        assertEquals(listOf("finish [day]", "close tasks", "task left?", "refused", "store choice=dark applied=dark"), host.calls)
        assertEquals(LookMode.DARK to LookMode.DARK, look.choice to look.applied)
        assertEquals("dark" to "dark", host.choice to host.applied)
        // The same for a change that waited for Home, whose choice was already written.
        look.choose(LookMode.LIGHT, pageTaskStarted = 1)
        assertEquals("light", host.choice)
        host.calls.clear()
        look.onHomeResumed(startInFlight = false)
        assertEquals(listOf("finish [day]", "close tasks", "task left?", "refused", "store choice=dark applied=dark"), host.calls)
        assertEquals(LookMode.DARK to LookMode.DARK, look.choice to look.applied)
        assertEquals("dark" to "dark", host.choice to host.applied)
        // Nothing tries again on its own, also once the task is gone.
        host.taskLeft = false
        host.calls.clear()
        look.onHomeResumed(startInFlight = false)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `a look that could not be written refuses the change, and the old values are written again`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        host.writes = false
        look.choose(LookMode.LIGHT, pageTaskStarted = 0)
        assertEquals(change + listOf("refused", "store choice=dark applied=dark"), host.calls)
        // The look in memory changes only with a write that got through.
        assertEquals(LookMode.DARK to LookMode.DARK, look.choice to look.applied)
        // The next tap is a whole change again.
        host.writes = true
        host.calls.clear()
        look.choose(LookMode.LIGHT, pageTaskStarted = 0)
        assertEquals(change, host.calls)
        assertEquals(LookMode.LIGHT to LookMode.LIGHT, look.choice to look.applied)
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
    fun `an unlocked start only reads the store, and writes dark on the first run`() {
        // The first run after the update: nothing stored - dark is written.
        val first = Recorder(applied = null)
        val fresh = LookState(first).also { it.atUnlockedStart() }
        assertEquals(LookMode.DARK to LookMode.DARK, fresh.choice to fresh.applied)
        assertEquals(listOf("store choice=null applied=dark"), first.calls)
        // Every later start: no write, and nothing closed or asked.
        for ((stored, mode) in listOf("dark" to LookMode.DARK, "light" to LookMode.LIGHT, "auto" to LookMode.DARK)) {
            val host = Recorder(applied = stored, choice = stored)
            val look = LookState(host).also { it.atUnlockedStart() }
            assertEquals(stored, mode to mode, look.choice to look.applied)
            assertEquals(stored, emptyList<String>(), host.calls)
        }
    }

    @Test
    fun `a change that waits for Home survives a process start`() {
        // Lys tapped while a page task was started, then the process died: the stored choice is read at the next
        // unlocked start, the pages keep the applied look.
        val host = Recorder(applied = "dark", choice = "light")
        val look = LookState(host).also { it.atUnlockedStart() }
        assertEquals(LookMode.LIGHT to LookMode.DARK, look.choice to look.applied)
        assertEquals(emptyList<String>(), host.calls)
    }

    @Test
    fun `Home's resume runs a waiting change once, and nothing when none waits`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        look.onHomeResumed(startInFlight = false)
        assertEquals(emptyList<String>(), host.calls)
        look.choose(LookMode.LIGHT, pageTaskStarted = 1)
        host.calls.clear()
        // Not behind mayChange again: the page the kid left is stopped only after Home has resumed.
        look.onHomeResumed(startInFlight = false)
        assertEquals(change, host.calls)
        assertEquals(LookMode.LIGHT to LookMode.LIGHT, look.choice to look.applied)
        // Every later resume: the applied look is the choice, so Kalender's task is left alone.
        host.calls.clear()
        look.onHomeResumed(startInFlight = false)
        assertEquals(emptyList<String>(), host.calls)
    }

    /** Step 1's code QA L5: a Home resume between a page's start and its creation (the unlock on the way to a page the
     * glance opened) does not run a waiting change - it would close the task just made. The next resume does. */
    @Test
    fun `a waiting change holds back while a page's start is in flight`() {
        val host = Recorder(applied = "dark")
        host.pages.created("day")
        val look = started(host)
        look.choose(LookMode.LIGHT, pageTaskStarted = 1)
        host.calls.clear()
        look.onHomeResumed(startInFlight = true)
        assertEquals(emptyList<String>(), host.calls)
        assertEquals(LookMode.LIGHT to LookMode.DARK, look.choice to look.applied)
        look.onHomeResumed(startInFlight = false)
        assertEquals(change, host.calls)
        assertEquals(LookMode.LIGHT to LookMode.LIGHT, look.choice to look.applied)
    }

    @Test
    fun `a start is in flight from its ask until its page is created, or for five seconds at most`() {
        val registry = PageRegistry<String>()
        assertFalse(registry.startInFlight(1_000))
        registry.startAsked(1_000)
        assertTrue(registry.startInFlight(1_000))
        assertTrue(registry.startInFlight(1_000 + START_IN_FLIGHT_MS - 1))
        // A start that never made a page (refused, or a singleTop page brought back) stops holding.
        assertFalse(registry.startInFlight(1_000 + START_IN_FLIGHT_MS))
        // The page created: nothing in flight.
        registry.startAsked(9_000)
        registry.created("thread")
        assertFalse(registry.startInFlight(9_001))
        // A clock that went back (another boot's elapsed time) holds nothing.
        registry.startAsked(9_000)
        assertFalse(registry.startInFlight(8_000))
    }
}
