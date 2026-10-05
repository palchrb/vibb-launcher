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
    fun describe(context: Context, summary: MissedSummary, zone: ZoneId = ZoneId.systemDefault()): String {
        val at = Instant.ofEpochMilli(summary.lastAtMs).atZone(zone)
        val time = DateFormat.getTimeFormat(context).format(Date(summary.lastAtMs))
        val whenText = when (relativeDay(at.toLocalDate().toEpochDay(), LocalDate.now(zone).toEpochDay())) {
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
        return context.resources.getQuantityString(R.plurals.missed_calls_at, summary.count, summary.count, whenText)
    }
}
