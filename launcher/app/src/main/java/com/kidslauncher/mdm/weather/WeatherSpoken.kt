package com.kidslauncher.mdm.weather

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.kidslauncher.mdm.R
import java.util.Locale
import kotlin.math.abs

/** The resources [spokenCardText] reads: Android's in the app, the catalogs' in the unit tests. */
interface SpokenText {
    fun string(@StringRes id: Int, vararg args: Any): String
    fun plural(@PluralsRes id: Int, quantity: Int, vararg args: Any): String
}

/**
 * What TalkBack reads for the open card (launcher QA M5: whole sentences joined with a space). The evening card (18:00
 * to midnight) reads "I kveld: ..." first, then "I morgen." and tomorrow's morning and afternoon, as it shows; [dark] is
 * the foot's dark text ("Mørkt kl. 15:59"), already worded for the phone's clock.
 */
fun spokenCardText(model: WeatherCard, lowHigh: Boolean, dark: String?, locale: Locale, text: SpokenText): String {
    fun sentence(words: String) = if (words.trimEnd().endsWith(".")) words.trimEnd() else text.string(R.string.weather_spoken_sentence, words)
    val parts = mutableListOf<String>()
    fun column(label: String, c: WeatherCardColumn) {
        parts += text.string(R.string.weather_spoken_period, label)
        c.symbol?.let { WeatherSymbols.name(it) }?.let { parts += sentence(text.string(it)) }
        c.temp?.let { parts += text.plural(R.plurals.weather_spoken_degrees, abs(it), weatherNumber(it)) }
        c.mm?.let { weatherMm(it, locale) }?.let { parts += text.string(R.string.weather_spoken_mm, it) }
    }
    model.tonight?.takeIf { it.symbol != null || it.temp != null }?.let { column(text.string(R.string.weather_card_tonight), it) }
    if (model.tomorrow) parts += sentence(text.string(R.string.weather_card_tomorrow))
    for (c in model.columns) {
        // E8's L1: a period without its hours says nothing (no bare "Morgen:").
        if (c.symbol == null && c.temp == null) continue
        val label = WeatherWords.partRes(c.part)?.let { text.string(it) } ?: continue
        column(label, c)
    }
    if (lowHigh) {
        model.low?.let { parts += text.plural(R.plurals.weather_spoken_low, abs(it), weatherNumber(it)) }
        model.high?.let { parts += text.plural(R.plurals.weather_spoken_high, abs(it), weatherNumber(it)) }
    }
    dark?.let { parts += sentence(it) }
    return WeatherWords.spoken(parts.joinToString(" "))
}
