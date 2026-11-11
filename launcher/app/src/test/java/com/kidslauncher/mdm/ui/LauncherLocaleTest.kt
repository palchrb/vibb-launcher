package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.ui.day.DayActivity
import com.kidslauncher.mdm.ui.day.EventActivity
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
        val host = Recorder(wanted = "nb", current = "", pageTasks = 1, refuses = Refuses.WHILE_A_PAGE_TASK_LIVES)
        LauncherLanguage(host).onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks", "ask 'nb'"), host.calls)
        assertEquals("nb", host.current)
    }

    @Test
    fun `a switch still refused starts over at every resume, with one more ask only when a task was closed`() {
        val host = Recorder(wanted = "nb", current = "", pageTasks = 1, refuses = Refuses.ALWAYS)
        val language = LauncherLanguage(host)
        language.onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks", "ask 'nb'"), host.calls)
        host.calls.clear()
        language.onHomeResumed()
        assertEquals(listOf("ask 'nb'", "close tasks"), host.calls)
        assertEquals("", host.current)

        host.refuses = Refuses.NEVER
        host.calls.clear()
        language.onHomeResumed()
        language.onHomeResumed()
        assertEquals(listOf("ask 'nb'"), host.calls)
    }

    @Test
    fun `a refused switch closes Kalender's and Meldinger's tasks, by their roots, and no other`() {
        assertTrue(languageClosesTask(DayActivity::class.java.name))
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
