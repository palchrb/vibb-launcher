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
        assertEquals(MissedCallNotice(1, listOf("Pappa"), "+4790000002", 100, 7), result)
        assertEquals(NoticeText(NoticeTitle.ONE_CALL_FROM, 1, "Pappa", null), noticeText(result!!))
    }

    @Test
    fun `two calls from one contact`() {
        val result = notice(missed("+4790000002", 100, id = 7), missed("+47 900 00 002", 300, id = 9))!!
        assertEquals(MissedCallNotice(2, listOf("Pappa"), "+4790000002", 300, 9), result)
        assertEquals(NoticeText(NoticeTitle.CALLS_FROM, 2, "Pappa", null), noticeText(result))
    }

    @Test
    fun `several contacts - count, the names newest first, and no contact to open`() {
        val result = notice(
            missed("+4790000002", 300, id = 9),
            missed("+4790000001", 200, id = 8),
            missed("+4790000002", 100, id = 7),
        )!!
        assertEquals(MissedCallNotice(3, listOf("Pappa", "Mamma"), null, 300, 9), result)
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
        assertEquals(MissedCallNotice(1, listOf("Pappa"), "+4790000002", 100, 7), result)
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
        assertEquals(listOf("+4790000004"), notice(missed("+4790000004", 100, id = 7), contacts = listOf(nameless))!!.names)
    }

    @Test
    fun `the badges keep counting what the notification no longer shows`() {
        val log = listOf(missed("+4790000002", 100, id = 7, unread = false), missed("+4790000002", 200, id = 8))
        assertEquals(MissedSummary(2, 200), missedCallSummaries(log, view(), "47", emptyMap())["+4790000002"])
        assertEquals(1, missedCallNotice(log, view(), "47", emptyMap(), 8)!!.count)
    }

    private val one = MissedCallNotice(1, listOf("Pappa"), "+4790000002", 100, 7)
    private val two = MissedCallNotice(2, listOf("Pappa"), "+4790000002", 200, 9)

    private fun step(
        notice: MissedCallNotice?,
        rulesKnown: Boolean = true,
        mayPost: Boolean = true,
        shown: Boolean = false,
        lastPosted: MissedCallNotice? = null,
        unreadUpTo: Long = 9,
        alertedUpTo: Long = 0,
        newestLogged: Long = 9,
    ) = noticeStep(notice, rulesKnown, mayPost, shown, lastPosted, unreadUpTo, alertedUpTo, newestLogged)

    @Test
    fun `nothing to show clears ours and marks the log read (QA 1, 2)`() {
        assertEquals(NoticeStep.Clear(9), step(null))
        assertEquals(NoticeStep.Clear(9), step(null, mayPost = false, shown = true, lastPosted = one))
        // No unread missed call: nothing to mark, Telecom isn't told.
        assertEquals(NoticeStep.Clear(null), step(null, unreadUpTo = 0))
        // Rules unknown or unmanaged: only ours goes - a failure never marks missed calls read.
        assertEquals(NoticeStep.Clear(null), step(null, rulesKnown = false))
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
