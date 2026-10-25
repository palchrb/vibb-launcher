package com.kidslauncher.mdm.badges

import com.kidslauncher.mdm.appdisplay.isValidPackageName
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.MessagePackages
import com.kidslauncher.mdm.calls.MissedCallNotice
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.calls.RuleContact
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/*
 * The lock's glance page (design 25, docs/design/25-lock-glance.md at the monorepo root): the pure
 * rules behind its rows - the parent's policy, which notifications are candidates, at which level
 * one is read, how texts are cleaned and how notifications and missed calls become rows. No Android
 * imports; JVM-tested (GlanceRowsTest, PolicyResponseCompatTest). GlanceReader reads, GlanceStore
 * keeps (memory only). Every type here that can carry a name or a text has a redacted toString(),
 * so a stray log line or crash report can't carry one (LockGlanceGuardTest).
 */

/**
 * The parent's choice (`PolicyResponse.lock_glance`): the packages whose messages show on the lock
 * ([apps]'s keys), each with whether its text shows too (design 51; per app since then). Missed
 * calls always show; they aren't in it.
 */
data class GlancePolicy(val apps: Map<String, Boolean>) {
    /** This ticked app's message text shows, not only who it's from. */
    fun showsText(packageName: String): Boolean = apps[packageName] == true

    companion object {
        /** No usable policy (a corrupt or missing cache, the last-enforced fallback, anything the
         * server sent that isn't an object): missed calls only - the parent may have unticked
         * something. */
        val UNKNOWN = GlancePolicy(emptyMap())

        /** What an older server without the key means (its own default for every phone). */
        val SERVER_DEFAULT = GlancePolicy(mapOf(MessagePackages.ELEMENT_X to false))
    }
}

/**
 * `PolicyResponse.lockGlance` read field by field: nothing in it can fail the policy (design 25
 * §2.1, like `app_display` and `music`).
 * - an object: `apps` = its usable entries ([glanceApp]; anything else in the list is dropped, so
 *   is a non-list); a top-level `show_text` (before design 51) is ignored - sender only;
 * - missing or JSON `null`: from a fresh response ([fresh]) an older server - its own default,
 *   Element X with the sender only; from the cache it is a cache an older build wrote (it never
 *   kept the key), so unknown (QA Low 11);
 * - anything else: unknown.
 */
fun lockGlancePolicy(element: JsonElement?, fresh: Boolean): GlancePolicy = when (element) {
    null, JsonNull -> if (fresh) GlancePolicy.SERVER_DEFAULT else GlancePolicy.UNKNOWN
    is JsonObject -> GlancePolicy((element["apps"] as? JsonArray).orEmpty().mapNotNull(::glanceApp).toMap())
    else -> GlancePolicy.UNKNOWN
}

/** One entry of `lock_glance.apps`: a string is that package, sender only (the shape before design
 * 51, from an older server); an object is its `package` with its own `show_text` (true only for
 * JSON `true`); anything else, or a package name that isn't one, is dropped. */
