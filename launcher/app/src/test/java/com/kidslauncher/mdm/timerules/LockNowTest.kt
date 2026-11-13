package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.lock.cardRule
import com.kidslauncher.mdm.lock.lockEnd
import com.kidslauncher.mdm.server.STATUS_CAPABILITIES
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 93 §9.3: a rule turned on now ("Slå på nå"), the shared contract `lock_now.json` (byte-identical with
 * `server/testdata/`, whose `policy` the server's `lock_now::tests::the_contract` serves from seeded rows); every case
 * run through [inForceAt], [decideTimeLock], [cardRule] and [lockEnd], and the capability this launcher claims. */
class LockNowTest {
    private val contractText = javaClass.classLoader!!.getResource("lock_now.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun policy(name: String): TimePolicy =
        ServerJson.decodeFromJsonElement(TimePolicy.serializer(), contract[name]!!)

    @Test
    fun `the served policy decodes, the copy with its end and card choice`() {
        val policy = policy("policy")
        val copy = policy.rules[0]
        assertEquals(-1L, copy.id)
        assertEquals("", copy.name)
        assertEquals(KIND_BEDTIME, copy.kind)
        assertEquals(true, copy.callsAllowed)
        assertEquals(listOf("me.vibb.music"), copy.exemptApps)
        assertEquals(false, copy.calendarAllowed)
        assertEquals(true, copy.silent)
        assertEquals(listOf(TimeWindow(720, 1080)) + List(6) { null }, copy.days)
        assertEquals(1794848400000L, copy.untilMs)
        assertEquals(true, copy.cardFirst)
        for (rule in policy.rules.drop(1)) {
            assertTrue("${rule.id}", rule.id > 0)
            assertNull(rule.untilMs)
            assertEquals(false, rule.cardFirst)
        }
        assertEquals(listOf(-1L, 1L, 2L), policy.rules.map { it.id })
        assertEquals(listOf(-1L, 1L, 3L, 2L), policy("policy_end_locked").rules.map { it.id })
        // The pause of the turned-on rule and its twin for the copy: the same minutes and expiry.
        val (pause, twin) = policy.lifts
        assertEquals(Lift(5, TARGET_RULE, 2, 30, 1794828600000L), pause)
        assertEquals(Lift(-5, TARGET_RULE, -1, 30, 1794828600000L), twin)
        // The cache keeps the rule whole (PolicyGate drops only the lifts).
        val reencoded = ServerJson.encodeToString(TimePolicy.serializer(), policy)
        assertEquals(policy, ServerJson.decodeFromString(TimePolicy.serializer(), reencoded))
    }

    /** Each case at its local time in its zone, with no lift and no budget: whether it locks, the card's rule and end. */
    @Test
    fun `every case locks and names the card as the contract says`() {
        for (case in contract["cases"]!!.jsonArray.map { it.jsonObject }) {
            val zone = ZoneId.of(case["zone"]!!.jsonPrimitive.content)
            val at = LocalDateTime.parse(case["at"]!!.jsonPrimitive.content)
            val policy = policy(case["policy"]!!.jsonPrimitive.content).inForceAt(at.atZone(zone).toInstant().toEpochMilli())
            val lock = decideTimeLock(policy, at, overrideActive = false)
            val label = "${case["policy"]} ${case["at"]}"
            assertEquals(label, case["locked"]!!.jsonPrimitive.content.toBoolean(), lock.locked)
            val pick = cardRule(lock, at) { end -> lockEnd(policy, lock, RuleLifts(), null, null, zone, at, end) }
            assertEquals(label, case["card_rule"]!!.jsonPrimitive.longOrNull, pick.rule?.id)
            assertEquals(label, case["card_until"]!!.jsonPrimitive.let { if (it.isString) it.content else null }, pick.end?.toString())
        }
    }

    @Test
    fun `this launcher claims the contract's capability`() {
        assertEquals(contract["capability"]!!.jsonPrimitive.content, LOCK_NOW_CAPABILITY)
        assertTrue(LOCK_NOW_CAPABILITY in STATUS_CAPABILITIES)
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/lock_now.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
