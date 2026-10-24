package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.LoggedCallType.INCOMING_ANSWERED
import com.kidslauncher.mdm.calls.LoggedCallType.MISSED
import com.kidslauncher.mdm.calls.LoggedCallType.OUTGOING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 12-missed-calls.md (decisions after QA review): what our notification says and does. */
class MissedCallNoticeTest {

    private val pappa = RuleContact(id = 1, name = "Pappa", number = "+4790000002", inbound = true, outbound = true)
    private val mamma = RuleContact(id = 2, name = "Mamma", number = "+4790000001", inbound = true, outbound = true)
    private val bestemor = RuleContact(id = 3, name = "Bestemor", number = "+4790000003", inbound = true)

    private fun managed(callsEnabled: Boolean = true) =
        CallPolicyState.Managed(CallRules(callsEnabled = callsEnabled, contacts = listOf(pappa, mamma, bestemor)))

    /** The contacts the notification may name: the phone book's. */
    private fun view(state: CallPolicyState = managed()) = phoneBookView(state) { false }.contacts

    private fun missed(number: String, atMs: Long, id: Long, unread: Boolean = true) =
        CallLogEntry(number, MISSED, atMs, id = id, unread = unread)

    private fun notice(
        vararg log: CallLogEntry,
        seen: Map<String, Long> = emptyMap(),
        upTo: Long = Long.MAX_VALUE,
        contacts: List<RuleContact> = view(),
    ) = missedCallNotice(log.toList(), contacts, "47", seen, upTo)

    @Test
    fun `one call from one contact`() {
        val result = notice(missed("90000002", 100, id = 7))
        assertEquals(MissedCallNotice(1, listOf(MissedContact("+4790000002", "Pappa", 1, 100)), "+4790000002", 100, 7), result)
        assertEquals(NoticeText(NoticeTitle.ONE_CALL_FROM, 1, "Pappa", null), noticeText(result!!))
    }

    @Test
    fun `two calls from one contact`() {
        val result = notice(missed("+4790000002", 100, id = 7), missed("+47 900 00 002", 300, id = 9))!!
        assertEquals(MissedCallNotice(2, listOf(MissedContact("+4790000002", "Pappa", 2, 300)), "+4790000002", 300, 9), result)
        assertEquals(NoticeText(NoticeTitle.CALLS_FROM, 2, "Pappa", null), noticeText(result))
    }

    @Test
    fun `several contacts - count, the names newest first, and no contact to open`() {
        val result = notice(
            missed("+4790000002", 300, id = 9),
            missed("+4790000001", 200, id = 8),
            missed("+4790000002", 100, id = 7),
        )!!
        assertEquals(
            MissedCallNotice(3, listOf(MissedContact("+4790000002", "Pappa", 2, 300), MissedContact("+4790000001", "Mamma", 1, 200)), null, 300, 9),
            result,
        )
        assertEquals(listOf("Pappa", "Mamma"), result.names)
        assertEquals(NoticeText(NoticeTitle.CALLS, 3, null, "Pappa, Mamma"), noticeText(result))
    }

    @Test
    fun `only contacts the kid sees and may call back (QA 2)`() {
        // Inbound-only: never in the phone book, never named, never "seen" - so never shown.
        assertNull(notice(missed("+4790000003", 100, id = 7)))
        // Not a contact at all (a callback-window caller).
        assertNull(notice(missed("+4741234567", 100, id = 7)))
        // Calls off, or a no-calls time rule: the phone book only has emergency contacts.
        assertNull(notice(missed("+4790000002", 100, id = 7), contacts = view(managed(callsEnabled = false))))
        assertNull(notice(missed("+4790000002", 100, id = 7), contacts = view(CallPolicyState.UnknownFailClosed)))
        assertNull(notice(missed("+4790000002", 100, id = 7), contacts = view(CallPolicyState.Unmanaged)))
        // A stranger next to a contact doesn't count.
        assertEquals(1, notice(missed("+4741234567", 200, id = 8), missed("+4790000001", 100, id = 7))!!.count)
    }

    @Test
    fun `read calls, and rows newer than the bound, are left out`() {
        assertNull(notice(missed("+4790000002", 100, id = 7, unread = false)))
        assertNull(notice(missed("+4790000002", 100, id = 0)))
        val result = notice(missed("+4790000002", 100, id = 7), missed("+4790000001", 200, id = 8), upTo = 7)!!
        assertEquals(MissedCallNotice(1, listOf(MissedContact("+4790000002", "Pappa", 1, 100)), "+4790000002", 100, 7), result)
    }

