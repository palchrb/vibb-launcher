package com.kidslauncher.mdm.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Every English string has a Norwegian (bokmål) translation with the same placeholders (design
 * 05-ui-photos-i18n.md). Android lint's MissingTranslation would catch the first half, but lint
 * doesn't run offline here; a wrong placeholder would crash at runtime.
 */
class TranslationsTest {

    /** Any format specifier: `%s`, `%1$d`, `%,d`, `%.1f`, `%%` ... */
    private val placeholder = Regex("%(\\d+\\$)?[-#+ 0,(]*\\d*(\\.\\d+)?[a-zA-Z%]")

    /** name -> quantity ("" for a plain string) -> text. */
    private fun load(path: String): Map<String, Map<String, String>> {
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = mutableMapOf<String, Map<String, String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val element = strings.item(i) as org.w3c.dom.Element
            if (element.getAttribute("translatable") == "false") continue
            result[element.getAttribute("name")] = mapOf("" to element.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val element = plurals.item(i) as org.w3c.dom.Element
            val items = element.getElementsByTagName("item")
            result[element.getAttribute("name")] = (0 until items.length).associate {
                val item = items.item(it) as org.w3c.dom.Element
                item.getAttribute("quantity") to item.textContent
            }
        }
        return result
    }

    @Test
    fun `nb translates every string with the same placeholders`() {
        val en = load("src/main/res/values/strings.xml")
        val nb = load("src/main/res/values-nb/strings.xml")
        assertTrue(en.size > 100)
        assertEquals(en.keys, nb.keys)
        fun specifiers(text: String) = placeholder.findAll(text).map { it.value }.sorted().toList()
        for ((name, enTexts) in en) {
            val nbTexts = nb.getValue(name)
            // Plurals: every Norwegian quantity against the same English one (else "other").
            for ((quantity, text) in nbTexts) {
                val english = enTexts[quantity] ?: enTexts.getValue("other")
                if (quantity == "one" && enTexts[quantity] == null) continue
                assertEquals("$name[$quantity]", specifiers(english), specifiers(text))
            }
            assertTrue("$name has quantities missing in nb", nbTexts.keys.containsAll(enTexts.keys))
        }
    }
}
