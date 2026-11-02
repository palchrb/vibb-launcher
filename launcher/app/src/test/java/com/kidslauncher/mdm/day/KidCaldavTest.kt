package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Design 72b §8: the shared contract `calendar_kid_events.json` (byte-identical with `server/testdata/`, whose
 * `calendar_kid_events::tests::the_contract` posts `snapshot` against its fake CalDAV and produces `answers` and
 * `back_provider`): "Min kalender"'s rows encode to exactly `snapshot` (and, past the limits, `cut_variant`), every
 * `canonical` row hashes as the server's does, the policy and the answers decode, each hand-back maps to exactly the
 * provider values the server expects, and the ids follow R-M2's rule.
 */
class KidCaldavTest {
    private val text = javaClass.classLoader!!.getResource("calendar_kid_events.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(text).jsonObject
    private val own = contract.getValue("own_package").jsonPrimitive.content
    private val tag = contract.getValue("phone_tag").jsonPrimitive.content
    private val now = kidTimeMs(contract.getValue("now").jsonPrimitive.content, false)!!
    private val calendar = contract.getValue("policy").jsonObject.getValue("id").jsonPrimitive.content

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull && !it.isString }?.long

    private val rows: List<KidRow> = contract.getValue("provider").jsonArray.map {
        val r = it.jsonObject
        KidRow(
            id = r.getValue("id").jsonPrimitive.long,
            uid = r.str("uid"),
            title = r.str("title"),
            location = r.str("location"),
            description = r.str("description"),
            dtstart = r.getValue("dtstart").jsonPrimitive.long,
            dtend = r.long("dtend"),
            duration = r.str("duration"),
            allDay = r.getValue("all_day").jsonPrimitive.boolean,
            tz = r.str("tz"),
            rrule = r.str("rrule"),
            rdate = r.str("rdate"),
            deleted = r.getValue("deleted").jsonPrimitive.boolean,
            customApp = r.str("package"),
        )
    }

    private fun parse(body: String): JsonElement = ServerJson.parseToJsonElement(body)

    @Test
    fun `the provider encodes to exactly the snapshot`() {
        val snapshot = kidSnapshot(rows, own, tag, now, emptyList())
        assertEquals(contract.getValue("snapshot"), parse(snapshot.body(calendar)))
        // The rev is stable across the rows' order.
        assertEquals(snapshot.rev, kidSnapshot(rows.reversed(), own, tag, now, emptyList()).rev)
        // Left out: a sibling's event, a UID outside the pattern, a series, a deleted row; ended within 60 days only by
        // its uid.
        val sent = snapshot.events.map { it.uid }.toSet()
        for (id in listOf(8L, 9L, 10L, 11L, 12L, 13L)) assertTrue("row $id", rows.first { it.id == id }.uid !in sent)
        assertEquals(listOf(rows.first { it.id == 11L }.uid), snapshot.ended)
        // The acknowledgements go as kept.
        assertEquals(listOf("456a2d79dd8f4d23"), kidSnapshot(rows, own, tag, now, listOf("456a2d79dd8f4d23")).applied)
    }

    @Test
    fun `past the limits the farthest are cut and cut_from is the earliest left out`() {
        val variant = contract.getValue("cut_variant").jsonObject
        val snapshot = kidSnapshot(
            rows, own, tag, now, emptyList(),
            maxRows = variant.getValue("rows").jsonPrimitive.int,
            maxBytes = variant.getValue("bytes").jsonPrimitive.int,
        )
        assertEquals(variant.getValue("snapshot"), parse(snapshot.body(calendar)))
        // Kino, the nearest left out, was dropped by the bytes: cut_from is its start, not a farther one's.
        assertEquals("20261103T160000Z", snapshot.cutFrom)
        // Past 2000 uids the list is null (R-L3): the server deletes nothing.
        val many = (0 until 2400).map { n ->
            KidRow(1000L + n, "vibb-$tag-0b6f2c1e-3d4a-4b5c-8d9e-%012d".format(n), "x", "", "", now + 3_600_000L + n, now + 7_200_000L + n, null, false, "UTC", null, null, false, own)
        }
        val cut = kidSnapshot(many, own, tag, now, emptyList())
        assertEquals(2400 - KID_SET_ROWS, cut.cut)
        assertNull(cut.cutUids)
        assertTrue(parse(cut.body(calendar)).jsonObject.getValue("cut_uids") is JsonNull)
    }

    private fun ms(value: String, allDay: Boolean, tz: String?): Long = when {
        allDay -> LocalDate.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd")).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        value.endsWith("Z") -> kidTimeMs(value, false)!!
        // A parent's floating time, in the zone the phone sent for the event.
        else -> LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss")).atZone(ZoneId.of(tz ?: "UTC")).toInstant().toEpochMilli()
    }

