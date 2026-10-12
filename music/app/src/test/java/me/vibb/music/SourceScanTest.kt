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
            val text = code(f)
            for (token in banned) if (text.contains(token)) fail("${f.name} references $token")
        }
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
    fun `the bearer header is set in StorytelApi only, and the Storytel client never follows the asset's redirect`() {
        for (f in sources("src/main")) {
            val text = code(f)
            for (token in listOf("Authorization", "Bearer")) {
                if (text.contains(token)) assertEquals("${f.path} names $token", "StorytelApi.kt", f.name)
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
            if (code(f).contains("debugBase")) assertEquals(f.path, "StorytelEndpoints.kt", f.name)
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
        val assigning = sources("src/debug").filter { Regex("debugBase\\s*=[^=]").containsMatchIn(code(it)) }.map { it.name }
        assertEquals("only the debug feed assigns it", listOf("DebugStorytel.kt"), assigning)
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
        val manifest = code(moduleFile("src/debug/AndroidManifest.xml"))
        assertTrue(manifest.contains("android:name=\"me.vibb.music.debugfeed.DebugStartup\""))
        assertTrue("the start-up provider is not exported", manifest.substringAfter("DebugStartup").substringBefore("/>").contains("android:exported=\"false\""))
    }
}
