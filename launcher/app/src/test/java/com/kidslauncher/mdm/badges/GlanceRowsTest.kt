package com.kidslauncher.mdm.badges

import com.kidslauncher.mdm.calls.MissedCallNotice
import com.kidslauncher.mdm.calls.MissedContact
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 25 §2: the lock glance's pure rules - the policy, candidates, levels, titles, texts,
 * cleaning and the rows. */
class GlanceRowsTest {

    private val element = "io.element.android.x"
    private val school = "no.example.school"
    private val ours = "me.vibb.launcher"
    private val ticked = GlancePolicy(setOf(element, school), showText = false)

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // ---- the policy -----------------------------------------------------------------------------

    @Test
    fun `the policy - an object field by field, missing means default fresh and unknown cached`() {
        assertEquals(GlancePolicy(setOf(element), false), lockGlancePolicy(json("""{"apps": ["$element"], "show_text": false}"""), fresh = false))
        assertEquals(GlancePolicy(setOf(element, school), true), lockGlancePolicy(json("""{"apps": ["$school", "$element", "$element"], "show_text": true}"""), fresh = true))
        // Only valid package names; show_text only for JSON true.
        assertEquals(
            GlancePolicy(setOf(element), false),
            lockGlancePolicy(json("""{"apps": ["$element", "bad", ".x", "a..b", 7, null, ["x.y"]], "show_text": "true"}"""), fresh = true),
        )
        assertEquals(GlancePolicy(emptySet(), false), lockGlancePolicy(json("""{"show_text": 1}"""), fresh = true))
        assertEquals(GlancePolicy(emptySet(), true), lockGlancePolicy(json("""{"apps": {}, "show_text": true}"""), fresh = true))
        // Missing or null: an older server's default when fresh; a pre-25 cache when cached (QA Low 11).
        assertEquals(GlancePolicy(setOf(element), false), lockGlancePolicy(null, fresh = true))
        assertEquals(GlancePolicy.SERVER_DEFAULT, lockGlancePolicy(json("null"), fresh = true))
        assertEquals(GlancePolicy.UNKNOWN, lockGlancePolicy(null, fresh = false))
        assertEquals(GlancePolicy.UNKNOWN, lockGlancePolicy(json("null"), fresh = false))
        // Anything else: unknown - missed calls only.
        for (odd in listOf("[]", "[\"$element\"]", "5", "\"x\"", "true")) {
            assertEquals(odd, GlancePolicy.UNKNOWN, lockGlancePolicy(json(odd), fresh = true))
        }
        assertTrue(GlancePolicy.UNKNOWN.apps.isEmpty())
        assertFalse(GlancePolicy.UNKNOWN.showText)
    }

    @Test
    fun `the policy's order - a fresh one wins, the start's cache never overwrites it (code QA Low 4)`() {
        val fresh = GlancePolicy(setOf(element), showText = true)
        val cached = GlancePolicy(setOf(school), showText = false)
        // The cache first, then a sync: the sync wins.
        val afterCache = nextGlancePolicy(GlancePolicyState.START, cached, fresh = false)
        assertEquals(GlancePolicyState(cached, freshSeen = false), afterCache)
        assertEquals(GlancePolicyState(fresh, freshSeen = true), nextGlancePolicy(afterCache, fresh, fresh = true))
        // A sync first, then the slow cache read: the sync stays.
        val afterSync = nextGlancePolicy(GlancePolicyState.START, fresh, fresh = true)
        assertEquals(afterSync, nextGlancePolicy(afterSync, cached, fresh = false))
        // Every later sync replaces it, an unknown one included.
        assertEquals(GlancePolicyState(GlancePolicy.UNKNOWN, freshSeen = true), nextGlancePolicy(afterSync, GlancePolicy.UNKNOWN, fresh = true))
        assertEquals(GlancePolicy.UNKNOWN, GlancePolicyState.START.policy)
    }

    @Test
    fun `no ranking counts as suspended (QA Medium 1, code QA Low 4)`() {
        assertTrue(glanceSuspended(ranked = false, isSuspended = false))
        assertTrue(glanceSuspended(ranked = false, isSuspended = true))
        assertTrue(glanceSuspended(ranked = true, isSuspended = true))
        assertFalse(glanceSuspended(ranked = true, isSuspended = false))
    }

