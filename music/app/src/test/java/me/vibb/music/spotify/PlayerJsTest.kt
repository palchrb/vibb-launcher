package me.vibb.music.spotify

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The page and its glue (design 26c §5.2, §5.3, §7.2), checked as text from the JVM (no browser
 * here): `player.html` has no inline script or handler, the CSP of §5.2 and the template's two slots;
 * `player.js` speaks exactly [SpotifyPage]'s bridge both ways, posts exactly [SdkState.KEYS], keeps the
 * token to getOAuthToken's callback, and loads, stores and logs nothing itself. Step 5a's host uses
 * the same [SpotifyPage] names; scripts/fake-spotify.py's selftest checks that its stub SDK has every
 * method and event this script uses.
 */
class PlayerJsTest {
    private val html = moduleFile("src/main/assets/spotify/player.html").readText()
    private val js = moduleFile("src/main/assets/spotify/player.js").readText()

    /** The script without its comments (a comment may name what the code mustn't do). */
    private val code = js.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("(?m)^\\s*//.*$"), "")
        .replace(Regex("(?m)\\s//[^'\\n]*$"), "")

    @Test
    fun `the page - no inline script, no handler, the CSP, the two slots, our script before the SDK`() {
        val scripts = Regex("<script([^>]*)>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        assertEquals(listOf(" src=\"player.js\"", " src=\"${SpotifyPage.SDK_URL_SLOT}\""), scripts.map { it.groupValues[1] })
        assertTrue("no inline script", scripts.all { it.groupValues[2].isBlank() })
        assertEquals("every script tag is one of the two", 2, Regex("<script").findAll(html).count())
        assertFalse("no inline event handler", Regex("\\son[a-z]+\\s*=", RegexOption.IGNORE_CASE).containsMatchIn(html))
        assertFalse("never pinned (no SRI): Spotify updates it in place", html.contains("integrity"))
        assertFalse("no javascript: url", html.contains("javascript:", ignoreCase = true))
        val meta = Regex("<meta http-equiv=\"Content-Security-Policy\" content=\"([^\"]*)\">").find(html)
        assertEquals(SpotifyPage.csp(SpotifyPage.SDK_ORIGIN_SLOT), meta?.groupValues?.get(1))
        assertEquals("one CSP", 1, Regex("Content-Security-Policy").findAll(html).count())
        val page = SpotifyPage.html(html, SpotifyEndpoints.SDK_URL)
        assertTrue(page.contains("<script src=\"https://sdk.scdn.co/spotify-player.js\"></script>"))
        assertTrue(page.contains("default-src 'none'; script-src https://appassets.androidplatform.net https://sdk.scdn.co; frame-src https://sdk.scdn.co; connect-src https: wss:; media-src https: blob:; img-src https: data:"))
        assertFalse("nothing left to fill", page.contains("{{"))
        assertFalse(html.contains("debug-feed") || js.contains("debug-feed"))
    }

    @Test
    fun `page to host - the bridge object and exactly its five methods`() {
        assertTrue(code.contains("var bridge = window.${SpotifyPage.BRIDGE};"))
        val called = Regex("bridge\\.([a-zA-Z]+)\\(").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(SpotifyPage.FROM_PAGE.toSet(), called)
        assertFalse("no other way out", Regex("window\\.(?!${SpotifyPage.BRIDGE}\\b|onSpotifyWebPlaybackSDKReady|vibb\\b|Spotify\\b|addEventListener)[a-zA-Z]+").containsMatchIn(code))
        val kinds = Regex("report\\('([a-z]+)'").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals("error's kinds", SpotifyPage.ERROR_KINDS.toSet(), kinds)
        assertTrue("a message is cut", code.contains("String(message || '').slice(0, TEXT_MAX)") && code.contains("var TEXT_MAX = ${SpotifyPage.MESSAGE_MAX};"))
    }

    @Test
    fun `host to page - window vibb has exactly the entry points`() {
        val block = code.substringAfter("window.vibb = {").substringBefore("\n  };")
        val defined = Regex("(?m)^    ([a-zA-Z]+): function").findAll(block).map { it.groupValues[1] }.toList()
        assertEquals(SpotifyPage.TO_PAGE, defined)
        assertEquals("the call builder names only these", "vibb.disconnect()", SpotifyPage.call("disconnect"))
    }

    @Test
    fun `the player - Spotify's documented constructor, our session not the SDK's, the token only to its callback`() {
        assertTrue(code.contains("window.onSpotifyWebPlaybackSDKReady = function"))
        assertTrue(code.contains("new window.Spotify.Player({"))
        val options = code.substringAfter("new window.Spotify.Player({").substringBefore("});")
        assertEquals(listOf("name", "getOAuthToken", "volume"), Regex("(?m)^      ([a-zA-Z]+):").findAll(options).map { it.groupValues[1] }.toList())
        assertTrue("full volume but for the fade", options.contains("volume: 1.0"))
        assertFalse("enableMediaSession stays off: our Media3 session is the one", code.contains("enableMediaSession"))
        assertTrue("getOAuthToken asks the host", options.contains("bridge.needToken(id)"))
        assertTrue("and the host's answer goes only to the callback", code.contains("if (cb) cb(typeof token === 'string' ? token : null);"))
        val events = Regex("addListener\\('([a-z_]+)'").findAll(code).map { it.groupValues[1] }.toSet()
        assertEquals(
            setOf("ready", "not_ready", "player_state_changed", "autoplay_failed", "initialization_error", "authentication_error", "account_error", "playback_error"),
            events,
        )
        assertTrue(code.contains("player.activateElement()"))
        for (banned in listOf("console.", "localStorage", "sessionStorage", "indexedDB", "document.cookie", "innerHTML", "eval(", "Function(", "fetch(",
            "XMLHttpRequest", "WebSocket", "http://", "https://", "postMessage", "location.", "setTimeout(")) {
            assertFalse("player.js uses $banned", code.contains(banned))
        }
    }

    @Test
    fun `the state post - exactly SdkState's keys, three neighbours, every 5 s while playing`() {
        val compact = code.substringAfter("function compact(s) {").substringBefore("\n  }\n")
        val keys = Regex("(?m)^      ([a-z]+):").findAll(compact.substringAfter("return {")).map { it.groupValues[1] }.toList()
        assertEquals(SdkState.KEYS, keys)
        assertTrue(compact.contains("w.next_tracks") && compact.contains("w.previous_tracks") && compact.contains("t.linked_from"))
        assertTrue(code.contains("var NEIGHBOURS = ${SdkState.NEIGHBOURS};"))
        assertTrue("the 5 s post", code.contains("var TICK_MS = 5000;") && code.contains("setInterval(poll, TICK_MS)"))
        assertTrue("started by a playing state, cleared by a paused or null one", code.contains("if (c && !c.paused) {") && code.contains("clearInterval(tick);"))
        assertTrue("null is posted as null", code.contains("if (!s) return null;") && code.contains("bridge.state(JSON.stringify(c))"))
        val maxPost = 11 * 40 + 200 * (2 + 10) + 64 * 9 + 400
        assertTrue("a post fits the host's 8 KB with every field at its cap", maxPost * 2 < SdkState.MAX_BYTES)
    }

    @Test
    fun `a script that fails to load is reported without an inline handler`() {
        assertTrue(code.contains("window.addEventListener('error', function (e) {"))
        assertTrue(code.contains("target.tagName === 'SCRIPT'") && code.contains("report('script'"))
    }
}
