package com.kidslauncher.mdm.weather

import com.kidslauncher.mdm.R

/**
 * The server's rule keys and forms to the phone's own fixed words (design 75 §5.6; launcher QA L10: a `when` table,
 * never `getIdentifier`). Each string is whole and true on its own - "Regn fra kl. %1$s", "Regn nå", "Regn i morgen" -
 * nothing is glued. A pair the table doesn't know has no words (the line shows without them), so a newer server's
 * rule never breaks this launcher. `WeatherContractTest` checks every pair of the contract's `forms` maps here, in
 * both catalogs, with the placeholder `from` needs.
 */
object WeatherWords {
    /** The words of [key] in [form] (`from` takes the hour), or null. */
    fun res(key: String, form: String): Int? = when (key) {
        "thunder" -> precipitation(form, R.string.weather_thunder_from, R.string.weather_thunder_today, R.string.weather_thunder_now, R.string.weather_thunder_tomorrow)
        "snow" -> precipitation(form, R.string.weather_snow_from, R.string.weather_snow_today, R.string.weather_snow_now, R.string.weather_snow_tomorrow)
        "sleet" -> precipitation(form, R.string.weather_sleet_from, R.string.weather_sleet_today, R.string.weather_sleet_now, R.string.weather_sleet_tomorrow)
        "heavy_rain" -> precipitation(form, R.string.weather_heavy_rain_from, R.string.weather_heavy_rain_today, R.string.weather_heavy_rain_now, R.string.weather_heavy_rain_tomorrow)
        "rain" -> precipitation(form, R.string.weather_rain_from, R.string.weather_rain_today, R.string.weather_rain_now, R.string.weather_rain_tomorrow)
        "maybe_rain" -> day(form, R.string.weather_maybe_rain_today, R.string.weather_maybe_rain_tomorrow)
        "maybe_snow" -> day(form, R.string.weather_maybe_snow_today, R.string.weather_maybe_snow_tomorrow)
        "very_cold" -> day(form, R.string.weather_very_cold_today, R.string.weather_very_cold_tomorrow)
        "windy" -> day(form, R.string.weather_windy_today, R.string.weather_windy_tomorrow)
        "very_warm" -> day(form, R.string.weather_very_warm_today, R.string.weather_very_warm_tomorrow)
        else -> null
    }

    /** The card's nowcast line ([key] from `weather/now`): `_in` keys take the minutes. */
    fun nowRes(key: String): Int? = when (key) {
        "rain_in" -> R.string.weather_now_rain_in
        "snow_in" -> R.string.weather_now_snow_in
        "dry_in" -> R.string.weather_now_dry_in
        "rain_all" -> R.string.weather_now_rain_all
        "snow_all" -> R.string.weather_now_snow_all
        else -> null
    }

    /** Text for TalkBack: without the soft hyphens a shown label keeps for its line break ("Etter\u00ADmiddag",
     * E9 L4). */
    fun spoken(text: String): String = text.replace("\u00AD", "")

    /** A card column's label. */
    fun partRes(part: String): Int? = when (part) {
        "morning" -> R.string.weather_part_morning
        "afternoon" -> R.string.weather_part_afternoon
        "evening" -> R.string.weather_part_evening
        else -> null
    }

    private fun precipitation(form: String, from: Int, today: Int, now: Int, tomorrow: Int): Int? = when (form) {
        "from" -> from
        "today" -> today
        "now" -> now
        "tomorrow" -> tomorrow
        else -> null
    }

    private fun day(form: String, today: Int, tomorrow: Int): Int? = when (form) {
        "today" -> today
        "tomorrow" -> tomorrow
        else -> null
    }
}
