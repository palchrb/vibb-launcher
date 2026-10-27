package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.Serializable
import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/*
 * A calendar link's set as the phone writes it (design 72 §3-§4): the server's document (`GET
 * /api/devices/calendar/feeds/{id}`) decoded, and each series mapped to the provider's Events and Reminders rows. Pure
 * (the provider's column names are spelled here; FeedEventsTest checks each against CalendarContract), shared contract
 * `calendar_feeds.json` (CalendarFeedsTest).
 */

/** One override of a series on the wire: every time in the series' zone. */
@Serializable
data class FeedOverride(
    val recurrenceId: String,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val start: String,
    val end: String,
    val status: String = "confirmed",
    val alarms: List<Int> = emptyList(),
) {
    override fun toString() = "FeedOverride($recurrenceId)"
}

/** One series (or single event) on the wire (§3). */
@Serializable
data class FeedEvent(
    val uid: String,
    val rev: String,
    val summary: String = "",
    val description: String = "",
    val location: String = "",
    val start: String,
    val end: String,
    /** An IANA zone, `UTC` for `Z` times; `null` = floating (the phone's zone); ignored for all-day. */
    val tz: String? = null,
    val allDay: Boolean = false,
    val rrule: String? = null,
    val rdates: List<String> = emptyList(),
    val exdates: List<String> = emptyList(),
    val status: String = "confirmed",
    val alarms: List<Int> = emptyList(),
    val overrides: List<FeedOverride> = emptyList(),
) {
    override fun toString() = "FeedEvent(${overrides.size} overrides)"
}

/** The document: `v` 1, the link's uid, the set's rev. */
@Serializable
data class FeedDocument(val v: Int, val id: String, val rev: String, val events: List<FeedEvent>) {
    override fun toString() = "FeedDocument(${events.size} events)"
}

/** The document, or `null` when it doesn't read (a failed GET changes nothing). */
fun feedDocument(text: String): FeedDocument? = try {
    ServerJson.decodeFromString(FeedDocument.serializer(), text).takeIf { it.v == 1 }
} catch (e: Exception) {
    null
}

/** The provider's column names (CalendarContract's own; FeedEventsTest pins each). */
object FeedColumns {
    const val SYNC_ID = "_sync_id"
    const val UID = "uid2445"
    /** The series' rev: an unchanged rev is not rewritten. */
    const val SERIES_REV = "sync_data1"
    /** The zone a floating series was written in: a new zone rewrites it. */
    const val WRITTEN_ZONE = "sync_data2"
    const val TITLE = "title"
    const val DESCRIPTION = "description"
    const val LOCATION = "eventLocation"
    const val STATUS = "eventStatus"
    const val DTSTART = "dtstart"
    const val DTEND = "dtend"
    const val DURATION = "duration"
    const val TIMEZONE = "eventTimezone"
    const val ALL_DAY = "allDay"
    const val RRULE = "rrule"
    const val RDATE = "rdate"
    const val EXDATE = "exdate"
    const val HAS_ALARM = "hasAlarm"
    const val ORIGINAL_SYNC_ID = "original_sync_id"
    const val ORIGINAL_INSTANCE_TIME = "originalInstanceTime"
    const val ORIGINAL_ALL_DAY = "originalAllDay"

    /** `Events.STATUS_*`. */
    const val STATUS_TENTATIVE = 0
    const val STATUS_CONFIRMED = 1
    const val STATUS_CANCELED = 2
}

/** How a series is written; bumped when this launcher maps differently - every feed calendar is then rewritten
 * (`CAL_SYNC2`). */
const val FEED_MAPPING = "1"

/** EXDATE's form (QA L7, [verify] on the emulator, §6y): `true` = the provider's EXDATE column, UTC
 * `yyyyMMdd'T'HHmmss'Z'` comma-joined (all-day `yyyyMMdd`); `false` = each exclusion a cancelled override row. */
const val EXDATE_COLUMN = true

/** One Events row: its columns, and its alarms (one Reminders row each, METHOD_ALERT). */
data class EventRow(val values: Map<String, Any?>, val reminders: List<Int>) {
    val isOverride: Boolean get() = values.containsKey(FeedColumns.ORIGINAL_SYNC_ID)

    override fun toString() = "EventRow(override=$isOverride, ${reminders.size} reminders)"
}

/** A series as the provider gets it: the master first, then its overrides. */
data class SeriesRows(
    val uid: String,
    val rev: String,
    val rows: List<EventRow>,
    /** Its zone isn't in this phone's tz database: written in the phone's zone, counted in the report. */
    val unknownZone: Boolean,
    /** Written in the phone's zone (floating or unknown): a zone change rewrites it. */
    val floating: Boolean,
) {
    /** The provider operations it takes: each row and each of its reminders. */
    val operations: Int get() = rows.sumOf { 1 + it.reminders.size }

    override fun toString() = "SeriesRows(${rows.size} rows)"
}

