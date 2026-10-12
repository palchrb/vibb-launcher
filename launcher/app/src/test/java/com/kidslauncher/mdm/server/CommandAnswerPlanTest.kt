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
    private val at = "2026-10-11 18:00:00"

    @Test
    fun `a command delivered again is not run twice`() {
        var state = CommandAnswerState()
        val (first, afterFirst) = firstSight(state, commandKey(7, at))
        assertTrue(first)
        state = afterFirst
        val (again, afterAgain) = firstSight(state, commandKey(7, at))
        assertFalse(again)
        assertEquals(state, afterAgain)
        assertTrue(firstSight(state, commandKey(8, at)).first)
    }

    /** QA Medium 1: a restored server backup (or another server) hands out an id again - for
     * another command, asked for at another time. The phone runs it. */
    @Test
    fun `an id the phone saw before still runs when it names another command`() {
        val state = firstSight(CommandAnswerState(), commandKey(481, "2026-10-10 20:00:00")).second
        assertTrue(firstSight(state, commandKey(481, "2026-10-11 07:15:02")).first)
        assertFalse(firstSight(state, commandKey(481, "2026-10-10 20:00:00")).first)
        assertEquals("an older server sends no time: the id alone", "481", commandKey(481, null))
        assertEquals("481", commandKey(481, ""))
    }

    @Test
    fun `only the newest commands are remembered`() {
        var state = CommandAnswerState()
        for (id in 1L..(MAX_SEEN_COMMANDS + 5)) state = firstSight(state, commandKey(id, at)).second
        assertEquals(MAX_SEEN_COMMANDS, state.ran.size)
        assertTrue("the oldest are gone", firstSight(state, commandKey(1, at)).first)
        assertFalse(firstSight(state, commandKey(MAX_SEEN_COMMANDS + 5L, at)).first)
    }

    @Test
    fun `the outbox keeps one answer per command and kind, receipts go first when full`() {
        var state = CommandAnswerState()
        state = withAnswer(state, CommandAnswer(1, ANSWER_RECEIPT))
        state = withAnswer(state, CommandAnswer(1, ANSWER_RESULT, false, "failed to lock"))
        state = withAnswer(state, CommandAnswer(1, ANSWER_RESULT, true, "locked (Android)"))
        // Another command with the same id (another server's): its own answer.
        assertEquals(3, withAnswer(state, CommandAnswer(1, ANSWER_RESULT, true, "x", requestedAt = at)).outbox.size)
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
            ServerJson.encodeToString(CommandReceiptRequest.serializer(), CommandReceiptRequest(42, at)),
        ).jsonObject
        assertEquals(setOf("command_id", "requested_at"), json.keys)
        val legacy = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(CommandReceiptRequest.serializer(), CommandReceiptRequest(42)),
        ).jsonObject
        assertEquals("unknown time: left out", setOf("command_id"), legacy.keys)
        val result = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(
                com.kidslauncher.mdm.server.dto.CommandResultRequest.serializer(),
                com.kidslauncher.mdm.server.dto.CommandResultRequest(42, false, "x", at),
            ),
        ).jsonObject
        assertEquals(setOf("command_id", "success", "message", "requested_at"), result.keys)
        val pending = ServerJson.decodeFromString(
            com.kidslauncher.mdm.server.dto.PendingCommand.serializer(),
            """{"id": 42, "command": "lock", "requested_at": "$at"}""",
        )
        assertEquals(at, pending.requestedAt)
        assertEquals("command_receipt_v1", COMMAND_RECEIPT_CAPABILITY)
        assertTrue(COMMAND_RECEIPT_CAPABILITY in STATUS_CAPABILITIES)
    }

    /** The dispatch confirms before anything runs (a wipe included) and doesn't run a repeat. */
    @Test
    fun `the receipt comes before the command runs`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val sync = File(src, "main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        val dispatch = sync.substringAfter("private suspend fun dispatchPendingCommand(").substringBefore("\nprivate ")
        val begin = dispatch.indexOf("CommandAnswers.begin(context, pending)")
        val receipt = dispatch.indexOf("CommandAnswers.receipt(context, api, pending)")
        val repeat = dispatch.indexOf("if (!fresh)")
        val commands = dispatch.indexOf("when (pending.command)")
        assertTrue(begin in 0 until receipt)
        assertTrue(receipt < repeat && repeat < commands)
        val flush = sync.indexOf("CommandAnswers.flush(context, api)")
        val report = sync.indexOf("api.sendStatus(")
        assertTrue("the outbox goes after the status report", report in 0 until flush)
    }

    /** QA Medium 1: a new enrollment empties what the phone ran and queued (both enroll paths). */
    @Test
    fun `an enrollment clears the command answers`() {
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        for (path in listOf(
            "main/java/com/kidslauncher/mdm/server/Provisioning.kt",
            "main/java/com/kidslauncher/mdm/ui/settings/launcher/SettingsFragmentLauncher.kt",
        )) {
            val text = File(src, path).readText()
            val clearEvents = text.indexOf("ParentPin.clearEvents(context)")
            val clearAnswers = text.indexOf("CommandAnswers.clear(context)")
            assertTrue("$path clears the answers next to the PIN events", clearEvents >= 0 && clearAnswers > clearEvents)
        }
    }
}
