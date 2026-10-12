package me.vibb.music.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import me.vibb.music.sonos.SonosFakes.zoneGroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Port of palchrb/vibb `tests/sonos_groups.py` (MIT, Copyright (c) 2026 palchrb): the phone shows
 * and selects groups as the Sonos app made them - it does not manage them. Checks 1-4 are ported;
 * 5-6 are the Pi screen's grey "ghost" drawing, which on the phone is the sheet's "svarer ikke"
 * subtitle (step 3a, design 26a §5.1) and here the `stale` flag.
 */
class TopologyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun cache() = SpeakerCache(tmp.root.resolve("sonos.json"), wallS = { 1000.0 })

    @Test
    fun `both wrapper forms parse to the same shape`() {
        val wrapped = Topology.parse(zoneGroupState("zgs_wrapped.xml"))!!
        assertEquals(Zone("RINCON_STUA01400", "10.0.0.11", 1400, "Stua"), wrapped.players["RINCON_STUA01400"])
        assertEquals(Zone("RINCON_KJOKKEN01400", "10.0.0.12", 1400, "Kjøkken"), wrapped.players["RINCON_KJOKKEN01400"])
        val bare = Topology.parse(zoneGroupState("zgs_bare.xml"))!!
        assertEquals(Zone("RINCON_BARNEROM01400", "10.0.0.24", 1400, "Barnerom"), bare.players["RINCON_BARNEROM01400"])
        assertNull(Topology.parse("not xml"))
        assertEquals(Household(emptyMap(), emptyList()), Topology.parse("<ZoneGroups/>"))
    }

    @Test
    fun `1 invisible members dropped, coordinator first, a group whose coordinator is invisible skipped`() {
        val h = Topology.parse(zoneGroupState("zgs_wrapped.xml"))!!
        assertFalse("bonded subs are not pickable rooms", "RINCON_SUB01400" in h.players)
        assertFalse("a satellite is not a member", "RINCON_SURROUND01400" in h.players)
        assertFalse("RINCON_HIDDEN01400" in h.players)
        assertEquals(
            listOf(
                Group("RINCON_KJOKKEN01400", listOf("RINCON_KJOKKEN01400", "RINCON_STUA01400")),
                Group("RINCON_BAD01400", listOf("RINCON_BAD01400")),
            ),
            h.groups,
        )
    }

    @Test
    fun `1 the topology heals a moved ip and a rename, merges players, replaces groups`() {
        val c = cache()
        c.seed(listOf(Zone("RINCON_STUA01400", "10.0.0.99", 1400, "Gamlestua"), Zone("RINCON_OLD01400", "10.0.0.98", 1400, "Loftet")))
        c.applyTopology(Household(emptyMap(), listOf(Group("RINCON_STUA01400", listOf("RINCON_STUA01400", "RINCON_BAD01400")))))
        val doc = c.applyTopology(Topology.parse(zoneGroupState("zgs_wrapped.xml"))!!)
        assertEquals("10.0.0.11", doc.players.getValue("RINCON_STUA01400").ip)
        assertEquals("Stua", doc.players.getValue("RINCON_STUA01400").name)
        assertTrue("a row the scan missed is never deleted", "RINCON_OLD01400" in doc.players)
        assertEquals(
            listOf(CachedGroup("RINCON_KJOKKEN01400", listOf("RINCON_KJOKKEN01400", "RINCON_STUA01400")), CachedGroup("RINCON_BAD01400", listOf("RINCON_BAD01400"))),
            doc.groups,
        )
        assertEquals(doc, c.load())
    }

    @Test
    fun `2 nobody answering raises, and the sheet serves the cache marked stale`() {
        val c = cache()
        c.applyTopology(Topology.parse(zoneGroupState("zgs_wrapped.xml"))!!)
        val asked = mutableListOf<String>()
        val rooms = RoomDirectory(
            c, { ip, _, _ -> asked.add(ip); throw SpeakerUnreachable("down") }, { emptyList() }, { true },
            CoroutineScope(Dispatchers.IO), { 0L },
        )
        try {
            rooms.refreshTopology()
            fail("no speaker answering must raise")
        } catch (_: SpeakerUnreachable) {
        }
        assertEquals("in uid order", listOf("10.0.0.13", "10.0.0.12", "10.0.0.11"), asked)
        val view = rooms.refresh()
        assertTrue(view.stale)
        assertEquals(listOf("Kjøkken + Stua", "Bad"), view.rooms.map { it.label })
    }

    @Test
    fun `2 the refresh asks under one budget, 2 s per ip and 6 s in all`() {
        val c = cache()
        c.seed((1..5).map { Zone("RINCON_$it", "10.0.0.$it", 1400, "R$it") })
        var now = 0L
        val budgets = mutableListOf<Long>()
        val rooms = RoomDirectory(
            c, { _, _, timeout -> budgets.add(timeout); now += timeout; throw SpeakerUnreachable("timeout") }, { emptyList() }, { true },
            CoroutineScope(Dispatchers.IO), { now },
        )
        try {
            rooms.refreshTopology()
            fail()
        } catch (_: SpeakerUnreachable) {
        }
        assertEquals(listOf(2_000L, 2_000L, 2_000L), budgets)
    }

    @Test
    fun `the second cached speaker answers when the first doesn't`() {
        val c = cache()
        c.seed(listOf(Zone("RINCON_A", "10.0.0.1", 1400, "A"), Zone("RINCON_B", "10.0.0.2", 1401, "B")))
        val rooms = RoomDirectory(
            c, { ip, port, _ -> if (ip == "10.0.0.1") throw SpeakerUnreachable("down") else { assertEquals(1401, port); zoneGroupState("zgs_bare.xml") } },
            { emptyList() }, { true }, CoroutineScope(Dispatchers.IO), { 0L },
        )
        val view = rooms.refresh()
        assertFalse(view.stale)
        assertEquals(listOf("Stua + Kjøkken + Bad", "A", "B", "Barnerom"), view.rooms.map { it.label })
    }

    @Test
    fun `without Wi-Fi nothing is asked and nothing scans - the cabin case`() {
        val c = cache()
        c.seed(listOf(Zone("RINCON_A", "10.0.0.1", 1400, "Stua")))
        var asked = 0
        var scans = 0
        val rooms = RoomDirectory(c, { _, _, _ -> asked++; "" }, { scans++; emptyList() }, { false }, CoroutineScope(Dispatchers.IO), { 0L })
        val view = rooms.refresh()
        assertTrue(view.stale)
        assertFalse(view.scanning)
        assertEquals(listOf("Stua"), view.rooms.map { it.label })
        assertEquals(0, asked)
        assertEquals(0, scans)
    }

    @Test
    fun `3 and 4 the rows - a group is one row targeting the coordinator, absorbed members don't repeat`() {
        val doc = SpeakerDoc(
            players = mapOf(
                "RINCON_C" to CachedPlayer("10.0.0.13", name = "Bad"),
                "RINCON_B" to CachedPlayer("10.0.0.12", name = "Kjøkken"),
                "RINCON_A" to CachedPlayer("10.0.0.11", name = "Stua"),
            ),
            groups = listOf(CachedGroup("RINCON_B", listOf("RINCON_B", "RINCON_A")), CachedGroup("RINCON_C", listOf("RINCON_C"))),
        )
        val rows = SpeakerCache.rooms(doc)
        assertEquals(Room("Kjøkken + Stua", "RINCON_B", listOf("Kjøkken", "Stua"), listOf("RINCON_B", "RINCON_A")), rows[0])
        assertTrue("solo zones unchanged", Room("Bad", "RINCON_C", listOf("Bad")) in rows)
        assertEquals("absorbed members don't repeat as solo rows", 2, rows.size)
    }

    @Test
    fun `4 a group naming a uid the cache lacks gives no group row`() {
        val doc = SpeakerDoc(
            players = mapOf("RINCON_C" to CachedPlayer("10.0.0.13", name = "Bad"), "RINCON_B" to CachedPlayer("10.0.0.12", name = "Kjøkken"), "RINCON_A" to CachedPlayer("10.0.0.11", name = "Stua")),
            groups = listOf(CachedGroup("RINCON_B", listOf("RINCON_B", "RINCON_GONE"))),
        )
        val rows = SpeakerCache.rooms(doc)
        assertEquals(3, rows.size)
        assertTrue(rows.all { it.names.size == 1 })
        assertEquals("players by name", listOf("Bad", "Kjøkken", "Stua"), rows.map { it.label })
    }

    @Test
    fun `a three-member group is one row, every name coordinator first`() {
        val c = cache()
        val doc = c.applyTopology(Topology.parse(zoneGroupState("zgs_bare.xml"))!!)
        assertEquals(
            listOf(Room("Stua + Kjøkken + Bad", "RINCON_STUA01400", listOf("Stua", "Kjøkken", "Bad"), listOf("RINCON_STUA01400", "RINCON_KJOKKEN01400", "RINCON_BAD01400")), Room("Barnerom", "RINCON_BARNEROM01400", listOf("Barnerom"))),
            SpeakerCache.rooms(doc),
        )
    }

    @Test
    fun `the coordinator of a speaker's own group, visible or not`() {
        val zgs = zoneGroupState("zgs_wrapped.xml")
        assertEquals("RINCON_KJOKKEN01400", Topology.coordinatorOf(zgs, "RINCON_STUA01400"))
        assertEquals("RINCON_KJOKKEN01400", Topology.coordinatorOf(zgs, "RINCON_KJOKKEN01400"))
        assertEquals("RINCON_KJOKKEN01400", Topology.coordinatorOf(zgs, "RINCON_SUB01400"))
        assertNull(Topology.coordinatorOf(zgs, "RINCON_NOBODY"))
    }

    @Test
    fun `Location - ip and port`() {
        assertEquals("10.0.0.11" to 1400, Topology.location("http://10.0.0.11:1400/xml/device_description.xml"))
        assertEquals("10.0.2.2" to 1402, Topology.location("http://10.0.2.2:1402/xml/device_description.xml"))
        assertEquals("10.0.0.11" to 1400, Topology.location("http://10.0.0.11/xml/device_description.xml"))
        assertEquals("fe80::1" to 1400, Topology.location("http://[fe80::1]:1400/x"))
        assertNull(Topology.location("ftp://10.0.0.11/x"))
        assertNull("a name is never cached (L1)", Topology.location("http://sonos-stua.local:1400/x"))
        assertNull(Topology.location("http://999.0.0.1:1400/x"))
        assertNull(Topology.location("http://[not:v6]:1400/x"))
        assertNull(Topology.location("http://10.0.0.11:99999/x"))
    }
}
