package com.kidslauncher.mdm.calls

import com.kidslauncher.mdm.badges.glanceMissedCall

/*
 * Round L5: missed Element X calls on the contact card and as the phone book's badge. Pure, no Android imports -
 * ElementMissedCallsTest. The glue is badges/ElementDmReader (reads the notification, the same facts design 15
 * reads plus the category), ElementMissedCallStore (memory only) and MissedCallsRepo.contactMissed (with the phone's
 * own missed calls and the seen marks).
 *
 * What Element X does (its Android source, develop bb79064b, read 2026-11-02; since v0.4.15): an unanswered call
 * cancels the ring notification (a fixed id, CATEGORY_CALL, no room on it) and posts or updates the room's
 * MessagingStyle notification with `category = "missed_call"` - the tag is the room ID (`<room>|<thread>` in a
 * thread), each message bundle's `sender_person` key the sender's MXID, the messaging person's key the kid's own MXID,
 * `isGroupConversation` false for a DM. The text still says "Incoming call", so it is never read. The category stays
 * `missed_call` while the call's event is among the room's pending events and goes back to `message` when it drops
 * out; the notification goes away when the kid opens the room in Element X. With an app PIN in Element X every
 * notification is one generic fallback (no tag, no category): nothing to read then.
 *
 * Privacy: as design 15 - only the tag, the keys, the group flag and the category; the contact's MXID and when the
 * notification turned into a missed call are kept in memory, keyed by the notification's key; nothing is stored,
 * logged or sent.
 */

/**
 * The phone-book contact (by its Element MXID) a missed Element X call stands for, else null: the notification's
 * [category] is `missed_call`, the rules are managed, it is a DM (`group == false`; group rooms and a missing flag
 * mark nobody), and the contact is the one whose learned DM room ([rooms], design 15) is the tag's room - for the
 * same session when the facts name one - or else the newest `sender_person` key that isn't the kid's own. Either way
 * it must be a phone-book contact whose Message button opens Element X ([elementContactIds]); a stranger marks
 * nobody.
 */
fun elementMissedCallContact(category: String?, facts: ElementDmFacts, state: CallPolicyState, rooms: Map<String, ElementRoom>): String? {
    if (!glanceMissedCall(category)) return null
    val rules = (state as? CallPolicyState.Managed)?.rules ?: return null
    if (facts.group != false) return null
    val contacts = elementContactIds(rules)
    // A thread's tag is `<room>|<thread>`: the room is what was learned.
    val room = facts.tag?.substringBefore('|')?.takeIf(::isElementRoomId)
    val byRoom = room?.let { tag ->
        rooms.entries.firstOrNull { (mxid, learned) ->
            learned.room == tag && (facts.selfKey == null || learned.session == facts.selfKey) && mxid in contacts
        }?.key
    }
    if (byRoom != null) return byRoom
    return facts.senderKeys.lastOrNull { it != null && it != facts.selfKey }?.takeIf { it in contacts }
}

/** One room's missed Element X call: the contact's MXID and when its notification turned into a missed call. */
data class ElementMissedCall(val mxid: String, val sinceMs: Long)

/** One Element X notification as the listener saw it: its key, the contact a missed call stands for
 * ([elementMissedCallContact], null when it is no missed call of a contact) and its post time. */
data class ElementPost(val key: String, val mxid: String?, val postTimeMs: Long)

/**
 * The missed calls after one post: no contact (another app, a group, a stranger, the category back at `message`)
 * drops the key; the same contact again keeps the time it turned into a missed call (an update with a newer message
 * is the same call); a new one starts at the post's time.
 */
fun afterElementPost(current: Map<String, ElementMissedCall>, post: ElementPost): Map<String, ElementMissedCall> {
    val mxid = post.mxid ?: return if (post.key in current) current - post.key else current
    val kept = current[post.key]
    if (kept != null && kept.mxid == mxid) return current
    return current + (post.key to ElementMissedCall(mxid, post.postTimeMs))
}

/** The missed calls after the notification of [key] went away (the kid opened the room, or swiped it). */
fun afterElementRemoval(current: Map<String, ElementMissedCall>, key: String): Map<String, ElementMissedCall> =
    if (key in current) current - key else current

/** The missed calls after a look at every active notification ([active], the listener's connect): only those still
 * there, each kept as [afterElementPost] would. */
fun afterElementSweep(current: Map<String, ElementMissedCall>, active: List<ElementPost>): Map<String, ElementMissedCall> =
    active.fold(emptyMap()) { next, post ->
        val kept = current[post.key]?.takeIf { it.mxid == post.mxid }
        if (kept != null) next + (post.key to kept) else afterElementPost(next, post)
    }

/**
 * Per contact number (the badges' key): the missed Element X calls that still count - each room's notification is
 * one call (its text, where several calls could be told apart, is never read), counted when it turned into a missed
 * call after the contact's seen mark ([seenMs]: the kid opened the contact's sheet). The contact is looked up in the
 * current [rules] (a phone-book contact whose Message button opens Element X under that MXID); contacts without one
 * are left out.
 */
fun elementMissedSummaries(calls: Collection<ElementMissedCall>, rules: CallRules, seenMs: Map<String, Long>): Map<String, MissedSummary> {
    val byMxid = rules.phoneBook
        .filter { it.messageApp == "element" && isMatrixId(it.messageAddress) }
        .associateBy { it.messageAddress.orEmpty() }
    return calls.mapNotNull { call ->
        val number = byMxid[call.mxid]?.number ?: return@mapNotNull null
        if (call.sinceMs > (seenMs[number] ?: Long.MIN_VALUE)) number to call.sinceMs else null
    }.groupBy({ it.first }, { it.second })
        .mapValues { (_, times) -> MissedSummary(times.size, times.max()) }
}

/** What a contact's badge and card show: its missed phone calls ([phone]) and its missed Element X calls
 * ([element]); the badge is both together. */
data class ContactMissed(val phone: MissedSummary? = null, val element: MissedSummary? = null) {
    val count: Int get() = (phone?.count ?: 0) + (element?.count ?: 0)
}

/** Both kinds per contact number; a number with neither is left out. */
fun contactMissed(phone: Map<String, MissedSummary>, element: Map<String, MissedSummary>): Map<String, ContactMissed> =
    (phone.keys + element.keys).associateWith { ContactMissed(phone[it], element[it]) }
