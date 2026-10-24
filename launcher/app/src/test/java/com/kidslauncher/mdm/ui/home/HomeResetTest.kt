package com.kidslauncher.mdm.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * PLAN "Home starts at the top after an unlock" (the user, 2026-10-24): the grid goes back to the top when the lock is
 * left for Home or at the boot - never when the unlock's target is an app, never for our other starts, never on an
 * ordinary return from an app.
 */
class HomeResetTest {

    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }.readText()

    /** Code only: comments may name what the code does. */
    private fun code(path: String) = src(path)
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `only a leave for Home and the boot reset the grid`() {
        assertTrue(homeResetDue(HomeArrival.LOCK_LEFT_FOR_HOME))
        assertTrue(homeResetDue(HomeArrival.BOOT))
        assertFalse("a row's target opens over Home", homeResetDue(HomeArrival.LOCK_LEFT_FOR_APP))
        assertFalse("an update, a relock, the dark lock-up", homeResetDue(HomeArrival.OURS))
        assertFalse("the Home button from an app", homeResetDue(HomeArrival.PLAIN))
    }

    @Test
    fun `the arrival read from the intent`() {
        for (arrival in HomeArrival.entries.filter { it != HomeArrival.PLAIN }) {
            assertEquals(arrival, homeArrivalOf(brought = true, arrival = arrival.name))
        }
        assertEquals("an older build's start of ours, or junk", HomeArrival.OURS, homeArrivalOf(true, null))
        assertEquals(HomeArrival.OURS, homeArrivalOf(true, "SOMETHING_NEW"))
        assertEquals(HomeArrival.OURS, homeArrivalOf(true, "PLAIN"))
        // Not ours: whatever an extra says, the kid's or the system's Home - never a reset.
        assertEquals(HomeArrival.PLAIN, homeArrivalOf(brought = false, arrival = null))
        assertEquals(HomeArrival.PLAIN, homeArrivalOf(brought = false, arrival = "LOCK_LEFT_FOR_HOME"))
    }

    @Test
    fun `the lock's leave - Home unless a row's target opens over it`() {
        assertEquals(HomeArrival.LOCK_LEFT_FOR_HOME, lockLeaveArrival(hasTarget = false, lockWasRoot = false))
        assertEquals(HomeArrival.LOCK_LEFT_FOR_APP, lockLeaveArrival(hasTarget = true, lockWasRoot = false))
        // As the kiosk's lock-task root the opener opens nothing (design 25 §1.4): Home is what the kid sees.
        assertEquals(HomeArrival.LOCK_LEFT_FOR_HOME, lockLeaveArrival(hasTarget = true, lockWasRoot = true))
        assertEquals(HomeArrival.LOCK_LEFT_FOR_HOME, lockLeaveArrival(hasTarget = false, lockWasRoot = true))
    }

    @Test
    fun `the reset owed folds the arrivals before the resume`() {
        assertTrue(homeResetPending(false, HomeArrival.LOCK_LEFT_FOR_HOME))
        assertTrue(homeResetPending(false, HomeArrival.BOOT))
        assertFalse(homeResetPending(false, HomeArrival.OURS))
        assertFalse(homeResetPending(false, HomeArrival.PLAIN))
        assertFalse(homeResetPending(false, HomeArrival.LOCK_LEFT_FOR_APP))
        // A relock's queued Home right after the leave keeps it; an unlock into an app drops it.
        assertTrue(homeResetPending(true, HomeArrival.OURS))
        assertTrue(homeResetPending(true, HomeArrival.PLAIN))
        assertFalse(homeResetPending(true, HomeArrival.LOCK_LEFT_FOR_APP))
    }

    @Test
    fun `the lock's leave and the boot say why, every other start is ours`() {
        val front = code("lock/HomeFront.kt")
        assertTrue(front.substringAfter("fun intent(").substringBefore("fun bring(").contains(".putExtra(EXTRA_ARRIVAL, arrival.name)"))
        val go = code("lock/PinLockActivity.kt").substringAfter("private fun go(plan: LockLeave)").substringBefore("\n    }")
        val target = go.indexOf("val target = pendingOpen?.takeIf { PinLockRuntime.mode == LockMode.UNLOCKED }")
        val arrival = go.indexOf("val arrival = lockLeaveArrival(hasTarget = target != null, lockWasRoot = target?.lockWasRoot == true)")
        val bring = go.indexOf("HomeFront.bring(this, \"the lock left\", noAnimation = AndroidLock.faceOn, arrival = arrival)")
        val open = go.indexOf("target?.let { GlanceOpener.open(this, it, homeBelow = plan.homeFirst) }")
        assertTrue(go, target in 0 until arrival && arrival < bring && bring < open)
        assertTrue(code("lock/BootHome.kt").contains("HomeFront.bring(app, \"boot \$boot\", arrival = com.kidslauncher.mdm.ui.home.HomeArrival.BOOT)"))
        // Nobody else names an arrival: the update's, the relock's, the role change's, install mode's, the dark
        // lock-up's and the root's Homes are OURS (the default).
        val root = listOf("src/main/java/com/kidslauncher/mdm", "app/src/main/java/com/kidslauncher/mdm").map(::File).first { it.exists() }
        val namers = root.walk().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { Regex("arrival = (com\\.kidslauncher\\.mdm\\.ui\\.home\\.)?(HomeArrival\\.|arrival\\b)").containsMatchIn(code(it.relativeTo(root).path)) }
            .map { it.name }.sorted().toList()
        assertEquals(listOf("BootHome.kt", "HomeFront.kt", "PinLockActivity.kt"), namers)
    }

    @Test
    fun `Home's grid goes back to the top only from the rule`() {
        val home = code("ui/HomeActivity.kt")
        // The one unanimated scroll to the top, inside the reset - which runs only while owed.
        assertEquals(1, Regex("scrollToPositionWithOffset\\(").findAll(home).count())
        assertEquals(0, Regex("homeGrid\\.scrollToPosition\\(").findAll(home).count())
        val reset = home.substringAfter("private fun resetToTopIfDue() {").substringBefore("\n    }\n")
        assertTrue(reset, reset.trimStart().startsWith("if (!resetDue) return\n        resetDue = false"))
        assertTrue(reset.contains("gridLayout.scrollToPositionWithOffset(0, 0)"))
        assertTrue(reset.contains("binding.homeContactsScroll.scrollTo(0, 0)"))
        assertEquals("called from the content's resume only", 1, Regex("resetToTopIfDue\\(\\)").findAll(home).count() - 1)
        assertTrue(home.substringAfter("private fun resumeContent() {").substringBefore("\n    }\n").contains("resetToTopIfDue()"))
        // Owed only by the rule; dropped at the stop and when taken.
        val sets = Regex("resetDue = ([^\\n]+)").findAll(home).map { it.groupValues[1].trim() }.toList()
        assertEquals(listOf("homeResetPending(resetDue, arrival)"), sets.filter { it != "false" })
        assertTrue(home.substringAfter("override fun onStop() {").substringBefore("\n    }\n").contains("resetDue = false"))
        val note = home.substringAfter("private fun noteArrival(intent: Intent) {").substringBefore("\n    }\n")
        assertTrue(note.contains("homeArrivalOf(intent.getBooleanExtra(HomeFront.EXTRA_BROUGHT, false), intent.getStringExtra(HomeFront.EXTRA_ARRIVAL))"))
        // Read from every new intent, and from a first create (never a recreation).
        assertTrue(home.substringAfter("override fun onNewIntent(intent: Intent) {").substringBefore("\n    }\n").contains("noteArrival(intent)"))
        assertTrue(home.substringAfter("override fun onCreate(").substringBefore("\n    }\n").contains("if (savedInstanceState == null) intent?.let(::noteArrival)"))
        // The kid's press on Home keeps its own smooth scroll (launcher round 7 §4), from onNewIntent only.
        assertEquals(1, Regex("smoothScrollToPosition\\(0\\)").findAll(home).count())
    }
}
