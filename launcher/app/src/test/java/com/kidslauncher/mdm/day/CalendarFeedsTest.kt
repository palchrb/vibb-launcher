package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CalendarFeedReportRow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * Design 72 §3: the shared contract `calendar_feeds.json` (byte-identical with `server/testdata/`, whose
 * `calendar_feeds::tests::the_contract` produces `feed`, `keys` and the uids and revs): the document decodes, maps to
 * exactly `provider` in the contract's phone zone, the calendar key is the server's, the policy's row and the report's
 * row read and encode as the contract has them.
 */
class CalendarFeedsTest {
    private val text = javaClass.classLoader!!.getResource("calendar_feeds.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(text).jsonObject

    private fun value(v: Any?): JsonElement = when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        else -> error("an unexpected column type: ${v.javaClass}")
    }

    private fun json(row: EventRow): JsonObject =
        JsonObject(row.values.mapValues { (_, v) -> value(v) } + ("reminders" to JsonArray(row.reminders.map { JsonPrimitive(it) })))

    @Test
    fun `the feed maps to exactly the provider rows`() {
        val doc = feedDocument(contract.getValue("feed").toString())
        assertNotNull(doc)
        val zone = ZoneId.of(contract.getValue("zone").jsonPrimitive.content)
        val rows = doc!!.events.map { seriesRows(it, zone)!! }.flatMap { it.rows }.map { json(it) }
        assertEquals(contract.getValue("provider"), JsonArray(rows))
        assertEquals(1, doc.v)
        assertEquals(contract.getValue("policy").jsonObject.getValue("feeds").jsonArray[0].jsonObject.getValue("rev").jsonPrimitive.content, doc.rev)
    }

    @Test
    fun `the calendar key is the server's`() {
        for ((uid, key) in contract.getValue("keys").jsonObject) {
            assertEquals(key.jsonPrimitive.content, calendarKey("LOCAL", FEED_ACCOUNT, feedCalendarName(uid)))
            assertEquals(uid, feedUidOf(feedCalendarName(uid)))
        }
        assertEquals(null, feedUidOf("vibb-kid-calendar"))
        assertEquals(null, feedUidOf("vibb-feed-NOT-HEX"))
    }

    @Test
    fun `the policy's row and the report's row are the contract's`() {
        val policy = calendarPolicy(ServerJson.parseToJsonElement("""{"feeds": ${contract.getValue("policy").jsonObject.getValue("feeds")}}"""))
        val row = contract.getValue("policy").jsonObject.getValue("feeds").jsonArray[0].jsonObject
        assertEquals(
            listOf(FeedRef(row.getValue("id").jsonPrimitive.content, "Skolen", row.getValue("rev").jsonPrimitive.content)),
            policy.feeds,
        )
        assertTrue(policy.feedsComplete)
        val report = contract.getValue("report").jsonObject.getValue("feeds")
        val r = report.jsonArray[0].jsonObject
        val encoded = ServerJson.encodeToJsonElement(
            ListSerializer(CalendarFeedReportRow.serializer()),
            listOf(CalendarFeedReportRow(r.getValue("id").jsonPrimitive.content, r.getValue("applied_rev").jsonPrimitive.content, 0, 0)),
        )
        assertEquals(report, encoded)
        assertEquals("calendar_feeds_v1", CALENDAR_FEEDS_CAPABILITY)
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        val serverCopy = File("../../server/testdata/calendar_feeds.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals(serverCopy.readText(), text)
    }
}
