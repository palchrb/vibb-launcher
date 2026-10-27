package com.kidslauncher.mdm.appdisplay

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 14 (the parent names an app and picks its icon) with QA #1, #2 and #6. */
class AppDisplayTest {
    private val element = "io.element.android.x"

    private fun map(json: String) = appDisplayMap(ServerJson.parseToJsonElement(json))

    @Test
    fun `the server's entries become the map`() {
        assertEquals(
            mapOf(
                element to AppDisplayEntry("Chat", "chat", "peach"),
                "com.spotify.music" to AppDisplayEntry(null, "music_note", AUTO_COLOR),
                "org.example.clock" to AppDisplayEntry("Klokke", null, AUTO_COLOR),
            ),
            map(
                """[
                  {"package_name":"$element","label":"Chat","icon":"chat","color":"peach"},
                  {"package_name":"com.spotify.music","label":null,"icon":"music_note","color":"auto"},
                  {"package_name":"org.example.clock","label":"Klokke","icon":null,"color":"sky"}
                ]""",
            ),
        )
    }

    /** Design 69 §4.4: `on_home: false` alone is an entry (a sync app kept off Home); only JSON `false` is off; an
     * app off Home is listed only while a window is open. */
    @Test
    fun `on_home false keeps an app off Home outside a window`() {
        val off = map(
            """[{"package_name":"at.bitfire.icsdroid","label":null,"icon":null,"color":"auto","on_home":false},
                {"package_name":"at.bitfire.davdroid","label":null,"icon":null,"color":"auto","on_home":"false"},
                {"package_name":"$element","label":"Chat","icon":null,"color":"auto","on_home":true}]""",
        )
        assertEquals(
            mapOf(
                "at.bitfire.icsdroid" to AppDisplayEntry(null, null, AUTO_COLOR, onHome = false),
                element to AppDisplayEntry("Chat", null, AUTO_COLOR),
            ),
            off,
        )
        assertFalse(onHomeListable(off["at.bitfire.icsdroid"], windowOpen = false))
        assertTrue(onHomeListable(off["at.bitfire.icsdroid"], windowOpen = true))
        assertTrue(onHomeListable(off[element], windowOpen = false))
        assertTrue(onHomeListable(null, windowOpen = false))
    }

    /** Design 34 P1 (QA H1): "the app's own logo" is kept with and without a label, always auto. */
    @Test
    fun `the app's own logo is kept with and without a label`() {
        assertEquals(
            mapOf(
                element to AppDisplayEntry("Chat", OWN_ICON, AUTO_COLOR),
                "com.spotify.music" to AppDisplayEntry(null, OWN_ICON, AUTO_COLOR),
            ),
            map(
                """[
                  {"package_name":"$element","label":"Chat","icon":"own","color":"auto"},
                  {"package_name":"com.spotify.music","label":null,"icon":"own","color":"sky"}
                ]""",
            ),
        )
        assertTrue(map("""[{"package_name":"$element","icon":"own"}]""")[element]!!.ownLogo)
        assertFalse(AppDisplayEntry(null, "chat").ownLogo)
        assertFalse(AppDisplayEntry("Chat", null).ownLogo)
        assertTrue(OWN_ICON !in AppGlyphs.ICONS)
    }

    /** An older launcher (its icons = the glyphs alone) drops `own` as unknown: with no label the
     * entry is gone and it draws today's look; with one the label stays (design 14 QA #1). */
    @Test
    fun `an older launcher drops the own logo`() {
        val json = ServerJson.parseToJsonElement(
            """[
              {"package_name":"$element","label":null,"icon":"own","color":"auto"},
              {"package_name":"com.spotify.music","label":"Musikk","icon":"own","color":"auto"}
            ]""",
        )
        assertEquals(
            mapOf("com.spotify.music" to AppDisplayEntry("Musikk", null, AUTO_COLOR)),
            appDisplayMap(json, icons = AppGlyphs.ICONS.keys),
        )
    }

    /** Each bad field alone: only that field is dropped (QA #1). */
    @Test
    fun `a bad field drops only itself`() {
        // A newer server's icon keeps the label; the colour goes with the icon.
        assertEquals(AppDisplayEntry("Chat", null, AUTO_COLOR), map("""[{"package_name":"$element","label":"Chat","icon":"rocket","color":"peach"}]""")[element])
        // A bad label keeps the icon: too long, control characters, a number, blank.
        for (label in listOf("\"${"x".repeat(21)}\"", "\"Ch\\u0007at\"", "5", "\"   \"", "{}")) {
            assertEquals(label, AppDisplayEntry(null, "chat", "peach"), map("""[{"package_name":"$element","label":$label,"icon":"chat","color":"peach"}]""")[element])
        }
        // An unknown, null, missing or non-string colour = auto.
        for (color in listOf("\"green\"", "null", "7")) {
            assertEquals(color, AppDisplayEntry("Chat", "chat", AUTO_COLOR), map("""[{"package_name":"$element","label":"Chat","icon":"chat","color":$color}]""")[element])
        }
        assertEquals(AppDisplayEntry("Chat", "chat", AUTO_COLOR), map("""[{"package_name":"$element","label":"Chat","icon":"chat"}]""")[element])
        // 20 characters is fine, counted as characters; the label is trimmed.
        assertEquals("ø".repeat(20), map("""[{"package_name":"$element","label":" ${"ø".repeat(20)} "}]""")[element]?.label)
    }

