package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 69 §3.4: the title rules against the shared vectors `calendar_title_rules.json` (byte-identical with the
 * server's `testdata/`, whose `calendar::tests::the_title_rules_vectors` runs the same rows). A rule this launcher
 * can't use is dropped by [calendarPolicy], as the policy would drop it.
 */
class TitleRulesTest {
    private val vectors = javaClass.classLoader!!.getResource("calendar_title_rules.json")!!.readText()

    @Test
    fun `every vector shows what the server's engine shows`() {
        val rows = ServerJson.parseToJsonElement(vectors).jsonObject["titles"]!!.jsonArray
        assertTrue(rows.size >= 20)
        for ((i, row) in rows.withIndex()) {
            val r = row.jsonObject
            val policy = calendarPolicy(JsonObject(mapOf("rules" to r["rules"]!!)))
            val calendar = (r["calendar"] as? JsonPrimitive)?.takeIf { it.isString }?.content
            val description = (r["description"] as? JsonPrimitive)?.content
            val expected = (r["shown"] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
            val title = (r["title"] as JsonPrimitive).content
            assertEquals("row $i: $title", expected, TitleRules.apply(policy.rules, calendar, title, description))
        }
    }

    @Test
    fun `the vectors are identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/calendar_title_rules.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals(serverCopy.readText(), vectors)
    }

    @Test
    fun `cleaning, finding and which rule applied`() {
        assertEquals("a b cde", TitleRules.clean(" a  b\tc‮d​e  ", 200))
        assertEquals("å", TitleRules.clean("å", 200))
        assertEquals("abc", TitleRules.clean("abc def", 4))
        // A surrogate pair is one code point; the cut never splits it.
        assertEquals("🏊", TitleRules.clean("🏊🏊", 1))
        assertEquals(0 to 9, TitleRules.find("Gruppe 2- skoleåret", "gruppe2-", RuleMatch.STARTS))
        assertEquals(16 to 27, TitleRules.find("Det kuleste fra R omerriket", "Romerriket", RuleMatch.CONTAINS))
        assertNull(TitleRules.find("xabc", "abc", RuleMatch.STARTS))
        assertNull(TitleRules.find("abc", " ", RuleMatch.CONTAINS))
        val rules = calendarPolicy(
            ServerJson.parseToJsonElement(
                """{"rules": [{"id": 1, "field": "title", "match": "contains", "pattern": "x", "action": "hide", "text": ""},
                              {"id": 2, "field": "title", "match": "starts", "pattern": "Tur", "action": "replace", "text": "Tur!"}]}""",
            ),
        ).rules
        assertEquals(TitleRules.Applied(1, "Tur!"), TitleRules.applied(rules, null, "Tur i skogen", null))
        assertEquals(TitleRules.Applied(null, "Skole"), TitleRules.applied(rules, null, "Skole", null))
        assertEquals(TitleRules.Applied(0, null), TitleRules.applied(rules, null, "Tur x", null))
    }
}
