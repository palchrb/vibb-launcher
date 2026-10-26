package com.kidslauncher.mdm.calendar

import android.Manifest
import android.accounts.Account
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.CalendarContract
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import android.provider.CalendarContract.Reminders
import android.util.Log
import androidx.core.content.ContextCompat
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.day.CalendarInfo
import com.kidslauncher.mdm.day.CalendarPolicy
import com.kidslauncher.mdm.day.DayView
import com.kidslauncher.mdm.day.InstanceRow
import com.kidslauncher.mdm.day.calendarKey
import com.kidslauncher.mdm.day.calendarPolicy
import com.kidslauncher.mdm.day.calendarShown
import com.kidslauncher.mdm.day.calendarSource
import com.kidslauncher.mdm.day.dayView
import com.kidslauncher.mdm.day.julianDay
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.QuickControls
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.dto.CalendarReport
import com.kidslauncher.mdm.server.dto.CalendarReportRow
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * Dagen's window onto Android's calendar provider (design 69 §2.4), which ICSx5 and DAVx5 feed. Read-only but for the
 * kid's own events (step 3, [KidEvents]) and the local "Min kalender" ([ensureKidCalendar]). Every provider call runs on
 * one background thread; the results reach the main thread. The rows of the last 9 days read are kept (an LRU) and
 * dropped by the provider's change notification, debounced to one reload a second. Nothing read here leaves the phone
 * but [report]'s calendars - never an event, a title or a time - and nothing is logged beyond counts and exception
 * classes.
 */
object CalendarStore {
    private const val LOG_TAG = "CalendarStore"
    private const val LRU_DAYS = 9
    private const val DEBOUNCE_MS = 1_000L
    private const val SYNC_EVERY_MS = 30 * 60_000L

    /** The local calendar Vibb One makes for the kid's own events (PLAN L7 (a)). */
    const val LOCAL_ACCOUNT = "Vibb One"
    const val LOCAL_NAME = "vibb-kid-calendar"

    /** What the provider holds: whether it can be read, and its calendars that sync events. */
    data class CalendarsState(val readable: Boolean, val calendars: List<CalendarInfo>, val accounts: Map<Long, Account> = emptyMap()) {
        val byId: Map<Long, CalendarInfo> get() = calendars.associateBy { it.id }

        override fun toString() = "CalendarsState(readable=$readable, ${calendars.size})"

