package me.vibb.music.ui

import me.vibb.music.Fixtures.moduleFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 34 P10 a: the library, Nytt and search say kindly that there is nothing yet - a quiet disc with a glyph, the
 * words, nothing that moves or asks for attention, no source named. Source checks see the code with its whitespace
 * collapsed (qa-34-s11-code L2), so they pin what it does, not how it is laid out.
 */
class EmptyStateTest {
    private fun strings(path: String): Map<String, String> =
        Regex("""<string name="([a-z_0-9]+)"[^>]*>([^<]*)</string>""").findAll(moduleFile(path).readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'") }

    private val nb = strings("src/main/res/values-nb/strings.xml")
    private val en = strings("src/main/res/values/strings.xml")

    private fun flat(path: String) = moduleFile(path).readText().replace(Regex("\\s+"), " ")

    /** Every `EmptyState(...)` call's arguments in [text] (balanced parentheses). */
    private fun calls(text: String): List<String> = Regex("EmptyState\\(").findAll(text).mapNotNull { m ->
        var depth = 1
        var i = m.range.last + 1
        while (i < text.length && depth > 0) {
            if (text[i] == '(') depth++
            if (text[i] == ')') depth--
            i++
        }
        text.substring(m.range.last + 1, i - 1).trim().takeIf { !text.substring(0, m.range.first).endsWith("fun ") }
    }.toList()

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
    fun `each screen shows the disc with its glyph - the library and Nytt centred, search from the top`() {
        val library = calls(flat("src/main/java/me/vibb/music/ui/LibraryScreen.kt"))
        val lists = calls(flat("src/main/java/me/vibb/music/ui/ListScreens.kt"))
        assertEquals(1, library.size)
        assertEquals(2, lists.size)
        val (news, search) = lists.partition { it.contains("Icons.bell") }.let { it.first.single() to it.second.single() }
        assertTrue(library[0].startsWith("Icons.headphones, stringResource(R.string.no_library), stringResource(R.string.no_library_body),"))
        assertTrue(news.startsWith("Icons.bell, stringResource(R.string.nothing_new), null,"))
        assertTrue(search.startsWith("Icons.search, stringResource(R.string.nothing_found), null,"))
        assertFalse("the library is centred (the default)", library[0].contains("centred"))
        assertFalse("Nytt is centred (the default)", news.contains("centred"))
        assertTrue("search starts from the top, above the keyboard", search.contains("centred = false"))
        val widgets = flat("src/main/java/me/vibb/music/ui/Widgets.kt")
        assertTrue("centred is the default", widgets.contains("centred: Boolean = true"))
        assertTrue("centred means centred", widgets.contains("if (centred) Arrangement.Center else Arrangement.Top"))
    }

    @Test
    fun `the disc is decorative, the texts read as one, nothing moves`() {
        val widgets = flat("src/main/java/me/vibb/music/ui/Widgets.kt")
        val empty = widgets.substringAfter("fun EmptyState(").substringBefore(" } } } ")
        assertEquals(104, EMPTY_DISC_DP)
        assertTrue("the glyph through Glyph, which has no description", empty.contains("Glyph(icon,") && widgets.contains("Image(icon, contentDescription = null,"))
        assertFalse("no description on the disc (QA L7)", empty.contains("contentDescription"))
        assertTrue("TalkBack reads the headline and the body together", empty.contains("mergeDescendants = true"))
        for (moving in listOf("animate", "Animat", "infiniteRepeatable", "rememberInfiniteTransition", "clickable", "button(")) {
            assertFalse("nothing moves or taps: $moving", empty.contains(moving))
        }
    }
}
