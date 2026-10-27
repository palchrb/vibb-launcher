package com.kidslauncher.mdm.day

import com.kidslauncher.mdm.server.ServerJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 72 §4: the diff, the batches, the pass's outcome, when a set is fetched, which link calendars go. */
class FeedPlanTest {
    private fun series(uid: String, rev: String = "r-$uid", overrides: Int = 0, floating: Boolean = false, reminders: Int = 0) =
        SeriesRows(
            uid, rev,
            List(1 + overrides) { EventRow(if (it == 0) emptyMap() else mapOf(FeedColumns.ORIGINAL_SYNC_ID to uid), List(reminders) { 15 }) },
            unknownZone = false, floating = floating,
        )

    private fun master(id: Long, uid: String, rev: String = "r-$uid", zone: String? = null) = StoredRow(id, uid, null, rev, zone)
    private fun override(id: Long, uid: String) = StoredRow(id, "$uid#x$id", uid, "r-$uid", null)

    @Test
    fun `no change is no operation`() {
        val plan = feedPlan(listOf(series("a", overrides = 1)), listOf(master(1, "a"), override(2, "a")), "Europe/Oslo", true)
        assertTrue(plan.nothing)
    }

    @Test
    fun `new, changed, gone and half-made`() {
        val stored = listOf(
            master(1, "kept"),
            master(2, "changed", rev = "old"), override(3, "changed"),
            master(4, "gone"), override(5, "gone"),
            override(6, "orphan"),
            master(7, "twice"), master(8, "twice"),
        )
        val plan = feedPlan(
            listOf(series("kept"), series("changed"), series("new"), series("twice")),
            stored, "Europe/Oslo", true,
        )
        assertEquals(setOf(2L, 3L, 4L, 5L, 6L, 7L, 8L), plan.deletes.toSet())
        assertEquals(listOf("changed", "new", "twice"), plan.inserts.map { it.uid })
    }

    @Test
    fun `a zone change rewrites only floating series, a mapping bump everything`() {
        val stored = listOf(master(1, "floating", zone = "Europe/Oslo"), master(2, "zoned"))
        val set = listOf(series("floating", floating = true), series("zoned"))
        val moved = feedPlan(set, stored, "Europe/London", true)
        assertEquals(listOf(1L), moved.deletes)
        assertEquals(listOf("floating"), moved.inserts.map { it.uid })
        assertTrue(feedPlan(set, stored, "Europe/Oslo", true).nothing)
        val bump = feedPlan(set, stored, "Europe/Oslo", false)
        assertEquals(listOf(1L, 2L), bump.deletes)
        assertEquals(2, bump.inserts.size)
    }

    @Test
    fun `batches hold at most 200 operations and never split a series`() {
        val set = List(30) { series("s$it", overrides = 4, reminders = 2) } + series("big", overrides = 99, reminders = 2)
        val batches = feedBatches(FeedPlan(List(450) { it.toLong() }, set))
        assertEquals(listOf(200, 200, 50), batches.deleteChunks.map { it.size })
        for (batch in batches.insertBatches) {
            assertTrue(batch.size == 1 || batch.sumOf { it.operations } <= FEED_BATCH_OPS)
        }
        assertEquals(set, batches.insertBatches.flatten())
        assertEquals(listOf("big"), batches.insertBatches.last().map { it.uid })
        assertEquals(300, batches.insertBatches.last().single().operations)
    }

    @Test
    fun `refused rows write the rev and are counted, the provider gone leaves it`() {
        val set = List(5) { series("s$it") }
        val batches = feedBatches(FeedPlan(listOf(9L), set), max = 2)
        val order = set.withIndex().associate { (i, s) -> s.uid to i }
        val bad = setOf("s1", "s4")
        val inserted = ArrayList<String>()
        val refused = runPass(
            batches, { order.getValue(it.uid) },
            delete = { BatchResult.OK },
            insert = { batch -> if (batch.any { it.uid in bad }) BatchResult.REFUSED else BatchResult.OK.also { inserted += batch.map { s -> s.uid } } },
        )
        assertTrue(refused.writeRev)
        assertEquals(2, refused.refused)
        assertEquals(listOf(1, 4), refused.refusedIndexes)
        assertEquals(listOf("s0", "s2", "s3"), inserted)
        var calls = 0
        val gone = runPass(batches, { 0 }, delete = { BatchResult.OK }, insert = { if (++calls == 2) BatchResult.GONE else BatchResult.OK })
        assertFalse(gone.writeRev)
        val goneDeleting = runPass(batches, { 0 }, delete = { BatchResult.GONE }, insert = { error("no insert after a gone delete") })
        assertFalse(goneDeleting.writeRev)
    }

    @Test
    fun `a set is fetched only when something moved`() {
        val z = "Europe/Oslo"
        assertEquals(FeedFetch(null), feedFetch(false, null, "r2", null, null, z))
        assertEquals(FeedFetch("\"r1\""), feedFetch(true, "r1", "r2", FEED_MAPPING, "", z))
        assertNull(feedFetch(true, "r2", "r2", FEED_MAPPING, "", z))
        assertNull(feedFetch(true, "r2", "r2", FEED_MAPPING, z, z))
        assertEquals(FeedFetch(null), feedFetch(true, "r2", "r2", "0", "", z))
        assertEquals(FeedFetch(null), feedFetch(true, "r2", "r2", FEED_MAPPING, "Europe/London", z))
    }

    @Test
    fun `feeds missing or with a bad element delete nothing, an empty array every link calendar`() {
        fun read(json: String) = calendarPolicy(ServerJson.parseToJsonElement(json))
        val uids = listOf("a".repeat(32), "b".repeat(32))
        val one = """{"id": "${"a".repeat(32)}", "name": "Skolen", "rev": "r1"}"""
        assertEquals(listOf("b".repeat(32)), feedCalendarsGone(read("""{"feeds": [$one]}"""), uids))
        assertEquals(uids, feedCalendarsGone(read("""{"feeds": []}"""), uids))
        for (odd in listOf("{}", """{"feeds": "x"}""", """{"feeds": [$one, 5]}""", """{"feeds": [$one, {"id": "short", "name": "x", "rev": "r"}]}""")) {
            assertEquals(odd, emptyList<String>(), feedCalendarsGone(read(odd), uids))
        }
        // A bad element still leaves the good ones applied.
        assertEquals(1, read("""{"feeds": [$one, 5]}""").feeds.size)
        assertEquals(emptyList<String>(), feedCalendarsGone(CalendarPolicy.UNKNOWN, uids))
        assertEquals(emptyList<String>(), feedCalendarsGone(CalendarPolicy.OLDER_SERVER, uids))
    }

    @Test
    fun `a feed calendar is never the kid's`() {
        val feed = CalendarInfo(
            id = 1, key = calendarKey("LOCAL", FEED_ACCOUNT, feedCalendarName("a".repeat(32))), accountType = "LOCAL",
            displayName = "Skolen", syncEvents = true, accessLevel = FEED_ACCESS_LEVEL, vibbLocal = false,
            name = feedCalendarName("a".repeat(32)),
        )
        val policy = CalendarPolicy(usable = true, kidAdds = true, calendars = mapOf(feed.key to CalendarSetting(writableForKid = true)))
        assertNull(kidCalendarOf(policy, listOf(feed), 500))
    }
}
