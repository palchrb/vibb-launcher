package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.calls.LoggedCallType.INCOMING_ANSWERED
import com.kidslauncher.mdm.calls.LoggedCallType.MISSED
import com.kidslauncher.mdm.calls.LoggedCallType.OTHER
import com.kidslauncher.mdm.calls.LoggedCallType.OUTGOING
import org.junit.Assert.assertEquals
import org.junit.Test

class MissedCallsTest {

    private val pappa = RuleContact(id = 1, name = "Pappa", number = "+4790000002")
    private val mamma = RuleContact(id = 2, name = "Mamma", number = "+4790000001")
    private val contacts = listOf(pappa, mamma)

    private fun summaries(vararg log: CallLogEntry, seen: Map<String, Long> = emptyMap()) =
        missedCallSummaries(log.toList(), contacts, "47", seen)

    @Test
    fun `missed calls are counted per contact, matched after normalising`() {
        val result = summaries(
            CallLogEntry("90000002", MISSED, 100),
            CallLogEntry("+47 900 00 002", MISSED, 300),
            CallLogEntry("004790000001", MISSED, 200),
        )
        assertEquals(
            mapOf("+4790000002" to MissedSummary(2, 300), "+4790000001" to MissedSummary(1, 200)),
            result,
        )
    }

    /** Design 79 §3.2 rule 1: the phone book's visit marks a contact seen up to the newest call its row showed - that
     * call and the ones before it go, a call that came after it still counts. */
    @Test
    fun `a mark up to a time - the shown calls go, a newer one stays`() {
        val log = arrayOf(CallLogEntry("+4790000002", MISSED, 100), CallLogEntry("+4790000002", MISSED, 300))
        assertEquals(emptyMap<String, MissedSummary>(), summaries(*log, seen = mapOf("+4790000002" to 300L)))
        assertEquals(mapOf("+4790000002" to MissedSummary(1, 300)), summaries(*log, seen = mapOf("+4790000002" to 100L)))
        // A card opened during the visit marked "now" (later): the visit's mark can't move it back.
        assertEquals(500L, seenMarkAfter(500L, 300L))
    }

    /** Design 79 §3.2 rule 3: Melding deals with the contact - on the card and on the row - as Ring and the card do. */
    @Test
    fun `seen by Melding, on the card and on the row`() {
        fun source(path: String) = listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path")
            .map { java.io.File(it) }.first { it.exists() }.readText()
        val sheet = source("calls/ContactSheet.kt")
        val ownThread = sheet.substringAfter("MessageAction.OwnThread ->").substringBefore("MessageAction.None ->")
        assertEquals(true, ownThread.contains("MissedCallsRepo.markSeen(activity, contact.number)"))
        val app = sheet.substringAfter("is MessageAction.App ->").substringBefore("view.findViewById<View>(R.id.sheet_close)")
        assertEquals(true, app.contains("MissedCallsRepo.markSeen(activity, contact.number)"))
        val book = source("calls/PhoneBookActivity.kt")
        val message = book.substringAfter("private fun message(").substringBefore("private fun handleNumber(")
        assertEquals(2, Regex("""MissedCallsRepo\.markSeen\(this, row\.contact\.number\)""").findAll(message).count())
        val call = book.substringAfter("private fun call(").substringBefore("private fun message(")
        assertEquals(2, Regex("""MissedCallsRepo\.markSeen\(this, contact\.number\)""").findAll(call).count())
        // The visit's marks, at onStop and not for a rotation.
        val stop = book.substringAfter("override fun onStop()").substringBefore("override fun onSaveInstanceState")
        assertEquals(true, stop.contains("if (!isChangingConfigurations") && stop.contains("MissedCallsRepo.markSeen(this, number, upToMs)"))
    }

    @Test
    fun `calling back or talking later clears them`() {
        assertEquals(
            emptyMap<String, MissedSummary>(),
            summaries(CallLogEntry("+4790000002", MISSED, 100), CallLogEntry("+4790000002", OUTGOING, 150)),
        )
        assertEquals(
            emptyMap<String, MissedSummary>(),
            summaries(CallLogEntry("+4790000002", MISSED, 100), CallLogEntry("+4790000002", INCOMING_ANSWERED, 150)),
        )
        // A new missed call after the call back counts again.
        assertEquals(
            mapOf("+4790000002" to MissedSummary(1, 200)),
            summaries(
                CallLogEntry("+4790000002", MISSED, 100),
                CallLogEntry("+4790000002", OUTGOING, 150),
                CallLogEntry("+4790000002", MISSED, 200),
            ),
        )
        // Calling someone else doesn't.
        assertEquals(
            mapOf("+4790000002" to MissedSummary(1, 100)),
            summaries(CallLogEntry("+4790000002", MISSED, 100), CallLogEntry("+4790000001", OUTGOING, 150)),
        )
    }

    @Test
    fun `opening the contact marks them seen`() {
        val log = arrayOf(CallLogEntry("+4790000002", MISSED, 100), CallLogEntry("+4790000002", MISSED, 300))
        assertEquals(mapOf("+4790000002" to MissedSummary(1, 300)), summaries(*log, seen = mapOf("+4790000002" to 200)))
        assertEquals(emptyMap<String, MissedSummary>(), summaries(*log, seen = mapOf("+4790000002" to 300)))
    }

    @Test
    fun `strangers, rejected calls and unparseable numbers never count`() {
        assertEquals(
            emptyMap<String, MissedSummary>(),
            summaries(
                CallLogEntry("+4799999999", MISSED, 100),
                CallLogEntry("+4790000002", OTHER, 100),
                CallLogEntry(null, MISSED, 100),
                CallLogEntry("*21#", MISSED, 100),
            ),
        )
    }

    @Test
    fun `relative days`() {
        assertEquals(RelativeDay.TODAY, relativeDay(100, 100))
        assertEquals(RelativeDay.YESTERDAY, relativeDay(99, 100))
        assertEquals(RelativeDay.EARLIER, relativeDay(90, 100))
        assertEquals(RelativeDay.EARLIER, relativeDay(101, 100))
    }
}
