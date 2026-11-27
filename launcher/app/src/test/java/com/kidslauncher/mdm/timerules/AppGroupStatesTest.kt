package com.kidslauncher.mdm.timerules

import android.app.AppOpsManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 139 §4.2-§4.3, §7: the groups' states, the lifts, the access, the timer, the warning and the cut. */
class AppGroupStatesTest {
    /** A Monday. */
    private val monday = "2026-11-23"
    private val spill = AppGroup(3, "Spill", listOf("org.example.game", "me.vibb.music"), listOf(60, 0, null, 60, 60, 120, 120))
    private val policy = TimePolicy(groups = listOf(spill))

    private fun ledger(day: String = monday, usedMin: Long = 0, extra: Int = 0) =
        ScreenTimeLedger(day = day, groupUsedMs = mapOf(3L to usedMin * 60_000L), groupExtraMinutes = mapOf(3L to extra))

    private fun state(ledger: ScreenTimeLedger, access: Boolean? = true, failed: Boolean = false, lifted: Boolean = false) =
        groupStates(policy, ledger, access, failed, lifted).single()

    @Test
    fun the_limit_of_the_weekday() {
        assertEquals(GroupStatus.OPEN, state(ledger(usedMin = 59)).status)
        assertEquals(60_000L, state(ledger(usedMin = 59)).remainingMs)
        assertEquals(GroupStatus.USED_UP, state(ledger(usedMin = 60)).status)
        assertEquals(setOf("org.example.game"), groupClosedApps(listOf(state(ledger(usedMin = 60)))))
        assertEquals(GroupStatus.OPEN, state(ledger(usedMin = 60, extra = 15)).status)
        // Tuesday's 0 closes all day; Wednesday's null is no limit.
        assertEquals(GroupStatus.NOT_TODAY, state(ledger(day = "2026-11-24")).status)
        assertEquals(GroupStatus.NO_LIMIT, state(ledger(day = "2026-11-25", usedMin = 600)).status)
        assertFalse(state(ledger(day = "2026-11-25", usedMin = 600)).closed)
        // Days of the wrong size are a limit of 0 (D6).
        val bad = TimePolicy(groups = listOf(spill.copy(days = listOf(60))))
        assertEquals(GroupStatus.NOT_TODAY, groupStates(bad, ledger(), true, false, false).single().status)
        assertTrue(groupsLimit(bad))
        assertFalse(groupsLimit(TimePolicy(groups = listOf(spill.copy(days = List(7) { null })))))
        assertFalse(groupsLimit(null))
    }

    @Test
    fun no_access_an_unreadable_ledger_or_a_failed_read_close_the_group() {
        for (access in listOf(false, null)) assertEquals(GroupStatus.CANT_COUNT, state(ledger(), access = access).status)
        assertEquals(GroupStatus.CANT_COUNT, state(ledger(), failed = true).status)
        val lifts = listOf(Lift(id = 7, target = TARGET_GROUP, minutes = 15, groupId = 3), Lift(id = 8, target = TARGET_BUDGET, minutes = 15))
        val unreadable = unreadableLedger(Clocks(1_795_000_000_000L, 1_000, 1), java.time.ZoneOffset.UTC, lifts)
        assertEquals(listOf(7L), unreadable.appliedGroupLifts)
        assertNull(unreadable.foldPoint)
        assertEquals(listOf(7L), applyGroupLifts(unreadable, lifts).appliedGroupLifts)
        assertEquals(emptyMap<Long, Int>(), applyGroupLifts(unreadable, lifts).groupExtraMinutes)
        val tp = TimePolicy(groups = listOf(spill.copy(days = List(7) { 60 })))
        assertEquals(GroupStatus.CANT_COUNT, groupStates(tp, unreadable, true, false, false).single().status)
    }

