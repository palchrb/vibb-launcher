package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.sms.engine.Delivery
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.EmojiList
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.sms.engine.MessageRow
import com.kidslauncher.mdm.sms.engine.MessageVerdict
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.ParsedReaction
import com.kidslauncher.mdm.sms.engine.PartState
import com.kidslauncher.mdm.sms.engine.Reaction
import com.kidslauncher.mdm.sms.engine.ReactionCandidate
import com.kidslauncher.mdm.sms.engine.ReactionOp
import com.kidslauncher.mdm.sms.engine.ReactionTarget
import com.kidslauncher.mdm.sms.engine.Reactions
import com.kidslauncher.mdm.sms.engine.SenderKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** The pinned emoji list (design 81 D2), read from the launcher's asset as the phone reads it. */
object EmojiAsset {
    val file: File = listOf("src/main/assets/sms/emoji-16.0.txt", "app/src/main/assets/sms/emoji-16.0.txt").map(::File).first { it.isFile }
    val list: EmojiList by lazy { EmojiList.parse(file.readText()) }
}

/**
 * Design 81 §7: the launcher-only vectors `sms_reactions.json` (the server never sees bodies) - every form of §1 with
 * its source, the parse (D2), which messages may be one (H1), the bubble a reaction names (D3), the reactions in effect
 * (D4) and the bodies the kid sends (D8), each of which parses back to the same reaction. And the emoji list's SHA-256.
 */
class SmsReactionsTest {
    private val vectors = Json.parseToJsonElement(javaClass.classLoader!!.getResource("sms_reactions.json")!!.readText()).jsonObject
    private val list = EmojiAsset.list

