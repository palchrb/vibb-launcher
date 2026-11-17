package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.push.up.vendor.RaiseToForeground
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 108 §5.4 with design QA H1: the vendored raise never loses a message. A bind that returns false sends at
 * once; every unbind path (the timeout, a dead binding, a null binding, a disconnect) sends what is queued first; a key
 * is reported for the ack only after its broadcast was sent.
 */
class RaiseToForegroundTest {
    private class Fake : RaiseToForeground.Platform {
        val events = mutableListOf<String>()
        var hasService = true
        var bindResult = true
        var sendFails = false
        val timers = mutableListOf<Pair<Long, () -> Unit>>()
        var cancelled = 0

        override fun hasRaiseService(app: String) = hasService

        override fun bind(app: String, connection: RaiseToForeground): Boolean {
            events += "bind"
            return bindResult
        }

        override fun unbind(connection: RaiseToForeground) {
            events += "unbind"
        }

        override fun send(app: String, message: RaiseToForeground.Message) {
            if (sendFails) throw IllegalStateException("send failed")
            events += "send ${message.id}"
        }

        override fun schedule(delayMs: Long, action: () -> Unit): RaiseToForeground.Cancellable {
            val timer = delayMs to action
            timers += timer
            return RaiseToForeground.Cancellable {
                cancelled++
                timers.remove(timer)
            }
        }

        override fun noRaiseService(app: String) {
            events += "no raise"
        }

        fun fireTimeout() = timers.removeAt(0).second()
    }

    private val platform = Fake()
    private val unbound = mutableListOf<RaiseToForeground>()
    private val raise = RaiseToForeground(
        "org.unifiedpush.example", platform, { platform.events += "acked ${it.id}" }, { unbound += it }, { platform.events += "failed ${it.id}" },
    )

    private fun message(id: String) = RaiseToForeground.Message("tok-example-1", byteArrayOf(1, 2), id)

    @Test
    fun `bound and connected - queued until the connection, then sent and acked in order`() {
        raise.raiseAndSend(message("k1"))
        raise.raiseAndSend(message("k2"))
        assertEquals(listOf("bind"), platform.events)
        raise.onServiceConnected()
        raise.raiseAndSend(message("k3"))
        assertEquals(listOf("bind", "send k1", "acked k1", "send k2", "acked k2", "send k3", "acked k3"), platform.events)
        // Each new message pushed the unbind 5 s out: one timer left, at RAISE_MS.
        assertEquals(listOf(RaiseToForeground.RAISE_MS), platform.timers.map { it.first })
        platform.fireTimeout()
        assertEquals("unbind", platform.events.last())
        assertEquals(listOf(raise), unbound)
    }

    @Test
    fun `a bind that returns false sends at once, without the raise`() {
        platform.bindResult = false
        raise.raiseAndSend(message("k1"))
        assertEquals(listOf("bind", "send k1", "acked k1"), platform.events)
        assertTrue(platform.timers.isEmpty())
    }

    @Test
    fun `an app without the raise service is sent at once and noted`() {
        platform.hasService = false
        raise.raiseAndSend(message("k1"))
        assertEquals(listOf("no raise", "send k1", "acked k1"), platform.events)
    }

    @Test
    fun `every unbind path flushes the queue by sending, never clears it`() {
        val paths: List<Pair<String, (RaiseToForeground) -> Unit>> = listOf(
            "timeout" to { _ -> },
            "died" to { r -> r.onBindingDied() },
            "null binding" to { r -> r.onNullBinding() },
            "disconnected" to { r -> r.onServiceDisconnected() },
        )
        for ((name, path) in paths) {
            val fake = Fake()
            val r = RaiseToForeground("org.unifiedpush.example", fake, { fake.events += "acked ${it.id}" }, {})
            r.raiseAndSend(message("a"))
            r.raiseAndSend(message("b"))
            if (name == "timeout") fake.fireTimeout() else path(r)
            assertEquals(name, listOf("bind", "send a", "acked a", "send b", "acked b", "unbind"), fake.events)
            // A late connection after the unbind sends nothing twice.
            r.onServiceConnected()
            assertEquals(name, 6, fake.events.size)
        }
    }

    @Test
    fun `a send that fails is reported as failed, never as sent, so never acknowledged`() {
        platform.bindResult = false
        platform.sendFails = true
        raise.raiseAndSend(message("k1"))
        assertEquals(listOf("bind", "failed k1"), platform.events)
    }
}
