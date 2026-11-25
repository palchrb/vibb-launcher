package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.InstallProgressReport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Why an install failed (upstream review 2026-11-20): the contract `install_failure.json`
 * (in `contracts/core/`, which the server's `install_failure::tests` and
 * `tests::tracked_apps::an_install_failures_reason_is_stored_and_worded` read) - the report's exact
 * body, the launcher's own kinds and every Android result read to its code.
 */
class InstallFailureTest {
    private val text = javaClass.classLoader!!.getResource("install_failure.json")!!.readText()
    private val contract = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `the reports encode exactly as the contract`() {
        val failed = InstallProgressReport(7, percent = 0, failed = true, reason = "INSTALL_FAILED_OLDER_SDK")
        assertEquals(contract["report"], Json.parseToJsonElement(ServerJson.encodeToString(InstallProgressReport.serializer(), failed)))
        // A progress report sends neither `failed` nor a reason.
        val progress = InstallProgressReport(7, percent = 42)
        assertEquals(contract["report_progress"], Json.parseToJsonElement(ServerJson.encodeToString(InstallProgressReport.serializer(), progress)))
    }

    @Test
    fun `the launchers own kinds are the contracts`() {
        assertEquals(contract["kinds"]!!.jsonArray.map { it.jsonPrimitive.content }, InstallFailureKind.ALL)
    }

    @Test
    fun `every Android result reads to its code and never to its text`() {
        for (row in contract["parse"]!!.jsonArray) {
            val o = row.jsonObject
            val message = o["message"]!!.takeIf { it != JsonNull }?.jsonPrimitive?.contentOrNull
            assertEquals(row.toString(), o["reason"]!!.jsonPrimitive.contentOrNull, installFailureReason(o["status"]!!.jsonPrimitive.int, message))
        }
        // A code longer than the server keeps is none.
        assertNull(installFailureReason(1, "INSTALL_FAILED_" + "X".repeat(INSTALL_FAILURE_MAX_CODE)))
    }
}