    @Test
    fun `entries without a usable package or anything to show are left out`() {
        assertEquals(
            emptyMap<String, AppDisplayEntry>(),
            map(
                """[
                  {"label":"Chat","icon":"chat"},
                  {"package_name":"chat","label":"Chat"},
                  {"package_name":5,"label":"Chat"},
                  {"package_name":"$element","label":null,"icon":null},
                  {"package_name":"org.example.a","label":"${"x".repeat(30)}","icon":"rocket"},
                  "junk", 5, null, []
                ]""",
            ),
        )
        // Not a list at all - from a broken or future server - is no names, never a failure.
        for (json in listOf("{}", "\"x\"", "null", "3")) assertEquals(json, emptyMap<String, AppDisplayEntry>(), map(json))
        assertEquals(emptyMap<String, AppDisplayEntry>(), appDisplayMap(null))
        // The first entry of a package wins.
        assertEquals("A", map("""[{"package_name":"$element","label":"A"},{"package_name":"$element","label":"B"}]""")[element]?.label)
    }

    @Test
    fun `package names follow Android's grammar like the server's`() {
        for (ok in listOf("io.element.android.x", "a.b", "com.example_1.App2")) assertTrue(ok, isValidPackageName(ok))
        for (bad in listOf("", "chat", "1a.b", "a..b", "a.b.", "a.-b", "a b.c", "a.b/c", "æ.b", "a.${"b".repeat(254)}")) {
            assertFalse(bad, isValidPackageName(bad))
        }
    }

    @Test
    fun `the parent's name comes first, then the kid's, then the app's own`() {
        assertEquals("Chat", displayLabel("Chat", "Meldinger", "Element X"))
        assertEquals("Meldinger", displayLabel(null, "Meldinger", "Element X"))
        assertEquals("Element X", displayLabel(null, null, "Element X"))
        assertEquals("Element X", displayLabel(null, " ", "Element X"))
    }

    /** QA #6: Rename only for apps the parent hasn't named (an icon alone doesn't name it). */
    @Test
    fun `the kid may rename only what the parent hasn't named`() {
        assertTrue(kidMayRename(null))
        assertTrue(kidMayRename(AppDisplayEntry(null, "chat")))
        assertFalse(kidMayRename(AppDisplayEntry("Chat", null)))
    }

    /** QA #2: one key for lookup and render; a changed choice is a new key; auto keeps the app. */
    @Test
    fun `the icon key carries the parent's glyph and colour`() {
        assertEquals("app|144|420", appIconKey("app", 144, 420, null))
        assertEquals("app|144|420", appIconKey("app", 144, 420, AppDisplayEntry("Chat", null)))
        assertEquals("app|144|420|g=chat|c=peach", appIconKey("app", 144, 420, AppDisplayEntry(null, "chat", "peach")))
        assertEquals("app|144|420|g=chat|c=auto", appIconKey("app", 144, 420, AppDisplayEntry(null, "chat")))
        assertTrue(appIconKey("app", 144, 420, AppDisplayEntry(null, "chat", "sky")) != appIconKey("app", 144, 420, AppDisplayEntry(null, "chat", "peach")))
        // Design 34 P1: the app's own logo is its own key, apart from Vibb style and every glyph.
        val own = appIconKey("app", 144, 420, AppDisplayEntry(null, OWN_ICON))
        assertEquals("app|144|420|g=own|c=auto", own)
        assertTrue(own.contains("g=own"))
        assertTrue(own != appIconKey("app", 144, 420, null))
        assertEquals(own, appIconKey("app", 144, 420, AppDisplayEntry("Chat", OWN_ICON)))
    }

    @Test
    fun `clean labels`() {
        assertEquals("Chat", cleanDisplayLabel("  Chat "))
        assertNull(cleanDisplayLabel(""))
        assertNull(cleanDisplayLabel(null))
        assertNull(cleanDisplayLabel("a\nb"))
        // An emoji is one character (two UTF-16 units).
        assertEquals("😀".repeat(20), cleanDisplayLabel("😀".repeat(20)))
        assertNull(cleanDisplayLabel("😀".repeat(21)))
    }
}
