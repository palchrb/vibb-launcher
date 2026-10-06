package com.kidslauncher.mdm.badges

/*
 * Other apps' notifications as a kiosk escape (handy step 11, design 11-kiosk-escapes.md §3 with
 * qa-11-design.md #11-#13 and the binding decisions): while the phone is managed, our listener
 * cancels a clearable notification of a package that is neither allowed nor essential - Play
 * services' "set a screen lock", Safety Center, setup and wellbeing nags whose tap would open a
 * screen outside the kiosk. Pure, no Android imports - NotificationRuleTest. The glue is
 * NotificationRuleRuntime + BadgeListenerService.
 *
 * Privacy: the input carries only the package, `Notification.channelId` (never the Ranking's
 * conversation channel or a shortcut id - they can encode a contact), the category and flags -
 * no title, text, key or tag. Logs and the status report carry package + channel ids and capped
 * counts only.
 */

/** What the rule may know about one notification. Deliberately no text fields (tested). */
data class NotificationFacts(
    val packageName: String,
    /** `Notification.getChannelId()` - the app's own channel, not a conversation channel. */
    val channelId: String?,
    /** `Notification.category`. */
    val category: String?,
    /** `FLAG_ONGOING_EVENT` (or a foreground service's). */
    val ongoing: Boolean,
    /** `StatusBarNotification.isClearable`. */
    val clearable: Boolean,
    /** Has a `fullScreenIntent` - incoming calls, alarms, emergency alerts. */
    val fullScreenIntent: Boolean,
    /** `FLAG_INSISTENT` - repeats its sound until seen (alarms, alerts). */
    val insistent: Boolean,
)

/**
 * What the rule needs from the enforced policy, resolved off the main thread (no PackageManager
 * work in the listener's callbacks). `null` = the policy is unknown: nothing is cancelled.
 */
data class NagPolicy(
    /** The server's `notification_auto_cancel` switch. */
    val enabled: Boolean,
    /** Apps are managed (an allowlist, no override or pause). */
    val managed: Boolean,
    /** The effective allowed set: allowlist + the contacts' messaging apps + a time rule's usable apps. */
    val allowed: Set<String>,
    /** Resolved essentials: dialers, Telecom, emergency dialer, `com.android.phone`, every
     * cell-broadcast receiver, the SMS app while SMS is on, the clock/alarm app, the keyboards,
     * SystemUI and `android` (battery, USB, system). */
    val essential: Set<String>,
    /** Ours and the `.debug`/release sibling. */
    val own: Set<String>,
)

/** `Notification.CATEGORY_*` values that always stay (literal strings: Android-free). */
val ESSENTIAL_CATEGORIES: Set<String> = setOf("call", "missed_call", "alarm", "stopwatch")

/**
 * Packages always essential, whatever resolves on this phone: the platform, SystemUI, the phone
 * process, Telecom, and the cell-broadcast receivers/services under their AOSP, mainline-module
 * and Google names (qa-11-design.md #11).
 */
val ESSENTIAL_PACKAGES: Set<String> = setOf(
    "android",
    "com.android.systemui",
    "com.android.phone",
    "com.android.server.telecom",
    "com.android.cellbroadcastreceiver",
    "com.android.cellbroadcastreceiver.module",
    "com.google.android.cellbroadcastreceiver",
    "com.android.cellbroadcastservice",
    "com.google.android.cellbroadcastservice",
)

/**
 * Channel ids that look like a public-safety alert (Play services posts the Android Earthquake
 * Alerts and personal-safety alerts; the B1 device check classifies the real ids). Matching is
 * deliberately generous: a nag kept by mistake is harmless, a cancelled alert is not.
 */
private val ESSENTIAL_CHANNEL_HINTS = listOf(
    "earthquake", "emergency", "ealert", "public_alert", "publicalert", "cmas", "etws",
    "cellbroadcast", "cell_broadcast", "crisis", "disaster", "safety_alert", "tsunami",
)

fun essentialChannel(channelId: String?): Boolean {
    val id = channelId?.lowercase() ?: return false
    return ESSENTIAL_CHANNEL_HINTS.any { it in id }
}

enum class NagKeep {
    POLICY_UNKNOWN, SWITCHED_OFF, UNMANAGED, OWN, ONGOING, NOT_CLEARABLE, FULL_SCREEN, INSISTENT,
    ESSENTIAL_CATEGORY, ESSENTIAL_PACKAGE, ESSENTIAL_CHANNEL, ALLOWED,
}

sealed interface NagVerdict {
    data class Keep(val reason: NagKeep) : NagVerdict
    data object Cancel : NagVerdict
}

/**
 * The generic rule: cancel a clearable notification of a package that is neither ours, allowed
 * nor essential - only while managed, with the switch on and the policy known. Never an ongoing
 * one (lock task still blocks its tap), never a full-screen or insistent one (calls, alarms,
 * emergency alerts), never a call/missed-call/alarm/stopwatch, never a channel that looks like a
 * public-safety alert.
 */
