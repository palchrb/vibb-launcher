package com.kidslauncher.mdm.music

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spotify's hosts (design 26c §6.2, §8 item 5): the constants are the shared list's, and the rule. */
class SpotifyHostsTest {
    private val contract by lazy {
        ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("spotify_hosts.json")!!.readText()).jsonObject
    }

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
            "scdn.com", ".spotify.com", "spotify.co", "",
        )) {
            assertFalse(host, isSpotifyHost(host))
        }
    }
}
