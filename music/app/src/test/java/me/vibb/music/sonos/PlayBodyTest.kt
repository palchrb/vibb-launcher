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
 * address rule moved to the speaker's fetch), a DIDL duration only for Storytel (design 53 §2.1, against
 * the goldens `scripts/sonos-play-test.py --golden` writes); and the Storytel branch
 * (design 26b §5.1, vibb `tests/storytel_sonos.py` 2, 6, 9): a book minted in the play's job.
 */
class PlayBodyTest {
    private val nrkSerie = entry(id = 7, source = Source.NRK, target = "https://radio.nrk.no/serie/barnas-supersommer", name = "Barnas supersommer")

    @Test
    fun `an RSS item is a url body - its own url, the entry's name as album, no DIDL duration, the listing's as the hint`() {
        val b = PlayBodies.build(entry(), item(), "RINCON_X", 427.0)!!
        assertEquals(PlayBody("RINCON_X", Kind.URL, "https://podkast.example/ep12.mp3", "Sommerfuglen", "Barnas Supersommer", null, null, 427.0, cadenceHintS = 1453.0), b)
        assertNull("no listed duration, no hint", PlayBodies.build(entry(), item(durationMs = null), "RINCON_X", 0.0)!!.cadenceHintS)
    }

    // -- design 53 §2.1: vibb's DIDL field for field ("Ikke endre noe i didl!!"), against the generated goldens --

    /** `scripts/sonos-play-test.py --golden` wrote it from vibb's per-kind arguments (the same fixed inputs). */
    private fun golden(name: String) = SonosFakes.fixture(name)

