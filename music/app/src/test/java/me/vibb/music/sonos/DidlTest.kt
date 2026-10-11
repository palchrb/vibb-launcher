package me.vibb.music.sonos

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The DIDL-Lite of palchrb/vibb `pi/sonosd.py` `didl()` (MIT, Copyright (c) 2026 palchrb), byte for
 * byte: each expected string below is what vibb's own `didl()` printed for the same arguments
 * (`tests/sonos_contract.py`'s PLAY_URL and PLAY_NRK bodies), so the port can't drift.
 */
class DidlTest {
    private val ns = "xmlns=\"urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" " +
        "xmlns:upnp=\"urn:schemas-upnp-org:metadata-1-0/upnp/\" xmlns:r=\"urn:schemas-rinconnetworks-com:metadata-1-0/\""

    @Test
    fun `PLAY_URL - both artist tags and the duration attribute`() {
        assertEquals(
            "<DIDL-Lite $ns><item id=\"vibb-1\" parentID=\"-1\" restricted=\"true\"><dc:title>Sommerfuglen</dc:title>" +
                "<dc:creator>Barnas Supersommer</dc:creator><upnp:artist>Barnas Supersommer</upnp:artist>" +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"http-get:*:audio/mpeg:*\" duration=\"0:24:13\">https://podkast.example/ep12.mp3</res></item></DIDL-Lite>",
            didl("https://podkast.example/ep12.mp3", "Sommerfuglen", "Barnas Supersommer", null, null, 1453.0),
        )
    }

    @Test
    fun `PLAY_NRK - the service uri, the show class, the cdudn desc, the service protocol`() {
        val uri = NrkService.programUri("x", "MKTT81008181", "14")
        assertEquals("x-sonos-http:series%3ax%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=14", uri)
        assertEquals(
            "<DIDL-Lite $ns><item id=\"vibb-1\" parentID=\"-1\" restricted=\"true\"><dc:title>Kap. 3</dc:title>" +
                "<upnp:class>object.item.audioItem.show</upnp:class>" +
                "<desc id=\"cdudn\" nameSpace=\"urn:schemas-rinconnetworks-com:metadata-1-0/\">SA_RINCON70919_X_#Svc70919-0-Token</desc>" +
                "<res protocolInfo=\"sonos.com-http:*:audio/mpeg:*\">x-sonos-http:series%3ax%3a1%3aMKTT81008181.unknown?sid=277&amp;flags=0&amp;sn=14</res>" +
                "</item></DIDL-Lite>",
            didl(uri, "Kap. 3", upnpClass = NrkService.UPNP_CLASS, protocol = NrkService.PROTOCOL, desc = NrkService.DESC),
        )
    }

    @Test
    fun `values are escaped once here (amp, lt, gt - quotes stay), album and art, the mime by extension`() {
        assertEquals(
            "<DIDL-Lite $ns><item id=\"vibb-1\" parentID=\"-1\" restricted=\"true\"><dc:title>Ole &amp; Dole &lt;3&gt;</dc:title>" +
                "<dc:creator>A&amp;B</dc:creator><upnp:artist>A&amp;B</upnp:artist><upnp:album>Album \"Q\"</upnp:album>" +
                "<upnp:albumArtURI>http://10.0.0.5/c.jpg?a=1&amp;b=2</upnp:albumArtURI><upnp:class>object.item.audioItem.musicTrack</upnp:class>" +
                "<res protocolInfo=\"http-get:*:audio/mp4:*\" duration=\"0:00:59\">https://cdn.example/a.m4b?x=1&amp;y=2</res></item></DIDL-Lite>",
            didl("https://cdn.example/a.m4b?x=1&y=2", "Ole & Dole <3>", "A&B", "Album \"Q\"", "http://10.0.0.5/c.jpg?a=1&b=2", 59.9),
        )
    }

    @Test
    fun `and once more in the SOAP envelope - the amp-amp is correct`() {
        val meta = didl("https://cdn.example/a.m4b?x=1&y=2", "Ole & Dole <3>")
        val envelope = Soap.envelope(SonosService.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURIMetaData" to meta))
        assertEquals(1, Regex("&lt;dc:title&gt;Ole &amp;amp; Dole &amp;lt;3&amp;gt;&lt;/dc:title&gt;").findAll(envelope).count())
    }

    @Test
    fun `empty strings are absent - the default title, no artist, album, art or duration`() {
        assertEquals(
            "<DIDL-Lite $ns><item id=\"vibb-1\" parentID=\"-1\" restricted=\"true\"><dc:title>Vibb</dc:title>" +
                "<upnp:class>object.item.audioItem.musicTrack</upnp:class><res protocolInfo=\"http-get:*:audio/mpeg:*\">http://h/x</res></item></DIDL-Lite>",
            didl("http://h/x", "", "", "", "", 0.0),
        )
    }

    @Test
    fun `the household serial - looked up once per ip, the fallback 14 not cached`() {
        var calls = 0
        var fail = true
        val nrk = NrkService({ _, _ ->
            calls++
            if (fail) throw SpeakerUnreachable("down")
            SonosFakes.fixture("accounts.xml")
        })
        assertEquals("14", nrk.serial("10.0.0.11", 1400))
        fail = false
        assertEquals("7", nrk.serial("10.0.0.11", 1400))
        assertEquals("7", nrk.serial("10.0.0.11", 1400))
        assertEquals(2, calls)
        assertEquals("x-sonos-http:series%3abarnas-supersommer%3a1%3aMKTT81008181.unknown?sid=277&flags=0&sn=7", nrk.programUri("10.0.0.11", 1400, "barnas-supersommer", "MKTT81008181"))
    }
}
