package com.kidslauncher.mdm.lock

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kidslauncher.mdm.badges.GlanceCalendarRow
import com.kidslauncher.mdm.calendar.CalendarStore
import com.kidslauncher.mdm.day.GlanceNext
import com.kidslauncher.mdm.day.TodayCalendar
import com.kidslauncher.mdm.day.glanceNext
import com.kidslauncher.mdm.day.nextReminderBoundary
import com.kidslauncher.mdm.day.reminderNow
import java.time.LocalDate
import java.time.ZoneId

/**
 * Dagen on the lock's glance page (design 69 §5): today's calendar read off the main thread (at the page's start and
 * whenever the provider or the policy changes) - and tomorrow's while the parent's "Neste" switch is on, for the
 * evening's "I morgen" line (the calmer glance, 2026-11-04) -, the reminder row (C) and the "Neste" line (A) computed
 * from it at each render, and one main-thread callback at the next boundary while started - no alarm: the glance is
 * only seen with the screen on. Calm by construction: nothing here posts a notification or wakes the screen (no notice wake).
 */
internal class GlanceCalendar(private val activity: Activity, private val changed: () -> Unit) {
    private val main = Handler(Looper.getMainLooper())
    private var today: TodayCalendar? = null
    private var loadedFor: LocalDate? = null
    private var started = false
    private val listener: () -> Unit = { load() }
    private val boundary = Runnable {
        if (!started) return@Runnable
        if (LocalDate.now() != loadedFor) load() else changed()
    }

    fun start() {
        if (started) return
        started = true
        shown++
        CalendarStore.addListener(listener)
        load()
    }

    fun stop() {
        if (started) shown--
        started = false
        CalendarStore.removeListener(listener)
        main.removeCallbacks(boundary)
    }

    companion object {
        /** Glance pages started now (main thread): the reminder row can be seen - the ping's `rowVisible`. */
        private var shown = 0

        val showing: Boolean get() = shown > 0
    }

    private class Loaded(val today: TodayCalendar?, val day: LocalDate)

    /** The reminder row tapped (2026-11-09): today and tomorrow read from the provider again, not the kept days, so a
     * removed or moved event leaves the row while the keypad or Android's pad is up. The tap's target (the day) stays. */
    fun refresh() = load(fresh = true)

    fun load(fresh: Boolean = false) {
        val app = activity.applicationContext
        CalendarStore.run({
            LocalDate.now().let { day ->
                if (fresh) CalendarStore.forgetDays(day, day.plusDays(1))
                val today = CalendarStore.todayBlocking(app, day)
                // Tomorrow only for the line, and only while the parent wants the line.
                val withTomorrow = if (today != null && CalendarStore.policy().glanceNext) today.copy(tomorrow = CalendarStore.dayBlocking(app, day.plusDays(1))) else today
                Loaded(withTomorrow, day)
            }
        }) { loaded ->
            if (!started) return@run
            today = loaded.today
            loadedFor = loaded.day
            changed()
        }
    }

    /** The reminder row now, if one is up. */
    fun reminderRow(now: Long = System.currentTimeMillis()): GlanceCalendarRow? {
        val t = today ?: return null
        val up = reminderNow(t, CalendarStore.policy(), now) ?: return null
        return GlanceCalendarRow(up.eventId, up.title, up.beginMs, up.minutesLeft, loadedFor?.toEpochDay() ?: LocalDate.now().toEpochDay(), up.color, up.calendarName)
    }

    /** The "Neste" line's event now - today's next, or in the evening tomorrow's first -, if the parent wants it and
     * there is one. */
    fun next(now: Long = System.currentTimeMillis()): GlanceNext? {
        val t = today ?: return null
        return glanceNext(t, CalendarStore.policy(), now, ZoneId.systemDefault())
    }

    /** One callback at the next boundary (a reminder's start, an event's start, a reminder's next minute, the evening's
     * start, midnight). */
    fun armBoundary(now: Long = System.currentTimeMillis()) {
        main.removeCallbacks(boundary)
        if (!started) return
        val t = today ?: return
        val next = nextReminderBoundary(t, CalendarStore.policy(), now, ZoneId.systemDefault()) ?: return
        main.postAtTime(boundary, SystemClock.uptimeMillis() + (next - now).coerceAtLeast(0) + 50)
    }
}