fun nagVerdict(facts: NotificationFacts, policy: NagPolicy?): NagVerdict {
    val keep = when {
        policy == null -> NagKeep.POLICY_UNKNOWN
        !policy.enabled -> NagKeep.SWITCHED_OFF
        !policy.managed -> NagKeep.UNMANAGED
        facts.packageName in policy.own -> NagKeep.OWN
        facts.ongoing -> NagKeep.ONGOING
        !facts.clearable -> NagKeep.NOT_CLEARABLE
        facts.fullScreenIntent -> NagKeep.FULL_SCREEN
        facts.insistent -> NagKeep.INSISTENT
        facts.category in ESSENTIAL_CATEGORIES -> NagKeep.ESSENTIAL_CATEGORY
        facts.packageName in ESSENTIAL_PACKAGES || facts.packageName in policy.essential -> NagKeep.ESSENTIAL_PACKAGE
        essentialChannel(facts.channelId) -> NagKeep.ESSENTIAL_CHANNEL
        facts.packageName in policy.allowed -> NagKeep.ALLOWED
        else -> null
    }
    return if (keep != null) NagVerdict.Keep(keep) else NagVerdict.Cancel
}

// ---- re-post budget ---------------------------------------------------------------------------

/** Cancels per (package, channel) within [REPOST_WINDOW_MS] before the rule snoozes instead. */
const val REPOST_BUDGET = 3
const val REPOST_WINDOW_MS = 60_000L

/** How long a notification past the budget is snoozed (a snooze fires no delete intent). */
const val NAG_SNOOZE_MS = 60 * 60_000L

enum class NagAction { CANCEL, SNOOZE }

/**
 * Each cancel fires the app's delete intent, and an app may post the same nag again at once
 * (qa-11-design.md #12): past [REPOST_BUDGET] cancels per (package, channel) a minute the
 * notification is snoozed for [NAG_SNOOZE_MS] instead. Keeps at most [maxKeys] pairs (oldest out).
 */
class RepostBudget(
    private val budget: Int = REPOST_BUDGET,
    private val windowMs: Long = REPOST_WINDOW_MS,
    private val maxKeys: Int = 64,
) {
    private val recent = LinkedHashMap<Pair<String, String?>, ArrayDeque<Long>>()

    @Synchronized
    fun action(packageName: String, channelId: String?, nowElapsedMs: Long): NagAction {
        val key = packageName to channelId
        val times = recent.remove(key) ?: ArrayDeque()
        while (times.isNotEmpty() && nowElapsedMs - times.first() >= windowMs) times.removeFirst()
        val action = if (times.size >= budget) NagAction.SNOOZE else NagAction.CANCEL
        if (action == NagAction.CANCEL) times.addLast(nowElapsedMs)
        recent[key] = times
        while (recent.size > maxKeys) recent.remove(recent.keys.first())
        return action
    }
}

// ---- counts for the status report and rate-limited logs ---------------------------------------

/** Status report limits (qa-11-design.md #13): at most this many (package, channel) entries, ids capped. */
const val NAG_REPORT_MAX_ENTRIES = 20
const val NAG_REPORT_MAX_ID = 64

data class NagCount(val packageName: String, val channelId: String?, val cancelled: Int, val snoozed: Int)

/**
 * Counts since the last status report per (package, channel) - no keys, tags or text. [take]
 * returns the [NAG_REPORT_MAX_ENTRIES] largest, ids cut to [NAG_REPORT_MAX_ID], and starts over.
 * Holds at most [maxKeys] pairs; later new pairs are only counted in [overflow].
 */
class NagCounts(private val maxKeys: Int = 100) {
    private val counts = LinkedHashMap<Pair<String, String?>, IntArray>()
    private var overflow = 0

    @Synchronized
    fun record(packageName: String, channelId: String?, action: NagAction) {
        val key = packageName to channelId
        val entry = counts[key] ?: if (counts.size >= maxKeys) {
            overflow++
            return
        } else {
            IntArray(2).also { counts[key] = it }
        }
        if (action == NagAction.CANCEL) entry[0]++ else entry[1]++
    }

    /** The cancels/snoozes of (package, channel) since the last [take] - for the log line. */
    @Synchronized
    fun total(packageName: String, channelId: String?): Int = counts[packageName to channelId]?.sum() ?: 0

    @Synchronized
    fun take(): Pair<List<NagCount>, Int> {
        val list = counts.entries
            .sortedByDescending { it.value.sum() }
            .take(NAG_REPORT_MAX_ENTRIES)
            .map { (key, value) -> NagCount(key.first.take(NAG_REPORT_MAX_ID), key.second?.take(NAG_REPORT_MAX_ID), value[0], value[1]) }
        val dropped = overflow + (counts.size - list.size).coerceAtLeast(0)
        counts.clear()
        overflow = 0
        return list to dropped
    }
}

/** Log a (package, channel) only on its 1st, 10th, 100th... action since the last report. */
fun nagLogDue(totalSoFar: Int): Boolean {
    if (totalSoFar <= 1) return totalSoFar == 1
    var n = totalSoFar
    while (n % 10 == 0) n /= 10
    return n == 1
}
