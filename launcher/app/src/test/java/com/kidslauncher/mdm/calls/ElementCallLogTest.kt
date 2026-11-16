package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.ElementLogChange.CLEARED
import com.kidslauncher.mdm.calls.ElementLogChange.POSTED
import com.kidslauncher.mdm.calls.ElementLogChange.REMOVED
import com.kidslauncher.mdm.calls.ElementLogChange.SWEPT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 106 §3.3 (Q3): Siste's small record of missed Element X calls - when a record is written, closed, kept and
 * pruned, and what it may hold. This is also the only test of the deduplication after a process restart (M4): the
 * emulator gate's debug receiver can't bring back a live Element X notification across a restart.
 */
class ElementCallLogTest {
    private val mammaId = "@mamma:example.org"
    private val pappaId = "@pappa:example.org"
    private val mamma = RuleContact(1, "Mamma", "+4790000004", inbound = true, outbound = true, messageApp = "element", messageAddress = mammaId)
    private val pappa = RuleContact(2, "Pappa", "+4790000005", inbound = true, outbound = true, messageApp = "element", messageAddress = pappaId)
    private val emma = RuleContact(3, "Emma", "+4790000001", inbound = true, outbound = true, messageApp = "sms")
    private val rules = CallRules(callsEnabled = true, contacts = listOf(mamma, pappa, emma))
    private val managed = CallPolicyState.Managed(rules)
    private val numberOf: (String) -> String? = { elementContactNumber(rules, it) }

    private val day = 24 * 60 * 60 * 1000L
    private val now = 100 * day
    private val keyA = "0|io.element.android.x|!mamma-dm:example.org|1"
    private val keyB = "0|io.element.android.x|!pappa-dm:example.org|1"

    private fun after(
        records: List<ElementCallRecord>,
        before: Map<String, ElementMissedCall>,
        after: Map<String, ElementMissedCall>,
        change: ElementLogChange,
    ) = elementCallLogAfter(records, before, after, change, numberOf, now)

    @Test
    fun `a new key records an open call for the contact's number`() {
        val live = mapOf(keyA to ElementMissedCall(mammaId, now - 60_000))
        val records = after(emptyList(), emptyMap(), live, POSTED)
        assertEquals(listOf(ElementCallRecord(mamma.number, now - 60_000, elementKeyHash(keyA), open = true)), records)
        // The same call posted again (a newer message in the room) records nothing more.
        assertEquals(records, after(records, live, live, POSTED))
        // A contact the rules don't know records nothing.
        assertEquals(emptyList<ElementCallRecord>(), after(emptyList(), emptyMap(), mapOf(keyA to ElementMissedCall("@stranger:example.com", now)), POSTED))
    }

    @Test
    fun `after a restart the sweep finds the open record - the same call, its time raised to the live one`() {
        val record = ElementCallRecord(mamma.number, now - 120_000, elementKeyHash(keyA), open = true)
        // The process died; the memory store is empty and the listener's connect sweeps the room's notification again,
        // its time now the notification's last post.
        val swept = after(listOf(record), emptyMap(), mapOf(keyA to ElementMissedCall(mammaId, now - 60_000)), SWEPT)
        assertEquals(listOf(record.copy(atMs = now - 60_000)), swept)
        // A sweep never lowers it.
        assertEquals(listOf(record), after(listOf(record), emptyMap(), mapOf(keyA to ElementMissedCall(mammaId, now - 600_000)), SWEPT))
        // A closed record of the same hash is history: the sweep's live call is a new one.
        val closed = record.copy(open = false)
        assertEquals(2, after(listOf(closed), emptyMap(), mapOf(keyA to ElementMissedCall(mammaId, now - 60_000)), SWEPT).size)
    }

    @Test
    fun `removed, a flip back to message and a changed contact close - a sweep closes the absent, an unbind nothing`() {
        val live = mapOf(keyA to ElementMissedCall(mammaId, now - 60_000), keyB to ElementMissedCall(pappaId, now - 30_000))
        val open = after(emptyList(), emptyMap(), live, POSTED)
        assertTrue(open.all { it.open } && open.size == 2)
        fun openOf(records: List<ElementCallRecord>) = records.filter { it.open }.map { it.number }.toSet()
        // The kid opened Mamma's room: removed.
        assertEquals(setOf(pappa.number), openOf(after(open, live, live - keyA, REMOVED)))
        // Mamma's notification went back to `message`: the post drops the key.
        assertEquals(setOf(pappa.number), openOf(after(open, live, afterElementPost(live, ElementPost(keyA, null, now)), POSTED)))
        // The key now stands for Pappa: Mamma's record closes, Pappa's opens.
        val moved = after(open, live, live + (keyA to ElementMissedCall(pappaId, now)), POSTED)
        assertEquals(setOf(pappa.number), openOf(moved))
        assertEquals(3, moved.size)
        // A sweep after a restart: only what is active stays open.
        assertEquals(setOf(pappa.number), openOf(after(open, emptyMap(), mapOf(keyB to live.getValue(keyB)), SWEPT)))
        assertEquals(emptySet<String>(), openOf(after(open, emptyMap(), emptyMap(), SWEPT)))
        // The listener was unbound: the next sweep decides.
        assertEquals(open, after(open, live, emptyMap(), CLEARED))
        // Every closed record stays for Siste.
        assertEquals(2, after(open, live, emptyMap(), REMOVED).size)
    }

