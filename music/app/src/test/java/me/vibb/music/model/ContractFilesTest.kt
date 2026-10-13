package me.vibb.music.model

import me.vibb.music.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared contract (design 21 §1.3, 21b §4.5): server/testdata/music_library.json,
 * music_listing.json and music_icons.json are byte-identical in src/test/resources, and the app
 * parses them. A bad element is dropped, not the document (21 §4.6).
 */
class ContractFilesTest {
    private fun server(name: String): File {
        // Gradle runs unit tests in music/app/.
        val f = listOf(File("../../server/testdata/$name"), File("../server/testdata/$name")).first { it.isFile }
        assertTrue("${f.absolutePath} not found", f.isFile)
        return f
    }

    @Test
    fun `the shared files are identical to the server's copies`() {
        for (name in listOf("music_library.json", "music_listing.json", "music_icons.json", "spotify_hosts.json")) {
            assertEquals("$name differs from the server's copy", server(name).readText(), Fixtures.resource(name))
        }
    }

    @Test
    fun `the library snapshot parses`() {
        val lib = LibraryParser.parse(Fixtures.resource("music_library.json"))!!
        assertEquals("ceca94f70db5e7a0", lib.version)
        assertEquals("the parent's room scope (design 26a §5.5)", listOf("RINCON_000E58A0B1C201400"), lib.sonosRooms)
        assertTrue("the snapshot's feeds are public", lib.entries.none { it.lan })
        assertEquals(listOf(1L, 3L, 4L), lib.categories.map { it.id })
        assertEquals(listOf("music_note", "menu_book", "mic"), lib.categories.map { it.icon })
        assertEquals(listOf(Source.NRK, Source.RSS, Source.OWN, Source.STORYTEL, Source.SPOTIFY, Source.SPOTIFY), lib.entries.map { it.source })
        assertEquals(setOf(1L, 2L, 3L, 5L, 6L, 7L), lib.entryIds)
        assertEquals("only Spotify entries have a mode", listOf(null, null, null, null, "list", "context"), lib.entries.map { it.spotifyMode })
        val nrk = lib.entry(1)!!
        assertEquals("abels_taarn", nrk.key)
        assertNull("never listed", nrk.items)
        assertFalse(nrk.nrkSerie)
        assertEquals(5, nrk.cache)
        val rss = lib.entry(2)!!
        assertEquals("0123456789abcdef", rss.items)
        assertEquals("newest_first", rss.order)
        val own = lib.entry(3)!!
        assertNull(own.items)
        assertFalse(own.resume)
        assertEquals(-1, own.cache)
        val files = lib.filesOf(3)
        assertEquals(listOf(1L, 2L), files.map { it.id })
        assertEquals("Hjulene på bussen", files[0].title)
        assertEquals(125_000L, files[0].durationMs)
        assertTrue(files[1].missing)
        // The Storytel series (design 26b §2.7): listed by the server, download-only, resume on.
        val storytel = lib.entry(5)!!
        assertEquals("storytel:series:26175", storytel.target)
        assertEquals("00ff00ff00ff00ff", storytel.items)
        assertEquals(-1, storytel.cache)
        assertTrue(storytel.resume)
        assertFalse(storytel.lan)
        assertEquals("d".repeat(64), storytel.cover)
        assertEquals(4, lib.covers.size)
        // The Spotify entries (design 26c §4.1, §8 item 3): a playlist in list mode, an artist a
        // context; streamed (cache 0), resume on, never on the LAN, their listings' versions.
        val playlist = lib.entry(6)!!
        assertEquals("spotify:playlist:FAKEplaylist0000000001", playlist.target)
        assertEquals("list", playlist.spotifyMode)
        assertEquals("11ee11ee11ee11ee", playlist.items)
        val artist = lib.entry(7)!!
        assertEquals("spotify:artist:FAKEartist000000000001", artist.target)
        assertEquals("context", artist.spotifyMode)
        assertEquals("22dd22dd22dd22dd", artist.items)
        for (spotify in listOf(playlist, artist)) {
            assertEquals(0, spotify.cache)
            assertTrue(spotify.resume)
            assertFalse(spotify.lan)
            assertEquals("auto", spotify.order)
            assertNull(spotify.cover)
        }
    }