private val LOCAL_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")
private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private val UTC_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)

private fun zoneOf(id: String): ZoneId? = try {
    ZoneId.of(id)
} catch (e: DateTimeException) {
    null
}

/** A wire time in ms: an all-day date as its UTC midnight, a time in `zone`; `null` when it doesn't read. */
internal fun wireMillis(text: String, allDay: Boolean, zone: ZoneId): Long? = try {
    if (allDay || text.length == 8) {
        val day = LocalDate.parse(text.take(8), DAY)
        if (allDay) day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() else day.atStartOfDay(zone).toInstant().toEpochMilli()
    } else {
        LocalDateTime.parse(text, LOCAL_TIME).atZone(zone).toInstant().toEpochMilli()
    }
} catch (e: DateTimeException) {
    null
}

/** RDATE/EXDATE values as the provider reads them: all-day `yyyyMMdd`, else UTC `yyyyMMdd'T'HHmmss'Z'`, comma-joined. */
private fun dateList(values: List<String>, allDay: Boolean, zone: ZoneId): String? {
    val written = values.mapNotNull { v ->
        if (allDay) v.take(8).takeIf { it.length == 8 } else wireMillis(v, false, zone)?.let { UTC_TIME.format(java.time.Instant.ofEpochMilli(it)) }
    }
    return written.takeIf { it.isNotEmpty() }?.joinToString(",")
}

/** RRULE with a local `UNTIL` made UTC in `zone` (QA L6: floating or unknown-zone series - the server leaves those
 * local); an all-day series keeps a date. */
internal fun ruleWithUtcUntil(rrule: String, allDay: Boolean, zone: ZoneId): String = rrule.split(';').joinToString(";") { part ->
    val until = part.removePrefix("UNTIL=")
    if (until == part) return@joinToString part
    try {
        when {
            allDay -> "UNTIL=${until.take(8)}"
            until.endsWith("Z") -> part
            until.length == 8 -> "UNTIL=" + UTC_TIME.format(LocalDate.parse(until, DAY).atTime(23, 59, 59).atZone(zone).toInstant())
            else -> "UNTIL=" + UTC_TIME.format(LocalDateTime.parse(until, LOCAL_TIME).atZone(zone).toInstant())
        }
    } catch (e: DateTimeException) {
        part
    }
}

private fun statusOf(status: String) = when (status) {
    "tentative" -> FeedColumns.STATUS_TENTATIVE
    "cancelled" -> FeedColumns.STATUS_CANCELED
    else -> FeedColumns.STATUS_CONFIRMED
}

/**
 * One series' rows (§4): the master - `_SYNC_ID` = `UID_2445` = uid, `SYNC_DATA1` = the series' rev, `SYNC_DATA2` =
 * the zone a floating series is written in; single: DTSTART + DTEND; recurring: DTSTART + DURATION (`PT<n>S`, all-day
 * `P<n>D`) + RRULE (a local UNTIL made UTC), RDATE, EXDATE; EVENT_TIMEZONE the series' zone, the phone's when floating
 * or unknown, UTC all-day (UTC midnights) - then each override (`_SYNC_ID` = uid `#` recurrence id, ORIGINAL_SYNC_ID,
 * ORIGINAL_INSTANCE_TIME, ORIGINAL_ALL_DAY, the series' zone; a cancelled one STATUS_CANCELED). `null` when the
 * start doesn't read.
 */
