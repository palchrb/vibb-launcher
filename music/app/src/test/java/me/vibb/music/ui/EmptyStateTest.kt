package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 34 P10 a: the library, Nytt and search say kindly that there is nothing yet - a quiet disc with a glyph, the
 * words, nothing that moves or asks for attention, no source named.
 */
class EmptyStateTest {
    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([a-z_0-9]+)"[^>]*>([^<]*)</string>""").findAll(moduleFile(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private val nb = strings("src/main/res/values-nb/strings.xml")
    private val en = strings("src/main/res/values/strings.xml")

    @Test
    fun `the design's words, in nb and en`() {
        assertEquals("Her kommer musikken din", nb["no_library"])
        assertEquals("Mamma eller pappa legger til musikk, podkaster og bøker. De dukker opp her av seg selv.", nb["no_library_body"])
        assertEquals("Your music will appear here", en["no_library"])
        assertEquals("Mum or dad adds music, podcasts and books. They show up here by themselves.", en["no_library_body"])
        // Nytt and search keep today's words
        assertEquals("Ingenting nytt akkurat nå", nb["nothing_new"])
        assertEquals("Fant ingenting", nb["nothing_found"])
        assertEquals("Nothing new right now", en["nothing_new"])
        assertEquals("Nothing found", en["nothing_found"])
        for (key in listOf("no_library", "no_library_body", "nothing_new", "nothing_found")) {
            for ((lang, values) in listOf("nb" to nb, "en" to en)) {
                val text = values.getValue(key)
                assertFalse("$lang $key names no source", text.contains("spotify", ignoreCase = true))
                assertFalse("$lang $key is calm: no exclamation", text.contains("!"))
            }
        }
    }

    @Test
    fun `each screen shows the disc with its glyph`() {
        val library = moduleFile("src/main/java/me/vibb/music/ui/LibraryScreen.kt").readText()
        assertTrue(library.contains("EmptyState(\n                Icons.headphones,\n                stringResource(R.string.no_library),\n                stringResource(R.string.no_library_body),"))
        val lists = moduleFile("src/main/java/me/vibb/music/ui/ListScreens.kt").readText()
        assertTrue(lists.contains("EmptyState(Icons.bell, stringResource(R.string.nothing_new), null, Modifier.weight(1f).fillMaxWidth())"))
        assertTrue(
            "search from the top, above the keyboard",
            lists.contains("EmptyState(Icons.search, stringResource(R.string.nothing_found), null, Modifier.weight(1f).fillMaxWidth(), centred = false)"),
        )
        assertEquals("each empty text is drawn by EmptyState only", 0, Regex("Line\\(stringResource\\(R.string.(no_library|nothing_new|nothing_found)").findAll(library + lists).count())
    }

    @Test
    fun `the disc is decorative, the texts read as one, nothing moves`() {
        val widgets = moduleFile("src/main/java/me/vibb/music/ui/Widgets.kt").readText()
        val empty = widgets.substringAfter("fun EmptyState(").substringBefore("\n}\n")
        assertEquals(104, EMPTY_DISC_DP)
        assertTrue("the night disc, P2's soft shadow", empty.contains("Modifier.size(EMPTY_DISC_DP.dp).shadow(BAR_ELEVATION_DP.dp, CircleShape).background(Palette.card)"))
        assertTrue("the glyph in peach, through Glyph (no description)", empty.contains("Glyph(icon, EMPTY_GLYPH_DP.dp, Palette.peach)"))
        assertTrue(widgets.contains("Image(icon, contentDescription = null, modifier = modifier.size(size)"))
        assertFalse("no description on the disc (QA L7)", empty.contains("contentDescription"))
        assertTrue("TalkBack reads the headline and the body together", empty.contains(".semantics(mergeDescendants = true) {}"))
        assertTrue("words wrap, never cut", empty.contains("maxLines = Int.MAX_VALUE, center = true"))
        assertTrue("a large font scrolls, never overlaps", empty.contains(".verticalScroll(rememberScrollState()).heightIn(min = maxHeight)"))
        for (moving in listOf("animate", "Animat", "infiniteRepeatable", "rememberInfiniteTransition", "clickable", "button(")) {
            assertFalse("nothing moves or taps: $moving", empty.contains(moving))
        }
    }
}
