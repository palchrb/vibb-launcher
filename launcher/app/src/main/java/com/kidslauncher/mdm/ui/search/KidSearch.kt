package com.kidslauncher.mdm.ui.search

/*
 * The search's rules on a screen (design 79 §4.1, `KidSearchTest`) - pure: when the pill shows, what Back does, when the
 * live count may speak.
 */

/** The pill shows from [minRows] rows ([com.kidslauncher.mdm.calls.SEARCH_MIN_ROWS]) - and, once shown, stays while it
 * holds text, even if a sync takes the list under (QA N3). [allowed]: the screen may search at all (the phone book: calls
 * managed and on). */
fun searchShown(rows: Int, query: String, allowed: Boolean, minRows: Int): Boolean =
    allowed && (query.isNotEmpty() || rows >= minRows)

/** What Back does on the phone book (QA N4, code QA L10): the keyboard first (stock Android), then the request form,
 * then the search's text, then it leaves. */
enum class PhoneBookBack { HIDE_KEYBOARD, CLOSE_FORM, CLEAR_SEARCH, LEAVE }

fun phoneBookBack(keyboardUp: Boolean, formOpen: Boolean, query: String): PhoneBookBack = when {
    keyboardUp -> PhoneBookBack.HIDE_KEYBOARD
    formOpen -> PhoneBookBack.CLOSE_FORM
    query.isNotEmpty() -> PhoneBookBack.CLEAR_SEARCH
    else -> PhoneBookBack.LEAVE
}

/** What Back does in Meldinger: the keyboard first, then the delete confirm, then the search's text, then "Ny melding"
 * back to the list, then it leaves. */
enum class MessagesBack { HIDE_KEYBOARD, CANCEL_DELETE, CLEAR_SEARCH, STOP_PICKING, LEAVE }

fun messagesBack(keyboardUp: Boolean, deleting: Boolean, query: String, picking: Boolean): MessagesBack = when {
    keyboardUp -> MessagesBack.HIDE_KEYBOARD
    deleting -> MessagesBack.CANCEL_DELETE
    query.isNotEmpty() -> MessagesBack.CLEAR_SEARCH
    picking -> MessagesBack.STOP_PICKING
    else -> MessagesBack.LEAVE
}

/** The live count speaks at most once per this long of typing (QA N7). */
const val SEARCH_ANNOUNCE_MS = 500L

/** When the count line may change next: at once if it last changed [SEARCH_ANNOUNCE_MS] ago or more, else then. */
fun searchAnnounceAt(lastMs: Long?, nowMs: Long): Long = if (lastMs == null) nowMs else maxOf(nowMs, lastMs + SEARCH_ANNOUNCE_MS)
