package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.timerules.TimeLock
import com.kidslauncher.mdm.ui.LockRow
import com.kidslauncher.mdm.ui.messages.messagesUsableUnder
import com.kidslauncher.mdm.ui.timeLockRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The phone book is a task of its own (2026-11-16, as Kalender's and Meldinger's): in Home's task a Home press cleared it
 * with no task-close animation and Recents had no card for it. Its two classes share the `.phonebook` affinity and stay
 * in Recents, Home's tile and the time-rule screen open it with one intent, its card carries the Home tile's name, and
 * since Recents can bring it over the time-rule screen its resume yields to that screen where the rule doesn't allow
 * calls - exactly where the time-rule screen offers no phone-book row.
 */
class PhoneBookTaskTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun activities(): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file("AndroidManifest.xml")).getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `the phone book's two classes share their own task and stay in recents`() {
        val affinity = "\${applicationId}.phonebook"
        val book = activities().filter { it.getAttributeNS(ns, "taskAffinity") == affinity }
        assertEquals(setOf(".calls.PhoneBookActivity", ".calls.LightPhoneBookActivity"), book.map { it.getAttributeNS(ns, "name") }.toSet())
        for (activity in book) {
            val name = activity.getAttributeNS(ns, "name")
            assertEquals(name, "", activity.getAttributeNS(ns, "excludeFromRecents"))
            assertEquals(name, "false", activity.getAttributeNS(ns, "exported"))
            assertEquals(name, "singleTop", activity.getAttributeNS(ns, "launchMode"))
            assertEquals(name, "", activity.getAttributeNS(ns, "directBootAware"))
        }
    }

    @Test
    fun `Home's tile and the time-rule screen open it with the one intent, its card has the tile's name, and it yields to the time-rule screen`() {
        val home = file("java/com/kidslauncher/mdm/ui/home/HomeGridAdapter.kt").readText()
        assertTrue(home.contains("GridTile.PhoneBook -> activity.startActivity(PhoneBookActivity.intent(activity))"))
        assertTrue(home.contains("GridTile.PhoneBook -> glyphTile(holder, R.color.kid_phone_book, R.drawable.ic_kid_phone_book, R.string.calls_phone_book)"))
        // The time-rule screen's row lands on the page itself (code QA H1): nothing left above it, and its gate runs.
        val lock = file("java/com/kidslauncher/mdm/ui/LockActivity.kt").readText()
        assertTrue(lock.contains("is LockRow.PhoneBook -> startActivity(PhoneBookActivity.intent(this, toPage = true))"))
        val book = file("java/com/kidslauncher/mdm/calls/PhoneBookActivity.kt").readText()
        val builder = book.substringAfter("fun intent(context: Context, toPage: Boolean = false): Intent =").substringBefore("\n\n")
        assertTrue(builder.contains("FLAG_ACTIVITY_NEW_TASK") && builder.substringAfter("if (toPage)").contains("FLAG_ACTIVITY_CLEAR_TOP"))
        // Code QA M1: the page's intent never carries an action, data or type, so the task's base intent is always the
        // tile's and Android never stacks a second phone book; the ask rides as extras.
        val companion = book.substringAfter("companion object {")
        for (setter in listOf("setAction(", "setData(", "setDataAndType(", "setType(", "action =")) {
            assertTrue(setter, !companion.substringBefore("fun missedCallsIntent(").contains(setter))
        }
        assertTrue(book.contains(".setLabel(getString(R.string.calls_phone_book))") && book.contains(".setIcon(R.mipmap.ic_launcher)"))
        // The ask is taken once: not after a recreation, nor from Recents' history; under a rule it is dropped.
        assertTrue(book.contains("(intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) takeAsked(intent)"))
        val asked = book.substringAfter("private fun takeAsked(intent: Intent) {").substringBefore("\n    }\n")
        assertTrue(asked.indexOf("!usableNow(this)") in 0 until asked.indexOf("handleNumber(number)"))
        // The ways out of the card's Melding: another app gets a task of its own (code QA H1).
        val sheet = file("java/com/kidslauncher/mdm/calls/ContactSheet.kt").readText()
        assertTrue(sheet.substringAfter("fun openMessage(").substringBefore("\n    }\n").contains("FLAG_ACTIVITY_NEW_TASK"))
        // The guard's own rule: no lock, or the rule in force allows calls (an error is no).
        val guard = book.substringAfter("internal fun redirectIfLocked(").substringBefore("\n        }\n")
        assertTrue(guard.contains("if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false"))
        assertTrue(guard.indexOf("if (usableNow(activity)) return false") in 0 until guard.indexOf("LockActivity.start(activity)"))
        val usable = book.substringAfter("internal fun usableNow(").substringBefore("\n        }\n")
        assertTrue(usable.contains("phoneBookUsableUnder("))
        assertTrue(usable.substringAfter("catch (e: Exception)").contains("false"))
        // Checked first at every resume, then the lock is watched while in front, and no longer once paused.
        val onResume = book.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        assertTrue(onResume.indexOf("if (redirectIfLocked(this)) return") in 0 until onResume.indexOf("lockWatch.start()"))
        assertTrue(onResume.indexOf("lockWatch.start()") < onResume.indexOf("render()"))
        assertTrue(book.substringAfter("override fun onPause()").substringBefore("\n    }\n").contains("lockWatch.stop()"))
    }

    @Test
    fun `the phone book is usable under a rule exactly where the time-rule screen offers its row`() {
        val locks = listOf(
            null,
            TimeLock(LockReason.NONE),
            TimeLock(LockReason.RULE, callsAllowed = true),
            TimeLock(LockReason.RULE, callsAllowed = false),
            TimeLock(LockReason.SCHOOL, callsAllowed = false),
            TimeLock(LockReason.SCREEN_TIME, budgetExhausted = true, callsAllowed = true),
        )
        assertEquals(listOf(true, true, true, false, false, true), locks.map(::phoneBookUsableUnder))
        for (lock in locks) {
            // As Meldinger ("as the phone book"), and as the time-rule screen's row while calls are managed.
            assertEquals("$lock", messagesUsableUnder(lock), phoneBookUsableUnder(lock))
            if (lock == null || !lock.locked) continue
            val rows = timeLockRows(callsManaged = true, callsAllowed = lock.callsAllowed, phoneBookLabel = "Telefonbok", apps = emptyList())
            assertEquals("$lock", phoneBookUsableUnder(lock), rows.any { it is LockRow.PhoneBook })
        }
        assertFalse(phoneBookUsableUnder(TimeLock(LockReason.RULE, callsAllowed = false, calendarUsable = true)))
    }
}
