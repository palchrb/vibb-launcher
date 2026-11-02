package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round L5: missed Element X calls on the contact card and the phone book's badge - who a missed call stands for, the
 * memory of the listener's posts, and the count after the seen marks. The fixtures are Element X's room notification
 * as its Android source builds it (develop bb79064b, read 2026-11-02): tag = the room ID (`<room>|<thread>` in a
 * thread), each message bundle's `sender_person` key = the sender's MXID, the messaging person's key = the kid's own
 * MXID, `isGroupConversation` false for a DM, `category` = `missed_call` while the call's event is pending, else
 * `message`; with an app PIN in Element X one fallback notification without tag or category.
 */
class ElementMissedCallsTest {

    private val kid = "@kid:example.org"
    private val pappa = "@pappa:example.org"
    private val mamma = "@mamma:example.net"
    private val stranger = "@stranger:example.com"
    private val pappaRoom = "!pappa-dm:example.org"
    private val groupRoom = "!family:example.org"

    private val contacts = listOf(
        RuleContact(1, "Mamma", "+4790000001", inbound = true, outbound = true, messageApp = "element", messageAddress = mamma),
        RuleContact(2, "Pappa", "+4790000002", inbound = true, outbound = true, messageApp = "element", messageAddress = pappa),
        RuleContact(3, "Bestemor", "+4790000003", inbound = true, outbound = true, messageApp = "sms"),
    )
    private val rules = CallRules(callsEnabled = true, contacts = contacts)
    private val managed = CallPolicyState.Managed(rules)
    private val learned = mapOf(pappa to ElementRoom(kid, pappaRoom))

    /** The facts [com.kidslauncher.mdm.badges.ElementDmReader] reads from one of Element X's room notifications, and
     * its category. [senders]: each message bundle's `sender_person` key, oldest first (the kid's own messages carry
     * none and aren't listed). */
    private data class RoomNotification(val category: String?, val facts: ElementDmFacts)

    private fun dmNotification(
        category: String? = "missed_call",
        room: String? = pappaRoom,
        senders: List<String?> = listOf(pappa),
        self: String? = kid,
        group: Boolean? = false,
    ) = RoomNotification(category, ElementDmFacts(room, self, senders, group))

    /** The app-PIN fallback: one generic notification, no tag, no category, no messages, no flag. */
    private val pinFallback = RoomNotification(null, ElementDmFacts(null, null, emptyList(), null))

    private fun contactOf(n: RoomNotification, state: CallPolicyState = managed, rooms: Map<String, ElementRoom> = learned) =
        elementMissedCallContact(n.category, n.facts, state, rooms)

    // ---- who a missed call stands for -----------------------------------------------------------------------------

    @Test
    fun `a missed call in a DM is the contact of its learned room, else its newest sender`() {
        assertEquals("by the learned room", pappa, contactOf(dmNotification()))
        // The ring and the missed call came before any message taught the room: the sender's key.
        assertEquals("not learned yet", pappa, contactOf(dmNotification(), rooms = emptyMap()))
        // An update after earlier messages: the call is the newest event, the room still Pappa's.
        assertEquals(pappa, contactOf(dmNotification(senders = listOf(pappa, pappa, pappa))))
        assertEquals("a thread in the DM: its room", pappa, contactOf(dmNotification(room = "$pappaRoom|\$thread-root", senders = listOf(null))))
        assertEquals("no session on it: the room alone", pappa, contactOf(dmNotification(self = null, senders = emptyList())))
        // The room is learned for another session: the sender decides.
        assertEquals(pappa, contactOf(dmNotification(), rooms = mapOf(mamma to ElementRoom("@other:example.org", pappaRoom))))
        // A stale pair whose contact left Element X: the sender decides, nobody when there is none.
        assertEquals(pappa, contactOf(dmNotification(), rooms = mapOf("@gone:example.org" to ElementRoom(kid, pappaRoom))))
        assertNull(contactOf(dmNotification(senders = emptyList()), rooms = mapOf("@gone:example.org" to ElementRoom(kid, pappaRoom))))
        // The kid's own key on a bundle is never the caller.
        assertEquals(pappa, contactOf(dmNotification(senders = listOf(pappa, kid)), rooms = emptyMap()))
    }

