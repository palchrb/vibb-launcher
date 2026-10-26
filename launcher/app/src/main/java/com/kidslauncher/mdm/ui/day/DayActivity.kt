package com.kidslauncher.mdm.ui.day

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calendar.CalendarStore
import com.kidslauncher.mdm.databinding.ActivityDayBinding
import com.kidslauncher.mdm.day.DAY_CENTER
import com.kidslauncher.mdm.day.DayView
import com.kidslauncher.mdm.day.TodayJump
import com.kidslauncher.mdm.day.dayAt
import com.kidslauncher.mdm.day.positionOf
import com.kidslauncher.mdm.day.todayJump
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.UIObjectActivity
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Dagen (design 69 §2.1, mockup `docs/design/69-mockups/dagen.html` §1): the kid's day as a list, one day per swipe,
 * endless both ways (pure `day/DayPager.kt`). The header always carries the weekday over the date; off today its
 * action jumps back ("← I dag" on a later day, "I dag →" on an earlier one). A page shows nothing until its day is
 * read (no empty-day flash); the provider's change notification reloads the bound pages; a minute tick moves
 * "Pågår nå"; a new day, a time set or a zone change moves the anchor and keeps the page's date. Read-only but for
 * the kid's own events ("+", step 3). Nothing here reaches the server.
 */
class DayActivity : UIObjectActivity() {

    private lateinit var binding: ActivityDayBinding
    private lateinit var adapter: DayPagerAdapter
    private var anchor: LocalDate = LocalDate.now()
    private val tick = Handler(Looper.getMainLooper())
    private val minute = object : Runnable {
        override fun run() {
            adapter.refreshBound()
            renderReminder()
            tick.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + 200)
        }
    }
    private val calendarListener: () -> Unit = {
        adapter.refreshBound()
        renderReminder()
        renderAdd()
    }
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) = moveAnchor()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDayBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        adapter = DayPagerAdapter(this) { anchor }
        binding.dayPager.adapter = adapter
        binding.dayPager.offscreenPageLimit = 1
        // A 10 dp peek of the neighbours: the pager's own list keeps its padding.
        (binding.dayPager.getChildAt(0) as? RecyclerView)?.let { list ->
            val peek = (10 * resources.displayMetrics.density).toInt()
            list.setPadding(peek, 0, peek, 0)
            list.clipToPadding = false
        }
        val start = savedInstanceState?.getLong(STATE_DAY)?.let(LocalDate::ofEpochDay)
            ?: intent.getLongExtra(EXTRA_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE }?.let(LocalDate::ofEpochDay)
            ?: anchor
        binding.dayPager.setCurrentItem(positionOf(anchor, start), false)
        binding.dayPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) = renderHeader()
        })
        renderHeader()
        CalendarStore.ensureKidCalendar(this)
        // Step 3: the "+" for the kid's own event, on the shown day.
        binding.dayAdd.setOnClickListener { startActivity(EventEditActivity.newIntent(this, shownDay())) }
    }

    /** The "+" while the kid may add events and their calendar is there to write to (§1b). */
    private fun renderAdd() {
        val shown = CalendarStore.kidCalendar() != null && CalendarStore.canWrite(this)
        binding.dayAdd.visibility = if (shown) android.view.View.VISIBLE else android.view.View.GONE
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val day = intent.getLongExtra(EXTRA_DAY, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return
        binding.dayPager.setCurrentItem(positionOf(anchor, LocalDate.ofEpochDay(day)), false)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putLong(STATE_DAY, shownDay().toEpochDay())
    }

    override fun onStart() {
        super.onStart()
        CalendarStore.addListener(calendarListener)
        ContextCompat.registerReceiver(
            this,
            dateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIME_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        if (anchor != LocalDate.now()) moveAnchor()
    }

    override fun onResume() {
        super.onResume()
        CalendarStore.requestSyncIfDue()
        adapter.refreshBound()
        renderReminder()
        renderAdd()
        tick.removeCallbacks(minute)
        tick.postDelayed(minute, 60_000L - System.currentTimeMillis() % 60_000L + 200)
    }

    override fun onPause() {
        tick.removeCallbacks(minute)
        super.onPause()
    }

    override fun onStop() {
        CalendarStore.removeListener(calendarListener)
        try {
            unregisterReceiver(dateReceiver)
        } catch (e: IllegalArgumentException) {
            // Not registered.
        }
        super.onStop()
    }

    private fun shownDay(): LocalDate = dayAt(anchor, binding.dayPager.currentItem)

    /** A new day, a time set or a zone change: today moves, the page keeps its date. */
    private fun moveAnchor() {
        val shown = shownDay()
        anchor = LocalDate.now()
        adapter.notifyDataSetChanged()
        binding.dayPager.setCurrentItem(positionOf(anchor, shown), false)
        renderHeader()
    }

    private fun renderHeader() {
        val day = shownDay()
        val (weekday, date) = headerTexts(day)
        val jump = todayJump(ChronoUnit.DAYS.between(anchor, day))
        val label = when (jump) {
            TodayJump.BACK_FROM_LATER -> getString(R.string.day_jump_back)
            TodayJump.FORWARD_FROM_EARLIER -> getString(R.string.day_jump_forward)
            null -> null
        }
        KidHeader.bind(this, binding.dayHeader, weekday, date, label) {
            binding.dayPager.setCurrentItem(DAY_CENTER, false)
        }
    }

    private fun headerTexts(day: LocalDate): Pair<String, String> = dayTexts(this, day, anchor)

    private class Today(val today: com.kidslauncher.mdm.day.TodayCalendar?)

    /** A reminder that is up shows under the header (design 69 §2.1) - the glance row's words, not tappable: the only
     * place a reminder reaches the kid while the phone is unlocked. */
    private fun renderReminder() {
        val app = applicationContext
        CalendarStore.run({ Today(CalendarStore.todayBlocking(app)) }) { loaded ->
            if (isDestroyed) return@run
            val up = loaded.today?.let { com.kidslauncher.mdm.day.reminderNow(it, CalendarStore.policy(), System.currentTimeMillis()) }
            binding.dayReminder.visibility = if (up == null) android.view.View.GONE else android.view.View.VISIBLE
            if (up != null) {
                val line = if (up.minutesLeft <= 1) getString(R.string.glance_reminder_now, up.title)
                else getString(R.string.glance_reminder_in, up.title, up.minutesLeft)
                binding.dayReminder.text = getString(R.string.day_reminder_line, line, com.kidslauncher.mdm.ui.ClockFormat.formatTime(this, up.beginMs))
            }
        }
    }

    companion object {
        /** The day to open on (epoch day), e.g. a reminder's. */
        const val EXTRA_DAY = "me.vibb.launcher.extra.DAY"
        private const val STATE_DAY = "day"

        fun intent(context: Context, day: LocalDate? = null): Intent =
            Intent(context, DayActivity::class.java).apply { day?.let { putExtra(EXTRA_DAY, it.toEpochDay()) } }

        /** The weekday ("Mandag") and the date ("26. oktober"; with the year when it isn't this year's), in Vibb's
         * language. */
        fun dayTexts(context: Context, day: LocalDate, today: LocalDate = LocalDate.now()): Pair<String, String> {
            val locale = context.resources.configuration.locales[0]
            val weekday = day.format(DateTimeFormatter.ofPattern("EEEE", locale)).replaceFirstChar { it.titlecase(locale) }
            val skeleton = if (day.year == today.year) "dMMMM" else "dMMMMy"
            val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton)
            return weekday to day.format(DateTimeFormatter.ofPattern(pattern, locale))
        }
    }
}

