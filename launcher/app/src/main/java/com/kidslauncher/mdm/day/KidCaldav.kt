package com.kidslauncher.mdm.day

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Design 72b: the kid's own events handed to the server for the CalDAV calendar the parent marked, and the parent's
 * edits coming back (`docs/design/72b-caldav-write-back.md` at the monorepo root). The phone keeps writing in its own
 * "Min kalender"; at a sync it posts the whole set of the kid's coming events (`POST /api/devices/calendar/kid-events`)
 * and applies the hand-backs the answer carries, acknowledging each by its id. The app password stays on the server;
 * the phone knows the calendar only by its feed uid. Pure: the policy's object, D2.7's canonical form and hash, the
 * snapshot with its limits, the hand-backs as provider writes, the skip set. KidCaldavTest runs the shared contract
 * `calendar_kid_events.json` (byte-identical with `server/testdata/`).
 */

/** A launcher that hands its kid's events over reports this capability; without it the server sends `kid_caldav: null`. */
const val CALENDAR_KID_CALDAV_CAPABILITY = "calendar_kid_caldav_v1"

/** The set's limits (D1.3): a safety net, never a reason to delete. */
const val KID_SET_ROWS = 300
const val KID_SET_BYTES = 512_000
const val KID_CUT_UIDS = 2000
const val KID_ENDED = 300
const val KID_APPLIED = 300

/** An event that ended within this many days goes in `ended` (R-M1): its uid only, never deleted on the calendar. */
const val KID_ENDED_DAYS = 60L

/** D2.7's cuts, in code points (the server's `ics` caps). */
const val KID_SUMMARY_MAX = 200
const val KID_LOCATION_MAX = 200
const val KID_DESCRIPTION_MAX = 2000

/** D4.9's pattern: `vibb-` + the 8-hex phone tag + a lowercase UUID. The server refuses a set with any other. */
val KID_UID = Regex("vibb-[0-9a-f]{8}-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")

/** The policy's `calendar.kid_caldav` (D1.6): the marked calendar's feed uid, the last set the server took (`null` =
 * send it all), the hand-backs waiting, the writes waiting. */
data class KidCaldavPolicy(val id: String, val accepted: String?, val back: Int, val pending: Int)

private val FEED_UID = Regex("[0-9a-f]{32}")