    @Test
    fun `nothing but a missed call of a phone-book Element contact in a DM marks a contact`() {
        assertNull("a plain message", contactOf(dmNotification(category = "message")))
        assertNull("no category", contactOf(dmNotification(category = null)))
        assertNull("the ring itself", contactOf(dmNotification(category = "call")))
        assertNull("the app-PIN fallback", contactOf(pinFallback))
        assertNull("a group room", contactOf(dmNotification(room = groupRoom, senders = listOf(mamma, pappa), group = true)))
        assertNull("a group room, even by a learned room", contactOf(dmNotification(group = true)))
        assertNull("no group flag", contactOf(dmNotification(group = null)))
        assertNull("a stranger", contactOf(dmNotification(room = "!stranger:example.com", senders = listOf(stranger))))
        assertNull("only the kid's own key", contactOf(dmNotification(senders = listOf(kid)), rooms = emptyMap()))
        assertNull("not a room ID, no sender", contactOf(dmNotification(room = "not-a-room", senders = listOf(null))))
        // Bestemor's Message button is SMS: her Element MXID (if any) is no phone-book Element contact.
        val smsRules = CallPolicyState.Managed(CallRules(callsEnabled = true, contacts = contacts.map { if (it.id == 2L) it.copy(messageApp = "sms") else it }))
        assertNull(contactOf(dmNotification(), smsRules))
        assertNull(contactOf(dmNotification(), CallPolicyState.Unmanaged))
        assertNull(contactOf(dmNotification(), CallPolicyState.UnknownFailClosed))
    }

    // ---- the listener's memory ----------------------------------------------------------------------------------------

    private val key = "0|io.element.android.x|1|$pappaRoom|10123"

    @Test
    fun `a post starts a call, an update keeps its time, message or a removal ends it`() {
        val started = afterElementPost(emptyMap(), ElementPost(key, pappa, 1_000))
        assertEquals(mapOf(key to ElementMissedCall(pappa, 1_000)), started)
        // A new message in the room while the call is still pending: the same call, missed at 1 000.
        assertEquals(started, afterElementPost(started, ElementPost(key, pappa, 2_000)))
        // The category back at `message`: gone.
        assertEquals(emptyMap<String, ElementMissedCall>(), afterElementPost(started, ElementPost(key, null, 3_000)))
        // The kid opened the room in Element X: the notification is cancelled.
        assertEquals(emptyMap<String, ElementMissedCall>(), afterElementRemoval(started, key))
        // Another app's post or removal changes nothing (the same map, so no listener runs).
        assertTrue(started === afterElementPost(started, ElementPost("0|other|1|null|10123", null, 3_000)))
        assertTrue(started === afterElementRemoval(started, "0|other|1|null|10123"))
        // The room's contact changed (another MXID learned): a new call from the post.
        assertEquals(mapOf(key to ElementMissedCall(mamma, 4_000)), afterElementPost(started, ElementPost(key, mamma, 4_000)))
    }

    @Test
    fun `the sweep keeps what is still there and forgets the rest`() {
        val threadKey = "0|io.element.android.x|1|$pappaRoom|\$thread-root|10123"
        val current = mapOf(key to ElementMissedCall(pappa, 1_000), threadKey to ElementMissedCall(pappa, 1_500))
        val swept = afterElementSweep(
            current,
            listOf(
                ElementPost(key, pappa, 9_000), // still there: missed at 1 000
                ElementPost("0|io.element.android.x|1|!mamma-dm:example.net|10123", mamma, 8_000), // new since
                ElementPost("0|other|1|null|7", null, 8_500), // anything else
            ),
        )
        assertEquals(
            mapOf(key to ElementMissedCall(pappa, 1_000), "0|io.element.android.x|1|!mamma-dm:example.net|10123" to ElementMissedCall(mamma, 8_000)),
            swept,
        )
        assertEquals(emptyMap<String, ElementMissedCall>(), afterElementSweep(current, emptyList()))
    }

