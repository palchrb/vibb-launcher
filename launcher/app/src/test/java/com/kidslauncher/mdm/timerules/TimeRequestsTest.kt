package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.TimeRequestAnswer
import com.kidslauncher.mdm.server.dto.TimeRequestBody
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design r13 §2.4-2.5, design 100 §14: the kid's request for more screen time - the shared contract
 * `time_request.json` (byte-identical with `server/testdata/`, whose `time_request` tests post its request, produce
 * every answer row and serve each `policy_*` row), and the phone's rules: [requestState] and [timePart] for every
 * `states` row (one open request until an answer or the day's end, the answer by id, the cap,
 * D10's gift, D12's 30 minutes), [askStep], [uploadStep] and [timeRequestAnswer] for every row of §2.4's table.
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

    private fun record(phase: TimeRequestPhase = TimeRequestPhase.PENDING, onDay: String = day, askedAt: Long = now) =
        TimeRequestRecord(id = "0123456789abcdef0123456789abcdef", askedAtMs = askedAt, day = onDay, state = phase)

    /** A contract row's `time_policy`, as the launcher decodes it. */
    private fun policy(name: String): TimePolicy =
        ServerJson.decodeFromString(TimePolicy.serializer(), parsed.getValue(name).jsonObject.getValue("time_policy").toString())

    /** The contract's words for a state and a note. */
    private fun stateWord(state: RequestState): String = when (state) {
        RequestState.Idle -> "idle"
        is RequestState.Pending -> "pending"
        is RequestState.Waiting -> "waiting"
        is RequestState.Answered -> if (state.no) "answered_no" else "answered_yes"
        RequestState.Capped -> "capped"
    }

    private fun noteWord(note: AskNote): String = when (note) {
        AskNote.None -> "none"
        is AskNote.Sending -> if (note.offline) "offline" else "sending"
        is AskNote.Waiting -> "waiting"
        is AskNote.No -> listOfNotNull("no", note.by).joinToString(":")
        is AskNote.Given -> listOfNotNull("given", note.by, note.minutes.toString()).joinToString(":")
    }

    /** Design 100 §14.2: each policy row decodes into the launcher's DTOs - the answer with its minutes (R2-L1), the
     * lifts' `by` and `at_ms`. */
    @Test
    fun `every policy row decodes`() {
        val no = policy("policy_no")
        assertEquals(TimeAnswer("0123456789abcdef0123456789abcdef", "no", "Pappa", 1_793_034_600_000), no.answer)
        val yes = policy("policy_yes")
        assertEquals(TimeAnswer("0123456789abcdef0123456789abcdef", "yes", "Mamma", 1_793_034_600_000, liftId = 1, minutes = 30), yes.answer)
        assertEquals(Lift(1, TARGET_BUDGET, null, 30, 1_793_077_800_000, by = "Mamma", atMs = 1_793_034_600_000), yes.lifts.single())
        val unnamed = policy("policy_unnamed")
        assertNull(unnamed.answer!!.by)
        assertNull(unnamed.lifts.single().by)
        val liftOnly = policy("policy_lift_only")
        assertNull(liftOnly.answer)
        assertEquals("Mamma", liftOnly.lifts.single().by)
    }

    /** Design 100 §14.1, §14.3: every `states` row through [requestState], [timePart] for the shade and the screen, and
     * the ask's part. */
    @Test
    fun `every state row`() {
        val rows = parsed.getValue("states").jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 10)
        val budget = BudgetUse(usedMs = 90 * 60_000L, budgetMinutes = 90, extraMinutes = 0)
        for (row in rows) {
            val name = row.getValue("name").jsonPrimitive.content
            val record = row.getValue("record").takeUnless { it is JsonNull }
                ?.let { ServerJson.decodeFromString(TimeRequestRecord.serializer(), it.toString()) }
            val count = row.getValue("count").takeUnless { it is JsonNull }
                ?.let { ServerJson.decodeFromString(TimeRequestCount.serializer(), it.toString()) }
            val policy = row.getValue("policy").takeUnless { it is JsonNull }?.let { policy(it.jsonPrimitive.content) }
            val rowDay = row.getValue("day").jsonPrimitive.content
            val nowMs = row.getValue("now_ms").jsonPrimitive.long
            val dayStart = java.time.LocalDate.parse(rowDay).atStartOfDay(java.time.ZoneId.of("Europe/Oslo")).toInstant().toEpochMilli()
            val state = requestState(record, count, rowDay, policy)
            assertEquals(name, row.getValue("state").jsonPrimitive.content, stateWord(state))
            val shade = timePart(budget, false, state, policy, nowMs)
            val screen = timePart(budget, false, state, policy, nowMs, NoteSurface.SCREEN, dayStart)
            assertEquals(name, row.getValue("shade_note").jsonPrimitive.content, noteWord(shade.note))
            assertEquals(name, row.getValue("screen_note").jsonPrimitive.content, noteWord(screen.note))
            assertEquals(name, row.getValue("can_ask").jsonPrimitive.boolean, shade.canAsk)
            assertEquals(name, shade.canAsk, askStep(enrolled = true, budgetToday = true, windowOpen = false, state) == AskStep.ASK)
        }
    }

    @Test
    fun `the state - the day, one open request, the answer, the cap`() {
        val answers = policy("policy_no")
        assertEquals(RequestState.Idle, requestState(null, null, day, answers))
        assertEquals(RequestState.Pending(now), requestState(record(), null, day, null))
        assertEquals("no 30 minutes: open all day", RequestState.Waiting(now), requestState(record(TimeRequestPhase.ASKED), null, day, NO_TIME_RULES))
        assertEquals("another day's record", RequestState.Idle, requestState(record(onDay = "2026-10-25"), null, day, null))
        assertEquals("no ledger day yet", RequestState.Idle, requestState(record(), null, null, null))
        // This server: a newer lift that answered nothing closes nothing (M2: it couldn't unlock the request's state).
        val lifted = TimePolicy(lifts = listOf(Lift(id = 8, target = TARGET_RULE, minutes = 30)))
        assertEquals(RequestState.Waiting(now), requestState(record(TimeRequestPhase.ASKED), null, day, lifted))
        val three = TimeRequestCount(day, TIME_REQUESTS_A_DAY)
        assertEquals(RequestState.Capped, requestState(null, three, day, null))
        assertEquals("the third's wait still shows", RequestState.Waiting(now), requestState(record(TimeRequestPhase.ASKED), three, day, null))
        assertEquals("yesterday's three", RequestState.Idle, requestState(null, three.copy(day = "2026-10-25"), day, null))
        assertEquals(RequestState.Idle, requestState(null, TimeRequestCount(day, 2), day, null))
        // An answer word this launcher doesn't know leaves the request open.
        val odd = answers.copy(answer = answers.answer!!.copy(answer = "later"))
        assertEquals(RequestState.Waiting(now), requestState(record(TimeRequestPhase.ASKED), null, day, odd))
    }

    @Test
    fun `the tap asks only while enrolled, with a budget, outside the pause, idle or answered under three`() {
        assertEquals(AskStep.ASK, askStep(enrolled = true, budgetToday = true, windowOpen = false, RequestState.Idle))
        assertEquals(AskStep.IGNORE, askStep(false, true, false, RequestState.Idle))
        assertEquals(AskStep.IGNORE, askStep(true, false, false, RequestState.Idle))
        assertEquals(AskStep.IGNORE, askStep(true, true, true, RequestState.Idle))
        assertEquals("ask again after an answer", AskStep.ASK, askStep(true, true, false, RequestState.Answered(true, "Pappa", 1, null, canAskAgain = true)))
        for (state in listOf(RequestState.Pending(1), RequestState.Waiting(1), RequestState.Capped, RequestState.Answered(false, null, 1, 30, canAskAgain = false))) {
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
    }

    /** qa-r13-rule-card-code Q-3, design 100 §14.1: the sync posts a request of today still open, offline all
     * afternoon too; another day's is dropped unsent; one this
     * server answered already (the upload's own answer lost) is left - its answer shows. */
    @Test
    fun `the upload sends only an open request of today`() {
        assertEquals(UploadStep.SEND, uploadStep(record(), null, day, null))
        assertEquals("hours later, still today", UploadStep.SEND, uploadStep(record(askedAt = now - 5 * 3_600_000L), null, day, NO_TIME_RULES))
        val lifted = TimePolicy(lifts = listOf(Lift(id = 8, target = TARGET_BUDGET, minutes = 15)))
        assertEquals("a lift answers nothing by itself", UploadStep.SEND, uploadStep(record(), null, day, lifted))
        // Code QA L1: answered here already (the 200 lost, then a parent answered): counted as taken, nothing sent.
        assertEquals("answered here already", UploadStep.COUNT, uploadStep(record(), null, day, policy("policy_no")))
        assertEquals("yesterday's", UploadStep.DROP, uploadStep(record(onDay = "2026-10-25"), null, day, null))
        assertEquals("no ledger day", UploadStep.DROP, uploadStep(record(), null, null, null))
        assertEquals(UploadStep.NONE, uploadStep(null, null, day, null))
        assertEquals("already taken", UploadStep.NONE, uploadStep(record(TimeRequestPhase.ASKED), null, day, null))
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
        assertTrue(glue.contains("when (uploadStep(record, count(context), day, policy))"))
        // Code QA L1: COUNT takes ACCEPTED's step for the record it read.
        assertTrue(glue.contains("recordAfterUpload(record(context), record.id, count(context), TimeRequestVerdict.ACCEPTED) ?: return"))
    }
}
