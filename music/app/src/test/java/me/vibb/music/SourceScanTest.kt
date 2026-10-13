package me.vibb.music

import me.vibb.music.Fixtures.moduleFile
import me.vibb.music.ui.MusicGlyphs
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Checks over the sources and resources: translations, the escape scan (design 21 §4.2: no
 * settings, links, share or web views), the debug feed kept out of everything but src/debug, the
 * category glyphs against music_icons.json, and Storytel's secrets (design 26b §0, §6.1, QA L9).
 */
class SourceScanTest {
    private companion object {
        /** The manifest's two WebView meta-data names (design 26c §5.2). */
        const val WEBVIEW_META_SAFE_BROWSING = "android:name=\"android.webkit.WebView.EnableSafeBrowsing"
        const val WEBVIEW_META_METRICS = "android:name=\"android.webkit.WebView.MetricsOptOut"
    }

    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun sources(path: String) =
        moduleFile(path).walk().filter { it.isFile && it.extension in setOf("kt", "java", "xml") }.toList()

    // Translations (as the launcher's TranslationsTest).

    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

    private fun strings(path: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(moduleFile(path))
        val result = mutableMapOf<String, String>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val e = strings.item(i) as org.w3c.dom.Element
            result[e.getAttribute("name")] = e.textContent
        }
        val plurals = doc.getElementsByTagName("item")
        for (i in 0 until plurals.length) {
            val e = plurals.item(i) as org.w3c.dom.Element
            val parent = e.parentNode as org.w3c.dom.Element
            result["${parent.getAttribute("name")}[${e.getAttribute("quantity")}]"] = e.textContent
        }
        return result
    }

    @Test
    fun `nb translates every string with the same placeholders`() {
        val en = strings("src/main/res/values/strings.xml")
        val nb = strings("src/main/res/values-nb/strings.xml")
        assertTrue(en.size > 50)
        assertEquals(en.keys, nb.keys)
        fun specifiers(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()
        for ((name, text) in en) assertEquals(name, specifiers(text), specifiers(nb.getValue(name)))
    }

    // The escape scan (like the launcher's KidScreensEscapeTest).

    @Test
    fun `the app opens no settings, links, share sheets or web views`() {
        val banned = listOf(
            "Settings.ACTION_", "Settings.Panel", "Intent.createChooser", "ACTION_SEND", "ACTION_VIEW", "WebView",
            "CustomTabs", "\"android.settings.", "\"android.intent.action.VIEW", "\"android.intent.action.SEND",
            "\"android.intent.action.CHOOSER", "Uri.parse(\"http", "openUri", "LocalUriHandler",
        )
        val files = sources("src/main/java") + sources("src/main/AndroidManifest.xml")
        assertTrue(files.size > 30)
        for (f in files) {
            // design 26c §7.2: the one web view is Spotify's headless player (the next test) - its file is scanned
            // for everything but the word WebView (qa-26c-step5a-code L7) - and the manifest names the WebView
            // only in its two meta-data (Safe Browsing and metrics off)
            val tokens = if (f.name == "SpotifyWebHost.kt") banned - "WebView" else banned
            val text = code(f).replace(WEBVIEW_META_SAFE_BROWSING, "").replace(WEBVIEW_META_METRICS, "")
            for (token in tokens) if (text.contains(token)) fail("${f.name} references $token")
        }
    }

    /**
     * Design 26c §5.2, §7.2: "no web views" became "the one WebView is SpotifyWebHost's, never attached to a
     * window" - `WebView(` only in that file, no `addView` anywhere, its file and content access off, Safe
     * Browsing and metrics off, `addJavascriptInterface` only there, navigation and new windows refused, the
     * protected-media permission only, and every request through WebPolicy (the frames' and the service
     * workers').
     */
    @Test
    fun `the one web view is SpotifyWebHost's, headless and locked down`() {
        val main = sources("src/main/java").filter { it.extension == "kt" }
        for (f in main) {
            val text = code(f)
            if (f.name != "SpotifyWebHost.kt") {
                assertFalse("${f.name} makes a WebView", text.contains("WebView("))
                assertFalse("${f.name} adds a JavaScript interface", text.contains("addJavascriptInterface"))
                assertFalse("${f.name} names android.webkit", text.contains("android.webkit"))
            }
            assertFalse("${f.name}: the web player is never on screen", text.contains("addView"))
        }
        val host = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyWebHost.kt"))
        // the page's, and a throwaway one only to clear the HTTP cache with no page alive (qa-26c-step5a-code L6)
        assertEquals("the page's WebView and the clear's", 2, Regex("""\bWebView\(""").findAll(host).count())
        val clear = host.substringAfter("override fun clearStorage(done: () -> Unit) {").substringBefore("\n    }\n")
        assertTrue(clear.contains("val tmp = WebView(app)\n            tmp.clearCache(true)\n            tmp.destroy()"))
        assertTrue("the next page waits for the cookies' clear", clear.contains("cookies.removeAllCookies {") && clear.contains("main.post(done)"))
        for (setting in listOf(
            "s.setAllowFileAccess(false)", "s.setAllowContentAccess(false)", "s.setGeolocationEnabled(false)",
            "s.setSupportMultipleWindows(false)", "s.safeBrowsingEnabled = false", "s.mediaPlaybackRequiresUserGesture = false",
            "v.addJavascriptInterface(PageBridge(gen), SpotifyPage.BRIDGE)",
            "override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = true",
            "WebPolicy.current().decide(url)", "val controller = ServiceWorkerController.getInstance()", "controller.setServiceWorkerClient(",
            "PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID", "request.deny()",
            "sw.setAllowFileAccess(false)", "sw.setAllowContentAccess(false)",
            "v.loadDataWithBaseURL(SpotifyPage.BASE_URL, page, \"text/html\", \"utf-8\", null)",
        )) {
            assertTrue("SpotifyWebHost.kt: $setting", host.contains(setting))
        }
        assertFalse("never a file or content URL allowed", host.contains("setAllowFileAccess(true)") || host.contains("setAllowContentAccess(true)"))
        assertFalse("never androidx.webkit (no new dependency, §11)", host.contains("androidx.webkit"))
        assertFalse("never mixed content", host.contains("mixedContentMode"))
        val manifest = code(moduleFile("src/main/AndroidManifest.xml"))
        assertTrue(manifest.contains(WEBVIEW_META_SAFE_BROWSING) && manifest.contains(WEBVIEW_META_METRICS))
        assertTrue(manifest.contains("$WEBVIEW_META_SAFE_BROWSING\"\n            android:value=\"false\""))
        assertTrue(manifest.contains("$WEBVIEW_META_METRICS\"\n            android:value=\"true\""))
        // the page bridge's methods are the page's (PlayerJsTest holds player.js to the same names)
        val bridge = host.substringAfter("private inner class PageBridge(").substringBefore("\n    }\n")
        // the SDK's own error text reaches the log only in a debug build (§7.1, qa-26c-step5a-code L8)
        val renderer = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt"))
        val messageLines = renderer.lines().filter { it.contains("\$message") }
        assertEquals(1, messageLines.size)
        assertTrue(messageLines.single(), messageLines.single().contains("if (BuildConfig.DEBUG) \": \$message\" else \"\""))
        val methods = Regex("""@JavascriptInterface\s+fun ([a-zA-Z]+)\(""").findAll(bridge).map { it.groupValues[1] }.toList()
        assertEquals(me.vibb.music.spotify.SpotifyPage.FROM_PAGE, methods)
        assertEquals("only the bridge is exposed", methods.size, Regex("@JavascriptInterface").findAll(host).count())
    }

    /** The episode carousel (design 27) is part of the player: no new screen, nothing it opens. */
    @Test
    fun `the screens are still the five of the mockup`() {
        assertEquals(listOf("LIBRARY", "PLAYER", "LIST", "NEWS", "SEARCH"), me.vibb.music.ui.Screen.entries.map { it.name })
        assertTrue(moduleFile("src/main/java/me/vibb/music/ui/EpisodeCarousel.kt").isFile)
    }

    /** The launcher counts no screen time while the player is in front (design 21 §3, QA #5): that is
     * safe only because the auto-lock still ends a player left on - the app never keeps the screen on. */
    @Test
    fun `the app never keeps the screen on`() {
        val banned = listOf("keepScreenOn", "KEEP_SCREEN_ON", "SCREEN_BRIGHT_WAKE_LOCK", "SCREEN_DIM_WAKE_LOCK", "FULL_WAKE_LOCK", "setTurnScreenOn", "turnScreenOn")
        for (f in sources("src/main")) {
            val text = code(f)
            for (token in banned) if (text.contains(token)) fail("${f.name} references $token")
        }
    }

    /**
     * Design 26a §6.5: the launcher's KidVpnService is the always-on VPN without `allowBypass()`, so
     * Android refuses to bind an app's socket to any other network (EPERM) - a Sonos socket bound to
     * the Wi-Fi `Network` would fail every call while the filter is on. Wi-Fi is a gate only; the LAN
     * and the SSDP multicast leave on the default route, which the VPN (192.0.2.2/32 only) never takes.
     */
    @Test
    fun `no socket is bound to a Network - the always-on VPN refuses it`() {
        val banned = listOf(
            "bindSocket", "socketFactory(", ".socketFactory", "SocketFactory()", "etSocketFactory", "bindProcessToNetwork",
            "setProcessDefaultNetwork", ".openConnection(",
        )
        // every source set the app is built from (src/debug too: a debug-only helper ships in debug builds)
        val sets = moduleFile("src").listFiles()!!.filter { it.isDirectory && it.name != "test" && !it.name.startsWith("androidTest") }
        assertTrue(sets.any { it.name == "debug" })
        val files = sets.flatMap { set -> set.walk().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
        assertTrue(files.size > 30)
        for (f in files) {
            val text = code(f)
            for (token in banned) if (text.contains(token)) fail("${f.name} references $token")
        }
    }

    /**
     * Android's regex is ICU, not the JVM's: `(?U)` (UNICODE_CHARACTER_CLASS) compiles in these JVM tests and throws
     * PatternSyntaxException on the phone - in Report.kt it failed every sync's report (2026-10-13). Spell the class out.
     */
    @Test
    fun `no regex uses flags Android's ICU rejects`() {
        val banned = listOf("(?U)", "UNICODE_CHARACTER_CLASS")
        val files = moduleFile("src").listFiles()!!.filter { it.isDirectory && it.name != "test" && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walk().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
        assertTrue(files.size > 30)
        for (f in files) {
            val text = code(f)
            for (token in banned) if (text.contains(token)) fail("${f.name} uses $token")
        }
    }

    // The debug feed (like the launcher's DebugHookAbsentTest); the built APKs are checked too
    // (checkReleaseHasNoDebugFeed, a dependency of assembleRelease).

    @Test
    fun `nothing outside src-debug names the debug feed`() {
        val outside = sources("src/main") + (if (File("src/release").exists() || File("app/src/release").exists()) sources("src/release") else emptyList())
        for (f in outside) {
            for (banned in listOf("DebugFeedReceiver", "debugfeed", "debug-feed")) {
                assertFalse("${f.path} names $banned", f.readText().contains(banned))
            }
        }
    }

    @Test
    fun `only the debug manifest declares the feed, guarded by DUMP`() {
        val debug = code(moduleFile("src/debug/AndroidManifest.xml"))
        val receiver = debug.substringAfter("android:name=\"me.vibb.music.debugfeed.DebugFeedReceiver\"").substringBefore("/>")
        assertTrue(receiver, receiver.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugFeedReceiver.kt").isFile)
    }

    // Category glyphs (generated by scripts/material-symbols.sh).

    @Test
    fun `the glyph table matches music_icons json and every drawable exists`() {
        val json = Json.parseToJsonElement(Fixtures.resource("music_icons.json")).jsonObject
        val icons = json.getValue("icons").jsonObject.keys
        assertEquals(icons, MusicGlyphs.ICONS.keys)
        for (key in icons) assertTrue(key, moduleFile("src/main/res/drawable/music_glyph_$key.xml").isFile)
        val colors = json.getValue("colors").jsonObject
        assertEquals(colors.keys, MusicGlyphs.COLORS.keys)
        for ((key, value) in colors) {
            val hex = value.jsonPrimitive.content.removePrefix("#").toLong(16).toInt() or 0xFF000000.toInt()
            assertEquals(key, hex, MusicGlyphs.COLORS.getValue(key))
        }
    }

    // Storytel (design 26b §0, §3.5, §6.1; QA L9, M2).

    /** Design 26c §5.8: the player's Spotify lines as the app spells them in bokmål. */
    @Test
    fun `nb has Spotify's renderer lines`() {
        val nb = strings("src/main/res/values-nb/strings.xml")
        assertEquals("Spotify - trenger Wi-Fi", nb["renderer_spotify_needs_wifi"])
        assertEquals("Spotify - trenger internett", nb["renderer_spotify_needs_internet"])
        assertEquals("Spotify er ikke tilgjengelig nå", nb["renderer_spotify_unavailable"])
        assertEquals("Spotify - spør en voksen", nb["renderer_spotify_ask_adult"])
        assertEquals("Spotify virker ikke på telefonen", nb["renderer_spotify_not_on_phone"])
        assertEquals("Spotify spiller et annet sted", nb["renderer_spotify_elsewhere"])
        assertEquals("Spotify stoppet - trykk spill", nb["renderer_spotify_stopped"])
        val screen = code(moduleFile("src/main/java/me/vibb/music/ui/PlayerScreen.kt"))
        for (state in me.vibb.music.playback.RendererState.entries) {
            assertTrue("$state has its line", screen.contains("RendererState.${state.name} -> R.string.renderer_"))
        }
    }

    @Test
    fun `nb has Storytel's words`() {
        val nb = strings("src/main/res/values-nb/strings.xml")
        assertEquals("Lydbok", nb["kind_audiobook"])
        assertEquals("%1\$s · del %2\$d av %3\$d", nb["hint_part"])
        assertEquals("Del %1\$d av %2\$d", nb["part_of"])
        assertEquals("Ny bok", nb["new_book"])
    }

    /**
     * The body of the class [declaration] matches, the match ending at that class's own opening brace
     * (qa-26b-step1-code L2: never the next class's); a class without a body fails.
     */
    private fun body(text: String, declaration: Regex): String {
        val open = declaration.find(text)?.range?.last ?: throw AssertionError("no body for ${declaration.pattern}")
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> if (--depth == 0) return text.substring(open, i + 1)
            }
        }
        return text.substring(open)
    }

    @Test
    fun `every type holding a Storytel secret prints it redacted`() {
        val holders = mapOf(
            "src/main/java/me/vibb/music/bridge/StorytelLogin.kt" to listOf("data class Login("),
            "src/main/java/me/vibb/music/storytel/StorytelHttp.kt" to listOf("data class StorytelRequest(", "class StorytelResponse("),
            "src/main/java/me/vibb/music/storytel/StorytelApi.kt" to listOf("class Url(", "private class Jwt("),
            // design 26b step 4b: a Storytel play's signed URL in memory - the body, the result, a snapshot, a hint
            "src/main/java/me/vibb/music/sonos/PlayBody.kt" to listOf("data class PlayBody("),
            "src/main/java/me/vibb/music/sonos/SonosTransport.kt" to listOf("data class PlayResult("),
            "src/main/java/me/vibb/music/sonos/SonosSession.kt" to listOf("data class Moved(", "data class Fields(", "data class PositionInfo("),
        )
        for ((path, classes) in holders) {
            val text = code(moduleFile(path))
            for (c in classes) {
                val b = body(text, Regex(Regex.escape(c) + "[^)]*\\)\\s*(:[^{\\n]*)?\\{"))
                assertTrue("$path: $c needs an explicit toString()", b.contains("override fun toString()"))
            }
        }
    }

    @Test
    fun `the bearer header is set in StorytelApi and SpotifyApi only, and the Storytel client never follows the asset's redirect`() {
        for (f in sources("src/main")) {
            val text = code(f)
            for (token in listOf("Authorization", "Bearer")) {
                // design 26c §7.1: the Spotify access token rides only in SpotifyApi's header
                if (text.contains(token)) assertTrue("${f.path} names $token", f.name in setOf("StorytelApi.kt", "SpotifyApi.kt"))
            }
            assertFalse("${f.path}: positions live in Room, never a destructive migration", text.contains("fallbackToDestructiveMigration"))
        }
        val http = code(moduleFile("src/main/java/me/vibb/music/storytel/StorytelHttp.kt"))
        assertTrue(http.contains(".followRedirects(request.follow)"))
        assertTrue(http.contains(".followSslRedirects(request.follow)"))
        assertFalse("never a logging interceptor", http.contains("Interceptor"))
    }

    @Test
    fun `no log line in storytel or sync interpolates a secret`() {
        val secret = Regex("\\$\\{?[^\"]*?(password|email|jwt|bearer|token|location|url|pwd)", RegexOption.IGNORE_CASE)
        val files = sources("src/main/java/me/vibb/music/storytel") + sources("src/main/java/me/vibb/music/sync")
        assertTrue(files.size >= 5)
        for (f in files) {
            for (line in code(f).lines().filter { it.contains("Log.") || it.contains("println(") }) {
                assertFalse("${f.name}: $line", secret.containsMatchIn(line))
            }
        }
        assertFalse("the Storytel client logs nothing", sources("src/main/java/me/vibb/music/storytel").any { code(it).contains("Log.") })
    }

    @Test
    fun `the debug base is assigned only in src-debug and read only by its four readers (M2)`() {
        for (f in sources("src/main")) {
            // Storytel's switch and Spotify's (design 26c §9.3, the next test), each in its own endpoints file
            if (code(f).contains("debugBase")) assertTrue(f.path, f.name in setOf("StorytelEndpoints.kt", "SpotifyEndpoints.kt"))
        }
        val endpoints = code(moduleFile("src/main/java/me/vibb/music/storytel/StorytelEndpoints.kt"))
        assertTrue(endpoints.contains("var debugBase: String? = null"))
        assertEquals("one declaration and four reads", 5, Regex("\\bdebugBase\\b").findAll(endpoints).count())
        assertFalse("main never assigns it", Regex("debugBase\\s*=[^=]").containsMatchIn(endpoints.replace("var debugBase: String? = null", "")))
        // (a) the endpoints and (b) the LAN client for the API: the account; (c) a Storytel download's
        // public-only switch: the download; (d) the minted-URL check on the Sonos path (step 4b).
        val readers = mapOf(
            "endpoints" to setOf("StorytelAccount.kt"),
            "apiOverLan" to setOf("StorytelAccount.kt"),
            "publicDownloads" to setOf("Downloads.kt"),
            "privateMintAllowed" to setOf("SonosController.kt"),
        )
        for ((reader, allowed) in readers) {
            assertTrue("no reader $reader", endpoints.contains("fun $reader()"))
            val body = endpoints.substringAfter("fun $reader()").split(Regex("\\n    (private )?fun ")).first()
            assertTrue("$reader reads the switch", body.contains("debugBase"))
            for (f in sources("src/main/java")) {
                if (code(f).contains("StorytelEndpoints.$reader(")) assertTrue("${f.name} reads $reader", f.name in allowed)
            }
        }
        // every assignment of a debug base in src/debug, qualified or not (qa-26c-step5a-code L7): Storytel's in
        // DebugStorytel.kt, Spotify's in DebugSpotify.kt, nothing else
        val assignments = sources("src/debug").flatMap { f ->
            Regex("""([A-Za-z_.]*)\bdebugBase\s*=[^=]""").findAll(code(f)).map { f.name to it.groupValues[1] }.toList()
        }.sortedBy { it.first }
        assertEquals(
            "only the debug feed assigns them",
            listOf("DebugSpotify.kt" to "SpotifyEndpoints.", "DebugStorytel.kt" to "StorytelEndpoints."),
            assignments,
        )
    }

    /**
     * Spotify's debug switch (design 26c §9.3, QA M3: 26b M2's way again): one declaration in
     * SpotifyEndpoints.kt, never assigned outside src/debug (DebugSpotify.kt, step 5a), and exactly six
     * readers there, each taking the base only through the fake-homes parse. Who may call each reader:
     * the WebView host and the renderer are step 5a's files.
     */
    @Test
    fun `Spotify's debug base is assigned only in src-debug and read only by its six readers (26c M3)`() {
        val endpoints = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyEndpoints.kt"))
        assertTrue(endpoints.contains("var debugBase: String? = null"))
        assertEquals("one declaration and six reads", 7, Regex("\\bdebugBase\\b").findAll(endpoints).count())
        assertFalse("main never assigns it", Regex("debugBase\\s*=[^=]").containsMatchIn(endpoints.replace("var debugBase: String? = null", "")))
        val readers = mapOf(
            "apiBase" to setOf("SpotifyRenderer.kt"),
            "apiOverLan" to setOf("SpotifyRenderer.kt"),
            "sdkUrl" to setOf("SpotifyWebHost.kt"),
            "webHosts" to setOf("WebPolicy.kt"),
            "webProxy" to setOf("WebPolicy.kt"),
            "debugTokens" to setOf("SpotifyRenderer.kt"),
        )
        for ((reader, allowed) in readers) {
            assertTrue("no reader $reader", endpoints.contains("fun $reader("))
            val body = endpoints.substringAfter("fun $reader(").split(Regex("\\n    (private )?fun ")).first()
            assertTrue("$reader reads the switch through the fake-homes parse", body.contains("parse(debugBase)"))
            for (f in sources("src/main/java")) {
                if (code(f).contains("SpotifyEndpoints.$reader(") || code(f).contains("SpotifyEndpoints::$reader")) {
                    assertTrue("${f.name} reads $reader", f.name in allowed)
                }
            }
        }
        val assigning = sources("src/debug").filter { Regex("SpotifyEndpoints\\.debugBase\\s*=[^=]").containsMatchIn(code(it)) }.map { it.name }
        assertEquals("only the debug feed assigns it", listOf("DebugSpotify.kt"), assigning)
        // read at every process start too, as Storytel's (DebugStartup)
        val startup = code(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugStorytel.kt")).substringAfter("class DebugStartup")
        assertTrue(startup.contains("DebugSpotify.applyBase(it)"))
        assertTrue(code(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugFeedReceiver.kt")).contains("DebugSpotify.applyBase(context)"))
    }

    /** Design 26c §7.1: the Spotify token's holders print it redacted, and the Spotify code logs nothing (the debug log is step 5a's, fixed words only). */
    @Test
    fun `every type holding a Spotify token prints it redacted, and spotify-slash logs nothing`() {
        val holders = mapOf(
            "src/main/java/me/vibb/music/spotify/SpotifyHttp.kt" to listOf("class SpotifyRequest(", "class SpotifyResponse("),
            "src/main/java/me/vibb/music/bridge/SpotifyBridgeToken.kt" to listOf("data class Ok("),
        )
        for ((path, classes) in holders) {
            val text = code(moduleFile(path))
            for (c in classes) {
                val b = body(text, Regex(Regex.escape(c) + "[^)]*\\)\\s*(:[^{\\n]*)?\\{"))
                assertTrue("$path: $c needs an explicit toString()", b.contains("override fun toString()"))
            }
        }
        val spotify = sources("src/main/java/me/vibb/music/spotify")
        assertTrue(spotify.size >= 9)
        for (f in spotify) {
            assertFalse("${f.name} logs", code(f).contains("Log.") || code(f).contains("println("))
            assertFalse("${f.name}: a data class would print its token", code(f).contains("data class SpotifyRequest") || code(f).contains("data class TokenCache"))
        }
        val http = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyHttp.kt"))
        assertTrue("a Web API call never follows a redirect", http.contains(".followRedirects(false)") && http.contains(".followSslRedirects(false)"))
        assertFalse("never a logging interceptor", http.contains("Interceptor"))
    }

    /**
     * Design 26c step 5b (§7.1, qa-26c-step5a-code L8): the web player is made only by the service, with a log that is
     * a debug build's only and writes the renderer's fixed words as they are; the outcome file is the store's alone
     * and holds a word; the tile and the player say "Spotify" in both languages.
     */
    @Test
    fun `5b - the web player's log is debug-only, it is made by the service alone, and the outcome file holds a word`() {
        val made = sources("src/main/java").filter { code(it).contains("SpotifyRenderer.create(") }.map { it.name }
        assertEquals(listOf("PlaybackService.kt"), made)
        val service = code(moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt"))
        val logs = service.lines().filter { it.contains("Log.") }
        assertEquals(listOf("if (BuildConfig.DEBUG) Log.d(SPOTIFY_LOG_TAG, message)"), logs.map { it.trim() })
        assertTrue(service.contains("log = ::spotifyLog,"))
        // the outcome's file: written by the store only, read by the report through the store's name
        val files = sources("src/main/java").filter { code(it).contains("spotify_state.json") }.map { it.name }
        assertEquals(listOf("Store.kt"), files)
        val store = code(moduleFile("src/main/java/me/vibb/music/data/Store.kt"))
        assertTrue(store.contains("val next = SpotifyOutcome.next(spotifyOutcome(), word, nowMs) ?: return"))
        assertTrue("tmp + rename", store.contains("writeAtomic(spotifyStateFile, next.encode().toByteArray())"))
        val logic = code(moduleFile("src/main/java/me/vibb/music/logic/Spotify.kt"))
        assertTrue("the file is the word and its time", logic.contains("put(\"outcome\", outcome)\n        put(\"at\", atMs)\n    }.toString()"))
        // the attribution and the lines, in both languages
        for (path in listOf("src/main/res/values/strings.xml", "src/main/res/values-nb/strings.xml")) {
            assertEquals(path, "Spotify", strings(path)["kind_spotify"])
        }
    }

    /** qa-26c-step5b-code M2: the WebView's console never reaches logcat; L2: the debug switch never meets a real token. */
    @Test
    fun `5b fix round - the page's console is handled and never logged, and the debug switch sends fake tokens only`() {
        val host = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyWebHost.kt"))
        val console = host.substringAfter("override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {").substringBefore("\n        }\n")
        assertTrue("handled, always", console.trimEnd().endsWith("return true"))
        assertEquals("one return, true", 1, Regex("""\breturn\b""").findAll(console).count())
        assertFalse("never the message", console.contains(".message()"))
        assertTrue("a debug build only", console.contains("if (BuildConfig.DEBUG) log(\"spotify: console "))
        assertTrue("the level and the source's host only", console.contains("consoleMessage.messageLevel()") && console.contains("consoleMessage.sourceId()?.toHttpUrlOrNull()?.host"))
        assertEquals("the one console override", 1, Regex("""onConsoleMessage\(""").findAll(host).count())
        val renderer = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt"))
        val create = renderer.substringAfter("fun create(").substringBefore("\n        }\n")
        assertTrue(create.contains("debugSwitchToken(t).also { if (it !== t) log(\"spotify: a real token with the debug switch - not sent\") }"))
        assertTrue("the bridge's token as it is only without the switch", create.contains("if (fakeTokens == null) {\n                        t\n"))
    }

    /**
     * Design 26c §9.4, step 6 (qa-26c-step5b-code, "the state-recording gap"): the state recorder is a port that is a no-op in
     * the main source set - its one hook null, assigned in src/debug's DebugSpotify.kt alone, switched by config.json's
     * `record_spotify_states` - and no state's JSON ever reaches a log line (§7.1), the recorder's file being src/debug's.
     */
    @Test
    fun `step 6 - the state recorder is a no-op in main, written only by src-debug, and no state JSON reaches a log`() {
        val hook = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRecording.kt"))
        assertTrue(hook.contains("var recorder: SpotifyRecorder? = null"))
        assertFalse("the hook writes nothing itself", hook.contains("File") || hook.contains("write") || hook.contains("Log."))
        // main never assigns the hook; src/debug's DebugSpotify.kt does, and nothing else
        for (f in sources("src/main/java")) {
            assertFalse("${f.name} assigns the recorder", Regex("""recorder\s*=[^=]""").containsMatchIn(code(f).replace("var recorder: SpotifyRecorder? = null", "")))
            assertFalse("${f.name}: the recorder's file is src/debug's", code(f).contains("sdk_states") || code(f).contains("record_spotify_states"))
        }
        val assigning = sources("src/debug").filter { Regex("""SpotifyRecording\.recorder\s*=[^=]""").containsMatchIn(code(it)) }.map { it.name }
        assertEquals(listOf("DebugSpotify.kt"), assigning)
        val debug = code(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugSpotify.kt"))
        assertTrue(debug.contains("config?.get(\"record_spotify_states\")?.jsonPrimitive?.booleanOrNull"))
        assertTrue("debug-feed/sdk_states/<start>.jsonl", debug.contains("File(File(dir, \"sdk_states\"), \"\${stamp()}.jsonl\")"))
        assertTrue(debug.contains("append(\"{\\\"t\\\":\$atMs,\\\"state\\\":\$compact}\")"))
        val logLines = debug.lines().filter { it.contains("Log.") }
        assertTrue("the recorder never logs a state: $logLines", logLines.none { it.contains("json") || it.contains("compact") || it.contains("line") })
        // read at every process start and at the receiver, as the debug base
        assertTrue(code(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugStorytel.kt")).substringAfter("class DebugStartup").contains("DebugSpotify.applyRecorder(it)"))
        assertTrue(code(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugFeedReceiver.kt")).contains("DebugSpotify.applyRecorder(context)"))
        // the port in main: the hook or nothing
        val renderer = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyRenderer.kt"))
        assertTrue(renderer.contains("override fun recordState(json: String) {\n        SpotifyRecording.recorder?.state(now(), json)\n    }"))
        assertTrue(renderer.contains("override fun record(event: String) {\n        SpotifyRecording.recorder?.event(now(), event)\n    }"))
        assertTrue("the posted state's JSON goes to the recorder only", renderer.contains("is HostEvent.State -> e.json?.let { ports.recordState(it) }"))
        assertEquals("one recordState call", 1, Regex("""ports\.recordState\(""").findAll(renderer).count())
        assertTrue("the SDK's error by its kind only", renderer.contains("is HostEvent.Error -> ports.record(\"error \${e.kind}\")"))
        // no state JSON in any log line of the web player: the host passes it on untouched, the renderer logs fixed words
        val host = code(moduleFile("src/main/java/me/vibb/music/spotify/SpotifyWebHost.kt"))
        val bridge = host.substringAfter("fun state(json: String?) {").substringBefore("\n        }\n")
        assertFalse(bridge.contains("log("))
        assertTrue(bridge.contains("post(gen, HostEvent.State(snapshot, json))"))
        assertTrue("the event's text leaves the JSON out", host.contains("override fun toString(): String = \"State(\$snapshot)\""))
        for (f in sources("src/main/java/me/vibb/music/spotify") + moduleFile("src/main/java/me/vibb/music/playback/PlaybackService.kt")) {
            for (line in code(f).lines().filter { Regex("""\blog\(|Log\.""").containsMatchIn(it) }) {
                assertFalse("${f.name}: $line", Regex("""\bjson\b|\.json\b""").containsMatchIn(line))
            }
        }
    }

    @Test
    fun `a Storytel download never follows a redirect, so no token reaches the cache index`() {
        val downloads = code(moduleFile("src/main/java/me/vibb/music/sync/Downloads.kt"))
        assertTrue(downloads.contains("followRedirects = !storytel"))
        val cache = code(moduleFile("src/main/java/me/vibb/music/media/MediaCache.kt"))
        assertTrue(cache.contains(".followRedirects(false).followSslRedirects(false)"))
        assertTrue(cache.contains("OkHttpDataSource.Factory(if (followRedirects) httpClient(publicOnly) else noRedirectClients.getValue(publicOnly))"))
    }

    @Test
    fun `the debug feed's Storytel login is deleted once read, and its email never logged (L13)`() {
        val debug = code(moduleFile("src/debug/java/me/vibb/music/debugfeed/DebugStorytel.kt"))
        val seed = debug.substringAfter("fun seedLogin(").substringBefore("\n    }\n")
        assertTrue(seed.contains("} finally {\n            f.delete()"))
        for (line in debug.lines().filter { it.contains("Log.") }) {
            assertFalse(line, Regex("\\$\\{?(email|password|o\\b)").containsMatchIn(line))
        }
        // qa-26b-step3-code L1: a malformed file never reaches the parser's message (it quotes the password)
        assertTrue(seed.contains("val o = runCatching { JSON.parseToJsonElement(f.readText()) as? JsonObject }.getOrNull()"))
        val manifest = code(moduleFile("src/debug/AndroidManifest.xml"))
        assertTrue(manifest.contains("android:name=\"me.vibb.music.debugfeed.DebugStartup\""))
        val provider = manifest.substringAfter("DebugStartup").substringBefore("/>")
        assertTrue("the start-up provider is not exported", provider.contains("android:exported=\"false\""))
        assertTrue("created before WorkManager's provider (L2)", provider.contains("android:initOrder=\"1000\""))
    }

    /**
     * qa-30-s3b-code H2: an app targeting API 28+ refuses every cleartext request without a config of its own -
     * Sonos SOAP (http://<ip>:1400), http enclosures, `lan` feeds and the emulator's fakes. Cleartext is allowed
     * for every host (a speaker's LAN address can't be named in advance); the app's own address rules decide
     * what may be fetched. The system's CAs only, one config for every build type.
     */
    @Test
    fun `cleartext is allowed for Sonos, http enclosures, lan feeds and the fakes - the system CAs only`() {
        val manifest = moduleFile("src/main/AndroidManifest.xml").readText()
        assertTrue(manifest.contains("android:networkSecurityConfig=\"@xml/network_security_config\""))
        assertFalse("one place decides it", manifest.contains("usesCleartextTraffic"))
        val config = moduleFile("src/main/res/xml/network_security_config.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        assertTrue(config.contains("<base-config cleartextTrafficPermitted=\"true\""))
        assertEquals("system trust anchors only", listOf("system"), Regex("<certificates src=\"(\\w+)\"").findAll(config).map { it.groupValues[1] }.toList())
        for (narrower in listOf("domain-config", "debug-overrides", "pin-set")) assertFalse(narrower, config.contains(narrower))
        val others = moduleFile("src").walk().filter { it.isFile && it.name == "network_security_config.xml" }.map { it.path }.toList()
        assertEquals(others.toString(), 1, others.size)
        for (variant in listOf("src/debug/AndroidManifest.xml")) assertFalse(moduleFile(variant).readText().contains("networkSecurityConfig"))
    }
}
