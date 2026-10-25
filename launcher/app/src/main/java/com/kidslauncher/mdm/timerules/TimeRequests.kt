package com.kidslauncher.mdm.timerules

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.TimeRequestAnswer
import com.kidslauncher.mdm.server.dto.TimeRequestBody
import kotlinx.serialization.Serializable
import java.security.SecureRandom

/*
 * The kid's request for more screen time (design r13 §2.4, `TimeRequestsTest`): the pure rules - the record, the
 * phone's own count of three a day (D5), the state the notice shows, the tap's step and the server's answer - and the
 * glue that keeps them in device-protected prefs and uploads with the sync. Nothing personal: an id, instants, counts.
 */

/** Requests a day the phone sends at most (D5); the server answers a fourth `capped` anyway. */
const val TIME_REQUESTS_A_DAY = 3

/** A request is open this long: "Spurt" shows and the phone retries the upload until then (§2.4). */
const val TIME_REQUEST_OPEN_MS = 30 * 60_000L

/** The request's own record. [day] is the ledger's day it was asked on, [liftIdAtAsk] the largest lift id in the
 * policy then (lift ids only grow on the server: any newer lift is the answer). */
@Serializable
data class TimeRequestRecord(
    val id: String,
    val askedAtMs: Long,
    val openUntilMs: Long,
    val day: String,
    val liftIdAtAsk: Long,
    val state: TimeRequestPhase,
)

@Serializable
enum class TimeRequestPhase { PENDING, ASKED }

/** The phone's own count of the requests the server took on [day] (D5). */
@Serializable
data class TimeRequestCount(val day: String, val accepted: Int)

/** What the notice shows of the request. */
sealed interface RequestState {
    data object Idle : RequestState

    data object Pending : RequestState

    data class Asked(val atMs: Long) : RequestState

    data object Capped : RequestState
}

/**
 * The request's state at [nowMs] on the ledger's [day], with [maxLiftId] the largest lift id in the policy now. A
 * record of today that is still open - not past `openUntilMs`, no lift newer than the one at the ask - is Pending or
 * Asked (so "Spurt" shows after the day's third accepted request too); else Capped when today's accepted count is
 * [TIME_REQUESTS_A_DAY]; else Idle (no record, another day's, its 30 minutes over, or answered by a lift).
 */
fun requestState(record: TimeRequestRecord?, count: TimeRequestCount?, nowMs: Long, day: String?, maxLiftId: Long): RequestState {
    if (record != null && day != null && record.day == day && nowMs < record.openUntilMs && maxLiftId <= record.liftIdAtAsk) {
        return when (record.state) {
            TimeRequestPhase.PENDING -> RequestState.Pending
            TimeRequestPhase.ASKED -> RequestState.Asked(record.askedAtMs)
        }
    }
    if (count != null && day != null && count.day == day && count.accepted >= TIME_REQUESTS_A_DAY) return RequestState.Capped
    return RequestState.Idle
}

/** What a tap on "Be om mer tid" does. */
enum class AskStep { ASK, IGNORE }

/** [AskStep.ASK] only while enrolled, with a budget today, outside the pause and with nothing open or capped; a tap on
 * a stale notification otherwise does nothing. */
fun askStep(enrolled: Boolean, budgetToday: Boolean, windowOpen: Boolean, state: RequestState): AskStep =
    if (enrolled && budgetToday && !windowOpen && state == RequestState.Idle) AskStep.ASK else AskStep.IGNORE

/** The server's answer to the upload, as the phone acts on it (§2.4's table). */
enum class TimeRequestVerdict {
    /** 200 `{"accepted":true}`: Asked, today's count + 1. */
    ACCEPTED,

    /** 200 `{"accepted":false,"reason":"capped"}`: dropped, the count set to three. */
    CAPPED,

    /** 200 `{"accepted":false,"reason":"stale"}`, another 4xx (but 401, 408, 429), a 200 that doesn't parse: dropped,
     * the action back. */
    DROPPED,

    /** 404, an old server: dropped, nothing shown - the action stays (D5). */
    NO_ROUTE,

    /** 401, 408, 429, 5xx, no answer: kept, retried with the next sync until its 30 minutes are over. */
    RETRY,
}

