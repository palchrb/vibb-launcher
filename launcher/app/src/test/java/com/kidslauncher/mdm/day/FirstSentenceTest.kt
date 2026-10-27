package com.kidslauncher.mdm.day

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 69 §2.3: the second line - the location, else the description's first sentence (the real itslearning
 * feed's descriptions, mockup §5), at most 120 code points; `cut` when text was dropped (the row's "›"). */
class FirstSentenceTest {
    @Test
    fun `the real feed's descriptions`() {
        // "09.11." and "08.30." end after digits: no sentence ends, the whole text (under 120) shows.
        val bus = "Vi deler gruppa i to og tar buss 08.56 og 09.11. Viktig at man er klar på skolen 08.30."
        assertEquals(Cut(bus, false), firstSentence(bus))
        // "5. trinn" is an ordinal and "ca." an abbreviation.
        val girls = "Jentene på 5. trinn, hentes med buss fra VoMo klokka 0900. Retur med buss, tilbake på skolen ca. 13.15"
        assertEquals(Cut(girls, false), firstSentence(girls))
        // A 150-character first sentence is cut at the last space before 120.
        val long = "Vi premierer forrige ukes vinnere av ordtak-konkurransen, og mens vi koser oss med premien ser vi på " +
            "hvor noen kjente ordspråk og talemåter kommer fra. Som ferske eksperter forsøker vi å sortere ordtakene."
        val cut = firstSentence(long)
        assertTrue(cut.cut)
        assertTrue(cut.text, cut.text.endsWith("…") && cut.text.length <= 121)
        assertEquals("Vi premierer forrige ukes vinnere av ordtak-konkurransen, og mens vi koser oss med premien ser vi på hvor noen kjente…", cut.text)
    }

    @Test
    fun `the first sentence, abbreviations and the edges`() {
        assertEquals(Cut("For 6. trinn.", true), firstSentence("For 6. trinn. Vi tar bussen."))
        assertEquals(Cut("Ta med gymtøy!", true), firstSentence("Ta med gymtøy! Vi går ut."))
        assertEquals(Cut("Kommer du?", true), firstSentence("Kommer du? Si fra."))
        assertEquals(
            Cut("Møt opp ca. kl. 8 ved porten, bl.a. med f.eks. niste.", true),
            firstSentence("Møt opp ca. kl. 8 ved porten, bl.a. med f.eks. niste. Ha med vann."),
        )
        assertEquals(Cut("Husk gymtøy", false), firstSentence("Husk gymtøy"))
        assertEquals(Cut("Slutt.", false), firstSentence("Slutt."))
        assertEquals(Cut("", false), firstSentence(""))
        // No space at all in 130 characters: a hard cut.
        val word = "x".repeat(130)
        assertEquals(Cut("x".repeat(120) + "…", true), firstSentence(word))
    }

    @Test
    fun `the second line is the location when there is one`() {
        assertEquals(Cut("Rom 204", false), secondLine("Rom 204", "For 6. trinn. Buss."))
        assertEquals(Cut("For 6. trinn.", true), secondLine(" ", "For 6. trinn. Buss."))
        assertFalse(secondLine("", "").cut)
    }
}
