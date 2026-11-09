package com.kidslauncher.mdm.ui.home

import android.content.Context
import android.text.format.DateFormat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.MissedSummary
import com.kidslauncher.mdm.calls.RelativeDay
import com.kidslauncher.mdm.calls.relativeDay
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date

/** "1 missed call today 13:05" / "2 tapte anrop i går 09:10", in the launcher's language. */
object MissedCallText {
    fun describe(context: Context, summary: MissedSummary, zone: ZoneId = ZoneId.systemDefault()): String =
        context.resources.getQuantityString(R.plurals.missed_calls_at, summary.count, summary.count, whenText(context, summary, zone))

    /** Round L5: "1 missed call in Element X today 13:05" / "1 tapt anrop i Element X i dag 13:05" - [app] the name the
     * kid sees for the app (design 14's shown label). */
    fun describeIn(context: Context, summary: MissedSummary, app: String, zone: ZoneId = ZoneId.systemDefault()): String =
        context.resources.getQuantityString(R.plurals.missed_calls_in_app_at, summary.count, summary.count, app, whenText(context, summary, zone))

    /**
     * The phone book row's short line (design 79 §3.4): "Tapt anrop 14:02" today, "Tapt anrop i går", "Tapt anrop 3.
     * nov." earlier, "2 tapte anrop 14:02" for more - [count] the phone's and Element X's calls together, [lastAtMs] the
     * newest. The full sentence stays on the card and in TalkBack ([describe]).
     */
    fun short(context: Context, count: Int, lastAtMs: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(lastAtMs).atZone(zone)
        val whenText = when (relativeDay(at.toLocalDate().toEpochDay(), LocalDate.now(zone).toEpochDay())) {
            RelativeDay.TODAY -> com.kidslauncher.mdm.ui.ClockFormat.formatTime(context, lastAtMs, zone)
            RelativeDay.YESTERDAY -> context.getString(R.string.missed_short_yesterday)
            RelativeDay.EARLIER -> {
                val locale = context.resources.configuration.locales[0]
                android.text.format.DateFormat.format(DateFormat.getBestDateTimePattern(locale, "dMMM"), Date(lastAtMs)).toString()
            }
        }
        return context.resources.getQuantityString(R.plurals.missed_short, count, whenText, count)
    }

    private fun whenText(context: Context, summary: MissedSummary, zone: ZoneId): String {
        val at = Instant.ofEpochMilli(summary.lastAtMs).atZone(zone)
        val time = com.kidslauncher.mdm.ui.ClockFormat.formatTime(context, summary.lastAtMs, zone)
        return when (relativeDay(at.toLocalDate().toEpochDay(), LocalDate.now(zone).toEpochDay())) {
            RelativeDay.TODAY -> context.getString(R.string.missed_when_today, time)
            RelativeDay.YESTERDAY -> context.getString(R.string.missed_when_yesterday, time)
            RelativeDay.EARLIER -> {
                val locale = context.resources.configuration.locales[0]
                val day = android.text.format.DateFormat.format(
                    DateFormat.getBestDateTimePattern(locale, "dMMM"), Date(summary.lastAtMs)
                )
                context.getString(R.string.missed_when_date, day, time)
            }
        }
    }
}