/** The verdict for HTTP [code] (`null` = no answer) and its [body]. */
fun timeRequestAnswer(code: Int?, body: String?): TimeRequestVerdict = when {
    code == null || code == 401 || code == 408 || code == 429 || code >= 500 -> TimeRequestVerdict.RETRY
    code == 404 -> TimeRequestVerdict.NO_ROUTE
    code == 200 -> {
        val answer = try {
            body?.let { ServerJson.decodeFromString(TimeRequestAnswer.serializer(), it) }
        } catch (e: Exception) {
            null
        }
        when {
            answer == null -> TimeRequestVerdict.DROPPED
            answer.accepted -> TimeRequestVerdict.ACCEPTED
            answer.reason == "capped" -> TimeRequestVerdict.CAPPED
            else -> TimeRequestVerdict.DROPPED
        }
    }
    else -> TimeRequestVerdict.DROPPED
}

/** What the sync does with the stored record (qa-r13-rule-card-code Q-3). */
enum class UploadStep {
    /** A PENDING record still open: post it. */
    SEND,

    /** A PENDING record no longer open - its 30 minutes over, another day's, or answered by a lift that came meanwhile
     * (a lift while offline): dropped unsent, so no stale push and no "Asked" row after the answer. */
    DROP,

    /** Nothing to send (no record, or one already taken). */
    NONE,
}

/** The step for [record] at [nowMs] on the ledger's [day], with [maxLiftId] the policy's largest lift id now. */
fun uploadStep(record: TimeRequestRecord?, count: TimeRequestCount?, nowMs: Long, day: String?, maxLiftId: Long): UploadStep = when {
    record == null || record.state != TimeRequestPhase.PENDING -> UploadStep.NONE
    requestState(record, count, nowMs, day, maxLiftId) == RequestState.Pending -> UploadStep.SEND
    else -> UploadStep.DROP
}

/**
 * What the answer for the record [sentId] writes (mutation L15): `null` - nothing - when the stored record is another
 * one now (a newer request written while the upload ran) or gone; else [afterTimeRequestAnswer] of it.
 */
fun recordAfterUpload(
    current: TimeRequestRecord?,
    sentId: String,
    count: TimeRequestCount?,
    verdict: TimeRequestVerdict,
): Pair<TimeRequestRecord?, TimeRequestCount?>? =
    if (current == null || current.id != sentId) null else afterTimeRequestAnswer(current, count, verdict)

/** The record and the count after [verdict] for [record]. */
fun afterTimeRequestAnswer(
    record: TimeRequestRecord,
    count: TimeRequestCount?,
    verdict: TimeRequestVerdict,
): Pair<TimeRequestRecord?, TimeRequestCount?> = when (verdict) {
    TimeRequestVerdict.ACCEPTED -> record.copy(state = TimeRequestPhase.ASKED) to
        TimeRequestCount(record.day, (count?.takeIf { it.day == record.day }?.accepted ?: 0) + 1)
    TimeRequestVerdict.CAPPED -> null to TimeRequestCount(record.day, TIME_REQUESTS_A_DAY)
    TimeRequestVerdict.DROPPED, TimeRequestVerdict.NO_ROUTE -> null to count
    TimeRequestVerdict.RETRY -> record to count
}

/** A new request's id: 32 lowercase hex characters. */
fun newTimeRequestId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

/** The largest lift id in [policy] (0 without one). */
fun maxLiftId(policy: TimePolicy?): Long = policy?.lifts?.maxOfOrNull { it.id } ?: 0L

private const val LOG_TAG = "TimeRequests"

/** Device-protected prefs file for the record and the count (one `commit()` each write, under [TimeRequests]'s lock). */
private const val PREFS = "time_request"
private const val RECORD_KEY = "record_v1"
private const val COUNT_KEY = "count_v1"

/** The Android side: the record and the count in DE prefs, the tap, the upload with the sync. */
object TimeRequests {
    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun <T> read(prefs: SharedPreferences, key: String, decode: (String) -> T): T? = try {
        prefs.getString(key, null)?.let(decode)
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Unreadable $key: ${e.javaClass.simpleName}")
        null
    }

    @Synchronized
    fun record(context: Context): TimeRequestRecord? =
        read(prefs(context), RECORD_KEY) { ServerJson.decodeFromString(TimeRequestRecord.serializer(), it) }

    @Synchronized
    fun count(context: Context): TimeRequestCount? =
        read(prefs(context), COUNT_KEY) { ServerJson.decodeFromString(TimeRequestCount.serializer(), it) }

    @Synchronized
    private fun write(context: Context, record: TimeRequestRecord?, count: TimeRequestCount?) {
        val edit = prefs(context).edit()
        if (record == null) edit.remove(RECORD_KEY) else edit.putString(RECORD_KEY, ServerJson.encodeToString(TimeRequestRecord.serializer(), record))
        if (count == null) edit.remove(COUNT_KEY) else edit.putString(COUNT_KEY, ServerJson.encodeToString(TimeRequestCount.serializer(), count))
        if (!edit.commit()) Log.w(LOG_TAG, "Couldn't save the request for more time")
    }

