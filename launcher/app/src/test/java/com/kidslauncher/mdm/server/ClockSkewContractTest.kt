package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.LocationReport
import com.kidslauncher.mdm.zones.QueuedFix
import com.kidslauncher.mdm.zones.fixAgeMs
import com.kidslauncher.mdm.zones.toReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The phone's clock in the status report (shared contract `clock_skew.json`, byte-identical with `server/testdata/`,
 * whose `tests::clock_skew` runs the server's rows): the reported offset's steps and hysteresis, and a fix's age from
 * elapsed realtime - the server stamps the fix at receipt minus it.
 */
class ClockSkewContractTest {
    private val text = javaClass.classLoader!!.getResource("clock_skew.json")!!.readText()
    private val contract = Json.parseToJsonElement(text).jsonObject

    private fun rows(name: String) = contract[name]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.opt(key: String) = this[key]!!.takeIf { it != JsonNull }?.jsonPrimitive?.longOrNull

    @Test
    fun `the reported offset's steps`() {
        for (row in rows("round")) {
            assertEquals(row["note"].toString(), row["reported"]!!.jsonPrimitive.long, reportedSkew(row["raw"]!!.jsonPrimitive.long, row.opt("previous")))
        }
    }

    @Test
    fun `a fix's age from elapsed realtime`() {
        for (row in rows("phone_age")) {
            val fix = QueuedFix(59.9, 10.7, null, 0L, elapsedMs = row.opt("fix_elapsed_ms"), bootCount = row["fix_boot"]!!.jsonPrimitive.int)
            assertEquals(row["note"].toString(), row.opt("age_ms"), fixAgeMs(fix, row["now_elapsed_ms"]!!.jsonPrimitive.long, row["now_boot"]!!.jsonPrimitive.int))
        }
    }

    @Test
    fun `the report carries the age and leaves it out when unknown`() {
        val fix = QueuedFix(59.9, 10.7, 20f, 1_764_000_000_000L, "gps", elapsedMs = 1_000_000L, bootCount = 42)
        val encoded = ServerJson.encodeToString(LocationReport.serializer(), fix.toReport(1_090_000L, 42))
        assertTrue(encoded, encoded.contains("\"age_ms\":90000"))
        val lost = ServerJson.encodeToString(LocationReport.serializer(), fix.toReport(1_090_000L, 43))
        assertFalse(lost, lost.contains("age_ms"))
    }

    @Test
    fun `the contract is identical to the servers copy`() {
        val serverCopy = File("../../server/testdata/clock_skew.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), text)
    }
}
