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

    private val placeholder = Regex("%(\\d+\\$)?[sd]")

    /** name -> texts (one for a string, one per quantity for plurals). */
    private fun load(path: String): Map<String, List<String>> {
        val file = listOf(File(path), File("app/$path")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file)
        val result = mutableMapOf<String, List<String>>()
        val strings = doc.getElementsByTagName("string")
        for (i in 0 until strings.length) {
            val element = strings.item(i) as org.w3c.dom.Element
            if (element.getAttribute("translatable") == "false") continue
            result[element.getAttribute("name")] = listOf(element.textContent)
        }
        val plurals = doc.getElementsByTagName("plurals")
        for (i in 0 until plurals.length) {
            val element = plurals.item(i) as org.w3c.dom.Element
            val items = element.getElementsByTagName("item")
            result[element.getAttribute("name")] = (0 until items.length).map { items.item(it).textContent }
        }
        return result
    }

    @Test
    fun `nb translates every string with the same placeholders`() {
        val en = load("src/main/res/values/strings.xml")
        val nb = load("src/main/res/values-nb/strings.xml")
        assertTrue(en.size > 100)
        assertEquals(en.keys, nb.keys)
        for ((name, texts) in en) {
            val expected = texts.flatMap { placeholder.findAll(it).map { m -> m.value } }.toSet()
            val actual = nb.getValue(name).flatMap { placeholder.findAll(it).map { m -> m.value } }.toSet()
            assertEquals(name, expected, actual)
        }
    }
}
