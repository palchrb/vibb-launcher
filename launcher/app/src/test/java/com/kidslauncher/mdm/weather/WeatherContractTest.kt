package com.kidslauncher.mdm.weather

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.WeatherAnswer
import com.kidslauncher.mdm.server.dto.WeatherAnswerHour
import com.kidslauncher.mdm.server.dto.WeatherNow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 75 §7.3: the weather's shared contract `weather.json` (byte-identical with `server/testdata/`, whose
 * `weather::tests::the_contract` writes its `answer`, `answer_line`, `now_answers` and `statuses` from recorded MET
 * answers) decodes into the DTOs, and every symbol code the answer sends - and every code yr maps - has its drawable
 * and its name. The words' strings (`WeatherWords`) come with step 2.
 */
class WeatherContractTest {
    private val contract = javaClass.classLoader!!.getResource("weather.json")!!.readText()
    private val parsed = Json.parseToJsonElement(contract).jsonObject

    @Test
    fun `the contract is identical to the servers copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = File("../../server/testdata/weather.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contract)
    }

    private fun answer() = ServerJson.decodeFromString(WeatherAnswer.serializer(), parsed.getValue("answer").toString())

    @Test
    fun `the answer decodes and names the line a phone shows`() {
        val answer = answer()
        assertEquals(1, answer.v)
        assertEquals("ok", answer.status)
        assertEquals("Bjølsen", answer.place)
        assertEquals(4, answer.hours.size)
        val now = parsed.getValue("answer_now_ms").jsonPrimitive.long
        val shown = answer.hours.single { it.atMs <= now && now < it.atMs + 3_600_000 }
        assertEquals(ServerJson.decodeFromString(WeatherAnswerHour.serializer(), parsed.getValue("answer_line").toString()), shown)
        // Every card date an hour names is one of the days.
        assertTrue(answer.hours.all { hour -> answer.days.any { it.date == hour.card } })
        for (day in answer.days) {
            assertEquals(listOf("morning", "afternoon", "evening"), day.periods.map { it.part })
            assertNotNull(day.dark)
        }
        // Never the place, a temperature or a time in a log line.
        assertFalse(answer.toString(), answer.toString().contains("Bjølsen"))
        assertFalse(answer.hours.first().toString().contains("temp"))
    }

    @Test
    fun `the nowcast answers and the bare statuses decode`() {
        val now = parsed.getValue("now_answers").jsonArray.map {
            ServerJson.decodeFromString(WeatherNow.serializer(), it.jsonObject.getValue("answer").toString())
        }
        assertEquals(listOf("ok", "ok", "ok", "ok", "none"), now.map { it.status })
        assertEquals(null, now[0].line)
        assertEquals("rain_in" to 25, now[1].line!!.key to now[1].line!!.minutes)
        assertEquals("dry_in" to 45, now[2].line!!.key to now[2].line!!.minutes)
        assertEquals("rain_all" to null, now[3].line!!.key to now[3].line!!.minutes)
        val keys = parsed.getValue("now_keys").jsonArray.map { it.jsonPrimitive.content }
        assertTrue(now.mapNotNull { it.line?.key }.all { it in keys })
        val statuses = parsed.getValue("statuses").jsonArray.map {
            ServerJson.decodeFromString(WeatherAnswer.serializer(), it.toString())
        }
        assertEquals(listOf("off", "no_position", "unavailable"), statuses.map { it.status })
        assertTrue(statuses.all { it.hours.isEmpty() && it.days.isEmpty() && it.place == null })
    }

    @Test
    fun `every form the server may send is one of the four`() {
        val forms = parsed.getValue("forms").jsonObject
        for ((key, list) in forms) {
            val values = list.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(key, values.isNotEmpty() && values.all { it in setOf("from", "today", "now", "tomorrow") })
        }
    }

    /** Code QA M1: the words as the server really sends them (`answer_words`: the recorded answer with rain laid over
     * 16:00-18:00) - `from` with its hour, `now` without - each a key and form of `forms`. */
    @Test
    fun `the words decode as the server sends them`() {
        val forms = parsed.getValue("forms").jsonObject
        val answer = ServerJson.decodeFromString(WeatherAnswer.serializer(), parsed.getValue("answer_words").toString())
        val words = answer.hours.mapNotNull { it.words }
        assertTrue(words.toString(), words.any { it.form == "from" } && words.any { it.form == "now" })
        for (w in words) {
            assertTrue(w.toString(), forms.getValue(w.key).jsonArray.any { it.jsonPrimitive.content == w.form })
            assertEquals(w.toString(), w.form == "from", w.atMs != null)
        }
        // The `from` hour is the first wet hour, after the entry it is shown in.
        val from = answer.hours.first { it.words?.form == "from" }
        assertTrue(from.words!!.atMs!! > from.atMs)
    }

    /** The answer's codes all map; every English symbol name has its Norwegian one (lint doesn't stop the build, and
     * TalkBack would speak English). */
    @Test
    fun `every symbol has its drawable and its name in both languages`() {
        val answer = answer()
        val codes = answer.hours.mapNotNull { it.symbol } + answer.days.flatMap { day -> day.periods.mapNotNull { it.symbol } }
        assertTrue(codes.isNotEmpty())
        for (code in codes) {
            assertNotNull("the answer's $code", WeatherSymbols.drawable(code))
            assertNotNull("the answer's $code", WeatherSymbols.name(code))
        }
        assertEquals(null, WeatherSymbols.drawable("not_a_code"))
        fun names(dir: String) = Regex("""<string name="(weather_symbol_[0-9]{2})">""")
            .findAll(File("src/main/res/$dir/weather_symbols.xml").readText()).map { it.groupValues[1] }.toSet()
        val en = names("values")
        assertEquals(41, en.size)
        assertEquals(en, names("values-nb"))
    }
}
