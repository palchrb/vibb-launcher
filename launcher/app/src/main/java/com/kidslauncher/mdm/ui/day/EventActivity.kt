package com.kidslauncher.mdm.ui.day

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calendar.CalendarStore
import com.kidslauncher.mdm.databinding.ActivityEventBinding
import com.kidslauncher.mdm.day.DESCRIPTION_MAX
import com.kidslauncher.mdm.day.DayRow
import com.kidslauncher.mdm.day.TITLE_MAX
import com.kidslauncher.mdm.day.TitleRules
import com.kidslauncher.mdm.day.eventDay
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.look.KidPageActivity
import com.kidslauncher.mdm.ui.look.PageKind
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * An event's own page (design 69 §2.3, mockup §5 "Hele teksten"): read again by id from the provider, the rules
 * applied to the title, the instance's time, the location, the calendar's name and the whole description (cleaned,
 * 2 000 code points) - a plain TextView, no links (kiosk). An event that is gone (or a rule now hides) says so and
 * goes back. Not exported. `open` for its light twin alone (`LightEventActivity`, design 78 §12.2 R2).
 */
open class EventActivity : KidPageActivity() {

    private lateinit var binding: ActivityEventBinding
    private val main = Handler(Looper.getMainLooper())
    private var eventId = 0L
    private var begin = 0L
    private var end = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return // The other look's class: its task is closing (KidPageActivity).
        binding = ActivityEventBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        eventId = intent.getLongExtra(EXTRA_EVENT, 0L)
        begin = intent.getLongExtra(EXTRA_BEGIN, 0L)
        end = intent.getLongExtra(EXTRA_END, 0L)
        val day = eventDay(begin, intent.getBooleanExtra(EXTRA_ALL_DAY, false), ZoneId.systemDefault())
        val (weekday, date) = DayActivity.dayTexts(this, day)
        KidHeader.bind(this, binding.eventHeader, weekday, date, action = null, back = R.string.kid_back, onAction = null)
    }

    override fun onResume() {
        super.onResume()
        // QA L3: Dagen turned off (or the policy unusable) while the page was open - it closes.
        if (!CalendarStore.dagenOn()) {
            finish()
            return
        }
        // Dagen's own task can come back from Recents under a time rule: the time-rule screen first (2026-10-27).
        if (DayActivity.redirectIfLocked(this)) return
        lockWatch.start()
        load()
    }

    override fun onPause() {
        lockWatch.stop()
        super.onPause()
    }

    /** A rule that doesn't allow Kalender beginning while the page is in front: the time-rule screen. */
    private val lockWatch = LockWatch(this)

    private fun load() {
        val app = applicationContext
        CalendarStore.run({ Loaded(CalendarStore.eventBlocking(app, eventId), CalendarStore.kidMayEditBlocking(app, eventId)) }) {
            render(it.detail, it.mine)
        }
    }

    private class Loaded(val detail: CalendarStore.EventDetail?, val mine: Boolean)

    private fun render(detail: CalendarStore.EventDetail?, mine: Boolean) {
        if (isDestroyed) return
        if (!CalendarStore.dagenOn()) {
            finish()
            return
        }
        val calendar = detail?.let { CalendarStore.state.byId[it.calendarId] }
        val policy = CalendarStore.policy()
        val shown = if (detail != null && calendar != null) {
            TitleRules.apply(policy.rules, calendar.key, detail.title, detail.description)
        } else {
            null
        }
        if (detail == null || calendar == null || shown == null) {
            binding.eventTitle.setText(R.string.event_gone)
            listOf(binding.eventTime, binding.eventLocation, binding.eventCalendar, binding.eventDescription, binding.eventOwnActions)
                .forEach { it.visibility = View.GONE }
            main.postDelayed({ if (!isFinishing) finish() }, GONE_BACK_MS)
            return
        }
        binding.eventTitle.text = shown
        binding.eventTime.visibility = View.VISIBLE
        binding.eventTime.text = timeLine(detail.allDay)
        val location = TitleRules.clean(detail.location, TITLE_MAX)
        binding.eventLocation.text = location
        binding.eventLocation.visibility = if (location.isEmpty()) View.GONE else View.VISIBLE
        binding.eventCalendar.text = calendar.displayName
        binding.eventCalendar.visibility = View.VISIBLE
        val description = TitleRules.clean(detail.description, DESCRIPTION_MAX)
        binding.eventDescription.text = description
        binding.eventDescription.visibility = if (description.isEmpty()) View.GONE else View.VISIBLE
        bindMapRows(listOfNotNull(detail.location, detail.description).joinToString("\n"))
        // Step 3: only the kid's own event (Vibb One's, in the kid's calendar) can be changed or deleted - here only.
        binding.eventOwnActions.visibility = if (mine) View.VISIBLE else View.GONE
        if (mine) {
            binding.eventChange.setOnClickListener { startActivity(EventEditActivity.editIntent(this, eventId)) }
            binding.eventDelete.setOnClickListener { confirmDelete(shown) }
        }
    }

    /** Design 110 §7.3: a "Kart" row for each of the first two map links in the event's text, while the parent's switch
     * is on - straight to Vibb's handler, which opens Organic Maps. A plain address gets none (§16). */
    private fun bindMapRows(text: String) {
        val box = binding.eventMaps
        box.removeAllViews()
        val links = if (com.kidslauncher.mdm.map.MapLinksRuntime.on(this)) {
            com.kidslauncher.mdm.map.MapLinks.webLinks(text).map { text.substring(it.first, it.last + 1) }
                .filter(com.kidslauncher.mdm.map.MapLinkForms::isMapLink).distinct().take(2)
        } else {
            emptyList()
        }
        for (link in links) {
            val row = layoutInflater.inflate(R.layout.item_event_map_row, box, false) as android.widget.TextView
            row.setOnClickListener {
                val https = com.kidslauncher.mdm.map.MapLinkForms.upgrade(link) ?: link
                startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(https))
                        .setClass(this, com.kidslauncher.mdm.map.MapLinkActivity::class.java),
                )
            }
            box.addView(row)
        }
        box.visibility = if (links.isEmpty()) View.GONE else View.VISIBLE
    }

    private class Deleted(val ok: Boolean)

    private fun confirmDelete(title: String) {
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setMessage(getString(R.string.event_delete_confirm, title))
            .setPositiveButton(R.string.event_delete) { _, _ ->
                val app = applicationContext
                CalendarStore.run({ Deleted(CalendarStore.deleteKidEventBlocking(app, eventId)) }) {
                    if (it.ok) com.kidslauncher.mdm.calendar.KidCaldavSync.requestSoon(app)
                    if (it.ok && !isDestroyed) finish()
                }
            }
            .setNegativeButton(R.string.event_cancel, null)
            .show()
    }

    private fun timeLine(allDay: Boolean): String {
        if (allDay) return getString(R.string.day_all_day)
        val zone = ZoneId.systemDefault()
        val first = Instant.ofEpochMilli(begin).atZone(zone).toLocalDate()
        val last = Instant.ofEpochMilli(if (end > begin) end - 1 else begin).atZone(zone).toLocalDate()
        val from = ClockFormat.formatClock(this, begin)
        val until = ClockFormat.formatClock(this, end)
        if (first == last) return if (end > begin) getString(R.string.event_time_range, from, until) else from
        val locale = resources.configuration.locales[0]
        val weekday = { d: LocalDate -> d.format(DateTimeFormatter.ofPattern("EEE", locale)) }
        return getString(R.string.event_time_range, "${weekday(first)} $from", "${weekday(last)} $until")
    }

    companion object {
        const val EXTRA_EVENT = "me.vibb.launcher.extra.EVENT"
        const val EXTRA_BEGIN = "me.vibb.launcher.extra.BEGIN"
        const val EXTRA_END = "me.vibb.launcher.extra.END"
        const val EXTRA_ALL_DAY = "me.vibb.launcher.extra.ALL_DAY"
        private const val GONE_BACK_MS = 2_000L

        fun intent(context: Context, row: DayRow): Intent =
            Intent(context, PageKind.EVENT.current())
                .putExtra(EXTRA_EVENT, row.eventId)
                .putExtra(EXTRA_BEGIN, row.begin)
                .putExtra(EXTRA_END, row.end)
                .putExtra(EXTRA_ALL_DAY, row.allDayEvent)
    }
}
