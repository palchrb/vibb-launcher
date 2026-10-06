package com.kidslauncher.mdm.calls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 15 (the learned variant): the learning rule, the room-ID grammar, the link, pruning,
 * the storage codec and the privacy invariants of the notification reader. */
class ElementRoomsTest {

    private val element = MessagePackages.ELEMENT_X
    private val kid = "@kid:vibb.me"
    private val mamma = "@mamma:matrix.org"
    private val pappa = "@pappa:vibb.me"

    /** Live (emulator, 2026-10-06): a v12 room ID as Element X's DM notification tag. */
    private val dmRoom = "!xrLg_-U0V0jULeIm_fvol9NEvBHNxqHrSP5K1R7BUpI"

    private val contacts = listOf(
        RuleContact(1, "Mamma", "+4790000001", inbound = true, outbound = true, messageApp = "element", messageAddress = mamma),
        RuleContact(2, "Pappa", "+4790000002", inbound = true, outbound = true, messageApp = "element", messageAddress = pappa),
        RuleContact(3, "Bestemor", "+4790000003", inbound = true, outbound = true, messageApp = "sms", messageAddress = "@bestemor:x.org"),
        // Inbound only: not in the phone book, so no Message button and nothing learned.
        RuleContact(4, "Trener", "+4790000004", inbound = true, outbound = false, messageApp = "element", messageAddress = "@trener:x.org"),
    )
    private val managed = CallPolicyState.Managed(CallRules(callsEnabled = true, contacts = contacts))

    private fun dm(
        tag: String? = dmRoom,
        selfKey: String? = kid,
        senderKeys: List<String?> = listOf(mamma, mamma),
        group: Boolean? = false,
    ) = ElementDmFacts(tag, selfKey, senderKeys, group)

    private fun learned(contact: String = mamma, room: String = dmRoom) = LearnedElementRoom(contact, ElementRoom(kid, room))

    // ---- privacy: exact fields ----------------------------------------------------------------

    @Test
    fun `the facts type has exactly the four fields the decision names`() {
        assertEquals(setOf("tag", "selfKey", "senderKeys", "group"), fields(ElementDmFacts::class.java))
        // Stored: only contact MXID -> (session, room).
        assertEquals(setOf("session", "room"), fields(ElementRoom::class.java))
        assertEquals(setOf("contactMxid", "room"), fields(LearnedElementRoom::class.java))
    }

    private fun fields(type: Class<*>) =
        type.declaredFields.filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()

    // ---- the learning rule ----------------------------------------------------------------------

    @Test
    fun `a DM from a phone-book contact teaches its room and the kid's session`() {
        assertEquals(learned(), learnElementRoom(element, dm(), managed))
        // Replies by the kid have no sender_person and aren't listed: still the contact's DM.
        assertEquals(learned(), learnElementRoom(element, dm(senderKeys = listOf(mamma)), managed))
        assertEquals(learned(contact = pappa), learnElementRoom(element, dm(senderKeys = listOf(pappa)), managed))
        // A room ID with a server part (room versions before 12).
        assertEquals(learned(room = "!AbCdEf:matrix.org"), learnElementRoom(element, dm(tag = "!AbCdEf:matrix.org"), managed))
    }

    @Test
    fun `only Element X's own notifications count`() {
        for (other in listOf("io.element.android.x.debug", "im.vector.app", "com.example.fake", "")) {
            assertNull(other, learnElementRoom(other, dm(), managed))
        }
    }

    @Test
    fun `group conversations and threads teach nothing`() {
        assertNull(learnElementRoom(element, dm(group = true), managed))
        // A missing flag is not "false".
        assertNull(learnElementRoom(element, dm(group = null), managed))
        // A thread's tag is `<room>|<thread>`.
        assertNull(learnElementRoom(element, dm(tag = "$dmRoom|\$threadRoot"), managed))
    }

    @Test
    fun `the tag must be a room ID`() {
        for (tag in listOf(null, "", "#alias:x.org", "!short", "@mamma:matrix.org", "summary", "$dmRoom ", "!a b:x.org")) {
            assertNull("$tag", learnElementRoom(element, dm(tag = tag), managed))
        }
    }

    @Test
    fun `the session must be a Matrix ID`() {
        for (self in listOf(null, "", "kid", "Kid", "@Kid:vibb.me", "@kid:vibb.me/x")) {
            assertNull("$self", learnElementRoom(element, dm(selfKey = self), managed))
        }
    }