    @Test
    fun `a seen mark or a call with the contact deals with its missed calls`() {
        assertNull(notice(missed("+4790000002", 100, id = 7), seen = mapOf("+4790000002" to 150L)))
        assertNull(notice(missed("+4790000002", 100, id = 7), CallLogEntry("+4790000002", OUTGOING, 150, id = 8)))
        assertNull(notice(missed("+4790000002", 100, id = 7), CallLogEntry("90000002", INCOMING_ANSWERED, 150, id = 8)))
        assertEquals(1, notice(missed("+4790000002", 200, id = 9), seen = mapOf("+4790000002" to 150L))!!.count)
    }

    @Test
    fun `a contact without a name shows its number`() {
        val nameless = RuleContact(id = 4, name = " ", number = "+4790000004", outbound = true)
        val result = notice(missed("+4790000004", 100, id = 7), contacts = listOf(nameless))!!
        assertEquals(listOf("+4790000004"), result.names)
        assertEquals(listOf(MissedContact("+4790000004", null, 1, 100)), result.contacts)
        assertEquals("+4790000004", result.contacts.single().shown)
    }

    /** Design 25 §2.4: the lock's glance page shows one row per contact - from the same rows the
     * notification counts, newest first, each with its own count and newest call. */
    @Test
    fun `per-contact rows - counts and the newest call each, newest contact first`() {
        val result = notice(
            missed("+4790000002", 100, id = 7),
            missed("+4790000001", 250, id = 8),
            missed("+4790000002", 300, id = 9),
            missed("+4790000002", 50, id = 6, unread = false),
        )!!
        assertEquals(
            listOf(MissedContact("+4790000002", "Pappa", 2, 300), MissedContact("+4790000001", "Mamma", 1, 250)),
            result.contacts,
        )
        assertEquals(result.count, result.contacts.sumOf { it.count })
        // The plain notice has no contacts: one "N tapte anrop" row.
        assertTrue(plainMissedCallNotice(UnreadMissed(3, 12, 500))!!.contacts.isEmpty())
    }

    /** Design 25 QA Low 9: the lock's panel holds these - their toString() carries no name or number. */
    @Test
    fun `toString is redacted`() {
        val result = notice(missed("+4790000002", 100, id = 7))!!
        for (printed in listOf(result.toString(), result.contacts.single().toString(), NoticeStep.Post(result, alert = true).toString())) {
            assertFalse(printed, printed.contains("Pappa") || printed.contains("4790000002"))
        }
    }

    @Test
    fun `the badges keep counting what the notification no longer shows`() {
        val log = listOf(missed("+4790000002", 100, id = 7, unread = false), missed("+4790000002", 200, id = 8))
        assertEquals(MissedSummary(2, 200), missedCallSummaries(log, view(), "47", emptyMap())["+4790000002"])
        assertEquals(1, missedCallNotice(log, view(), "47", emptyMap(), 8)!!.count)
    }

    private val one = MissedCallNotice(1, listOf(MissedContact("+4790000002", "Pappa", 1, 100)), "+4790000002", 100, 7)
    private val two = MissedCallNotice(2, listOf(MissedContact("+4790000002", "Pappa", 2, 200)), "+4790000002", 200, 9)

    private fun step(
        notice: MissedCallNotice?,
        mayMarkRead: Boolean = true,
        mayPost: Boolean = true,
        shown: Boolean = false,
        lastPosted: MissedCallNotice? = null,
        unreadUpTo: Long = 9,
        alertedUpTo: Long = 0,
        newestLogged: Long = 9,
        mayClear: Boolean = true,
    ) = noticeStep(notice, mayMarkRead, mayPost, shown, lastPosted, unreadUpTo, alertedUpTo, newestLogged, mayClear)

    @Test
    fun `nothing to show clears ours and marks the log read (QA 1, 2)`() {
        assertEquals(NoticeStep.Clear(9), step(null))
        assertEquals(NoticeStep.Clear(9), step(null, mayPost = false, shown = true, lastPosted = one))
        // No unread missed call: nothing to mark, Telecom isn't told.
        assertEquals(NoticeStep.Clear(null), step(null, unreadUpTo = 0))
        // Rules unknown or unmanaged, calls off or a no-calls time rule (the view only has
        // emergency contacts - qa-12-code #3): only ours goes, nothing is marked read.
        assertEquals(NoticeStep.Clear(null), step(null, mayMarkRead = false))
    }

