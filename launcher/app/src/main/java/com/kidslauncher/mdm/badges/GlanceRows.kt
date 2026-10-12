package com.kidslauncher.mdm.badges

import com.kidslauncher.mdm.appdisplay.isValidPackageName
import com.kidslauncher.mdm.calls.MessagePackages
import com.kidslauncher.mdm.calls.MissedCallNotice
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
 * ([apps]) and whether with their text ([showText]). Missed calls always show; they aren't in it.
 */
data class GlancePolicy(val apps: Set<String>, val showText: Boolean) {
    companion object {
        /** No usable policy (a corrupt or missing cache, the last-enforced fallback, anything the
         * server sent that isn't an object): missed calls only - the parent may have unticked
         * something. */
        val UNKNOWN = GlancePolicy(emptySet(), showText = false)

        /** What an older server without the key means (its own default for every phone). */
        val SERVER_DEFAULT = GlancePolicy(setOf(MessagePackages.ELEMENT_X), showText = false)
    }
}

/**
 * `PolicyResponse.lockGlance` read field by field: nothing in it can fail the policy (design 25
 * §2.1, like `app_display` and `music`).
 * - an object: `apps` = its valid package names (anything else in the list is dropped, so is a
 *   non-list), `show_text` true only for JSON `true`;
 * - missing or JSON `null`: from a fresh response ([fresh]) an older server - its own default,
 *   Element X with the sender only; from the cache it is a cache an older build wrote (it never
 *   kept the key), so unknown (QA Low 11);
 * - anything else: unknown.
 */
fun lockGlancePolicy(element: JsonElement?, fresh: Boolean): GlancePolicy = when (element) {
    null, JsonNull -> if (fresh) GlancePolicy.SERVER_DEFAULT else GlancePolicy.UNKNOWN
    is JsonObject -> GlancePolicy(
        apps = (element["apps"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .filter(::isValidPackageName)
            .toSet(),
        showText = (element["show_text"] as? JsonPrimitive)?.let { !it.isString && it.content == "true" } == true,
    )
    else -> GlancePolicy.UNKNOWN
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

/** A group's text gets its sender in front ("Pappa: Kommer kl 5"). */
fun glanceGroupText(group: Boolean, sender: String?, text: String?): String? = when {
    text == null -> null
    group && sender != null -> "$sender: $text"
    else -> text
}

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
            )
        }
    val calls: List<GlanceRow> = when {
        missed == null -> emptyList()
        missed.contacts.isEmpty() -> listOf(GlanceMissedRow(null, null, missed.count, missed.lastAtMs))
        else -> missed.contacts.map { GlanceMissedRow(it.number, it.name, it.count, it.lastAtMs) }
    }
    return (messages + calls).sortedByDescending { it.timeMs }
}