/** The marked calendar from the policy's `calendar` object; missing, `null` or malformed = off (nothing is sent). */
fun kidCaldavPolicy(calendar: JsonElement?): KidCaldavPolicy? {
    val obj = (calendar as? JsonObject)?.get("kid_caldav") as? JsonObject ?: return null
    val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { FEED_UID.matches(it) } ?: return null
    val accepted = when (val a = obj["accepted"]) {
        null, JsonNull -> null
        is JsonPrimitive -> if (a.isString) a.content else return null
        else -> return null
    }
    val back = (obj["back"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
    val pending = (obj["pending"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it >= 0 } ?: return null
    return KidCaldavPolicy(id, accepted, back, pending)
}

// ---- the canonical form (D2.7) ----------------------------------------------------------------------------------

/** `\r\n` and `\r` as `\n`, C0 controls other than LF and TAB dropped, then cut by code point - nothing else changed. */
fun canonicalText(raw: String?, max: Int): String {
    val s = (raw ?: "").replace("\r\n", "\n").replace('\r', '\n')
    val out = StringBuilder()
    var kept = 0
    var i = 0
    while (i < s.length && kept < max) {
        val cp = s.codePointAt(i)
        i += Character.charCount(cp)
        if (cp < 0x20 && cp != '\n'.code && cp != '\t'.code) continue
        out.appendCodePoint(cp)
        kept++
    }
    return out.toString()
}

/** One version of a kid event as the server hashes, writes and hands back: UTC `20261103T140000Z`, or an all-day
 * `20261103` with an exclusive end. */
data class KidVersion(
    val summary: String,
    val location: String,
    val description: String,
    val start: String,
    val end: String,
    val allDay: Boolean,
) {
    override fun toString() = "KidVersion(allDay=$allDay)"
}

private val BASIC_UTC: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC)
private val BASIC_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
private const val DAY_MS = 86_400_000L

fun utcBasic(ms: Long): String = BASIC_UTC.format(Instant.ofEpochMilli(ms))

private fun utcDate(ms: Long): LocalDate = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()

/** An RFC 5545 duration (`PT30M`, `P1D`, `-PT5M`) in ms; `null` when it doesn't read. */
fun kidDurationMs(value: String?): Long? {
    val m = Regex("([+-])?P(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+)S)?)?").matchEntire(value?.trim()?.uppercase() ?: return null)
        ?: return null
    val (sign, w, d, h, mi, s) = m.destructured
    if (listOf(w, d, h, mi, s).all { it.isEmpty() }) return null
    val n = { v: String -> v.toLongOrNull() ?: 0L }
    val seconds = n(w) * 604_800 + n(d) * 86_400 + n(h) * 3600 + n(mi) * 60 + n(s)
    return (if (sign == "-") -seconds else seconds) * 1000
}

/** A provider row's canonical version: a missing end a zero length (all-day a day), a DURATION resolved to an end, an
 * all-day row on its UTC dates. */
fun kidVersion(summary: String?, location: String?, description: String?, startMs: Long, endMs: Long?, durationMs: Long?, allDay: Boolean): KidVersion {
    val texts = Triple(canonicalText(summary, KID_SUMMARY_MAX), canonicalText(location, KID_LOCATION_MAX), canonicalText(description, KID_DESCRIPTION_MAX))
    if (allDay) {
        val start = utcDate(startMs)
        var end = endMs?.let { utcDate(it) } ?: durationMs?.let { start.plusDays((it / DAY_MS).coerceAtLeast(1)) }
        if (end == null || !end.isAfter(start)) end = start.plusDays(1)
        return KidVersion(texts.first, texts.second, texts.third, BASIC_DATE.format(start), BASIC_DATE.format(end), true)
    }
    var end = endMs ?: durationMs?.let { startMs + it } ?: startMs
    if (end < startMs) end = startMs
    return KidVersion(texts.first, texts.second, texts.third, utcBasic(startMs), utcBasic(end), false)
}

/** A JSON string as the server's serde_json writes it: `"` and `\` escaped, the short forms for \b \f \n \r \t, any
 * other control as a four-digit unicode escape, everything else as it is. */
fun jsonString(value: String): String {
    val out = StringBuilder(value.length + 2).append('"')
    for (c in value) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c.code < 0x20 -> out.append("\\u00").append("%02x".format(c.code))
            else -> out.append(c)
        }
    }
    return out.append('"').toString()
}

/** The version's canonical JSON `{summary, location, description, start, end, all_day}`. */
fun canonicalJson(v: KidVersion): String =
    "{\"summary\":${jsonString(v.summary)},\"location\":${jsonString(v.location)},\"description\":${jsonString(v.description)}," +
        "\"start\":${jsonString(v.start)},\"end\":${jsonString(v.end)},\"all_day\":${v.allDay}}"

internal fun hex16(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).take(8).joinToString("") { "%02x".format(it.toInt() and 0xff) }

/** 16 hex of SHA-256 over the canonical JSON (the zone and the bytes never count). */
fun kidHash(v: KidVersion): String = hex16(canonicalJson(v))

/** A hand-back's id (R-M2): SHA-256 over the uid and the version's hash (`delete` for a delete). */
fun kidBackId(uid: String, versionHash: String): String = hex16(uid + versionHash)

// ---- the set (D1.3) ---------------------------------------------------------------------------------------------

