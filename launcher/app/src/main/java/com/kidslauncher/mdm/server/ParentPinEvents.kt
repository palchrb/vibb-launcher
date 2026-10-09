package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.ParentPinEvent
import java.security.SecureRandom

/*
 * The parent-PIN event queue (design 24 §3.2). Pure; ParentPin writes it in the same commit as the
 * lockout state (ParentPinStore, `events_v1`).
 */

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
