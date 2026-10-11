package com.kidslauncher.mdm.server

import kotlinx.serialization.Serializable

/*
 * The explicit command receipt (security review L12) - pure, tested in CommandAnswerPlanTest;
 * CommandAnswers stores it and talks to the server. The phone confirms each command it gets
 * (`POST /api/devices/command-receipt`) before running it, then reports the result. The server
 * offers a command the phone didn't confirm again (the response was lost, or the phone refused the
 * policy), so the phone remembers which command ids it ran and runs each one once; a receipt or a
 * result that doesn't reach the server waits in the outbox for the next sync.
 */

/** Status-report capability: this launcher confirms commands, so the server may offer one again. */
const val COMMAND_RECEIPT_CAPABILITY = "command_receipt_v1"

/** Command ids remembered as run - far more than the server's three deliveries of one command. */
const val MAX_SEEN_COMMANDS = 32

/** Answers waiting for the server, at most. */
const val MAX_QUEUED_ANSWERS = 32

const val ANSWER_RECEIPT = "receipt"
const val ANSWER_RESULT = "result"

/** A receipt ([ANSWER_RECEIPT]) or a result ([ANSWER_RESULT], with [success] and [message]). */
@Serializable
data class CommandAnswer(
    val commandId: Long,
    val kind: String,
    val success: Boolean = true,
    val message: String? = null,
)

/** CE prefs `command_answers`: the ids run (newest last) and the answers not delivered yet. */
@Serializable
data class CommandAnswerState(
    val seen: List<Long> = emptyList(),
    val outbox: List<CommandAnswer> = emptyList(),
)

/** Whether [id] is new - `false` = delivered again: confirm it again, never run it twice. The
 * state remembers it either way (the newest [MAX_SEEN_COMMANDS]). */
fun firstSight(state: CommandAnswerState, id: Long): Pair<Boolean, CommandAnswerState> {
    if (id in state.seen) return false to state
    return true to state.copy(seen = (state.seen + id).takeLast(MAX_SEEN_COMMANDS))
}

/** [answer] queued for the next sync: it replaces one of the same command and kind; when full,
 * the oldest receipt goes first (a result says more), then the oldest answer. */
fun withAnswer(state: CommandAnswerState, answer: CommandAnswer): CommandAnswerState {
    val out = state.outbox.filterNot { it.commandId == answer.commandId && it.kind == answer.kind }.toMutableList()
    out += answer
    while (out.size > MAX_QUEUED_ANSWERS) {
        val receipt = out.indexOfFirst { it.kind == ANSWER_RECEIPT }
        out.removeAt(if (receipt >= 0) receipt else 0)
    }
    return state.copy(outbox = out)
}

/** The queue without [gone] (sent, or dropped for good). */
fun withoutAnswers(state: CommandAnswerState, gone: Collection<CommandAnswer>): CommandAnswerState =
    state.copy(outbox = state.outbox.filterNot { it in gone })

enum class AnswerFate {
    /** The server has it. */
    SENT,

    /** The server will never take it: drop it. */
    DROP,

    /** Try again at the next sync. */
    KEEP,
}

/**
 * What the server's answer ([code], `null` = none: no network, a timeout) means for an answer of
 * [kind]: a 2xx is sent; 401, 408, 429, a 5xx and no answer keep it; a 404 drops a receipt (an
 * older server has no receipt endpoint) and a result alike; any other 4xx drops it - a server
 * that refuses it now always will, and the queue is short.
 */
fun answerFate(code: Int?): AnswerFate = when (code) {
    null -> AnswerFate.KEEP
    in 200..299 -> AnswerFate.SENT
    401, 408, 429 -> AnswerFate.KEEP
    in 400..499 -> AnswerFate.DROP
    else -> AnswerFate.KEEP
}
