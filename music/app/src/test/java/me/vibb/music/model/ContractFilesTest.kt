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
        for (name in listOf("music_library.json", "music_listing.json", "music_icons.json")) {
            assertEquals("$name differs from the server's copy", server(name).readText(), Fixtures.resource(name))
        }
    }

    @Test
    fun `the library snapshot parses`() {
        val lib = LibraryParser.parse(Fixtures.resource("music_library.json"))!!
        assertEquals("cd0b77ca352b1fd4", lib.version)
        assertTrue("the snapshot's feeds are public", lib.entries.none { it.lan })
        assertEquals(listOf(1L, 4L), lib.categories.map { it.id })
        assertEquals(listOf("music_note", "mic"), lib.categories.map { it.icon })
        assertEquals(listOf(Source.NRK, Source.RSS, Source.OWN), lib.entries.map { it.source })
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
        assertEquals(3, lib.covers.size)
    }

    @Test
    fun `the listing snapshot parses, oldest first, with withdrawn items as url null`() {
        val listing = ListingParser.parse(Fixtures.resource("music_listing.json"))!!
        assertEquals(1L, listing.entryId)
        assertEquals("33e4fb66e77b8d7f", listing.version)
        assertEquals(listOf("c00dbbc9dadf", "c2a6b03f190d"), listing.items.map { it.key })
        assertNull(listing.items[0].url)
        assertNull(listing.items[0].durationMs)
        assertEquals("https://cdn.example.org/ny.m3u8", listing.items[1].url)
        assertTrue(listing.items[1].hls)
        assertEquals(3_723_000L, listing.items[1].durationMs)
    }

    @Test
    fun `a bad entry, file or item is dropped, not the document`() {
        val text = Fixtures.resource("music_library.json")
            .replace("\"source\": \"rss\"", "\"source\": \"storytel\"") // not this phase
            .replace("\"cache\": -1", "\"cache\": -2") // out of range
        val lib = LibraryParser.parse(text)!!
        assertEquals(listOf(1L), lib.entries.map { it.id })
        assertTrue("files of a dropped entry go too", lib.files.isEmpty())

        val listing = Fixtures.resource("music_listing.json")
            .replace("\"key\": \"c00dbbc9dadf\"", "\"key\": \"../etc\"")
            .replace("https://cdn.example.org/ny.m3u8", "file:///sdcard/x.mp3")
        val parsed = ListingParser.parse(listing)!!
        assertEquals(listOf("c2a6b03f190d"), parsed.items.map { it.key })
        assertNull("only http(s) urls are used", parsed.items[0].url)
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
        }
    }

    @Test
    fun `lan is read for RSS only, and absent means public`() {
        val base = Fixtures.resource("music_library.json")
        val lan = LibraryParser.parse(base.replace("\"lan\": false", "\"lan\": true"))!!
        assertEquals(listOf(false, true, false), lan.entries.map { it.lan })
        val old = LibraryParser.parse(base.replace(",\n      \"lan\": false", ""))!!
        assertTrue(old.entries.none { it.lan })
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
