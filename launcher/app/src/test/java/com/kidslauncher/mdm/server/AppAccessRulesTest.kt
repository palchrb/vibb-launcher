package com.kidslauncher.mdm.server

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 127 §4.1: the launcher's own access rules, `app_access_rules.json` - the grant plan ([accessGrants]), the
 * report's readings ([accessReports]) and the refusals kept between passes ([accessRefusedAfter]). */
class AppAccessRulesTest {
    private val rules = ServerJson.parseToJsonElement(
        javaClass.classLoader!!.getResource("app_access_rules.json")!!.readText(),
    ).jsonObject

    private fun state(name: String) = when (name) {
        "default" -> GRANT_STATE_DEFAULT
        "granted" -> GRANT_STATE_GRANTED
        "denied" -> GRANT_STATE_DENIED
        else -> error("state $name")
    }

    private fun strings(element: JsonElement?) = element?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
    private fun rows(element: JsonElement?) = element?.jsonArray?.map { strings(it) }.orEmpty()
    private fun grants(element: JsonElement?) = rows(element).mapTo(mutableSetOf()) { Grant(it[0], it[1]) }
    private fun grantStates(element: JsonElement?) = rows(element).associate { Grant(it[0], it[1]) to state(it[2]) }
    private fun appKeys(element: JsonElement?) = rows(element).mapTo(mutableSetOf()) { AppKey(it[0], AccessKey.of(it[1])!!) }
    private fun flag(row: JsonObject, key: String) = row[key]?.jsonPrimitive?.content == "true"

    @Test
    fun `every grant row`() {
        val own = rules["own"]!!.jsonPrimitive.content
        val requested = rules["requested"]!!.jsonObject.mapValues { strings(it.value) }
        val ourSigner = strings(rules["same_signer"]).toSet()
        for (row in rules["grants"]!!.jsonArray.map { it.jsonObject }) {
            val name = row["name"]!!.jsonPrimitive.content
            val denied = row["denied"].takeUnless { it == null || it is JsonNull }?.jsonObject
                ?.mapValues { (_, keys) -> strings(keys).mapTo(mutableSetOf()) { AccessKey.of(it)!! } }
            val signed = row["same_signer"]?.let { strings(it).toSet() } ?: ourSigner
            val plan = accessGrants(
                requested, own, denied, flag(row, "window"), flag(row, "call"), grants(row["held"]), grantStates(row["current"]),
            ) { it in signed }
            assertEquals(name, grantStates(row["writes"]), plan.writes)
            assertEquals(name, grants(row["waiting"]), plan.waiting)
            assertEquals(name, plan.waiting.isNotEmpty(), plan.deferred)
            assertEquals(name, true, plan.writes.values.none { it == GRANT_STATE_GRANTED })
        }
    }

    @Test
    fun `every report row`() {
        for (row in rules["reports"]!!.jsonArray.map { it.jsonObject }) {
            val states = row["states"]?.jsonObject.orEmpty().mapValues { state(it.value.jsonPrimitive.content) }
            val refused = strings(row["refused"]).mapTo(mutableSetOf()) { AccessKey.of(it)!! }
            val report = accessReports(strings(row["requested"]), { states[it] ?: GRANT_STATE_DEFAULT }, refused)
            assertEquals(row["name"]!!.jsonPrimitive.content, rows(row["report"]), report.map { listOf(it.first.key, it.second) })
        }
    }

    @Test
    fun `every refusal row`() {
        for (row in rules["refused"]!!.jsonArray.map { it.jsonObject }) {
            assertEquals(
                row["name"]!!.jsonPrimitive.content,
                appKeys(row["after"]),
                accessRefusedAfter(appKeys(row["before"]), appKeys(row["evaluated"]), appKeys(row["waiting"]), appKeys(row["failed"])),
            )
        }
    }

    private fun JsonObject?.orEmpty(): JsonObject = this ?: JsonObject(emptyMap())
}
