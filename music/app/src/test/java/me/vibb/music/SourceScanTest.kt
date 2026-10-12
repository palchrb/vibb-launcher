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
 * settings, links, share or web views), the debug feed kept out of everything but src/debug, and
 * the category glyphs against music_icons.json.
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
}
