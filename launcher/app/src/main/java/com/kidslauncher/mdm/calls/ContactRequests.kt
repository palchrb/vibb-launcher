package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.kidslauncher.mdm.day.TitleRules
import com.kidslauncher.mdm.server.MdmApi
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.ContactRequestAnswer
import com.kidslauncher.mdm.server.dto.ContactRequestBody
import com.kidslauncher.mdm.server.dto.PolicyContactRequest
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArraySet

/*
 * The kid's contact request (design 77 §14, `ContactRequestsTest`): from the phone book's "+", the kid asks the
 * parents to add someone - a name, a number and maybe a Matrix ID. The phone only asks: nothing here calls, texts or
 * opens anything, and nothing is added on the phone; a parent adds the contact in the PWA, and it arrives with the
 * policy like any other. The pure rules - the form's check, the server's answer, what the policy says, what the phone
 * book shows - and the glue that keeps the requests in prefs and uploads them with the sync.
 */

/** A name's length after cleaning (the server's `contact_requests::NAME_MAX`). */
const val CONTACT_REQUEST_NAME_MAX = 40

/** Requests the phone keeps open at once (the server's `OPEN_MAX`). */
const val CONTACT_REQUESTS_OPEN_MAX = 5

/** A request is kept this long on the phone, as the policy carries it (§14). */
const val CONTACT_REQUEST_KEEP_MS = 7 * 24 * 60 * 60_000L

/** Where a request stands on the phone. */
@Serializable
enum class ContactRequestPhase {
    /** Kept, not yet taken by the server: sent with the next sync. */
    PENDING,

    /** The server took it: "Venter på svar" until the contact arrives or a parent declines. */
    ASKED,

    /** The rest are answers the phone book shows once: a parent declined ("Ikke lagt til"), ... */
    DECLINED,

    /** ... the day's three or five open were asked already, ... */
    CAPPED,

    /** ... the number is on the phone or asked for already, ... */
    KNOWN,

    /** ... or the server couldn't take it (refused, or a server without the route). */
    NOT_SENT,
}

/** One request. [shown]: an answer the phone book has shown - it goes when the phone book is left. */
@Serializable
data class ContactRequestRecord(
    val id: String,
    val name: String,
    val number: String,
    val matrixId: String? = null,
    val askedAtMs: Long,
    val phase: ContactRequestPhase = ContactRequestPhase.PENDING,
    val shown: Boolean = false,
) {
    /** Still waiting: the kid sees "Venter på svar". */
    val open: Boolean get() = phase == ContactRequestPhase.PENDING || phase == ContactRequestPhase.ASKED

    fun body() = ContactRequestBody(id, name, number, matrixId, askedAtMs)
}

/** The form's field a refusal belongs to. */
enum class RequestField { NAME, NUMBER, MATRIX }

/** Why the form can't send. */
enum class RequestRefusal {
    NAME_EMPTY,
    NAME_TOO_LONG,
    NUMBER_INVALID,
    NUMBER_EMERGENCY,
    NUMBER_KNOWN,
    MATRIX_INVALID,
    TOO_MANY,
}

/** The form's check: the request to keep, or the field and why not. */
sealed interface RequestCheck {
    data class Ok(val name: String, val number: String, val matrixId: String?) : RequestCheck

    data class Refused(val field: RequestField, val why: RequestRefusal) : RequestCheck
}

/**
 * §14's checks on the phone, the server's (`contact_requests::check`, the shared `valid`/`invalid` vectors) and two of
 * its own: the name cleaned (the server's `calendar::clean`) to 1-[CONTACT_REQUEST_NAME_MAX] characters; the number
 * normalised with [defaultCc] and not an emergency number ([Emergency.staticSet], or [isEmergency] - the platform's
 * answer for what the kid typed); an empty Matrix ID none, any other one a Matrix ID ([isMatrixId]). The phone's own:
 * a number already in [knownNumbers] (the policy's contacts) or in an open request is [RequestRefusal.NUMBER_KNOWN],
 * and [CONTACT_REQUESTS_OPEN_MAX] open ones refuse another.
 */