    @Test
    fun `every sender must be the same phone-book contact`() {
        // Only the kid's own messages: no contact to learn for.
        assertNull(learnElementRoom(element, dm(senderKeys = emptyList()), managed))
        // A sender_person without a key.
        assertNull(learnElementRoom(element, dm(senderKeys = listOf(mamma, null)), managed))
        // Element's mention/reply person, another sender, two contacts, the kid itself.
        assertNull(learnElementRoom(element, dm(senderKeys = listOf("mention-or-reply:$mamma")), managed))
        assertNull(learnElementRoom(element, dm(senderKeys = listOf(mamma, "@stranger:x.org")), managed))
        assertNull(learnElementRoom(element, dm(senderKeys = listOf(mamma, pappa)), managed))
        assertNull(learnElementRoom(element, dm(senderKeys = listOf(kid)), managed))
        // Not a contact, a contact on SMS, an inbound-only contact (not in the phone book).
        assertNull(learnElementRoom(element, dm(senderKeys = listOf("@stranger:x.org")), managed))
        assertNull(learnElementRoom(element, dm(senderKeys = listOf("@bestemor:x.org")), managed))
        assertNull(learnElementRoom(element, dm(senderKeys = listOf("@trener:x.org")), managed))
        // MXIDs match exactly (the grammar is lower-case; no case folding).
        assertNull(learnElementRoom(element, dm(senderKeys = listOf("@Mamma:matrix.org")), managed))
    }

    @Test
    fun `nothing is learned without managed rules`() {
        assertNull(learnElementRoom(element, dm(), CallPolicyState.Unmanaged))
        assertNull(learnElementRoom(element, dm(), CallPolicyState.UnknownFailClosed))
    }

    // ---- grammar and link ---------------------------------------------------------------------------

    @Test
    fun `room ID grammar`() {
        assertTrue(isElementRoomId(dmRoom))
        assertEquals(44, dmRoom.length)
        assertTrue(isElementRoomId("!AbCdEf:matrix.org"))
        assertTrue(isElementRoomId("!a.b_c=d~e+f/g-h:example.org:8448"))
        assertTrue(isElementRoomId("!abc:[::1]:8448"))
        for (bad in listOf(
            null, "", "!", dmRoom.dropLast(1), "${dmRoom}A", "!${"+".repeat(43)}", "#room:x.org", "abc:x.org",
            "!abc", "!abc:", "!ab|c:x.org", "$dmRoom|\$thread", "!a?b:x.org", "!a#b:x.org", "!a&b:x.org", "!a b:x.org",
            "!abc:x.org/path", "!" + "a".repeat(250) + ":x.org",
        )) {
            assertFalse("$bad", isElementRoomId(bad))
        }
    }

    @Test
    fun `the link is Element X's own open link, every segment fully encoded`() {
        // Verified live: this exact link opened the DM.
        assertEquals(
            "elementx://open/%40dockerbot%3Avibb.me/%21xrLg_-U0V0jULeIm_fvol9NEvBHNxqHrSP5K1R7BUpI",
            elementRoomUri(ElementRoom("@dockerbot:vibb.me", dmRoom)),
        )
        // A raw `/` would split a segment and a raw `+` would become a space (QA #5).
        assertEquals(
            "elementx://open/%40a%2Fb%2Bc%3Dd%3Ax.org/%21r%2Fs%2Bt%3Ax.org%3A8448",
            elementRoomUri(ElementRoom("@a/b+c=d:x.org", "!r/s+t:x.org:8448")),
        )
        assertNull(elementRoomUri(ElementRoom("kid", dmRoom)))
        assertNull(elementRoomUri(ElementRoom(kid, "$dmRoom|\$thread")))
    }

    // ---- storage ----------------------------------------------------------------------------------

    @Test
    fun `pairs go when the contact leaves the phone book, stops using Element or changes MXID`() {
        val stored = mapOf(
            mamma to ElementRoom(kid, dmRoom),
            pappa to ElementRoom(kid, "!AbCdEf:matrix.org"),
        )
        assertEquals(stored, pruneElementRooms(stored, managed))
        val withoutPappa = CallPolicyState.Managed(CallRules(contacts = contacts.filter { it.id != 2L }))
        assertEquals(setOf(mamma), pruneElementRooms(stored, withoutPappa).keys)
        val pappaOnSms = CallPolicyState.Managed(CallRules(contacts = contacts.map { if (it.id == 2L) it.copy(messageApp = "sms") else it }))
        assertEquals(setOf(mamma), pruneElementRooms(stored, pappaOnSms).keys)
        val newMxid = CallPolicyState.Managed(CallRules(contacts = contacts.map { if (it.id == 2L) it.copy(messageAddress = "@pappa:new.org") else it }))
        assertEquals(setOf(mamma), pruneElementRooms(stored, newMxid).keys)
        val notCallable = CallPolicyState.Managed(CallRules(contacts = contacts.map { if (it.id == 2L) it.copy(outbound = false) else it }))
        assertEquals(setOf(mamma), pruneElementRooms(stored, notCallable).keys)
        // Unmanaged calls have no phone book; unknown rules keep what is there.
        assertEquals(emptyMap<String, ElementRoom>(), pruneElementRooms(stored, CallPolicyState.Unmanaged))
        assertEquals(stored, pruneElementRooms(stored, CallPolicyState.UnknownFailClosed))
    }

