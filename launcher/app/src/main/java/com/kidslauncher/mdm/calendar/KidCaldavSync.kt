package com.kidslauncher.mdm.calendar

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.day.KidAnswer
import com.kidslauncher.mdm.day.PostedUnion
import com.kidslauncher.mdm.day.afterAnswer
import com.kidslauncher.mdm.day.afterBackDeletes
import com.kidslauncher.mdm.day.afterPost
import com.kidslauncher.mdm.day.backOps
import com.kidslauncher.mdm.day.calendarPolicy
import com.kidslauncher.mdm.day.kidAnswer
import com.kidslauncher.mdm.day.kidCaldavPolicy
import com.kidslauncher.mdm.day.kidSnapshot
import com.kidslauncher.mdm.day.nextApplied
import com.kidslauncher.mdm.day.shouldPost
import com.kidslauncher.mdm.server.CommandListenerService
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.time.ZoneId

/**
 * The kid's events for the CalDAV calendar the parent marked (design 72b D6.2): at a sync with a fresh policy, right
 * before [CalendarFeeds.refreshIfNeeded], the whole set of the kid's coming events in "Min kalender" is posted when
 * it differs from the last one the server took, the server has hand-backs, or the phone has some to acknowledge; the
 * answer's hand-backs (the parent's edits and deletes - the calendar wins) are written into "Min kalender" through
 * [CalendarStore] and acknowledged at the next post. Kept in prefs: the ids applied, and every uid posted since the
 * server last showed that set accepted with nothing pending (the feeds' skip set, D3.2). Never throws; a failed post
 * changes nothing on the phone (the next sync sends the set again). Nothing read here is logged beyond counts.
 */
object KidCaldavSync {
    private const val LOG_TAG = "KidCaldav"
    private const val PREFS = "kid_caldav"
    private const val KEY_CALENDAR = "calendar"
    private const val KEY_APPLIED = "applied"
    private const val KEY_POSTED_REV = "posted_rev"
    private const val KEY_POSTED_UIDS = "posted_uids"
    private const val KEY_UNION = "union"
    private const val KEY_POSTED_CUT = "posted_cut"

    private val main = Handler(Looper.getMainLooper())

    private suspend fun <T> onWorker(work: () -> T): T = suspendCancellableCoroutine { c ->
        CalendarStore.executeOnWorker { c.resumeWith(runCatching(work)) }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun list(context: Context, key: String): List<String> =
        prefs(context).getString(key, null)?.split(',')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun union(context: Context): PostedUnion = PostedUnion(
        rev = prefs(context).getString(KEY_POSTED_REV, null),
        uids = list(context, KEY_POSTED_UIDS).toSet(),
        union = list(context, KEY_UNION).toSet(),
        cut = prefs(context).getString(KEY_POSTED_CUT, null),
    )

    private fun save(context: Context, state: PostedUnion) {
        prefs(context).edit()
            .putString(KEY_POSTED_REV, state.rev)
            .putString(KEY_POSTED_UIDS, state.uids.sorted().joinToString(","))
            .putString(KEY_UNION, state.union.sorted().joinToString(","))
            .putString(KEY_POSTED_CUT, state.cut)
            .commit()
    }

    /** The uids posted since the server last showed them taken with nothing pending: the feeds skip them (D3.2). */
    fun postedUnion(context: Context): Set<String> = union(context).union

    /** After the kid's save, change or delete (D1.5): a sync soon, while a CalDAV calendar is marked. */
    fun requestSoon(context: Context) {
        if (CalendarStore.policy().kidCaldav != null) CommandListenerService.requestSync(context.applicationContext, "kid_event")
    }

    suspend fun syncIfNeeded(context: Context, api: MdmApi, calendar: JsonElement?) {
        try {
            sync(context.applicationContext, api, calendar)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The kid's events weren't handed over: ${e.javaClass.simpleName}")
        }
    }

    private suspend fun sync(app: Context, api: MdmApi, calendar: JsonElement?) {
        val policy = kidCaldavPolicy(calendar)
        val prefs = prefs(app)
        if (policy == null || prefs.getString(KEY_CALENDAR, null) != policy.id) {
            // No calendar marked, or another one: nothing posted counts any more (the calendar holds what it holds).
            // Written only when something is stored (launcher QA N1: no disk write at every sync of an unmarked phone).
            if (prefs.all.isNotEmpty()) prefs.edit().clear().commit()
            if (policy == null) return
            prefs.edit().putString(KEY_CALENDAR, policy.id).commit()
        }
        if (!calendarPolicy(calendar).usable || !CalendarStore.canWrite(app)) return
        // A full read only (D1.3): the provider readable and "Min kalender" there.
        val rows = onWorker { CalendarStore.kidEventsBlocking(app) } ?: return
        val applied = list(app, KEY_APPLIED)
        val tag = CalendarStore.phoneTag(app)
        val snapshot = kidSnapshot(rows, app.packageName, tag, System.currentTimeMillis(), applied)
        var state = union(app)
        if (!shouldPost(snapshot, policy, applied, state)) {
            save(app, afterAnswer(state, policy.accepted, policy.pending))
            return
        }
        // Posted: its uids skipped from the feeds until the server shows the set taken with nothing pending.
        state = afterPost(state, snapshot)
        save(app, state)
        val response = api.kidEvents(snapshot.body(policy.id).toRequestBody("application/json".toMediaType()))
        if (response.code() == 429) {
            val wait = response.headers()["Retry-After"]?.trim()?.toLongOrNull()?.coerceIn(1, 600) ?: 10
            response.errorBody()?.close()
            // D1.5's third moment (QA N6): a quick second edit doesn't wait a whole sync.
            main.postDelayed({ CommandListenerService.requestSync(app, "kid_event_retry") }, wait * 1000 + 500)
            return
        }
        if (!response.isSuccessful) {
            Log.i(LOG_TAG, "The server answered ${response.code()} to the kid's events")
            response.errorBody()?.close()
            return
        }
        val answer: KidAnswer = response.body()?.use { kidAnswer(ServerJson.parseToJsonElement(it.string())) } ?: run {
            Log.w(LOG_TAG, "The kid's events' answer doesn't read")
            return
        }
        state = afterAnswer(state, answer.accepted, answer.pending)
        val ops = backOps(answer.back, rows, app.packageName, tag, ZoneId.systemDefault().id)
        val acked = if (ops.isEmpty()) emptyList() else onWorker { CalendarStore.applyKidBackBlocking(app, ops) }
        // A delete applied (or left) takes its uid out of the skip set at once (launcher QA M2).
        val deletesDone = answer.back.filter { it.delete && it.id in acked }.mapTo(HashSet()) { it.uid }
        save(app, afterBackDeletes(state, deletesDone))
        prefs.edit().putString(KEY_APPLIED, nextApplied(list(app, KEY_APPLIED), snapshot.applied, acked).joinToString(",")).commit()
        // The acknowledgements go soon (N7): one more sync, after the server's 10 s gap.
        if (acked.isNotEmpty()) main.postDelayed({ CommandListenerService.requestSync(app, "kid_event_ack") }, 11_000)
        Log.i(LOG_TAG, "Handed over ${snapshot.events.size} events (cut ${snapshot.cut}); ${acked.size} of ${answer.back.size} hand-backs applied")
    }
}