    @Test
    fun `a Spotify entry needs its canonical target and its mode, else it is hidden with its id kept (26c §4_1)`() {
        val base = Fixtures.resource("music_library.json")
        fun parsed(text: String) = LibraryParser.parse(text)!!
        val list = "\"spotify\": \"list\""
        assertTrue(base.contains(",\n      $list"))
        val noMode = parsed(base.replace(",\n      $list", ""))
        assertNull("no mode", noMode.entry(6))
        assertTrue(6L in noMode.entryIds)
        assertEquals("the other one stays", "context", noMode.entry(7)!!.spotifyMode)
        assertNull("another mode", parsed(base.replace(list, "\"spotify\": \"shuffle\"")).entry(6))
        assertNull("not text", parsed(base.replace(list, "\"spotify\": 1")).entry(6))
        for (bad in listOf("spotify:audiobook:FAKEplaylist0000000001", "spotify:playlist:FAKE-playlist", "https://open.spotify.com/playlist/FAKEplaylist0000000001")) {
            val lib = parsed(base.replace("spotify:playlist:FAKEplaylist0000000001", bad))
            assertNull(bad, lib.entry(6))
            assertTrue(bad, 6L in lib.entryIds)
        }
        // Streamed, whatever is sent: a cache becomes 0, a lan flag is never read.
        val lanFalse = "\"lan\": false,\n      $list"
        assertTrue(base.contains(lanFalse))
        val sent = parsed(base.replace("\"cache\": 0", "\"cache\": 5").replace(lanFalse, "\"lan\": true,\n      $list"))
        assertEquals(0, sent.entry(6)!!.cache)
        assertFalse(sent.entry(6)!!.lan)
        // The field on another source is no mode.
        assertNull(parsed(base.replace("\"source\": \"rss\",", "\"source\": \"rss\", $list,")).entry(2)!!.spotifyMode)
    }

    @Test
    fun `the listing snapshot parses, oldest first, with withdrawn items as url null`() {
        val listing = ListingParser.parse(Fixtures.resource("music_listing.json"))!!
        assertEquals(1L, listing.entryId)
        assertEquals("a8146790d70b89a3", listing.version)
        assertEquals(listOf("c00dbbc9dadf", "c2a6b03f190d"), listing.items.map { it.key })
        assertNull(listing.items[0].url)
        assertNull(listing.items[0].durationMs)
        assertEquals("https://cdn.example.org/ny.m3u8", listing.items[1].url)
        assertTrue(listing.items[1].hls)
        assertEquals(3_723_000L, listing.items[1].durationMs)
        assertNull("no own picture", listing.items[0].art)
        assertEquals("940b951005008e7207e7f8b8807ac39691025858882b8c67843a29b65e388187", listing.items[1].art)
    }

    @Test
    fun `an item's art is a 64-hex hash or nothing (design 27)`() {
        val base = Fixtures.resource("music_listing.json")
        val hash = "940b951005008e7207e7f8b8807ac39691025858882b8c67843a29b65e388187"
        fun artOf(text: String) = ListingParser.parse(text)!!.items[1].art
        assertEquals(hash, artOf(base))
        assertNull("an older server's URL", artOf(base.replace("\"$hash\"", "\"https://example.org/ny.jpg\"")))
        assertNull("upper case", artOf(base.replace(hash, hash.uppercase())))
        assertNull("too short", artOf(base.replace(hash, hash.drop(1))))
        assertNull("a path", artOf(base.replace(hash, "../" + hash.drop(3))))
    }

    @Test
    fun `an entry or file the app can't use is hidden, but its id stays named (H1)`() {
        val text = Fixtures.resource("music_library.json")
            .replace("\"source\": \"rss\"", "\"source\": \"storytel\"") // a feed url isn't a Storytel target
            .replace("\"cache\": -1", "\"cache\": -2") // out of range (the own files and the Storytel series)
            .replace("\"spotify\": \"context\"", "\"spotify\": \"shuffle\"") // a mode this app doesn't know
        val lib = LibraryParser.parse(text)!!
        assertEquals(listOf(1L, 6L), lib.entries.map { it.id })
        assertEquals("named, so nothing of them is deleted", setOf(1L, 2L, 3L, 5L, 6L, 7L), lib.entryIds)
        assertTrue("the hidden entry's files are hidden", lib.files.isEmpty())
        assertEquals(setOf(1L, 2L), lib.fileIds)
    }

    @Test
    fun `a field that can degrade does - unknown order, bad cover, bad items version`() {
        val text = Fixtures.resource("music_library.json")
            .replace("\"order\": \"newest_first\"", "\"order\": \"by_mood\"")
            .replace("\"cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc\",\n      \"items\"", "\"cover-v2:xyz\",\n      \"items\"")
            .replace("\"items\": \"0123456789abcdef\"", "\"items\": \"v2-0123\"")
        val rss = LibraryParser.parse(text)!!.entry(2)!!
        assertEquals("auto", rss.order)
        assertNull(rss.cover)
        assertNull(rss.items)
    }

    @Test
    fun `unknown fields are ignored everywhere`() {
        val lib = Fixtures.resource("music_library.json")
            .replace("\"v\": 1,", "\"v\": 1, \"generated\": \"x\",")
            .replace("\"sort\": 10\n    }", "\"sort\": 10, \"mood\": [1]\n    }")
            .replace("\"missing\": false", "\"missing\": false, \"bpm\": 120")
        val parsed = LibraryParser.parse(lib)!!
        assertEquals(6, parsed.entries.size)
        assertEquals(2, parsed.files.size)
        val listing = Fixtures.resource("music_listing.json")
            .replace("\"hls\": true,", "\"hls\": true, \"chapters\": [],")
            .replace("\"v\": 1,", "\"v\": 1, \"next\": null,")
        assertEquals(2, ListingParser.parse(listing)!!.items.size)
    }

