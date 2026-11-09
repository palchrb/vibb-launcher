package com.kidslauncher.mdm.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale

/** Design 79 §4.2: the search fold, the match and its highlight's indices - and that nothing that decides calls it. */
class SearchFoldTest {

    @Test
    fun `the fold's table`() {
        val table = mapOf(
            "Ørjan" to "orjan", "Åse" to "ase", "Ærlig" to "aerlig", "Hélène" to "helene", "Œdipus" to "oedipus",
            "Straße" to "strasse", "Đorđe" to "dorde", "Łukasz" to "lukasz", "Þór" to "thor", "Iıİ" to "iii",
            "Anne-Marie" to "anne-marie", "O'Neil" to "o'neil", "Mamma Mia" to "mamma mia",
        )
        for ((source, folded) in table) assertEquals(source, folded, searchFold(source))
    }

    @Test
    fun `o finds Ørjan, a finds Åse, e finds Hélène`() {
        assertEquals(SearchMatch(MatchRank.PREFIX, 0, 1), searchMatch("Ørjan", "o"))
        assertEquals(SearchMatch(MatchRank.PREFIX, 0, 1), searchMatch("Åse", "a"))
        assertEquals(SearchMatch(MatchRank.INSIDE, 1, 2), searchMatch("Hélène", "e"))
    }

    /** Code QA M3 (the coordinator's decision): one way - the name folds, the query keeps Æ, Ø, Å and marked letters. */
    @Test
    fun `one way - o finds Ø names, but ø finds only Ø and å never finds Anna`() {
        val names = listOf("Ola", "Ørjan", "Sofie Østby", "Anna", "Åse", "Ærlig", "Hélène", "Helene")
        fun hits(query: String) = names.filter { searchMatch(it, query) != null }
        assertEquals(listOf("Ola", "Ørjan", "Sofie Østby"), hits("o"))
        assertEquals(listOf("Ørjan", "Sofie Østby"), hits("ø"))
        assertEquals(listOf("Ørjan", "Sofie Østby"), hits("Ø"))
        assertNull(searchMatch("Ola", "øl"))
        assertEquals(listOf("Åse"), hits("å"))
        assertEquals(listOf("Ola", "Ørjan", "Anna", "Åse", "Ærlig"), hits("a"))
        assertEquals(listOf("Ærlig"), hits("æ"))
        assertEquals(listOf("Ærlig"), hits("aer"))
        assertEquals(listOf("Hélène"), hits("hé"))
        assertEquals(listOf("Hélène", "Helene"), hits("he"))
    }

    @Test
    fun `the span is on the shown string, over a decomposed letter and a letter that grows`() {
        val decomposed = "Björn" // "Björn" with a combining diaeresis
        assertEquals("bjorn", searchFold(decomposed))
        // "jö" covers the o and its mark: the span ends after the mark.
        assertEquals(SearchMatch(MatchRank.INSIDE, 1, 4), searchMatch(decomposed, "jo"))
        assertEquals(SearchMatch(MatchRank.PREFIX, 0, decomposed.length), searchMatch(decomposed, "björn"))
        // "æ" folds to two letters: half of it still highlights the whole letter, never an empty or reversed span.
        assertEquals(SearchMatch(MatchRank.PREFIX, 0, 1), searchMatch("Ærlig", "a"))
        assertEquals(SearchMatch(MatchRank.PREFIX, 0, 2), searchMatch("Ærlig", "aer"))
        // "ß" grows to "ss": the match after it maps back past it.
        assertEquals(SearchMatch(MatchRank.INSIDE, 5, 6), searchMatch("Straße", "e"))
    }

    @Test
    fun `İ and a surrogate pair - the span on whole letters of the shown string`() {
        assertEquals(SearchMatch(MatchRank.PREFIX, 0, 1), searchMatch("İda", "i"))
        val emoji = "😀 Ola" // an emoji (two chars) before the name
        assertEquals(SearchMatch(MatchRank.WORD, 3, 6), searchMatch(emoji, "ola"))
    }

    @Test
    fun `the ranks - prefix, word, inside`() {
        assertEquals(MatchRank.PREFIX, searchMatch("Anna", "an")?.rank)
        assertEquals(MatchRank.WORD, searchMatch("Trener Kari", "ka")?.rank)
        assertEquals(MatchRank.WORD, searchMatch("Anne-Marie", "ma")?.rank)
        assertEquals(MatchRank.WORD, searchMatch("O'Neil", "ne")?.rank)
        assertEquals(MatchRank.INSIDE, searchMatch("Mamma", "mm")?.rank)
        assertNull(searchMatch("Mamma", "x"))
        assertNull(searchMatch("Mamma", "   "))
        // Inner spaces are kept, the ends trimmed.
        assertEquals(MatchRank.PREFIX, searchMatch("Mamma Mia", " mamma m ")?.rank)
        assertNull(searchMatch("MammaMia", "mamma m"))
    }

    @Test
    fun `a query is cut to 40 characters`() {
        val long = "a".repeat(45)
        assertEquals(40, searchQuery(long).length)
        assertEquals("abc", searchQuery("  abc  "))
        assertTrue(searching(" a"))
        assertTrue(!searching("   "))
    }

    @Test
    fun `hits by rank, each rank A-Å in Norwegian`() {
        val nb = listCollator(Locale.forLanguageTag("nb"))
        val names = listOf("Åse Mo", "Mari", "Ola Moe", "Ammo", "Aase Mo", "Zmom")
        val hits = searchHits(names, { it }, "mo", nb).map { it.first }
        // Prefix: none. Word: "Åse Mo", "Ola Moe", "Aase Mo" A-Å (nb: Å last, "Aa" as Å). Inside: "Ammo", "Zmom".
        assertEquals(listOf("Ola Moe", "Åse Mo", "Aase Mo", "Ammo", "Zmom"), hits)
        assertEquals(emptyList<String>(), searchHits(names, { it }, "", nb).map { it.first })
    }

    /** It never decides anything: D17's sender names and the call rules stay exact. */
    @Test
    fun `nothing under sms or in the call rules calls the fold`() {
        val root = listOf(File("src/main/java/com/kidslauncher/mdm"), File("app/src/main/java/com/kidslauncher/mdm")).first { it.isDirectory }
        val files = File(root, "sms").walk().filter { it.isFile && it.name.endsWith(".kt") }.toList() + File(root, "calls/CallRules.kt")
        assertTrue(files.size > 10)
        for (file in files) {
            val text = file.readText()
            for (token in listOf("searchFold", "searchMatch", "searchHits", "ui.search")) {
                assertTrue("${file.name} uses $token", !text.contains(token))
            }
        }
    }
}
