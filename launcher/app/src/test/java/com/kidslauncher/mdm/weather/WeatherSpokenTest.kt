package com.kidslauncher.mdm.weather

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.server.dto.WeatherAnswerDark
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/** TalkBack's words for the open card (QA N3, 2026-11-04): the evening card reads "I kveld: ..." then "I morgen." and
 * tomorrow's morning and afternoon, the plain card as before - in both catalogs, read from the strings files. */
class WeatherSpokenTest {
    private val NB: Locale = Locale.forLanguageTag("nb")

    private fun catalog(dir: String, locale: Locale): SpokenText {
        val xml = File("src/main/res/$dir/strings.xml").readText() + File("src/main/res/$dir/weather_symbols.xml").readText()
        fun unescape(text: String) = text.replace(Regex("""\\u([0-9A-Fa-f]{4})""")) { it.groupValues[1].toInt(16).toChar().toString() }
            .replace("\\'", "'").replace("\\\"", "\"")
        val strings = Regex("""<string name="([a-z_0-9]+)">([^<]*)</string>""").findAll(xml).associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        val plurals = Regex("""<plurals name="([a-z_0-9]+)">(.*?)</plurals>""", RegexOption.DOT_MATCHES_ALL).findAll(xml).associate { plural ->
            plural.groupValues[1] to Regex("""<item quantity="([a-z]+)">([^<]*)</item>""").findAll(plural.groupValues[2])
                .associate { it.groupValues[1] to unescape(it.groupValues[2]) }
        }
        // R is generated: an id's name is read back from its fields.
        fun nameOf(type: Class<*>, id: Int) = type.fields.first { it.getInt(null) == id }.name
        return object : SpokenText {
            override fun string(id: Int, vararg args: Any) =
                String.format(locale, strings.getValue(nameOf(R.string::class.java, id)), *args)

            override fun plural(id: Int, quantity: Int, vararg args: Any): String {
                val items = plurals.getValue(nameOf(R.plurals::class.java, id))
                return String.format(locale, items.getValue(if (quantity == 1) "one" else "other"), *args)
            }
        }
    }

    private val evening = WeatherCard(
        tomorrow = true,
        columns = listOf(
            WeatherCardColumn("morning", "partlycloudy_day", 3, 0.0, current = false),
            WeatherCardColumn("afternoon", "rain", 5, 1.4, current = false),
        ),
        low = 1,
        high = 7,
        dark = WeatherAnswerDark("all_day", null),
        updatedAtMs = null,
        tonight = WeatherCardColumn("evening", "partlycloudy_night", -1, 0.0, current = true),
    )

    @Test
    fun `the evening card reads I kveld first, then I morgen`() {
        assertEquals(
            "I kveld: Delvis skyet. −1 grad. I morgen. Morgen: Delvis skyet. 3 grader. " +
                "Ettermiddag: Regn. 5 grader. 1,4 millimeter. Laveste 1 grad. Høyeste 7 grader. Mørkt hele dagen.",
            spokenCardText(evening, true, "Mørkt hele dagen", NB, catalog("values-nb", NB)),
        )
        assertEquals(
            "Tonight: Partly cloudy. −1 degree. Tomorrow. Morning: Partly cloudy. 3 degrees. " +
                "Afternoon: Rain. 5 degrees. 1.4 millimetres. Low 1 degree. High 7 degrees. Dark all day.",
            spokenCardText(evening, true, "Dark all day", Locale.US, catalog("values", Locale.US)),
        )
    }

    @Test
    fun `a tonight without symbol and degrees is not read, and the plain card has no I kveld`() {
        val nb = catalog("values-nb", NB)
        val empty = evening.copy(tonight = WeatherCardColumn("evening", null, null, null, current = true))
        assertEquals(
            spokenCardText(evening.copy(tonight = null), false, null, NB, nb),
            spokenCardText(empty, false, null, NB, nb),
        )
        val plain = evening.copy(tomorrow = false, tonight = null, columns = evening.columns + WeatherCardColumn("evening", "fair_night", 2, null, current = false))
        assertEquals(
            "Morgen: Delvis skyet. 3 grader. Ettermiddag: Regn. 5 grader. 1,4 millimeter. Kveld: Lettskyet. 2 grader.",
            spokenCardText(plain, false, null, NB, nb),
        )
    }
}
