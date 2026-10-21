package me.vibb.music.sonos

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * palchrb/vibb `pi/sonosd.py` `_norm_uri`, `_hms`, `_hms_to_s`, `_mime_for` and Python's
 * `urllib.parse.quote` (MIT, Copyright (c) 2026 palchrb). Every expected value is what vibb's own
 * functions returned for the same input (design 26a §2.3, QA L1).
 */
class SonosUrisTest {
    @Test
    fun `normUri strips one of the four prefixes after decoding`() {
        assertEquals("a/b", normUri("x-rincon-mp3radio://a/b"))
        assertEquals("a/b", normUri("aac://a/b"))
        assertEquals("a/b", normUri("https://a/b"))
        assertEquals("a/b", normUri("http://a/b"))
        assertEquals("only the listed spellings", "HTTPS://a/b", normUri("HTTPS://a/b"))
        assertEquals("x-rincon:RINCON_X", normUri("x-rincon:RINCON_X"))
        assertEquals("", normUri(null))
    }

    @Test
    fun `normUri decodes like Python's unquote - a plus stays a plus`() {
        assertEquals("a.example/x y.mp3", normUri("https%3A%2F%2Fa.example%2Fx%20y.mp3"))
        assertEquals("a/b+c", normUri("http://a/b+c"))
        assertEquals("a/✓", normUri("https://a/%E2%9C%93"))
        assertEquals("a bad escape stays", "%zz%4", normUri("%zz%4"))
        assertEquals("a broken UTF-8 byte is replaced", "�", normUri("%C3"))
        assertEquals("non-ASCII passes through", "ææ", normUri("æ%C3%A6"))
        // the speaker's re-encoding of our own uri is still ours (field 2026-08-09)
        assertEquals(
            normUri("https://cdn.example/signed/ep12.mp3?tok=abc"),
            normUri("https://cdn.example/signed/ep12.mp3%3Ftok%3Dabc"),
        )
        assertEquals(normUri("https://podkast.example/ep12.mp3"), normUri("x-rincon-mp3radio://podkast.example/ep12.mp3"))
    }

    @Test
    fun `hms - whole seconds, H MM SS`() {
        assertEquals("0:00:00", hms(null))
        assertEquals("0:00:00", hms(0.0))
        assertEquals("0:00:04", hms(4.9))
        assertEquals("0:00:59", hms(59.9))
        assertEquals("0:01:01", hms(61.0))
        assertEquals("1:00:00", hms(3600.0))
        assertEquals("1:01:01", hms(3661.7))
        assertEquals("10:00:00", hms(36000.0))
        assertEquals("0:00:00", hms(-5.0))
        assertEquals("0:02:00", hms(120.0))
    }

    @Test
    fun `hmsToS - 0 00 00 and null are 0, NOT_IMPLEMENTED is null`() {
        assertEquals(0.0, hmsToS("0:00:00")!!, 0.0)
        assertEquals(0.0, hmsToS(null)!!, 0.0)
        assertEquals(0.0, hmsToS("")!!, 0.0)
        assertNull(hmsToS("NOT_IMPLEMENTED"))
        assertEquals(3723.0, hmsToS("1:02:03")!!, 0.0)
        assertEquals(431.5, hmsToS("0:07:11.500")!!, 0.0)
        assertEquals(5.0, hmsToS("0:00:05")!!, 0.0)
        assertNull(hmsToS("12:00"))
        assertNull(hmsToS("a:b:c"))
        assertNull("not Kotlin's 1d", hmsToS("0:00:1d"))
    }

    @Test
    fun `quote keeps the slash, encodes UTF-8 uppercase`() {
        assertEquals("barnas-supersommer", pyQuote("barnas-supersommer"))
        assertEquals("a/b%20c", pyQuote("a/b c"))
        assertEquals("%C3%A6%C3%B8%C3%A5", pyQuote("æøå"))
        assertEquals("x~y_z.-", pyQuote("x~y_z.-"))
        assertEquals("a%2Bb%26c", pyQuote("a+b&c"))
    }

    @Test
    fun `mime by the path's extension, query, params and fragment ignored`() {
        assertEquals("audio/mpeg", mimeFor("https://a/b.MP3"))
        assertEquals("audio/mp4", mimeFor("https://a/b.m4b?x=y.mp3"))
        assertEquals("audio/ogg", mimeFor("https://a/b.ogg;v=1"))
        assertEquals("audio/mpeg", mimeFor("https://a/b"))
        assertEquals("audio/ogg", mimeFor("https://a/b.opus#t"))
        assertEquals("audio/mpeg", mimeFor("x-sonos-http:series%3ax.unknown?sid=277"))
        assertEquals("audio/flac", mimeFor("http://h:8080/x/y.flac"))
    }

    @Test
    fun `escape touches amp lt gt only`() {
        assertEquals("a &amp; b &lt;c&gt; \"d\" 'e'", xmlEscape("a & b <c> \"d\" 'e'"))
    }

    @Test
    fun `d54 the kind of a stored uri - the speaker's own queue is a share link's marker`() {
        assertEquals(Kind.SPOTIFY_SHARELINK, kindOfUri(queueUri("RINCON_X")))
        assertEquals(Kind.NRK_PROGRAM, kindOfUri("x-sonos-http:series%3ax.unknown?sid=277"))
        assertEquals(Kind.URL, kindOfUri("https://podkast.example/ep12.mp3"))
        assertEquals("spotify_sharelink", Kind.SPOTIFY_SHARELINK.wire)
    }
}
