package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.CommandReceiptRequest
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Security review L12: the phone confirms every command before it runs it, and runs each id once. */
class CommandAnswerPlanTest {
    @Test
    fun `a command delivered again is not run twice`() {
        var state = CommandAnswerState()
        val (first, afterFirst) = firstSight(state, 7)
        assertTrue(first)
        state = afterFirst
        val (again, afterAgain) = firstSight(state, 7)
        assertFalse(again)
        assertEquals(state, afterAgain)
        assertTrue(firstSight(state, 8).first)
    }

    @Test
    fun `only the newest ids are remembered`() {
        var state = CommandAnswerState()
        for (id in 1L..(MAX_SEEN_COMMANDS + 5)) state = firstSight(state, id).second
        assertEquals(MAX_SEEN_COMMANDS, state.seen.size)
        assertTrue("the oldest are gone", firstSight(state, 1).first)
        assertFalse(firstSight(state, MAX_SEEN_COMMANDS + 5L).first)
    }

    @Test
    fun `the outbox keeps one answer per command and kind, receipts go first when full`() {
        var state = CommandAnswerState()
        state = withAnswer(state, CommandAnswer(1, ANSWER_RECEIPT))
        state = withAnswer(state, CommandAnswer(1, ANSWER_RESULT, false, "failed to lock"))
        state = withAnswer(state, CommandAnswer(1, ANSWER_RESULT, true, "locked (Android)"))
        assertEquals(
            listOf(CommandAnswer(1, ANSWER_RECEIPT), CommandAnswer(1, ANSWER_RESULT, true, "locked (Android)")),
            state.outbox,
        )
        // One more than fits: the receipt goes, the older result stays.
        for (id in 2L..MAX_QUEUED_ANSWERS) state = withAnswer(state, CommandAnswer(id, ANSWER_RESULT))
        assertEquals(MAX_QUEUED_ANSWERS, state.outbox.size)
        assertFalse("the receipt went first", state.outbox.any { it.kind == ANSWER_RECEIPT })
        assertEquals(CommandAnswer(1, ANSWER_RESULT, true, "locked (Android)"), state.outbox.first())
        // Then the oldest answer.
        state = withAnswer(state, CommandAnswer(99, ANSWER_RESULT))
        assertEquals(MAX_QUEUED_ANSWERS, state.outbox.size)
        assertEquals(2L, state.outbox.first().commandId)

        val sent = state.outbox.take(3)
        assertEquals(MAX_QUEUED_ANSWERS - 3, withoutAnswers(state, sent).outbox.size)
    }

    @Test
    fun `what the server's answer means`() {
        assertEquals(AnswerFate.SENT, answerFate(204))
        assertEquals(AnswerFate.SENT, answerFate(200))
        assertEquals("no network", AnswerFate.KEEP, answerFate(null))
        assertEquals(AnswerFate.KEEP, answerFate(401))
        assertEquals(AnswerFate.KEEP, answerFate(408))
        assertEquals(AnswerFate.KEEP, answerFate(429))
        assertEquals(AnswerFate.KEEP, answerFate(500))
        assertEquals(AnswerFate.KEEP, answerFate(503))
        assertEquals("an older server has no receipts", AnswerFate.DROP, answerFate(404))
        assertEquals(AnswerFate.DROP, answerFate(422))
        assertEquals(AnswerFate.DROP, answerFate(413))
    }

    @Test
    fun `the receipt uses the server's keys and the capability is reported`() {
        val json = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(CommandReceiptRequest.serializer(), CommandReceiptRequest(42)),
        ).jsonObject
        assertEquals(setOf("command_id"), json.keys)
        assertEquals("command_receipt_v1", COMMAND_RECEIPT_CAPABILITY)
        assertTrue(COMMAND_RECEIPT_CAPABILITY in STATUS_CAPABILITIES)
    }

    /** The dispatch confirms before anything runs (a wipe included) and doesn't run a repeat. */
    @Test
    fun `the receipt comes before the command runs`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val sync = File(src, "main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        val dispatch = sync.substringAfter("private suspend fun dispatchPendingCommand(").substringBefore("\nprivate ")
        val begin = dispatch.indexOf("CommandAnswers.begin(context, pending.id)")
        val receipt = dispatch.indexOf("CommandAnswers.receipt(context, api, pending.id)")
        val repeat = dispatch.indexOf("if (!fresh)")
        val commands = dispatch.indexOf("when (pending.command)")
        assertTrue(begin in 0 until receipt)
        assertTrue(receipt < repeat && repeat < commands)
        val flush = sync.indexOf("CommandAnswers.flush(context, api)")
        val report = sync.indexOf("api.sendStatus(")
        assertTrue("the outbox goes after the status report", report in 0 until flush)
    }
}