    @Test
    fun `fourteen days and fifty records, dropped with the contact or unmanaged rules, filtered on read`() {
        val old = ElementCallRecord(mamma.number, now - 15 * day, "0".repeat(32), open = false)
        val fresh = ElementCallRecord(mamma.number, now - day, "1".repeat(32), open = false)
        assertEquals(listOf(fresh), trimElementCallLog(listOf(old, fresh), now))
        val many = (1..60).map { ElementCallRecord(pappa.number, now - it * 60_000L, "%032x".format(it), open = false) }
        val kept = trimElementCallLog(many, now)
        assertEquals(ELEMENT_CALL_LOG_MAX, kept.size)
        assertEquals(many.take(50), kept)
        // At a refresh: unmanaged drops all, a contact that left or stopped using Element is dropped, unknown rules keep.
        val emmaRecord = fresh.copy(number = emma.number)
        assertEquals(emptyList<ElementCallRecord>(), pruneElementCallLog(listOf(fresh), CallPolicyState.Unmanaged, now))
        assertEquals(listOf(fresh), pruneElementCallLog(listOf(fresh, emmaRecord), managed, now))
        val withoutMamma = CallPolicyState.Managed(rules.copy(contacts = listOf(pappa, emma)))
        assertEquals(emptyList<ElementCallRecord>(), pruneElementCallLog(listOf(fresh), withoutMamma, now))
        assertEquals(listOf(fresh), pruneElementCallLog(listOf(fresh), CallPolicyState.UnknownFailClosed, now))
        // A read shows only what the rules allow now, of the last 14 days - also what a refresh hasn't pruned yet.
        assertEquals(listOf(fresh), shownElementCallLog(listOf(old, fresh, emmaRecord), managed, now))
        assertEquals(emptyList<ElementCallRecord>(), shownElementCallLog(listOf(fresh), CallPolicyState.UnknownFailClosed, now))
        assertEquals(emptyList<ElementCallRecord>(), shownElementCallLog(listOf(fresh), withoutMamma, now))
    }

    /** Code QA L1: two phone-book contacts on one MXID - the badge, the record and Siste's unseen row all name the first. */
    @Test
    fun `one MXID, one contact - the first in the phone book, for the badge, the record and the row`() {
        val twin = RuleContact(9, "Lily", "+4790000003", inbound = true, outbound = true, messageApp = "element", messageAddress = mammaId)
        val shared = rules.copy(contacts = listOf(mamma, twin, pappa))
        assertEquals(mamma.number, elementNumberByMxid(shared)[mammaId])
        assertEquals(mamma.number, elementContactNumber(shared, mammaId))
        val badge = elementMissedSummaries(listOf(ElementMissedCall(mammaId, now)), shared, emptyMap())
        assertEquals(setOf(mamma.number), badge.keys)
        val record = elementCallLogAfter(emptyList(), emptyMap(), mapOf(keyA to ElementMissedCall(mammaId, now)), POSTED, { elementContactNumber(shared, it) }, now)
        assertEquals(listOf(mamma.number), record.map { it.number })
        // An inbound-only contact (no sheet) never takes the MXID.
        val inboundFirst = rules.copy(contacts = listOf(twin.copy(outbound = false), mamma))
        assertEquals(mamma.number, elementNumberByMxid(inboundFirst)[mammaId])
    }

    @Test
    fun `the hash is 128 bits, the codec keeps no room, the strings name nothing`() {
        val hash = elementKeyHash(keyA)
        assertTrue(hash.matches(Regex("[0-9a-f]{32}")))
        assertFalse(hash == elementKeyHash(keyB))
        val record = ElementCallRecord(mamma.number, now, hash, open = true)
        val stored = encodeElementCallLog(listOf(record))
        assertFalse("!mamma-dm" in stored || mammaId in stored || "Mamma" in stored)
        assertEquals(listOf(record), decodeElementCallLog(stored))
        assertEquals(emptyList<ElementCallRecord>(), decodeElementCallLog("not json"))
        assertEquals(emptyList<ElementCallRecord>(), decodeElementCallLog("""[{"n":"+4790000004","t":1,"h":"short","o":true}]"""))
        assertEquals("ElementCallRecord(atMs=$now, open=true)", record.toString())
        assertEquals(setOf("number", "atMs", "hash", "open"), ElementCallRecord::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet())
    }
}
