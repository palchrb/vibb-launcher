package com.kidslauncher.mdm.calls

/*
 * Missed-call badges (design 05-ui-photos-i18n.md) - pure, tested in MissedCallsTest. The call log
 * is read by MissedCallsRepo; "seen" marks (the kid opened the contact, or tapped Call) live in CE
 * preferences, never in device-protected storage.
 */

enum class LoggedCallType { INCOMING_ANSWERED, OUTGOING, MISSED, OTHER }

/** One call-log row: the number as logged (raw), its type and when it was; [id] is its `_id`
 * and [unread] its `new` flag (Telecom's "not dealt with" mark), both only for the missed-call
 * notification ([missedCallNotice]). */
data class CallLogEntry(
    val number: String?,
    val type: LoggedCallType,
    val atMs: Long,
    val id: Long = 0,
    val unread: Boolean = false,
)

/** [count] missed calls from one contact since it was last dealt with, the latest at [lastAtMs]. */
data class MissedSummary(val count: Int, val lastAtMs: Long)

/**
 * Missed calls per contact number (the contact's normalised number as the key). A missed call
 * counts only if it came after the latest of: the contact's seen mark ([seenMs]), and any outgoing
 * or answered call with that number (calling back, or talking later, deals with it). Rejected and
 * blocked calls are [LoggedCallType.OTHER] and never count - the kid shouldn't see strangers
 * screened out. Numbers that aren't a contact are ignored; contacts without missed calls are left
 * out of the map.
 */
fun missedCallSummaries(
    log: List<CallLogEntry>,
    contacts: List<RuleContact>,
    defaultCc: String,
    seenMs: Map<String, Long>,
): Map<String, MissedSummary> =
    unseenMissedCalls(log, contacts, defaultCc, seenMs).mapValues { (_, missed) ->
        MissedSummary(missed.size, missed.maxOf { it.atMs })
    }

/** The rows behind [missedCallSummaries]: per contact number, its missed calls that still count
 * (never an empty list). */
internal fun unseenMissedCalls(
    log: List<CallLogEntry>,
    contacts: List<RuleContact>,
    defaultCc: String,
    seenMs: Map<String, Long>,
): Map<String, List<CallLogEntry>> {
    val known = contacts.mapTo(mutableSetOf()) { it.number }
    val byNumber = log.mapNotNull { entry ->
        val number = entry.number?.let { PhoneNumbers.normalize(it, defaultCc)?.value }
        if (number != null && number in known) number to entry else null
    }.groupBy({ it.first }, { it.second })

    val result = mutableMapOf<String, List<CallLogEntry>>()
    for ((number, entries) in byNumber) {
        val dealtWith = entries
            .filter { it.type == LoggedCallType.OUTGOING || it.type == LoggedCallType.INCOMING_ANSWERED }
            .maxOfOrNull { it.atMs } ?: Long.MIN_VALUE
        val since = maxOf(dealtWith, seenMs[number] ?: Long.MIN_VALUE)
        val missed = entries.filter { it.type == LoggedCallType.MISSED && it.atMs > since }
        if (missed.isNotEmpty()) result[number] = missed
    }
    return result
}

/** The day a missed-call time falls on, relative to now, for "today 13:05" / "yesterday 13:05". */
enum class RelativeDay { TODAY, YESTERDAY, EARLIER }

/** [atDay]/[todayDay] are local epoch days (`LocalDate.toEpochDay()`). */
fun relativeDay(atDay: Long, todayDay: Long): RelativeDay = when (todayDay - atDay) {
    0L -> RelativeDay.TODAY
    1L -> RelativeDay.YESTERDAY
    else -> RelativeDay.EARLIER
}
