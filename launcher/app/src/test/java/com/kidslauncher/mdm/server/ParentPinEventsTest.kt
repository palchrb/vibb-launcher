package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.ParentPinEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 24 §3.2: the event queue on the phone. */
class ParentPinEventsTest {
    private fun event(id: String, kind: String) = ParentPinEvent(id, kind, "settings", 1L, null, null, null)

    @Test
    fun `the queue holds 100 and drops the oldest wrong try first`() {
        var queue = emptyList<ParentPinEvent>()
        for (i in 0 until PIN_EVENTS_MAX) queue = enqueuePinEvent(queue, event("e$i", if (i % 2 == 0) PIN_EVENT_UNLOCKED else PIN_EVENT_WRONG))
        assertEquals(PIN_EVENTS_MAX, queue.size)
        queue = enqueuePinEvent(queue, event("new", PIN_EVENT_LOCKOUT))
        assertEquals(PIN_EVENTS_MAX, queue.size)
        assertTrue(queue.none { it.id == "e1" })
        assertEquals("e0", queue.first().id)
        assertEquals("new", queue.last().id)
    }

    @Test
    fun `without wrong tries the oldest event goes`() {
        var queue = (0 until PIN_EVENTS_MAX).map { event("e$it", PIN_EVENT_LOCKOUT) }
        queue = enqueuePinEvent(queue, event("new", PIN_EVENT_UNLOCKED))
        assertEquals((1 until PIN_EVENTS_MAX).map { "e$it" } + "new", queue.map { it.id })
    }

    @Test
    fun `ids are 32 lowercase hex characters, never repeated`() {
        val a = newPinEventId()
        assertTrue(a, Regex("[0-9a-f]{32}").matches(a))
        assertNotEquals(a, newPinEventId())
    }
}
