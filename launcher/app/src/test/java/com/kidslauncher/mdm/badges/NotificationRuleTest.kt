package com.kidslauncher.mdm.badges

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 11 §3 with qa-11-design.md #11-#13: the generic notification auto-cancel rule. */
class NotificationRuleTest {
    private val gms = "com.google.android.gms"
    private val own = setOf("com.kidslauncher.mdm", "com.kidslauncher.mdm.debug")
    private val policy = NagPolicy(
        enabled = true,
        managed = true,
        allowed = setOf("org.example.chat", "org.thoughtcrime.securesms"),
        essential = setOf("com.google.android.dialer", "com.google.android.deskclock", "com.google.android.inputmethod.latin", "com.oem.cb"),
        own = own,
    )

    private fun nag(
        pkg: String = gms,
        channel: String? = "screen_lock_nag",
        category: String? = null,
        ongoing: Boolean = false,
        clearable: Boolean = true,
        fsi: Boolean = false,
        insistent: Boolean = false,
    ) = NotificationFacts(pkg, channel, category, ongoing, clearable, fsi, insistent)

    private fun keep(reason: NagKeep) = NagVerdict.Keep(reason)

    @Test
    fun `a Play services nag on a managed phone is cancelled`() {
        assertEquals(NagVerdict.Cancel, nagVerdict(nag(), policy))
        assertEquals(NagVerdict.Cancel, nagVerdict(nag(pkg = "com.google.android.setupwizard", channel = null), policy))
        assertEquals(NagVerdict.Cancel, nagVerdict(nag(pkg = "com.google.android.permissioncontroller", channel = "safety_center"), policy))
    }

    @Test
    fun `nothing while the policy is unknown, the switch is off or the phone unmanaged`() {
        assertEquals(keep(NagKeep.POLICY_UNKNOWN), nagVerdict(nag(), null))
        assertEquals(keep(NagKeep.SWITCHED_OFF), nagVerdict(nag(), policy.copy(enabled = false)))
        assertEquals(keep(NagKeep.UNMANAGED), nagVerdict(nag(), policy.copy(managed = false)))
    }

    @Test
    fun `ours - release and debug - always stay`() {
        for (pkg in own) assertEquals(keep(NagKeep.OWN), nagVerdict(nag(pkg = pkg), policy))
    }

    @Test
    fun `allowed apps keep their notifications - the effective allowed set`() {
        assertEquals(keep(NagKeep.ALLOWED), nagVerdict(nag(pkg = "org.example.chat"), policy))
        assertEquals(keep(NagKeep.ALLOWED), nagVerdict(nag(pkg = "org.thoughtcrime.securesms"), policy))
    }

    @Test
    fun `cell broadcast under its AOSP, mainline and Google names stays - also when not resolved`() {
        for (pkg in listOf(
            "com.android.cellbroadcastreceiver", "com.android.cellbroadcastreceiver.module", "com.google.android.cellbroadcastreceiver",
            "com.android.cellbroadcastservice", "com.google.android.cellbroadcastservice",
        )) {
            assertEquals(pkg, keep(NagKeep.ESSENTIAL_PACKAGE), nagVerdict(nag(pkg = pkg), policy.copy(essential = emptySet())))
        }
        assertEquals(keep(NagKeep.ESSENTIAL_PACKAGE), nagVerdict(nag(pkg = "com.oem.cb"), policy))
    }

    @Test
    fun `system, phone, Telecom, dialer, clock and keyboard stay`() {
        for (pkg in listOf("android", "com.android.systemui", "com.android.phone", "com.android.server.telecom")) {
            assertEquals(pkg, keep(NagKeep.ESSENTIAL_PACKAGE), nagVerdict(nag(pkg = pkg), policy))
        }
        for (pkg in policy.essential) assertEquals(pkg, keep(NagKeep.ESSENTIAL_PACKAGE), nagVerdict(nag(pkg = pkg), policy))
    }

    @Test
    fun `GMS earthquake alerts stay - full-screen, insistent or an alert channel`() {
        assertEquals(keep(NagKeep.FULL_SCREEN), nagVerdict(nag(channel = "ealert_take_action", fsi = true), policy))
        assertEquals(keep(NagKeep.FULL_SCREEN), nagVerdict(nag(channel = "anything", fsi = true), policy))
        assertEquals(keep(NagKeep.INSISTENT), nagVerdict(nag(insistent = true), policy))
        for (channel in listOf("earthquake_alerts", "EALERT_BE_AWARE", "emergency_location", "personal_safety_crisis_alerts", "cmas_presidential")) {
            assertEquals(channel, keep(NagKeep.ESSENTIAL_CHANNEL), nagVerdict(nag(channel = channel), policy))
        }
    }

