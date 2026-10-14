package com.kidslauncher.mdm.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The token courier (design 26c §6.1): the server's answer as the callback carries it. */
class SpotifyTokenCourierTest {
    private fun ok(token: String = "FAKE-ACCESS-1", life: String = "3300", account: String = "\"3:2\"") =
        """{"status": "ok", "access_token": "$token", "expires_in_s": $life, "account": $account}"""

    @Test
    fun `ok carries the token, its life in ms and the account`() {
        assertEquals(SpotifyTokenAnswer("ok", "FAKE-ACCESS-1", 3_300_000, "3:2"), spotifyTokenAnswer(200, ok()))
    }

    @Test
    fun `an ok that isn't whole is the launcher's failure, never a half token`() {
        for (body in listOf(
            ok(life = "0"), ok(life = "\"3300\""), ok(life = "86401"), ok(account = "\"3\""), ok(account = "null"),
            ok(token = ""), ok(token = "two words"), """{"status": "ok"}""",
        )) {
            assertEquals(body, SpotifyTokenAnswer.LAUNCHER, spotifyTokenAnswer(200, body))
        }
        assertEquals("ok only with 200", SpotifyTokenAnswer.LAUNCHER, spotifyTokenAnswer(500, ok()))
    }

    @Test
    fun `every other status passes through without a token`() {
        assertEquals(SpotifyTokenAnswer("off", null, 0, null), spotifyTokenAnswer(404, """{"status": "off"}"""))
        assertEquals(SpotifyTokenAnswer("reauthorize", null, 0, null), spotifyTokenAnswer(409, """{"status": "reauthorize"}"""))
        assertEquals(SpotifyTokenAnswer("unreachable", null, 0, null), spotifyTokenAnswer(502, """{"status": "unreachable"}"""))
        assertEquals(SpotifyTokenAnswer("key", null, 0, null), spotifyTokenAnswer(503, """{"status": "key"}"""))
        // A token in a failing answer is never passed on.
        assertEquals(SpotifyTokenAnswer("key", null, 0, null), spotifyTokenAnswer(503, """{"status": "key", "access_token": "FAKE-ACCESS-9"}"""))
        // A newer server's word, as it is (the music app maps it to "unreachable").
        assertEquals(SpotifyTokenAnswer("paused_by_parent", null, 0, null), spotifyTokenAnswer(403, """{"status": "paused_by_parent"}"""))
    }

    @Test
    fun `busy carries the wait`() {
        assertEquals(SpotifyTokenAnswer("busy", null, 7_000, null), spotifyTokenAnswer(429, """{"status": "busy", "retry_after_s": 7}"""))
        assertEquals(SpotifyTokenAnswer("busy", null, SPOTIFY_BUSY_DEFAULT_MS, null), spotifyTokenAnswer(429, """{"status": "busy"}"""))
        assertEquals(SpotifyTokenAnswer("busy", null, SPOTIFY_BUSY_DEFAULT_MS, null), spotifyTokenAnswer(429, """{"status": "busy", "retry_after_s": 99999}"""))
    }

    @Test
    fun `no status word - an older server's 404 is off, a 5xx unreachable, the rest the launcher's`() {
        assertEquals(SpotifyTokenAnswer("off", null, 0, null), spotifyTokenAnswer(404, ""))
        assertEquals(SpotifyTokenAnswer("unreachable", null, 0, null), spotifyTokenAnswer(502, "<html>Bad gateway</html>"))
        assertEquals(SpotifyTokenAnswer.LAUNCHER, spotifyTokenAnswer(401, null))
        assertEquals(SpotifyTokenAnswer.LAUNCHER, spotifyTokenAnswer(200, """{"status": "OK!"}"""))
        assertEquals(SpotifyTokenAnswer.LAUNCHER, spotifyTokenAnswer(200, "[]"))
    }

    @Test
    fun `the answer never prints its token`() {
        val answer = spotifyTokenAnswer(200, ok())
        assertFalse(answer.toString(), answer.toString().contains("FAKE-ACCESS"))
        assertTrue(answer.toString().contains("<redacted>"))
    }

    @Test
    fun `the bridge asks the server at every call - no cache, no log of the answer, launcher on any failure`() {
        val source = listOf(File("src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt"), File("app/src/main/java/com/kidslauncher/mdm/music/MusicBridgeService.kt"))
            .first { it.exists() }.readText()
        val method = source.substringAfter("private suspend fun answerSpotify(").substringBefore("\n    }\n")
        assertTrue(method.contains("withTimeout(SPOTIFY_TOKEN_TIMEOUT_MS)"))
        assertTrue(method.contains("createMusicServer(url, token).spotifyToken()"))
        assertTrue(method.contains("SpotifyTokenAnswer.LAUNCHER"))
        val logs = method.lines().filter { "Log." in it }
        assertEquals(2, logs.size)
        for (line in logs) assertFalse(line, Regex("\\$\\{?(answer|body|response|token|url)").containsMatchIn(line))
        assertFalse("nothing stored", method.contains("MusicStore") || method.contains("prefs"))
    }
}
