package me.vibb.music.sonos

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The event socket's reducer (design 53 §2.2, §3), replaying `src/test/resources/sonos/ws/` (provisional
 * until the capture, its README): the handshake's order, our player, the re-subscribe rule, `MOVED`
 * as an event and never a connect, health, both state spellings, and what is dropped.
 */
class SonosEventsTest {
    private val stua = "RINCON_00000000000001400"
    private val kjokken = "RINCON_00000000000001401"
    private val ip = "192.168.1.16"

    /** A fixture's messages, each as the text a speaker sends. */
    private fun messages(name: String): List<String> =
        (Json.parseToJsonElement(SonosFakes.fixture("ws/$name")) as JsonArray).map { it.toString() }

    private fun header(command: String): JsonObject = (Json.parseToJsonElement(command) as JsonArray)[0].jsonObject

    private fun what(command: String): String = header(command).let { h ->
        listOfNotNull(h["namespace"]?.jsonPrimitive?.content, h["command"]?.jsonPrimitive?.content, (h["groupId"] ?: h["householdId"])?.jsonPrimitive?.content).joinToString(" ")
    }

    private fun feed(plan: SonosEvents.Plan, text: String, at: Long = 1L): SonosEvents.Step = plan.receive(SonosEvents.parse(text)!!, at)

    /** The handshake through its last message; the steps in order. */
    private fun shaken(plan: SonosEvents.Plan = SonosEvents.Plan(stua, ip)): List<SonosEvents.Step> = messages("handshake.json").map { feed(plan, it) }

    @Test
    fun `the handshake - empty command, getGroups, subscribe then getPlaybackStatus, then groups - healthy on the ack and the status`() {
        val plan = SonosEvents.Plan(stua, ip)
        val first = plan.start().single()
        assertEquals("[{\"cmdId\":\"vibb-1\"},{}]", first)
        val steps = shaken(plan)
        assertEquals(listOf("groups:1 getGroups Sonos_FAKEhousehold000000000000001"), steps[0].commands.map(::what))
        assertEquals(
            listOf("playback:1 subscribe $stua:100", "playback:1 getPlaybackStatus $stua:100", "groups:1 subscribe Sonos_FAKEhousehold000000000000001"),
            steps[1].commands.map(::what),
        )
        assertEquals(stua, plan.playerId)
        assertTrue("every command carries a cmdId", (steps[0].commands + steps[1].commands).all { header(it)["cmdId"] != null })
        assertTrue("no groups event for the handshake's own reply", steps[1].events.isEmpty())
        assertEquals("the getPlaybackStatus reply is an event", listOf(SocketEvent.Playback("$stua:100", WsPlayback.PLAYING, 431_250, 1L)), steps[3].events)
        assertTrue(plan.healthy)
        // the ack alone, or the status alone, is not health
        val noStatus = SonosEvents.Plan(stua, ip)
        messages("handshake.json").take(3).forEach { feed(noStatus, it) }
        assertFalse(noStatus.healthy)
    }

    @Test
    fun `our player by its id, else by the websocketUrl host we dialled - a URL is read, never connected to`() {
        val byHost = SonosEvents.Plan("RINCON_NOT_IN_THE_LIST", "192.168.1.17")
        shaken(byHost)
        assertEquals(kjokken, byHost.playerId)
        assertFalse("its group isn't the one subscribed in the replies", byHost.healthy)
        val nobody = SonosEvents.Plan("RINCON_NOT_IN_THE_LIST", "192.168.1.99")
        val steps = shaken(nobody)
        assertNull(nobody.playerId)
        assertTrue("no playback subscription for nobody", steps[1].commands.none { "playback:1" in what(it) })
        assertEquals("192.168.1.16", SonosEvents.wsHost("wss://192.168.1.16:1443/websocket/api"))
        assertEquals("fe80::1", SonosEvents.wsHost("wss://[fe80::1]:1443/websocket/api"))
        assertNull(SonosEvents.wsHost("https://192.168.1.16/"))
    }