fun checkContactRequest(
    name: String,
    number: String,
    matrixId: String,
    defaultCc: String,
    knownNumbers: Set<String>,
    records: List<ContactRequestRecord>,
    isEmergency: (String) -> Boolean = { false },
): RequestCheck {
    if (records.count { it.open } >= CONTACT_REQUESTS_OPEN_MAX) return RequestCheck.Refused(RequestField.NAME, RequestRefusal.TOO_MANY)
    val cleaned = TitleRules.clean(name, CONTACT_REQUEST_NAME_MAX + 1)
    if (cleaned.isEmpty()) return RequestCheck.Refused(RequestField.NAME, RequestRefusal.NAME_EMPTY)
    if (cleaned.codePointCount(0, cleaned.length) > CONTACT_REQUEST_NAME_MAX) {
        return RequestCheck.Refused(RequestField.NAME, RequestRefusal.NAME_TOO_LONG)
    }
    val normalized = PhoneNumbers.normalize(number, defaultCc)?.value
        ?: return RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_INVALID)
    if (normalized in Emergency.staticSet(defaultCc) || isEmergency(number)) {
        return RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_EMERGENCY)
    }
    if (normalized in knownNumbers || records.any { it.open && it.number == normalized }) {
        return RequestCheck.Refused(RequestField.NUMBER, RequestRefusal.NUMBER_KNOWN)
    }
    val matrix = matrixId.trim().ifEmpty { null }
    if (matrix != null && !isMatrixId(matrix)) return RequestCheck.Refused(RequestField.MATRIX, RequestRefusal.MATRIX_INVALID)
    return RequestCheck.Ok(cleaned, normalized, matrix)
}

/** The server's answer to the upload, as the phone acts on it. */
enum class ContactRequestVerdict {
    /** 200 `{"accepted":true}`: ASKED. */
    ACCEPTED,

    /** 200 `capped`: shown once, gone. */
    CAPPED,

    /** 200 `known`: shown once, gone. */
    KNOWN,

    /** 200 `invalid`, another 4xx (but 401, 408, 429), a 200 that doesn't parse, an old server's 404: "Ikke sendt"
     * once, gone. */
    NOT_SENT,

    /** 401, 408, 429, 5xx, no answer: kept, sent again with the next sync while it is kept. */
    RETRY,
}

/** The verdict for HTTP [code] (`null` = no answer) and its [body]. */
fun contactRequestAnswer(code: Int?, body: String?): ContactRequestVerdict = when {
    code == null || code == 401 || code == 408 || code == 429 || code >= 500 -> ContactRequestVerdict.RETRY
    code == 200 -> {
        val answer = try {
            body?.let { ServerJson.decodeFromString(ContactRequestAnswer.serializer(), it) }
        } catch (e: Exception) {
            null
        }
        when {
            answer == null -> ContactRequestVerdict.NOT_SENT
            answer.accepted -> ContactRequestVerdict.ACCEPTED
            answer.reason == "capped" -> ContactRequestVerdict.CAPPED
            answer.reason == "known" -> ContactRequestVerdict.KNOWN
            else -> ContactRequestVerdict.NOT_SENT
        }
    }
    else -> ContactRequestVerdict.NOT_SENT
}

/** [record] after [verdict]. */
fun afterContactRequestAnswer(record: ContactRequestRecord, verdict: ContactRequestVerdict): ContactRequestRecord = when (verdict) {
    ContactRequestVerdict.ACCEPTED -> record.copy(phase = ContactRequestPhase.ASKED)
    ContactRequestVerdict.CAPPED -> record.copy(phase = ContactRequestPhase.CAPPED, shown = false)
    ContactRequestVerdict.KNOWN -> record.copy(phase = ContactRequestPhase.KNOWN, shown = false)
    ContactRequestVerdict.NOT_SENT -> record.copy(phase = ContactRequestPhase.NOT_SENT, shown = false)
    ContactRequestVerdict.RETRY -> record
}

/**
 * The requests after an accepted policy at [nowMs]: one older than [CONTACT_REQUEST_KEEP_MS] goes; an open one whose
 * number is a contact now ([contactNumbers], the policy's contacts - `null` when calls aren't managed, which says
 * nothing) goes - the contact arrived; an open one the policy calls `added` goes (added under another number), one it
 * calls `declined` becomes [ContactRequestPhase.DECLINED], shown once. The rest stay.
 */
fun reconcileContactRequests(
    records: List<ContactRequestRecord>,
    policyRows: List<PolicyContactRequest>,
    contactNumbers: Set<String>?,
    nowMs: Long,
): List<ContactRequestRecord> {
    val states = policyRows.associate { it.id to it.state }
    return records.mapNotNull { record ->
        when {
            nowMs - record.askedAtMs > CONTACT_REQUEST_KEEP_MS -> null
            !record.open -> record
            contactNumbers != null && record.number in contactNumbers -> null
            states[record.id] == "added" -> null
            states[record.id] == "declined" -> record.copy(phase = ContactRequestPhase.DECLINED, shown = false)
            else -> record
        }
    }
}

/** What a request's row in the phone book says. */
enum class RequestLine { WAITING, NOT_ADDED, CAPPED, KNOWN, NOT_SENT }

/** One row under the phone book's contacts. */
data class RequestRow(val id: String, val name: String, val number: String, val line: RequestLine)

