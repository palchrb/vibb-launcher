package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The on-device filter's decision ([classifyDomain]): the suffix walk, FCM's global exemption and
 * Spotify's per-phone one (design 26c §6.2). */
class DnsFilterEngineTest {
    private val blocked = mapOf(
        "spotify.com" to "Streaming",
        "scdn.co" to "Streaming",
        "akamaized.net" to "Ads",
        "ads.example" to "Ads",
        "google.com" to "Ads",
    )

    @Test
    fun `the suffix walk blocks a domain and everything under it`() {
        assertEquals("Ads", classifyDomain(blocked, "ads.example", spotify = false))
        assertEquals("Ads", classifyDomain(blocked, "x.Ads.Example.", spotify = false))
        assertNull(classifyDomain(blocked, "example", spotify = false))
        assertNull(classifyDomain(blocked, "notads.example.org", spotify = false))
    }

    @Test
    fun `FCM's hosts always pass, a parent the parent blocked doesn't open the rest`() {
        assertNull(classifyDomain(blocked, "mtalk.google.com", spotify = false))
        assertEquals("Ads", classifyDomain(blocked, "ads.google.com", spotify = false))
    }

    @Test
    fun `Spotify's hosts under a blocked parent pass only while the phone plays Spotify (26c §6_2)`() {
        val spotifyHosts = listOf(
            "spotify.com", "api.spotify.com", "spclient.wg.spotify.com", "sdk.scdn.co", "i.scdn.co",
            "audio-ak-spotify-com.akamaized.net", "audio-akp-quic-spotify-com.akamaized.net.",
        )
        for (host in spotifyHosts) {
            assertNull(host, classifyDomain(blocked, host, spotify = true))
            assertEquals(host, if (host.contains("akamaized")) "Ads" else "Streaming", classifyDomain(blocked, host, spotify = false))
        }
        // Never the third-party parent, nor anything else under it, nor a look-alike.
        assertEquals("Ads", classifyDomain(blocked, "akamaized.net", spotify = true))
        assertEquals("Ads", classifyDomain(blocked, "other.akamaized.net", spotify = true))
        assertEquals("Ads", classifyDomain(blocked, "ads.example", spotify = true))
        assertEquals("Ads", classifyDomain(blocked + ("evilspotify.com" to "Ads"), "evilspotify.com", spotify = true))
    }
}