    // ---- candidates -----------------------------------------------------------------------------

    private fun facts(
        pkg: String = element,
        groupSummary: Boolean = false,
        ongoing: Boolean = false,
        clearable: Boolean = true,
        media: Boolean = false,
        importance: Int? = 3,
        category: String? = "msg",
    ) = GlanceCandidateFacts(pkg, groupSummary, ongoing, clearable, media, importance, category)

    @Test
    fun `every skip rule`() {
        assertTrue(glanceCandidate(facts(), ours))
        assertTrue("no category", glanceCandidate(facts(category = null), ours))
        assertTrue("a missed VoIP call is a row", glanceCandidate(facts(category = "missed_call"), ours))
        assertTrue("no ranking yet: a candidate, but suspended (fail closed)", glanceCandidate(facts(importance = null), ours))
        // Our own and our sibling's.
        for (own in listOf(ours, "$ours.debug")) {
            assertFalse(own, glanceCandidate(facts(pkg = own), ours))
            assertFalse(own, glanceCandidate(facts(pkg = own), "$ours.debug"))
        }
        assertFalse(glanceCandidate(facts(groupSummary = true), ours))
        assertFalse(glanceCandidate(facts(ongoing = true), ours))
        assertFalse(glanceCandidate(facts(clearable = false), ours))
        assertFalse(glanceCandidate(facts(media = true), ours))
        assertFalse("IMPORTANCE_MIN", glanceCandidate(facts(importance = 1), ours))
        assertFalse("IMPORTANCE_NONE", glanceCandidate(facts(importance = 0), ours))
        assertTrue("IMPORTANCE_LOW", glanceCandidate(facts(importance = 2), ours))
        for (category in listOf(
            "call", "alarm", "stopwatch", "progress", "transport", "navigation", "service", "sys", "status",
            "location_sharing", "workout", "promo", "recommendation",
        )) {
            assertFalse(category, glanceCandidate(facts(category = category), ours))
        }
        assertEquals(13, GLANCE_SKIPPED_CATEGORIES.size)
    }

    // ---- levels ---------------------------------------------------------------------------------

    @Test
    fun `levels - not ticked, SECRET, the text switch, and sender-only outside MessagingStyle (QA Low 8)`() {
        assertEquals(GlanceLevel.NOT_READ, glanceLevel(ticked = false, secret = false, showText = true, messaging = true))
        assertEquals(GlanceLevel.APP_ONLY, glanceLevel(ticked = true, secret = true, showText = true, messaging = true))
        assertEquals(GlanceLevel.APP_ONLY, glanceLevel(ticked = true, secret = true, showText = false, messaging = true))
        assertEquals(GlanceLevel.TEXT, glanceLevel(ticked = true, secret = false, showText = true, messaging = true))
        assertEquals(GlanceLevel.TEXT, glanceLevel(ticked = true, secret = false, showText = true, messaging = false))
        assertEquals(GlanceLevel.SENDER, glanceLevel(ticked = true, secret = false, showText = false, messaging = true))
        // A school app's title can be a grade: sender-only shows the app and a count.
        assertEquals(GlanceLevel.APP_ONLY, glanceLevel(ticked = true, secret = false, showText = false, messaging = false))
        assertTrue(GlanceLevel.NOT_READ < GlanceLevel.APP_ONLY && GlanceLevel.APP_ONLY < GlanceLevel.SENDER && GlanceLevel.SENDER < GlanceLevel.TEXT)
        assertEquals(-1, GLANCE_VISIBILITY_SECRET)
        assertEquals(1, GLANCE_IMPORTANCE_MIN)
    }

    // ---- titles, texts, counts ------------------------------------------------------------------

