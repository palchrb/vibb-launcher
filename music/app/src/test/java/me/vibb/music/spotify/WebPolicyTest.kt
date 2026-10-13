package me.vibb.music.spotify

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * What the phone's web player may load (design 26c §5.2, §7.2, §9.3): our two assets, https to
 * Spotify's hosts, the fake only with the debug switch and only from 10.0.2.2 or loopback; the
 * page's CSP, its one substitution and the host -> page calls.
 */
class WebPolicyTest {
    @After
    fun off() {
        SpotifyEndpoints.debugBase = null
    }

    private val release = WebPolicy()

    @Test
    fun `our origin serves our two assets and nothing else`() {
        assertEquals(WebDecision.Asset("spotify/player.js"), release.decide("https://appassets.androidplatform.net/spotify/player.js"))
        assertEquals(WebDecision.Asset("spotify/player.html"), release.decide("https://appassets.androidplatform.net/spotify/player.html"))
        for (other in listOf("/spotify/", "/spotify/other.js", "/player.js", "/licenses/MIT-vibb.txt", "/spotify/player.js/x")) {
            assertEquals(other, WebDecision.NotFound, release.decide("https://appassets.androidplatform.net$other"))
        }
        assertEquals("http is never ours", WebDecision.Block, release.decide("http://appassets.androidplatform.net/spotify/player.js"))
    }

    @Test
    fun `https to Spotify's hosts passes, everything else is blocked`() {
        for (url in listOf(
            "https://sdk.scdn.co/spotify-player.js", "https://api.spotify.com/v1/me/player", "https://gew1-spclient.spotify.com/x?y=1",
            "https://audio-ak-spotify-com.akamaized.net/audio/abc", "https://i.scdn.co/image/x", "https://SDK.SCDN.CO/spotify-player.js",
        )) {
            assertEquals(url, WebDecision.Pass, release.decide(url))
        }
        for (url in listOf(
            "http://sdk.scdn.co/spotify-player.js", "https://sdk.scdn.co:8443/x", "https://user:pw@sdk.scdn.co/x",
            "https://evilspotify.com/x", "https://spotify.com.evil.net/x", "https://akamaized.net/x", "https://www.googleapis.com/x",
            "https://fake-spotify.invalid/sdk/spotify-player.js", "https://10.0.2.2/x", "file:///sdcard/x", "content://x/y",
            "javascript:alert(1)", "ftp://sdk.scdn.co/x", "wss://dealer.spotify.com/", "", "not a url", "intent://x",
        )) {
            assertEquals(url, WebDecision.Block, release.decide(url))
        }
        assertEquals("the SDK's media source", WebDecision.Pass, release.decide("blob:https://sdk.scdn.co/1234-5678"))
        assertEquals("an inline picture", WebDecision.Pass, release.decide("data:image/png;base64,AAAA"))
    }

    @Test
    fun `the fake's https name only with the debug switch, fetched from the debug base`() {
        assertEquals(WebDecision.Block, WebPolicy.current().decide("https://fake-spotify.invalid/sdk/spotify-player.js"))
        SpotifyEndpoints.debugBase = "http://10.0.2.2:8731"
        val debug = WebPolicy.current()
        assertEquals(
            WebDecision.Proxy("http://10.0.2.2:8731/sdk/spotify-player.js".toHttpUrl()),
            debug.decide("https://fake-spotify.invalid/sdk/spotify-player.js"),
        )
        assertEquals(
            WebDecision.Proxy("http://10.0.2.2:8731/fake/device/fakedevice1/commands?after=3".toHttpUrl()),
            debug.decide("https://fake-spotify.invalid/fake/device/fakedevice1/commands?after=3"),
        )
        assertEquals("http to it stays blocked", WebDecision.Block, debug.decide("http://fake-spotify.invalid/x"))
        assertEquals("Spotify's hosts still pass", WebDecision.Pass, debug.decide("https://sdk.scdn.co/spotify-player.js"))
        assertEquals("the debug base itself is never the page's", WebDecision.Block, debug.decide("http://10.0.2.2:8731/sdk/spotify-player.js"))
    }

