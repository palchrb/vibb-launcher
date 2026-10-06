package com.kidslauncher.mdm.calls

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/*
 * Element X: the Message button opens the DM, not the profile (design 15, the learned variant
 * after its QA review). Element X has no "open DM with this user" link - `matrix:u/…?action=chat`
 * opens the profile - but `elementx://open/<session>/<room>` (its notifications' own VIEW link)
 * opens a room. The launcher learns the room from Element X's DM notifications: a notification
 * whose every sender is one phone-book contact names that contact's DM room (its tag) and the
 * kid's own account (the messaging person). Pure, no Android imports - ElementRoomsTest. The glue
 * is badges/ElementDmReader (reads the notification) and ElementRoomStore (CE prefs).
 *
 * Privacy: the input is only the tag, the messaging person's key, the senders' keys and the
 * group flag - never a title, name or text (ElementDmFacts, exact-field test; the reader is
 * scan-tested). Stored is only contact MXID -> (session, room), for phone-book contacts, in CE
 * prefs; nothing is logged or reported to the server.
 */

/**
 * What the learning rule may know about one Element X notification - exactly these four fields
 * (ElementRoomsTest pins them; no text, title or names).
 */
data class ElementDmFacts(
    /** `StatusBarNotification.tag`: Element X's room ID for a room's messages
     * (`<room>|<thread>` for a thread). */
    val tag: String?,
    /** The key of `Notification.EXTRA_MESSAGING_PERSON`: the account (session) it was posted for. */
    val selfKey: String?,
    /** For each `EXTRA_MESSAGES` bundle that has a `sender_person`: that person's key (null
     * without one). The kid's own messages have no `sender_person` and aren't listed. */
    val senderKeys: List<String?>,
    /** `android.isGroupConversation`; null when the extra is missing. */
    val group: Boolean?,
)

/** A learned DM: the kid's own Element account ([session], an MXID) and the room ID. */
@Serializable
data class ElementRoom(val session: String, val room: String)

/** [learnElementRoom]'s result: the phone-book contact (by its Element MXID) and its DM. */
data class LearnedElementRoom(val contactMxid: String, val room: ElementRoom)

/** v12 room ID (MSC4291): `!` + the create event's reference hash, 43 characters of unpadded
 * URL-safe base64. */
private val ROOM_ID_V12 = Regex("""![A-Za-z0-9_-]{43}""")

/** Older room ID `!opaque:server` - the server part as in [isMatrixId]. Never a `|` (a thread
 * tag), `?`, `#`, `&` or a space. */
private val ROOM_ID_SERVER = Regex("""![A-Za-z0-9._=~+/-]+:(?:[A-Za-z0-9.-]+|\[[0-9A-Fa-f:.]+\])(?::[0-9]{1,5})?""")

fun isElementRoomId(value: String?): Boolean =
    value != null && value.length <= 255 && (ROOM_ID_V12.matches(value) || ROOM_ID_SERVER.matches(value))

/** The MXIDs of the phone-book contacts whose Message button opens Element X. */
fun elementContactIds(rules: CallRules): Set<String> =
    rules.phoneBook
        .filter { it.messageApp == "element" }
        .mapNotNull { it.messageAddress?.takeIf(::isMatrixId) }
        .toSet()

/**
 * The learning rule (design 15, QA #2). A DM room is learned only when all of this holds:
 * - the poster is Element X ([packageName] is `sbn.packageName`, tied by NMS to the poster's
 *   uid; no signer pin - the button opens this package, so a fake one owns it anyway);
 * - calls are managed (there is a phone book) and it isn't a group conversation (threads and
 *   group rooms are);
 * - the tag is a room ID ([isElementRoomId]; a thread tag `<room>|<thread>` is not);
 * - the messaging person's key is an MXID (the session);
 * - every `sender_person` has a key, all keys are the same MXID, not the session, and that is a
 *   phone-book contact's Element MXID. Anything else (a `mention-or-reply:` key, another sender,
 *   only the kid's own messages) learns nothing. Never the shortcut id.
 */
fun learnElementRoom(packageName: String, facts: ElementDmFacts, state: CallPolicyState): LearnedElementRoom? {
    if (packageName != MessagePackages.ELEMENT_X) return null
    val rules = (state as? CallPolicyState.Managed)?.rules ?: return null
    if (facts.group != false) return null
    val room = facts.tag?.takeIf(::isElementRoomId) ?: return null
    val session = facts.selfKey?.takeIf(::isMatrixId) ?: return null
    if (facts.senderKeys.isEmpty() || facts.senderKeys.any { it == null }) return null
    val sender = facts.senderKeys.distinct().singleOrNull() ?: return null
    if (sender == session || sender !in elementContactIds(rules)) return null
    return LearnedElementRoom(sender, ElementRoom(session, room))
}

/**
 * What stays stored after the rules changed (QA #4): with managed rules only the entries of
 * phone-book contacts that still use Element under the same MXID (an entry is keyed by that
 * MXID); unmanaged calls have no phone book, so nothing; unknown rules (fail-closed) leave
 * everything as it is.
 */
fun pruneElementRooms(stored: Map<String, ElementRoom>, state: CallPolicyState): Map<String, ElementRoom> = when (state) {
    CallPolicyState.UnknownFailClosed -> stored
    CallPolicyState.Unmanaged -> emptyMap()
    is CallPolicyState.Managed -> elementContactIds(state.rules).let { ids -> stored.filterKeys { it in ids } }
}

/**
 * The stored map after [learned]: pruned against [state] (the rules read under the store's lock),
 * then the newer pair replaces the old one - only while the contact is still a phone-book Element
 * contact in [state] (qa-15-code #2: a notification judged against the rules a refresh just
 * replaced must not bring back a pair its prune dropped).
 */
fun withLearnedRoom(stored: Map<String, ElementRoom>, learned: LearnedElementRoom, state: CallPolicyState): Map<String, ElementRoom> {
    val pruned = pruneElementRooms(stored, state)
    val rules = (state as? CallPolicyState.Managed)?.rules ?: return pruned
    return if (learned.contactMxid in elementContactIds(rules)) pruned + (learned.contactMxid to learned.room) else pruned
}

/**
 * `elementx://open/<session>/<room>` (Element X's own notification link), each segment fully
 * percent-encoded (QA #5): Element splits the encoded path on `/` before URL-decoding, so a raw
 * `/` would split a segment and a raw `+` would become a space. `null` for an invalid pair.
 */
fun elementRoomUri(room: ElementRoom): String? {
    if (!isMatrixId(room.session) || !isElementRoomId(room.room)) return null
    return "elementx://open/${uriEncode(room.session)}/${uriEncode(room.room)}"
}

// ---- storage codec ----------------------------------------------------------------------------

private val roomsSerializer = MapSerializer(String.serializer(), ElementRoom.serializer())
private val roomsJson = Json { ignoreUnknownKeys = true }

fun encodeElementRooms(rooms: Map<String, ElementRoom>): String = roomsJson.encodeToString(roomsSerializer, rooms)

/** Missing or unreadable = empty; an entry that isn't contact MXID -> (MXID, room ID) is dropped. */
fun decodeElementRooms(json: String?): Map<String, ElementRoom> {
    if (json.isNullOrBlank()) return emptyMap()
    val decoded = try {
        roomsJson.decodeFromString(roomsSerializer, json)
    } catch (e: Exception) {
        return emptyMap()
    }
    return decoded.filter { (contact, room) -> isMatrixId(contact) && isMatrixId(room.session) && isElementRoomId(room.room) }
}