/** One row of "Min kalender" as the provider holds it (DELETED rows included: they are left out here). */
data class KidRow(
    val id: Long,
    val uid: String?,
    val title: String?,
    val location: String?,
    val description: String?,
    val dtstart: Long,
    val dtend: Long?,
    val duration: String?,
    val allDay: Boolean,
    val tz: String?,
    val rrule: String?,
    val rdate: String?,
    val deleted: Boolean,
    val customApp: String?,
) {
    override fun toString() = "KidRow($id)"

    /** The end: DTEND, else DTSTART + DURATION, else DTSTART. */
    val endMs: Long get() = dtend ?: kidDurationMs(duration)?.let { dtstart + it } ?: dtstart

    fun version(): KidVersion = kidVersion(title, location, description, dtstart, dtend, kidDurationMs(duration), allDay)
}

/** One event of the set on the wire. */
data class KidSetEvent(val uid: String, val version: KidVersion, val tz: String?) {
    override fun toString() = "KidSetEvent"

    /** `{uid, summary, location, description, start, end, all_day, tz}` - the bytes the limits count and the rev hashes. */
    fun json(): String =
        "{\"uid\":${jsonString(uid)},\"summary\":${jsonString(version.summary)},\"location\":${jsonString(version.location)}," +
            "\"description\":${jsonString(version.description)},\"start\":${jsonString(version.start)},\"end\":${jsonString(version.end)}," +
            "\"all_day\":${version.allDay},\"tz\":${tz?.let { jsonString(it) } ?: "null"}}"
}

/** The POST's set (D1.3): the rows sent, what the limits left out, the ended uids, the acknowledgements. */
data class KidSnapshot(
    val rev: String,
    val from: String,
    val cut: Int,
    val cutFrom: String?,
    /** `null` when more than [KID_CUT_UIDS] were left out: the server then deletes nothing (R-L3). */
    val cutUids: List<String>?,
    val ended: List<String>,
    val applied: List<String>,
    val events: List<KidSetEvent>,
) {
    override fun toString() = "KidSnapshot(${events.size} events, cut=$cut)"

    val uids: Set<String> get() = events.mapTo(LinkedHashSet()) { it.uid }

    /** The body, in the server's key order. */
    fun body(calendar: String): String {
        val list = { items: List<String> -> items.joinToString(",", "[", "]") { jsonString(it) } }
        return "{\"calendar\":${jsonString(calendar)},\"rev\":${jsonString(rev)},\"from\":${jsonString(from)},\"cut\":$cut," +
            "\"cut_from\":${cutFrom?.let { jsonString(it) } ?: "null"},\"cut_uids\":${cutUids?.let(list) ?: "null"}," +
            "\"ended\":${list(ended)},\"applied\":${list(applied)},\"events\":${events.joinToString(",", "[", "]") { it.json() }}}"
    }
}

/** A row of the kid's (D1.3): this phone's ([ownedByVibb] - its tag, or a `vibb-` UID with our package), the pattern,
 * live, not a series. */
fun kidSetRow(row: KidRow, ownPackage: String, phoneTag: String): Boolean =
    !row.deleted && row.rrule.isNullOrEmpty() && row.rdate.isNullOrEmpty() && row.uid != null && KID_UID.matches(row.uid) &&
        ownedByVibb(row.uid, row.customApp, ownPackage, phoneTag)

/** The set from "Min kalender"'s rows (D1.3): the coming ones (`end > now`, no forward horizon), at most [maxRows]
 * and [maxBytes] - the farthest from now left out first, `cut_from` the earliest start of any left out -, the uids of
 * the rows that ended within [KID_ENDED_DAYS], the rev over the rows sorted by uid. */
