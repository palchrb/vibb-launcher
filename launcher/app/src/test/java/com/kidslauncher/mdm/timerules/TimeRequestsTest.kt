package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.TimeRequestAnswer
import com.kidslauncher.mdm.server.dto.TimeRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design r13 §2.4-2.5: the kid's request for more screen time - the shared contract
 * `time_request.json` (byte-identical with `server/testdata/`, whose `time_request::the_contract_answers`
 * posts its request and produces every answer row), and the phone's rules: [requestState] (the day, the 30
 * minutes, a newer lift, the cap), [askStep] and [timeRequestAnswer] for every row of §2.4's table.
 */
class TimeRequestsTest {
    private val contract = javaClass.classLoader!!.getResource("time_request.json")!!.readText()
    private val parsed = Json.parseToJsonElement(contract).jsonObject

    @Test
    fun `the request encodes to exactly the contract's`() {
        val body = TimeRequestBody(id = "0123456789abcdef0123456789abcdef", askedAtMs = 1_793_034_120_000)
        val encoded = ServerJson.encodeToString(TimeRequestBody.serializer(), body)
        assertEquals(parsed.getValue("request"), Json.parseToJsonElement(encoded))
        assertEquals(parsed.getValue("request").toString(), encoded)
    }

    @Test
    fun `every answer decodes`() {
        val answers = parsed.getValue("answers").jsonArray.map {
            ServerJson.decodeFromString(TimeRequestAnswer.serializer(), it.toString())
        }
        assertEquals(
            listOf(TimeRequestAnswer(true), TimeRequestAnswer(false, "capped"), TimeRequestAnswer(false, "stale")),
            answers,
        )
        // Each row encodes back to itself (no null sent).
        for ((row, answer) in parsed.getValue("answers").jsonArray.zip(answers)) {
            assertEquals(row as JsonObject, Json.parseToJsonElement(ServerJson.encodeToString(TimeRequestAnswer.serializer(), answer)))
        }
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/time_request.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    private val day = "2026-10-26"
    private val now = 1_793_034_120_000L

    private fun record(phase: TimeRequestPhase = TimeRequestPhase.PENDING, onDay: String = day, liftId: Long = 7, askedAt: Long = now) =
        TimeRequestRecord("0123456789abcdef0123456789abcdef", askedAt, askedAt + TIME_REQUEST_OPEN_MS, onDay, liftId, phase)

    @Test
    fun `the state - the day, the 30 minutes, a newer lift, the cap`() {
        assertEquals(RequestState.Idle, requestState(null, null, now, day, 7))
        assertEquals(RequestState.Pending, requestState(record(), null, now + 60_000, day, 7))
        assertEquals(RequestState.Asked(now), requestState(record(TimeRequestPhase.ASKED), null, now + 60_000, day, 7))
        assertEquals("another day's record", RequestState.Idle, requestState(record(onDay = "2026-10-25"), null, now, day, 7))
        assertEquals("its 30 minutes over", RequestState.Idle, requestState(record(), null, now + TIME_REQUEST_OPEN_MS, day, 7))
        assertEquals(RequestState.Pending, requestState(record(), null, now + TIME_REQUEST_OPEN_MS - 1, day, 7))
        assertEquals("a newer lift is the answer", RequestState.Idle, requestState(record(TimeRequestPhase.ASKED), null, now, day, 8))
        assertEquals("no ledger day yet", RequestState.Idle, requestState(record(), null, now, null, 7))
        val three = TimeRequestCount(day, TIME_REQUESTS_A_DAY)
        assertEquals(RequestState.Capped, requestState(null, three, now, day, 7))
        assertEquals("the third's Spurt still shows", RequestState.Asked(now), requestState(record(TimeRequestPhase.ASKED), three, now, day, 7))
        assertEquals("answered: capped till midnight", RequestState.Capped, requestState(record(TimeRequestPhase.ASKED), three, now, day, 8))
        assertEquals("yesterday's three", RequestState.Idle, requestState(null, three.copy(day = "2026-10-25"), now, day, 7))
        assertEquals(RequestState.Idle, requestState(null, TimeRequestCount(day, 2), now, day, 7))
    }

    @Test
    fun `the tap asks only while enrolled, with a budget, outside the pause and idle`() {
        assertEquals(AskStep.ASK, askStep(enrolled = true, budgetToday = true, windowOpen = false, RequestState.Idle))
        assertEquals(AskStep.IGNORE, askStep(false, true, false, RequestState.Idle))
        assertEquals(AskStep.IGNORE, askStep(true, false, false, RequestState.Idle))
        assertEquals(AskStep.IGNORE, askStep(true, true, true, RequestState.Idle))
        for (state in listOf(RequestState.Pending, RequestState.Asked(1), RequestState.Capped)) {
            assertEquals("$state", AskStep.IGNORE, askStep(true, true, false, state))
        }
    }

    @Test
    fun `the answer table`() {
        val rows = parsed.getValue("answers").jsonArray.map { it.toString() }
        assertEquals(TimeRequestVerdict.ACCEPTED, timeRequestAnswer(200, rows[0]))
        assertEquals(TimeRequestVerdict.CAPPED, timeRequestAnswer(200, rows[1]))
        assertEquals(TimeRequestVerdict.DROPPED, timeRequestAnswer(200, rows[2]))
        assertEquals("a 200 that doesn't parse", TimeRequestVerdict.DROPPED, timeRequestAnswer(200, "<html>"))
        assertEquals(TimeRequestVerdict.DROPPED, timeRequestAnswer(200, null))
        assertEquals("an unknown reason", TimeRequestVerdict.DROPPED, timeRequestAnswer(200, """{"accepted":false,"reason":"later"}"""))
        assertEquals("an old server", TimeRequestVerdict.NO_ROUTE, timeRequestAnswer(404, null))
        for (code in listOf(400, 403, 409, 413)) assertEquals("$code", TimeRequestVerdict.DROPPED, timeRequestAnswer(code, null))
        for (code in listOf(null, 401, 408, 429, 500, 502, 503)) assertEquals("$code", TimeRequestVerdict.RETRY, timeRequestAnswer(code, null))
        // What each does to the record and the count.
        val r = record()
        assertEquals(r.copy(state = TimeRequestPhase.ASKED) to TimeRequestCount(day, 1), afterTimeRequestAnswer(r, null, TimeRequestVerdict.ACCEPTED))
        assertEquals("yesterday's count starts again", TimeRequestCount(day, 1), afterTimeRequestAnswer(r, TimeRequestCount("2026-10-25", 3), TimeRequestVerdict.ACCEPTED).second)
        assertEquals(TimeRequestCount(day, 3), afterTimeRequestAnswer(r, TimeRequestCount(day, 2), TimeRequestVerdict.ACCEPTED).second)
        assertEquals("the server's cap wins", null to TimeRequestCount(day, TIME_REQUESTS_A_DAY), afterTimeRequestAnswer(r, TimeRequestCount(day, 1), TimeRequestVerdict.CAPPED))
        assertEquals(null to TimeRequestCount(day, 1), afterTimeRequestAnswer(r, TimeRequestCount(day, 1), TimeRequestVerdict.DROPPED))
        assertEquals(null to null, afterTimeRequestAnswer(r, null, TimeRequestVerdict.NO_ROUTE))
        assertEquals(r to null, afterTimeRequestAnswer(r, null, TimeRequestVerdict.RETRY))
    }

    @Test
    fun `ids are 32 lowercase hex, the record and the count round-trip`() {
        val id = newTimeRequestId()
        assertTrue(id, Regex("[0-9a-f]{32}").matches(id))
        val r = record()
        assertEquals(r, ServerJson.decodeFromString(TimeRequestRecord.serializer(), ServerJson.encodeToString(TimeRequestRecord.serializer(), r)))
        assertEquals(7L, maxLiftId(TimePolicy(lifts = listOf(Lift(id = 3), Lift(id = 7)))))
        assertEquals(0L, maxLiftId(null))
    }

    /** qa-r13-rule-card-code Q-3: the sync posts only a request still open; one answered by a lift that came while it
     * waited, another day's or an expired one is dropped unsent. */
    @Test
    fun `the upload sends only an open request`() {
        assertEquals(UploadStep.SEND, uploadStep(record(), null, now + 60_000, day, 7))
        assertEquals("a lift came while it waited", UploadStep.DROP, uploadStep(record(), null, now + 60_000, day, 8))
        assertEquals("yesterday's", UploadStep.DROP, uploadStep(record(onDay = "2026-10-25"), null, now, day, 7))
        assertEquals("its 30 minutes over", UploadStep.DROP, uploadStep(record(), null, now + TIME_REQUEST_OPEN_MS, day, 7))
        assertEquals("no ledger day", UploadStep.DROP, uploadStep(record(), null, now, null, 7))
        assertEquals(UploadStep.NONE, uploadStep(null, null, now, day, 7))
        assertEquals("already taken", UploadStep.NONE, uploadStep(record(TimeRequestPhase.ASKED), null, now, day, 7))
    }

    /** Mutation L15: the answer never overwrites a newer record written while the upload ran. */
    @Test
    fun `the answer writes only the record that was sent`() {
        val sent = record()
        val newer = sent.copy(id = "ffffffffffffffffffffffffffffffff", askedAtMs = now + 1)
        assertNull("a newer record stays", recordAfterUpload(newer, sent.id, null, TimeRequestVerdict.ACCEPTED))
        assertNull("none left", recordAfterUpload(null, sent.id, null, TimeRequestVerdict.DROPPED))
        assertEquals(afterTimeRequestAnswer(sent, null, TimeRequestVerdict.ACCEPTED), recordAfterUpload(sent, sent.id, null, TimeRequestVerdict.ACCEPTED))
        val glue = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/timerules/TimeRequests.kt") }.first { it.exists() }.readText()
        assertTrue(glue.contains("recordAfterUpload(record(context), record.id, count(context), verdict) ?: return"))
        assertTrue(glue.contains("when (uploadStep(record, count(context), now, day, maxLiftId(policy)))"))
    }
}