private fun glanceApp(entry: JsonElement): Pair<String, Boolean>? {
    val (packageName, showText) = when (entry) {
        is JsonObject -> (entry["package"] as? JsonPrimitive)?.takeIf { it.isString }?.content to
            ((entry["show_text"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true)
        is JsonPrimitive -> entry.takeIf { it.isString }?.content to false
        else -> null to false
    }
    return packageName?.takeIf(::isValidPackageName)?.let { it to showText }
}

/** The store's policy and whether a fresh one came in this process. */
data class GlancePolicyState(val policy: GlancePolicy, val freshSeen: Boolean) {
    companion object {
        val START = GlancePolicyState(GlancePolicy.UNKNOWN, freshSeen = false)
    }
}

/** The next policy (`GlanceStore.refreshPolicy`): a fresh one always wins; the cache read at process
 * start never overwrites a fresh one that arrived first. */
fun nextGlancePolicy(state: GlancePolicyState, incoming: GlancePolicy, fresh: Boolean): GlancePolicyState =
    if (!fresh && state.freshSeen) state else GlancePolicyState(incoming, state.freshSeen || fresh)

// ---- which notifications ----------------------------------------------------------------------

/** `NotificationListenerService.Ranking.getImportance()`'s `IMPORTANCE_MIN` (NotificationManager). */
const val GLANCE_IMPORTANCE_MIN = 1

/** `Notification.VISIBILITY_SECRET`. */
const val GLANCE_VISIBILITY_SECRET = -1

/** Categories that are never a row (§2.2): calls are design 17's (a missed VoIP call -
 * `missed_call` - is a row), the rest isn't a message. */
val GLANCE_SKIPPED_CATEGORIES = setOf(
    "call", "alarm", "stopwatch", "progress", "transport", "navigation", "service", "sys", "status",
    "location_sharing", "workout", "promo", "recommendation",
)

/** What [glanceCandidate] looks at - flags only, no text. [importance] `null` = no ranking found. */
data class GlanceCandidateFacts(
    val packageName: String,
    val groupSummary: Boolean,
    val ongoing: Boolean,
    val clearable: Boolean,
    val media: Boolean,
    val importance: Int?,
    val category: String?,
)

/**
 * Whether a ticked app's notification may become a row (§2.2): never ours (or our `.debug`/release
 * sibling), a group summary, an ongoing or non-clearable one, a player's media notification, one
 * the user or the app turned down to [GLANCE_IMPORTANCE_MIN] or below, or one of
 * [GLANCE_SKIPPED_CATEGORIES]. Suspension is not decided here: the store keeps the flag, so an
 * unsuspended app's rows come back without a new post.
 */
fun glanceCandidate(facts: GlanceCandidateFacts, ownPackage: String): Boolean =
    facts.packageName.removeSuffix(".debug") != ownPackage.removeSuffix(".debug") &&
        !facts.groupSummary && !facts.ongoing && facts.clearable && !facts.media &&
        (facts.importance == null || facts.importance > GLANCE_IMPORTANCE_MIN) &&
        facts.category !in GLANCE_SKIPPED_CATEGORIES

/** Whether a notification counts as suspended (§2.2, QA Medium 1): its ranking says so - or no
 * ranking was found for it ([ranked] false: fail closed). */
fun glanceSuspended(ranked: Boolean, isSuspended: Boolean): Boolean = !ranked || isSuspended

/** `Notification.CATEGORY_MISSED_CALL`: a missed call in an app (Element X sets it on the room's
 * notification when it holds an RTC notification event); its row is a missed-call row. */
fun glanceMissedCall(category: String?): Boolean = category == "missed_call"

/** What a message row's subtitle says (qa-25b-25f L4). */
enum class GlanceSubtitle { MISSED_CALL, MISSED_CALL_AND_COUNT, NOTICES, TEXT, COUNT }

/**
 * A message row's subtitle: a missed call in the app says so - with the count when the
 * conversation has more than the call - but only from SENDER up: at APP_ONLY (the app hides its
 * notifications' content, e.g. lock-screen visibility SECRET) the row stays "N varsler" and shows
 * no call badge either, so the lock never tells more than the app chose to.
 */
fun glanceSubtitle(level: GlanceLevel, missedCall: Boolean, count: Int, hasText: Boolean): GlanceSubtitle = when {
    level == GlanceLevel.APP_ONLY -> GlanceSubtitle.NOTICES
    missedCall && count > 1 -> GlanceSubtitle.MISSED_CALL_AND_COUNT
    missedCall -> GlanceSubtitle.MISSED_CALL
    level == GlanceLevel.TEXT && hasText -> GlanceSubtitle.TEXT
    else -> GlanceSubtitle.COUNT
}

/** How much of a notification is read (ordered: each level reads everything the one before does). */
enum class GlanceLevel {
    /** Not ticked: never read past its package name. */
    NOT_READ,

    /** The app and a count only ("Element X" + "2 nye varsler"): no title, no text. */
    APP_ONLY,

    /** The sender (a group: its name) and a count. */
    SENDER,

    /** The sender and the newest text. */
    TEXT,
}

/**
 * The level (§2.2 with QA Low 8 as decided): not ticked -> [GlanceLevel.NOT_READ]; the notification's
 * or its channel's lock-screen visibility is SECRET ([secret]) -> [GlanceLevel.APP_ONLY]; the
 * parent's text switch on -> [GlanceLevel.TEXT]; else [GlanceLevel.SENDER] for a MessagingStyle
 * notification ([messaging]) - any other's title can be content (a grade, an event, a promotion),
 * so sender-only shows it as the app and a count. `VISIBILITY_PRIVATE` is not honoured: the
 * parent's switch decides.
 */
fun glanceLevel(ticked: Boolean, secret: Boolean, showText: Boolean, messaging: Boolean): GlanceLevel = when {
    !ticked -> GlanceLevel.NOT_READ
    secret -> GlanceLevel.APP_ONLY
    showText -> GlanceLevel.TEXT
    messaging -> GlanceLevel.SENDER
    else -> GlanceLevel.APP_ONLY
}

/** A notification's title as the lock shows it (§2.2): a group's conversation title, else the
 * newest sender, else the conversation title, else the notification's title - each already cleaned. */
fun glanceTitle(group: Boolean, conversationTitle: String?, newestSender: String?, title: String?): String? =
    if (group && conversationTitle != null) conversationTitle else newestSender ?: conversationTitle ?: title

/** What a notification's title names (2026-10-25, PLAN "Contact photos on the glance's message rows"): one sender
 * ([DIRECT] - a MessagingStyle conversation that isn't a group: its title is the sender), a group by its own name
 * ([GROUP] - its newest sender is kept beside the name), or anything else ([PLAIN]: another style's title, or a group
 * without a name, whose title is then its newest sender). */
enum class GlanceShape { DIRECT, GROUP, PLAIN }

/** The shape (each input as [glanceTitle] reads it): only a MessagingStyle notification has a sender. */
fun glanceShape(messaging: Boolean, group: Boolean, conversationTitle: String?): GlanceShape = when {
    !messaging -> GlanceShape.PLAIN
    group -> if (conversationTitle != null) GlanceShape.GROUP else GlanceShape.PLAIN
    else -> GlanceShape.DIRECT
}

/** A group's text gets its sender in front ("Pappa: Kommer kl 5"). */
fun glanceGroupText(group: Boolean, sender: String?, text: String?): String? = when {
    text == null -> null
    group && sender != null -> "$sender: $text"
    else -> text
}

/** The packages whose newest sender is matched by the person's `tel:` URI (the user's decision 2026-10-25: WhatsApp's
 * `sender_person` carries the number as `tel:+47…`). */
val GLANCE_TEL_PACKAGES = setOf("com.whatsapp", "com.whatsapp.w4b")

/**
 * The phone-book contact a WhatsApp sender is (2026-10-25): [uri] is the newest sender person's URI, used only when it is
 * a `tel:` URI - its number normalised as the phone book's ([PhoneNumbers.normalize] with the rules' country code) and
 * compared exactly with the outbound contacts' numbers, in memory. Returns the contact's id (`> 0`; a contact without one
 * matches nothing) - never the number, which is neither kept nor logged. Unmanaged or unknown rules: nobody.
 */
fun glanceTelContact(uri: String?, state: CallPolicyState): Long? {
    if (uri == null || !uri.regionMatches(0, "tel:", 0, 4, ignoreCase = true)) return null
    val rules = (state as? CallPolicyState.Managed)?.rules ?: return null
    val raw = PhoneNumbers.numberFromHandle(uri) ?: return null
    val number = PhoneNumbers.normalize(raw, rules.defaultCc)?.value ?: return null
    return rules.phoneBook.firstOrNull { it.number == number }?.id?.takeIf { it > 0 }
}

/** The phone-book contact whose photo a message row shows in the app icon's place: the row's [GlanceMessageRow.contactId]
 * among the outbound contacts now - only from [GlanceLevel.SENDER] up (at APP_ONLY the app hides who wrote). */
fun glanceRowContact(row: GlanceMessageRow, state: CallPolicyState): RuleContact? {
    val id = row.contactId?.takeIf { row.level >= GlanceLevel.SENDER && it > 0 } ?: return null
    return (state as? CallPolicyState.Managed)?.rules?.phoneBook?.firstOrNull { it.id == id }
}

/** What follows the name on a message row's name line (2026-10-25). */
sealed interface GlanceNameSuffix {
    /** A group's newest sender in "Name only" ("Fotballgjengen · Pappa"). */
    data class Sender(val name: String) : GlanceNameSuffix {
        override fun toString() = "Sender"
    }

    /** "Full text": several messages from one sender folded into the row ("Mamma · 3 nye"). */
    data class NewCount(val count: Int) : GlanceNameSuffix
}

/**
 * A message row's name-line suffix: a [GlanceShape.GROUP] row at [GlanceLevel.SENDER] ("Name only") gets its newest
 * sender (when known and not the name itself) - "Full text" keeps "Sender: text" under the group's name; a
 * [GlanceShape.DIRECT] row at [GlanceLevel.TEXT] showing its text gets the count when more than one message is folded
 * in. Never on a missed-call row (its subtitle says it) or at APP_ONLY.
 */
fun glanceNameSuffix(row: GlanceMessageRow): GlanceNameSuffix? = when {
    row.missedCall || row.title == null -> null
    row.shape == GlanceShape.GROUP && row.level == GlanceLevel.SENDER ->
        row.sender?.takeIf { it != row.title }?.let(GlanceNameSuffix::Sender)
    row.shape == GlanceShape.DIRECT && row.level == GlanceLevel.TEXT && row.text != null && row.count > 1 ->
        GlanceNameSuffix.NewCount(row.count)
    else -> null
}

/** Whether TalkBack names the row as a group ("Gruppe Fotballgjengen, siste fra Pappa"): a group row with its sender. */
fun glanceSpokenGroup(row: GlanceMessageRow): String? =
    row.sender?.takeIf { row.shape == GlanceShape.GROUP && row.level >= GlanceLevel.SENDER && row.title != null && it != row.title }

/** How many messages a notification stands for (§2.2): MessagingStyle ([messages] bundles) - the
 * ones with a sender ([fromOthers]; the kid's own have none); any other style - its `number` when
 * set, else 1. */
fun glanceCount(messages: Int, fromOthers: Int, number: Int): Int =
    if (messages > 0) fromOthers else number.takeIf { it > 0 } ?: 1

/** Every kind of whitespace, Unicode's included (Java's `\s` is ASCII only). */
private val GLANCE_WHITESPACE = Regex("[\\s\\p{Z}]+")

const val GLANCE_TITLE_MAX = 60
const val GLANCE_TEXT_MAX = 200

/**
 * Cleaning (§2.2): control characters and bidi overrides and isolates (U+202A-202E, U+2066-2069) out
 * (a space each), the invisible marks around them (U+061C, U+200E/200F, U+2060-2064, U+FEFF - code QA
 * Low 5) dropped (joiners stay: emoji need them), all whitespace collapsed - Unicode's too, line and
 * paragraph separators and no-break spaces included -, capped at [max] characters (a cut ends in "…"),
 * empty = absent (the row then uses the app's label).
 */
fun cleanGlanceText(raw: CharSequence?, max: Int): String? {
    if (raw == null) return null
    val kept = StringBuilder(raw.length)
    for (c in raw) {
        val bidi = c in '\u202A'..'\u202E' || c in '\u2066'..'\u2069'
        val mark = c in '\u2060'..'\u2064' || c == '\u061C' || c == '\u200E' || c == '\u200F' || c == '\uFEFF'
        when {
            mark -> Unit
            Character.isISOControl(c) || bidi -> kept.append(' ')
            else -> kept.append(c)
        }
    }
    val text = kept.toString().replace(GLANCE_WHITESPACE, " ").trim()
    if (text.isEmpty()) return null
    if (text.length <= max) return text
    var end = max - 1
    // Never cut a surrogate pair in half.
    if (end > 0 && Character.isHighSurrogate(text[end - 1])) end--
    return text.substring(0, end).trimEnd() + "…"
}

/**
 * What the reader kept of one notification (memory only, [GlanceStore]). [conversationKey] is the
 * shortcut id, else the notification key; [title]/[text] are cleaned and present only at the level
 * that reads them; [suspended]: the app is suspended now (or no ranking was found - fail closed).
 */
data class GlanceNotice(
    val key: String,
    val packageName: String,
    val conversationKey: String,
    val level: GlanceLevel,
    val title: String?,
    val text: String?,
    val count: Int,
    val whenMs: Long,
    val postTimeMs: Long,
    val suspended: Boolean,
    /** `Notification.category` is `missed_call` (Element X's room notification with an RTC
     * notification event - a missed Element Call; other apps' missed calls alike). */
    val missedCall: Boolean = false,
    /** What [title] names ([glanceShape]). */
    val shape: GlanceShape = GlanceShape.PLAIN,
    /** The newest sender's cleaned name, from [GlanceLevel.SENDER] up (a group row shows it after the group's name). */
    val sender: String? = null,
    /** The newest sender's phone-book contact id (Element X: design 15's link; WhatsApp: the `tel:` URI), from
     * [GlanceLevel.SENDER] up - our own contact's id, never the sender's key or number. */
    val contactId: Long? = null,
) {
    override fun toString() = "GlanceNotice($packageName)"
}

/** A conversation: (package, shortcut id else notification key) - the unit of a row and of the
 * "opened through the glance" mark. */
data class GlanceConversation(val packageName: String, val conversationKey: String) {
    override fun toString() = "GlanceConversation($packageName)"
}

/** One row of the glance page. [timeMs] orders them, newest first. */
sealed interface GlanceRow {
    val timeMs: Long
}

/**
 * A conversation's row: the newest notification's [notificationKey], [title], [text] and time,
 * every notification's [count] added up, the most restrictive [level] of them.
 */
data class GlanceMessageRow(
    val conversation: GlanceConversation,
    val notificationKey: String,
    val level: GlanceLevel,
    val title: String?,
    val text: String?,
    val count: Int,
    override val timeMs: Long,
    val postTimeMs: Long,
    /** The newest notification is a missed call: the row says "Tapt anrop", not "N meldinger". */
    val missedCall: Boolean = false,
    /** The newest notification's [GlanceNotice.shape]. */
    val shape: GlanceShape = GlanceShape.PLAIN,
    /** The newest notification's sender, from [GlanceLevel.SENDER] up. */
    val sender: String? = null,
    /** The newest notification's contact ([glanceRowContact] finds it in the phone book). */
    val contactId: Long? = null,
) : GlanceRow {
    val packageName: String get() = conversation.packageName

    override fun toString() = "GlanceRow($packageName)"
}

/** A missed-call row: one per contact ([number] and its phone-book [name]), or the plain one
 * ([number] `null`: "N tapte anrop", no names - fail-closed or unmanaged with our dialer role). */
data class GlanceMissedRow(
    val number: String?,
    val name: String?,
    val count: Int,
    override val timeMs: Long,
) : GlanceRow {
    override fun toString() = "GlanceMissedRow(count=$count)"
}

/**
 * The rows (§2.3), newest first: one per conversation of a ticked, unsuspended app - several
 * notifications of one conversation add their counts, the newest (by post time) gives title, text,
 * time and key - hidden while it was opened through the glance and nothing newer was posted since
 * ([opened]: the newest post time at the tap); plus our missed-call notification's contacts, or its
 * plain row. A message row's time is the notification's `when`, else its post time.
 */
fun glanceRows(
    notices: Collection<GlanceNotice>,
    missed: MissedCallNotice?,
    policy: GlancePolicy,
    opened: Map<GlanceConversation, Long>,
): List<GlanceRow> {
    val messages = notices
        .filter { !it.suspended && it.packageName in policy.apps && it.level != GlanceLevel.NOT_READ }
        .groupBy { GlanceConversation(it.packageName, it.conversationKey) }
        .mapNotNull { (conversation, group) ->
            val newest = group.maxWith(compareBy<GlanceNotice> { it.postTimeMs }.thenBy { it.key })
            val mark = opened[conversation]
            if (mark != null && newest.postTimeMs <= mark) return@mapNotNull null
            val level = group.minOf { it.level }
            GlanceMessageRow(
                conversation = conversation,
                notificationKey = newest.key,
                level = level,
                title = newest.title.takeIf { level >= GlanceLevel.SENDER },
                text = newest.text.takeIf { level == GlanceLevel.TEXT },
                count = group.sumOf { it.count }.coerceAtLeast(1),
                timeMs = newest.whenMs.takeIf { it > 0 } ?: newest.postTimeMs,
                postTimeMs = newest.postTimeMs,
                missedCall = newest.missedCall,
                shape = newest.shape,
                sender = newest.sender.takeIf { level >= GlanceLevel.SENDER },
                contactId = newest.contactId.takeIf { level >= GlanceLevel.SENDER },
            )
        }
    val calls: List<GlanceRow> = when {
        missed == null -> emptyList()
        missed.contacts.isEmpty() -> listOf(GlanceMissedRow(null, null, missed.count, missed.lastAtMs))
        else -> missed.contacts.map { GlanceMissedRow(it.number, it.name, it.count, it.lastAtMs) }
    }
    return (messages + calls).sortedByDescending { it.timeMs }
}