    @Test
    fun `titles for a DM, a group, other styles and an empty sender`() {
        assertEquals("DM: the newest sender", "Pappa", glanceTitle(group = false, conversationTitle = null, newestSender = "Pappa", title = "Pappa"))
        assertEquals("a DM's room name doesn't replace the sender", "Pappa", glanceTitle(false, "Pappa og meg", "Pappa", "x"))
        assertEquals("group: its name", "Klassen 5B", glanceTitle(group = true, conversationTitle = "Klassen 5B", newestSender = "Ola", title = "Ola"))
        assertEquals("group without a name: the sender", "Ola", glanceTitle(group = true, conversationTitle = null, newestSender = "Ola", title = "x"))
        assertEquals("other styles: the title", "Gmail: Ola Nordmann", glanceTitle(false, null, null, "Gmail: Ola Nordmann"))
        // Redacted (15/16 NMS: an empty sender, the app's label as title).
        assertEquals("Element X", glanceTitle(false, null, null, "Element X"))
        assertEquals("empty sender, conversation title", "Rommet", glanceTitle(false, "Rommet", null, "Element X"))
        assertNull("nothing: the row uses the app's label", glanceTitle(false, null, null, null))
    }

    @Test
    fun `a group's text gets its sender in front`() {
        assertEquals("Pappa: Kommer kl 5", glanceGroupText(group = true, sender = "Pappa", text = "Kommer kl 5"))
        assertEquals("Kommer kl 5", glanceGroupText(group = false, sender = "Pappa", text = "Kommer kl 5"))
        assertEquals("Kommer kl 5", glanceGroupText(group = true, sender = null, text = "Kommer kl 5"))
        assertNull(glanceGroupText(group = true, sender = "Pappa", text = null))
    }

    @Test
    fun `counts - messages from others, else number, else 1`() {
        assertEquals("the kid's own replies don't count", 2, glanceCount(messages = 3, fromOthers = 2, number = 9))
        assertEquals(0, glanceCount(messages = 1, fromOthers = 0, number = 0))
        assertEquals(4, glanceCount(messages = 0, fromOthers = 0, number = 4))
        assertEquals(1, glanceCount(messages = 0, fromOthers = 0, number = 0))
        assertEquals(1, glanceCount(messages = 0, fromOthers = 0, number = -2))
    }

    @Test
    fun `cleaning - control and bidi characters out, whitespace collapsed, capped, empty is absent`() {
        assertEquals("Pappa", cleanGlanceText("  Pappa \n", GLANCE_TITLE_MAX))
        assertEquals("evil txt.exe", cleanGlanceText("evil\u202Etxt.exe", GLANCE_TITLE_MAX))
        assertEquals("a b c d", cleanGlanceText("a\u2066b\u2069c\u202Ad", GLANCE_TITLE_MAX))
        assertEquals("line one line two", cleanGlanceText("line one\r\n\tline\u0000two", GLANCE_TEXT_MAX))
        assertNull(cleanGlanceText("  \n\u202E ", GLANCE_TITLE_MAX))
        // Unicode's whitespace too: line and paragraph separators, no-break spaces (code QA Low 5).
        assertEquals("Pappa Kommer kl 5", cleanGlanceText("Pappa\u2028Kommer\u2029kl\u00A05", GLANCE_TITLE_MAX))
        assertEquals("a b", cleanGlanceText("a\u3000\u2003b", GLANCE_TITLE_MAX))
        // Invisible marks are dropped (not spaced); joiners stay - an emoji family keeps them.
        assertEquals("Pappa", cleanGlanceText("Pap\u200Epa\u200F\uFEFF\u2060\u061C", GLANCE_TITLE_MAX))
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67"
        assertEquals(family, cleanGlanceText(family, GLANCE_TITLE_MAX))
        assertNull(cleanGlanceText("\u2028\u00A0\uFEFF", GLANCE_TITLE_MAX))
        assertNull(cleanGlanceText(null, GLANCE_TITLE_MAX))
        val long = "x".repeat(100)
        assertEquals(60, cleanGlanceText(long, GLANCE_TITLE_MAX)!!.length)
        assertTrue(cleanGlanceText(long, GLANCE_TITLE_MAX)!!.endsWith("…"))
        assertEquals(200, cleanGlanceText("y".repeat(500), GLANCE_TEXT_MAX)!!.length)
        assertEquals("x".repeat(60), cleanGlanceText("x".repeat(60), GLANCE_TITLE_MAX))
        // A cut never splits a surrogate pair (an emoji at the edge).
        val emoji = "a".repeat(58) + "😀" + "bbb"
        val cut = cleanGlanceText(emoji, GLANCE_TITLE_MAX)!!
        assertFalse(cut.dropLast(1).last().isHighSurrogate())
        assertEquals(60, GLANCE_TITLE_MAX)
        assertEquals(200, GLANCE_TEXT_MAX)
    }

