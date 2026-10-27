package com.kidslauncher.mdm.calendar

import android.content.Context
import android.util.Log
import com.kidslauncher.mdm.day.CalendarPolicy
import com.kidslauncher.mdm.day.FEED_MAPPING
import com.kidslauncher.mdm.day.FeedRef
import com.kidslauncher.mdm.day.calendarPolicy
import com.kidslauncher.mdm.day.feedBatches
import com.kidslauncher.mdm.day.feedCalendarsGone
import com.kidslauncher.mdm.day.feedDocument
import com.kidslauncher.mdm.day.feedFetch
import com.kidslauncher.mdm.day.feedPlan
import com.kidslauncher.mdm.day.runPass
import com.kidslauncher.mdm.day.seriesRows
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.dto.CalendarFeedReportRow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import java.time.ZoneId

/**
 * The parent's calendar links on the phone (design 72 §4): at a sync with a fresh policy, each link whose set moved is
 * fetched (`GET /api/devices/calendar/feeds/{id}`) and written into a LOCAL read-only calendar of its own - account
 * "Vibb One", NAME `vibb-feed-<uid>`, `CAL_ACCESS_READ` - by the pure `day/FeedPlan.kt` (the diff, the batches, the
 * pass) and [CalendarStore]'s feed writes (the provider's only writer); a link gone from a fully readable
 * `calendar.feeds` takes its calendar with it. The calendar's sync columns: `CAL_SYNC1` the set's rev written last,
 * `CAL_SYNC2` [FEED_MAPPING], `CAL_SYNC3` the series refused at the last pass, `CAL_SYNC4` the series whose zone this
 * phone doesn't know, `CAL_SYNC5` the zone floating series were written in. Provider work runs on the store's worker;
 * nothing read here is logged beyond counts, indexes and exception classes.
 */
object CalendarFeeds {
    private const val LOG_TAG = "CalendarFeeds"

    private suspend fun <T> onWorker(work: () -> T): T = suspendCancellableCoroutine { c ->
        CalendarStore.executeOnWorker { c.resumeWith(runCatching(work)) }
    }

    /** After every accepted fresh policy (`MdmSyncWorker`): the links' calendars made, renamed, filled and deleted.
     * Never throws; a failed GET or an unreachable provider changes nothing (the next sync tries again). */
    suspend fun refreshIfNeeded(context: Context, api: MdmApi, calendar: JsonElement?) {
        try {
            val policy = calendarPolicy(calendar)
            val app = context.applicationContext
            if (!policy.usable || !CalendarStore.canWrite(app)) return
            val zone = ZoneId.systemDefault()
            val calendars = onWorker { gone(app, policy, CalendarStore.feedCalendarsBlocking(app)) } ?: return
            var changed = false
            for (feed in policy.feeds) {
                changed = refreshOne(app, api, feed, calendars[feed.id], zone) || changed
            }
            if (changed) CalendarStore.invalidate()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Calendar links failed: ${e.javaClass.simpleName}")
        }
    }

    /** The link calendars the policy no longer names deleted (only with a fully readable `feeds`); the rest by uid.
     * `null` when the provider can't be read. */
    private fun gone(context: Context, policy: CalendarPolicy, calendars: List<CalendarStore.FeedCalendar>?): Map<String, CalendarStore.FeedCalendar>? {
        calendars ?: return null
        val doomed = feedCalendarsGone(policy, calendars.map { it.uid }).toSet()
        for (c in calendars.filter { it.uid in doomed }) CalendarStore.deleteFeedCalendarBlocking(context, c.id)
        return calendars.filter { it.uid !in doomed }.associateBy { it.uid }
    }

    /** One link: its calendar made or renamed, then its set fetched and applied when [feedFetch] says so. `true` when
     * anything was written. */
    private suspend fun refreshOne(context: Context, api: MdmApi, feed: FeedRef, existing: CalendarStore.FeedCalendar?, zone: ZoneId): Boolean {
        val need = feedFetch(existing != null, existing?.appliedRev, feed.rev, existing?.mapping, existing?.floatingZone, zone.id)
        val calendarId = onWorker { CalendarStore.ensureFeedCalendarBlocking(context, feed, existing) } ?: return false
        val renamed = existing == null || existing.displayName != feed.name
        need ?: return renamed
        val answer = try {
            api.calendarFeed(feed.id, need.ifNoneMatch)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "A calendar link's set couldn't be fetched: ${e.javaClass.simpleName}")
            return renamed
        }
        if (!answer.isSuccessful) {
            if (answer.code() != 304) Log.w(LOG_TAG, "A calendar link's set answered ${answer.code()}")
            answer.errorBody()?.close()
            return renamed
        }
        val doc = answer.body()?.use { feedDocument(it.string()) }?.takeIf { it.id == feed.id } ?: run {
            Log.w(LOG_TAG, "A calendar link's set doesn't read")
            return renamed
        }
        val series = doc.events.mapNotNull { seriesRows(it, zone) }
        val unknown = series.count { it.unknownZone }
        val floating = if (series.any { it.floating }) zone.id else ""
        onWorker {
            val stored = CalendarStore.feedRowsBlocking(context, calendarId) ?: return@onWorker
            val plan = feedPlan(series, stored, zone.id, existing?.mapping == FEED_MAPPING)
            val order = series.withIndex().associate { (i, s) -> s.uid to i }
            val result = runPass(
                feedBatches(plan),
                indexOf = { order[it.uid] ?: -1 },
                delete = { ids -> CalendarStore.deleteFeedRowsBlocking(context, ids) },
                insert = { batch -> CalendarStore.insertFeedSeriesBlocking(context, calendarId, batch) },
            )
            if (result.refused > 0) Log.w(LOG_TAG, "The provider refused series ${result.refusedIndexes} of a calendar link")
            if (!result.writeRev) {
                Log.w(LOG_TAG, "The provider went away mid-pass; the next sync goes on")
                return@onWorker
            }
            CalendarStore.markFeedAppliedBlocking(context, calendarId, doc.rev, result.refused, unknown, floating)
            Log.i(LOG_TAG, "Applied a calendar link: ${plan.deletes.size} rows deleted, ${plan.inserts.size} series written")
        }
        return true
    }

    /** The status report's `calendar.feeds` (on the sync's thread): each link calendar's applied rev and counts. */
    fun report(context: Context): List<CalendarFeedReportRow> =
        (CalendarStore.feedCalendarsBlocking(context.applicationContext) ?: emptyList()).map {
            CalendarFeedReportRow(it.uid, it.appliedRev?.takeIf { rev -> rev.isNotEmpty() }, it.refused, it.unknownZone)
        }
}
