package com.kidslauncher.mdm.day

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.longOrNull

/*
 * The parent's Dagen settings (design 69 §4.1): `PolicyResponse.calendar`, read field by field like `lock_glance`
 * (nothing in it can fail the policy). Pure; CalendarPolicyTest, PolicyResponseCompatTest.
 */

/** A launcher with Dagen reports this capability; the server's Calendar page waits for it. */
const val CALENDAR_CAPABILITY = "calendar_v1"

/** A calendar's reminder (§5.2): none, the calendar's own alarms, or a fixed number of minutes before every event. */
sealed interface Remind {
    data object Off : Remind
    data object Own : Remind
    data class Before(val minutes: Int) : Remind
}

/** The minutes "N min before" may say; anything else is no reminder. */
val REMIND_MINUTES = setOf(5, 10, 15, 30, 60)

/** One calendar as the parent set it. A calendar without a row is shown, reminders off, not the kid's. */
data class CalendarSetting(val shown: Boolean = true, val remind: Remind = Remind.Off, val writableForKid: Boolean = false)

data class CalendarPolicy(
    /** A usable policy: `false` = none (a corrupt or missing cache) - no Dagen at all, never "everything shown". */
    val usable: Boolean,
    /** Dagen on Home: off hides the tile, the glance line and the reminders (the rules still apply). */
    val onHome: Boolean = true,
    /** The kid may add events: to [kidCalendarKey]'s calendar, else the phone's own "Min kalender". */
    val kidAdds: Boolean = false,
    /** The lock's "Neste: …" line (A). */
    val glanceNext: Boolean = false,
    /** The master switch over every calendar's reminder: `true` = the calm row (C). */
    val lockReminders: Boolean = false,
    val calendars: Map<String, CalendarSetting> = emptyMap(),
    val rules: List<TitleRule> = emptyList(),
    /** Ids of rules this launcher can't use (an unknown field, match or action; a description rule that doesn't
     * hide; nothing to match), reported in the status report. */
    val rulesSkipped: List<Long> = emptyList(),
) {
    fun setting(key: String): CalendarSetting = calendars[key] ?: CalendarSetting()

    /** The parent's pick for the kid's own events; `null` = the phone's own "Min kalender". */
    val kidCalendarKey: String? get() = calendars.entries.firstOrNull { it.value.writableForKid }?.key

    override fun toString() = "CalendarPolicy(usable=$usable, rules=${rules.size})"

    companion object {
        /** No usable policy: Dagen off. */
        val UNKNOWN = CalendarPolicy(usable = false, onHome = false)

        /** An older server without the key: every calendar shown, no rules, nothing on the lock, no "+". */
        val OLDER_SERVER = CalendarPolicy(usable = true)
    }
}

private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

/**
 * `PolicyResponse.calendar` field by field: missing or JSON `null` = an older server ([CalendarPolicy.OLDER_SERVER]);
 * anything but an object = [CalendarPolicy.UNKNOWN]. A switch that isn't a JSON boolean is its default; a calendar row
 * without a string key is dropped; a rule without a numeric id is dropped, one this launcher can't use is dropped and
 * its id reported.
 */
fun calendarPolicy(element: JsonElement?): CalendarPolicy {
    val obj = when (element) {
        null, JsonNull -> return CalendarPolicy.OLDER_SERVER
        is JsonObject -> element
        else -> return CalendarPolicy.UNKNOWN
    }
    val calendars = LinkedHashMap<String, CalendarSetting>()
    for (row in (obj["calendars"] as? JsonArray).orEmpty()) {
        val r = row as? JsonObject ?: continue
        val key = r.string("key") ?: continue
        val minutes = (r["remind_minutes"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull?.toInt()
        val remind = when (r.string("remind")) {
            "own" -> Remind.Own
            "before" -> if (minutes in REMIND_MINUTES) Remind.Before(minutes!!) else Remind.Off
            else -> Remind.Off
        }
        calendars.putIfAbsent(key, CalendarSetting(r.bool("shown") ?: true, remind, r.bool("writable_for_kid") == true))
    }
    val rules = ArrayList<TitleRule>()
    val skipped = ArrayList<Long>()
    for (row in (obj["rules"] as? JsonArray).orEmpty()) {
        val r = row as? JsonObject ?: continue
        val id = (r["id"] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull ?: continue
        val rule = titleRule(id, r)
        if (rule != null) rules += rule else skipped += id
    }
    return CalendarPolicy(
        usable = true,
        onHome = obj.bool("on_home") ?: true,
        kidAdds = obj.bool("kid_adds") ?: false,
        glanceNext = obj.bool("glance_next") ?: false,
        lockReminders = obj.string("lock_reminders") == "row",
        calendars = calendars,
        rules = rules,
        rulesSkipped = skipped,
    )
}

/** One rule, or `null` when this launcher can't use it (§4.1, §3.4's grammar). */
private fun titleRule(id: Long, r: JsonObject): TitleRule? {
    val field = when (r.string("field")) {
        "title" -> RuleField.TITLE
        "description" -> RuleField.DESCRIPTION
        else -> return null
    }
    val match = when (r.string("match")) {
        "starts" -> RuleMatch.STARTS
        "contains" -> RuleMatch.CONTAINS
        else -> return null
    }
    val action = when (r.string("action")) {
        "replace" -> RuleAction.REPLACE
        "prefix" -> RuleAction.PREFIX
        "hide" -> RuleAction.HIDE
        else -> return null
    }
    val pattern = TitleRules.clean(r.string("pattern"), PATTERN_MAX)
    val text = TitleRules.clean(r.string("text"), TEXT_MAX)
    if (pattern.isEmpty()) return null
    if (field == RuleField.DESCRIPTION && action != RuleAction.HIDE) return null
    if (action == RuleAction.REPLACE && text.isEmpty()) return null
    // A calendar that isn't a string is a key the phone doesn't have: the rule applies to nothing.
    val calendar = when (val c = r["calendar"]) {
        null, JsonNull -> null
        else -> (c as? JsonPrimitive)?.takeIf { it.isString }?.content ?: "-"
    }
    return TitleRule(id, calendar, field, match, pattern, action, text)
}
