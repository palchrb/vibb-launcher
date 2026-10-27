package com.kidslauncher.mdm.ui.day

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.appcompat.app.AlertDialog
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calendar.CalendarStore
import com.kidslauncher.mdm.databinding.ActivityEventEditBinding
import com.kidslauncher.mdm.day.KID_EVENT_DEFAULT_MINUTES
import com.kidslauncher.mdm.day.KID_EVENT_MINUTES
import com.kidslauncher.mdm.day.kidEventDefaultStart
import com.kidslauncher.mdm.day.kidEventInput
import com.kidslauncher.mdm.day.kidEventMinutes
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.UIObjectActivity
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * "Ny avtale" (design 69 step 3, mockup §1b): the kid's own event in the kid's calendar - what, the day (the shown
 * day), from, how long, an optional place; no guests, no recurrence, no reminder of its own. With [EXTRA_EVENT] it
 * changes one of the kid's own events (re-checked before the save: Vibb One's, in the kid's calendar). Every provider
 * write is [CalendarStore]'s, off the main thread. Not exported.
 */
class EventEditActivity : UIObjectActivity() {

    private lateinit var binding: ActivityEventEditBinding
    private var eventId: Long? = null
    private var day: LocalDate = LocalDate.now()
    private var start: LocalTime = LocalTime.NOON
    private var minutes = KID_EVENT_DEFAULT_MINUTES
    private var saving = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEventEditBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        eventId = intent.getLongExtra(EXTRA_EVENT, 0L).takeIf { it != 0L }
        intent.getLongExtra(DayActivity.EXTRA_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let { day = LocalDate.ofEpochDay(it) }
        kidEventDefaultStart(day, LocalDateTime.now()).let {
            day = it.toLocalDate()
            start = it.toLocalTime()
        }
        savedInstanceState?.let {
            day = LocalDate.ofEpochDay(it.getLong(STATE_DAY))
            start = LocalTime.ofSecondOfDay(it.getLong(STATE_START))
            minutes = it.getInt(STATE_MINUTES)
        }
        KidHeader.bind(this, binding.eventEditHeader, getString(if (eventId == null) R.string.event_new_title else R.string.event_edit_title), null, null, null)
        binding.eventEditDay.setOnClickListener { pickDay() }
        binding.eventEditFrom.setOnClickListener { pickStart() }
        binding.eventEditLength.setOnClickListener { pickLength() }
        binding.eventEditSave.setOnClickListener { save() }
        render()
        val id = eventId
        if (id != null && savedInstanceState == null) loadOwn(id)
    }

    override fun onResume() {
        super.onResume()
        // QA L3: Dagen turned off (or the policy unusable) while the form was open - it closes, nothing saved.
        if (!CalendarStore.dagenOn()) {
            finish()
            return
        }
        // Dagen's own task can come back from Recents under a time rule: the time-rule screen first (2026-10-27).
        DayActivity.redirectIfLocked(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DAY, day.toEpochDay())
        outState.putLong(STATE_START, start.toSecondOfDay().toLong())
        outState.putInt(STATE_MINUTES, minutes)
    }

    private class Own(val detail: CalendarStore.EventDetail?, val mine: Boolean)

    /** The kid's own event to change: only one that is still theirs, else the page goes back. */
    private fun loadOwn(id: Long) {
        val app = applicationContext
        CalendarStore.run({ Own(CalendarStore.eventBlocking(app, id), CalendarStore.kidMayEditBlocking(app, id)) }) { own ->
            val detail = own.detail
            if (isDestroyed) return@run
            if (detail == null || !own.mine) {
                finish()
                return@run
            }
            val zone = ZoneId.systemDefault()
            val at = Instant.ofEpochMilli(detail.begin).atZone(zone)
            day = at.toLocalDate()
            start = at.toLocalTime().withSecond(0).withNano(0)
            minutes = kidEventMinutes(detail.end - detail.begin)
            binding.eventEditWhat.setText(detail.title.orEmpty())
            binding.eventEditWhere.setText(detail.location.orEmpty())
            render()
        }
    }

    private fun render() {
        binding.eventEditDay.text = DayActivity.dayLine(this, day)
        val at = day.atTime(start).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        binding.eventEditFrom.text = ClockFormat.formatClock(this, at)
        binding.eventEditLength.text = lengthText(minutes)
    }

    private fun lengthText(m: Int): String = when {
        m < 60 -> getString(R.string.event_minutes, m)
        m % 60 == 0 -> resources.getQuantityString(R.plurals.event_hours, m / 60, m / 60)
        else -> getString(R.string.event_hour_and_half)
    }

    private fun pickDay() {
        DatePickerDialog(this, R.style.VibbPickerDialog, { _, y, mo, d ->
            day = LocalDate.of(y, mo + 1, d)
            render()
        }, day.year, day.monthValue - 1, day.dayOfMonth).show()
    }

    private fun pickStart() {
        TimePickerDialog(this, R.style.VibbPickerDialog, { _, h, m ->
            start = LocalTime.of(h, m)
            render()
        }, start.hour, start.minute, ClockFormat.systemIs24(this)).show()
    }

    private fun pickLength() {
        val labels = KID_EVENT_MINUTES.map { lengthText(it) }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.event_length)
            .setSingleChoiceItems(labels, KID_EVENT_MINUTES.indexOf(minutes)) { dialog, which ->
                minutes = KID_EVENT_MINUTES[which]
                render()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.event_cancel, null)
            .show()
    }

    private class Saved(val ok: Boolean)

    private fun save() {
        if (saving) return
        val input = kidEventInput(
            binding.eventEditWhat.text.toString(), binding.eventEditWhere.text.toString(), day, start, minutes, ZoneId.systemDefault(),
        )
        if (input == null) {
            showError(R.string.event_what_missing)
            return
        }
        saving = true
        val app = applicationContext
        val id = eventId
        CalendarStore.run({
            Saved(if (id == null) CalendarStore.insertKidEventBlocking(app, input) != null else CalendarStore.updateKidEventBlocking(app, id, input))
        }) { saved ->
            saving = false
            if (isDestroyed) return@run
            if (saved.ok) {
                finish()
            } else {
                showError(R.string.event_save_failed)
            }
        }
    }

    private fun showError(text: Int) {
        binding.eventEditError.setText(text)
        binding.eventEditError.visibility = View.VISIBLE
    }

    companion object {
        const val EXTRA_EVENT = "me.vibb.launcher.extra.EVENT"
        private const val STATE_DAY = "day"
        private const val STATE_START = "start"
        private const val STATE_MINUTES = "minutes"

        fun newIntent(context: Context, day: LocalDate): Intent =
            Intent(context, EventEditActivity::class.java).putExtra(DayActivity.EXTRA_DAY, day.toEpochDay())

        fun editIntent(context: Context, eventId: Long): Intent =
            Intent(context, EventEditActivity::class.java).putExtra(EXTRA_EVENT, eventId)
    }
}
