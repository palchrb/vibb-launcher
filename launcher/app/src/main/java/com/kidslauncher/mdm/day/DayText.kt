package com.kidslauncher.mdm.day

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.ceil

/*
 * Dagen's words for a day and the width of its time column (the E5 run's L4 and M1) - pure, no Android imports;
 * DayTextTest.
 */

/** The skeleton of a day on one line: weekday, day and month, the year only when it isn't this year's. */
fun dayLineSkeleton(day: LocalDate, today: LocalDate): String = if (day.year == today.year) "EEEEdMMMM" else "EEEEdMMMMy"

/** The day on one line in the locale's own [pattern] (Android's best pattern for [dayLineSkeleton]: en-US
 * "EEEE, MMMM d" -> "Tuesday, October 27", nb "EEEE d. MMMM" -> "Tirsdag 27. oktober"), the first letter capitalised
 * as a label's. */
fun dayLineText(pattern: String, day: LocalDate, locale: Locale): String =
    day.format(DateTimeFormatter.ofPattern(pattern, locale)).replaceFirstChar { it.titlecase(locale) }

/** The time column's width on one page: the widest of its texts (each one line), at least [minPx] - so a 12-hour
 * "12:30 PM" or a larger font widens every row's column alike, and a 24-hour page keeps 56 dp. */
fun timeColumnPx(widestPx: Float, minPx: Int, slackPx: Int): Int = maxOf(minPx, ceil(widestPx).toInt() + slackPx)
