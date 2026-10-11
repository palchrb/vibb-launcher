package com.kidslauncher.mdm.server

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.server.dto.ParentPinEvent
import com.kidslauncher.mdm.server.dto.ParentPinEventBatch
import java.security.SecureRandom

/*
 * The parent-PIN event queue (design 24 §3.2) and its upload. ParentPin writes the queue in the
 * same commit as the lockout state (ParentPinStore, `events_v1`); the upload runs in the sync.
 */

private const val LOG_TAG = "ParentPinEvents"

/** At most this many queued events; the server refuses a bigger batch (413). */
const val PIN_EVENTS_MAX = 100

private val random = SecureRandom()

/** 128 random bits as 32 lowercase hex characters. */
fun newPinEventId(): String {
    val bytes = ByteArray(16)
    random.nextBytes(bytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** [queue] plus [event]; when full, the oldest `wrong` goes first, then the oldest overall (a
 * lockout already says "5 wrong", QA S3). */
fun enqueuePinEvent(queue: List<ParentPinEvent>, event: ParentPinEvent): List<ParentPinEvent> {
    val out = (queue + event).toMutableList()
    while (out.size > PIN_EVENTS_MAX) {
        val wrong = out.indexOfFirst { it.kind == PIN_EVENT_WRONG }
        out.removeAt(if (wrong >= 0) wrong else 0)
    }
    return out
}

/** Status-report capability: this launcher reports parent-PIN use to `POST /api/devices/pin-events`. */
const val PARENT_PIN_EVENTS_CAPABILITY = "parent_pin_events_v1"

/**
 * Whether a sent batch leaves the queue after the server's answer ([code], `null` = no answer):
 * yes after a 2xx; also after any other 4xx except 401, 404, 408 and 429 - a server that refuses
 * it now always will (QA #10), and keeping it would block everything behind it. Kept on those four
 * (an old server answers 404: the events wait for its update), any 5xx and a network failure.
 */
fun pinEventsLeaveQueue(code: Int?): Boolean = when (code) {
    null -> false
    in 200..299 -> true
    401, 404, 408, 429 -> false
    in 400..499 -> true
    else -> false
}

object ParentPinEvents {
    /**
     * After the status report in `performMdmSync`, next to the crash reports - behind the sync
     * mutex, so one at a time. Sends a snapshot of the queue; afterwards only the sent ids leave
     * it, so events added meanwhile stay (QA #11). No send of its own: without push the parent sees
     * them when they open the page, and an online phone syncs at least every 30 minutes (QA S1).
     */
    suspend fun upload(context: Context, api: MdmApi) {
        val batch = ParentPin.pendingEvents(context)
        if (batch.isEmpty()) return
        val code = try {
            api.sendPinEvents(ParentPinEventBatch(batch)).code()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Parent PIN event upload failed", e)
            null
        }
        if (!pinEventsLeaveQueue(code)) return
        if (code != null && code !in 200..299) Log.w(LOG_TAG, "The server refused ${batch.size} parent PIN events (HTTP $code) - dropped")
        ParentPin.removeEvents(context, batch.map { it.id }.toSet())
    }
}