    // ---- rows -----------------------------------------------------------------------------------

    private fun notice(
        key: String,
        pkg: String = element,
        conversation: String = key,
        level: GlanceLevel = GlanceLevel.SENDER,
        title: String? = "Pappa",
        text: String? = null,
        count: Int = 1,
        whenMs: Long = 0,
        post: Long,
        suspended: Boolean = false,
    ) = GlanceNotice(key, pkg, conversation, level, title, text, count, whenMs, post, suspended)

    private fun rows(
        vararg notices: GlanceNotice,
        missed: MissedCallNotice? = null,
        policy: GlancePolicy = ticked,
        opened: Map<GlanceConversation, Long> = emptyMap(),
    ) = glanceRows(notices.toList(), missed, policy, opened)

    @Test
    fun `one row per conversation - grouped by shortcut id, counts added, the newest gives title, text and time`() {
        val result = rows(
            notice("k1", conversation = "room-a", title = "Pappa", count = 2, post = 100),
            notice("k2", conversation = "room-a", title = "Mamma", count = 1, whenMs = 150, post = 200),
            notice("k3", conversation = "room-b", title = "Ola", post = 50),
        )
        assertEquals(2, result.size)
        val first = result[0] as GlanceMessageRow
        assertEquals(GlanceConversation(element, "room-a"), first.conversation)
        assertEquals("k2", first.notificationKey)
        assertEquals("Mamma", first.title)
        assertEquals(3, first.count)
        assertEquals("when, else the post time", 150L, first.timeMs)
        assertEquals(200L, first.postTimeMs)
        assertEquals(50L, result[1].timeMs)
        // The same shortcut id in another app is another conversation.
        assertEquals(2, rows(notice("k1", conversation = "c", post = 1), notice("k2", pkg = school, conversation = "c", post = 2)).size)
        // A count of 0 (only the kid's own replies) still shows as one.
        assertEquals(1, (rows(notice("k1", count = 0, post = 1))[0] as GlanceMessageRow).count)
    }

    @Test
    fun `text only at TEXT, title only from SENDER, the most restrictive level of a conversation wins`() {
        val atText = rows(notice("k1", level = GlanceLevel.TEXT, title = "Klassen 5B", text = "Pappa: Kommer kl 5", post = 1))[0] as GlanceMessageRow
        assertEquals("Klassen 5B", atText.title)
        assertEquals("Pappa: Kommer kl 5", atText.text)
        val atSender = rows(notice("k1", level = GlanceLevel.SENDER, text = "leaked", post = 1))[0] as GlanceMessageRow
        assertNull(atSender.text)
        assertEquals("Pappa", atSender.title)
        val appOnly = rows(notice("k1", level = GlanceLevel.APP_ONLY, title = "leaked", text = "leaked", count = 2, post = 1))[0] as GlanceMessageRow
        assertNull(appOnly.title)
        assertNull(appOnly.text)
        assertEquals(2, appOnly.count)
        val mixed = rows(
            notice("k1", conversation = "c", level = GlanceLevel.APP_ONLY, post = 1),
            notice("k2", conversation = "c", level = GlanceLevel.TEXT, title = "Pappa", text = "Hei", post = 2),
        )[0] as GlanceMessageRow
        assertEquals(GlanceLevel.APP_ONLY, mixed.level)
        assertNull(mixed.title)
        assertNull(mixed.text)
    }

    @Test
    fun `a suspended app's notifications are no row until the ranking unsuspends them (QA Medium 1)`() {
        val posted = notice("k1", post = 1, suspended = true)
        assertTrue(rows(posted).isEmpty())
        assertEquals(1, rows(posted.copy(suspended = false)).size)
        // Only that app's.
        assertEquals(1, rows(posted, notice("k2", pkg = school, post = 2)).size)
    }

