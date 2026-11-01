package me.vibb.music.logic

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.sonos.Fields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Security review 2026-10-21 P-L1: a release build's Sonos log names no MAC, room, title or URL path. */
class LogRedactTest {
    @Test
    fun `a release line keeps no uid, URL path, name or exception text - debug keeps all`() {
        val line = "sonos: playing on RINCON_000E58A0B1C201400 from http://10.0.0.5:1400/getaa?u=x (x-rincon-queue:RINCON_000E58A0B1C201400#0) " +
            "https://cdn.example.invalid/feed/episode-7.mp3?token=FAKE"
        val out = releaseLogLine(line)
        assertFalse(out, out.contains("000E58A0B1C2"))
        assertFalse(out, out.contains("getaa") || out.contains("episode-7") || out.contains("token"))
        assertTrue(out, out.contains("http://10.0.0.5:1400/…") && out.contains("https://cdn.example.invalid/…"))
        val tags = Regex("RINCON_#[0-9a-f]{4}").findAll(out).map { it.value }.toList()
        assertEquals("one uid, one tag", 1, tags.distinct().size)
        assertEquals(2, tags.size)
        assertEquals("‹name›", logName("Emmas rom", debug = false))
        assertEquals("Emmas rom", logName("Emmas rom", debug = true))
        val e = IllegalStateException("errorDescription 10.0.0.5:1400 RINCON_000E58A0B1C201400")
        assertEquals("IllegalStateException", logFailure(e, debug = false))
        // a speaker uri without "://" (the line filter can't cut it): by its scheme only in a release build
        for (u in listOf("x-sonos-http:cdn.example.invalid/ep-7.mp3?sid=1", "x-sonosapi-hls-static:ALkSOiH0?sid=2", "https://cdn.example.invalid/ep-7.mp3")) {
            val shown = Fields.shown(u, debug = false)!!
            assertEquals(u.substringBefore(':') + ":…", shown)
        }
        assertEquals("x-sonos-http:cdn.example.invalid/ep-7.mp3", Fields.shown("x-sonos-http:cdn.example.invalid/ep-7.mp3?sid=1", debug = true))
    }

    @Test
    fun `every Sonos line goes through the release filter, and names, titles and exception text only through the helpers`() {
        val home = moduleFile("src/main/java/me/vibb/music/sonos/SonosHome.kt").readText()
        assertTrue(home.contains("Log.i(TAG, if (BuildConfig.DEBUG) message else releaseLogLine(message))"))
        val files = moduleFile("src/main/java/me/vibb/music/sonos").listFiles()!!.filter { it.name.endsWith(".kt") } +
            moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt")
        val d = "\\$"
        val bare = Regex("$d\\{[^}]*\\.(name|title|message)\\}|${d}e\"|${d}e\\)|$d\\{e\\}")
        for (f in files) {
            for ((i, l) in f.readLines().withIndex()) {
                if (!Regex("""\blog\("|SonosHome\.log\(""").containsMatchIn(l)) continue
                assertFalse("${f.name}:${i + 1}: $l", bare.containsMatchIn(l))
            }
        }
    }
}