    @Test
    fun `calls, missed calls, alarms and stopwatches stay whoever posts them`() {
        for (category in listOf("call", "missed_call", "alarm", "stopwatch")) {
            assertEquals(category, keep(NagKeep.ESSENTIAL_CATEGORY), nagVerdict(nag(pkg = "org.example.voip", category = category), policy))
        }
        assertEquals(NagVerdict.Cancel, nagVerdict(nag(category = "recommendation"), policy))
    }

    @Test
    fun `ongoing and non-clearable notifications are never cancelled (lock task still blocks their taps)`() {
        assertEquals(keep(NagKeep.ONGOING), nagVerdict(nag(ongoing = true), policy))
        assertEquals(keep(NagKeep.NOT_CLEARABLE), nagVerdict(nag(clearable = false), policy))
    }

    @Test
    fun `the input type has no text, key or tag fields (privacy)`() {
        val fields = NotificationFacts::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("packageName", "channelId", "category", "ongoing", "clearable", "fullScreenIntent", "insistent"), fields)
        val counted = NagCount::class.java.declaredFields.map { it.name }.toSet()
        assertEquals(setOf("packageName", "channelId", "cancelled", "snoozed"), counted)
    }

    @Test
    fun `a re-posting app gets 3 cancels a minute, then snoozes`() {
        val budget = RepostBudget()
        val actions = (0 until 5).map { budget.action(gms, "nag", 1_000L + it * 100L) }
        assertEquals(listOf(NagAction.CANCEL, NagAction.CANCEL, NagAction.CANCEL, NagAction.SNOOZE, NagAction.SNOOZE), actions)
        // Another channel of the same app has its own budget.
        assertEquals(NagAction.CANCEL, budget.action(gms, "other", 1_500L))
        // A minute after the first cancels the budget is back.
        assertEquals(NagAction.CANCEL, budget.action(gms, "nag", 1_000L + REPOST_WINDOW_MS + 300L))
    }

    @Test
    fun `the budget forgets the oldest pairs past its cap`() {
        val budget = RepostBudget(maxKeys = 2)
        repeat(3) { budget.action("a", "c", 0L) }
        assertEquals(NagAction.SNOOZE, budget.action("a", "c", 1L))
        budget.action("b", "c", 2L)
        budget.action("d", "c", 3L)
        assertEquals(NagAction.CANCEL, budget.action("a", "c", 4L))
    }

    @Test
    fun `report counts are capped to 20 entries and 64-character ids, then start over`() {
        val counts = NagCounts()
        repeat(25) { i -> repeat(i + 1) { counts.record("pkg$i", "ch", NagAction.CANCEL) } }
        counts.record("x".repeat(200), "y".repeat(200), NagAction.SNOOZE)
        val (list, dropped) = counts.take()
        assertEquals(NAG_REPORT_MAX_ENTRIES, list.size)
        assertEquals("pkg24", list.first().packageName)
        assertEquals(25, list.first().cancelled)
        assertEquals(6, dropped)
        assertTrue(list.all { it.packageName.length <= NAG_REPORT_MAX_ID && (it.channelId?.length ?: 0) <= NAG_REPORT_MAX_ID })
        assertEquals(emptyList<NagCount>() to 0, counts.take())
    }

    @Test
    fun `counts beyond the key cap only add to the overflow`() {
        val counts = NagCounts(maxKeys = 1)
        counts.record("a", null, NagAction.CANCEL)
        counts.record("b", null, NagAction.SNOOZE)
        counts.record("a", null, NagAction.SNOOZE)
        assertEquals(2, counts.total("a", null))
        assertEquals(listOf(NagCount("a", null, 1, 1)) to 1, counts.take())
    }

    @Test
    fun `log lines are rate-limited to the 1st, 10th, 100th action`() {
        val due = (0..1000).filter { nagLogDue(it) }
        assertEquals(listOf(1, 10, 100, 1000), due)
        assertFalse(nagLogDue(0))
    }

    @Test
    fun `a group summary is only cancelled when every non-ongoing child would be (qa-11-code 6)`() {
        val nagChild = nag(channel = "nag")
        val alertChild = nag(channel = "earthquake_alerts")
        val callChild = nag(category = "call")
        val ongoingChild = nag(ongoing = true)
        fun children(vararg facts: NotificationFacts) = facts.map { it to nagVerdict(it, policy) }
        assertTrue(groupSummaryCancellable(children(nagChild, nagChild)))
        assertTrue("an ongoing child isn't cancelled with its summary", groupSummaryCancellable(children(nagChild, ongoingChild)))
        assertTrue("no children left", groupSummaryCancellable(emptyList()))
        assertFalse(groupSummaryCancellable(children(nagChild, alertChild)))
        assertFalse(groupSummaryCancellable(children(callChild)))
        assertFalse(groupSummaryCancellable(children(nag(fsi = true))))
    }
}