/** The phone book's rows: every kept request, the newest first. */
fun contactRequestRows(records: List<ContactRequestRecord>): List<RequestRow> =
    records.sortedByDescending { it.askedAtMs }.map { record ->
        val line = when (record.phase) {
            ContactRequestPhase.PENDING, ContactRequestPhase.ASKED -> RequestLine.WAITING
            ContactRequestPhase.DECLINED -> RequestLine.NOT_ADDED
            ContactRequestPhase.CAPPED -> RequestLine.CAPPED
            ContactRequestPhase.KNOWN -> RequestLine.KNOWN
            ContactRequestPhase.NOT_SENT -> RequestLine.NOT_SENT
        }
        RequestRow(record.id, record.name, record.number, line)
    }

/** The phone book showed the rows: every answer is [ContactRequestRecord.shown]. */
fun markContactRequestsShown(records: List<ContactRequestRecord>): List<ContactRequestRecord> =
    records.map { if (it.open) it else it.copy(shown = true) }

/** The phone book was left: the answers it showed go (each shows once). */
fun dropShownContactRequests(records: List<ContactRequestRecord>): List<ContactRequestRecord> =
    records.filterNot { !it.open && it.shown }

/** A new request's id: 32 lowercase hex characters. */
fun newContactRequestId(random: SecureRandom = SecureRandom()): String {
    val bytes = ByteArray(16).also(random::nextBytes)
    return bytes.joinToString("") { "%02x".format(it) }
}

private const val LOG_TAG = "ContactRequests"
private const val PREFS = "contact_requests"
private const val RECORDS_KEY = "records_v1"
private val RECORDS = ListSerializer(ContactRequestRecord.serializer())

/** The Android side: the requests in prefs (credential-encrypted: names and numbers), the upload with the sync, the
 * policy's answers, and the phone book's listeners. */
object ContactRequests {
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun changed() {
        Handler(Looper.getMainLooper()).post { listeners.forEach { it() } }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The kept requests; none when they can't be read (before the first unlock, or unreadable - logged). */
    @Synchronized
    fun records(context: Context): List<ContactRequestRecord> = try {
        prefs(context).getString(RECORDS_KEY, null)?.let { ServerJson.decodeFromString(RECORDS, it) }.orEmpty()
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Unreadable contact requests: ${e.javaClass.simpleName}")
        emptyList()
    }

    @Synchronized
    private fun update(context: Context, change: (List<ContactRequestRecord>) -> List<ContactRequestRecord>) {
        val before = records(context)
        val after = change(before)
        if (after == before) return
        try {
            if (!prefs(context).edit().putString(RECORDS_KEY, ServerJson.encodeToString(RECORDS, after)).commit()) {
                Log.w(LOG_TAG, "Couldn't save the contact requests")
            }
        } catch (e: Exception) {
            Log.w(LOG_TAG, "Couldn't save the contact requests: ${e.javaClass.simpleName}")
        }
        changed()
    }

    /** The form's "Send": a new PENDING request; the caller asks for a sync, which sends it. */
    fun add(context: Context, ok: RequestCheck.Ok, nowMs: Long = System.currentTimeMillis()) {
        val record = ContactRequestRecord(newContactRequestId(), ok.name, ok.number, ok.matrixId, nowMs)
        update(context) { it + record }
    }

    fun markShown(context: Context) = update(context, ::markContactRequestsShown)

    fun dropShown(context: Context) = update(context, ::dropShownContactRequests)

    /** At every accepted policy (`performMdmSync`): [reconcileContactRequests] with its rows and contacts. */
    fun policyAccepted(context: Context, callPolicy: CallPolicy?) {
        val numbers = callPolicy?.takeIf { it.managed }?.contacts?.map { it.number }?.toSet()
        val rows = callPolicy?.contactRequests.orEmpty()
        update(context) { reconcileContactRequests(it, rows, numbers, System.currentTimeMillis()) }
    }

    /**
     * With the sync, after the status report (behind the sync mutex): each PENDING request still kept is posted, and
     * [contactRequestAnswer] decides; only that request's record changes (a newer one written meanwhile stays).
     */
    suspend fun upload(context: Context, api: MdmApi) {
        val now = System.currentTimeMillis()
        val pending = records(context).filter {
            it.phase == ContactRequestPhase.PENDING && now - it.askedAtMs <= CONTACT_REQUEST_KEEP_MS
        }
        for (record in pending) {
            var code: Int? = null
            var body: String? = null
            try {
                val response = api.sendContactRequest(record.body())
                code = response.code()
                body = response.body()?.use { it.string() }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(LOG_TAG, "A contact request didn't get through: ${e.javaClass.simpleName}")
            }
            val verdict = contactRequestAnswer(code, body)
            Log.i(LOG_TAG, "Contact request: $verdict${code?.let { " (HTTP $it)" } ?: ""}")
            if (verdict == ContactRequestVerdict.RETRY) return
            update(context) { records ->
                records.map { if (it.id == record.id && it.phase == ContactRequestPhase.PENDING) afterContactRequestAnswer(it, verdict) else it }
            }
        }
    }
}
