package com.kidslauncher.mdm.push.up

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 108 §2.1 and §5.5: a registration's way through the server, and H1 - a key is acknowledged only after its
 * broadcast was sent, a failed pull keeps its acks. */
class UpEngineTest {
    private val store = UpFixtures.store()
    private val server = FakeUpServer()
    private val phone = FakeUpPhone()
    private var view = UpFixtures.view("on")
    private val engine = UpEngine({ true }, { store }, { view }, { server }, phone, { 10_000_000 }).also { e -> phone.onSent = { e.sent(it) } }
    private val element = "io.element.android.x"

    private fun rows() = (store.read() as UpRead.Rows).rows

    @Test
    fun `a new registration is pending before the call, confirmed after, and answered with its endpoint`() {
        engine.handle(UpRequest.Register(element, "tok-new", "@emma:example.org", null))
        val body = server.registerBodies.single()
        assertEquals(element, body.pkg)
        val row = rows().single()
        assertTrue(row.confirmed)
        assertEquals("@emma:example.org", row.message)
        assertEquals(listOf("NEW_ENDPOINT $element tok-new https://phones.example.com/up/${body.secret}"), phone.sent)
        // The token itself never left the phone.
        assertFalse("tok-new" in body.toString() || body.reg == "tok-new" || body.secret == "tok-new")
        // A second REGISTER of the token: from the store, no call.
        engine.handle(UpRequest.Register(element, "tok-new", null, null))
        assertEquals(1, server.registerBodies.size)
        assertEquals(2, phone.sent.size)
        assertEquals(listOf(10_000_000L), store.churn()[element])
    }

    @Test
    fun `no network keeps the pending row and its pair for the connector's retry`() {
        server.registered += UpServer.Registered.Network
        engine.handle(UpRequest.Register(element, "tok-new", null, null))
        assertEquals(listOf("REGISTRATION_FAILED $element tok-new NETWORK"), phone.sent)
        val pending = rows().single()
        assertFalse(pending.confirmed)
        engine.handle(UpRequest.Register(element, "tok-new", null, null))
        assertEquals(listOf(pending.reg, pending.reg), server.registerBodies.map { it.reg })
        assertEquals(pending.secret, server.registerBodies[1].secret)
        assertTrue(rows().single().confirmed)
    }

    @Test
    fun `a server refusal drops the row and answers ACTION_REQUIRED, a conflict gets one fresh pair`() {
        server.registered += UpServer.Registered.Refused("stopped")
        engine.handle(UpRequest.Register(element, "tok-a", null, null))
        assertEquals(listOf("REGISTRATION_FAILED $element tok-a ACTION_REQUIRED"), phone.sent)
        assertTrue(rows().isEmpty())
        server.registered += UpServer.Registered.Refused("conflict")
        engine.handle(UpRequest.Register(element, "tok-b", null, null))
        assertEquals(2, server.registerBodies.size - 1)
        assertTrue(server.registerBodies[1].reg != server.registerBodies[2].reg)
        assertTrue(rows().single().confirmed)
        // A refusal on the phone never calls the server.
        view = UpFixtures.view("off")
        engine.handle(UpRequest.Register(element, "tok-c", null, null))
        assertEquals(3, server.registerBodies.size)
        assertEquals("REGISTRATION_FAILED $element tok-c ACTION_REQUIRED", phone.sent.last())
    }

    @Test
    fun `unregister removes the row, sends the DELETE and answers UNREGISTERED`() {
        store.write(UpFixtures.rows)
        engine.handle(UpRequest.Unregister("org.unifiedpush.example", "tok-element-1"))
        assertEquals(3, rows().size)
        engine.handle(UpRequest.Unregister(element, "tok-element-1"))
        assertEquals(listOf("c0ffee00000000000000000000000002"), server.unregistered)
        assertEquals(listOf("UNREGISTERED $element tok-element-1"), phone.sent)
    }

