package me.vibb.music.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import me.vibb.music.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Spotify's hosts (design 26c §6.2, §8 item 5): the music app's constants are its copy of
 * spotify_hosts.json's (ContractFilesTest keeps the copy equal to the server's), and the rule is the
 * server's and the launcher's.
 */
class SpotifyHostsTest {
    private val contract by lazy { Json.parseToJsonElement(Fixtures.resource("spotify_hosts.json")).jsonObject }

    private fun strings(key: String) = (contract[key] as JsonArray).map { (it as JsonPrimitive).content }

    @Test
    fun `the constants are the shared list's`() {
        assertEquals(strings("suffixes"), SPOTIFY_HOST_SUFFIXES)
        assertEquals(strings("exact"), SPOTIFY_EXACT_HOSTS)
    }

    /**
     * The list's shape, never its wording (qa-26c-step7-docs M1): the DNS capture's commit changes the data and
     * the comment, not this test. Exactly the three keys, a comment, two lists of distinct lower-case host names
     * without a leading or trailing dot, and no exact host under a suffix.
     */
    @Test
    fun `the shared list has its shape`() {
        assertEquals(setOf("comment", "suffixes", "exact"), contract.keys)
        assertTrue((contract["comment"] as JsonPrimitive).content.isNotBlank())
        for (key in listOf("suffixes", "exact")) {
            val hosts = strings(key)
            assertTrue(key, hosts.isNotEmpty())
            assertEquals("$key repeats a host", hosts.size, hosts.toSet().size)
            for (host in hosts) {
                assertTrue("$key: $host", HOST_NAME.matches(host))
            }
        }
        for (host in strings("exact")) {
            assertFalse("$host lies under a suffix", strings("suffixes").any { host == it || host.endsWith(".$it") })
        }
    }

    private companion object {
        val HOST_NAME = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)+$")
    }

    @Test
    fun `a host is Spotify's - equal to or under a suffix, or an exact CDN host`() {
        for (host in listOf(
            "spotify.com", "api.spotify.com", "gew1-spclient.spotify.com", "SDK.scdn.co.", "i.scdn.co", "a.spotifycdn.com",
            "b.spotifycdn.net", "anchor.pscdn.co", "audio-ak-spotify-com.akamaized.net", "audio-akp-quic-spotify-com.akamaized.net",
        )) {
            assertTrue(host, isSpotifyHost(host))
        }
        for (host in listOf(
            "akamaized.net", "x.akamaized.net", "x.audio-ak-spotify-com.akamaized.net", "evilspotify.com", "spotify.com.evil.net",
            "scdn.com", ".spotify.com", "spotify.co", "", "fake-spotify.invalid", "appassets.androidplatform.net",
        )) {
            assertFalse(host, isSpotifyHost(host))
        }
    }
}