    private fun rows(name: String) = vectors[name]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String): String? = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun op(text: String) = ReactionOp.valueOf(text.uppercase())

    private val likeHei = ParsedReaction("👍", "👍", ReactionOp.ADD, ReactionTarget.Quote("Hei", false))

    @Test
    fun `the emoji list is the pinned one, and the six are its own fully-qualified forms`() {
        val sha = MessageDigest.getInstance("SHA-256").digest(EmojiAsset.file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 0xff) }
        assertEquals(vectors["emoji_list_sha256"]!!.jsonPrimitive.content, sha)
        for (emoji in Reactions.SIX) assertEquals(emoji, list.canonical(emoji))
        // D13's groups: nine, no Component, no skin tones, every one fully-qualified and within D2's bound.
        assertEquals(9, list.groups.size)
        assertTrue(list.groups.none { it.name == "Component" })
        val all = list.groups.flatMap { it.emoji }
        assertTrue(all.size in 1800..2000)
        assertTrue(all.none { e -> e.codePoints().anyMatch { it in 0x1F3FB..0x1F3FF } })
        assertTrue(all.all { list.canonical(it) == it && it.length <= EmojiList.MAX_UNITS })
    }

    @Test
    fun `the forms are the vectors' rows, and each has a source`() {
        val table = Reactions.FORMS.map { listOf(it.lang, it.op.name.lowercase(), it.emoji, it.template, it.send) }
        val pinned = rows("forms").map { row ->
            val source = row["source"]!!.jsonArray.map { it.jsonPrimitive.content }
            assertTrue(row.toString(), source.isNotEmpty() && source.all { it in setOf("apple", "wire", "capture") })
            if ("apple" in source) assertNotNull(row.str("apple"))
            if ("wire" in source) assertNotNull(row.str("wire"))
            listOf(row.str("lang"), row.str("op"), row.str("emoji"), row.str("form"), row["send"]!!.jsonPrimitive.boolean)
        }
        assertEquals(pinned, table)
        // One form to send per language, op, emoji and noun.
        val sent = Reactions.FORMS.filter { it.send }.groupBy { listOf(it.lang, it.op, it.emoji, it.image) }
        assertTrue(sent.values.all { it.size == 1 })
        assertEquals(2 * 2 * 7 * 2, sent.size)
    }

    private fun expected(result: JsonElement?): ParsedReaction? {
        if (result == null || result is JsonNull) return null
        val row = result.jsonObject
        val emoji = row.str("emoji")!!
        val target = if (row["image"] != null) ReactionTarget.Image else ReactionTarget.Quote(row.str("quote")!!, row["cut"]!!.jsonPrimitive.boolean)
        return ParsedReaction(emoji, EmojiList.key(emoji), op(row.str("op")!!), target)
    }

    @Test
    fun `without the emoji list the six are still read, nothing else (code QA N1)`() {
        for (emoji in Reactions.SIX) assertEquals(emoji, Reactions.emoji(emoji, EmojiList.EMPTY))
        assertEquals("❤️", Reactions.emoji("❤", EmojiList.EMPTY))
        assertNull(Reactions.emoji("🎉", EmojiList.EMPTY))
        assertEquals(likeHei, Reactions.parse("Reacted 👍 to “Hei”", EmojiList.EMPTY))
        assertNull(Reactions.parse("Reacted 🎉 to “Hei”", EmojiList.EMPTY))
    }

    @Test
    fun `every parse vector`() {
        for (row in rows("parse")) assertEquals(row.str("case"), expected(row["result"]), Reactions.parse(row.str("body")!!, list))
    }

    @Test
    fun `every candidate vector (H1)`() {
        for (row in rows("candidate")) {
            val got = Reactions.candidate(row["texts"]!!.jsonPrimitive.int, row["other"]!!.jsonPrimitive.int)
            assertEquals(row.str("case"), row["candidate"]!!.jsonPrimitive.boolean, got)
        }
    }

    @Test
    fun `every match vector (D3)`() {
        for (row in rows("match")) {
            val parsed = Reactions.parse(row.str("body")!!, list)
            assertNotNull(row.str("case"), parsed)
            val candidates = row["candidates"]!!.jsonArray.map { it.jsonObject }.map { c ->
                ReactionCandidate(
                    id = c.str("id")!!,
                    sender = c.str("sender"),
                    body = c.str("body"),
                    pictures = c["pictures"]!!.jsonArray.map { PartState.valueOf(it.jsonPrimitive.content) },
                    reactions = c["reactions"]!!.jsonArray.map { it.jsonObject }.map {
                        val emoji = it.str("emoji")!!
                        Reaction(MessageId("r"), emoji, EmojiList.key(emoji), it.str("by"), pending = false)
                    },
                )
            }
            assertEquals(row.str("case"), row.str("target"), Reactions.resolve(parsed!!, row.str("reactor")!!, candidates))
        }
    }

    @Test
    fun `every state vector (D4)`() {
        for (row in rows("state")) {
            val rows = row["rows"]!!.jsonArray.map { it.jsonObject }.mapIndexed { i, r ->
                val emoji = r.str("emoji")!!
                val direction = Direction.valueOf(r.str("direction")!!.uppercase())
                MessageRow(
                    id = "r$i", conversationId = "c", seq = i + 1L, direction = direction, sender = r.str("sender"), senderKind = SenderKind.NUMBER,
                    unknownSender = false, at = 0, sentAt = null, body = "", delivery = Delivery.valueOf(r.str("delivery")!!), failure = null,
                    read = true, clientId = null, origin = Origin.ENGINE, verdict = MessageVerdict.SHOWN, subId = null,
                    reactionTo = "target", reactionEmoji = emoji, reactionKey = EmojiList.key(emoji), reactionOp = op(r.str("op")!!),
                )
            }
            val want = row["reactions"]!!.jsonArray.map { it.jsonObject }.map { Triple(it.str("by"), it.str("emoji"), it["pending"]!!.jsonPrimitive.boolean) }
            assertEquals(row.str("case"), want, Reactions.state(rows.shuffled(java.util.Random(7))).map { Triple(it.by, it.emoji, it.pending) })
        }
    }

    @Test
    fun `every send vector, and each parses back to the same reaction`() {
        for (row in rows("send")) {
            val case = row.str("case")
            val emoji = row.str("emoji")!!
            val op = op(row.str("op")!!)
            val quote = row.str("quote")
            val body = Reactions.format(emoji, op, quote, row.str("lang")!!)
            assertEquals(case, row.str("body"), body)
            assertTrue(case, body.length <= Reactions.SEND_MAX)
            // No half of a surrogate pair is ever sent.
            assertTrue(case, body.indices.none { body[it].isHighSurrogate() && (it + 1 == body.length || !body[it + 1].isLowSurrogate()) })
            val back = Reactions.parse(body, list)
            assertNotNull(case, back)
            assertEquals(case, op, back!!.op)
            assertEquals(case, EmojiList.key(emoji), back.key)
            when (val target = back.target) {
                ReactionTarget.Image -> assertNull(case, quote)
                is ReactionTarget.Quote -> if (target.cut) {
                    assertTrue(case, Reactions.normalise(quote!!).startsWith(target.text.removeSuffix(Reactions.CUT)))
                } else {
                    assertEquals(case, Reactions.normalise(quote!!), target.text)
                }
            }
        }
    }
}
