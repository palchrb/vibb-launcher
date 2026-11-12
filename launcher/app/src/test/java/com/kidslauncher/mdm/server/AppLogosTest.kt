package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.APP_LOGOS_CAPABILITY
import com.kidslauncher.mdm.server.dto.APP_LOGOS_MAX_WANTED
import com.kidslauncher.mdm.server.dto.APP_LOGO_MAX_BYTES
import com.kidslauncher.mdm.server.dto.APP_LOGO_SIZE_PX
import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import com.kidslauncher.mdm.server.dto.WantedLogos
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** Design 87 step 1: the apps' real logos, the shared contract `app_logos.json` (byte-identical with
 * `server/testdata/`, which the server's `tests::app_logos::the_contract` posts and uploads). */
class AppLogosTest {
    private val contractText = javaClass.classLoader!!.getResource("app_logos.json")!!.readText()
    private val contract = Json.parseToJsonElement(contractText).jsonObject

    @Test
    fun `the constants are the contract's`() {
        assertEquals(APP_LOGOS_CAPABILITY, contract["capability"]!!.jsonPrimitive.content)
        assertEquals(APP_LOGO_SIZE_PX, contract["size_px"]!!.jsonPrimitive.int)
        assertEquals(APP_LOGO_MAX_BYTES, contract["max_bytes"]!!.jsonPrimitive.int)
        assertEquals(APP_LOGOS_MAX_WANTED, contract["max_wanted"]!!.jsonPrimitive.int)
    }

    @Test
    fun `the png's hash is the contract's`() {
        val png = Base64.getDecoder().decode(contract["png_base64"]!!.jsonPrimitive.content)
        val hash = MessageDigest.getInstance("SHA-256").digest(png).joinToString("") { "%02x".format(it) }
        assertEquals(contract["png_sha256"]!!.jsonPrimitive.content, hash)
        assertTrue("a ${png.size} byte PNG", png.size <= APP_LOGO_MAX_BYTES)
    }

    @Test
    fun `the report encodes exactly the contract's, logo only where drawn`() {
        val hash = contract["png_sha256"]!!.jsonPrimitive.content
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            installedApps = listOf(
                InstalledApp("org.example.drawing", "Drawing", false, logo = hash),
                InstalledApp("org.example.notes", "Notes", true),
            ),
            capabilities = listOf(APP_LOGOS_CAPABILITY),
        )
        assertEquals(contract["report"], ServerJson.encodeToJsonElement(StatusReportRequest.serializer(), report))
    }

    @Test
    fun `the answer decodes, and an empty body is nothing wanted`() {
        val answer = ServerJson.decodeFromJsonElement(WantedLogos.serializer(), contract["answer"]!!)
        assertEquals(WantedLogos(listOf(contract["png_sha256"]!!.jsonPrimitive.content)), answer)
        assertEquals(WantedLogos(), ServerJson.decodeFromString(WantedLogos.serializer(), "{}"))
        // A newer server's key is ignored.
        assertEquals(
            WantedLogos(listOf("a")),
            ServerJson.decodeFromString(WantedLogos.serializer(), """{"wanted_logos":["a"],"later":1}"""),
        )
    }

    @Test
    fun `the upload's path and answers are the contract's`() {
        val hash = contract["png_sha256"]!!.jsonPrimitive.content
        val upload = contract["upload"]!!.jsonObject
        assertEquals("/api/devices/app-logos/$hash", upload["path"]!!.jsonPrimitive.content)
        assertEquals("image/png", upload["content_type"]!!.jsonPrimitive.content)
        val answers = contract["answers"]!!.jsonArray.associate {
            it.jsonObject["case"]!!.jsonPrimitive.content to it.jsonObject["status"]!!.jsonPrimitive.int
        }
        assertEquals(
            mapOf(
                "stored" to 204, "bad_hash" to 400, "mismatch" to 400, "type" to 415, "too_large" to 413,
                "not_reported" to 409, "wrong_size" to 422, "not_png" to 422,
            ),
            answers,
        )
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/app_logos.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