    @Test
    fun `a newer notification replaces the pair`() {
        val old = mapOf(mamma to ElementRoom("@kid:old.org", "!AbCdEf:matrix.org"), "@gone:x.org" to ElementRoom(kid, dmRoom))
        assertEquals(mapOf(mamma to ElementRoom(kid, dmRoom)), withLearnedRoom(old, learned(), managed))
    }

    @Test
    fun `the codec keeps only valid pairs`() {
        val rooms = mapOf(mamma to ElementRoom(kid, dmRoom), pappa to ElementRoom(kid, "!AbCdEf:matrix.org"))
        assertEquals(rooms, decodeElementRooms(encodeElementRooms(rooms)))
        assertEquals(emptyMap<String, ElementRoom>(), decodeElementRooms(null))
        assertEquals(emptyMap<String, ElementRoom>(), decodeElementRooms("{"))
        assertEquals(emptyMap<String, ElementRoom>(), decodeElementRooms("[]"))
        val mixed = """{"$mamma":{"session":"$kid","room":"$dmRoom"},"x":{"session":"$kid","room":"$dmRoom"},""" +
            """"$pappa":{"session":"$kid","room":"#alias:x.org"}}"""
        assertEquals(mapOf(mamma to ElementRoom(kid, dmRoom)), decodeElementRooms(mixed))
    }

    // ---- privacy: the reader and the store (source scans) -----------------------------------------

    private val sources = listOf("src/main/java", "app/src/main/java").map(::File).first { it.isDirectory }

    private fun source(name: String): String = sources.walkTopDown().first { it.name == name }.readText()

    /** The code without comments (KDoc may name what is *not* read). */
    private fun code(name: String): String =
        source(name).replace(Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("""//[^\n]*"""), "")

    @Test
    fun `the reader never reads text, titles or names and never logs`() {
        for (file in listOf("ElementDmReader.kt", "BadgeListenerService.kt")) {
            val code = code(file)
            val withoutContext = code.replace(Regex("context", RegexOption.IGNORE_CASE), "")
            assertFalse("$file reads text", Regex("text", RegexOption.IGNORE_CASE).containsMatchIn(withoutContext))
            for (forbidden in listOf("title", "MessagingStyle", "getCharSequence", "getString", ".name", "shortcut", "tickerText")) {
                assertFalse("$file: $forbidden", code.contains(forbidden, ignoreCase = true))
            }
        }
        val reader = code("ElementDmReader.kt")
        assertFalse("ElementDmReader logs", "Log" in reader)
        // Only these extras and bundle keys.
        val extras = Regex("""Notification\.EXTRA_[A-Z_]+""").findAll(reader).map { it.value }.toSet()
        assertEquals(
            setOf("Notification.EXTRA_MESSAGES", "Notification.EXTRA_MESSAGING_PERSON", "Notification.EXTRA_IS_GROUP_CONVERSATION"),
            extras,
        )
        assertEquals(setOf("\"sender_person\""), Regex(""""[^"]*"""").findAll(reader).map { it.value }.toSet())
        // The listener itself still reads no extras: only the reader does.
        assertFalse("extras" in code("BadgeListenerService.kt"))
    }

    @Test
    fun `learned rooms are never logged or sent to the server`() {
        for (file in listOf("ElementRooms.kt", "ElementRoomStore.kt", "ElementDmReader.kt")) {
            assertFalse("$file logs", Regex("""\bLog\.""").containsMatchIn(code(file)))
        }
        // The sync, the API, the DTOs and the call-state report never see them.
        val offenders = sources.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { "/mdm/server/" in it.invariantSeparatorsPath || "/mdm/push/" in it.invariantSeparatorsPath || it.name == "CallStateReport.kt" }
            .filter { "ElementRoom" in it.readText() || "ElementDm" in it.readText() }
            .map { it.name }
            .toList()
        assertTrue("Found in $offenders", offenders.isEmpty())
        // The open link is never logged where it is started.
        val sheet = code("ContactSheet.kt")
        assertFalse(Regex("""\bLog\.""").containsMatchIn(sheet.substringAfter("fun openMessage(").substringBefore("\n    }\n")))
    }
}