fun seriesRows(event: FeedEvent, phoneZone: ZoneId, exdateColumn: Boolean = EXDATE_COLUMN): SeriesRows? {
    val known = event.tz?.let { zoneOf(it) }
    val allDay = event.allDay
    val unknownZone = !allDay && event.tz != null && known == null
    val floating = !allDay && known == null
    val zone: ZoneId = if (allDay) ZoneOffset.UTC else known ?: phoneZone
    val tzId = if (allDay) "UTC" else zone.id
    val start = wireMillis(event.start, allDay, zone) ?: return null
    val end = (wireMillis(event.end, allDay, zone) ?: start).coerceAtLeast(start)
    val recurring = event.rrule != null || event.rdates.isNotEmpty()
    val c = FeedColumns
    val master = linkedMapOf<String, Any?>(
        c.SYNC_ID to event.uid,
        c.UID to event.uid,
        c.SERIES_REV to event.rev,
        c.WRITTEN_ZONE to if (floating) phoneZone.id else null,
        c.TITLE to event.summary,
        c.DESCRIPTION to event.description,
        c.LOCATION to event.location,
        c.STATUS to statusOf(event.status),
        c.DTSTART to start,
    )
    val exdates = if (recurring && exdateColumn) dateList(event.exdates, allDay, zone) else null
    if (recurring) {
        val duration = if (allDay) {
            val days = ChronoUnit.DAYS.between(LocalDate.parse(event.start.take(8), DAY), LocalDate.parse(event.end.take(8), DAY)).coerceAtLeast(1)
            "P${days}D"
        } else {
            "PT${(end - start) / 1000}S"
        }
        master[c.DTEND] = null
        master[c.DURATION] = duration
        master[c.TIMEZONE] = tzId
        master[c.ALL_DAY] = if (allDay) 1 else 0
        master[c.RRULE] = event.rrule?.let { ruleWithUtcUntil(it, allDay, zone) }
        master[c.RDATE] = dateList(event.rdates, allDay, zone)
        master[c.EXDATE] = exdates
    } else {
        master[c.DTEND] = end
        master[c.DURATION] = null
        master[c.TIMEZONE] = tzId
        master[c.ALL_DAY] = if (allDay) 1 else 0
        master[c.RRULE] = null
        master[c.RDATE] = null
        master[c.EXDATE] = null
    }
    master[c.HAS_ALARM] = if (event.alarms.isNotEmpty()) 1 else 0
    val rows = mutableListOf(EventRow(master, event.alarms))
    if (recurring) {
        val length = end - start
        val moved = event.overrides.map { it.recurrenceId }.toSet()
        for (o in event.overrides) {
            val instance = wireMillis(o.recurrenceId, allDay, zone) ?: continue
            val oStart = wireMillis(o.start, allDay, zone) ?: instance
            val oEnd = (wireMillis(o.end, allDay, zone) ?: (oStart + length)).coerceAtLeast(oStart)
            rows += overrideRow(event, o.recurrenceId, instance, oStart, oEnd, tzId, allDay, o.summary, o.description, o.location, o.status, o.alarms)
        }
        // EXDATE's fallback (QA L7): each exclusion a cancelled override row.
        if (!exdateColumn) {
            for (x in event.exdates.filter { it !in moved }) {
                val instance = wireMillis(x, allDay, zone) ?: continue
                rows += overrideRow(event, x, instance, instance, instance + length, tzId, allDay, event.summary, event.description, event.location, "cancelled", emptyList())
            }
        }
    }
    return SeriesRows(event.uid, event.rev, rows, unknownZone, floating)
}

private fun overrideRow(
    event: FeedEvent,
    recurrenceId: String,
    instance: Long,
    start: Long,
    end: Long,
    tzId: String,
    allDay: Boolean,
    summary: String,
    description: String,
    location: String,
    status: String,
    alarms: List<Int>,
): EventRow {
    val c = FeedColumns
    return EventRow(
        linkedMapOf(
            c.SYNC_ID to "${event.uid}#$recurrenceId",
            c.UID to event.uid,
            c.SERIES_REV to event.rev,
            c.ORIGINAL_SYNC_ID to event.uid,
            c.ORIGINAL_INSTANCE_TIME to instance,
            c.ORIGINAL_ALL_DAY to if (allDay) 1 else 0,
            c.TITLE to summary,
            c.DESCRIPTION to description,
            c.LOCATION to location,
            c.STATUS to statusOf(status),
            c.DTSTART to start,
            c.DTEND to end,
            c.TIMEZONE to tzId,
            c.ALL_DAY to if (allDay) 1 else 0,
            c.HAS_ALARM to if (alarms.isNotEmpty()) 1 else 0,
        ),
        alarms,
    )
}

/** A link's calendar: LOCAL, account [FEED_ACCOUNT], NAME `vibb-feed-<uid>` - the key the server computes too. */
const val FEED_ACCOUNT = "Vibb One"
const val FEED_NAME_PREFIX = "vibb-feed-"

/** The calendar's NAME for a link. */
fun feedCalendarName(uid: String) = "$FEED_NAME_PREFIX$uid"

/** A link's uid from its calendar's NAME, `null` for any other calendar. */
fun feedUidOf(name: String?): String? = name?.removePrefix(FEED_NAME_PREFIX)?.takeIf { it != name && Regex("[0-9a-f]{32}").matches(it) }

/** A link calendar's access level: `CAL_ACCESS_READ` - the kid-write rules (>= 500, not LOCAL) never pick it. */
const val FEED_ACCESS_LEVEL = 200

/** Calm colours for the link calendars (the key picks one), muted like Home's tones. */
private val FEED_COLOURS = intArrayOf(0xFF496C9C.toInt(), 0xFF347470.toInt(), 0xFF805B9A.toInt(), 0xFFA45166.toInt(), 0xFF6065A9.toInt(), 0xFF606B80.toInt())

fun feedColour(key: String): Int = FEED_COLOURS[Math.floorMod(key.hashCode(), FEED_COLOURS.size)]