    @Test
    fun `H1 - a key reaches the ack only after its send, and a failed pull keeps it`() {
        store.write(UpFixtures.rows)
        server.pulls += UpMessagesAnswer(
            listOf(
                UpWireMessage("deadbeef000000000000000000000003", "c0ffee00000000000000000000000002", "high", "eyJhIjoxfQ=="),
                UpWireMessage("deadbeef000000000000000000000004", "c0ffee00000000000000000000000099", "normal", "AAEC"),
            ),
            more = false,
        )
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        // The unknown reg's message is acknowledged at once; the delivered one only after its send.
        assertEquals(listOf("deadbeef000000000000000000000004"), engine.pendingAcks())
        assertEquals("deadbeef000000000000000000000003", phone.delivering.single().id)
        // A pull while it is still in the raise: the server sends it again (not acknowledged), it isn't broadcast twice.
        server.pulls += UpMessagesAnswer(listOf(UpWireMessage("deadbeef000000000000000000000003", "c0ffee00000000000000000000000002", "high", "eyJhIjoxfQ==")))
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        assertEquals(listOf("deadbeef000000000000000000000004"), server.pullAcks.last())
        assertEquals(1, phone.delivering.size)
        assertTrue(engine.pendingAcks().isEmpty())
        phone.sendAll()
        assertEquals(1, phone.pullsLater)
        assertEquals(listOf("deadbeef000000000000000000000003"), engine.pendingAcks())
        // The next pull fails: nothing is lost.
        server.pulls += null
        assertTrue(engine.requestPull("ack"))
        assertFalse(engine.pullStep())
        assertEquals(1, engine.pendingAcks().size)
        assertTrue(engine.requestPull("ack"))
        engine.pullStep()
        assertEquals(listOf("deadbeef000000000000000000000003"), server.pullAcks.last())
        assertTrue(engine.pendingAcks().isEmpty())
        assertTrue(rows().first { it.reg == "c0ffee00000000000000000000000002" }.lastDeliveredMs != null)
    }

    @Test
    fun `pulls coalesce - one in flight, one trailing - and more asks for the next step`() {
        store.write(UpFixtures.rows)
        server.pulls += UpMessagesAnswer(emptyList(), more = true)
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.requestPull("sse"))
        assertTrue(engine.pullStep())
        assertFalse(engine.pullStep())
        assertTrue(engine.requestPull("sse"))
        view = UpFixtures.view("off")
        assertFalse(engine.pullStep())
        assertFalse(engine.requestPull("sse"))
    }

    @Test
    fun `M1 - a batch still in the raise never spins the pull, and a failed send is delivered again`() {
        store.write(UpFixtures.rows)
        val message = UpWireMessage("deadbeef000000000000000000000005", "c0ffee00000000000000000000000002", "high", "AAEC")
        server.pulls += UpMessagesAnswer(listOf(message), more = true)
        assertTrue(engine.requestPull("sse"))
        assertTrue(engine.pullStep())
        // The server says more, but all it sends is the one still in the raise: the pull stops and waits for the send.
        server.pulls += UpMessagesAnswer(listOf(message), more = true)
        assertFalse(engine.pullStep())
        assertEquals(1, phone.delivering.size)
        // The send failed: no ack, and the next pull hands it to the raise again.
        engine.failed(phone.delivering.removeAt(0))
        assertTrue(engine.pendingAcks().isEmpty())
        server.pulls += UpMessagesAnswer(listOf(message))
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        assertEquals(message.key, phone.delivering.single().id)
    }

    @Test
    fun `M2 - a fault in a step ends the pull, so the next one runs`() {
        store.write(UpFixtures.rows)
        val throwing = UpEngine({ true }, { store }, { view }, { throw IllegalStateException("boom") }, phone)
        assertTrue(throwing.requestPull("sse"))
        assertTrue(runCatching { throwing.pullStep() }.isFailure)
        assertFalse(throwing.pullActive)
        assertTrue(throwing.requestPull("sse"))
    }

    @Test
    fun `L3 - a refused app and a repeated ack never read the sealed store`() {
        var reads = 0
        val counted = UpEngine({ true }, { reads++; store }, { UpFixtures.view("element_only") }, { server }, phone, { 10_000_000 })
        counted.handle(UpRequest.Register("org.unifiedpush.example", "tok-x", null, null))
        assertEquals(0, reads)
        assertEquals("REGISTRATION_FAILED org.unifiedpush.example tok-x ACTION_REQUIRED", phone.sent.single())
        counted.handle(UpRequest.Ack(null, "tok-element-1", "id1"))
        val afterFirst = reads
        assertTrue(afterFirst > 0)
        counted.handle(UpRequest.Ack(null, "tok-element-1", "id2"))
        assertEquals(afterFirst, reads)
    }
}