    // ---- the count on the card and the badge ----------------------------------------------------------------------------

    @Test
    fun `each room's missed call counts once after the contact's seen mark`() {
        val calls = listOf(ElementMissedCall(pappa, 1_000), ElementMissedCall(pappa, 1_500), ElementMissedCall(mamma, 900))
        assertEquals(
            mapOf("+4790000002" to MissedSummary(2, 1_500), "+4790000001" to MissedSummary(1, 900)),
            elementMissedSummaries(calls, rules, emptyMap()),
        )
        // Pappa's sheet opened at 1 200: only the call after it.
        assertEquals(MissedSummary(1, 1_500), elementMissedSummaries(calls, rules, mapOf("+4790000002" to 1_200))["+4790000002"])
        assertNull(elementMissedSummaries(calls, rules, mapOf("+4790000002" to 1_500))["+4790000002"])
        // A contact that left the phone book (or stopped using Element X) counts nowhere.
        val withoutPappa = rules.copy(contacts = contacts.filterNot { it.id == 2L })
        assertEquals(setOf("+4790000001"), elementMissedSummaries(calls, withoutPappa, emptyMap()).keys)
        val inboundOnly = rules.copy(contacts = contacts.map { if (it.id == 2L) it.copy(outbound = false) else it })
        assertEquals(setOf("+4790000001"), elementMissedSummaries(calls, inboundOnly, emptyMap()).keys)
    }

    @Test
    fun `the badge is the phone's own missed calls and the Element X ones together`() {
        val phone = mapOf("+4790000002" to MissedSummary(2, 300), "+4790000003" to MissedSummary(1, 200))
        val element = mapOf("+4790000002" to MissedSummary(1, 400), "+4790000001" to MissedSummary(1, 500))
        val both = contactMissed(phone, element)
        assertEquals(setOf("+4790000001", "+4790000002", "+4790000003"), both.keys)
        assertEquals(ContactMissed(MissedSummary(2, 300), MissedSummary(1, 400)), both["+4790000002"])
        assertEquals(3, both.getValue("+4790000002").count)
        assertEquals(1, both.getValue("+4790000001").count)
        assertNull(both.getValue("+4790000001").phone)
        assertEquals(emptyMap<String, ContactMissed>(), contactMissed(emptyMap(), emptyMap()))
    }

    @Test
    fun `an unanswered call from Pappa, from the ring to the kid opening his card`() {
        var memory = emptyMap<String, ElementMissedCall>()
        fun post(n: RoomNotification, at: Long) {
            memory = afterElementPost(memory, ElementPost(key, contactOf(n), at))
        }
        fun badge(seen: Map<String, Long> = emptyMap()) = elementMissedSummaries(memory.values, rules, seen)["+4790000002"]?.count ?: 0

        post(dmNotification(category = "message", senders = listOf(pappa)), 500) // "Hei!"
        assertEquals(0, badge())
        post(dmNotification(senders = listOf(pappa, pappa)), 1_000) // the call nobody answered
        assertEquals(1, badge())
        post(dmNotification(senders = listOf(pappa, pappa, pappa)), 2_000) // "Ring meg" - still pending
        assertEquals(1, badge())
        assertEquals(1_000L, memory.getValue(key).sinceMs)
        assertEquals("the kid opened Pappa's card at 3 000", 0, badge(mapOf("+4790000002" to 3_000)))
        post(dmNotification(category = "message", senders = listOf(pappa)), 4_000) // the call's event dropped out
        assertTrue(memory.isEmpty())
        post(dmNotification(senders = listOf(pappa, pappa)), 5_000) // a new missed call
        assertEquals(1, badge(mapOf("+4790000002" to 3_000)))
        memory = afterElementRemoval(memory, key) // read in Element X
        assertEquals(0, badge())
    }