    @Test
    fun `without readable managed rules - a plain count, opening the call log (qa-12-code 1)`() {
        assertNull(plainMissedCallNotice(UnreadMissed.NONE))
        assertNull(plainMissedCallNotice(UnreadMissed(2, 0, 100)))
        val plain = plainMissedCallNotice(UnreadMissed(3, 12, 500))!!
        assertEquals(MissedCallNotice(3, emptyList(), null, 500, 12), plain)
        assertEquals(NoticeText(NoticeTitle.CALLS, 3, null, null), noticeText(plain))
        assertEquals(NoticeText(NoticeTitle.CALLS, 1, null, null), noticeText(plainMissedCallNotice(UnreadMissed(1, 12, 500))!!))
        // Posted like any other (alerting once per newer id), never marked read automatically.
        assertEquals(NoticeStep.Post(plain, alert = true), step(plain, mayMarkRead = false, unreadUpTo = 12))
        assertEquals(NoticeStep.Keep, step(plain, mayMarkRead = false, shown = true, lastPosted = plain, alertedUpTo = 12))
    }

    @Test
    fun `the log's missed calls are ours to keep while managed or while we are the dialer`() {
        assertTrue(canKeepMissedCalls(granted = true, unlocked = true, callsManaged = true, dialerRoleHeld = false))
        assertTrue(canKeepMissedCalls(granted = true, unlocked = true, callsManaged = false, dialerRoleHeld = true))
        assertFalse(canKeepMissedCalls(granted = true, unlocked = true, callsManaged = false, dialerRoleHeld = false))
        assertFalse(canKeepMissedCalls(granted = false, unlocked = true, callsManaged = true, dialerRoleHeld = true))
        assertFalse(canKeepMissedCalls(granted = true, unlocked = false, callsManaged = true, dialerRoleHeld = true))
    }

    @Test
    fun `Telecom's count 0 cancels ours unless ours is newer than our last reset (qa-12-code 2)`() {
        assertTrue(zeroCountCancels(shownUpToId = 0, resetUpToId = 0))
        assertTrue(zeroCountCancels(shownUpToId = 9, resetUpToId = 9))
        assertTrue(zeroCountCancels(shownUpToId = 7, resetUpToId = 9))
        // We reset up to 9, then posted a call with id 12: the late echo must not remove it.
        assertFalse(zeroCountCancels(shownUpToId = 12, resetUpToId = 9))
    }

    @Test
    fun `a swipe clears the state only when nothing newer was posted (qa-12-code 7)`() {
        assertTrue(swipeClearsShown(swipedUpToId = 9, shownUpToId = 9))
        assertTrue(swipeClearsShown(swipedUpToId = 9, shownUpToId = 0))
        assertFalse(swipeClearsShown(swipedUpToId = 9, shownUpToId = 12))
    }

    @Test
    fun `a broadcast posts, a newer call alerts, the same content isn't posted again`() {
        assertEquals(NoticeStep.Post(one, alert = true), step(one))
        assertEquals(NoticeStep.Post(two, alert = true), step(two, shown = true, lastPosted = one, alertedUpTo = 7))
        assertEquals(NoticeStep.Keep, step(one, shown = true, lastPosted = one, alertedUpTo = 7))
        // A boot re-send (new process, nothing remembered on screen) posts silently.
        assertEquals(NoticeStep.Post(one, alert = false), step(one, shown = true, lastPosted = null, alertedUpTo = 7))
        assertEquals(NoticeStep.Post(one, alert = false), step(one, alertedUpTo = 7))
        // Swiped away, then a newer call: posted and alerting.
        assertEquals(NoticeStep.Post(two, alert = true), step(two, alertedUpTo = 7))
    }

    @Test
    fun `after our call ends only a notification on screen changes`() {
        assertEquals(NoticeStep.Keep, step(one, mayPost = false))
        assertEquals(NoticeStep.Post(one, alert = false), step(one, mayPost = false, shown = true, lastPosted = two, alertedUpTo = 9))
        assertEquals(NoticeStep.Keep, step(two, mayPost = false, shown = true, lastPosted = two, alertedUpTo = 9))
    }