    @Test
    fun `unticked apps and unread notices are no rows`() {
        assertTrue(rows(notice("k1", post = 1), policy = GlancePolicy(setOf(school), false)).isEmpty())
        assertTrue(rows(notice("k1", post = 1), policy = GlancePolicy.UNKNOWN).isEmpty())
        assertTrue(rows(notice("k1", level = GlanceLevel.NOT_READ, post = 1)).isEmpty())
    }

    @Test
    fun `opened through the glance - hidden until the conversation is posted again with a newer post time`() {
        val conversation = GlanceConversation(element, "room-a")
        val first = notice("k1", conversation = "room-a", post = 100)
        assertTrue(rows(first, opened = mapOf(conversation to 100L)).isEmpty())
        assertTrue("an older post too", rows(first, opened = mapOf(conversation to 150L)).isEmpty())
        assertEquals("a new message", 1, rows(first, notice("k2", conversation = "room-a", post = 200), opened = mapOf(conversation to 100L)).size)
        assertEquals("other conversations stay", 1, rows(first, notice("k3", conversation = "room-b", post = 50), opened = mapOf(conversation to 100L)).size)
    }

    private val pappa = MissedContact("+4790000002", "Pappa", 2, 300)
    private val mamma = MissedContact("+4790000001", "Mamma", 1, 120)

    @Test
    fun `missed calls join the rows - one per contact, all newest first`() {
        val missed = MissedCallNotice(3, listOf(pappa, mamma), null, 300, 9)
        val result = rows(
            notice("k1", conversation = "room-a", whenMs = 200, post = 200),
            notice("k2", conversation = "room-b", post = 100),
            missed = missed,
        )
        assertEquals(listOf(300L, 200L, 120L, 100L), result.map { it.timeMs })
        assertEquals(GlanceMissedRow("+4790000002", "Pappa", 2, 300), result[0])
        assertEquals(GlanceMissedRow("+4790000001", "Mamma", 1, 120), result[2])
        // The plain notice (fail-closed, unmanaged with our dialer role): one row, no names.
        val plain = MissedCallNotice(4, emptyList(), null, 500, 12)
        assertEquals(listOf(GlanceMissedRow(null, null, 4, 500)), rows(missed = plain))
        // Missed calls show whatever the policy says (unknown included).
        assertEquals(2, rows(missed = missed, policy = GlancePolicy.UNKNOWN).size)
        assertTrue(rows().isEmpty())
    }

    @Test
    fun `toString is redacted - no name, number or text in a log line or crash report`() {
        val secrets = listOf("Pappa", "Kommer kl 5", "+4790000002", "room-a", "k-secret")
        val all: List<Any> = listOf(
            notice("k-secret", conversation = "room-a", level = GlanceLevel.TEXT, title = "Pappa", text = "Kommer kl 5", post = 1),
            GlanceConversation(element, "room-a"),
            GlanceMessageRow(GlanceConversation(element, "room-a"), "k-secret", GlanceLevel.TEXT, "Pappa", "Kommer kl 5", 1, 1, 1),
            GlanceMissedRow("+4790000002", "Pappa", 1, 1),
            pappa,
            MissedCallNotice(1, listOf(pappa), "+4790000002", 1, 1),
        )
        for (value in all) {
            val printed = value.toString()
            for (secret in secrets) assertFalse("$printed carries $secret", printed.contains(secret))
        }
        assertEquals("GlanceRow($element)", all[2].toString())
    }

    @Test
    fun `the kept facts are exactly the ones the design names`() {
        fun fields(type: Class<*>) = type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
        assertEquals(
            setOf("key", "packageName", "conversationKey", "level", "title", "text", "count", "whenMs", "postTimeMs", "suspended"),
            fields(GlanceNotice::class.java),
        )
        assertEquals(setOf("packageName", "groupSummary", "ongoing", "clearable", "media", "importance", "category"), fields(GlanceCandidateFacts::class.java))
    }
}
