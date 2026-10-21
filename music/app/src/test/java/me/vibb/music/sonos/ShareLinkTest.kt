package me.vibb.music.sonos

import me.vibb.music.sonos.SonosFakes.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SoCo's ShareLink request, byte for byte (design 54 §0.2, §2.2; SoCo `soco/plugins/sharelink.py`, MIT): each type's
 * `EnqueuedURI` and metadata against the goldens `sharelink_<type>.xml` (`scripts/sonos-play-test.py --golden`; their
 * README says which writer), and the wire - `AddURIToQueue`'s arguments in SoCo's order.
 */
class ShareLinkTest {
    /** The golden inputs (`sonos-play-test.py`'s `GOLDEN_SHARES`): change both together. */
    private val shares = mapOf(
        "playlist" to "spotify:playlist:0FakePlaylistKveld0001",
        "album" to "spotify:album:2FakeAlbumX00000000001",
        "track" to "spotify:track:1FakeTrackH00000000008",
        "show" to "spotify:show:4FakeShow0000000000001",
        "episode" to "spotify:episode:5FakeEpisode0000000003",
    )

    /** A golden's arguments, in order: (name, text). */
    private fun golden(type: String): List<Pair<String, String>> =
        children(parseXml(fixture("sharelink_$type.xml")).documentElement).map { (it.localName ?: it.nodeName) to it.textContent }

    @Test
    fun `d54 every share type's EnqueuedURI and metadata are SoCo's, byte for byte`() {
        for ((type, target) in shares) {
            val args = golden(type).toMap()
            val link = ShareLink.of(target)!!
            assertEquals(type, link.type.wire)
            assertEquals(type, args.getValue("EnqueuedURI"), link.enqueuedUri)
            assertEquals(type, args.getValue("EnqueuedURIMetaData"), link.metadata(2311))
            assertEquals(type, args.getValue("EnqueuedURI"), shareLinkUri(target))
            assertEquals(type, args.getValue("EnqueuedURIMetaData"), shareLinkMetadata(target, 2311))
            // SpotifyUSShare: the same template, service type 3079
            assertEquals(type, args.getValue("EnqueuedURIMetaData").replace("2311", "3079"), link.metadata(3079))
        }
        assertEquals("a track and an episode go without a prefix", "spotify%3atrack%3a1FakeTrackH00000000008", shareLinkUri(shares["track"]))
        assertTrue("dc:title empty - nothing added to SoCo's template", shareLinkMetadata(shares["playlist"], 2311)!!.contains("<dc:title></dc:title>"))
    }

    @Test
    fun `d54 the wire - AddURIToQueue's five arguments in SoCo's order, the metadata escaped once more`() {
        TinyHttp { TinyHttp.Response(200, fixture("add_uri_to_queue.xml")) }.use { server ->
            val spk = SoapSpeaker(Soap(Soap.client()), "127.0.0.1", server.port, "RINCON_STUA01400")
            for ((type, target) in shares) {
                val link = ShareLink.of(target)!!
                assertEquals(AddResult(1, 30, 30), spk.addUriToQueue(link.enqueuedUri, link.metadata(2311)))
                val req = server.requests.last()
                assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#AddURIToQueue\"", req.headers["soapaction"])
                val call = children(children(parseXml(req.body).documentElement).first { it.localName == "Body" }).first()
                assertEquals(type, golden(type), children(call).map { it.localName to it.textContent })
            }
        }
    }

    @Test
    fun `d54 SoCo's pattern - a share link reads as its canonical uri, an artist and junk are refused`() {
        val canonical = ShareLink.of("https://open.spotify.com/playlist/0FakePlaylistKveld0001?si=FAKEsi0001")!!
        assertEquals(ShareLink(ShareType.PLAYLIST, "spotify:playlist:0FakePlaylistKveld0001"), canonical)
        assertEquals("spotify%3aplaylist%3a0FakePlaylistKveld0001", canonical.encoded)
        assertNull("no artist share type (SoCo raises; vibb never played one on a room)", ShareLink.of("spotify:artist:3FakeArtist00000000001"))
        assertNull(ShareLink.of("https://open.spotify.com/user/fakeparent"))
        assertNull(ShareLink.of("tidal:album:1"))
        assertNull(ShareLink.of(null))
        assertNull(shareLinkUri(""))
        assertEquals("never the id in a line", "ShareLink(playlist)", canonical.toString())
    }
}
