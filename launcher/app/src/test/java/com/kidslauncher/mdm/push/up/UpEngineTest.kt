package com.kidslauncher.mdm.push.up

import com.kidslauncher.mdm.push.up.vendor.RaiseToForeground
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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

    // Design 108b: liveness, the refresh, the key update.
    private val day = UpRules.DAY_MS
    private val now = 40 * day
    private val keyA = "B" + "A".repeat(86)
    private val keyB = "B" + "B".repeat(86)
    private val keyC = "B" + "C".repeat(86)
    private val allRegs = listOf("c0ffee00000000000000000000000001", "c0ffee00000000000000000000000002")
    private val elementReg = "c0ffee00000000000000000000000002"

    private fun lateEngine(clock: () -> Long = { now }) =
        UpEngine({ true }, { store }, { view }, { server }, phone, clock).also { e -> phone.onSent = { e.sent(it) } }

    private fun seed(vararg overrides: Pair<String, Long?>, extra: (kotlinx.serialization.json.JsonObjectBuilder.() -> Unit)? = null) {
        val own = UpFixtures.rowWith(
            elementReg,
            buildJsonObject {
                for ((k, v) in overrides) put(k, v)
                extra?.invoke(this)
            },
        )
        // Row 1 answered just now and the pending row is left out (a day-old pending one is dropped by the reconcile).
        store.write(UpFixtures.rows.filter { it.confirmed }.map { if (it.reg == elementReg) own else it.copy(lastAppAckMs = now) })
    }

    @Test
    fun `a ping goes through the raise with its id, is never acknowledged to the server, and an ack clears it`() {
        seed("last_app_ack_ms" to now - 24 * day)
        val e = lateEngine()
        e.policyAccepted(view, allRegs, 7000)
        val ping = rows().first { it.reg == elementReg }
        assertEquals("$element tok-element-1 ${ping.endpoint} ${ping.pingId}", phone.raised.single())
        assertEquals(now, ping.pingSentMs)
        // The raise reports it sent: no server message, so no ack and no pull.
        e.sent(RaiseToForeground.Message.newEndpoint("tok-element-1", ping.endpoint!!, ping.pingId!!))
        assertTrue(e.pendingAcks().isEmpty())
        assertEquals(0, phone.pullsLater)
        // The app answers it (the connector acks by itself): the ping is cleared.
        e.handle(UpRequest.Ack(null, "tok-element-1", ping.pingId!!))
        val after = rows().first { it.reg == elementReg }
        assertEquals(null, after.pingId)
        assertEquals(now, after.lastAppAckMs)
        // A second pass: nothing more to send.
        e.policyAccepted(view, allRegs, 7000)
        assertEquals(1, phone.raised.size)
    }

    @Test
    fun `a row nobody answered for 30 days is unregistered, its DELETE sent and the note written`() {
        seed("last_app_ack_ms" to now - 40 * day, "ping_sent_ms" to now - 8 * day, "ping_last_send_ms" to now - 2 * 3_600_000L, "ping_boot" to 0, extra = { put("ping_id", "ping-1") })
        lateEngine().policyAccepted(view, allRegs, 7000)
        assertTrue(rows().none { it.reg == elementReg })
        assertEquals("UNREGISTERED $element tok-element-1", phone.sent.single())
        assertEquals(listOf(elementReg), server.unregistered)
        assertEquals(NOTE_UNANSWERED, store.notes().single().kind)
        assertTrue(phone.raised.isEmpty())
    }

    @Test
    fun `a refresh waits for the phone in use, then goes at the unlock, once a day`() {
        seed("last_app_ack_ms" to now)
        view = view.copy(refresh = listOf(UpRefresh(elementReg, "outside")))
        val e = lateEngine()
        phone.inUse = false
        e.policyAccepted(view, allRegs, 7000)
        assertTrue(phone.raised.isEmpty())
        phone.inUse = true
        e.userPresent()
        assertEquals(1, phone.raised.size)
        assertEquals(now, rows().first { it.reg == elementReg }.refreshMs)
        e.userPresent()
        e.policyAccepted(view, allRegs, 7000)
        assertEquals(1, phone.raised.size)
        assertEquals(0, phone.pullsLater)
    }

    @Test
    fun `H1 - another key is answered from the store at once, never REGISTRATION_FAILED, and posted at the next policy`() {
        store.write(UpFixtures.rows.map { if (it.reg == elementReg) it.copy(vapid = keyA) else it })
        server.registered += UpServer.Registered.Network
        var clock = now
        val e = lateEngine { clock }
        e.handle(UpRequest.Register(element, "tok-element-1", null, keyB))
        val first = rows().first { it.reg == elementReg }
        assertEquals(listOf("NEW_ENDPOINT $element tok-element-1 ${first.endpoint}"), phone.sent)
        assertEquals(keyA, first.vapid)
        assertEquals(keyB, first.vapidPending)
        assertEquals(keyB, server.registerBodies.single().vapid)
        // A key flipping within the hour: no second call (M4), the newest kept pending; a REGISTER without a key changes
        // nothing (H1).
        e.handle(UpRequest.Register(element, "tok-element-1", null, keyC))
        e.handle(UpRequest.Register(element, "tok-element-1", null, null))
        assertEquals(1, server.registerBodies.size)
        assertEquals(keyC, rows().first { it.reg == elementReg }.vapidPending)
        assertEquals(3, phone.sent.size)
        // The next policy, an hour on: the newest key is posted and taken.
        clock += 2 * 3_600_000L
        e.policyAccepted(view, allRegs, 7000)
        val taken = rows().first { it.reg == elementReg }
        assertEquals(keyC, taken.vapid)
        assertEquals(null, taken.vapidPending)
        assertEquals(2, server.registerBodies.size)
    }

    @Test
    fun `a key the server refuses is dropped, the old one kept and the note written`() {
        store.write(UpFixtures.rows.map { if (it.reg == elementReg) it.copy(vapid = keyA) else it })
        server.registered += UpServer.Registered.Invalid
        lateEngine().handle(UpRequest.Register(element, "tok-element-1", null, keyB))
        val row = rows().first { it.reg == elementReg }
        assertEquals(keyA, row.vapid)
        assertEquals(null, row.vapidPending)
        assertEquals(NOTE_BAD_KEY, store.notes().single().kind)
        assertEquals(1, phone.sent.size)
    }

    // Code QA of 108b R23-L: M1, L1, L2, L7, L9.
    @Test
    fun `M1 - an outstanding ping goes again a day on, with its id, and a ping whose send failed goes at the next pass`() {
        seed("last_app_ack_ms" to now - 30 * day, "ping_sent_ms" to now - 3 * day, "ping_last_send_ms" to now - 25 * 3_600_000L, "ping_boot" to 0, extra = { put("ping_id", "ping-1") })
        val e = lateEngine()
        e.policyAccepted(view, allRegs, 7000)
        val row = rows().first { it.reg == elementReg }
        assertEquals("$element tok-element-1 ${row.endpoint} ping-1", phone.raised.single())
        assertEquals(now, row.pingLastSendMs)
        assertEquals(now - 3 * day, row.pingSentMs)
        // An hour-old send is not repeated, but one that failed (the stamp was stored before the send) is.
        e.policyAccepted(view, allRegs, 7000)
        assertEquals(1, phone.raised.size)
        e.failed(RaiseToForeground.Message.newEndpoint("tok-element-1", row.endpoint!!, "ping-1"))
        assertEquals(null, rows().first { it.reg == elementReg }.pingBoot)
        e.policyAccepted(view, allRegs, 7000)
        assertEquals(2, phone.raised.size)
        assertTrue(phone.raised.all { it.endsWith(" ping-1") })
        assertTrue(rows().any { it.reg == elementReg })
    }

    @Test
    fun `M1 - the answer to a ping is read although the token's ack was read within the hour`() {
        seed("last_app_ack_ms" to now - 30 * 60_000L)
        val e = lateEngine()
        e.handle(UpRequest.Ack(null, "tok-element-1", "first"))
        store.write(rows().map { if (it.reg == elementReg) it.copy(lastAppAckMs = now - 24 * day) else it })
        e.policyAccepted(view, allRegs, 7000)
        val ping = rows().first { it.reg == elementReg }.pingId!!
        e.handle(UpRequest.Ack(null, "tok-element-1", ping))
        assertEquals(null, rows().first { it.reg == elementReg }.pingId)
    }

    @Test
    fun `L1 - a stamp a week ahead of the clock is written back as now`() {
        seed("last_app_ack_ms" to now + 7 * day, "refresh_ms" to now + 7 * day)
        lateEngine().policyAccepted(view, allRegs, 7000)
        val row = rows().first { it.reg == elementReg }
        assertEquals(now, row.lastAppAckMs)
        assertEquals(now, row.refreshMs)
    }

    @Test
    fun `L2 - a 429 keeps the key pending and retries it at the next policy, only a 400 drops it`() {
        store.write(UpFixtures.rows.map { if (it.reg == elementReg) it.copy(vapid = keyA) else it })
        server.registered += UpServer.Registered.Rejected(429)
        var clock = now
        val e = lateEngine { clock }
        e.handle(UpRequest.Register(element, "tok-element-1", null, keyB))
        assertEquals(keyB, rows().first { it.reg == elementReg }.vapidPending)
        assertTrue(store.notes().isEmpty())
        clock += 10 * 60_000L
        e.policyAccepted(view, allRegs, 7000)
        assertEquals(keyB, rows().first { it.reg == elementReg }.vapid)
        assertEquals(2, server.registerBodies.size)
    }

    @Test
    fun `L7 - a registration confirmed again clears the unanswered note, a taken key the bad-key note`() {
        store.note(UpNote(element, NOTE_UNANSWERED, 1))
        store.note(UpNote(element, NOTE_BAD_KEY, 1))
        lateEngine().handle(UpRequest.Register(element, "tok-new", null, keyA))
        assertEquals(listOf(NOTE_BAD_KEY), store.notes().map { it.kind })
        val reg = rows().single().reg
        store.write(rows().map { it.copy(vapid = keyB) })
        lateEngine().handle(UpRequest.Register(element, "tok-new", null, keyA))
        assertEquals(keyA, rows().single { it.reg == reg }.vapid)
        assertTrue(store.notes().isEmpty())
    }

    @Test
    fun `a pending row's REGISTER is retried with the newest key (4-6)`() {
        store.write(UpFixtures.rows)
        lateEngine().handle(UpRequest.Register(element, "tok-element-2", null, keyA))
        assertEquals(keyA, server.registerBodies.single().vapid)
    }

    // ---- Design 115: held while the apps have no network -----------------------------------------------------------

    private val message = UpWireMessage("deadbeef000000000000000000000006", "c0ffee00000000000000000000000002", "high", "AAEC")

    @Test
    fun `design 115 - held, no pull runs and the acks wait for the first pull after it, which says held on every step`() {
        store.write(UpFixtures.rows)
        server.pulls += UpMessagesAnswer(listOf(message))
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        phone.sendAll()
        assertEquals(listOf(message.key), engine.pendingAcks())
        // Held: no request at all, the ack kept.
        phone.hold = UpHold.NO_NETWORK
        assertFalse(engine.requestPull("ack"))
        assertEquals(1, server.pullAcks.size)
        assertTrue(engine.held)
        // Held at a step (the hold came after the pull was asked): the pull ends before its call, the ack kept.
        phone.hold = UpHold.NONE
        assertTrue(engine.requestPull("sse"))
        phone.hold = UpHold.NO_NETWORK
        assertFalse(engine.pullStep())
        assertEquals(1, server.pullAcks.size)
        assertEquals(listOf(message.key), engine.pendingAcks())
        // The hold ends: the kept ack rides the first pull, which says held on each of its steps; the next run doesn't.
        phone.hold = UpHold.NONE
        val next = UpWireMessage("deadbeef000000000000000000000007", "c0ffee00000000000000000000000002", "high", "AAEC")
        server.pulls += UpMessagesAnswer(listOf(next), more = true)
        server.pulls += UpMessagesAnswer(emptyList())
        assertTrue(engine.requestPull("net"))
        assertTrue(engine.pullStep())
        // Code QA L4: the run's second step says held too; the mark goes only when the run ends with an answer.
        assertTrue(engine.held)
        assertFalse(engine.pullStep())
        assertEquals(listOf(message.key), server.pullAcks[1])
        assertEquals(listOf(true, true), server.pullHeld.drop(1))
        assertFalse(engine.held)
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        assertEquals(listOf(true, true, false), server.pullHeld.drop(1))
    }

    @Test
    fun `design 115 - QA M5, a held run whose call failed keeps the mark for the next run`() {
        store.write(UpFixtures.rows)
        engine.noteHeld()
        server.pulls += null
        assertTrue(engine.requestPull("net"))
        assertFalse(engine.pullStep())
        assertTrue(engine.held)
        assertTrue(engine.requestPull("net"))
        assertFalse(engine.pullStep())
        assertEquals(listOf(true, true), server.pullHeld)
        assertFalse(engine.held)
    }

    @Test
    fun `design 115 - QA M1, a send refused for the hold asks the net pull and is delivered with no change of shape`() {
        store.write(UpFixtures.rows)
        server.pulls += UpMessagesAnswer(listOf(message))
        assertTrue(engine.requestPull("sse"))
        assertFalse(engine.pullStep())
        // The send found the phone held (the runtime posts these two): not acknowledged, the net pull asked.
        engine.noteHeld(askPull = true)
        engine.failed(phone.delivering.removeAt(0))
        assertEquals(1, phone.pullsAfterHold)
        assertTrue(engine.pendingAcks().isEmpty())
        // The hold was a moment's: no shape change ever comes, the net pull itself delivers it.
        server.pulls += UpMessagesAnswer(listOf(message))
        assertTrue(engine.requestPull("net"))
        assertFalse(engine.pullStep())
        assertEquals(message.key, phone.delivering.single().id)
        assertEquals(listOf(false, true), server.pullHeld)
    }

    @Test
    fun `design 115 - a shape change asks the net pull only once a hold was seen`() {
        store.write(UpFixtures.rows)
        engine.shapeChanged()
        assertEquals(0, phone.pullsAfterHold)
        phone.hold = UpHold.NO_NETWORK
        engine.shapeChanged()
        assertEquals(1, phone.pullsAfterHold)
        // QA M1 (b): every change after the hold asks again, whatever the computed hold says at that call.
        engine.shapeChanged()
        phone.hold = UpHold.NONE
        engine.shapeChanged()
        assertEquals(3, phone.pullsAfterHold)
        // UP off: nothing.
        view = UpFixtures.view("off")
        engine.shapeChanged()
        assertEquals(3, phone.pullsAfterHold)
    }
}