    @Test
    fun `every canonical row hashes as the server's`() {
        for (row in contract.getValue("canonical").jsonArray.map { it.jsonObject }) {
            val input = row.getValue("input").jsonObject
            val allDay = input.getValue("all_day").jsonPrimitive.boolean
            val tz = input.str("tz")
            val v = kidVersion(
                input.str("summary"), input.str("location"), input.str("description"),
                ms(input.str("start")!!, allDay, tz), input.str("end")?.let { ms(it, allDay, tz) },
                kidDurationMs(input.str("duration")), allDay,
            )
            val name = row.getValue("name").jsonPrimitive.content
            assertEquals(name, row.getValue("canonical"), parse(canonicalJson(v)))
            assertEquals(name, row.getValue("hash").jsonPrimitive.content, kidHash(v))
        }
    }

    @Test
    fun `the policy decodes, a malformed one is off`() {
        assertEquals(KidCaldavPolicy(calendar, null, 0, 0), kidCaldavPolicy(JsonObject(mapOf("kid_caldav" to contract.getValue("policy")))))
        val waiting = contract.getValue("policy_waiting").jsonObject
        assertEquals(
            KidCaldavPolicy(calendar, waiting.str("accepted"), 3, 0),
            kidCaldavPolicy(JsonObject(mapOf("kid_caldav" to waiting))),
        )
        for (odd in listOf("null", "5", "{}", """{"id": "x", "accepted": null, "back": 0, "pending": 0}""", """{"id": "$calendar", "back": -1, "pending": 0}""")) {
            assertNull(odd, kidCaldavPolicy(parse("""{"kid_caldav": $odd}""")))
        }
        assertNull(kidCaldavPolicy(null))
        assertEquals(KidCaldavPolicy(calendar, null, 0, 0), calendarPolicy(JsonObject(mapOf("kid_caldav" to contract.getValue("policy")))).kidCaldav)
    }

    private fun answer(name: String): JsonObject =
        contract.getValue("answers").jsonArray.map { it.jsonObject }.first { it.getValue("name").jsonPrimitive.content == name }

    @Test
    fun `the answers decode and their ids follow the rule`() {
        for (a in contract.getValue("answers").jsonArray.map { it.jsonObject }) {
            val status = a.getValue("status").jsonPrimitive.int
            val read = kidAnswer(a.getValue("body"))
            if (status != 200) {
                assertNull(read)
                continue
            }
            assertNotNull(read)
            val body = a.getValue("body").jsonObject
            assertEquals(body.getValue("back").jsonArray.size, read!!.back.size)
            for (b in read.back) {
                assertEquals(kidBackId(b.uid, b.version?.let { kidHash(it) } ?: "delete"), b.id)
            }
            assertEquals(body.str("accepted"), read.accepted)
        }
        // A held set leaves the old rev accepted; an acknowledged hand-back is gone from the next answer.
        assertEquals(answer("stale_ack").getValue("body").jsonObject.str("accepted"), kidAnswer(answer("held").getValue("body"))!!.accepted)
        assertEquals(3, kidAnswer(answer("accepted").getValue("body"))!!.back.size)
        assertEquals(2, kidAnswer(answer("acknowledged").getValue("body"))!!.back.size)
    }

    private fun json(op: KidBackOp): JsonObject {
        fun values(v: KidProviderValues) = JsonObject(
            mapOf(
                "title" to JsonPrimitive(v.title), "location" to JsonPrimitive(v.location), "description" to JsonPrimitive(v.description),
                "dtstart" to JsonPrimitive(v.dtstart), "dtend" to JsonPrimitive(v.dtend), "all_day" to JsonPrimitive(v.allDay), "tz" to JsonPrimitive(v.tz),
            ),
        )
        return when (op) {
            is KidBackOp.Update -> JsonObject(mapOf("id" to JsonPrimitive(op.backId), "action" to JsonPrimitive("update"), "event" to JsonPrimitive(op.eventId), "values" to values(op.values)))
            is KidBackOp.Insert -> JsonObject(mapOf("id" to JsonPrimitive(op.backId), "action" to JsonPrimitive("insert"), "event" to JsonNull, "values" to values(op.values)))
            is KidBackOp.Delete -> JsonObject(mapOf("id" to JsonPrimitive(op.backId), "action" to JsonPrimitive("delete"), "event" to JsonPrimitive(op.eventId)))
            is KidBackOp.Ignore -> JsonObject(mapOf("id" to JsonPrimitive(op.backId), "action" to JsonPrimitive("ignore"), "event" to JsonNull))
        }
    }

    @Test
    fun `each hand-back maps to exactly the provider values`() {
        val back = kidAnswer(answer("accepted").getValue("body"))!!.back
        val ops = backOps(back, rows, own, tag, "Europe/Oslo")
        assertEquals(contract.getValue("back_provider"), JsonArray(ops.map { json(it) }))
    }

