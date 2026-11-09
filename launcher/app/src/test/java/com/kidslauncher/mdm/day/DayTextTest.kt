package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.ui.ClockFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.util.Locale

/** The E5 run's L4 and M1: Dagen's day on one line and its times in the locale's own form (en-US with the comma and
 * AM/PM, nb as before), and the time column that fits its widest text. */
class DayTextTest {
    private val us = Locale.forLanguageTag("en-US")
    private val nb = Locale.forLanguageTag("nb-NO")
    private val day = LocalDate.of(2026, 10, 27)

    @Test
    fun `the day on one line - the locale's order and punctuation, the year only another year`() {
        assertEquals("EEEEdMMMM", dayLineSkeleton(day, day))
        assertEquals("EEEEdMMMMy", dayLineSkeleton(day, LocalDate.of(2027, 1, 2)))
        // Android's best patterns for the skeleton (ICU/CLDR): en-US "EEEE, MMMM d", nb "EEEE d. MMMM".
        assertEquals("Tuesday, October 27", dayLineText("EEEE, MMMM d", day, us))
        assertEquals("Tirsdag 27. oktober", dayLineText("EEEE d. MMMM", day, nb))
        assertEquals("Tuesday, October 27, 2026", dayLineText("EEEE, MMMM d, y", day, us))
    }

    @Test
    fun `a time in 12 hours carries its AM or PM, in 24 hours none`() {
        // 00:38 and 12:38 are different times: "12:38" alone was both (E5's L4).
        assertEquals("12:38 AM", ClockFormat.format("h:mm a", 0, 38, false, us, amPm = true))
        assertEquals("12:38 PM", ClockFormat.format("h:mm a", 12, 38, false, us, amPm = true))
        assertEquals("00:38", ClockFormat.format("HH:mm", 0, 38, true, nb, amPm = true))
        assertEquals("12:38", ClockFormat.format("HH:mm", 12, 38, true, nb, amPm = true))
    }

    @Test
    fun `Dagen's surfaces take the time with its marker, never the clock face's`() {
        val src = listOf("src/main/java/com/kidslauncher/mdm", "app/src/main/java/com/kidslauncher/mdm").map(::File).first { it.isDirectory }
        val clock = File(src, "ui/ClockFormat.kt").readText()
        assertTrue(clock.contains("fun formatClock(context: Context, ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =\n        render(context, at(ms, zone), amPm = true)"))
        for (name in listOf("DayListAdapter.kt", "DayActivity.kt", "EventActivity.kt", "EventEditActivity.kt")) {
            val text = File(src, "ui/day/$name").readText()
            assertFalse(name, text.contains("ClockFormat.formatTime("))
            assertTrue(name, text.contains("ClockFormat.formatClock("))
        }
        val glance = File(src, "lock/GlancePanel.kt").readText()
        // The "Neste" line and the evening's "I morgen" line (the calmer glance) take the same time.
        assertTrue(glance.contains("val time = ClockFormat.formatClock(activity, line.row.begin)"))
        assertTrue(glance.contains("if (line.tomorrow) R.string.glance_tomorrow else R.string.glance_next, line.row.title, time)"))
        assertTrue(glance.contains("val time = ClockFormat.formatClock(activity, row.beginMs)"))
        // The line puts the time before the title (the one-line ellipsis cuts the title); the reminder row's second line
        // is the time, then the calendar's name when it has one, and TalkBack names it too.
        fun res(dir: String) = listOf("src/main", "app/src/main").map { File(it, "res/$dir/strings.xml") }.first { it.exists() }.readText()
        for ((dir, next, tomorrow) in listOf(Triple("values", "Next", "Tomorrow"), Triple("values-nb", "Neste", "I morgen"))) {
            assertTrue(dir, res(dir).contains("<string name=\"glance_next\">$next %2\$s: %1\$s</string>"))
            assertTrue(dir, res(dir).contains("<string name=\"glance_tomorrow\">$tomorrow %2\$s: %1\$s</string>"))
            assertTrue(dir, res(dir).contains("<string name=\"glance_reminder_when\">%1\$s · %2\$s</string>"))
        }
        assertTrue(res("values-nb").contains("<string name=\"glance_reminder_spoken_calendar\">Påminnelse: %1\$s, %2\$s, %3\$s, %4\$s-kalenderen</string>"))
        assertTrue(glance.contains("activity.getString(R.string.glance_reminder_when, time, name)"))
        assertTrue(glance.contains("R.string.glance_reminder_spoken_calendar, row.title, left, time, name"))
        // The form's day is the one-line day, not weekday + date glued with a space.
        val form = File(src, "ui/day/EventEditActivity.kt").readText()
        assertTrue(form.contains("binding.eventEditDay.text = DayActivity.dayLine(this, day)"))
    }

    @Test
    fun `the time column fits its widest text, never below 56 dp`() {
        assertEquals(112, timeColumnPx(60.2f, 112, 2))
        assertEquals(165, timeColumnPx(162.4f, 112, 2))
        assertEquals(112, timeColumnPx(0f, 112, 2))
    }
}
