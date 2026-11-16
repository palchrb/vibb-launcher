package com.kidslauncher.mdm.calls

/*
 * What the phone book's look-less entry (`PhoneBookEntryActivity`) does with an intent from outside the process - pure,
 * `PhoneBookAskTest`. The entry is exported (the dialer role's DIAL, links, the call button, the call log), so this is
 * its contract: the call log is passed on to the system dialer while calls are unmanaged; anything else opens the
 * phone book with a [PhoneBookAsk] - a number from a `tel:`/`voicemail:` link, whether the call log was asked, and our
 * own missed-call notification's contact - and nothing else of the outside intent (no extras, flags, ClipData or
 * grants; another scheme's data is dropped). The page carries the ask as extras on a component-only intent, so the
 * task's base intent always equals Home's tile's (code QA M1: a different one made Android stack a second phone book).
 */

/** What an outside intent asks of the phone book. [number] is the link as sent (`tel:...`/`voicemail:...`), checked by
 * the page against the call rules; [contact] the number whose card the missed-call notification asks for. */
data class PhoneBookAsk(
    val callLog: Boolean = false,
    val number: String? = null,
    val contact: String? = null,
)

sealed interface EntryRoute {
    /** The call log while calls are unmanaged: the system dialer's, as before the own task. */
    data object PassOnCallLog : EntryRoute

    data class Open(val ask: PhoneBookAsk) : EntryRoute
}

/** The schemes a number may come in; `voicemail:` as the filter has it. */
private val NUMBER_SCHEMES = setOf("tel", "voicemail")

/** A number or contact longer than this is no phone number: dropped. */
const val PHONE_BOOK_ASK_MAX_CHARS = 128

/**
 * The entry's rule. [action], [type] and [data] are the outside intent's (data as a string); [contact] its
 * `EXTRA_MISSED_CONTACT`, honoured only with the call log - the only way our notification asks for it.
 */
fun entryRoute(action: String?, type: String?, data: String?, contact: String?, callsUnmanaged: Boolean): EntryRoute {
    val callLog = isCallLogView(action, type, data)
    if (callLog && callsUnmanaged) return EntryRoute.PassOnCallLog
    fun short(s: String?) = s?.takeIf { it.isNotBlank() && it.length <= PHONE_BOOK_ASK_MAX_CHARS }
    val number = short(data)?.takeIf { !callLog && it.substringBefore(':', "").lowercase() in NUMBER_SCHEMES }
    return EntryRoute.Open(PhoneBookAsk(callLog = callLog, number = number, contact = short(contact)?.takeIf { callLog }))
}