    @Test
    fun `backOps - update, insert with the UID, delete, ignore`() {
        val kino = rows.first { it.id == 1L }
        val upsert = KidBack("0123456789abcdef", kino.uid!!, KidVersion("Kino flyttet", "", "", "20261103T170000Z", "20261103T190000Z", false))
        val allDay = KidBack("1123456789abcdef", kino.uid!!, KidVersion("Hele dagen", "", "", "20261104", "20261105", true))
        val gone = rows.first { it.id == 12L }.uid!!
        val deletedRow = KidBack("2123456789abcdef", gone, KidVersion("Tilbake", "", "", "20261107T100000Z", "20261107T110000Z", false))
        val sibling = KidBack("3123456789abcdef", rows.first { it.id == 8L }.uid!!, null)
        val missing = KidBack("4123456789abcdef", "vibb-$tag-0b6f2c1e-3d4a-4b5c-8d9e-0f1a2b3c4d99", null)
        val ops = backOps(listOf(upsert, allDay, deletedRow, sibling, missing, KidBack("5123456789abcdef", kino.uid!!, null)), rows, own, tag, "Europe/Berlin")
        val update = ops[0] as KidBackOp.Update
        assertEquals(1L, update.eventId)
        assertEquals("Europe/Oslo", update.values.tz)
        // All-day at UTC midnights in UTC (design 69 §2.4).
        val day = ops[1] as KidBackOp.Update
        assertEquals(LocalDate.of(2026, 11, 4).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli(), day.values.dtstart)
        assertEquals("UTC", day.values.tz)
        // The kid deleted it while the parent edited (only a DELETED=1 row left): inserted with the same UID.
        val insert = ops[2] as KidBackOp.Insert
        assertEquals(gone, insert.uid)
        assertEquals("Europe/Berlin", insert.values.tz)
        // A sibling's event (not this phone's) is never touched; a delete of a row already gone is just acknowledged.
        assertEquals(KidBackOp.Ignore("3123456789abcdef"), ops[3])
        assertEquals(KidBackOp.Ignore("4123456789abcdef"), ops[4])
        assertEquals(KidBackOp.Delete("5123456789abcdef", 1L), ops[5])
        // A row that doesn't read is dropped, never acknowledged.
        assertEquals(0, kidAnswer(parse("""{"accepted": null, "pending": 0, "back": [{"id": "x", "uid": "y", "op": "delete"}, {"id": "0123456789abcdef", "uid": "${kino.uid}", "op": "upsert"}]}"""))!!.back.size)
        assertNull(kidAnswer(parse("""{"error": "not_marked"}""")))
    }

    @Test
    fun `the skip set and the posted union`() {
        val snapshot = kidSnapshot(rows, own, tag, now, emptyList())
        var state = afterPost(PostedUnion(), snapshot)
        assertEquals(snapshot.uids, state.union)
        // The kid deletes Kino and posts again: Kino stays skipped until the server shows the set taken, pending 0.
        val kino = rows.first { it.id == 1L }
        val without = kidSnapshot(rows.filter { it.id != 1L }, own, tag, now, emptyList())
        state = afterPost(state, without)
        assertTrue(kino.uid in state.union)
        assertTrue(kino.uid in afterAnswer(state, without.rev, 1).union)
        assertTrue(kino.uid in afterAnswer(state, snapshot.rev, 0).union)
        assertEquals(without.uids, afterAnswer(state, without.rev, 0).union)
        assertEquals(setOf("a", "b"), skipSet(listOf("a"), listOf("b", "a")))
        // CAL_SYNC6: a uid that joins is deleted locally, one that leaves fetches the set again.
        assertEquals(SkipChange(setOf("c"), setOf("a")), skipChange(setOf("a", "b"), setOf("b", "c")))
        assertEquals(setOf("x", "y"), skippedOf(skippedText(listOf("x", "y"))))
        assertEquals(emptySet<String>(), skippedOf("not json"))
    }

    @Test
    fun `canonical text is cut by code point and keeps the rest`() {
        assertEquals("a\nb\tc  d", canonicalText("a\r\nb\tc\u0007  d", 100))
        assertEquals("x\n\ny", canonicalText("x\r\ry", 100))
        assertEquals(2, canonicalText("👨‍👩", 2).codePointCount(0, canonicalText("👨‍👩", 2).length))
        assertEquals(30 * 60_000L, kidDurationMs("PT30M"))
        assertEquals(86_400_000L, kidDurationMs("P1D"))
        assertNull(kidDurationMs("P"))
        assertEquals("\"a\\\"b\\\\c\\n\\u0001\"", jsonString("a\"b\\c\n\u0001"))
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        val serverCopy = File("../../server/testdata/calendar_kid_events.json")
        assertTrue(serverCopy.exists())
        assertEquals(serverCopy.readText(), text)
    }
}
