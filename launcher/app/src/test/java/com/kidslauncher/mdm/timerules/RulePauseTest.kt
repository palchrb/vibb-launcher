package com.kidslauncher.mdm.timerules

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 98 §4.4: the shared contract `rule_pause.json` (byte-identical with `server/testdata/`, whose
 * `tests::rule_pause` posts `request` and produces every answer row). */
class RulePauseTest {
    private val contractText = javaClass.classLoader!!.getResource("rule_pause.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    @Test
    fun `the request encodes exactly the contract's`() {
        val request = RulePauseRequest(
            key = "0123456789abcdef0123456789abcdef", minutes = 30, startedAtMs = 1794826200000,
            rules = listOf(RulePauseRuleBody(2, 1794828000000), RulePauseRuleBody(-1, 1794828000000)),
        )
        assertEquals(contract["request"], ServerJson.encodeToJsonElement(RulePauseRequest.serializer(), request))
    }

    @Test
    fun `the served policy decodes with the phone key and copy_of`() {
        val policy = ServerJson.decodeFromJsonElement(PolicyResponse.serializer(), contract["policy"]!!)
        assertTrue(policy.rulePause)
        val time = policy.timePolicy!!
        assertEquals(listOf(2L, null), time.rules.map { it.copyOf })
        assertEquals(listOf(null, "0123456789abcdef0123456789abcdef", "0123456789abcdef0123456789abcdef"), time.lifts.map { it.phoneKey })
        assertNull(ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").timePolicy)
        assertEquals(false, ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").rulePause)
        // The view keeps each phone-key lift with its rule (code QA M1): the row and its copy's twin, not the PWA's lift.
        val key = "0123456789abcdef0123456789abcdef"
        val view = pausePolicyView(time, 42)
        assertEquals(setOf(PausePolicyLift(key, 2), PausePolicyLift(key, -1)), view.lifts)
        assertEquals(setOf(key), view.keys)
        assertEquals(PausePolicyView(42, emptySet()), pausePolicyView(null, 42))
    }

    private fun rows(name: String) = contract[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.longOrNull(key: String) = this[key]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLong()

    @Test
    fun `the plans - only the rules in force, each its own end, a copy with its rule, never all`() {
        val policy = ServerJson.decodeFromJsonElement(TimePolicy.serializer(), contract["plan_policy"]!!)
        val zone = java.time.ZoneId.of(contract["plan_zone"]!!.jsonPrimitive.content)
        for (row in rows("plans")) {
            val now = row.longOrNull("at_ms")!!
            val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), zone)
            val plan = rulePausePlan(
                policy.inForceAt(now), at, zone, RuleLifts(), row["budget_exhausted"]!!.jsonPrimitive.content.toBoolean(),
                row["minutes"]!!.jsonPrimitive.content.toInt(), now,
            )
            val case = row["case"]!!.jsonPrimitive.content
            assertEquals(case, row["offered"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }, plan.offered)
            val expected = row["rules"]!!.jsonArray.map { it.jsonObject }.map { RulePauseRuleBody(it.longOrNull("id")!!, it.longOrNull("until_ms")!!) }
            assertEquals(case, expected, plan.rules)
        }
        // A rule already lifted isn't paused again; the lengths alone without a choice.
        val at = java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(1794859800000), zone)
        val lifted = rulePausePlan(policy, at, zone, RuleLifts(ruleIds = setOf(3L)), false, 30, 1794859800000)
        assertEquals(listOf(2L), lifted.rules.map { it.ruleId })
        assertEquals(emptyList<RulePauseRuleBody>(), rulePausePlan(policy, at, zone, RuleLifts(), false, null, 1794859800000).rules)
    }

    @Test
    fun `the adoption rows - a reboot, a pruned entry, the clock set back, ended in the app, one rule of two ended`() {
        for (row in rows("adopt")) {
            val entry = row["entry"]!!.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(PhonePause.serializer(), it) }
            val sync = row["sync"]!!.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(PhonePauseSync.serializer(), it) }
            val view = row["policy"]!!.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(PausePolicyView.serializer(), it) }
            val c = row["clocks"]!!.jsonObject
            val clocks = Clocks(c.longOrNull("wall_ms")!!, c.longOrNull("elapsed_ms")!!, c.longOrNull("boot_count")!!.toInt())
            val lifted = row["lifted"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }.toSet()
            assertEquals(row["case"]!!.jsonPrimitive.content, lifted, phonePauseLifts(entry, sync, view, clocks))
        }
    }

    @Test
    fun `every answer reads to its action`() {
        for (row in rows("phone_answers")) {
            val answer = row["answer"]!!.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(RulePauseAnswer.serializer(), it) }
            val action = rulePauseAction(row.longOrNull("status")?.toInt(), answer, row["past_end"]!!.jsonPrimitive.content.toBoolean())
            assertEquals(row.toString(), row["action"]!!.jsonPrimitive.content, action.wire)
            if (action == RulePauseAction.ACKED) assertEquals(row["lift_ids"]!!.jsonArray.map { it.jsonPrimitive.content.toLong() }, answer!!.liftIds)
        }
        // Every server answer row decodes for the phone.
        for (row in rows("answers")) {
            row["answer"]!!.takeIf { it !is JsonNull }?.let { ServerJson.decodeFromJsonElement(RulePauseAnswer.serializer(), it) }
        }
    }

    @Test
    fun `the stored entry holds no PIN material, a policy carrying the key acks it, the pruning`() {
        val entry = phonePauseOf("0123456789abcdef0123456789abcdef", 30, listOf(RulePauseRuleBody(2, 1_800_000)), Clocks(0, 100, 3))
        val stored = ServerJson.encodeToJsonElement(PhonePause.serializer(), entry).jsonObject
        assertEquals(setOf("key", "minutes", "rules", "elapsed_start_ms", "boot_count"), stored.keys)
        assertEquals(setOf("id", "until_wall_ms", "duration_ms"), stored["rules"]!!.jsonArray.single().jsonObject.keys)
        val sync = ServerJson.encodeToJsonElement(PhonePauseSync.serializer(), PhonePauseSync("k", true, 5, listOf(7))).jsonObject
        assertEquals(setOf("key", "posted", "acked_at_wall_ms", "lift_ids"), sync.keys)
        // A policy carrying the key acks a pause the post hasn't; an acked one keeps its ack.
        val view = PausePolicyView(10, setOf(PausePolicyLift("k", 2)))
        assertEquals(10L, syncAfterPolicy(null, "k", view)!!.ackedAtWallMs)
        assertEquals(5L, syncAfterPolicy(PhonePauseSync("k", true, 5, emptyList()), "k", view)!!.ackedAtWallMs)
        assertNull(syncAfterPolicy(null, "other", view))
        // A posted entry is kept until its last end plus the slack, at most eight.
        val old = phonePauseOf("a".repeat(32), 15, listOf(RulePauseRuleBody(2, 1_000)), Clocks(0, 0, 1))
        val fresh = phonePauseOf("b".repeat(32), 15, listOf(RulePauseRuleBody(2, 10_000_000)), Clocks(0, 0, 1))
        assertEquals(listOf(fresh), phonePausesAfterAdd(listOf(old), fresh, 1_000 + PHONE_PAUSE_SLACK_MS, setOf(old.key)))
        val many = (0 until 10).map { phonePauseOf("%032d".format(it), 15, listOf(RulePauseRuleBody(2, 10_000_000)), Clocks(0, 0, 1)) }
        assertEquals(PHONE_PAUSES_MAX, phonePausesAfterAdd(many.dropLast(1), many.last(), 0, emptySet()).size)
        // Code QA L5: one never posted (a flight, no network) stays past its end until posted, or the server's 7 days.
        assertEquals(listOf(old, fresh), phonePausesAfterAdd(listOf(old), fresh, 1_000 + PHONE_PAUSE_SLACK_MS, emptySet()))
        assertEquals(listOf(fresh), phonePausesAfterAdd(listOf(old), fresh, PHONE_PAUSE_POST_MAX_AGE_MS, emptySet()))
        // Over the cap the oldest posted go first, then the oldest.
        val posted = setOf(many[3].key, many[5].key)
        val capped = phonePausesAfterAdd(many.take(8), many[8], 0, posted)
        assertEquals(many.take(8) - many[3] + many[8], capped)
        assertEquals(many.drop(1).take(7) + many[8], phonePausesAfterAdd(many.take(8), many[8], 0, emptySet()))
    }

    @Test
    fun `a phone_key lift never lifts through a record`() {
        val lifts = listOf(Lift(7, TARGET_RULE, 2, 30, 10_000_000, phoneKey = "k"), Lift(5, TARGET_RULE, 3, 30, 10_000_000))
        val clocks = Clocks(1_000, 1_000, 1)
        val records = updateLiftRecords(emptyList(), lifts, clocks)
        assertEquals(listOf(5L), records.map { it.id })
        assertEquals(setOf(3L), activeRuleLifts(records, lifts, clocks).ruleIds)
    }

    @Test
    fun `the lengths and the capability`() {
        assertEquals(contract["lengths"]!!.jsonArray.map { it.jsonPrimitive.content.toInt() }, RULE_LIFT_MINUTES)
        assertEquals(contract["capability"]!!.jsonPrimitive.content, RULE_PAUSE_CAPABILITY)
        assertTrue(RULE_PAUSE_CAPABILITY in com.kidslauncher.mdm.server.STATUS_CAPABILITIES)
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        val serverCopy = java.io.File("../../server/testdata/rule_pause.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