    @Test
    fun `d53 the url DIDL is vibb's - title and album, no duration, no art`() {
        val b = PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = null)!!
        assertEquals(golden("didl_url.xml"), builtDidl(b, b.uri))
    }

    @Test
    fun `d53 the NRK series DIDL is vibb's - the service uri, class, protocol and desc, no duration`() {
        val b = PlayBodies.build(nrkSerie, item(key = "MKTT81008181", url = "https://nrk-od.example/MKTT81008181/master.m3u8", hls = true, entryId = 7), "RINCON_X", 0.0)!!
        assertNull(b.durationS)
        assertEquals(1453.0, b.cadenceHintS!!, 1e-9)
        assertEquals(golden("didl_nrk.xml"), builtDidl(b, NrkService.programUri(b.series!!, b.programId!!, NrkService.FALLBACK_SERIAL)))
    }

    @Test
    fun `d53 the Storytel DIDL is vibb's - the minted URL and the shelf's duration, no art`() {
        val minted = "https://cdn.example.invalid/book?token=FAKE-SIG-1"
        val b = PlayBodies.build(series, book("111"), "RINCON_X", 0.0, art = "https://covers.example.invalid/111.jpg")!!.copy(uri = minted, mint = null)
        assertEquals(golden("didl_storytel.xml"), builtDidl(b, b.uri))
    }

    @Test
    fun `d53 a hash is never an albumArtURI, and the cadence hint never reaches the DIDL`() {
        val hash = "940b951005008e7207e7f8b8807ac39691025858882b8c67843a29b65e388187"
        val b = PlayBodies.build(entry(), item().copy(artHash = hash), "RINCON_X", 0.0, art = hash)!!
        val built = builtDidl(b.copy(cadenceHintS = 99_999.0), b.uri)
        assertFalse(built.contains("albumArtURI"))
        assertFalse(built.contains("duration="))
    }

    @Test
    fun `art only when it is http - the phone's cover file can't reach a speaker`() {
        assertEquals("http://10.0.0.5/c.jpg", PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "http://10.0.0.5/c.jpg")!!.art)
        assertEquals("https://img.example/c.jpg", PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "https://img.example/c.jpg")!!.art)
        assertNull(PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "/data/user/0/me.vibb.music/files/music/covers/abc")!!.art)
        assertNull(PlayBodies.build(entry(), item(), "RINCON_X", 0.0, art = "content://x/y")!!.art)
    }

    @Test
    fun `design 27's episode art stays off the speaker - a hash is no URL, and nothing serves it on the LAN`() {
        // The listing's art is a 64-hex hash since design 27 (server/testdata/music_listing.json); the
        // server serves it only to the phone's bearer (GET /api/devices/music/covers/{hash}), and the
        // phone's copy is a file - no http URL a speaker can fetch (the merge of design 27, design 26a).
        val hash = "940b951005008e7207e7f8b8807ac39691025858882b8c67843a29b65e388187"
        assertNull(PlayBodies.build(entry(), item().copy(artHash = hash), "RINCON_X", 0.0)!!.art)
        assertNull(PlayBodies.build(entry(), item().copy(artHash = hash), "RINCON_X", 0.0, art = hash)!!.art)
        val controller = me.vibb.music.Fixtures.moduleFile("src/main/java/me/vibb/music/sonos/SonosController.kt").readText()
        assertTrue("the controller's body carries no art", controller.contains("PlayBodies.build(e, item, uid, startS, art = null)"))
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
        assertEquals("not an http url", PlayBodies.speakerDropReason(entry(), item(url = "ftp://x/y.mp3")))
        assertNull(PlayBodies.build(entry(), item(url = null), "RINCON_X", 0.0))
    }

    @Test
    fun `a public entry's item never from a private literal - a lan entry's may, but not from the tailnet`() {
        for (host in listOf("192.168.1.5", "10.0.0.2", "100.100.1.1", "127.0.0.1", "[fd7a:115c:a1e0::1]", "[::1]")) {
            assertEquals(host, "private address", PlayBodies.speakerDropReason(entry(), item(url = "http://$host/ep.mp3")))
        }
        for (host in listOf("192.168.1.5", "10.0.0.2", "172.16.4.4", "[fd00::5]", "nas.tailnet.ts.net")) {
            assertTrue(host, PlayBodies.speakerPlayable(entry(lan = true), item(url = "http://$host/ep.mp3")))
        }
        // a speaker on the LAN never reaches the tailnet the phone (and the server) can (21b's lan covers both)
        for (host in listOf("100.64.0.1", "100.100.1.1", "100.127.255.254", "[fd7a:115c:a1e0::1]", "[fd7a:115c:a1e0:ab12::7]")) {
            assertEquals(host, "tailnet address", PlayBodies.speakerDropReason(entry(lan = true), item(url = "http://$host/ep.mp3")))
        }
        for (host in listOf("100.63.255.255", "100.128.0.1", "[fd7a:115c:a1e1::1]")) {
            assertFalse(host, PlayBodies.tailnetLiteral(host))
        }
        assertTrue("a public literal", PlayBodies.speakerPlayable(entry(), item(url = "http://93.184.215.14/ep.mp3")))
        assertTrue("a name is never resolved here", PlayBodies.speakerPlayable(entry(), item(url = "http://nas.local/ep.mp3")))
        assertFalse("not an address", PlayBodies.privateLiteral("999.1.1.1"))
        assertFalse(PlayBodies.privateLiteral("podkast.example"))
    }

    @Test
    fun `L6 - the URL as OkHttp reads it - an unencoded space or pipe still plays, a key-less series item drops`() {
        assertTrue(PlayBodies.speakerPlayable(entry(), item(url = "https://cdn.example/Episode 12|final.mp3")))
        assertEquals("https://cdn.example/Episode 12|final.mp3", PlayBodies.build(entry(), item(url = "https://cdn.example/Episode 12|final.mp3"), "RINCON_X", 0.0)!!.uri)
        val keyless = item(key = "", url = "https://nrk-od.example/a.m3u8", hls = true, entryId = 7)
        assertEquals("no program id", PlayBodies.speakerDropReason(nrkSerie, keyless))
        assertNull(PlayBodies.build(nrkSerie, keyless, "RINCON_X", 0.0))
        assertFalse("numeric shorthand counts as a name (accepted, see privateLiteral)", PlayBodies.privateLiteral("10.1"))
        assertTrue(PlayBodies.privateLiteral("fd7a:115c:a1e0::1"))
    }

    @Test
    fun `the plan follows the filter - the start item kept at its place, else the next kept one from 0, nothing left is null`() {
        val plan = me.vibb.music.logic.QueuePlan(listOf("a", "b", "c", "d"), 1, 42_000)
        assertEquals(me.vibb.music.logic.QueuePlan(listOf("a", "b", "d"), 1, 42_000), PlayBodies.speakerPlan(plan) { it != "c" })
        assertEquals("b dropped: c from 0", me.vibb.music.logic.QueuePlan(listOf("a", "c", "d"), 1, 0), PlayBodies.speakerPlan(plan) { it != "b" })
        assertEquals("wraps", me.vibb.music.logic.QueuePlan(listOf("a"), 0, 0), PlayBodies.speakerPlan(plan) { it == "a" })
        assertNull(PlayBodies.speakerPlan(plan) { false })
    }

    // -- Storytel (design 26b §5.1; vibb tests/storytel_sonos.py) --

    private val series = entry(id = 9, source = Source.STORYTEL, target = "storytel:series:26175", name = "Kokosbananas")

    /** A book as the listing has it: no url (a signed URL expires), the shelf's length. */
    private fun book(cid: String, durationMs: Long? = 721_893) = item(key = cid, url = null, durationMs = durationMs, entryId = 9)

    @Test
    fun `storytel_sonos 6 - the http-only filter lets a Storytel book through, downloaded or not`() {
        // vibb's exemption (daemon.py 1693-1699): without it every book is dropped before the speaker
        assertNull(PlayBodies.speakerDropReason(series, book("111")))
        assertTrue(PlayBodies.speakerPlayable(series, book("222")))
        // ... and only Storytel: an RSS item without a url is still withdrawn
        assertEquals("withdrawn", PlayBodies.speakerDropReason(entry(), item(url = null)))
        val plan = me.vibb.music.logic.QueuePlan(listOf("111", "222", "333"), 1, 60_000)
        assertEquals("the whole series on a room, at the planned book and place", plan, PlayBodies.speakerPlan(plan) { PlayBodies.speakerPlayable(series, book(it)) })
    }

    @Test
    fun `storytel_sonos 2 and 9 - a book's body is minted in the play job - the known length rides, no art`() {
        val b = PlayBodies.build(series, book("111"), "RINCON_X", 0.0, art = "https://covers.example.invalid/111.jpg")!!
        assertEquals(Kind.URL, b.kind)
        assertEquals("no URL yet: the job mints it right before SetAVTransportURI", "", b.uri)
        assertEquals("111", b.mint)
        assertEquals("the shelf's length (a speaker reads 0:00 for an extension-less signed URL)", 721.893, b.durationS!!, 1e-9)
        assertEquals("and the session's tail hint", 721.893, b.cadenceHintS!!, 1e-9)
        assertNull("no art to the speaker (design 26a §9: a hash has no LAN URL)", b.art)
        assertEquals("Kokosbananas", b.album)
        assertNull("no length listed, none sent", PlayBodies.build(series, book("222", durationMs = null), "RINCON_X", 0.0)!!.durationS)
        assertNull("every other source has nothing to mint", PlayBodies.build(entry(), item(), "RINCON_X", 0.0)!!.mint)
        // renderer.json's uri for it: the marker, never the signed URL (§5.3)
        assertEquals("storytel:book:111", me.vibb.music.logic.storedUri(series, "111", "https://cdn.example.invalid/a?consumableId=111&token=FAKE-SIG-1"))
    }

    @Test
    fun `the minted URL gets the listed url's address checks - one helper - a private literal only with the debug switch`() {
        val cdn = "https://cdn.example.invalid/mp3/111/abook.mp3?consumableId=111&token=FAKE-SIG-1"
        assertNull(PlayBodies.mintedDropReason(cdn, privateAllowed = false))
        assertNull("a public literal", PlayBodies.mintedDropReason("http://93.184.215.14/a?consumableId=111&token=FAKE-SIG-1", false))
        val fake = "http://10.0.2.2:8730/cdn/111.wav?consumableId=111&token=FAKE-SIG-1"
        assertEquals("private address", PlayBodies.mintedDropReason(fake, privateAllowed = false))
        assertNull("the emulator's fake Storytel (design 26b §4.6 (d))", PlayBodies.mintedDropReason(fake, privateAllowed = true))
        for (host in listOf("100.64.0.1", "[fd7a:115c:a1e0::1]")) {
            assertEquals(host, "tailnet address", PlayBodies.mintedDropReason("http://$host/a?consumableId=1", privateAllowed = true))
        }
        assertEquals("not an http url", PlayBodies.mintedDropReason("ftp://cdn.example.invalid/a", false))
        assertEquals("not an http url", PlayBodies.mintedDropReason("", false))
        // the same verdicts as the listed url's filter for a public entry (one helper, the merge's 4b list)
        for (url in listOf("http://192.168.1.5/ep.mp3", "http://100.100.1.1/ep.mp3", "https://podkast.example/ep.mp3")) {
            assertEquals(url, PlayBodies.speakerDropReason(entry(), item(url = url)), PlayBodies.mintedDropReason(url, privateAllowed = false))
        }
    }

    @Test
    fun `a body, a result and a snapshot never print a signed URL's token`() {
        val signed = "https://cdn.example.invalid/mp3/111/abook.mp3?consumableId=111&token=FAKE-SIG-7"
        val body = PlayBodies.build(series, book("111"), "RINCON_X", 0.0)!!.copy(uri = signed, mint = null)
        assertFalse(body.toString(), body.toString().contains("FAKE-SIG"))
        assertTrue(body.toString().contains("cdn.example.invalid/mp3/111/abook.mp3"))
        val result = PlayResult("RINCON_X", signed, true, didl(signed, "En", null, "Kokosbananas", null, 721.9))
        assertFalse(result.toString(), result.toString().contains("FAKE-SIG"))
        val fields = Fields(armed = true, uid = "RINCON_X", kind = Kind.URL, seq = 1, measuredAt = 1L, uri = signed, foreignUri = signed, streamMoved = Moved("RINCON_Y", "Stua", signed))
        assertFalse(fields.toString(), fields.toString().contains("FAKE-SIG"))
        assertTrue(fields.toString().contains("RINCON_Y"))
    }
}
