package com.kidslauncher.mdm.calls

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.security.MessageDigest

/*
 * Design 106 §3.3 (Q3): a small record of the missed Element X calls, so Siste keeps a row after the kid opened the room
 * in Element X ([ElementMissedCallStore] forgets the call then). Pure, no Android imports - `ElementCallLogTest`. The
 * glue is [ElementCallLogStore] (CE prefs `element_call_log`), fed by the memory store's own changes.
 *
 * What is kept: the contact's number (resolved through the rules when the record is written), the call's time (the
 * live entry's `sinceMs`), a hash of the notification's key (so no room ID is stored) and whether the call is still open
 * in the live store. Never a room ID, an MXID, a name or a text; never logged, never sent.
 */

/** One missed Element X call as Siste shows it. [hash]: [elementKeyHash] of its notification's key; [open]: the call is
 * still in the live store (only a guard against a second record of the same call after a process restart). */
data class ElementCallRecord(val number: String, val atMs: Long, val hash: String, val open: Boolean) {
    /** Never the number or the hash (a stray log or crash report). */
    override fun toString() = "ElementCallRecord(atMs=$atMs, open=$open)"
}

/** Which change of the live store a record update follows: `set()` alone can't tell an unbind from removals. */
enum class ElementLogChange { POSTED, REMOVED, SWEPT, CLEARED }

/** At most this many records are kept (the newest). */
const val ELEMENT_CALL_LOG_MAX = 50

/** The record's and the live entry's common key: SHA-256 of the notification key, its first 128 bits in hex. */
fun elementKeyHash(key: String): String =
    MessageDigest.getInstance("SHA-256").digest(key.toByteArray(Charsets.UTF_8)).take(16).joinToString("") { "%02x".format(it) }

/**
 * The records after one change of the live store, from [before] to [after] (notification key -> missed call):
 * - a key new to the store (or whose contact changed) adds an open record for the contact's number ([numberOf] its
 *   MXID; none for a contact no longer in the rules) - unless an open record with the same hash and number is there
 *   already: that is the connect sweep after a process restart, the same call, and its time is raised to the live
 *   `sinceMs` if that is later, so the row's time is the badge's;
 * - a key that leaves the store ([ElementLogChange.REMOVED], or a post that drops it) closes its record; so does a
 *   key whose contact changed;
 * - [ElementLogChange.SWEPT] closes every open record whose key isn't active;
 * - [ElementLogChange.CLEARED] (the listener was unbound) closes nothing - the next sweep decides.
 * Then pruned ([pruneElementCallLog]'s age and count).
 */
fun elementCallLogAfter(
    records: List<ElementCallRecord>,
    before: Map<String, ElementMissedCall>,
    after: Map<String, ElementMissedCall>,
    change: ElementLogChange,
    numberOf: (String) -> String?,
    nowMs: Long,
): List<ElementCallRecord> {
    if (change == ElementLogChange.CLEARED) return trimElementCallLog(records, nowMs)
    val out = records.toMutableList()
    fun close(hash: String) {
        for (i in out.indices) if (out[i].open && out[i].hash == hash) out[i] = out[i].copy(open = false)
    }
    if (change == ElementLogChange.SWEPT) {
        val active = after.keys.mapTo(mutableSetOf(), ::elementKeyHash)
        for (i in out.indices) if (out[i].open && out[i].hash !in active) out[i] = out[i].copy(open = false)
    } else {
        for (key in before.keys - after.keys) close(elementKeyHash(key))
    }
    for ((key, call) in after) {
        val previous = before[key]
        if (previous != null && previous.mxid == call.mxid) continue
        val hash = elementKeyHash(key)
        val number = numberOf(call.mxid)
        val same = out.indexOfFirst { it.open && it.hash == hash && it.number == number }
        if (same >= 0) {
            out[same] = out[same].copy(atMs = maxOf(out[same].atMs, call.sinceMs))
            continue
        }
        // Another contact on the same key (or none now): that call is over.
        close(hash)
        if (number != null) out += ElementCallRecord(number, call.sinceMs, hash, open = true)
    }
    return trimElementCallLog(out, nowMs)
}

/** None older than 14 days ([MISSED_CALL_LOG_WINDOW_MS]), at most [ELEMENT_CALL_LOG_MAX], newest first. */
fun trimElementCallLog(records: List<ElementCallRecord>, nowMs: Long): List<ElementCallRecord> =
    records.filter { it.atMs >= nowMs - MISSED_CALL_LOG_WINDOW_MS }
        .sortedByDescending { it.atMs }
        .take(ELEMENT_CALL_LOG_MAX)

/** The numbers of the phone-book contacts whose Message button opens Element X (an MXID) - who a record may name. */
fun elementContactNumbers(rules: CallRules): Set<String> =
    rules.phoneBook.filter { it.messageApp == "element" && isMatrixId(it.messageAddress) }.mapTo(mutableSetOf()) { it.number }

/** The number of the phone-book Element contact with [mxid], or `null`. */
fun elementContactNumber(rules: CallRules, mxid: String): String? =
    rules.phoneBook.firstOrNull { it.messageApp == "element" && it.messageAddress == mxid }?.number

/**
 * At each [CallPolicyStore.refresh] (as [pruneElementRooms]): unmanaged drops all; managed drops a record whose contact
 * is no longer a phone-book Element contact; unknown rules keep them. Then trimmed.
 */
fun pruneElementCallLog(records: List<ElementCallRecord>, state: CallPolicyState, nowMs: Long): List<ElementCallRecord> = when (state) {
    CallPolicyState.Unmanaged -> emptyList()
    CallPolicyState.UnknownFailClosed -> trimElementCallLog(records, nowMs)
    is CallPolicyState.Managed -> elementContactNumbers(state.rules).let { numbers ->
        trimElementCallLog(records.filter { it.number in numbers }, nowMs)
    }
}

/** What a read shows (§3.3): only while managed, only a phone-book Element contact's, only of the last 14 days - a
 * record a refresh hasn't pruned yet never shows. */
fun shownElementCallLog(records: List<ElementCallRecord>, state: CallPolicyState, nowMs: Long): List<ElementCallRecord> =
    if (state is CallPolicyState.Managed) pruneElementCallLog(records, state, nowMs) else emptyList()

// ---- storage codec (CE prefs, versioned v1) ----------------------------------------------------------------------

@Serializable
private data class StoredElementCall(
    @SerialName("n") val number: String,
    @SerialName("t") val atMs: Long,
    @SerialName("h") val hash: String,
    @SerialName("o") val open: Boolean,
)

private val logSerializer = ListSerializer(StoredElementCall.serializer())
private val logJson = Json { ignoreUnknownKeys = true }

fun encodeElementCallLog(records: List<ElementCallRecord>): String =
    logJson.encodeToString(logSerializer, records.map { StoredElementCall(it.number, it.atMs, it.hash, it.open) })

/** Missing or unreadable = empty; a record without a number or a 128-bit hash is dropped. */
fun decodeElementCallLog(json: String?): List<ElementCallRecord> {
    if (json.isNullOrBlank()) return emptyList()
    val decoded = try {
        logJson.decodeFromString(logSerializer, json)
    } catch (e: Exception) {
        return emptyList()
    }
    return decoded.filter { it.number.isNotBlank() && it.hash.matches(HASH) }.map { ElementCallRecord(it.number, it.atMs, it.hash, it.open) }
}

private val HASH = Regex("[0-9a-f]{32}")