    @Test
    fun the_pause_and_the_override_lift_the_group_but_it_still_counts() {
        val used = state(ledger(usedMin = 70), lifted = true)
        assertEquals(GroupStatus.USED_UP, used.status)
        assertFalse(used.closed)
        assertEquals(70 * 60_000L, used.usedMs)
        assertEquals(emptySet<String>(), groupClosedApps(listOf(used)))
    }

    @Test
    fun a_group_lift_adds_once_per_id_to_its_group() {
        val lift = Lift(id = 9, target = TARGET_GROUP, minutes = 15, groupId = 3)
        val once = applyGroupLifts(ledger(), listOf(lift))
        assertEquals(15, once.groupExtraMinutes[3])
        assertEquals(once, applyGroupLifts(once, listOf(lift)))
        // A lift of a group that is gone counts as applied and adds nothing; a budget lift isn't a group's.
        val gone = applyGroupLifts(ledger(), listOf(lift.copy(id = 10, groupId = null), Lift(id = 11, target = TARGET_BUDGET, minutes = 30)))
        assertEquals(listOf(10L), gone.appliedGroupLifts)
        assertEquals(0, gone.groupExtraMinutes[3])
    }

    @Test
    fun the_access_is_read_the_services_way_and_an_empty_read_without_it_applies_nothing() {
        assertTrue(usageAccessGranted(AppOpsManager.MODE_ALLOWED, permissionGranted = false))
        assertTrue(usageAccessGranted(AppOpsManager.MODE_DEFAULT, permissionGranted = true))
        assertFalse(usageAccessGranted(AppOpsManager.MODE_DEFAULT, permissionGranted = false))
        assertFalse(usageAccessGranted(AppOpsManager.MODE_IGNORED, permissionGranted = true))
        assertEquals(ReadOutcome.APPLY, readOutcome(true, true, read = true))
        assertEquals(ReadOutcome.NO_ACCESS, readOutcome(false, true, read = true))
        assertEquals(ReadOutcome.NO_ACCESS, readOutcome(true, null, read = true))
        assertEquals(ReadOutcome.FAILED, readOutcome(true, true, read = false))
    }

    @Test
    fun the_timer_the_warning_and_the_cut() {
        val open = state(ledger(usedMin = 57))
        assertEquals(3 * 60_000L + 1_000L, groupRunsOutDelayMs(listOf(open, state(ledger(day = "2026-11-25")))))
        assertNull(groupRunsOutDelayMs(listOf(state(ledger(usedMin = 60)))))
        // Q4 (b): the last 5 minutes while one of its apps was on at the last fold.
        assertEquals(TimeLeftText.GroupLeft("Spill", 3), groupWarningText(listOf(open), setOf("org.example.game")))
        assertNull(groupWarningText(listOf(open), setOf("org.example.other")))
        assertNull(groupWarningText(listOf(state(ledger(usedMin = 50))), setOf("org.example.game")))
        assertNull(groupWarningText(listOf(state(ledger(usedMin = 57), lifted = true)), setOf("org.example.game")))
        // D7: the call's own app is held back, every other app of the group is cut at once.
        val closed = setOf("org.example.game", "org.example.chat")
        assertEquals(setOf("org.example.game"), groupSuspends(closed, voipPackage = "org.example.chat"))
        assertEquals(closed, groupSuspends(closed, voipPackage = null))
        assertEquals("org.example.game", cutTarget(closed, emptySet(), setOf("org.example.game"), voipPackage = null))
        assertNull(cutTarget(closed, closed, setOf("org.example.game"), voipPackage = null))
        assertNull(cutTarget(setOf("org.example.chat"), emptySet(), setOf("org.example.chat"), voipPackage = "org.example.chat"))
    }

    /** §4.1: without the declaration Vibb One isn't on Android's list and its page shows the switch greyed. */
    @Test
    fun the_manifest_declares_package_usage_stats() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        assertTrue(Regex("""android:name="android\.permission\.PACKAGE_USAGE_STATS"\s+tools:ignore="ProtectedPermissions"""").containsMatchIn(manifest))
    }
}
