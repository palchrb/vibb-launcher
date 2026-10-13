package me.vibb.music.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** The page's state posts (design 26c §5.3): the shape, the caps, null, junk; and the listing title the tracker compares. */
class SdkStateTest {
    private val full = """{"paused":false,"position":42000,"duration":201000,"uri":"spotify:track:1FakeTrackA00000000001",""" +
        """"linked":"spotify:track:1FakeTrackZ00000000009","type":"track","name":"Stille natt","artists":["Ola Fjell","Kari Li"],""" +
        """"next":["spotify:track:FAKEtrack0000000000002","spotify:track:FAKEtrack0000000000003"],"prev":[],"context":"spotify:playlist:FAKEplaylist0000000001"}"""

    @Test
    fun `a full post`() {
        assertEquals(
            SdkState(
                paused = false, positionMs = 42_000, durationMs = 201_000, uri = "spotify:track:1FakeTrackA00000000001",
                linkedUri = "spotify:track:1FakeTrackZ00000000009", nextUris = listOf("spotify:track:FAKEtrack0000000000002", "spotify:track:FAKEtrack0000000000003"),
                prevUris = emptyList(), type = "track", name = "Stille natt", artists = listOf("Ola Fjell", "Kari Li"),
                contextUri = "spotify:playlist:FAKEplaylist0000000001",
            ),
            SdkState.parse(full),
        )
        assertEquals("Stille natt — Ola Fjell, Kari Li", (SdkState.parse(full) as SdkState).title)
    }

    @Test
    fun `null is Inactive, junk is nothing`() {
        assertSame(Inactive, SdkState.parse("null"))
        assertSame(Inactive, SdkState.parse(" null "))
        for (junk in listOf(
            null, "", "nul", "[]", "42", "\"paused\"", "{}", """{"paused":"false","position":0}""", """{"paused":false}""",
            """{"paused":false,"position":"5"}""", """{"paused":1,"position":0}""", """{"position":0}""", "{", "x".repeat(10),
        )) {
            assertNull(junk, SdkState.parse(junk))
        }
        assertNull("over 8 KB", SdkState.parse(full.dropLast(1) + ",\"pad\":\"" + "x".repeat(SdkState.MAX_BYTES) + "\"}"))
    }

    @Test
    fun `every field is capped and checked`() {
        val s = SdkState.parse(
            """{"paused":true,"position":-5,"duration":0,"uri":"https://open.spotify.com/track/x","linked":"spotify:track:a-b",""" +
                """"type":"podcast","name":"${"n".repeat(500)}","artists":["${"a".repeat(300)}",7,null,"",""" +
                (1..20).joinToString(",") { "\"A$it\"" } + """],"next":["spotify:track:1","spotify:track:2","spotify:track:3","spotify:track:4",""" +
                """"junk"],"prev":"spotify:track:1","context":"spotify:user:x:collection","extra":{"x":1}}""",
        )
        assertNull("a negative position is junk", s)
        val t = SdkState.parse(
            """{"paused":true,"position":${48L * 3_600_000},"duration":0,"uri":"https://open.spotify.com/track/x","linked":"spotify:track:a-b",""" +
                """"type":"podcast","name":"${"n".repeat(500)}","artists":["${"a".repeat(300)}",7,null,"",""" +
                (1..20).joinToString(",") { "\"A$it\"" } + """],"next":["spotify:track:1","spotify:track:2","spotify:track:3","spotify:track:4",""" +
                """"junk"],"prev":"spotify:track:1","context":"spotify:user:x:collection","extra":{"x":1}}""",
        ) as SdkState
        assertEquals("a day at most", 24L * 3_600_000, t.positionMs)
        assertNull("0 is none", t.durationMs)
        assertNull("not a spotify uri", t.uri)
        assertNull("a uri's id is base62", t.linkedUri)
        assertNull("track, episode or ad only", t.type)
        assertEquals(200, t.name!!.length)
        assertEquals(10, t.artists.size)
        assertEquals(200, t.artists[0].length)
        assertEquals("A1", t.artists[1])
        assertEquals(listOf("spotify:track:1", "spotify:track:2", "spotify:track:3"), t.nextUris)
        assertEquals("not a list", emptyList<String>(), t.prevUris)
        assertEquals("spotify:user:x:collection", t.contextUri)
        val missing = SdkState.parse("""{"paused":false,"position":12.7}""") as SdkState
        assertEquals(SdkState(false, 12, null, null, null, emptyList(), emptyList(), null, null, emptyList(), null), missing)
        assertNull(missing.title)
        assertEquals("an episode", "episode", (SdkState.parse("""{"paused":false,"position":0,"type":"episode"}""") as SdkState).type)
    }

    @Test
    fun `shuffle and repeat are read, junk is off, and a post without a track is trackless (qa-26c-step4-code M1, L2)`() {
        fun post(extra: String) = SdkState.parse("""{"paused":false,"position":0,"uri":"spotify:track:FAKEtrack0000000000001","name":"x"$extra}""") as SdkState
        assertEquals(false to 0, post("").let { it.shuffle to it.repeat })
        assertTrue(post(",\"shuffle\":true").reordered)
        assertEquals(2, post(",\"repeat\":2").repeat)
        assertTrue(post(",\"repeat\":1").reordered)
        for (junk in listOf(",\"shuffle\":\"true\"", ",\"repeat\":3", ",\"repeat\":-1", ",\"repeat\":\"2\"")) {
            assertFalse(junk, post(junk).reordered)
        }
        assertFalse(post("").reordered)
        assertTrue((SdkState.parse("""{"paused":false,"position":0}""") as SdkState).trackless)
        assertFalse("a name alone is a track", (SdkState.parse("""{"paused":false,"position":0,"name":"x"}""") as SdkState).trackless)
    }

    @Test
    fun `the listing title is the server's - track_title then cut_title`() {
        assertEquals("Stille natt — Ola Fjell, Kari Li", listingTitle("Stille natt", listOf("Ola Fjell", "Kari Li")))
        assertEquals("no artists: the name alone", "Stille natt", listingTitle(" Stille natt ", emptyList()))
        assertEquals("blank artists are left out", "Sang — Kari", listingTitle("Sang", listOf(" ", "Kari", "")))
        assertEquals("whitespace runs (Unicode's) collapse", "A b — C", listingTitle("A \t  b", listOf(" C\n")))
        assertEquals("control characters go after the join (Rust's order)", "a  b", cutTitle("a \u0001 b"))
        assertEquals(200, cutTitle("x".repeat(300)).length)
        assertEquals("code points, not chars", 200, cutTitle("🎵".repeat(300)).codePointCount(0, 400))
        assertEquals(
            SdkState.KEYS,
            listOf("paused", "position", "duration", "uri", "linked", "type", "name", "artists", "next", "prev", "context", "shuffle", "repeat"),
        )
    }
}
