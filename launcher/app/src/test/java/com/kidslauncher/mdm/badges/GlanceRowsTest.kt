package com.kidslauncher.mdm.badges

import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.MissedCallNotice
import com.kidslauncher.mdm.calls.MissedContact
import com.kidslauncher.mdm.calls.RuleContact
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
    private val ticked = GlancePolicy(mapOf(element to false, school to false))

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    // ---- the policy -----------------------------------------------------------------------------

    @Test
    fun `the policy - an object field by field, missing means default fresh and unknown cached`() {
        // Design 51: each app an object with its own text.
        assertEquals(
            GlancePolicy(mapOf(school to true, element to false)),
            lockGlancePolicy(json("""{"apps": [{"package": "$school", "show_text": true}, {"package": "$element", "show_text": false}]}"""), fresh = false),
        )
        // The shape before 51 (an older server): strings, sender only - its global switch is ignored.
        assertEquals(GlancePolicy(mapOf(element to false, school to false)), lockGlancePolicy(json("""{"apps": ["$school", "$element", "$element"], "show_text": true}"""), fresh = true))
        // Mixed; only valid package names, show_text only for JSON true (missing = false); an
        // object without a usable package is dropped.
        assertEquals(
            GlancePolicy(mapOf(element to true, school to false, "org.a.chat" to false, "org.b.chat" to false)),
            lockGlancePolicy(
                json(
                    """{"apps": [{"package": "$element", "show_text": true}, "$school", {"package": "org.a.chat"},
                    {"package": "org.b.chat", "show_text": "true"}, {"show_text": true}, {"package": "bad", "show_text": true},
                    {"package": 7}, {"package": null}, "bad", ".x", "a..b", 7, null, ["x.y"]]}""",
                ),
                fresh = true,
            ),
        )
        assertEquals(GlancePolicy(emptyMap()), lockGlancePolicy(json("""{"show_text": 1}"""), fresh = true))
        assertEquals(GlancePolicy(emptyMap()), lockGlancePolicy(json("""{"apps": {}}"""), fresh = true))
        // Missing or null: an older server's default when fresh; a pre-25 cache when cached (QA Low 11).
        assertEquals(GlancePolicy(mapOf(element to false)), lockGlancePolicy(null, fresh = true))
        assertEquals(GlancePolicy.SERVER_DEFAULT, lockGlancePolicy(json("null"), fresh = true))
        assertEquals(GlancePolicy.UNKNOWN, lockGlancePolicy(null, fresh = false))
        assertEquals(GlancePolicy.UNKNOWN, lockGlancePolicy(json("null"), fresh = false))
        // Anything else: unknown - missed calls only.
        for (odd in listOf("[]", "[\"$element\"]", "5", "\"x\"", "true")) {
            assertEquals(odd, GlancePolicy.UNKNOWN, lockGlancePolicy(json(odd), fresh = true))
        }
        assertTrue(GlancePolicy.UNKNOWN.apps.isEmpty())
        assertFalse(GlancePolicy.UNKNOWN.showsText(element))
    }

    @Test
    fun `the policy's order - a fresh one wins, the start's cache never overwrites it (code QA Low 4)`() {
        val fresh = GlancePolicy(mapOf(element to true))
        val cached = GlancePolicy(mapOf(school to false))
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
        // Design 51: the text per app - shown for one ticked app, the sender only for the other.
        val policy = GlancePolicy(mapOf(element to true, school to false))
        assertEquals(GlanceLevel.TEXT, glanceLevel(ticked = true, secret = false, showText = policy.showsText(element), messaging = true))
        assertEquals(GlanceLevel.SENDER, glanceLevel(ticked = true, secret = false, showText = policy.showsText(school), messaging = true))
        assertFalse(policy.showsText(ours))
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
        missedCall: Boolean = false,
    ) = GlanceNotice(key, pkg, conversation, level, title, text, count, whenMs, post, suspended, missedCall)

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
        assertTrue(rows(notice("k1", post = 1), policy = GlancePolicy(mapOf(school to false))).isEmpty())
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

    /** Bug 25b-E: Element X sets `CATEGORY_MISSED_CALL` on a room notification that holds a missed
     * call (an RTC notification event - its NotificationCreator); the row is a missed-call row. */
    @Test
    fun `an app's missed call is a missed-call row, its newest notification decides`() {
        assertTrue(glanceMissedCall("missed_call"))
        assertFalse(glanceMissedCall("msg"))
        assertFalse(glanceMissedCall("call"))
        assertFalse(glanceMissedCall(null))
        // A ringing call ("call") is design 17's, never a row; a missed one is.
        assertFalse(glanceCandidate(facts(category = "call"), ours))
        assertTrue(glanceCandidate(facts(category = "missed_call"), ours))
        val missed = rows(notice("k1", conversation = "room-a", title = "Pappa", post = 200, missedCall = true))[0] as GlanceMessageRow
        assertTrue(missed.missedCall)
        assertEquals("Pappa", missed.title)
        val laterMessage = rows(
            notice("k1", conversation = "room-a", post = 100, missedCall = true),
            notice("k2", conversation = "room-a", post = 200),
        )[0] as GlanceMessageRow
        assertFalse("a newer message in the same room: a message row", laterMessage.missedCall)
        assertEquals("GlanceRow(io.element.android.x)", missed.toString())
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
            setOf(
                "key", "packageName", "conversationKey", "level", "title", "text", "count", "whenMs", "postTimeMs", "suspended", "missedCall",
                // 2026-10-25: what the title names, the newest sender's name and our own contact's id - never a key or number.
                "shape", "sender", "contactId",
            ),
            fields(GlanceNotice::class.java),
        )
        assertEquals(setOf("packageName", "groupSummary", "ongoing", "clearable", "media", "importance", "category"), fields(GlanceCandidateFacts::class.java))
    }

    /** qa-25b-25f L4: a missed call shows only from SENDER up, with the count when there is more. */
    @Test
    fun `a missed-call row never tells more than the app's level`() {
        assertEquals(GlanceSubtitle.NOTICES, glanceSubtitle(GlanceLevel.APP_ONLY, missedCall = true, count = 1, hasText = false))
        assertEquals(GlanceSubtitle.NOTICES, glanceSubtitle(GlanceLevel.APP_ONLY, missedCall = false, count = 3, hasText = false))
        assertEquals(GlanceSubtitle.MISSED_CALL, glanceSubtitle(GlanceLevel.SENDER, missedCall = true, count = 1, hasText = false))
        assertEquals(GlanceSubtitle.MISSED_CALL_AND_COUNT, glanceSubtitle(GlanceLevel.SENDER, missedCall = true, count = 3, hasText = false))
        assertEquals(GlanceSubtitle.MISSED_CALL, glanceSubtitle(GlanceLevel.TEXT, missedCall = true, count = 1, hasText = true))
        assertEquals(GlanceSubtitle.TEXT, glanceSubtitle(GlanceLevel.TEXT, missedCall = false, count = 1, hasText = true))
        assertEquals(GlanceSubtitle.COUNT, glanceSubtitle(GlanceLevel.TEXT, missedCall = false, count = 2, hasText = false))
        assertEquals(GlanceSubtitle.COUNT, glanceSubtitle(GlanceLevel.SENDER, missedCall = false, count = 2, hasText = false))
        val panel = listOf("src/main", "app/src/main").map { java.io.File(it, "java/com/kidslauncher/mdm/lock/GlancePanel.kt") }.first { it.exists() }.readText()
        // The badge follows the shown kind, not the raw flag (Q-3: the pure glanceRowBadge, bound in one line).
        assertTrue(panel.contains("when (glanceRowBadge(missedShown, hasContact = contact != null, hasIcon = icon != null)) {"))
        assertTrue(panel.contains("GlanceRowBadge.MISSED -> missedBadge(view)"))
        assertTrue(panel.contains("GlanceRowBadge.APP_MARK -> appMark(view, icon)"))
        assertTrue(panel.contains("GlanceRowBadge.NONE -> view.glanceRowBadge.visibility = View.GONE"))
        for (name in listOf("appMark", "missedBadge")) {
            val body = panel.substringAfter("private fun $name(").substringBefore("\n    }\n")
            assertTrue(name, body.contains("badge.visibility = View.VISIBLE"))
        }
    }

    @Test
    fun `the corner - a missed call's red phone wins, else the app mark on a contact's photo once drawn`() {
        for (missed in listOf(true, false)) for (contact in listOf(true, false)) for (icon in listOf(true, false)) {
            val expected = when {
                missed -> GlanceRowBadge.MISSED
                contact && icon -> GlanceRowBadge.APP_MARK
                else -> GlanceRowBadge.NONE
            }
            assertEquals("$missed $contact $icon", expected, glanceRowBadge(missed, contact, icon))
        }
    }

    @Test
    fun `TalkBack reads the row's own words once - a group with its sender, the name line's count`() {
        val dm = row(shape = GlanceShape.DIRECT, title = "Mamma", count = 3)
        assertEquals(listOf(GlanceSpokenPart.Name("Mamma"), GlanceSpokenPart.Count), glanceSpokenParts(dm))
        val dmFull = dm.copy(level = GlanceLevel.TEXT, text = "Kommer snart")
        assertEquals(
            listOf(GlanceSpokenPart.Name("Mamma"), GlanceSpokenPart.NewCount(3), GlanceSpokenPart.Text("Kommer snart")),
            glanceSpokenParts(dmFull),
        )
        assertEquals(
            "one message: the messages count stays",
            listOf(GlanceSpokenPart.Name("Mamma"), GlanceSpokenPart.Text("Kommer snart"), GlanceSpokenPart.Count),
            glanceSpokenParts(dmFull.copy(count = 1)),
        )
        val group = row(shape = GlanceShape.GROUP, title = "Fotballgjengen", sender = "Pappa", count = 5)
        assertEquals(listOf(GlanceSpokenPart.Group("Fotballgjengen", "Pappa"), GlanceSpokenPart.Count), glanceSpokenParts(group))
        assertEquals(
            listOf(GlanceSpokenPart.Group("Fotballgjengen", "Pappa"), GlanceSpokenPart.NewCount(5), GlanceSpokenPart.Text("Pappa: Hei")),
            glanceSpokenParts(group.copy(level = GlanceLevel.TEXT, text = "Pappa: Hei")),
        )
        assertEquals("no sender: the name", listOf(GlanceSpokenPart.Name("Fotballgjengen"), GlanceSpokenPart.Count), glanceSpokenParts(group.copy(sender = null)))
        assertEquals("APP_ONLY: the count alone", listOf(GlanceSpokenPart.Count), glanceSpokenParts(group.copy(level = GlanceLevel.APP_ONLY, title = null, sender = null)))
        for (part in listOf(GlanceSpokenPart.Name("Mamma"), GlanceSpokenPart.Group("Fotballgjengen", "Pappa"), GlanceSpokenPart.Text("Kommer snart"))) {
            assertFalse(part.toString(), Regex("Mamma|Pappa|Fotballgjengen|Kommer").containsMatchIn(part.toString()))
        }
        // The panel maps each part (M13: the group's string), and the strings say it.
        val panel = listOf("src/main", "app/src/main").map { java.io.File(it, "java/com/kidslauncher/mdm/lock/GlancePanel.kt") }.first { it.exists() }.readText()
        assertTrue(panel.contains("is GlanceSpokenPart.Group -> activity.getString(R.string.glance_group_spoken, part.name, part.sender)"))
        assertTrue(panel.contains("is GlanceSpokenPart.NewCount -> activity.resources.getQuantityString(R.plurals.glance_new_count, part.count, part.count)"))
        assertTrue(panel.contains("else -> listOf(label) + glanceSpokenParts(row).map { part -> spokenPart(part, count) } + time"))
        fun res(dir: String) = listOf("src/main", "app/src/main").map { java.io.File(it, "res/$dir/strings.xml") }.first { it.exists() }.readText()
        assertTrue(res("values").contains("<string name=\"glance_group_spoken\">Group %1\$s, latest from %2\$s</string>"))
        assertTrue(res("values-nb").contains("<string name=\"glance_group_spoken\">Gruppe %1\$s, siste fra %2\$s</string>"))
    }

    // ---- 2026-10-25: contact photos, the group's sender, one sender's count -------------------------------------------

    private val whatsapp = "com.whatsapp"
    private val phoneBook = CallPolicyState.Managed(
        CallRules(
            callsEnabled = true,
            contacts = listOf(
                RuleContact(7, "Mamma", "+4790000001", inbound = true, outbound = true),
                RuleContact(8, "Pappa", "+4790000002", inbound = true, outbound = true),
                // Inbound only: not in the kid's phone book.
                RuleContact(9, "Lærer", "+4790000003", inbound = true, outbound = false),
                // A contact without an id (an old cache) never matches.
                RuleContact(0, "Ukjent", "+4790000004", inbound = true, outbound = true),
            ),
        ),
    )

    @Test
    fun `the shape - one sender, a named group, or plain`() {
        assertEquals(GlanceShape.DIRECT, glanceShape(messaging = true, group = false, conversationTitle = null))
        assertEquals(GlanceShape.DIRECT, glanceShape(messaging = true, group = false, conversationTitle = "Mamma"))
        assertEquals(GlanceShape.GROUP, glanceShape(messaging = true, group = true, conversationTitle = "Fotballgjengen"))
        assertEquals("a group without a name is titled by its sender", GlanceShape.PLAIN, glanceShape(messaging = true, group = true, conversationTitle = null))
        assertEquals(GlanceShape.PLAIN, glanceShape(messaging = false, group = false, conversationTitle = null))
        assertEquals(GlanceShape.PLAIN, glanceShape(messaging = false, group = true, conversationTitle = "X"))
    }

    @Test
    fun `WhatsApp's sender is a contact only by a tel URI, normalised as the phone book's`() {
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), GLANCE_TEL_PACKAGES)
        assertEquals(8L, glanceTelContact("tel:+4790000002", phoneBook))
        assertEquals("national form, the rules' country code", 8L, glanceTelContact("tel:900 00 002", phoneBook))
        assertEquals("percent-encoded and with parameters", 7L, glanceTelContact("TEL:%2B47-9000-0001;phone-context=x", phoneBook))
        assertEquals("00 prefix", 7L, glanceTelContact("tel:004790000001", phoneBook))
        assertNull("not in the phone book", glanceTelContact("tel:+4790000099", phoneBook))
        assertNull("inbound only is not the phone book", glanceTelContact("tel:+4790000003", phoneBook))
        assertNull("a contact without an id", glanceTelContact("tel:+4790000004", phoneBook))
        for (other in listOf(null, "", "sip:+4790000002@ims.example", "mailto:a@b.c", "+4790000002", "content://contacts/1", "tel:*21#")) {
            assertNull("$other", glanceTelContact(other, phoneBook))
        }
        assertNull(glanceTelContact("tel:+4790000002", CallPolicyState.Unmanaged))
        assertNull(glanceTelContact("tel:+4790000002", CallPolicyState.UnknownFailClosed))
    }

    private fun row(
        level: GlanceLevel = GlanceLevel.SENDER,
        shape: GlanceShape = GlanceShape.DIRECT,
        title: String? = "Mamma",
        text: String? = null,
        count: Int = 1,
        sender: String? = null,
        contactId: Long? = null,
        missedCall: Boolean = false,
    ) = GlanceMessageRow(
        GlanceConversation(whatsapp, "c"), "k", level, title, text, count, 1, 1,
        missedCall = missedCall, shape = shape, sender = sender, contactId = contactId,
    )

    @Test
    fun `a row's photo is its contact's from the phone book now, never at APP_ONLY`() {
        assertEquals("Pappa", glanceRowContact(row(contactId = 8), phoneBook)?.name)
        assertEquals("Pappa", glanceRowContact(row(level = GlanceLevel.TEXT, contactId = 8), phoneBook)?.name)
        assertNull("the app hides who wrote", glanceRowContact(row(level = GlanceLevel.APP_ONLY, contactId = 8), phoneBook))
        assertNull("left the phone book", glanceRowContact(row(contactId = 9), phoneBook))
        assertNull(glanceRowContact(row(contactId = 0), phoneBook))
        assertNull(glanceRowContact(row(contactId = null), phoneBook))
        assertNull(glanceRowContact(row(contactId = 8), CallPolicyState.Unmanaged))
    }

    @Test
    fun `a group in Name only shows its newest sender, Full text keeps the text's sender`() {
        val group = row(shape = GlanceShape.GROUP, title = "Fotballgjengen", sender = "Pappa", count = 3)
        assertEquals(GlanceNameSuffix.Sender("Pappa"), glanceNameSuffix(group))
        assertEquals("Pappa", glanceSpokenGroup(group))
        val full = group.copy(level = GlanceLevel.TEXT, text = "Pappa: Kommer kl 5")
        // Full text: "Sender: text" under the name, and the count (Q-6, the user's decision): "Fotballgjengen · 3 nye".
        assertEquals(GlanceNameSuffix.NewCount(3), glanceNameSuffix(full))
        assertNull("one message: the name alone", glanceNameSuffix(full.copy(count = 1)))
        assertEquals("TalkBack names the group in both", "Pappa", glanceSpokenGroup(full))
        assertNull("no sender known", glanceNameSuffix(group.copy(sender = null)))
        assertNull("the sender is the name", glanceNameSuffix(group.copy(sender = "Fotballgjengen")))
        assertNull(glanceSpokenGroup(group.copy(sender = "Fotballgjengen")))
        assertNull("a missed call says so itself", glanceNameSuffix(group.copy(missedCall = true)))
        assertNull(glanceNameSuffix(group.copy(level = GlanceLevel.APP_ONLY, title = null)))
        assertNull(glanceSpokenGroup(group.copy(level = GlanceLevel.APP_ONLY, title = null)))
        assertNull("one sender: no group", glanceSpokenGroup(row(sender = "Mamma")))
    }

    @Test
    fun `Full text shows the count of one sender's messages when more than one`() {
        val full = row(level = GlanceLevel.TEXT, text = "Kommer snart", count = 3)
        assertEquals(GlanceNameSuffix.NewCount(3), glanceNameSuffix(full))
        assertNull("one message", glanceNameSuffix(full.copy(count = 1)))
        assertNull("Name only already says \"3 meldinger\"", glanceNameSuffix(full.copy(level = GlanceLevel.SENDER, text = null)))
        assertNull("no text shown", glanceNameSuffix(full.copy(text = null)))
        assertNull("another style's title isn't a sender", glanceNameSuffix(full.copy(shape = GlanceShape.PLAIN)))
        assertEquals("a group's too (Q-6)", GlanceNameSuffix.NewCount(3), glanceNameSuffix(full.copy(shape = GlanceShape.GROUP, sender = "Pappa")))
        assertNull(glanceNameSuffix(full.copy(missedCall = true)))
        // The rows add the conversation's counts: three notifications of one DM are "· 3 nye".
        val folded = rows(
            notice("k1", pkg = whatsapp, conversation = "dm", level = GlanceLevel.TEXT, title = "Mamma", text = "a", post = 1).copy(shape = GlanceShape.DIRECT),
            notice("k2", pkg = whatsapp, conversation = "dm", level = GlanceLevel.TEXT, title = "Mamma", text = "b", post = 2).copy(shape = GlanceShape.DIRECT),
            notice("k3", pkg = whatsapp, conversation = "dm", level = GlanceLevel.TEXT, title = "Mamma", text = "c", post = 3).copy(shape = GlanceShape.DIRECT),
            policy = GlancePolicy(mapOf(whatsapp to true)),
        ).single() as GlanceMessageRow
        assertEquals("c", folded.text)
        assertEquals(GlanceNameSuffix.NewCount(3), glanceNameSuffix(folded))
    }

    @Test
    fun `the rows carry the newest's shape, sender and contact - the latter two from SENDER up`() {
        val policy = GlancePolicy(mapOf(whatsapp to false))
        val result = rows(
            notice("k1", pkg = whatsapp, conversation = "g", title = "Fotballgjengen", post = 1)
                .copy(shape = GlanceShape.GROUP, sender = "Mamma", contactId = 7),
            notice("k2", pkg = whatsapp, conversation = "g", title = "Fotballgjengen", post = 2)
                .copy(shape = GlanceShape.GROUP, sender = "Pappa", contactId = 8),
            policy = policy,
        ).single() as GlanceMessageRow
        assertEquals(GlanceShape.GROUP, result.shape)
        assertEquals("Pappa", result.sender)
        assertEquals(8L, result.contactId)
        // One notification at APP_ONLY makes the row APP_ONLY: no sender, no contact.
        val hidden = rows(
            notice("k1", pkg = whatsapp, conversation = "g", level = GlanceLevel.APP_ONLY, title = null, post = 1),
            notice("k2", pkg = whatsapp, conversation = "g", title = "Fotballgjengen", post = 2)
                .copy(shape = GlanceShape.GROUP, sender = "Pappa", contactId = 8),
            policy = policy,
        ).single() as GlanceMessageRow
        assertNull(hidden.sender)
        assertNull(hidden.contactId)
        assertNull(glanceRowContact(hidden, phoneBook))
        // The suffix's name stays out of a log line.
        assertFalse(GlanceNameSuffix.Sender("Pappa").toString().contains("Pappa"))
    }
}