    @Test
    fun `a groups event re-subscribes playback only for a changed group our player coordinates`() {
        val plan = SonosEvents.Plan(stua, ip)
        shaken(plan)
        val (joined, away, alone) = messages("groups_changed.json")
        val j = feed(plan, joined)
        assertTrue("Kjøkken joined, the id stayed: nothing to send", j.commands.isEmpty())
        assertEquals(listOf(SocketEvent.Groups("$stua:100", stua, listOf(stua, kjokken), listOf(WsGroup("$stua:100", stua, listOf(stua, kjokken))))), j.events)
        assertTrue(plan.healthy)
        val a = feed(plan, away)
        assertTrue("grouped away: no subscribe to Kjøkken's group (it would only earn a MOVED)", a.commands.isEmpty())
        assertFalse("and not healthy: our uid coordinates no subscribed group", plan.healthy)
        assertEquals(kjokken, (a.events.single() as SocketEvent.Groups).ourCoordinatorId)
        val b = feed(plan, alone)
        assertEquals("coordinating again, a new id", listOf("playback:1 subscribe $stua:104", "playback:1 getPlaybackStatus $stua:104"), b.commands.map(::what))
        assertFalse("not healthy until the new ack and status", plan.healthy)
    }

    @Test
    fun `MOVED is an event, never a connect, and our subscription is no longer healthy - UPDATED is nothing`() {
        val plan = SonosEvents.Plan(stua, ip)
        shaken(plan)
        val (moved, updated) = messages("moved.json")
        val m = feed(plan, moved)
        assertTrue("nothing sent, nothing dialled", m.commands.isEmpty())
        assertEquals(listOf(SocketEvent.Moved(kjokken)), m.events)
        assertFalse(plan.healthy)
        assertTrue(feed(plan, updated).events.isEmpty())
    }

    @Test
    fun `a subscribe answer is told by its response first - one carrying the status acks and is an event (qa-53a-code 7)`() {
        val plan = SonosEvents.Plan(stua, ip)
        val hs = messages("handshake.json")
        feed(plan, hs[0])
        feed(plan, hs[1])
        val answer = "[{\"namespace\":\"playback:1\",\"groupId\":\"$stua:100\",\"response\":\"subscribe\",\"success\":true," +
            "\"type\":\"playbackStatus\"},{\"playbackState\":\"PLAYBACK_STATE_PAUSED\",\"positionMillis\":5000}]"
        val step = feed(plan, answer, at = 7L)
        assertEquals(listOf(SocketEvent.Playback("$stua:100", WsPlayback.PAUSED, 5_000, 7L)), step.events)
        assertTrue("acked and the status read", plan.healthy)
    }

    @Test
    fun `a refused subscribe is not healthy, the connection stays for groups`() {
        val plan = SonosEvents.Plan(stua, ip)
        val hs = messages("handshake.json")
        feed(plan, hs[0])
        feed(plan, hs[1])
        feed(plan, messages("subscribe_refused.json").single())
        feed(plan, hs[3])
        assertFalse(plan.healthy)
        assertTrue("groups still arrive", feed(plan, messages("groups_changed.json")[0]).events.single() is SocketEvent.Groups)
    }

    @Test
    fun `playbackStatus - both spellings, our group only, unknown states and other types dropped`() {
        val plan = SonosEvents.Plan(stua, ip)
        shaken(plan)
        val events = messages("playback_events.json").mapIndexed { i, m -> feed(plan, m, at = 100L + i).events }
        assertEquals(SocketEvent.Playback("$stua:100", WsPlayback.PAUSED, 440_120, 100L), events[0].single())
        assertEquals(WsPlayback.BUFFERING, (events[1].single() as SocketEvent.Playback).state)
        assertEquals("PLAYING without the prefix", WsPlayback.PLAYING, (events[2].single() as SocketEvent.Playback).state)
        assertEquals(WsPlayback.IDLE, (events[3].single() as SocketEvent.Playback).state)
        assertTrue("another group's status", events[4].isEmpty())
        assertTrue("metadataStatus isn't subscribed or read", events[5].isEmpty())
        assertTrue("an unknown state", events[6].isEmpty())
        assertEquals(TransportState.TRANSITIONING, WsPlayback.BUFFERING.transport)
        assertEquals(TransportState.STOPPED, WsPlayback.IDLE.transport)
    }

    @Test
    fun `junk, another shape and an over-size message are dropped unread`() {
        assertNull(SonosEvents.parse("not json"))
        assertNull(SonosEvents.parse("{\"a\":1}"))
        assertNull(SonosEvents.parse("[{}, {}, {}]"))
        assertNull(SonosEvents.parse("[{}, 3]"))
        val big = "[{\"type\":\"groups\"},{\"pad\":\"" + "x".repeat(SonosEvents.MAX_MESSAGE_CHARS) + "\"}]"
        assertNull(SonosEvents.parse(big))
    }
}