    @Test
    fun `the debug switch takes the fake's homes only, and its six readers answer by it`() {
        assertEquals(SpotifyEndpoints.API, SpotifyEndpoints.apiBase())
        assertFalse(SpotifyEndpoints.apiOverLan())
        assertEquals("https://sdk.scdn.co/spotify-player.js", SpotifyEndpoints.sdkUrl())
        assertEquals(emptySet<String>(), SpotifyEndpoints.webHosts())
        assertNull(SpotifyEndpoints.webProxy("https://fake-spotify.invalid/x".toHttpUrl()))
        assertNull(SpotifyEndpoints.debugTokens())
        for (bad in listOf("http://192.168.1.5:8731", "https://10.0.2.2:8731", "http://user:pw@10.0.2.2:8731", "http://example.org:8731", "10.0.2.2:8731", "junk")) {
            SpotifyEndpoints.debugBase = bad
            assertEquals(bad, SpotifyEndpoints.API, SpotifyEndpoints.apiBase())
            assertFalse(bad, SpotifyEndpoints.apiOverLan())
            assertEquals(bad, SpotifyEndpoints.SDK_URL, SpotifyEndpoints.sdkUrl())
            assertNull(bad, SpotifyEndpoints.debugTokens())
        }
        for (good in listOf("http://10.0.2.2:8731", "http://127.0.0.1:8731", "http://localhost:8731", "http://[::1]:8731")) {
            SpotifyEndpoints.debugBase = good
            assertEquals(good, good.toHttpUrl().newBuilder().encodedPath("/").build(), SpotifyEndpoints.apiBase())
            assertTrue(good, SpotifyEndpoints.apiOverLan())
            assertEquals("https://fake-spotify.invalid/sdk/spotify-player.js", SpotifyEndpoints.sdkUrl())
            assertEquals(setOf("fake-spotify.invalid"), SpotifyEndpoints.webHosts())
            assertEquals(good.toHttpUrl().newBuilder().encodedPath("/fake/token").addQueryParameter("user", "kid").build(), SpotifyEndpoints.debugTokens())
        }
        SpotifyEndpoints.debugBase = "http://10.0.2.2:8731"
        assertNull("only the fake's name", SpotifyEndpoints.webProxy("https://sdk.scdn.co/x".toHttpUrl()))
        assertNull("only https", SpotifyEndpoints.webProxy("http://fake-spotify.invalid/x".toHttpUrl()))
    }

    @Test
    fun `the page's CSP and its one substitution`() {
        val template = "<meta content=\"${SpotifyPage.csp(SpotifyPage.SDK_ORIGIN_SLOT)}\"><script src=\"${SpotifyPage.SDK_URL_SLOT}\"></script>"
        val page = SpotifyPage.html(template, SpotifyEndpoints.SDK_URL)
        assertEquals(
            "<meta content=\"default-src 'none'; script-src https://appassets.androidplatform.net https://sdk.scdn.co; frame-src https://sdk.scdn.co; " +
                "connect-src https: wss:; media-src https: blob:; img-src https: data:\"><script src=\"https://sdk.scdn.co/spotify-player.js\"></script>",
            page,
        )
        assertTrue(SpotifyPage.html(template, "https://fake-spotify.invalid/sdk/spotify-player.js")
            .contains("script-src https://appassets.androidplatform.net https://fake-spotify.invalid; frame-src https://fake-spotify.invalid;"))
        for (bad in listOf("http://sdk.scdn.co/spotify-player.js", "https://sdk.scdn.co/spotify-player.js?x=1", "https://u:p@sdk.scdn.co/x")) {
            try {
                SpotifyPage.html(template, bad)
                fail(bad)
            } catch (_: IllegalArgumentException) {
            }
        }
        try {
            SpotifyPage.html("<script src=\"x\"></script>", SpotifyEndpoints.SDK_URL)
            fail("a template without its slots")
        } catch (_: IllegalArgumentException) {
        }
        assertEquals("https://appassets.androidplatform.net/spotify/", SpotifyPage.BASE_URL)
    }

    @Test
    fun `a host to page call quotes every argument`() {
        assertEquals("vibb.connect(\"Vibb\")", SpotifyPage.call("connect", SpotifyPage.PLAYER_NAME))
        assertEquals("vibb.token(3,\"FAKE-ACCESS-1\")", SpotifyPage.call("token", 3, "FAKE-ACCESS-1"))
        assertEquals("vibb.token(4,null)", SpotifyPage.call("token", 4, null))
        assertEquals("vibb.seek(42000)", SpotifyPage.call("seek", 42_000L))
        assertEquals("vibb.volume(0.3)", SpotifyPage.call("volume", 0.3))
        assertEquals("vibb.volume(null)", SpotifyPage.call("volume", Double.NaN))
        assertEquals("vibb.pause()", SpotifyPage.call("pause"))
        assertEquals(
            "a string can't end the call",
            "vibb.connect(\"a\\\"); evil(\\\"\\u2028\\u2029\\\\\")",
            SpotifyPage.call("connect", "a\"); evil(\"  \\"),
        )
        try {
            SpotifyPage.call("eval", "x")
            fail("only the page's entry points")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SpotifyPage.call("token", 1, listOf("x"))
            fail("strings, numbers, booleans and null only")
        } catch (_: IllegalArgumentException) {
        }
        assertTrue(SpotifyPage.DEVICE_ID.matches("fakedevice0000000000000000000000000000001"))
        assertFalse(SpotifyPage.DEVICE_ID.matches("a-b"))
        assertFalse("Policy §VI: never Spot...", SpotifyPage.PLAYER_NAME.startsWith("Spot", ignoreCase = true))
    }
}