fun kidSnapshot(
    rows: List<KidRow>,
    ownPackage: String,
    phoneTag: String,
    nowMs: Long,
    applied: List<String>,
    maxRows: Int = KID_SET_ROWS,
    maxBytes: Int = KID_SET_BYTES,
): KidSnapshot {
    // Two live rows with one UID (the provider doesn't keep them unique) would make every set a 400: the first by id.
    val mine = rows.filter { kidSetRow(it, ownPackage, phoneTag) }.sortedBy { it.id }.distinctBy { it.uid }
    val coming = mine.filter { it.endMs > nowMs }.sortedWith(compareBy<KidRow>({ it.dtstart }, { it.uid }))
    val kept = ArrayList<KidSetEvent>()
    val left = ArrayList<Pair<KidRow, KidSetEvent>>()
    var used = 0
    for (row in coming) {
        val event = KidSetEvent(row.uid!!, row.version(), row.tz)
        val bytes = event.json().toByteArray(Charsets.UTF_8).size + 1
        if (kept.size < maxRows && used + bytes <= maxBytes) {
            kept += event
            used += bytes
        } else {
            left += row to event
        }
    }
    kept.sortBy { it.uid }
    val since = nowMs - KID_ENDED_DAYS * DAY_MS
    val ended = mine.filter { it.endMs in (since + 1)..nowMs }
        .sortedByDescending { it.endMs }
        .take(KID_ENDED)
        .map { it.uid!! }
        .sorted()
    val cutUids = left.map { it.second.uid }.sorted().takeIf { it.size <= KID_CUT_UIDS }
    return KidSnapshot(
        rev = hex16(kept.joinToString(",", "[", "]") { it.json() }),
        from = utcBasic(nowMs),
        cut = left.size,
        cutFrom = left.minByOrNull { it.first.dtstart }?.second?.version?.start,
        cutUids = cutUids,
        ended = ended,
        applied = applied.distinct().take(KID_APPLIED),
        events = kept,
    )
}

// ---- the answer and the hand-backs (D2.3) -----------------------------------------------------------------------

/** One hand-back: the parent's version of the uid (an upsert), or its delete. */
data class KidBack(val id: String, val uid: String, val version: KidVersion?) {
    val delete: Boolean get() = version == null

    override fun toString() = "KidBack($id, delete=$delete)"
}

/** The 200's `{accepted, pending, back}`. */
data class KidAnswer(val accepted: String?, val pending: Int, val back: List<KidBack>)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

private val BACK_ID = Regex("[0-9a-f]{16}")

/** The answer, or `null` when it doesn't read. A hand-back that doesn't read is dropped (never acknowledged: the
 * server sends it again). */
