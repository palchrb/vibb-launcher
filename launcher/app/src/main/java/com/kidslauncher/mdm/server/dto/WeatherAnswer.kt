package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * `GET /api/devices/weather`'s answer (design 75 §3.5), decoded by `ServerJson` (snake_case, unknown keys ignored).
 * [status] is `ok` (the rest is set), `off`, `no_position` or `unavailable` (nothing else). Every string the
 * server computed is a key the phone maps to its own words and drawables (the model leaves out what it doesn't
 * know: a newer server never breaks this launcher; [v] changes only for a breaking shape). Pinned by `weather.json`'s
 * `answer` and `statuses` (byte-identical with `server/testdata/`, `WeatherContractTest`).
 *
 * Since 2026-11-04 the answer carries no place name (the user: "Vi dropper navnet"): an older server's `place` is an
 * unknown key and ignored, and an older launcher's `place` defaults to null when it is missing.
 *
 * Never logged as it is: every `toString()` here leaves out the temperatures and the times.
 */
@Serializable
data class WeatherAnswer(
    val v: Int = 0,
    val status: String = "",
    /** When MET last answered for the cell (D11: "Oppdatert kl." past 60 min, hidden past 3 h). */
    val checkedAtMs: Long? = null,
    /** Four entries from the UTC hour containing the server's now (D2): the phone shows the one whose hour holds
     * its clock. */
    val hours: List<WeatherAnswerHour> = emptyList(),
    /** Every date an entry's [WeatherAnswerHour.card] names. */
    val days: List<WeatherAnswerDay> = emptyList(),
) {
    override fun toString() = "WeatherAnswer(v=$v, status=$status, hours=${hours.size}, days=${days.size})"
}

/** One hour of the schedule: MET's `symbol_code`, the degrees, the words, the card's date and current period. */
@Serializable
data class WeatherAnswerHour(
    val atMs: Long = 0,
    val symbol: String? = null,
    val temp: Int? = null,
    val words: WeatherAnswerWords? = null,
    /** The local date (`yyyy-MM-dd`) the card shows during this hour. */
    val card: String? = null,
    /** 0-2: the period of today's card containing the hour; `null` before 06:00 or on tomorrow's card. */
    val current: Int? = null,
) {
    override fun toString() = "WeatherAnswerHour(symbol=$symbol, words=$words, current=$current)"
}

/** A rule key and its form (`from` carries [atMs]): `rain`/`from`, `very_cold`/`tomorrow`, ... (`weather.json`'s
 * `forms`). */
@Serializable
data class WeatherAnswerWords(val key: String = "", val form: String = "", val atMs: Long? = null) {
    override fun toString() = "WeatherAnswerWords(key=$key, form=$form)"
}

/** A card's day: morning, afternoon and evening, the date's low and high, when it gets dark. */
@Serializable
data class WeatherAnswerDay(
    val date: String = "",
    val periods: List<WeatherAnswerPeriod> = emptyList(),
    val low: Int? = null,
    val high: Int? = null,
    val dark: WeatherAnswerDark? = null,
) {
    override fun toString() = "WeatherAnswerDay(periods=${periods.size}, dark=${dark?.kind})"
}

/** `part` is `morning`, `afternoon` or `evening`; a period without its hours has all three values `null`. */
@Serializable
data class WeatherAnswerPeriod(
    val part: String = "",
    val symbol: String? = null,
    val temp: Int? = null,
    val precipMm: Double? = null,
) {
    override fun toString() = "WeatherAnswerPeriod(part=$part, symbol=$symbol)"
}

/** `kind` `at` (with [atMs]: "Mørkt kl. 17:02"), `never` ("Lyst hele natta") or `all_day` ("Mørkt hele dagen"). */
@Serializable
data class WeatherAnswerDark(val kind: String = "", val atMs: Long? = null) {
    override fun toString() = "WeatherAnswerDark(kind=$kind)"
}
