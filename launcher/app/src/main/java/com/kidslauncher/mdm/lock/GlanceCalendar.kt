package com.kidslauncher.mdm.lock

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.kidslauncher.mdm.badges.GlanceCalendarRow
import com.kidslauncher.mdm.calendar.CalendarStore
import com.kidslauncher.mdm.day.TodayCalendar
import com.kidslauncher.mdm.day.glanceNext
import com.kidslauncher.mdm.day.nextReminderBoundary
import com.kidslauncher.mdm.day.reminderNow
import java.time.LocalDate

/**
 * Dagen on the lock's glance page (design 69 §5): today's calendar read off the main thread (at the page's start and
 * whenever the provider or the policy changes), the reminder row (C) and the "Neste" line (A) computed from it at each
 * render, and one main-thread callback at the next boundary while started - no alarm: the glance is only seen with the
 * screen on. Calm by construction: nothing here posts a notification or wakes the screen (no notice wake).
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
        CalendarStore.addListener(listener)
        load()
    }

    fun stop() {
        started = false
        CalendarStore.removeListener(listener)
        main.removeCallbacks(boundary)
    }

    private class Loaded(val today: TodayCalendar?, val day: LocalDate)

    fun load() {
        val app = activity.applicationContext
        CalendarStore.run({ LocalDate.now().let { Loaded(CalendarStore.todayBlocking(app, it), it) } }) { loaded ->
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
        return GlanceCalendarRow(up.eventId, up.title, up.beginMs, up.minutesLeft, loadedFor?.toEpochDay() ?: LocalDate.now().toEpochDay())
    }

    /** The "Neste" line's event now (title, start), if the parent wants it and there is one. */
    fun next(now: Long = System.currentTimeMillis()): Pair<String, Long>? {
        val t = today ?: return null
        return glanceNext(t, CalendarStore.policy(), now)?.let { it.title to it.begin }
    }

    /** One callback at the next boundary (a reminder's start, an event's start, a reminder's next minute). */
    fun armBoundary(now: Long = System.currentTimeMillis()) {
        main.removeCallbacks(boundary)
        if (!started) return
        val t = today ?: return
        val next = nextReminderBoundary(t, CalendarStore.policy(), now) ?: return
        main.postAtTime(boundary, SystemClock.uptimeMillis() + (next - now).coerceAtLeast(0) + 50)
    }
}
