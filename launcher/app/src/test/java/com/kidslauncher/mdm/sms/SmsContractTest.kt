package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.appdisplay.AppDisplayEntry
import com.kidslauncher.mdm.appdisplay.AppGlyphs
import com.kidslauncher.mdm.appdisplay.BUILTIN_MESSAGES
import com.kidslauncher.mdm.appdisplay.BUILTIN_PREFIX
import com.kidslauncher.mdm.appdisplay.MessagesLook
import com.kidslauncher.mdm.appdisplay.appDisplayMap
import com.kidslauncher.mdm.appdisplay.appEntry
import com.kidslauncher.mdm.appdisplay.builtinEntry
import com.kidslauncher.mdm.appdisplay.messagesLook
import com.kidslauncher.mdm.appdisplay.onHomeListable
import com.kidslauncher.mdm.badges.lockGlancePolicy
import com.kidslauncher.mdm.calls.CallPolicyState
import com.kidslauncher.mdm.calls.CallRules
import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.channelModes
import com.kidslauncher.mdm.calls.forCalls
import com.kidslauncher.mdm.calls.smsMode
import com.kidslauncher.mdm.calls.toRules
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.PolicyMmscHosts
import com.kidslauncher.mdm.server.dto.SmsBlockedCounts
import com.kidslauncher.mdm.server.dto.SmsHeldAnswer
import com.kidslauncher.mdm.server.dto.SmsHeldBody
import com.kidslauncher.mdm.server.dto.SmsHeldRefusal
import com.kidslauncher.mdm.server.dto.SmsHoldFull
import com.kidslauncher.mdm.server.dto.SmsOwnLearned
import com.kidslauncher.mdm.server.dto.SmsOwnNumber
import com.kidslauncher.mdm.server.dto.SmsState
import com.kidslauncher.mdm.server.smsRestricted
import com.kidslauncher.mdm.sms.engine.SenderClean
import com.kidslauncher.mdm.sms.engine.SenderKind
import com.kidslauncher.mdm.sms.engine.SmsNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 77 §7: `sms_contract.json`, byte-identical with `server/testdata/` (the server's `sms::tests` run the same
 * rows). The launcher's side: the old server's keys read by D2's table, the modes and the old-launcher view of them,
 * the policy the server sends, the exact `sms_state` and `sms-held` body, the answers, the cleaning and the names.
 */
class SmsContractTest {
    private val text = javaClass.classLoader!!.getResource("sms_contract.json")!!.readText()
    private val contract = Json.parseToJsonElement(text).jsonObject

    private fun rows(key: String) = contract[key]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.str(key: String) = this[key]!!.jsonPrimitive.content

    private fun JsonObject.bool(key: String) = this[key]!!.jsonPrimitive.boolean

