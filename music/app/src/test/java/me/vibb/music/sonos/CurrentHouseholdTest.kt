package me.vibb.music.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.ui.RoomLine
import me.vibb.music.ui.SheetItem
import me.vibb.music.ui.groupSheet
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.ConcurrentLinkedQueue

/** The phone on its home Wi-Fi, for the tests that don't change networks. */
internal val HOME_LINK = WifiLink(1L, "w-home")

/**
 * "Spill av på" lists only the speakers on the Wi-Fi the phone is on (the user, 2026-11-21: "Vi bør jo bare vise
 * høyttalere som faktisk er på det aktuelle wifi"; ours, not vibb's): each topology is recorded with the network it
 * answered on, the sheet shows that household's rooms there, none elsewhere or off Wi-Fi; players not seen for 30
 * days leave the cache; a room of another household is refused as an unreachable one.
 */
class CurrentHouseholdTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    private val day = 86_400.0

    /** Home: Stua + Kjøkken grouped, on 192.168.1.x. A friend's: Loft, on 10.0.0.x. */
    private val home = Household(
        mapOf("RINCON_H1" to Zone("RINCON_H1", "192.168.1.10", 1400, "Stua"), "RINCON_H2" to Zone("RINCON_H2", "192.168.1.11", 1400, "Kjøkken")),
        listOf(Group("RINCON_H1", listOf("RINCON_H1", "RINCON_H2"))),
    )
    private val friend = Household(mapOf("RINCON_F1" to Zone("RINCON_F1", "10.0.0.10", 1400, "Loft")), listOf(Group("RINCON_F1", listOf("RINCON_F1"))))
    private val cabin = Household(mapOf("RINCON_C1" to Zone("RINCON_C1", "10.1.0.10", 1400, "Hytta")), listOf(Group("RINCON_C1", listOf("RINCON_C1"))))

    private fun xml(h: Household) = "<ZoneGroupState><ZoneGroups>" + h.groups.joinToString("") { g ->
        "<ZoneGroup Coordinator=\"${g.coordinator}\">" + g.members.joinToString("") { u ->
            val z = h.players.getValue(u)
            "<ZoneGroupMember UUID=\"$u\" Location=\"http://${z.ip}:${z.port}/xml/device_description.xml\" ZoneName=\"${z.name}\"/>"
        } + "</ZoneGroup>"
    } + "</ZoneGroups></ZoneGroupState>"

    @Test
    fun `two households on two networks - each network shows its own rooms, none on another or off Wi-Fi`() {
        val file = tmp.root.resolve("sonos.json")
        val cache = SpeakerCache(file) { 1_000_000.0 }
        cache.applyTopology(home, network = "w-home")
        cache.applyTopology(friend, network = "w-friend")
        val doc = SpeakerCache(file).load()
        assertEquals(mapOf("w-home" to CachedNetwork(mapOf("RINCON_H1" to 1_000_000.0)), "w-friend" to CachedNetwork(mapOf("RINCON_F1" to 1_000_000.0))), doc.networks)
        assertEquals("the home's group survives the friend's topology", listOf("Stua + Kjøkken"), SpeakerCache.roomsOn(doc, "w-home").map { it.label })
        assertEquals(listOf("Loft"), SpeakerCache.roomsOn(doc, "w-friend").map { it.label })
        assertEquals("off Wi-Fi", emptyList<Room>(), SpeakerCache.roomsOn(doc, null))
        assertEquals("a network no topology answered on", emptyList<Room>(), SpeakerCache.roomsOn(doc, "w-cafe"))
        assertEquals("the session's view still knows both", listOf("Stua + Kjøkken", "Loft"), SpeakerCache.rooms(doc).map { it.label })
        // a room of another household than this network's is refused as unreachable - only on a confirmed connection;
        // otherwise (no answer on this connection yet, no household known here, a uid the cache can't name) the speaker decides
        assertTrue(SpeakerCache.elsewhere(doc, "RINCON_F1", "w-home", confirmed = true))
        assertFalse("unconfirmed: two LANs may share a key", SpeakerCache.elsewhere(doc, "RINCON_F1", "w-home", confirmed = false))
        assertFalse("a uid the cache can't name", SpeakerCache.elsewhere(doc, "RINCON_GONE", "w-home", confirmed = true))
        assertFalse(SpeakerCache.elsewhere(doc, "RINCON_H2", "w-home", confirmed = true))
        assertFalse(SpeakerCache.elsewhere(doc, "RINCON_F1", null, confirmed = true))
        assertFalse(SpeakerCache.elsewhere(doc, "RINCON_F1", "w-cafe", confirmed = true))
        // the home answering again replaces its own groups only
        val ungrouped = cache.applyTopology(Household(home.players, listOf(Group("RINCON_H1", listOf("RINCON_H1")), Group("RINCON_H2", listOf("RINCON_H2")))), network = "w-home")
        assertEquals(listOf("Kjøkken", "Stua"), SpeakerCache.roomsOn(ungrouped, "w-home").map { it.label })
        assertEquals(listOf(CachedGroup("RINCON_F1", listOf("RINCON_F1"))), ungrouped.groups.filter { it.coordinator == "RINCON_F1" })
    }

    @Test
    fun `the switch between them - home, the friend's, home again after a reconnect`() {
        val cache = SpeakerCache(tmp.root.resolve("sonos.json"))
        var link: WifiLink? = WifiLink(1L, "w-home")
        val asked = ConcurrentLinkedQueue<String>()
        val rooms = RoomDirectory(
            cache,
            { ip, _, _ ->
                asked += ip
                when {
                    link?.key == "w-home" && ip.startsWith("192.168.1.") -> xml(home)
                    link?.key == "w-friend" && ip.startsWith("10.0.0.") -> xml(friend)
                    else -> throw SpeakerUnreachable("not on this LAN")
                }
            },
            { if (link?.key == "w-home") listOf(SsdpHit("192.168.1.10", 1400)) else listOf(SsdpHit("10.0.0.10", 1400)) },
            { link }, scope, System::currentTimeMillis,
        )
        fun waitForRound() = repeat(50) { if (rooms.scanning) Thread.sleep(100) }

        // home, a cold cache: the opening's look finds nothing cached, the round finds the home
        rooms.refresh()
        waitForRound()
        assertEquals(listOf("Stua + Kjøkken"), rooms.state.value.sheet.map { it.label })
        assertFalse(rooms.state.value.stale)

        // the friend's Wi-Fi: no room at once (the home's never), then the friend's once its round answers
        link = WifiLink(2L, "w-friend")
        val there = rooms.view()
        assertEquals(emptyList<Room>(), there.sheet)
        assertTrue(there.stale)
        assertFalse("nothing asked yet: no \"svarer ikke\"", there.noAnswer)
        assertTrue("the session's view keeps the home's", there.rooms.isNotEmpty())
        asked.clear()
        rooms.refresh()
        waitForRound()
        assertEquals("the cached home was asked first - nobody there", listOf("192.168.1.10", "192.168.1.11"), asked.toList().take(2))
        assertEquals(listOf("Loft"), rooms.state.value.sheet.map { it.label })
        assertFalse(rooms.state.value.stale)
        assertTrue("the home's rooms are of another household here", rooms.elsewhere("RINCON_H1"))

        // home again, a new connection to the same LAN: the home at once, unconfirmed (plain lines, no grouping)
        link = WifiLink(3L, "w-home")
        val back = rooms.view()
        assertEquals(listOf("Stua + Kjøkken"), back.sheet.map { it.label })
        assertTrue(back.stale)
        assertFalse(back.noAnswer)
        val lines = groupSheet(back.forSheet(), ours = null, live = false, playing = false)
        assertEquals(listOf(RoomLine.SONOS), lines.map { (it as SheetItem.Speaker).line })
        assertFalse(groupingOffered(back.forSheet(), "RINCON_H1", live = true))
        assertFalse(rooms.elsewhere("RINCON_H1"))
        assertFalse("not before an answer on this connection: the speaker decides", rooms.elsewhere("RINCON_F1"))
        asked.clear()
        val fresh = rooms.refresh()
        assertEquals("this network's household is asked first (the friend's uid sorts before it)", "192.168.1.10", asked.first())
        assertFalse(fresh.stale)
        assertTrue("confirmed now", rooms.elsewhere("RINCON_F1"))
        assertTrue(groupingOffered(fresh.forSheet(), "RINCON_H1", live = true))

        // off Wi-Fi: no rooms, nothing asked
        link = null
        asked.clear()
        val off = rooms.refresh()
        assertEquals(emptyList<Room>(), off.sheet)
        assertTrue(off.stale && off.noAnswer)
        assertTrue(asked.isEmpty())
    }

    @Test
    fun `an answer that came while the network changed is recorded for no network`() {
        val cache = SpeakerCache(tmp.root.resolve("sonos.json"))
        cache.seed(listOf(Zone("RINCON_H1", "192.168.1.10", 1400, "Stua")))
        var link: WifiLink? = WifiLink(1L, "w-home")
        val rooms = RoomDirectory(
            cache, { _, _, _ -> xml(home).also { link = WifiLink(2L, "w-friend") } }, { emptyList() }, { link }, scope, System::currentTimeMillis,
        )
        rooms.refresh()
        assertEquals(emptyMap<String, CachedNetwork>(), cache.load().networks)
        assertTrue("not fresh on the new connection", rooms.state.value.stale)
        assertEquals(emptyList<Room>(), rooms.state.value.sheet)
    }

    @Test
    fun `a reconnect to the same LAN keeps its key - another subnet or gateway doesn't, IPv6 never counts`() {
        val home = SpeakerCache.networkKey(WifiFacts(listOf("192.168.1.23/24", "fe80::1c2:3ff:fe44:5566/64", "2001:db8:1::23/64"), listOf("192.168.1.1", "fe80::1")))
        assertTrue(home!!.matches(Regex("w[0-9a-f]{16}")))
        assertEquals("a new lease, no IPv6 yet", home, SpeakerCache.networkKey(WifiFacts(listOf("192.168.1.57/24"), listOf("192.168.1.1"))))
        assertNotEquals("another router on the same subnet", home, SpeakerCache.networkKey(WifiFacts(listOf("192.168.1.23/24"), listOf("192.168.1.254"))))
        assertNotEquals(home, SpeakerCache.networkKey(WifiFacts(listOf("192.168.2.23/24"), listOf("192.168.1.1"))))
        assertNull("IPv6 only: no key, no household", SpeakerCache.networkKey(WifiFacts(listOf("2001:db8:1::23/64"), listOf("fe80::1"))))
        assertNull(SpeakerCache.networkKey(WifiFacts(emptyList(), listOf("192.168.1.1"))))
        assertEquals("10.0.0.0/8", SpeakerCache.ipv4Subnet("10.0.0.138/8"))
        assertEquals("172.16.4.0/22", SpeakerCache.ipv4Subnet("172.16.7.200/22"))
        assertNull(SpeakerCache.ipv4Subnet("fe80::1/64"))
        assertNull(SpeakerCache.ipv4Subnet("192.168.1.300/24"))
        assertNull(SpeakerCache.ipv4Subnet("192.168.1.3"))
        assertNull(SpeakerCache.ipv4Subnet("example.com/24"))
    }

    @Test
    fun `the 30-day prune - players and networks not seen go, the household last played on stays`() {
        var now = 1_000_000.0
        val cache = SpeakerCache(tmp.root.resolve("sonos.json")) { now }
        cache.applyTopology(home, network = "w-home")
        cache.applyTopology(friend, network = "w-friend")
        cache.markPlayed("RINCON_H1")
        now += 30 * day
        val kept = cache.applyTopology(cabin, network = "w-cabin")
        assertEquals("30 days to the second: kept", setOf("RINCON_H1", "RINCON_H2", "RINCON_F1", "RINCON_C1"), kept.players.keys)
        now += 1.0
        val doc = cache.applyTopology(cabin, network = "w-cabin")
        assertEquals("the friend's speakers go; the home (played on) stays however old", setOf("RINCON_H1", "RINCON_H2", "RINCON_C1"), doc.players.keys)
        assertEquals(setOf("w-home", "w-cabin"), doc.networks.keys)
        assertEquals(setOf("RINCON_H1"), doc.networks.getValue("w-home").households.keys)
        assertFalse(doc.groups.any { it.coordinator == "RINCON_F1" })
        assertEquals("back home after the cabin: its rooms at once", listOf("Stua + Kjøkken"), SpeakerCache.roomsOn(doc, "w-home").map { it.label })
        assertEquals("the report still names the home (P-L2)", setOf("RINCON_H1", "RINCON_H2"), SpeakerCache.reportable(doc, null, now).keys)

        // never played on a room: the home is a household like any other
        val other = SpeakerCache(tmp.root.resolve("sonos2.json")) { now }
        other.applyTopology(home, network = "w-home")
        now += 31 * day
        val alone = other.applyTopology(friend, network = "w-friend")
        assertEquals(setOf("RINCON_F1"), alone.players.keys)
        assertEquals(setOf("w-friend"), alone.networks.keys)
        assertEquals(listOf(CachedGroup("RINCON_F1", listOf("RINCON_F1"))), alone.groups)
    }

    /** A directory over [cache] on the Wi-Fi [link] says: each LAN answers for the households [lans] puts on it. */
    private inner class Lan(val cache: SpeakerCache, scoped: List<String>? = null) {
        var link: WifiLink? = null
        var lans: Map<String, List<Household>> = emptyMap()
        var hits: List<String> = emptyList()
        val asked = ConcurrentLinkedQueue<String>()
        val rooms = RoomDirectory(
            cache,
            { ip, _, _ ->
                asked += ip
                lans[link?.let { it.handle.toString() }].orEmpty().firstOrNull { h -> h.players.values.any { it.ip == ip } }?.let(::xml)
                    ?: throw SpeakerUnreachable("not on this LAN")
            },
            { hits.map { SsdpHit(it, 1400) } }, { link }, scope, System::currentTimeMillis,
            scoped = { scoped },
        )

        fun waitForRound() = repeat(50) { if (rooms.scanning) Thread.sleep(100) }
    }

    @Test
    fun `H1 a friend's LAN with the home's key - back home the remembered room is still asked, never skipped`() {
        val lan = Lan(SpeakerCache(tmp.root.resolve("sonos.json")))
        // connection 1: home (the key a router's defaults give, here shared with the friend's)
        lan.link = WifiLink(1L, "w-lan")
        lan.lans = mapOf("1" to listOf(home))
        lan.hits = listOf("192.168.1.10")
        lan.rooms.refresh()
        lan.waitForRound()
        assertEquals(listOf("Stua + Kjøkken"), lan.rooms.state.value.sheet.map { it.label })
        // connection 2: the friend's LAN, the same key; the home's rows show unconfirmed and are never refused
        lan.link = WifiLink(2L, "w-lan")
        lan.lans = mapOf("2" to listOf(friend))
        lan.hits = listOf("10.0.0.10")
        assertFalse(lan.rooms.elsewhere("RINCON_H1"))
        lan.rooms.refresh()
        lan.waitForRound()
        assertEquals("both answered on the key", setOf("RINCON_H1", "RINCON_F1"), lan.cache.load().networks.getValue("w-lan").households.keys)
        assertFalse("the home's household answered on this key: not elsewhere", lan.rooms.elsewhere("RINCON_H1"))
        // connection 3: home again, the next start's look at renderer.json's room (probe asks elsewhere first)
        lan.link = WifiLink(3L, "w-lan")
        lan.lans = mapOf("3" to listOf(home))
        assertFalse("unconfirmed: the remembered home room is asked", lan.rooms.elsewhere("RINCON_H1"))
        lan.rooms.refresh()
        assertFalse(lan.rooms.state.value.stale)
        assertFalse("confirmed, and the home is this key's", lan.rooms.elsewhere("RINCON_H1"))
    }

    @Test
    fun `a friend's LAN with the home's key - after the friend's answer only its rooms, back home after the home's only the home's`() {
        val lan = Lan(SpeakerCache(tmp.root.resolve("sonos.json")))
        lan.link = WifiLink(1L, "w-lan")
        lan.lans = mapOf("1" to listOf(home))
        lan.hits = listOf("192.168.1.10")
        lan.rooms.refresh()
        lan.waitForRound()
        // the friend's LAN, the same key: before any answer the key's household shows, stale (a fast first look)
        lan.link = WifiLink(2L, "w-lan")
        lan.lans = mapOf("2" to listOf(friend))
        lan.hits = listOf("10.0.0.10")
        val first = lan.rooms.view()
        assertEquals(listOf("Stua + Kjøkken"), first.sheet.map { it.label })
        assertTrue(first.stale)
        lan.rooms.refresh()
        lan.waitForRound()
        assertEquals("after the friend's answer only the friend's", listOf("Loft"), lan.rooms.state.value.sheet.map { it.label })
        assertEquals("the key keeps both for the next connection", setOf("RINCON_H1", "RINCON_F1"), lan.cache.load().networks.getValue("w-lan").households.keys)
        assertFalse("listed under the key: not refused", lan.rooms.elsewhere("RINCON_H1"))
        // a failed look later on this connection keeps the sheet to what answered here
        lan.lans = emptyMap()
        lan.hits = emptyList()
        lan.rooms.refresh()
        lan.waitForRound()
        assertEquals(listOf("Loft"), lan.rooms.state.value.sheet.map { it.label })
        // home again: the key's households at once, stale; after the home's answer only the home's
        lan.link = WifiLink(3L, "w-lan")
        lan.lans = mapOf("3" to listOf(home))
        assertEquals(listOf("Stua + Kjøkken", "Loft"), lan.rooms.view().sheet.map { it.label })
        lan.rooms.refresh()
        assertEquals(listOf("Stua + Kjøkken"), lan.rooms.state.value.sheet.map { it.label })
        assertFalse(lan.rooms.state.value.stale)
    }

    @Test
    fun `M2 two households on one LAN - both listed, neither elsewhere, both asked first`() {
        val s1 = Household(mapOf("RINCON_A1" to Zone("RINCON_A1", "192.168.1.10", 1400, "Stua")), listOf(Group("RINCON_A1", listOf("RINCON_A1"))))
        val s2 = Household(mapOf("RINCON_B1" to Zone("RINCON_B1", "192.168.1.20", 1400, "Loft")), listOf(Group("RINCON_B1", listOf("RINCON_B1"))))
        val lan = Lan(SpeakerCache(tmp.root.resolve("sonos.json")))
        lan.cache.applyTopology(friend, network = "w-friend")
        lan.link = WifiLink(1L, "w-lan")
        lan.lans = mapOf("1" to listOf(s1, s2))
        lan.hits = listOf("192.168.1.20")
        lan.rooms.refresh()
        lan.waitForRound()
        lan.hits = listOf("192.168.1.10")
        assertTrue(lan.rooms.rescanInBackground())
        lan.waitForRound()
        assertEquals(setOf("RINCON_A1", "RINCON_B1"), lan.cache.load().networks.getValue("w-lan").households.keys)
        assertEquals(listOf("Loft", "Stua"), lan.rooms.view().sheet.map { it.label })
        assertFalse(lan.rooms.elsewhere("RINCON_A1"))
        assertFalse(lan.rooms.elsewhere("RINCON_B1"))
        assertTrue(lan.rooms.elsewhere("RINCON_F1"))
        lan.asked.clear()
        lan.lans = emptyMap()
        lan.rooms.refreshQuietly()
        assertEquals("this LAN's households first, the friend's last", listOf("192.168.1.10", "192.168.1.20", "10.0.0.10"), lan.asked.toList())
    }

    @Test
    fun `M3 the prune keeps the parent's scoped rooms and their household however long the phone was away`() {
        var now = 1_000_000.0
        val lan = Lan(SpeakerCache(tmp.root.resolve("sonos.json")) { now }, scoped = listOf("RINCON_H2"))
        lan.link = WifiLink(1L, "w-home")
        lan.lans = mapOf("1" to listOf(home))
        lan.hits = listOf("192.168.1.10")
        lan.rooms.refresh()
        lan.waitForRound()
        assertNull("never played on a room", lan.cache.load().played)
        now += 31 * day
        lan.link = WifiLink(2L, "w-friend")
        lan.lans = mapOf("2" to listOf(friend))
        lan.hits = listOf("10.0.0.10")
        lan.rooms.refresh()
        lan.waitForRound()
        val doc = lan.cache.load()
        assertEquals("the scoped room's household stays", setOf("RINCON_H1", "RINCON_H2", "RINCON_F1"), doc.players.keys)
        assertEquals(setOf("w-home", "w-friend"), doc.networks.keys)
        assertEquals("the parent's card keeps the home", setOf("RINCON_H1", "RINCON_H2"), SpeakerCache.reportable(doc, listOf("RINCON_H2"), now).keys)
        // a scoped uid stays even with no household of its own
        val bare = SpeakerDoc(mapOf("RINCON_X" to CachedPlayer("10.0.0.9", seenAt = 0.0), "RINCON_Y" to CachedPlayer("10.0.0.8", seenAt = 0.0)))
        assertEquals(setOf("RINCON_X"), SpeakerCache.pruned(bare, now, listOf("RINCON_X")).players.keys)
    }

    @Test
    fun `L4 an answer under a new key on the same handle is recorded for no network`() {
        val cache = SpeakerCache(tmp.root.resolve("sonos.json"))
        cache.seed(listOf(Zone("RINCON_H1", "192.168.1.10", 1400, "Stua")))
        var link: WifiLink? = WifiLink(1L, "w-old")
        val rooms = RoomDirectory(
            cache, { _, _, _ -> xml(home).also { link = WifiLink(1L, "w-new") } }, { emptyList() }, { link }, scope, System::currentTimeMillis,
        )
        rooms.refresh()
        assertEquals(emptyMap<String, CachedNetwork>(), cache.load().networks)
        assertTrue(rooms.state.value.stale)
    }

    @Test
    fun `the marks - fresh only after an answer on this connection, svarer ikke only after a look got none`() {
        val l1 = WifiLink(1L, "w-home")
        val l2 = WifiLink(2L, "w-home")
        assertEquals("off Wi-Fi", true to true, SpeakerCache.roomMarks(null, 1L, null))
        assertEquals(false to false, SpeakerCache.roomMarks(l1, 1L, null))
        assertEquals("a reconnect: stale, but no \"svarer ikke\" before a look", true to false, SpeakerCache.roomMarks(l2, 1L, null))
        assertEquals(true to true, SpeakerCache.roomMarks(l1, null, 1L))
        assertEquals("a failed look on the last connection says nothing here", true to false, SpeakerCache.roomMarks(l2, null, 1L))
        val stua = Room("Stua", "RINCON_H1", listOf("RINCON_H1" to "Stua"))
        assertEquals(RoomLine.STALE, (groupSheet(RoomsView(listOf(stua), stale = true, scanning = false, noAnswer = true), null, false, false).single() as SheetItem.Speaker).line)
        assertEquals(RoomLine.SONOS, (groupSheet(RoomsView(listOf(stua), stale = true, scanning = false, noAnswer = false), null, false, false).single() as SheetItem.Speaker).line)
    }

    @Test
    fun `the service - the sheet's rows, a switch to another household refused, the startup look never asks it`() {
        val service = moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt").readText()
        val renderer = moduleFile("src/main/java/me/vibb/music/sonos/SonosRenderer.kt").readText()
        val publish = service.substringAfter("private fun publish() {").substringBefore("\n    }\n")
        assertTrue(publish.contains("rooms = allowedRooms(home.rooms.state.value.forSheet(), roomScope"))
        assertTrue("a \"+\" only on a row of the sheet", service.substringAfter("private fun roomJoin(").substringBefore("\n    }\n").contains("val view = home.rooms.state.value.forSheet()"))
        // the switch: after the same-room rule (a live session goes on whatever the sheet shows) and the scope, before anything moves
        val toRoom = service.substringAfter("private suspend fun toRoom(uid: String) {").substringBefore("\n    }\n")
        val refusal = toRoom.substringAfter("if (withContext(Dispatchers.IO) { home.rooms.elsewhere(uid) }) {").substringBefore("val name = ")
        assertTrue(toRoom.indexOf("home.rooms.elsewhere(uid)") > toRoom.indexOf("if (!groupAllowed("))
        assertTrue(toRoom.indexOf("home.rooms.elsewhere(uid)") > toRoom.indexOf("old.uid == uid"))
        assertTrue(refusal.contains("PlayerHub.mutableMessages.tryEmit(PlayerHub.Message.CANT_PLAY_ON_SONOS)"))
        assertTrue(refusal.contains("return"))
        for (move in listOf("handOff(", "saveNow(", "home.write(", "attach(", "start(", ".play()")) assertFalse(move, refusal.contains(move))
        // renderer.json's room on the next start (and every look before the phone plays): never asked - no answer, kept
        val probe = renderer.substringAfter("suspend fun probe(").substringBefore("\n    }\n")
        assertTrue(probe.contains("if (withContext(Dispatchers.IO) { home.rooms.elsewhere(target) }) return null"))
        assertTrue(probe.indexOf("home.rooms.elsewhere(target)") < probe.indexOf("actor.call("))
    }
}
