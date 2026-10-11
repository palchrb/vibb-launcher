package me.vibb.music.sonos

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import me.vibb.music.sonos.SonosFakes.zoneGroupState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Port of palchrb/vibb `tests/sonos_hold_scan.py` (`pipewire`; MIT, Copyright (c) 2026 palchrb): the
 * list fills itself - nobody answering serves the old rows at once, marked stale, with ONE SSDP
 * round behind them; a second ask while it runs starts nothing. Plus the cache file's rules
 * (`_load_cache`/`_save_cache`) and `pi/vibb/renderer.py`'s read/write.
 */
class SpeakerCacheTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @After
    fun tearDown() = scope.cancel()

    /** The household SSDP finds: Edith and Kjøkken, answering their topology on 192.168.1.x. */
    private val found = """<ZoneGroupState><ZoneGroups>
        <ZoneGroup Coordinator="RINCON_E"><ZoneGroupMember UUID="RINCON_E" Location="http://192.168.1.17:1400/xml/device_description.xml" ZoneName="Edith"/></ZoneGroup>
        <ZoneGroup Coordinator="RINCON_K"><ZoneGroupMember UUID="RINCON_K" Location="http://192.168.1.21:1400/xml/device_description.xml" ZoneName="Kjøkken"/></ZoneGroup>
        </ZoneGroups></ZoneGroupState>"""

    private inner class Rig {
        val cache = SpeakerCache(tmp.root.resolve("sonos.json"))
        val discovers = AtomicInteger()
        val gate = CountDownLatch(1)

        /** Cached ips never answer; the SSDP round's speakers do. */
        val rooms = RoomDirectory(
            cache,
            { ip, _, _ -> if (ip.startsWith("192.168.1.") && discovers.get() > 0) found else throw SpeakerUnreachable("unreachable") },
            {
                discovers.incrementAndGet()
                gate.await(5, TimeUnit.SECONDS)
                listOf(SsdpHit("192.168.1.17", 1400), SsdpHit("192.168.1.21", 1400))
            },
            { true }, scope, System::currentTimeMillis,
        )

        fun waitForRound() {
            repeat(50) {
                if (!rooms.scanning) return
                Thread.sleep(100)
            }
        }
    }

    @Test
    fun `1 empty cache - an instant stale answer, one SSDP round behind it`() {
        val rig = Rig()
        val t0 = System.nanoTime()
        val view = rig.rooms.refresh()
        assertEquals(emptyList<Room>(), view.rooms)
        assertTrue(view.stale)
        assertTrue(view.scanning)
        assertTrue("the request must not wait for SSDP", System.nanoTime() - t0 < 2_000_000_000L)
        repeat(50) { if (rig.discovers.get() == 0) Thread.sleep(20) }
        assertEquals(1, rig.discovers.get())
        rig.gate.countDown()
    }

    @Test
    fun `2 a second ask while scanning starts no second round`() {
        val rig = Rig()
        rig.rooms.refresh()
        val view = rig.rooms.refresh()
        assertTrue(view.scanning)
        assertFalse(rig.rooms.rescanInBackground())
        repeat(50) { if (rig.discovers.get() == 0) Thread.sleep(20) }
        assertEquals(1, rig.discovers.get())
        rig.gate.countDown()
    }

    @Test
    fun `3 the round fills the cache, the plain read serves it, scanning is off`() {
        val rig = Rig()
        rig.rooms.refresh()
        rig.gate.countDown()
        rig.waitForRound()
        val view = rig.rooms.view()
        assertEquals(listOf("Edith", "Kjøkken"), view.rooms.map { it.label })
        assertFalse(view.scanning)
        assertEquals(1400, rig.cache.resolve("RINCON_E")!!.port)
        assertTrue("an SSDP round stamps fetched_at", rig.cache.load().fetchedAt != null)
    }

    @Test
    fun `4 cached rooms that stopped answering - kept, stale, one new round`() {
        val rig = Rig()
        rig.cache.seed(listOf(Player("RINCON_E", "10.9.0.17", 1400, "Edith"), Player("RINCON_K", "10.9.0.21", 1400, "Kjøkken")))
        rig.gate.countDown()
        val view = rig.rooms.refresh()
        assertTrue(view.stale)
        assertEquals("old rows stay", listOf("Edith", "Kjøkken"), view.rooms.map { it.label })
        rig.waitForRound()
        assertEquals(1, rig.discovers.get())
        assertEquals("the round healed the ips", "192.168.1.17", rig.cache.resolve("RINCON_E")!!.ip)
        assertFalse("now the topology answers: not stale", rig.rooms.refresh().stale)
    }

    @Test
    fun `a garbage or missing file is an empty cache, a bad row is dropped`() {
        val file = tmp.root.resolve("sonos.json")
        assertEquals(SpeakerDoc(), SpeakerCache(file).load())
        file.writeText("{not json")
        assertEquals(SpeakerDoc(), SpeakerCache(file).load())
        file.writeText("""{"players": []}""")
        assertEquals(SpeakerDoc(), SpeakerCache(file).load())
        file.writeText("""{"players": {"RINCON_A": {"ip": "10.0.0.1", "name": "Stua", "seen_at": 1.5}, "RINCON_B": {"name": "no ip"}}, "groups": [{"coordinator": "RINCON_A", "members": ["RINCON_A"]}, 7], "topology_at": 2.0}""")
        val doc = SpeakerCache(file).load()
        assertEquals(mapOf("RINCON_A" to CachedPlayer("10.0.0.1", 1400, "Stua", 1.5)), doc.players)
        assertEquals(listOf(CachedGroup("RINCON_A", listOf("RINCON_A"))), doc.groups)
        assertEquals(2.0, doc.topologyAt!!, 0.0)
    }

    @Test
    fun `the file round-trips with vibb's keys and the port, written whole`() {
        val file = tmp.root.resolve("sonos.json")
        val cache = SpeakerCache(file, wallS = { 42.0 })
        cache.seed(listOf(Player("RINCON_FAKE1401", "10.0.2.2", 1401, "Stua")))
        val text = file.readText()
        assertTrue(text, text.contains("\"seen_at\":42.0") && text.contains("\"port\":1401") && text.contains("\"fetched_at\":42.0"))
        assertEquals(CachedPlayer("10.0.2.2", 1401, "Stua", 42.0), SpeakerCache(file).resolve("RINCON_FAKE1401"))
        assertNull(cache.resolve("RINCON_OTHER"))
        assertEquals("no temp files left behind", listOf("sonos.json"), tmp.root.list()!!.toList())
        // The topology carries the speaker's own port (the fake's), so a refresh keeps it.
        cache.applyTopology(Topology.parse(zoneGroupState("zgs_wrapped.xml"))!!)
        assertEquals(1401, cache.resolve("RINCON_FAKE1401")!!.port)
    }

    @Test
    fun `the resolver - the cached ip, the uid compared on the play path only`() {
        TinyHttp { TinyHttp.Response(200, SonosFakes.fixture("device_description.xml")) }.use { server ->
            val cache = SpeakerCache(tmp.root.resolve("sonos.json"))
            cache.seed(listOf(Player("RINCON_STUA01400", "127.0.0.1", server.port, "Stua"), Player("RINCON_MOVED", "127.0.0.1", server.port, "Bad")))
            val speakers = CachedSpeakers(cache, Soap(Soap.client()))
            assertEquals(server.port, speakers.speaker("RINCON_STUA01400", verify = true).port)
            try {
                speakers.speaker("RINCON_MOVED", verify = true)
                org.junit.Assert.fail("the ip now answers with another uid")
            } catch (e: UnknownUid) {
                assertEquals("RINCON_MOVED", e.uid)
            }
            val n = server.requests.size
            speakers.speaker("RINCON_MOVED", verify = false)
            assertEquals("the poll path never asks", n, server.requests.size)
            try {
                speakers.speaker("RINCON_NONE", verify = false)
                org.junit.Assert.fail()
            } catch (_: UnknownUid) {
            }
        }
        val cache = SpeakerCache(tmp.root.resolve("sonos2.json"))
        val closed = TinyHttp { TinyHttp.Response(200, "") }.let { it.close(); it.port }
        cache.seed(listOf(Player("RINCON_DOWN", "127.0.0.1", closed, "Loft")))
        assertEquals("unreachable: left to the poller", "RINCON_DOWN", CachedSpeakers(cache, Soap(Soap.client())).speaker("RINCON_DOWN", verify = true).uid)
    }

    // pi/vibb/renderer.py

    @Test
    fun `renderer state - garbage or not sonos-with-uid is local, written tmp + rename`() {
        val file = tmp.root.resolve("renderer.json")
        assertEquals(RendererState.LOCAL_STATE, RendererState.read(file))
        file.writeText("garbage")
        assertEquals(RendererState.LOCAL_STATE, RendererState.read(file))
        file.writeText("""{"renderer": "sonos"}""")
        assertFalse(RendererState.read(file).sonos)
        val state = RendererState(RendererState.SONOS, "RINCON_A", "Stua", 12, "MKTT81008181", "x-sonos-http:series%3ax%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=14", 25)
        RendererState.write(file, state)
        assertEquals(state, RendererState.read(file))
        assertTrue(file.readText().contains("\"entry_id\":12"))
        RendererState.write(file, RendererState.LOCAL_STATE)
        assertFalse(RendererState.read(file).sonos)
        assertEquals(listOf("renderer.json"), tmp.root.list()!!.toList())
    }
}