    @Test
    fun `a bad item is dropped, an unsafe url or key too`() {
        val listing = Fixtures.resource("music_listing.json")
            .replace("\"key\": \"c00dbbc9dadf\"", "\"key\": \"../etc\"")
            .replace("https://cdn.example.org/ny.m3u8", "file:///sdcard/x.mp3")
        val parsed = ListingParser.parse(listing)!!
        assertEquals(listOf("c2a6b03f190d"), parsed.items.map { it.key })
        assertNull("only http(s) urls are used", parsed.items[0].url)
    }

    @Test
    fun `a listing without readable items is refused, never applied as empty (M5)`() {
        val base = Fixtures.resource("music_listing.json")
        assertNull("items missing", ListingParser.parse(base.replace("\"items\":", "\"episodes\":")))
        assertNull("items not an array", ListingParser.parse("{\"v\": 1, \"entry\": 1, \"version\": \"x\", \"items\": {}}"))
        assertNull("no item parses", ListingParser.parse(base.replace("\"key\":", "\"id\":")))
        assertNotNull("an empty list is a list", ListingParser.parse("{\"v\": 1, \"entry\": 1, \"version\": \"x\", \"items\": []}"))
    }

    @Test
    fun `a library without its arrays is refused`() {
        val base = Fixtures.resource("music_library.json")
        assertNull(LibraryParser.parse(base.replace("\"entries\":", "\"entrys\":")))
        assertNull(LibraryParser.parse(base.replace("\"files\":", "\"filez\":")))
    }

    @Test
    fun `a document that isn't v1 is refused`() {
        assertNull(LibraryParser.parse(Fixtures.resource("music_library.json").replace("\"v\": 1", "\"v\": 2")))
        assertNull(LibraryParser.parse("[]"))
        assertNull(LibraryParser.parse("not json"))
        assertNull(ListingParser.parse(Fixtures.resource("music_listing.json").replace("\"v\": 1", "\"v\": 9")))
        assertNotNull(ListingParser.parse(Fixtures.resource("music_listing.json")))
    }

    @Test
    fun `cache -1 is accepted, -2 and 101 are refused (vibb offline_keep_all case 1)`() {
        val base = Fixtures.resource("music_library.json")
        assertEquals(-1, LibraryParser.parse(base)!!.entry(3)!!.cache)
        for (bad in listOf("-2", "101")) {
            val lib = LibraryParser.parse(base.replace("\"cache\": -1", "\"cache\": $bad"))!!
            assertNull("cache=$bad", lib.entry(3))
            assertTrue("hidden, not removed", 3L in lib.entryIds)
        }
    }

    @Test
    fun `lan is read for RSS only, and absent means public`() {
        val base = Fixtures.resource("music_library.json")
        val lan = LibraryParser.parse(base.replace("\"lan\": false", "\"lan\": true"))!!
        assertEquals("RSS only", listOf(false, true, false, false, false, false), lan.entries.map { it.lan })
        val old = LibraryParser.parse(base.replace(",\n      \"lan\": false", ""))!!
        assertTrue(old.entries.none { it.lan })
    }

    @Test
    fun `sonos_rooms - absent or null is every room, a list those, anything else none (26a §5_5)`() {
        val base = Fixtures.resource("music_library.json")
        val list = "\"sonos_rooms\": [\n    \"RINCON_000E58A0B1C201400\"\n  ]"
        assertTrue(base.contains(list))
        val absent = LibraryParser.parse(base.replace(",\n  $list", ""))!!
        assertNull("an older server: every room", absent.sonosRooms)
        assertNull(LibraryParser.parse(base.replace(list, "\"sonos_rooms\": null"))!!.sonosRooms)
        assertEquals(emptyList<String>(), LibraryParser.parse(base.replace(list, "\"sonos_rooms\": []"))!!.sonosRooms)
        val two = LibraryParser.parse(base.replace(list, "\"sonos_rooms\": [\"RINCON_A\", 5, null, {}, \"RINCON_B\", \"RINCON_A\"]"))!!
        assertEquals("strings only, once each", listOf("RINCON_A", "RINCON_B"), two.sonosRooms)
        for (odd in listOf("\"RINCON_A\"", "{}", "true", "7")) {
            assertEquals("fail closed: $odd", emptyList<String>(), LibraryParser.parse(base.replace(list, "\"sonos_rooms\": $odd"))!!.sonosRooms)
        }
        assertEquals("the rest of the library unchanged", 6, absent.entries.size)
    }

    @Test
    fun `urls are http or https without userinfo`() {
        assertEquals("https://a.example/x.mp3", safeUrl("https://a.example/x.mp3"))
        assertNull(safeUrl("data:audio/mp3;base64,AAAA"))
        assertNull(safeUrl("content://evil/x"))
        assertNull(safeUrl("https://user:pw@a.example/x.mp3"))
        assertNull(safeUrl("https://a.example/" + "x".repeat(2000)))
    }
}
