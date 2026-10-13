package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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

    private fun source(name: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/server/$name"), File("app/src/main/java/com/kidslauncher/mdm/server/$name"))
            .first { it.exists() }.readText()

    /** qa-26c-step3-code L1: the flag [classifyDomain] gets is set at both places - every fresh sync, before
     * the list's refetch, and the VPN's start from the cached policy - and `classify` passes it. */
    @Test
    fun `the Spotify flag is set by the sync and at the VPN's load, and classify reads it`() {
        val sync = source("MdmSyncWorker.kt")
        val set = sync.indexOf("DnsFilterEngine.setSpotify(com.kidslauncher.mdm.music.musicSpotify(freshPolicy.music))")
        val refresh = sync.indexOf("DnsFilterEngine.refreshIfNeeded(context, api, freshPolicy.dnsFilterVersion)")
        assertTrue("the sync sets it", set >= 0)
        assertTrue("before the list is refetched", set < refresh)
        val engine = source("DnsFilterEngine.kt")
        val load = engine.substringAfter("fun loadFromDisk(context: Context) {").substringBefore("try {")
        assertTrue(load, Regex("spotifyOn = runCatching \\{\\s*com\\.kidslauncher\\.mdm\\.music\\.musicSpotify\\(\\(cachedPolicy\\(\\) as\\? CachedPolicy\\.Ok\\)\\?\\.policy\\?\\.music\\)").containsMatchIn(load))
        assertTrue(engine.contains("fun setSpotify(on: Boolean) {\n        spotifyOn = on"))
        assertTrue(engine.contains("fun classify(domainRaw: String): String? = classifyDomain(blockedDomains, domainRaw, spotifyOn)"))
    }
}
