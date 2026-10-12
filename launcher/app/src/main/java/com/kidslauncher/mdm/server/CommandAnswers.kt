package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.server.dto.CommandReceiptRequest
import com.kidslauncher.mdm.server.dto.CommandResultRequest
import com.kidslauncher.mdm.server.dto.PendingCommand

private const val LOG_TAG = "CommandAnswers"
private const val PREFS = "command_answers"
private const val KEY_STATE = "state_v1"

/**
 * The command receipts and results (security review L12, [CommandAnswerPlan]): CE prefs
 * `command_answers`, every change one `commit()`. Used inside `performMdmSync` (under its mutex);
 * the store's own lock covers the rest.
 */
object CommandAnswers {
    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun load(context: Context): CommandAnswerState {
        val raw = prefs(context).getString(KEY_STATE, null) ?: return CommandAnswerState()
        return try {
            ServerJson.decodeFromString(CommandAnswerState.serializer(), raw)
        } catch (e: Exception) {
            // Unreadable: start empty - at worst a command delivered again runs again.
            Log.w(LOG_TAG, "Command answers don't decode", e)
            CommandAnswerState()
        }
    }

    @Synchronized
    private fun update(context: Context, change: (CommandAnswerState) -> CommandAnswerState): CommandAnswerState {
        val before = load(context)
        val after = change(before)
        if (after != before && !prefs(context).edit().putString(KEY_STATE, ServerJson.encodeToString(CommandAnswerState.serializer(), after)).commit()) {
            Log.w(LOG_TAG, "Couldn't write the command answers")
        }
        return after
    }

    /** Marks [command] as run before it runs; `false` = it ran already (delivered again). */
    @Synchronized
    fun begin(context: Context, command: PendingCommand): Boolean {
        var fresh = true
        update(context) { state ->
            val (first, next) = firstSight(state, commandKey(command.id, command.requestedAt))
            fresh = first
            next
        }
        return fresh
    }

    /** A new enrollment: the commands run and the answers queued were another server's (or this
     * one's before a restore) - QA of the launcher wave, Medium 1. */
    @Synchronized
    fun clear(context: Context) {
        if (!prefs(context).edit().clear().commit()) Log.w(LOG_TAG, "Couldn't clear the command answers")
    }

    /** The receipt, before the command runs (a wipe's only answer when it works). */
    suspend fun receipt(context: Context, api: MdmApi, command: PendingCommand) =
        send(context, api, CommandAnswer(command.id, ANSWER_RECEIPT, requestedAt = command.requestedAt))

    /** What the command did. */
    suspend fun result(context: Context, api: MdmApi, command: PendingCommand, success: Boolean, message: String?) =
        send(context, api, CommandAnswer(command.id, ANSWER_RESULT, success, message, command.requestedAt))

    private suspend fun send(context: Context, api: MdmApi, answer: CommandAnswer) {
        when (answerFate(post(api, answer))) {
            AnswerFate.SENT -> {}
            AnswerFate.DROP -> Log.w(LOG_TAG, "The server refused the ${answer.kind} for command ${answer.commandId} - dropped")
            AnswerFate.KEEP -> update(context) { withAnswer(it, answer) }
        }
    }

    /** After the status report: the answers that didn't get through, oldest first. */
    suspend fun flush(context: Context, api: MdmApi) {
        val queued = load(context).outbox
        if (queued.isEmpty()) return
        val gone = mutableListOf<CommandAnswer>()
        for (answer in queued) {
            val fate = answerFate(post(api, answer))
            if (fate == AnswerFate.KEEP) break
            gone += answer
        }
        if (gone.isNotEmpty()) update(context) { withoutAnswers(it, gone) }
    }

    /** The HTTP status, `null` = no answer. */
    private suspend fun post(api: MdmApi, answer: CommandAnswer): Int? = try {
        when (answer.kind) {
            ANSWER_RECEIPT -> api.sendCommandReceipt(CommandReceiptRequest(answer.commandId, answer.requestedAt)).code()
            else -> api.sendCommandResult(
                CommandResultRequest(answer.commandId, answer.success, answer.message, answer.requestedAt),
            ).code()
        }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't send the ${answer.kind} for command ${answer.commandId}", e)
        null
    }
}
