package me.vibb.music.sonos

import me.vibb.music.model.Source
import me.vibb.music.sonos.SonosFakes.entry
import me.vibb.music.sonos.SonosFakes.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * palchrb/vibb `pi/daemon.py` `_sonos_body` and `sonos_start_target`'s filter (MIT, Copyright (c)
 * 2026 palchrb), as design 26a §2.5 and §4.2 port them: the art rule, the NRK series branch with the
 * item key as the program id, the speaker-playable filter (HLS only as an NRK series, the public
 * address rule moved to the speaker's fetch), the listing's duration.
 */
class PlayBodyTest {
    private val nrkSerie = entry(id = 7, source = Source.NRK, target = "https://radio.nrk.no/serie/barnas-supersommer", name = "Barnas supersommer")

    @Test
    fun `an RSS item is a url body - its own url, the entry's name as album, the listing's duration`() {
        val b = PlayBodies.build(entry(), item(), "RINCON_X", 427.0)!!
        assertEquals(PlayBody("RINCON_X", Kind.URL, "https://podkast.example/ep12.mp3", "Sommerfuglen", "Barnas Supersommer", null, 1453.0, 427.0), b)
        assertNull("no listed duration, none sent", PlayBodies.build(entry(), item(durationMs = null), "RINCON_X", 0.0)!!.durationS)
    }

    @Test
    fun `art only when it is http - the phone's cover file can't reach a speaker`() {
        assertEquals("http://10.0.0.5/c.jpg", PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "http://10.0.0.5/c.jpg")!!.art)
        assertEquals("https://img.example/c.jpg", PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "https://img.example/c.jpg")!!.art)
        assertNull(PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "/data/user/0/me.vibb.music/files/music/covers/abc")!!.art)
        assertNull(PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "content://x/y")!!.art)
    }

    @Test
    fun `an NRK series item is nrk_program - the slug from the target, the item key as the program id`() {
        val b = PlayBodies.build(nrkSerie, item(key = "MKTT81008181", url = "https://nrk-od.example/MKTT81008181/master.m3u8", hls = true, entryId = 7), "RINCON_X", 0.0)!!
        assertEquals(Kind.NRK_PROGRAM, b.kind)
        assertEquals("https://radio.nrk.no/serie/barnas-supersommer", b.uri)
        assertEquals("barnas-supersommer", b.series)
        assertEquals("MKTT81008181", b.programId)
        assertEquals("Barnas supersommer", b.album)
    }

    @Test
    fun `the series slug is vibb's regex`() {
        assertEquals("barnas-supersommer", nrkSeriesSlug("https://radio.nrk.no/serie/barnas-supersommer/sesong/1"))
        assertEquals("x_y-1", nrkSeriesSlug("HTTP://RADIO.NRK.NO/serie/x_y-1"))
        assertNull(nrkSeriesSlug("https://radio.nrk.no/podkast/x"))
        assertNull(nrkSeriesSlug("see https://radio.nrk.no/serie/x"))
        assertNull(nrkSeriesSlug(null))
    }

    @Test
    fun `an NRK podcast is a plain url, and its HLS is filtered`() {
        val podkast = entry(id = 8, source = Source.NRK, target = "https://radio.nrk.no/podkast/x")
        assertEquals(Kind.URL, PlayBodies.build(podkast, item(entryId = 8), "RINCON_X", 0.0)!!.kind)
        assertEquals("hls", PlayBodies.speakerDropReason(podkast, item(hls = true)))
    }

    @Test
    fun `the speaker-playable filter`() {
        assertTrue(PlayBodies.speakerPlayable(entry(), item()))
        assertEquals("own file", PlayBodies.speakerDropReason(entry(source = Source.OWN, target = null), item(url = null, fileId = 5)))
        assertEquals("withdrawn", PlayBodies.speakerDropReason(entry(), item(url = null)))
        assertEquals("a non-series HLS item", "hls", PlayBodies.speakerDropReason(entry(), item(hls = true)))
        assertTrue("an NRK series' HLS goes as nrk_program", PlayBodies.speakerPlayable(nrkSerie, item(hls = true, url = "https://nrk-od.example/a.m3u8")))
        assertEquals("not http", PlayBodies.speakerDropReason(entry(), item(url = "ftp://x/y.mp3")))
        assertNull(PlayBodies.build(entry(), item(url = null), "RINCON_X", 0.0))
    }

    @Test
    fun `a public entry's item never from a private literal - a lan entry's may`() {
        for (host in listOf("192.168.1.5", "10.0.0.2", "100.100.1.1", "127.0.0.1", "[fd7a:115c:a1e0::1]", "[::1]")) {
            assertEquals(host, "private address", PlayBodies.speakerDropReason(entry(), item(url = "http://$host/ep.mp3")))
            assertTrue(host, PlayBodies.speakerPlayable(entry(lan = true), item(url = "http://$host/ep.mp3")))
        }
        assertTrue("a public literal", PlayBodies.speakerPlayable(entry(), item(url = "http://93.184.215.14/ep.mp3")))
        assertTrue("a name is never resolved here", PlayBodies.speakerPlayable(entry(), item(url = "http://nas.local/ep.mp3")))
        assertFalse("not an address", PlayBodies.privateLiteral("999.1.1.1"))
        assertFalse(PlayBodies.privateLiteral("podkast.example"))
    }
}
