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
 * The kid's request for more screen time (design r13 §2.4, design 100 §14, `TimeRequestsTest`): the pure rules - the
 * record, the phone's own count of three a day (D5), the state the notice and the time-rule screen show, the tap's step
 * and the server's answer - and the glue that keeps them in device-protected prefs and uploads with the sync. One
 * request is open at a time, until a parent answers it (the policy's `time_policy.answer`) or the phone's day ends.
 * Nothing personal: an id, instants, counts.
 */

/** Requests a day the phone sends at most (D5); the server answers a fourth `capped` anyway. "Du har spurt tre ganger
 * i dag." says three (`lock_ask_capped`, pinned in `TimeRequestsTest`). */
const val TIME_REQUESTS_A_DAY = 3

/** The request's own record. [day] is the ledger's day it was asked on, [liftIdAtAsk] the largest lift id in the
 * policy then (lift ids only grow on the server: on an older server any newer lift is the answer, §14.7).
 * [openUntilMs] is r13's 30 minutes, gone in design 100 (§14.1): kept with a default so a stored `record_v1` still
 * decodes; a new record leaves it 0 (not written) and nothing reads it. */
@Serializable
data class TimeRequestRecord(
    val id: String,
    val askedAtMs: Long,
    val openUntilMs: Long = 0,
    val day: String,
    val liftIdAtAsk: Long,
    val state: TimeRequestPhase,
)

@Serializable
enum class TimeRequestPhase { PENDING, ASKED }

/** The phone's own count of the requests the server took on [day] (D5). */
@Serializable
data class TimeRequestCount(val day: String, val accepted: Int)

/** Where the request stands (design 100 §14.1). */
sealed interface RequestState {
    /** Nothing open, the day's three not used: the kid may ask. */
    data object Idle : RequestState

    /** Tapped, not taken by the server yet (being sent, or offline). */
    data class Pending(val atMs: Long) : RequestState

    /** Taken by the server, no answer yet (r13's Asked). */
    data class Waiting(val atMs: Long) : RequestState

    /** A parent answered it: [no], or a yes of [minutes]; [by] the display name (`null` without one, D11); [atMs] when
     * (0 from an older server). [canAskAgain]: the day's accepted count is under [TIME_REQUESTS_A_DAY]. */
    data class Answered(val no: Boolean, val by: String?, val atMs: Long, val minutes: Int?, val canAskAgain: Boolean) : RequestState

    /** The day's three were asked and nothing of today is open or answered on the phone. */
    data object Capped : RequestState
}

/**
 * The request's state on the ledger's [day] with [policy] (its answer and lifts; design 100 §14.1). A record of today
 * is open - Pending or Waiting - until the policy carries a parent's answer to its id (this server, `request_answers`)
 * or, on an older server, a lift newer than the one at the ask closes it (r13's rule, §14.7); then it is Answered. With
 * no record of today: Capped when today's accepted count is [TIME_REQUESTS_A_DAY], else Idle. No time limit: the
 * day's end closes it (another day's record is no record).
 */
fun requestState(record: TimeRequestRecord?, count: TimeRequestCount?, day: String?, policy: TimePolicy?): RequestState {
    if (day == null) return RequestState.Idle
    val accepted = count?.takeIf { it.day == day }?.accepted ?: 0
    val canAskAgain = accepted < TIME_REQUESTS_A_DAY
    if (record != null && record.day == day) {
        val answered = if (policy?.requestAnswers == true) {
            policy.answer?.takeIf { it.requestId == record.id }?.let { answerState(it, policy, canAskAgain) }
        } else {
            // Code QA L4: any newer lift closes it (§14.7), but only screen time a parent gave is "Du fikk N min mer" - a
            // rule pause or the parent code's pause closes it with no words of time.
            val newer = policy?.lifts.orEmpty().filter { it.id > record.liftIdAtAsk }
            newer.maxByOrNull { it.id }?.let { lift ->
                val given = newer.filter { it.target == TARGET_BUDGET && it.phoneKey == null }.maxByOrNull { it.id }
                RequestState.Answered(
                    no = false, by = cleanBy((given ?: lift).by), atMs = (given ?: lift).atMs, minutes = given?.minutes, canAskAgain = canAskAgain,
                )
            }
        }
        if (answered != null) return answered
        return when (record.state) {
            TimeRequestPhase.PENDING -> RequestState.Pending(record.askedAtMs)
            TimeRequestPhase.ASKED -> RequestState.Waiting(record.askedAtMs)
        }
    }
    return if (canAskAgain) RequestState.Idle else RequestState.Capped
}

/** [answer] as an Answered state: "no", or "yes" with its minutes (the answer's own, else its lift's); `null` for an
 * answer word this launcher doesn't know (the request stays open). */
fun answerState(answer: TimeAnswer, policy: TimePolicy?, canAskAgain: Boolean): RequestState.Answered? {
    val by = cleanBy(answer.by)
    return when (answer.answer) {
        "no" -> RequestState.Answered(true, by, answer.atMs, null, canAskAgain)
        "yes" -> RequestState.Answered(
            false, by, answer.atMs,
            answer.minutes ?: policy?.lifts?.firstOrNull { it.id == answer.liftId }?.minutes,
            canAskAgain,
        )
        else -> null
    }
}

/** What a tap on "Be om mer tid" does. */
enum class AskStep { ASK, IGNORE }

/** [AskStep.ASK] only while enrolled, with a budget today, outside the pause, with nothing open and the day's three not
 * used (Idle, or Answered with [RequestState.Answered.canAskAgain]); a tap on a stale notification or screen otherwise
 * does nothing. */
fun askStep(enrolled: Boolean, budgetToday: Boolean, windowOpen: Boolean, state: RequestState): AskStep {
    val askable = state == RequestState.Idle || (state is RequestState.Answered && state.canAskAgain)
    return if (enrolled && budgetToday && !windowOpen && askable) AskStep.ASK else AskStep.IGNORE
}

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

    /** 401, 408, 429, 5xx, no answer: kept, retried with the next sync while it is of today (design 100 §14.1). */
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

/** What the sync does with the stored record (qa-r13-rule-card-code Q-3, design 100 §14.1). */
enum class UploadStep {
    /** A PENDING record of today, still open: post it (offline all afternoon, it goes when the phone is back). */
    SEND,

    /** A PENDING record no longer to send - another day's, or answered by a lift on an older server meanwhile (a lift
     * while offline, §14.7): dropped unsent, so no stale push and no open request after the answer. */
    DROP,

    /** Nothing to send (no record, or one already taken). */
    NONE,

    /** A PENDING record this server answered already: the server took it (its 200 was lost, a retry) and a parent
     * answered before the next sync - counted as taken (ACCEPTED's step: ASKED, today's count + 1; code QA L1), nothing
     * sent. Its answer shows. */
    COUNT,
}

/** The step for [record] on the ledger's [day], with [policy] the policy just fetched. */
fun uploadStep(record: TimeRequestRecord?, count: TimeRequestCount?, day: String?, policy: TimePolicy?): UploadStep {
    if (record == null || record.state != TimeRequestPhase.PENDING) return UploadStep.NONE
    return when (requestState(record, count, day, policy)) {
        is RequestState.Pending -> UploadStep.SEND
        is RequestState.Answered -> if (policy?.requestAnswers == true) UploadStep.COUNT else UploadStep.DROP
        else -> UploadStep.DROP
    }
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

    /** The state for [snap] (its ledger's day, its policy's answer and lifts). */
    fun state(context: Context, snap: TimeRulesRuntime.Snapshot): RequestState =
        state(context, snap.ledger?.day, snap.policy)

    /** The state on the ledger's [day] with [policy]. */
    fun state(context: Context, day: String?, policy: TimePolicy?): RequestState =
        requestState(record(context), count(context), day, policy)

    /** When the shade's note changes next by itself, for the boundary alarm ([noteChangeMs], QA R2-L3: a stored
     * instant, no handler). */
    fun noteChangeMs(context: Context, day: String?, policy: TimePolicy?, nowMs: Long): Long? = try {
        noteChangeMs(state(context, day, policy), policy, nowMs)
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
        val record = TimeRequestRecord(
            id = newTimeRequestId(), askedAtMs = now, day = day, liftIdAtAsk = maxLiftId(snap.policy), state = TimeRequestPhase.PENDING,
        )
        write(context, record, count(context))
        return true
    }

    /**
     * With the sync, after the status report (`performMdmSync`, behind the sync mutex): a PENDING record of today still
     * open is posted, and [timeRequestAnswer] decides; another day's, or one an older server's lift answered, goes
     * unsent. The notice is refreshed after any change.
     */
    suspend fun upload(context: Context, api: MdmApi) {
        val record = record(context) ?: return
        if (record.state != TimeRequestPhase.PENDING) return
        // Q-3: the policy this sync just fetched - an answer (or, on an older server, a lift) that came while it waited.
        val policy = com.kidslauncher.mdm.server.KidModeEnforcer.timePolicyOf(com.kidslauncher.mdm.server.currentPolicyDecision().policy)
        val day = policy?.let { TimeRulesRuntime.ledger(context, it).day }
        when (uploadStep(record, count(context), day, policy)) {
            UploadStep.NONE -> return
            UploadStep.DROP -> {
                // Another day's, or answered meanwhile by an older server's lift: nothing sent.
                synchronized(this) {
                    if (record(context)?.id == record.id) write(context, null, count(context))
                }
                changed(context)
                return
            }
            UploadStep.COUNT -> {
                // Code QA L1: the server took it - the day's count follows, so a fourth tap isn't offered.
                synchronized(this) {
                    val (next, count) = recordAfterUpload(record(context), record.id, count(context), TimeRequestVerdict.ACCEPTED) ?: return
                    write(context, next, count)
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

    /** The record changed: the notice again, and the boundary alarm for the note's next change (Q-4, QA R2-L3). */
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