/** The pager: [Int.MAX_VALUE] pages, page `p` is day `anchor + (p - CENTER)`. Each page reads its day lazily and shows
 * nothing until it lands; a reload keeps the old rows until the new ones come. */
class DayPagerAdapter(
    private val activity: DayActivity,
    private val anchor: () -> LocalDate,
) : RecyclerView.Adapter<DayPagerAdapter.Page>() {

    class Page(val list: RecyclerView) : RecyclerView.ViewHolder(list) {
        val rows = DayListAdapter()
        var day: LocalDate? = null
    }

    private val bound = LinkedHashSet<Page>()

    override fun getItemCount(): Int = Int.MAX_VALUE

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Page {
        val list = LayoutInflater.from(parent.context).inflate(R.layout.item_day_page, parent, false) as RecyclerView
        list.layoutManager = LinearLayoutManager(parent.context)
        val page = Page(list)
        list.adapter = page.rows
        return page
    }

    override fun onBindViewHolder(holder: Page, position: Int) {
        val day = dayAt(anchor(), position)
        if (holder.day != day) holder.rows.submit(null)
        holder.day = day
        bound += holder
        load(holder, day)
    }

    override fun onViewRecycled(holder: Page) {
        bound -= holder
        holder.day = null
    }

    private fun load(page: Page, day: LocalDate) {
        CalendarStore.day(activity, day) { view: DayView? ->
            if (page.day == day && !activity.isDestroyed) page.rows.submit(view ?: DayView.EMPTY)
        }
    }

    /** The provider changed or a minute passed: every bound page reads its day again. */
    fun refreshBound() {
        for (page in bound.toList()) page.day?.let { load(page, it) }
    }
}
