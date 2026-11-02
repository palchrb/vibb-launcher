package com.kidslauncher.mdm.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Dagen is a task of its own (2026-10-27): in Home's task (`excludeFromRecents`, `clearTaskOnLaunch`) it had no
 * Recents card, and Home/Back were an in-task switch without Android's task-close animation. The three Dagen
 * activities share the `.day` affinity, stay in Recents, and Home's tile and the glance start it with one intent.
 */
class DayTaskTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }
    private fun activities(): List<Element> {
        val nodes = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file("AndroidManifest.xml")).getElementsByTagName("activity")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    @Test
    fun `Dagen's three activities share their own task and stay in recents`() {
        val day = activities().filter { it.getAttributeNS(ns, "name").startsWith(".ui.day.") }
        assertEquals(setOf(".ui.day.DayActivity", ".ui.day.EventActivity", ".ui.day.EventEditActivity"), day.map { it.getAttributeNS(ns, "name") }.toSet())
        for (activity in day) {
            val name = activity.getAttributeNS(ns, "name")
            assertEquals(name, "\${applicationId}.day", activity.getAttributeNS(ns, "taskAffinity"))
            assertEquals(name, "", activity.getAttributeNS(ns, "excludeFromRecents"))
            assertEquals(name, "false", activity.getAttributeNS(ns, "exported"))
        }
        assertEquals("singleTop", day.first { it.getAttributeNS(ns, "name") == ".ui.day.DayActivity" }.getAttributeNS(ns, "launchMode"))
        // No other activity of ours shares the affinity: a Home press leaves only Dagen's pages in that task.
        assertTrue(activities().none { !it.getAttributeNS(ns, "name").startsWith(".ui.day.") && it.getAttributeNS(ns, "taskAffinity") == "\${applicationId}.day" })
    }

    @Test
    fun `Home's tile and the glance open Dagen with the one intent, and its pages yield to the time-rule screen`() {
        val home = file("java/com/kidslauncher/mdm/ui/home/HomeGridAdapter.kt").readText()
        assertTrue(home.contains("GridTile.Day -> activity.startActivity(com.kidslauncher.mdm.ui.day.DayActivity.intent(activity))"))
        assertTrue(!home.contains("Intent(activity, com.kidslauncher.mdm.ui.day.DayActivity::class.java)"))
        val day = file("java/com/kidslauncher/mdm/ui/day/DayActivity.kt").readText()
        assertTrue(!day.contains("fun glanceIntent"))
        assertTrue(day.contains(".setLabel(getString(R.string.day_title))"))
        // qa-day-task-code Q-1: the guard's own rule, and CLEAR_TOP only with a day.
        val guard = day.substringAfter("internal fun redirectIfLocked(").substringBefore("\n        }\n")
        assertTrue(guard.contains("if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false"))
        assertTrue(guard.substringAfter("return false").contains("LockActivity.start(activity)"))
        // PLAN 2026-11-02: a rule set that allows Kalender lets the pages through, read from the lock in force (an
        // error is no).
        assertTrue(guard.indexOf("if (calendarUsableNow(activity)) return false") in 0 until guard.indexOf("LockActivity.start(activity)"))
        val usable = day.substringAfter("internal fun calendarUsableNow(").substringBefore("\n        }\n")
        assertTrue(usable.contains("calendarUsableUnder("))
        assertTrue(usable.substringAfter("catch (e: Exception)").contains("false"))
        val intent = day.substringAfter("fun intent(context: Context, day: LocalDate? = null): Intent =").substringBefore("\n            }\n")
        assertTrue(intent.contains("Intent(context, DayActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)"))
        assertTrue(intent.substringAfter("if (day != null) {").contains("addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)"))
        assertTrue(!intent.substringBefore("if (day != null) {").contains("CLEAR_TOP"))
        assertTrue(day.substringAfter("override fun onNewIntent(").contains("setIntent(intent)"))
        for (name in listOf("DayActivity", "EventActivity", "EventEditActivity")) {
            val code = file("java/com/kidslauncher/mdm/ui/day/$name.kt").readText()
            val onResume = code.substringAfter("override fun onResume()").substringBefore("\n    }\n")
            assertTrue(name, onResume.contains("redirectIfLocked(this)"))
            // Let through, the page watches the lock (a rule without Kalender beginning), and stops in onPause.
            assertTrue(name, onResume.contains("lockWatch.start()"))
            assertTrue(name, code.substringAfter("override fun onPause()").substringBefore("\n    }\n").contains("lockWatch.stop()"))
        }
        // Code QA M1: "Ny avtale"'s two fields start nothing (no selection, share, "process text" or smart-action menu),
        // like the re-enrol code field - always, not only under a rule.
        val layout = file("res/layout/activity_event_edit.xml").readText()
        assertEquals(2, Regex("""<EditText\b""").findAll(layout).count())
        val edit = file("java/com/kidslauncher/mdm/ui/day/EventEditActivity.kt").readText()
        for (field in listOf("eventEditWhat", "eventEditWhere")) {
            assertTrue(field, edit.contains("com.kidslauncher.mdm.ui.NoTextActions.apply(binding.$field)"))
        }
        assertTrue(file("java/com/kidslauncher/mdm/ui/ReEnrolDialog.kt").readText().contains("NoTextActions.apply(field)"))
        val helper = file("java/com/kidslauncher/mdm/ui/NoTextActions.kt").readText()
        for (part in listOf(
            "field.customSelectionActionModeCallback = none", "field.customInsertionActionModeCallback = none",
            "field.isLongClickable = false", "field.setTextClassifier(TextClassifier.NO_OP)",
            "override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false",
        )) {
            assertTrue(part, helper.contains(part))
        }
    }
}
