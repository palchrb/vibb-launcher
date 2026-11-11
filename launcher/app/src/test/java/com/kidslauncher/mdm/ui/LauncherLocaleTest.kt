package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.ui.day.DayActivity
import com.kidslauncher.mdm.ui.day.EventActivity
import com.kidslauncher.mdm.ui.day.LightDayActivity
import com.kidslauncher.mdm.ui.messages.MessagesActivity
import com.kidslauncher.mdm.ui.messages.ThreadActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LauncherLocaleTest {
    /** When the stand-in system refuses a call, keeping nothing of it. */
    private enum class Refuses { NEVER, WHILE_A_PAGE_TASK_LIVES, ALWAYS }

    /** A [LanguageHost] that records every call. */
    private class Recorder(
        var wanted: String?,
        var current: String = "",
        var call: Boolean = false,
        var pageTasks: Int = 0,
        var refuses: Refuses = Refuses.NEVER,
    ) : LanguageHost {
        val calls = ArrayList<String>()

        /** A [retryLater] waits. */
        var retryWaiting = false

        override fun wanted(): String? = wanted

        override fun current(): String = current

        override fun callActive(): Boolean = call

        override fun closePageTasks(): Boolean {
            calls += "close tasks"
            val closed = pageTasks > 0
            pageTasks = 0
            return closed
        }

        override fun ask(tags: String): Boolean {
            calls += "ask '$tags'"
            val refused = when (refuses) {
                Refuses.NEVER -> false
                Refuses.WHILE_A_PAGE_TASK_LIVES -> pageTasks > 0
                Refuses.ALWAYS -> true
            }
            if (!refused) current = tags
            return !refused
        }

        override fun retryLater() {
            calls += "retry later"
            retryWaiting = true
        }

        override fun cancelRetry() {
            retryWaiting = false
        }
    }

    @Test
    fun `nb and en are set, anything else follows the phone`() {
        assertEquals("nb", resolveLauncherLocale("nb"))
        assertEquals("en", resolveLauncherLocale("en"))
        assertEquals("nb", resolveLauncherLocale(" NB "))
        for (other in listOf(null, "", "system", "de", "no", "nb-NO")) {
            assertEquals(other.toString(), "", resolveLauncherLocale(other))
        }
    }

    @Test
    fun `only a real change is applied`() {
        assertFalse(localeChangeNeeded("", ""))
        assertFalse(localeChangeNeeded("nb", "nb"))
        assertTrue(localeChangeNeeded("", "nb"))
        assertTrue(localeChangeNeeded("nb", ""))
        assertTrue(localeChangeNeeded("en", "nb"))
    }

    @Test
    fun `never switched during a call`() {
        assertTrue(applyLocaleNow("", "nb", callActive = false))
        assertFalse(applyLocaleNow("", "nb", callActive = true))
        assertFalse(applyLocaleNow("nb", "nb", callActive = false))
    }

    @Test
    fun `an unlocked start asks for the wanted language, also when the system says it holds it`() {
        // After a reboot that lost it, and in a process started before the unlock.
        for (current in listOf("", "nb", "en")) {
            val host = Recorder(wanted = "nb", current = current)
            LauncherLanguage(host).atUnlockedStart()
            assertEquals(current, listOf("ask 'nb'"), host.calls)
        }
        // "Follow the phone" only while the system still holds a language.
        val held = Recorder(wanted = "", current = "nb")
        LauncherLanguage(held).atUnlockedStart()
        assertEquals(listOf("ask ''"), held.calls)
        val free = Recorder(wanted = "", current = "")
        LauncherLanguage(free).atUnlockedStart()
        assertEquals(emptyList<String>(), free.calls)
    }

    @Test
    fun `an unlocked start asks for nothing before a policy has said, or during a call`() {
        val unset = Recorder(wanted = null, current = "nb")
        LauncherLanguage(unset).atUnlockedStart()
        assertEquals(emptyList<String>(), unset.calls)
        val calling = Recorder(wanted = "nb", call = true)
        LauncherLanguage(calling).atUnlockedStart()
        assertEquals(emptyList<String>(), calling.calls)
    }

    @Test
    fun `a refused unlocked start asks once, closes nothing and leaves the rest to Home's resume`() {
        val host = Recorder(wanted = "nb", pageTasks = 1, refuses = Refuses.ALWAYS)
        LauncherLanguage(host).atUnlockedStart()
        assertEquals(listOf("ask 'nb'"), host.calls)
        assertEquals(1, host.pageTasks)
    }

    @Test
    fun `Home's resume asks only for a real change outside a call, and a switch that got through closes nothing`() {
        val change = Recorder(wanted = "nb", current = "", pageTasks = 2)
        LauncherLanguage(change).onHomeResumed()
        assertEquals(listOf("ask 'nb'"), change.calls)
        assertEquals(2, change.pageTasks)
        // Set now: the next resume touches nothing.
        change.calls.clear()
        LauncherLanguage(change).onHomeResumed()
        assertEquals(emptyList<String>(), change.calls)

        val back = Recorder(wanted = "", current = "nb")
        LauncherLanguage(back).onHomeResumed()
        assertEquals(listOf("ask ''"), back.calls)

        for (quiet in listOf(Recorder(wanted = null, current = "nb"), Recorder(wanted = "nb", current = "", call = true))) {
            LauncherLanguage(quiet).onHomeResumed()
            assertEquals(emptyList<String>(), quiet.calls)
        }
    }

    @Test
    fun `a refused switch closes the page tasks and asks once more`() {
        // A page stopped behind Home: out of the process with its task.
        val host = Recorder(wanted = "nb", current = "", pageTasks = 1, refuses = Refuses.WHILE_A_PAGE_TASK_LIVES)
        LauncherLanguage(host).onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks", "ask 'nb'"), host.calls)
        assertEquals("nb", host.current)
    }

    @Test
    fun `a resume that ends refused asks one last time later, while Home is in front and no call is live`() {
        // Home straight from a page: the page is still in the process until the system destroys it.
        val host = Recorder(wanted = "nb", current = "", pageTasks = 1, refuses = Refuses.ALWAYS)
        val language = LauncherLanguage(host)
        language.onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks", "ask 'nb'", "retry later"), host.calls)
        host.refuses = Refuses.NEVER
        host.calls.clear()
        language.onRetry()
        assertEquals(listOf("ask 'nb'"), host.calls)
        assertEquals("nb", host.current)

        // With nothing to close too (the PIN lock just left).
        val bare = Recorder(wanted = "nb", current = "", refuses = Refuses.ALWAYS)
        val second = LauncherLanguage(bare)
        second.onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks", "retry later"), bare.calls)
        // Home left the front, and came back: the old retry is gone, the resume has its own.
        second.onHomePaused()
        assertFalse(bare.retryWaiting)
        second.onHomeResumed()
        assertTrue(bare.retryWaiting)
        // A call came in before it ran, or the language is in place by then: nothing.
        bare.calls.clear()
        bare.call = true
        second.onRetry()
        bare.call = false
        bare.current = "nb"
        second.onRetry()
        assertEquals(emptyList<String>(), bare.calls)
    }

    @Test
    fun `a close that did not help is not made again for that language`() {
        // Something else of the launcher's is what the process follows: closing the pages changes nothing.
        val host = Recorder(wanted = "nb", current = "", pageTasks = 1, refuses = Refuses.ALWAYS)
        val language = LauncherLanguage(host)
        language.onHomeResumed()
        language.onRetry()
        assertEquals(listOf("ask 'nb'", "close tasks", "ask 'nb'", "retry later", "ask 'nb'"), host.calls)
        // The kid opened Kalender again and came Home, twice: asked each time, the page left alone.
        for (round in 1..2) {
            host.pageTasks = 1
            host.calls.clear()
            language.onHomeResumed()
            language.onRetry()
            assertEquals("round $round", listOf("ask 'nb'", "retry later", "ask 'nb'"), host.calls)
            assertEquals(1, host.pageTasks)
        }
        // Another wanted value starts over.
        host.wanted = "en"
        host.calls.clear()
        language.onHomeResumed()
        assertEquals(listOf("ask 'en'", "close tasks", "ask 'en'", "retry later"), host.calls)
        language.onRetry()
        // So does an ask that got through: were the language lost again, a refusal would close once more.
        host.refuses = Refuses.NEVER
        language.onHomeResumed()
        assertEquals("en", host.current)
        host.current = ""
        host.pageTasks = 1
        host.refuses = Refuses.ALWAYS
        host.calls.clear()
        language.onHomeResumed()
        assertEquals(listOf("ask 'en'", "close tasks", "ask 'en'", "retry later"), host.calls)
    }

    @Test
    fun `a retry that never ran decides nothing`() {
        val host = Recorder(wanted = "nb", current = "", pageTasks = 1, refuses = Refuses.ALWAYS)
        val language = LauncherLanguage(host)
        language.onHomeResumed()
        language.onHomePaused()
        host.pageTasks = 1
        host.calls.clear()
        language.onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks", "ask 'nb'", "retry later"), host.calls)
    }

    @Test
    fun `a refused switch closes Kalender's and Meldinger's tasks, by their roots, and no other`() {
        assertTrue(languageClosesTask(DayActivity::class.java.name))
        // Kalender's task in Lys has the light twin as its root (design 78 §12.2).
        assertTrue(languageClosesTask(LightDayActivity::class.java.name))
        assertTrue(languageClosesTask(MessagesActivity::class.java.name))
        val others = listOf(
            null,
            "",
            HomeActivity::class.java.name,
            EventActivity::class.java.name,
            ThreadActivity::class.java.name,
            "com.kidslauncher.mdm.lock.PinLockActivity",
        )
        for (other in others) assertFalse("$other", languageClosesTask(other))
    }
}
