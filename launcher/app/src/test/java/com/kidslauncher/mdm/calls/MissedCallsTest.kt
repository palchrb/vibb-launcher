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