    @Test
    fun `the contract is identical to the servers copy`() {
        val serverCopy = File("../../server/testdata/sms_contract.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }

    @Test
    fun `the capability is the one the launcher claims`() {
        assertEquals(contract.str("capability"), SMS_ENGINE_CAPABILITY)
        assertTrue(SMS_ENGINE_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
    }

    @Test
    fun `an older server's switches read as D2's migration table`() {
        for (row in rows("migration")) {
            val policy = CallPolicy(managed = row.bool("calls_managed"), callsEnabled = row.bool("calls_enabled"), smsEnabled = row.bool("sms_enabled"))
            assertEquals(row.toString(), ChannelMode.of(row.str("calls_mode")) to ChannelMode.of(row.str("sms_mode")), channelModes(policy))
        }
    }

    @Test
    fun `each mode pair - the new launcher applies it, the derived old keys never loosen it`() {
        for (row in rows("modes")) {
            val calls = ChannelMode.of(row.str("calls_mode"))!!
            val sms = ChannelMode.of(row.str("sms_mode"))!!
            val wire = """{"managed":${row.bool("managed")},"calls_enabled":${row.bool("calls_enabled")},"sms_enabled":${row.bool("sms_enabled")}"""
            val new = ServerJson.decodeFromString(CallPolicy.serializer(), "$wire,\"calls_mode\":\"${calls.wire}\",\"sms_mode\":\"${sms.wire}\"}")
            assertEquals(row.toString(), calls to sms, channelModes(new))
            val state = if (calls == ChannelMode.UNMANAGED && sms == ChannelMode.UNMANAGED) CallPolicyState.Unmanaged else CallPolicyState.Managed(new.toRules())
            assertEquals(row.toString(), if (calls == ChannelMode.UNMANAGED) CallPolicyState.Unmanaged else state, state.forCalls())
            assertEquals(row.toString(), sms, state.smsMode)
            // R2-L7: what an older launcher makes of the old keys alone (D2's table) is never looser.
            val (oldCalls, oldSms) = channelModes(ServerJson.decodeFromString(CallPolicy.serializer(), "$wire}"))
            assertTrue(row.toString(), looser(oldCalls, calls).not())
            assertTrue(row.toString(), looser(oldSms, sms).not())
            // And an older launcher never filters SMS: "managed" there is DISALLOW_SMS, messages lost (as the PWA says).
            if (sms != ChannelMode.UNMANAGED) assertTrue(smsRestricted(oldSms, roleHeld = false))
        }
        // The pinned row: calls not managed, SMS managed reads as managed, calls on, SMS off.
        val row = rows("modes").single { it.str("calls_mode") == "unmanaged" && it.str("sms_mode") == "managed" }
        assertEquals(listOf(true, true, false), listOf(row.bool("managed"), row.bool("calls_enabled"), row.bool("sms_enabled")))
    }

    private fun looser(applied: ChannelMode, asked: ChannelMode): Boolean = rank(applied) > rank(asked)

    private fun rank(mode: ChannelMode) = when (mode) {
        ChannelMode.OFF -> 0
        ChannelMode.MANAGED -> 1
        ChannelMode.UNMANAGED -> 2
    }

    @Test
    fun `the policy the server sends decodes into the rules`() {
        val policy = contract["policy"]!!.jsonObject
        val callPolicy = ServerJson.decodeFromString(CallPolicy.serializer(), policy["call_policy"].toString())
        val rules = callPolicy.toRules()
        assertEquals(ChannelMode.MANAGED, rules.callsMode)
        assertEquals(ChannelMode.MANAGED, rules.smsMode)
        val (mamma, bestemor, ambulanse) = rules.contacts
        assertEquals(listOf(true, true, true), listOf(mamma.smsIn, mamma.smsOut, mamma.mms))
        // D3: independent of the calls - Bestemor calls in but may not text in; she may get texts without calls out.
        assertEquals(listOf(true, false, false, true, false), listOf(bestemor.inbound, bestemor.outbound, bestemor.smsIn, bestemor.smsOut, bestemor.mms))
        // R2-H1: the server never sends sms_in for an emergency number.
        assertFalse(ambulanse.smsIn)
        assertEquals(listOf("DNB", "Posten"), rules.smsNames)
        assertFalse(rules.smsLockText)
        assertEquals(listOf(1 to "+4790000001"), callPolicy.ownNumbers.map { it.slot to it.number })
        assertEquals(listOf("00112233445566778899aabbccddeeff"), callPolicy.smsHeldDrop)
        assertEquals(
            PolicyMmscHosts("24201", listOf("mmsc.example.invalid")),
            ServerJson.decodeFromString(PolicyMmscHosts.serializer(), policy["mmsc_hosts_row"].toString()),
        )
    }

    @Test
    fun `sms_state is encoded exactly as the contract's report`() {
        val state = SmsState(
            roleHeld = true, modeApplied = "managed", received = 12, sent = 4,
            blocked = SmsBlockedCounts(number = 0, short = 1, alphanumeric = 2, malformed = 0, empty = 0, group = 0, badLocation = 0, overCap = 1),
            held = 3, released = 1, expired = 1, parseFailures = 0, mmsWaiting = 0, mmsDataAllowed = true, mmscKnown = false,
            lastError = null, ownNumbers = listOf(SmsOwnNumber(1, "+4790000001"), SmsOwnNumber(2, null)), holdFull = SmsHoldFull(numbers = false, names = true),
            ownLearned = SmsOwnLearned("+4790000002", "+4791234567", 1762700000000, emptyList()),
            hasHistory = true,
        )
        assertEquals(
            contract["report"]!!.jsonObject["sms_state"],
            Json.parseToJsonElement(ServerJson.encodeToString(SmsState.serializer(), state)),
        )
        // Design 80 §4.4: a conflict - no number in use, both seen.
        val conflict = SmsOwnLearned(null, "+4791234567", 1762700400000, listOf("+4790000002", "+4790000003"))
        assertEquals(
            contract["report"]!!.jsonObject["own_learned_conflict"],
            Json.parseToJsonElement(ServerJson.encodeToString(SmsOwnLearned.serializer(), conflict)),
        )
    }

    /** Design 80 D13: the group switch's capability, and `call_policy.sms_groups` - sent true and false, absent from an
     * older server read as true (today's behaviour). */
    @Test
    fun `the group switch is claimed and decodes, absent as on`() {
        assertEquals(contract.str("groups_capability"), SMS_GROUPS_CAPABILITY)
        assertTrue(SMS_GROUPS_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
        val policy = contract["policy"]!!.jsonObject
        val on = ServerJson.decodeFromString(CallPolicy.serializer(), policy["call_policy"].toString())
        assertTrue(on.smsGroups && on.toRules().smsGroups)
        val off = ServerJson.decodeFromString(CallPolicy.serializer(), policy["groups_off"].toString())
        assertFalse(off.smsGroups || off.toRules().smsGroups)
        assertTrue(ServerJson.decodeFromString(CallPolicy.serializer(), """{"managed": true}""").smsGroups)
        // Rules stored before it read as on.
        assertTrue(com.kidslauncher.mdm.calls.decodeCallRules("""{"calls_enabled": true}""")!!.smsGroups)
    }

    /** Design 82 D1/D4: Meldinger's key, and every `app.lock_read` row - the policy's `lock_glance` as it arrived
     * (fresh or from the cache; no key = none) with the call rules' old switch (`null` = no managed rules) gives the
     * word read and the level shown. `app.lock` is the server's side: the three words it sends, the old key true only
     * with `text`. */
    @Test
    fun `Meldinger's lock level is read by D4's table`() {
        val app = contract["app"]!!.jsonObject
        assertEquals(app.str("key"), BUILTIN_MESSAGES)
        assertTrue(BUILTIN_MESSAGES.startsWith(BUILTIN_PREFIX))
        val rules = CallRules(callsEnabled = true, contacts = emptyList(), callsMode = ChannelMode.MANAGED, smsMode = ChannelMode.MANAGED)
        for (row in app["lock_read"]!!.jsonArray.map { it.jsonObject }) {
            val state = when (row["sms_lock_text"]) {
                is JsonNull -> CallPolicyState.Unmanaged
                else -> CallPolicyState.Managed(rules.copy(smsLockText = row.bool("sms_lock_text")))
            }
            val sms = lockGlancePolicy(row["lock_glance"], row.bool("fresh")).sms
            assertEquals(row.toString(), row.str("sms"), sms.wire ?: "legacy")
            assertEquals(row.toString(), row.str("level"), smsLockLevel(sms, state).name.lowercase())
        }
        val sent = app["lock"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("off", "name", "text"), sent.map { it.str("sms") }.toSet())
        for (row in sent) assertEquals(row.toString(), row.str("sms") == "text", row.bool("sms_lock_text"))
    }

    /** Design 82 D9, W1, W2: the capability is claimed, and `app.display` - the key's `launcher_ui.app_display` entry
     * as the server sends it - is Meldinger's alone: read only as a built-in's, never as an installed app's, and it
     * gives the glyph on its colour, the name, and the tile off Home outside a window. */
    @Test
    fun `Meldinger's capability is claimed and its display entry is read as its look`() {
        val app = contract["app"]!!.jsonObject
        assertEquals(app.str("capability"), MESSAGES_APP_CAPABILITY)
        assertTrue(MESSAGES_APP_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
        val map = appDisplayMap(JsonArray(listOf(app["display"]!!)))
        val entry = builtinEntry(map, BUILTIN_MESSAGES)
        assertEquals(AppDisplayEntry("Tekst", "mail", "plum", onHome = false), entry)
        assertNull(appEntry(map, BUILTIN_MESSAGES))
        assertEquals(MessagesLook(AppGlyphs.ICONS["mail"]!!, AppGlyphs.COLORS["plum"]!!.tile, "Tekst"), messagesLook(entry))
        assertFalse(onHomeListable(entry, windowOpen = false))
        assertTrue(onHomeListable(entry, windowOpen = true))
    }

    @Test
    fun `the held post is encoded exactly, and the answers decode`() {
        val held = contract["held"]!!.jsonObject
        val request = held["request"]!!.jsonObject
        val body = ServerJson.decodeFromString(SmsHeldBody.serializer(), request.toString())
        assertEquals(listOf("held", "released", "expired", "dropped", "blocked"), body.rows.map { it.state })
        assertEquals(request, Json.parseToJsonElement(ServerJson.encodeToString(SmsHeldBody.serializer(), body)))
        val (taken, capped) = held["answers"]!!.jsonArray.map { ServerJson.decodeFromString(SmsHeldAnswer.serializer(), it.toString()) }
        assertEquals(SmsHeldAnswer(accepted = 5, capped = false), taken)
        assertEquals(SmsHeldAnswer(accepted = 0, capped = true), capped)
        assertEquals(SmsHeldRefusal(0, "sender"), ServerJson.decodeFromString(SmsHeldRefusal.serializer(), held["refusal"].toString()))
    }

    @Test
    fun `the sender is cleaned by its class before it leaves`() {
        for (row in rows("clean")) {
            val kind = SenderKind.valueOf(row.str("type").uppercase())
            val clean = SenderClean.of(kind, row.str("raw"), "47")
            assertEquals(row.toString(), row.str("class") to row.str("sender"), clean.senderClass.wire to clean.sender)
        }
    }

    @Test
    fun `names - a letter required, 1-11 characters, one fold on both sides`() {
        for (row in rows("names")) {
            assertEquals(row.toString(), row.bool("valid"), SmsNames.valid(row.str("name")))
            row["fold"]?.let { assertEquals(row.toString(), it.jsonPrimitive.content, SmsNames.fold(SmsNames.clean(row.str("name")))) }
        }
        for (row in rows("name_matches")) {
            assertEquals(row.toString(), row.bool("match"), SmsNames.same(row.str("listed"), row.str("sender")))
        }
    }
}