    /** Design 25 §2.4: a new process's pass re-learns what is on screen (a silent re-post) and never
     * clears - during a no-calls time rule the view has only emergency contacts, and the night's
     * update must not drop a notification the kid hasn't seen. */
    @Test
    fun `the process start's pass re-posts what is shown silently and never clears`() {
        assertEquals(NoticeStep.Post(one, alert = false), step(one, mayPost = false, shown = true, lastPosted = null, alertedUpTo = 7, mayClear = false))
        assertEquals(NoticeStep.Keep, step(one, mayPost = false, shown = false, alertedUpTo = 7, mayClear = false))
        assertEquals(NoticeStep.Keep, step(null, mayPost = false, shown = true, mayClear = false))
        assertEquals(NoticeStep.Keep, step(null, mayPost = false, shown = false, mayClear = false))
        // Every other pass clears as before.
        assertEquals(NoticeStep.Clear(9), step(null, mayPost = false, shown = true))
    }

    /** Code QA Low 3: a re-learning pass counts against the rules without the time rule's block - a
     * notification on screen during a no-calls rule was posted before it, so it is known again. */
    @Test
    fun `a re-learning pass during a no-calls rule knows what is on screen`() {
        val log = arrayOf(missed("+4790000001", 100, id = 7))
        val rules = managed()
        val duringRule = withTimeRule(rules, callsBlocked = true)
        val posted = notice(*log)!!
        // The rule's own view has only emergency contacts: nothing to show.
        assertNull(notice(*log, contacts = view(duringRule)))
        assertEquals(duringRule, noticeState(rules, duringRule, relearnOnly = false))
        // The re-learning pass uses the rules: the same notice, posted again silently.
        val relearned = notice(*log, contacts = view(noticeState(rules, duringRule, relearnOnly = true)))
        assertEquals(posted, relearned)
        assertEquals(NoticeStep.Post(posted, alert = false), step(relearned, mayPost = false, shown = true, lastPosted = null, alertedUpTo = 7, mayClear = false))
    }

    @Test
    fun `a cleared call log starts its ids again - alerts come back`() {
        assertEquals(NoticeStep.Post(one, alert = true), step(one, alertedUpTo = 500, newestLogged = 9))
        // Nothing read at all: no evidence, keep the mark.
        assertEquals(NoticeStep.Post(one, alert = false), step(one, alertedUpTo = 500, newestLogged = 0))
    }

    @Test
    fun `the tap opens a contact only from the phone book (QA 8)`() {
        val state = managed()
        assertEquals(pappa, missedCallContact("+4790000002", phoneBookView(state) { false }))
        assertNull(missedCallContact("+4790000003", phoneBookView(state) { false }))
        assertNull(missedCallContact("+4741234567", phoneBookView(state) { false }))
        assertNull(missedCallContact(null, phoneBookView(state) { false }))
        assertNull(missedCallContact("+4790000002", phoneBookView(managed(callsEnabled = false)) { false }))
    }

    /** Design r11 §3.3, the user's decision 3: a contact dismissed on the lock leaves the notification, the phone
     * book's badge stays. */
    @Test
    fun `a contact dismissed on the lock leaves the notice, its badge stays (r11 §3_3)`() {
        val log = listOf(missed("+4790000002", 300, id = 9), missed("+4790000001", 200, id = 8))
        val dismissed = mapOf("+4790000002" to 300L)
        val marks = noticeMarks(seen = emptyMap(), dismissed = dismissed)
        val result = missedCallNotice(log, view(), "47", marks, Long.MAX_VALUE)!!
        assertEquals("Pappa's row went, Mamma's stays", listOf("Mamma"), result.names)
        assertEquals(1, result.count)
        // The badges count by the seen marks alone: the phone book still shows "Pappa 1".
        val badges = missedCallSummaries(log, view(), "47", seenMs = emptyMap())
        assertEquals(MissedSummary(1, 300), badges["+4790000002"])
        // The last contact dismissed too: nothing to show (the recompute's Clear).
        assertNull(missedCallNotice(log, view(), "47", noticeMarks(emptyMap(), dismissed + ("+4790000001" to 200L)), Long.MAX_VALUE))
    }

