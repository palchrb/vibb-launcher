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
        assertTrue("provisional until the capture", (contract["comment"] as JsonPrimitive).content.contains("PROVISIONAL"))
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
