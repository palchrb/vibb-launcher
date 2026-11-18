package com.kidslauncher.mdm.map

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 110: the shared contract `map_links/contract.json` (byte-identical with `server/testdata/map_links/`, whose
 * `integrations::map_links::tests` produces its rows): the link rules through [MapLinkForms], the report encoded
 * exactly, the policy decoded and every answer mapped to the phone's action. */
class MapLinksContractTest {
    private val contractText = javaClass.classLoader!!.getResource("map_links/contract.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun rows(key: String) = contract[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = this[key]!!.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/map_links/contract.json", "../server/testdata/map_links/contract.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    @Test
    fun `the link rules are the server's`() {
        for (row in rows("short_links")) assertEquals(row.text("url"), row.text("short"), MapLinkForms.shortLink(row.text("url")!!))
        for (row in rows("long_hosts")) {
            assertEquals(row.text("url"), row.text("map") == "true", MapLinkForms.longMapUrl(row.text("url")!!))
        }
        for (row in rows("feature_ids")) assertEquals(row.text("url"), row.text("ftid"), MapLinkForms.featureId(row.text("url")!!))
    }

    @Test
    fun `the report rows are encoded exactly`() {
        for (key in listOf("report", "report_steps_left", "report_hidden", "report_held_by_maps")) {
            val row = contract[key]!!
            val report = ServerJson.decodeFromJsonElement(MapLinksReport.serializer(), row)
            assertEquals(key, row, ServerJson.encodeToJsonElement(MapLinksReport.serializer(), report))
            assertTrue(key, report.target in MapLinks.TARGETS)
            assertTrue(key, report.targetState in TargetState.entries.map { it.wire })
        }
    }

    @Test
    fun `the policy rows decode, and a policy without the key is off`() {
        for ((name, row) in contract["policy"]!!.jsonObject) {
            val policy = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist": [], "map_links": $row}""")
            assertEquals(name, row.jsonObject["on"]!!.jsonPrimitive.content == "true", mapLinksOn(policy))
        }
        assertEquals(false, mapLinksOn(ServerJson.decodeFromString(PolicyResponse.serializer(), """{"allowlist": []}""")))
        assertEquals(false, mapLinksOn(null))
    }

    @Test
    fun `every answer maps to the phone's action`() {
        val resolve = contract["resolve"]!!.jsonObject
        val requests = resolve["requests"]!!.jsonObject
        // The two request shapes, encoded exactly.
        assertEquals(requests["short"], ServerJson.encodeToJsonElement(MapResolveRequest.serializer(), MapResolveRequest(url = requests["short"]!!.jsonObject.text("url"))))
        val ftid = requests["ftid"]!!.jsonObject
        assertEquals(ftid, ServerJson.encodeToJsonElement(MapResolveRequest.serializer(), MapResolveRequest(ftid = ftid.text("ftid"), text = ftid.text("text"))))
        for (row in resolve["answers"]!!.jsonArray.map { it.jsonObject }) {
            val case = row.text("case")
            val answer = ServerJson.decodeFromJsonElement(MapResolveAnswer.serializer(), row["body"]!!)
            val short = row.text("request") == "short"
            assertEquals(case, row.text("phone"), mapLinkAnswer(row["status"]!!.jsonPrimitive.int, answer, short))
            // And what the kid gets: a pin, the long link read, a search, or a note - never nothing.
            val parsed = if (short) {
                MapParse(MapParse.Kind.RESOLVE, short = requests["short"]!!.jsonObject.text("url"))
            } else {
                MapLinkForms.parse("https://maps.google.com?q=${ftid.text("text")!!.replace(" ", "+")}&ftid=${ftid.text("ftid")}")
            }
            val step = mapLinkAfter(row["status"]!!.jsonPrimitive.int, answer, parsed)
            assertTrue(case, step is MapStep.Open || step is MapStep.Note)
        }
        // The place's answer gives the pin with its name and address.
        val place = resolve["answers"]!!.jsonArray.map { it.jsonObject }.first { it.text("case") == "short_place" }
        val step = mapLinkAfter(200, ServerJson.decodeFromJsonElement(MapResolveAnswer.serializer(), place["body"]!!), MapParse(MapParse.Kind.RESOLVE, short = "x"))
        assertEquals(MapStep.Open("geo:59.922362,10.686889?z=17&q=59.922362,10.686889(Artbox%20AS%2C%20Drammensveien%20133%2C%200277%20Oslo)"), step)
    }
}