    @Test
    fun `a call after the dismissed mark shows again, and the later of both marks counts (r11 §3_3)`() {
        val dismissed = mapOf("+4790000002" to 300L)
        val again = listOf(missed("+4790000002", 300, id = 9), missed("+4790000002", 500, id = 11))
        val result = missedCallNotice(again, view(), "47", noticeMarks(emptyMap(), dismissed), Long.MAX_VALUE)!!
        assertEquals(listOf(MissedContact("+4790000002", "Pappa", 1, 500)), result.contacts)
        // Per number the later mark: a seen mark after the dismissal wins, an older one doesn't.
        assertEquals(mapOf("+4790000002" to 600L, "+4790000001" to 50L), noticeMarks(mapOf("+4790000002" to 600L, "+4790000001" to 50L), dismissed))
        assertEquals(mapOf("+4790000002" to 300L), noticeMarks(mapOf("+4790000002" to 100L), dismissed))
        assertEquals(emptyMap<String, Long>(), noticeMarks(emptyMap(), emptyMap()))
    }

    @Test
    fun `the dismissed marks are pruned to the log's 14 days at each write (r11 §3_3)`() {
        val day = 24L * 60 * 60 * 1000
        assertEquals(14 * day, MISSED_CALL_LOG_WINDOW_MS)
        val now = 100 * day
        val marks = mapOf("+4790000001" to now - 15 * day, "+4790000003" to now - 13 * day)
        assertEquals(
            mapOf("+4790000003" to now - 13 * day, "+4790000002" to now - day),
            dismissedMarksAfter(marks, "+4790000002", upToMs = now - day, nowMs = now),
        )
        // A later mark already there stays (a dismissal of an older row can't move it back).
        assertEquals(mapOf("+4790000002" to now), dismissedMarksAfter(mapOf("+4790000002" to now), "+4790000002", upToMs = now - day, nowMs = now))
        // A mark exactly at the window's edge is kept.
        assertEquals(setOf("+4790000002"), dismissedMarksAfter(emptyMap(), "+4790000002", upToMs = now - 14 * day, nowMs = now).keys)
    }

    /** The glue's pins (design r11 §3.3): the notice counts after both marks, the dismissal's pass never posts anew,
     * never alerts and counts against the rules themselves. */
    @Test
    fun `the notifier's dismissal pass (r11 §3_3)`() {
        val src = listOf("src/main/java", "app/src/main/java").map { java.io.File(it, "com/kidslauncher/mdm/calls/MissedCallNotifier.kt") }.first { it.exists() }.readText()
        assertTrue(src.contains("noticeMarks(MissedCallsRepo.seenMarks(context), MissedCallsRepo.dismissedMarks(context))"))
        assertTrue(src.contains("relearnOnly = !mayClear || ownDismiss"))
        val dismiss = src.substringAfter("fun dismissContact(").substringBefore("\n    }\n")
        assertTrue(dismiss, dismiss.indexOf("MissedCallsRepo.markDismissed(app, number, upToMs)") in 0 until dismiss.indexOf("recomputeNow(app, mayPost = false, mayClear = true, ownDismiss = true)"))
        assertTrue(dismiss, dismiss.contains("runThenDone(null) {"))
        assertTrue(src.contains("val alert = step.alert && !ownDismiss"))
        val repo = listOf("src/main/java", "app/src/main/java").map { java.io.File(it, "com/kidslauncher/mdm/calls/MissedCallsRepo.kt") }.first { it.exists() }.readText()
        assertTrue(repo.contains("private const val DISMISSED_PREFS = \"missed_call_dismissed\""))
        val mark = repo.substringAfter("fun markDismissed(").substringBefore("\n    }\n")
        assertTrue(mark, mark.contains("dismissedMarksAfter(dismissedMarks(context), number, upToMs, nowMs)") && mark.contains("editor.commit()"))
        // The badges keep the seen marks alone.
        assertTrue(repo.contains("missedCallSummaries(recentLog(context, nowMs), rules.contacts, rules.defaultCc, seenMarks(context))"))
    }

    @Test
    fun `call-log intents`() {
        val view = "android.intent.action.VIEW"
        assertTrue(isCallLogView(view, "vnd.android.cursor.dir/calls", null))
        assertTrue(isCallLogView(view, null, "content://call_log/calls"))
        assertTrue(isCallLogView(view, "vnd.android.cursor.dir/calls", "content://call_log/calls"))
        assertFalse(isCallLogView(view, null, "tel:+4790000002"))
        assertFalse(isCallLogView(view, null, "content://call_log/calls/12"))
        assertFalse(isCallLogView("android.intent.action.DIAL", "vnd.android.cursor.dir/calls", null))
        assertFalse(isCallLogView(view, "vnd.android.cursor.item/calls", null))
        assertEquals("vnd.android.cursor.dir/calls", CALL_LOG_TYPE)
    }
}