    /** The state for [snap] (its ledger's day, its policy's lifts) at the snapshot's wall clock. */
    fun state(context: Context, snap: TimeRulesRuntime.Snapshot): RequestState =
        state(context, snap.ledger?.day, snap.clocks.wallMs, maxLiftId(snap.policy))

    /** The state on the ledger's [day] at [nowMs], with [maxLiftId] the policy's largest lift id. */
    fun state(context: Context, day: String?, nowMs: Long, maxLiftId: Long): RequestState =
        requestState(record(context), count(context), nowMs, day, maxLiftId)

    /** When an open request's 30 minutes end, for the boundary alarm (R13-8: a stored instant, no handler). */
    fun openUntilMs(context: Context): Long? = try {
        record(context)?.openUntilMs
    } catch (e: Exception) {
        null
    }

    /**
     * The tap (`TimeAskReceiver`, main thread): with [AskStep.ASK] a new PENDING record (the action goes at once with
     * the refresh), then a sync; else one debug line and nothing - no toast, no sound.
     */
    @Synchronized
    fun ask(context: Context, enrolled: Boolean, windowOpen: Boolean, snap: TimeRulesRuntime.Snapshot): Boolean {
        val budgetToday = snap.budget?.effectiveMinutes != null
        val step = askStep(enrolled, budgetToday, windowOpen, state(context, snap))
        if (step == AskStep.IGNORE) {
            Log.d(LOG_TAG, "A tap on \"Be om mer tid\" ignored")
            return false
        }
        val now = snap.clocks.wallMs
        val day = snap.ledger?.day ?: return false
        val record = TimeRequestRecord(newTimeRequestId(), now, now + TIME_REQUEST_OPEN_MS, day, maxLiftId(snap.policy), TimeRequestPhase.PENDING)
        write(context, record, count(context))
        return true
    }

    /**
     * With the sync, after the status report (`performMdmSync`, behind the sync mutex): a PENDING record still open is
     * posted, and [timeRequestAnswer] decides; an expired one goes unsent. The notice is refreshed after any change.
     */
    suspend fun upload(context: Context, api: MdmApi) {
        val record = record(context) ?: return
        if (record.state != TimeRequestPhase.PENDING) return
        val now = System.currentTimeMillis()
        // Q-3: the policy this sync just fetched - a lift that came while the request waited is its answer.
        val policy = com.kidslauncher.mdm.server.KidModeEnforcer.timePolicyOf(com.kidslauncher.mdm.server.currentPolicyDecision().policy)
        val day = policy?.let { TimeRulesRuntime.ledger(context, it).day }
        when (uploadStep(record, count(context), now, day, maxLiftId(policy))) {
            UploadStep.NONE -> return
            UploadStep.DROP -> {
                // Over, another day's, or answered meanwhile: nothing sent, the action back.
                synchronized(this) {
                    if (record(context)?.id == record.id) write(context, null, count(context))
                }
                changed(context)
                return
            }
            UploadStep.SEND -> {}
        }
        var code: Int? = null
        var body: String? = null
        try {
            val response = api.sendTimeRequest(TimeRequestBody(record.id, record.askedAtMs))
            code = response.code()
            body = response.body()?.use { it.string() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(LOG_TAG, "The request for more time didn't get through: ${e.javaClass.simpleName}")
        }
        val verdict = timeRequestAnswer(code, body)
        Log.i(LOG_TAG, "Request for more time: $verdict${code?.let { " (HTTP $it)" } ?: ""}")
        if (verdict == TimeRequestVerdict.RETRY) return
        synchronized(this) {
            // Only the record that was sent: a newer one written meanwhile stays.
            val (next, count) = recordAfterUpload(record(context), record.id, count(context), verdict) ?: return
            write(context, next, count)
        }
        changed(context)
    }

    /** The record changed: the notice again, and the boundary alarm for the new `openUntilMs` (Q-4). */
    private fun changed(context: Context) {
        AnchorRefresh.request(context)
        TimeRuleAlarm.schedule(context)
    }
}

/** The anchor's notice refreshed from anywhere (the sync's thread included): posted to the main thread. */
object AnchorRefresh {
    fun request(context: Context) {
        val app = context.applicationContext
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                com.kidslauncher.mdm.server.AnchorNotice.refreshNow(app)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Notice refresh failed: ${e.javaClass.simpleName}")
            }
        }
    }
}
