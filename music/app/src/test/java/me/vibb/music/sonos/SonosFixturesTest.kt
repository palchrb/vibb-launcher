package me.vibb.music.sonos

import me.vibb.music.sonos.SonosFakes.fixture
import me.vibb.music.sonos.SonosFakes.position
import me.vibb.music.sonos.SonosFakes.reply
import me.vibb.music.sonos.SonosFakes.transport
import me.vibb.music.sonos.SonosFakes.zoneGroupState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The SOAP boundary, pinned (design 26a §7): every canned reply in src/test/resources/sonos/ parses
 * to what the session expects, and scripts/fake-sonos.py serves the same files - vibb's "two
 * fakes" guard (palchrb/vibb `tests/sonos_contract.py`, MIT, Copyright (c) 2026 palchrb): neither fake
 * can drift into agreeing with itself. Plus the client's wire format against a loopback server.
 */
class SonosFixturesTest {
    private val ours = "https://podkast.example/ep12.mp3"

    @Test
    fun `every fixture file is covered here`() {
        val checked = setOf(
            "position_playing_ours.xml", "position_paused.xml", "position_stopped_empty.xml", "position_stopped_retained.xml",
            "position_x_rincon.xml", "position_foreign.xml", "position_transitioning.xml", "position_not_implemented.xml",
            "position_nrk.xml", "transport_playing.xml", "transport_paused.xml", "transport_stopped.xml",
            "transport_transitioning.xml", "zgs_wrapped.xml", "zgs_bare.xml", "fault_701.xml", "fault_710.xml",
            "fault_711.xml", "fault_500.xml", "device_description.xml", "accounts.xml", "ssdp_sonos.txt", "ssdp_other.txt",
            "get_volume.xml", "get_mute.xml", "empty_response.xml",
        )
        assertEquals(checked, SonosFakes.fixtureNames().toSet())
    }

    @Test
    fun `GetPositionInfo fixtures`() {
        // (file, TrackURI, RelTime s, TrackDuration s, normUri == ours)
        val cases = listOf(
            listOf("position_playing_ours.xml", ours, 431.0, 1453.0, true),
            listOf("position_paused.xml", ours, 720.0, 1453.0, true),
            listOf("position_stopped_empty.xml", "", 0.0, 0.0, false),
            listOf("position_stopped_retained.xml", ours, 0.0, 1453.0, true),
            listOf("position_x_rincon.xml", "x-rincon:RINCON_PARENT01400", 1234.0, 240.0, false),
            listOf("position_foreign.xml", "x-sonos-spotify:spotify%3atrack%3aGUEST?sid=9&flags=8224&sn=1", 65.0, 200.0, false),
            listOf("position_transitioning.xml", ours, 0.0, 0.0, true),
            listOf("position_not_implemented.xml", "x-rincon-mp3radio://podkast.example/ep12.mp3", null, 0.0, true),
            listOf("position_nrk.xml", "x-sonos-http:series%3abarnas-supersommer%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=14", 750.0, 1680.0, false),
        )
        for ((file, uri, rel, dur, isOurs) in cases) {
            val p = position(file as String)
            assertEquals(file, uri, p.trackUri)
            assertEquals(file, rel, hmsToS(p.relTime))
            assertEquals(file, dur, hmsToS(p.trackDuration))
            assertEquals(file, isOurs, normUri(p.trackUri) == normUri(ours))
        }
        // The echoed DIDL comes back unescaped once (the speaker's TrackMetaData is itself XML).
        assertTrue(position("position_playing_ours.xml").trackMetaData!!.startsWith("<DIDL-Lite "))
        assertTrue(position("position_foreign.xml").trackMetaData!!.contains("<dc:title>Shout &amp; Out</dc:title>"))
        assertEquals("NOT_IMPLEMENTED", position("position_not_implemented.xml").trackMetaData)
    }

    @Test
    fun `GetTransportInfo fixtures map onto the closed set`() {
        assertEquals(TransportState.PLAYING, TransportState.parse(transport("transport_playing.xml")))
        assertEquals(TransportState.PAUSED_PLAYBACK, TransportState.parse(transport("transport_paused.xml")))
        assertEquals(TransportState.STOPPED, TransportState.parse(transport("transport_stopped.xml")))
        assertEquals(TransportState.TRANSITIONING, TransportState.parse(transport("transport_transitioning.xml")))
        assertEquals("an empty state is STOPPED (vibb's `or \"STOPPED\"`)", TransportState.STOPPED, TransportState.parse(""))
        assertEquals("anything else lands in the conservative branch", TransportState.UNKNOWN, TransportState.parse("NO_MEDIA_PRESENT"))
        assertEquals("UNREACHABLE is ours to say, never the speaker's", TransportState.UNKNOWN, TransportState.parse("UNREACHABLE"))
    }

    @Test
    fun `ZoneGroupState in both wrapper forms`() {
        val wrapped = Topology.parse(zoneGroupState("zgs_wrapped.xml"))!!
        assertEquals(setOf("RINCON_STUA01400", "RINCON_KJOKKEN01400", "RINCON_BAD01400"), wrapped.players.keys)
        assertEquals(2, wrapped.groups.size)
        val bare = Topology.parse(zoneGroupState("zgs_bare.xml"))!!
        assertEquals(4, bare.players.size)
        assertEquals(Group("RINCON_STUA01400", listOf("RINCON_STUA01400", "RINCON_KJOKKEN01400", "RINCON_BAD01400")), bare.groups[0])
    }

