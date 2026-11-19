package com.kidslauncher.mdm.setup

import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.map.MapLinksReport
import com.kidslauncher.mdm.map.playLinksDone
import com.kidslauncher.mdm.map.vibbLinksDone
import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 114 §3.1: the phone's side of the shared contract `setup_steps.json` (byte-identical with `server/testdata/`,
 * whose `setup_status::tests` read the same rows): the table, the names, the need rule's rows, the report, the policy
 * and the tick.
 */
class SetupStepsTest {
    private val contractText = javaClass.classLoader!!.getResource("setup_steps.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    private fun strings(dir: String): Map<String, String> =
        Regex("""<string name="([a-z0-9_]+)"[^>]*>([^<]*)</string>""")
            .findAll(File(main, "res/$dir/strings.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("&amp;", "&") }

    private fun resName(id: Int): String = R.string::class.java.fields.first { it.getInt(null) == id }.name

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content

    @Test
    fun `the table is the contract's, in its order`() {
        val rows = contract.getValue("steps").jsonArray.map { it.jsonObject }
        assertEquals(rows.map { it.str("key") }, SetupStep.entries.map { it.key })
        for ((row, step) in rows.zip(SetupStep.entries)) {
            val key = step.key
            assertEquals(key, row.str("need"), step.need.name.lowercase())
            assertEquals(key, row.str("read_by"), step.readBy.name.lowercase())
            assertEquals(key, row.str("when"), step.rule.name.lowercase())
            assertEquals(key, row.str("summary"), step.summary.name.lowercase())
            assertEquals(key, row.str("level"), step.level.name.lowercase())
            assertEquals(key, row.getValue("features").jsonArray.map { it.jsonPrimitive.content }, step.features)
            val page = row.getValue("phone_page")
            assertEquals(key, if (page is JsonNull) null else page.jsonArray.map { it.jsonPrimitive.content }, step.pageActions)
        }
    }

    @Test
    fun `every feature a step names has its place on the PWA`() {
        val features = contract.getValue("features").jsonArray.map { it.jsonObject.str("key") }.toSet()
        assertEquals(features, SetupStep.entries.flatMap { it.features }.toSet())
    }

    @Test
    fun `each step is named as the contract names it, with its one line, in nb and en`() {
        val en = strings("values")
        val nb = strings("values-nb")
        for (row in contract.getValue("steps").jsonArray.map { it.jsonObject }) {
            val step = SetupStep.of(row.str("key"))!!
            val names = row.getValue("names").jsonObject
            assertEquals(step.key, names.str("en"), en[resName(step.nameRes)])
            assertEquals(step.key, names.str("nb"), nb[resName(step.nameRes)])
            // A screen has its one line (the fingerprint's name says it, code QA L8); the two steps that are never
            // screens have none.
            assertEquals(step.key, step.screen && step != SetupStep.FINGERPRINT, step.howRes != null)
            step.howRes?.let {
                assertTrue(step.key, !en[resName(it)].isNullOrBlank())
                assertTrue(step.key, !nb[resName(it)].isNullOrBlank())
            }
        }
        for (name in listOf("parent_pin", "device_owner")) assertTrue(!SetupStep.of(name)!!.screen)
    }

    private fun inputsOf(row: JsonObject) = SetupInputs(
        mask = row.getValue("mask").jsonPrimitive.long,
        clockFormat = row.str("clock_format"),
        callsManaged = row.getValue("calls_managed").jsonPrimitive.boolean,
        androidLock = row.getValue("android_lock").jsonPrimitive.boolean,
        mapLinksOn = row.getValue("map_links_on").jsonPrimitive.boolean,
        mapAppInstalled = row.getValue("map_app_installed").jsonPrimitive.boolean,
    )

    @Test
    fun `every needed row through the one rule`() {
        val rows = contract.getValue("needed").jsonArray.map { it.jsonObject }
        assertTrue(rows.size >= 10)
        for (row in rows) {
            val name = row.str("name")
            val readings = row.getValue("readings").jsonObject.mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.booleanOrNull }
            val ticked = row.getValue("ticked").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val missing = setupMissing(inputsOf(row.getValue("inputs").jsonObject), readings, ticked)
            assertEquals(name, row.getValue("missing").jsonArray.map { it.jsonPrimitive.content }, missing.map { it.key })
            assertEquals(name, row.getValue("count").jsonPrimitive.int, setupCount(missing))
            assertEquals(name, row.getValue("alarm").jsonPrimitive.boolean, setupAlarm(missing))
            assertEquals(name, row.getValue("setup_row").jsonPrimitive.boolean, setupRow(missing, readings))
        }
    }

    @Test
    fun `the report encodes exactly, held and not`() {
        for (name in listOf("report", "report_no_hold")) {
            val row = contract.getValue(name).jsonObject
            val readings = row.filterKeys { !it.startsWith("hold_") }
                .mapValues { (_, v) -> if (v is JsonNull) null else v.jsonPrimitive.booleanOrNull }
            val report = SetupReport(
                readings,
                holdUntilMs = row["hold_until_ms"]?.jsonPrimitive?.long,
                holdLeftMs = row["hold_left_ms"]?.jsonPrimitive?.long,
            )
            assertEquals(name, row, report.json())
            // The key order too: the contract's text is the report's.
            assertEquals(name, row.keys.toList(), report.json().keys.toList())
        }
    }

    @Test
    fun `the policy decodes, and the tick's body and answers`() {
        assertEquals(listOf("vibration", "media_on_lock"), ServerJson.decodeFromJsonElement(SetupPolicy.serializer(), contract.getValue("policy")).ticked)
        assertEquals(emptyList<String>(), ServerJson.decodeFromJsonElement(SetupPolicy.serializer(), contract.getValue("policy_none")).ticked)
        val tick = contract.getValue("tick").jsonObject
        assertEquals(tick.getValue("request"), ServerJson.encodeToJsonElement(SetupTickBody.serializer(), SetupTickBody("touch_feedback")))
        for (row in tick.getValue("phone").jsonArray.map { it.jsonObject }) {
            val status = row.getValue("status").let { if (it is JsonNull) null else it.jsonPrimitive.intOrNull }
            assertEquals("$status", row.str("action"), tickAction(status).name.lowercase())
        }
        // Only a parent step is ever ticked on the phone (the server answers 400 for any other).
        val answers = tick.getValue("answers").jsonArray.map { it.jsonObject }
        for (answer in answers) {
            val key = answer.getValue("body").jsonObject["step"]?.jsonPrimitive?.content
            val parent = key?.let { SetupStep.of(it) }?.readBy == StepReadBy.PARENT
            assertEquals(answer.str("name"), if (parent) 204 else 400, answer.getValue("status").jsonPrimitive.int)
        }
    }

    @Test
    fun `no required step is ever a parent step - no tick can hide one`() {
        assertTrue(SetupStep.entries.none { it.readBy == StepReadBy.PARENT && it.need == StepNeed.REQUIRED })
        assertNotNull(SetupStep.of("message_banners"))
    }

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/setup_steps.json", "../server/testdata/setup_steps.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }

    /** Code QA L6: rows 15-16 read from a `map_links` report as the server's `map_links::setup_part` reads it. */
    @Test
    fun `the map steps read a report as the server does`() {
        for (row in contract.getValue("map_readings").jsonArray.map { it.jsonObject }) {
            val report = ServerJson.decodeFromJsonElement(MapLinksReport.serializer(), row.getValue("report"))
            assertEquals(row.str("name"), row.getValue("map_play_links").jsonPrimitive.booleanOrNull, playLinksDone(report.playServicesLinks))
            assertEquals(row.str("name"), row.getValue("map_vibb_links").jsonPrimitive.boolean, vibbLinksDone(report.vibbHosts))
        }
    }

    /** Code QA M2: `map_links`'s map app is read from the reported packages (a hidden app included), as the server reads
     * `installed_apps`. */
    @Test
    fun `a hidden map app counts as the report lists it`() {
        assertTrue(mapAppInstalled(listOf("com.example", "app.comaps")))
        assertEquals(false, mapAppInstalled(listOf("com.google.android.apps.maps")))
        val readings = File(main, "java/com/kidslauncher/mdm/setup/SetupReadings.kt").readText()
        assertTrue("mapAppInstalled = mapAppInstalled(reportedPackages(context))" in readings)
        assertTrue("controllablePackages(context.packageManager)" in readings.substringAfter("private fun reportedPackages("))
    }
}