        companion object {
            val UNKNOWN = CalendarsState(false, emptyList())
        }
    }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "calendar").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    private val rows = object : LinkedHashMap<LocalDate, List<InstanceRow>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, List<InstanceRow>>?) = size > LRU_DAYS
    }
    private val listeners = LinkedHashSet<() -> Unit>()
    private var observer: ContentObserver? = null
    private var lastSyncAsk = 0L

    @Volatile
    var state: CalendarsState = CalendarsState.UNKNOWN
        private set

    private val changed = Runnable {
        synchronized(rows) { rows.clear() }
        appContext?.let { refreshCalendars(it) }
    }

    @Volatile
    private var appContext: Context? = null

    /** Process start (`Application.initRest`): the provider's change notification and the calendars, off the main thread. */
    fun init(context: Context) {
        appContext = context.applicationContext
        worker.execute {
            try {
                grantOnStart(context.applicationContext)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't grant the calendar permissions: ${e.javaClass.simpleName}")
            }
        }
        refreshCalendars(context)
    }

    fun addListener(listener: () -> Unit) = main.post { listeners += listener }

    fun removeListener(listener: () -> Unit) = main.post { listeners -= listener }

    private fun notifyChanged() = main.post {
        for (l in listeners.toList()) {
            try {
                l()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A calendar listener failed: ${e.javaClass.simpleName}")
            }
        }
    }

    /** The parent's settings from the cached policy: an `Ok` cache's `calendar`, else none (Dagen off). */
    fun policy(): CalendarPolicy = try {
        (cachedPolicy() as? CachedPolicy.Ok)?.policy?.let { calendarPolicy(it.calendar) } ?: CalendarPolicy.UNKNOWN
    } catch (e: Exception) {
        CalendarPolicy.UNKNOWN
    }

    /** A sync accepted a policy: the rules and the calendars' settings apply at once. */
    fun policyChanged() {
        notifyChanged()
    }

    private fun canRead(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    fun canWrite(context: Context) =
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** `AppEnforcer.apply` while managed: Vibb One grants itself the calendar permissions (device owner; the kid can't
     * take them back). Only written when not granted (a re-grant re-notifies). */
    fun grantPermissions(context: Context, dpm: DevicePolicyManager, admin: ComponentName) {
        val before = canRead(context)
        QuickControls.selfGrantPermission(context, dpm, admin, Manifest.permission.READ_CALENDAR)
        QuickControls.selfGrantPermission(context, dpm, admin, Manifest.permission.WRITE_CALENDAR)
        if (!before && canRead(context)) refreshCalendars(context)
    }

    private fun grantOnStart(context: Context) {
        val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
        if (!dpm.isDeviceOwnerApp(context.packageName)) return
        grantPermissions(context, dpm, ComponentName(context, com.kidslauncher.mdm.server.MdmDeviceAdminReceiver::class.java))
    }

    /** Reads the calendars again (and registers for the provider's changes once it can), then tells the listeners. */
    fun refreshCalendars(context: Context) {
        val app = context.applicationContext
        appContext = app
        worker.execute {
            val next = readCalendars(app)
            state = next
            if (next.readable) main.post { watch(app) }
            notifyChanged()
        }
    }

    private fun watch(context: Context) {
        if (observer != null) return
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean) {
                // The provider notifies per sync batch: one reload a second at most.
                main.removeCallbacks(changed)
                main.postDelayed(changed, DEBOUNCE_MS)
            }
        }
        try {
            context.contentResolver.registerContentObserver(CalendarContract.CONTENT_URI, true, o)
            observer = o
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't watch the calendar provider: ${e.javaClass.simpleName}")
        }
    }

    private fun readCalendars(context: Context): CalendarsState {
        if (!canRead(context)) return CalendarsState.UNKNOWN
        return try {
            val out = ArrayList<CalendarInfo>()
            val accounts = HashMap<Long, Account>()
            context.contentResolver.query(
                Calendars.CONTENT_URI,
                arrayOf(
                    Calendars._ID, Calendars.ACCOUNT_TYPE, Calendars.ACCOUNT_NAME, Calendars.CALENDAR_DISPLAY_NAME,
                    Calendars.NAME, Calendars.SYNC_EVENTS, Calendars.CALENDAR_ACCESS_LEVEL,
                ),
                null, null, Calendars._ID,
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val type = c.getString(1).orEmpty()
                    val account = c.getString(2).orEmpty()
                    val name = c.getString(4)
                    out += CalendarInfo(
                        id = id,
                        key = calendarKey(type, account, name),
                        accountType = type,
                        displayName = c.getString(3) ?: name.orEmpty(),
                        syncEvents = c.getInt(5) == 1,
                        accessLevel = c.getInt(6),
                        vibbLocal = type == CalendarContract.ACCOUNT_TYPE_LOCAL && account == LOCAL_ACCOUNT && name == LOCAL_NAME,
                    )
                    if (type.isNotEmpty() && account.isNotEmpty()) accounts[id] = Account(account, type)
                }
            }
            CalendarsState(true, out, accounts)
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the calendars: ${e.javaClass.simpleName}")
            CalendarsState(false, emptyList())
        }
    }

    /** One day's instances, from the LRU or the provider (off the main thread). The provider is asked for the day and
     * its two neighbours, so its own time zone setting can't drop an event; [dayView] keeps the day's. */
    fun rowsBlocking(context: Context, day: LocalDate): List<InstanceRow>? {
        synchronized(rows) { rows[day] }?.let { return it }
        if (!canRead(context)) return null
        return try {
            val builder = Instances.CONTENT_BY_DAY_URI.buildUpon()
            ContentUris.appendId(builder, julianDay(day).toLong() - 1)
            ContentUris.appendId(builder, julianDay(day).toLong() + 1)
            val out = ArrayList<InstanceRow>()
            context.contentResolver.query(
                builder.build(),
                arrayOf(
                    Instances.EVENT_ID, Instances.BEGIN, Instances.END, Instances.ALL_DAY, Instances.TITLE,
                    Instances.EVENT_LOCATION, Instances.DESCRIPTION, Instances.CALENDAR_ID, Instances.STATUS,
                    Instances.SELF_ATTENDEE_STATUS, Instances.UID_2445, Instances.CUSTOM_APP_PACKAGE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += InstanceRow(
                        eventId = c.getLong(0),
                        begin = c.getLong(1),
                        end = c.getLong(2),
                        allDay = c.getInt(3) == 1,
                        title = c.getString(4),
                        location = c.getString(5),
                        description = c.getString(6),
                        calendarId = c.getLong(7),
                        status = if (c.isNull(8)) null else c.getInt(8),
                        selfStatus = if (c.isNull(9)) null else c.getInt(9),
                        uid = c.getString(10),
                        customApp = c.getString(11),
                    )
                }
            }
            synchronized(rows) { rows[day] = out }
            out
        } catch (e: Exception) {
            // A day the provider can't answer (an out-of-range year) is an empty day.
            Log.w(LOG_TAG, "Couldn't read a day: ${e.javaClass.simpleName}")
            emptyList()
        }
    }

    /** The day as the kid sees it, now (off the main thread); `null` = the provider can't be read. */
    fun dayBlocking(context: Context, day: LocalDate, now: Long = System.currentTimeMillis()): DayView? {
        val list = rowsBlocking(context, day) ?: return null
        return dayView(list, state.byId, policy(), day, ZoneId.systemDefault(), now, context.packageName)
    }

    /** [dayBlocking] on the worker, answered on the main thread. */
    fun day(context: Context, day: LocalDate, answer: (DayView?) -> Unit) {
        val app = context.applicationContext
        worker.execute {
            val view = dayBlocking(app, day)
            main.post { answer(view) }
        }
    }

    /** Anything on the worker, answered on the main thread. */
    fun <T> run(work: () -> T, answer: (T) -> Unit) {
        worker.execute {
            val result = try {
                work()
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Calendar work failed: ${e.javaClass.simpleName}")
                null
            }
            main.post { if (result != null) answer(result) }
        }
    }

    /** One event by id (the event's page), or `null` when it is gone. */
    data class EventDetail(
        val title: String?,
        val location: String?,
        val description: String?,
        val calendarId: Long,
        val allDay: Boolean,
        val uid: String?,
        val customApp: String?,
        val begin: Long,
        val end: Long,
        val timeZone: String?,
    ) {
        override fun toString() = "EventDetail($calendarId)"
    }

    fun eventBlocking(context: Context, eventId: Long): EventDetail? {
        if (!canRead(context)) return null
        return try {
            context.contentResolver.query(
                ContentUris.withAppendedId(Events.CONTENT_URI, eventId),
                arrayOf(
                    Events.TITLE, Events.EVENT_LOCATION, Events.DESCRIPTION, Events.CALENDAR_ID, Events.ALL_DAY,
                    Events.UID_2445, Events.CUSTOM_APP_PACKAGE, Events.DTSTART, Events.DTEND, Events.EVENT_TIMEZONE,
                    Events.DELETED,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst() || c.getInt(10) == 1) return null
                EventDetail(
                    title = c.getString(0),
                    location = c.getString(1),
                    description = c.getString(2),
                    calendarId = c.getLong(3),
                    allDay = c.getInt(4) == 1,
                    uid = c.getString(5),
                    customApp = c.getString(6),
                    begin = c.getLong(7),
                    end = if (c.isNull(8)) c.getLong(7) else c.getLong(8),
                    timeZone = c.getString(9),
                )
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read an event: ${e.javaClass.simpleName}")
            null
        }
    }

    /** The events' reminder minutes (method default, alert or alarm), by event id - the "own" reminders (§5.2). */
    fun remindersBlocking(context: Context, eventIds: Collection<Long>): Map<Long, List<Int>> {
        if (eventIds.isEmpty() || !canRead(context)) return emptyMap()
        return try {
            val out = HashMap<Long, MutableList<Int>>()
            context.contentResolver.query(
                Reminders.CONTENT_URI,
                arrayOf(Reminders.EVENT_ID, Reminders.MINUTES, Reminders.METHOD),
                "${Reminders.EVENT_ID} IN (${eventIds.joinToString(",") { "?" }})",
                eventIds.map { it.toString() }.toTypedArray(),
                null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val method = c.getInt(2)
                    if (method != Reminders.METHOD_DEFAULT && method != Reminders.METHOD_ALERT && method != Reminders.METHOD_ALARM) continue
                    out.getOrPut(c.getLong(0)) { ArrayList() } += c.getInt(1)
                }
            }
            out
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the reminders: ${e.javaClass.simpleName}")
            emptyMap()
        }
    }

    /** The status report's `calendar` (§4.2), read now on the sync's thread: the calendars that sync events, never an
     * event; `null` when the policy has no Dagen at all. */
    fun report(context: Context): CalendarReport? {
        val policy = policy()
        if (!policy.usable) return null
        val now = readCalendars(context.applicationContext)
        state = now
        if (!now.readable) return CalendarReport(false, emptyList(), policy.rulesSkipped)
        val names = HashMap<Long, String?>()
        try {
            context.contentResolver.query(Calendars.CONTENT_URI, arrayOf(Calendars._ID, Calendars.NAME), null, null, null)
                ?.use { c -> while (c.moveToNext()) names[c.getLong(0)] = c.getString(1) }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't read the calendars' sources: ${e.javaClass.simpleName}")
        }
        val rows = now.calendars.filter { it.syncEvents }.distinctBy { it.key }.take(50).map {
            CalendarReportRow(it.key, it.accountType, it.displayName.take(100), calendarSource(names[it.id]), it.accessLevel)
        }
        return CalendarReport(true, rows, policy.rulesSkipped)
    }

    /** Dagen's Home tile: a usable policy with Dagen on Home, a readable provider, and a calendar shown - or the kid's
     * own calendar to come (the "+" from day one). */
    fun tileShown(): Boolean {
        val policy = policy()
        val now = state
        return policy.usable && policy.onHome && now.readable &&
            (now.calendars.any { calendarShown(it, policy) } || (policy.kidAdds && policy.kidCalendarKey == null))
    }

    /** The kid's calendar now: the parent's pick when the phone has it and may write to it, else the phone's own; `null`
     * when the kid may add nothing. */
    fun kidCalendar(policy: CalendarPolicy = policy(), now: CalendarsState = state): CalendarInfo? {
        if (!policy.usable || !policy.kidAdds) return null
        val key = policy.kidCalendarKey
        return if (key != null) {
            now.calendars.firstOrNull { it.key == key && it.accessLevel >= Calendars.CAL_ACCESS_CONTRIBUTOR }
        } else {
            now.calendars.firstOrNull { it.vibbLocal }
        }
    }

    /** At Dagen's open: the phone's own "Min kalender" is made when the kid may add events and the parent picked no
     * calendar (`ACCOUNT_TYPE_LOCAL` through the sync-adapter URI, which a plain app may use for a local calendar). Off
     * the main thread. */
    fun ensureKidCalendar(context: Context) {
        val app = context.applicationContext
        worker.execute {
            val policy = policy()
            if (!policy.usable || !policy.kidAdds || policy.kidCalendarKey != null) return@execute
            if (state.calendars.any { it.vibbLocal } || !canWrite(app)) return@execute
            try {
                val uri = Calendars.CONTENT_URI.buildUpon()
                    .appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                    .appendQueryParameter(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
                    .appendQueryParameter(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    .build()
                val values = ContentValues().apply {
                    put(Calendars.ACCOUNT_NAME, LOCAL_ACCOUNT)
                    put(Calendars.ACCOUNT_TYPE, CalendarContract.ACCOUNT_TYPE_LOCAL)
                    put(Calendars.NAME, LOCAL_NAME)
                    put(Calendars.CALENDAR_DISPLAY_NAME, app.getString(R.string.day_kid_calendar_name))
                    put(Calendars.CALENDAR_COLOR, ContextCompat.getColor(app, R.color.kid_accent))
                    put(Calendars.CALENDAR_ACCESS_LEVEL, Calendars.CAL_ACCESS_OWNER)
                    put(Calendars.OWNER_ACCOUNT, LOCAL_ACCOUNT)
                    put(Calendars.SYNC_EVENTS, 1)
                    put(Calendars.VISIBLE, 1)
                    put(Calendars.CALENDAR_TIME_ZONE, ZoneId.systemDefault().id)
                }
                val made: Uri? = app.contentResolver.insert(uri, values)
                Log.i(LOG_TAG, if (made != null) "Made the kid's local calendar" else "The provider refused the kid's local calendar")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't make the kid's local calendar: ${e.javaClass.simpleName}")
            }
            state = readCalendars(app)
            notifyChanged()
        }
    }

    /** At Dagen's open (§6.3): a sync asked of each shown calendar's account, at most every 30 min - a rarely-run sync
     * app drops to a low standby bucket, and a device owner on top may ask. */
    fun requestSyncIfDue() {
        val now = SystemClock.elapsedRealtime()
        if (lastSyncAsk != 0L && now - lastSyncAsk < SYNC_EVERY_MS) return
        lastSyncAsk = now
        worker.execute {
            val policy = policy()
            val current = state
            val accounts = current.calendars
                .filter { calendarShown(it, policy) && it.accountType != CalendarContract.ACCOUNT_TYPE_LOCAL }
                .mapNotNull { current.accounts[it.id] }
                .toSet()
            for (account in accounts) {
                try {
                    ContentResolver.requestSync(
                        account,
                        CalendarContract.AUTHORITY,
                        Bundle().apply {
                            putBoolean(ContentResolver.SYNC_EXTRAS_MANUAL, true)
                            putBoolean(ContentResolver.SYNC_EXTRAS_EXPEDITED, true)
                        },
                    )
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Couldn't ask for a calendar sync: ${e.javaClass.simpleName}")
                }
            }
        }
    }

    /** The provider changed under us (the kid's own save): drop the kept days and tell the listeners. */
    fun invalidate() {
        main.removeCallbacks(changed)
        main.post(changed)
    }

    internal fun executeOnWorker(work: Runnable) = worker.execute(work)
}