    @Test
    fun `faults carry their UPnP code`() {
        for (code in listOf(701, 710, 711, 500)) {
            try {
                Soap.parseResponse(500, fixture("fault_$code.xml"), "Seek")
                fail("fault_$code must throw")
            } catch (e: UpnpFault) {
                assertEquals(code, e.code)
            }
        }
        try {
            Soap.parseResponse(500, fixture("fault_500.xml"), "Seek")
        } catch (e: UpnpFault) {
            assertEquals("Internal error", e.description)
        }
    }

    @Test
    fun `device description, accounts, volume, mute and an empty answer`() {
        assertEquals("RINCON_STUA01400", Soap.parseUdn(fixture("device_description.xml")))
        assertEquals("7", NrkService.parseSerial(fixture("accounts.xml")))
        assertEquals("31", reply("get_volume.xml", "GetVolume")["CurrentVolume"])
        assertEquals("0", reply("get_mute.xml", "GetMute")["CurrentMute"])
        assertEquals(emptyMap<String, String>(), reply("empty_response.xml", "Play"))
    }

    @Test
    fun `SSDP replies`() {
        assertEquals(SsdpHit("10.0.0.11", 1400), Ssdp.parseReply(fixture("ssdp_sonos.txt")))
        assertNull(Ssdp.parseReply(fixture("ssdp_other.txt")))
    }

    // The client on the wire (sonosd.py:319-323: each argument escaped once by the SOAP layer).

    @Test
    fun `a call posts the envelope with SOAPACTION and returns the response's fields`() {
        TinyHttp { TinyHttp.Response(200, fixture("position_playing_ours.xml")) }.use { server ->
            val soap = Soap(Soap.client())
            val out = soap.call("127.0.0.1", server.port, SonosService.AV_TRANSPORT, "GetPositionInfo", listOf("InstanceID" to "0"))
            assertEquals(ours, out["TrackURI"])
            val r = server.requests.single()
            assertEquals("POST", r.method)
            assertEquals("/MediaRenderer/AVTransport/Control", r.path)
            assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#GetPositionInfo\"", r.headers["soapaction"])
            assertTrue(r.headers["content-type"]!!.startsWith("text/xml"))
            assertTrue(r.body.contains("<u:GetPositionInfo xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><InstanceID>0</InstanceID></u:GetPositionInfo>"))
        }
    }

    @Test
    fun `a DIDL argument goes out escaped once more (the amp-amp on the wire is correct)`() {
        TinyHttp { TinyHttp.Response(200, fixture("empty_response.xml")) }.use { server ->
            val meta = didl("https://cdn.example/a.mp3?x=1&y=2", "Ole & Dole")
            SoapSpeaker(Soap(Soap.client()), "127.0.0.1", server.port, "RINCON_X").setAvTransportUri("https://cdn.example/a.mp3?x=1&y=2", meta)
            val body = server.requests.single().body
            assertTrue(body, body.contains("<CurrentURI>https://cdn.example/a.mp3?x=1&amp;y=2</CurrentURI>"))
            assertTrue(body, body.contains("&lt;dc:title&gt;Ole &amp;amp; Dole&lt;/dc:title&gt;"))
            assertTrue(body, body.contains("a.mp3?x=1&amp;amp;y=2&lt;/res&gt;"))
        }
    }

    @Test
    fun `an HTTP 500 fault is UpnpFault, HTML is unreachable, a closed port is unreachable`() {
        TinyHttp { TinyHttp.Response(500, fixture("fault_701.xml")) }.use { server ->
            try {
                SoapSpeaker(Soap(Soap.client()), "127.0.0.1", server.port, "RINCON_X").seek("0:02:00")
                fail()
            } catch (e: UpnpFault) {
                assertEquals(701, e.code)
            }
        }
        TinyHttp { TinyHttp.Response(200, "<html><body>captive portal</body>", "text/html") }.use { server ->
            try {
                SoapSpeaker(Soap(Soap.client()), "127.0.0.1", server.port, "RINCON_X").transportInfo()
                fail()
            } catch (_: SpeakerUnreachable) {
            }
        }
        val closed = TinyHttp { TinyHttp.Response(200, "") }.let { it.close(); it.port }
        try {
            Soap(Soap.client()).call("127.0.0.1", closed, SonosService.AV_TRANSPORT, "Play", emptyList())
            fail()
        } catch (_: SpeakerUnreachable) {
        }
    }

    @Test
    fun `the speaker's views over SOAP - group, volume, mute, uid`() {
        TinyHttp { r ->
            val body = when {
                r.path == "/xml/device_description.xml" -> fixture("device_description.xml")
                r.body.contains("GetZoneGroupState") -> fixture("zgs_wrapped.xml")
                r.body.contains("GetVolume") -> fixture("get_volume.xml")
                r.body.contains("GetMute") -> fixture("get_mute.xml")
                else -> fixture("empty_response.xml")
            }
            TinyHttp.Response(200, body)
        }.use { server ->
            val soap = Soap(Soap.client())
            val stua = SoapSpeaker(soap, "127.0.0.1", server.port, "RINCON_STUA01400")
            assertEquals("Stua is a member of Kjøkken's group", "RINCON_KJOKKEN01400", stua.groupCoordinator())
            assertEquals(31, stua.volume())
            assertEquals(false, stua.mute())
            assertEquals("RINCON_STUA01400", soap.uidOf("127.0.0.1", server.port))
            stua.setMute(true)
            assertTrue(server.requests.last().body.contains("<DesiredMute>1</DesiredMute>"))
        }
    }
}