fun kidAnswer(element: JsonElement?): KidAnswer? {
    val obj = element as? JsonObject ?: return null
    val pending = (obj["pending"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
    val accepted = obj.str("accepted")
    val back = (obj["back"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { row ->
        val r = row as? JsonObject ?: return@mapNotNull null
        val id = r.str("id")?.takeIf { BACK_ID.matches(it) } ?: return@mapNotNull null
        val uid = r.str("uid")?.takeIf { KID_UID.matches(it) } ?: return@mapNotNull null
        when (r.str("op")) {
            "delete" -> KidBack(id, uid, null)
            "upsert" -> {
                val allDay = (r["all_day"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: return@mapNotNull null
                val start = r.str("start") ?: return@mapNotNull null
                val end = r.str("end") ?: return@mapNotNull null
                if (kidTimeMs(start, allDay) == null || kidTimeMs(end, allDay) == null) return@mapNotNull null
                KidBack(
                    id,
                    uid,
                    KidVersion(r.str("summary") ?: return@mapNotNull null, r.str("location") ?: "", r.str("description") ?: "", start, end, allDay),
                )
            }
            else -> null
        }
    }
    return KidAnswer(accepted, pending, back)
}

/** A wire time in ms: an all-day date at UTC midnight, a UTC instant; `null` when it doesn't read. */
fun kidTimeMs(value: String, allDay: Boolean): Long? = try {
    if (allDay) {
        if (value.length != 8) null else LocalDate.parse(value, BASIC_DATE).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    } else {
        if (value.length != 16) null else Instant.from(BASIC_UTC.parse(value)).toEpochMilli()
    }
} catch (e: java.time.DateTimeException) {
    null
}

/** What a hand-back writes into "Min kalender": the parent's fields and times (design 69 §2.4: all-day at UTC
 * midnights in `UTC`; a timed row keeps its zone, a new one gets the phone's). */
data class KidProviderValues(
    val title: String,
    val location: String,
    val description: String,
    val dtstart: Long,
    val dtend: Long,
    val allDay: Boolean,
    val tz: String,
) {
    override fun toString() = "KidProviderValues(allDay=$allDay)"
}

sealed interface KidBackOp {
    val backId: String

    /** The live row with the uid takes the parent's version. */
    data class Update(override val backId: String, val eventId: Long, val values: KidProviderValues) : KidBackOp

    /** None live: the kid deleted it while the parent edited - inserted again with the same UID (the calendar wins). */
    data class Insert(override val backId: String, val uid: String, val values: KidProviderValues) : KidBackOp

    data class Delete(override val backId: String, val eventId: Long) : KidBackOp

    /** Nothing to do (a delete of a row already gone, a row that isn't the kid's): acknowledged as it is. */
    data class Ignore(override val backId: String) : KidBackOp
}

/** The hand-backs as provider writes (D2.3): by the live row with the UID - `DELETED=1` rows are ignored everywhere,
 * and a UID [ownedByVibb] refuses is never touched. */
fun backOps(back: List<KidBack>, rows: List<KidRow>, ownPackage: String, phoneTag: String, phoneZone: String): List<KidBackOp> = back.map { b ->
    // The row the snapshot sends for the uid (revision RN2): this phone's rows first, the lowest id.
    val live = rows.filter { !it.deleted && it.uid == b.uid }
    val row = live.filter { kidSetRow(it, ownPackage, phoneTag) }.minByOrNull { it.id } ?: live.minByOrNull { it.id }
    val ours = row != null && ownedByVibb(row.uid, row.customApp, ownPackage, phoneTag)
    val version = b.version
    when {
        row != null && !ours -> KidBackOp.Ignore(b.id)
        version == null -> if (row != null) KidBackOp.Delete(b.id, row.id) else KidBackOp.Ignore(b.id)
        else -> {
            val values = KidProviderValues(
                title = version.summary,
                location = version.location,
                description = version.description,
                dtstart = kidTimeMs(version.start, version.allDay)!!,
                dtend = kidTimeMs(version.end, version.allDay)!!,
                allDay = version.allDay,
                // A row that was all-day (UTC) gets the phone's zone for a timed version (launcher QA L5).
                tz = if (version.allDay) "UTC" else row?.takeIf { !it.allDay }?.tz ?: phoneZone,
            )
            if (row != null) KidBackOp.Update(b.id, row.id, values) else KidBackOp.Insert(b.id, b.uid, values)
        }
    }
}

// ---- the skip set (D3.2) ----------------------------------------------------------------------------------------

/** What the phone remembers of its posts: the last set's rev and uids, and every uid posted since the server last
 * showed that rev accepted with nothing pending. */
data class PostedUnion(
    val rev: String? = null,
    val uids: Set<String> = emptySet(),
    val union: Set<String> = emptySet(),
    /** The last posted set's cut, `cut|cut_from|cut_uids` (code QA N5): a change there is posted too. */
    val cut: String? = null,
) {
    override fun toString() = "PostedUnion(${union.size})"
}

fun cutKey(snapshot: KidSnapshot): String =
    "${snapshot.cut}|${snapshot.cutFrom.orEmpty()}|${snapshot.cutUids?.let { hex16(it.joinToString(",")) } ?: "null"}"

fun afterPost(state: PostedUnion, snapshot: KidSnapshot): PostedUnion =
    PostedUnion(snapshot.rev, snapshot.uids, state.union + snapshot.uids, cutKey(snapshot))

/** A hand-back delete applied (or left, deliberately): the event is the parent's or gone, and the server never deletes
 * it again - it leaves the skip set, so a released series shows from the feed at once (D3.2, launcher QA M2). */
fun afterBackDeletes(state: PostedUnion, uids: Set<String>): PostedUnion =
    state.copy(uids = state.uids - uids, union = state.union - uids)

/** Whether the set goes to the server (D1.2): it differs from the last one the server took or from the last one
 * posted (a held set undone, code QA L3), its cut changed (N5), hand-backs wait, or acknowledgements do. */
fun shouldPost(snapshot: KidSnapshot, policy: KidCaldavPolicy, applied: List<String>, posted: PostedUnion): Boolean =
    snapshot.rev != policy.accepted ||
        policy.back > 0 ||
        applied.isNotEmpty() ||
        (posted.rev != null && posted.rev != snapshot.rev) ||
        (posted.cut != null && posted.cut != cutKey(snapshot))

/** The acknowledgements kept after a 200: those not sent this time, and the ones just applied (at most
 * [KID_APPLIED]). */
fun nextApplied(stored: List<String>, sent: List<String>, acked: List<String>): List<String> =
    ((stored - sent.toSet()) + acked).distinct().takeLast(KID_APPLIED)

/** A 200 or a policy that shows the last posted rev accepted and `pending` 0: the union is the current set's uids. */
fun afterAnswer(state: PostedUnion, accepted: String?, pending: Int): PostedUnion =
    if (state.rev != null && accepted == state.rev && pending == 0) state.copy(union = state.uids) else state

/** The feeds' skip set: the live rows of "Min kalender" and the posted union - an event in "Min kalender" never shows
 * twice, and one the kid just deleted doesn't come back from a feed that still holds it. */
fun skipSet(liveUids: Collection<String>, union: Collection<String>): Set<String> = (liveUids + union).toSet()

/** The skipped uids a feed calendar keeps (`CAL_SYNC6`, at most [KID_SKIP_KEPT]). */
const val KID_SKIP_KEPT = 300

fun skipKept(skip: Set<String>): List<String> = skip.sorted().take(KID_SKIP_KEPT)

/** `CAL_SYNC6` read: a JSON array of uids; anything else is none. */
fun skippedOf(text: String?): Set<String> {
    if (text == null) return emptySet()
    return try {
        (com.kidslauncher.mdm.server.ServerJson.parseToJsonElement(text) as? kotlinx.serialization.json.JsonArray)
            .orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }
}

fun skippedText(uids: List<String>): String = uids.joinToString(",", "[", "]") { jsonString(it) }

/** What a feed calendar's pass does with the skip set (D3.2): the uids that joined it lose their rows locally (no
 * GET); one that left it makes the set fetched again without `If-None-Match` (QA N3). */
data class SkipChange(val joined: Set<String>, val left: Set<String>)

fun skipChange(stored: Set<String>, now: Set<String>): SkipChange = SkipChange(joined = now - stored, left = stored - now)

/** The skip set's fingerprint a feed calendar keeps beside `CAL_SYNC6` (`CAL_SYNC7`, revision R-L1): unchanged since
 * that calendar's last pass, its rows aren't read again for newly skipped uids. */
fun skipHash(skip: Set<String>): String = hex16(skip.sorted().joinToString(","))

/** Whether a feed calendar's rows must be looked through for the uids that joined the skip set. */
fun skipPassDue(change: SkipChange, storedHash: String?, skip: Set<String>): Boolean =
    change.joined.isNotEmpty() && storedHash != skipHash(skip)

/** A feed's set without the kid's own events (D3.2): a series or a lone override (`uid#…`) of a skipped uid out. */
fun skipFeedEvents(events: List<FeedEvent>, skip: Set<String>): List<FeedEvent> =
    events.filter { it.uid.substringBefore('#') !in skip }

/** What a feed calendar keeps in `CAL_SYNC6` after a pass (code QA L2): only the uids it actually skipped - those in
 * its set - so the list stays small and a uid leaving it means something for this calendar. */
fun feedSkipped(events: List<FeedEvent>, skip: Set<String>): List<String> =
    skipKept(events.map { it.uid.substringBefore('#') }.filter { it in skip }.toSet())