    @Test
    fun `a change of the Element contacts judges the active notifications again`() {
        assertFalse("the same rules", elementMissedRejudgeDue(managed, CallPolicyState.Managed(rules.copy(smsEnabled = true))))
        // A new name or number for Pappa: the summaries look him up when they are read.
        assertFalse(elementMissedRejudgeDue(managed, CallPolicyState.Managed(rules.copy(contacts = contacts.map { if (it.id == 2L) it.copy(name = "Far", number = "+4790000009") else it }))))
        val withoutPappa = CallPolicyState.Managed(rules.copy(contacts = contacts.filterNot { it.id == 2L }))
        assertTrue("Pappa added after the call", elementMissedRejudgeDue(withoutPappa, managed))
        assertTrue("Pappa left", elementMissedRejudgeDue(managed, withoutPappa))
        assertTrue("Bestemor moved to Element X", elementMissedRejudgeDue(managed, CallPolicyState.Managed(rules.copy(contacts = contacts.map { if (it.id == 3L) it.copy(messageApp = "element", messageAddress = "@bestemor:example.org") else it }))))
        assertTrue("another MXID", elementMissedRejudgeDue(managed, CallPolicyState.Managed(rules.copy(contacts = contacts.map { if (it.id == 2L) it.copy(messageAddress = "@pappa2:example.org") else it }))))
        assertTrue("the rules read at last", elementMissedRejudgeDue(CallPolicyState.UnknownFailClosed, managed))
        assertTrue("unmanaged", elementMissedRejudgeDue(managed, CallPolicyState.Unmanaged))
        assertFalse(elementMissedRejudgeDue(CallPolicyState.UnknownFailClosed, CallPolicyState.Unmanaged))
        // What the sweep then does with a post the old rules judged as nobody's: a mark from the post's time.
        val judgedBefore = afterElementPost(emptyMap(), ElementPost(key, contactOf(dmNotification(), withoutPappa), 1_000))
        assertTrue(judgedBefore.isEmpty())
        assertEquals(mapOf(key to ElementMissedCall(pappa, 1_000)), afterElementSweep(judgedBefore, listOf(ElementPost(key, contactOf(dmNotification(), managed), 1_000))))
    }

    @Test
    fun `the redacted strings name no MXID and no room`() {
        assertEquals("ElementMissedCall(sinceMs=1000)", ElementMissedCall(pappa, 1_000).toString())
        val post = ElementPost(key, pappa, 2_000).toString()
        assertEquals("ElementPost(contact=true, postTimeMs=2000)", post)
        assertFalse(pappaRoom in post || pappa in post)
    }

    // ---- privacy -------------------------------------------------------------------------------------------------------

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun code(name: String): String = withoutComments(sources.walkTopDown().first { it.name == name }.readText())

    private fun withoutComments(source: String): String =
        source.replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `missed Element X calls stay in memory - never stored, logged or sent`() {
        assertEquals(setOf("mxid", "sinceMs"), ElementMissedCall::class.java.declaredFields.filterNot { it.isSynthetic }.map { it.name }.toSet())
        for (file in listOf("ElementMissedCalls.kt", "ElementMissedCallStore.kt")) {
            val code = code(file)
            for (shape in listOf("SharedPreferences", "getSharedPreferences", "Log.", "println", "System.out", "System.err", "File(")) {
                assertFalse("$file: $shape", shape in code)
            }
        }
        // Every main source file: only these touch the calls' MXIDs (the rest only listen to the store or ask it to
        // judge again - nothing reaches the sync, the API or a report).
        val allowed = setOf("ElementMissedCalls.kt", "ElementMissedCallStore.kt", "MissedCallsRepo.kt", "BadgeListenerService.kt", "ElementDmReader.kt")
        val carriers = Regex("""\bElementMissedCall\b|\bElementPost\b|ElementMissedCallStore\.calls\b|\belementMissedSummaries\b|\belementMissedCallContact\b|ElementDmReader\.missedCallContact\b|\bafterElement[A-Za-z]+\b""")
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .filter { carriers.containsMatchIn(withoutComments(it.readText())) }
            .map { it.name }
            .toList()
        assertTrue("Found in $offenders", offenders.isEmpty())
        // The listener hands over the key, the reader's contact and the post time - nothing else.
        assertTrue(code("BadgeListenerService.kt").contains("ElementPost(sbn.key, ElementDmReader.missedCallContact(this, sbn), sbn.postTime)"))
    }
}
