package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.appdisplay.LOGO_UPLOAD_BUDGET_MS
import com.kidslauncher.mdm.appdisplay.LOGO_UPLOAD_CALL_MS
import com.kidslauncher.mdm.appdisplay.WANTED_LOGOS_MAX_BODY
import com.kidslauncher.mdm.appdisplay.logoHashOf
import com.kidslauncher.mdm.appdisplay.logoUploadPlan
import com.kidslauncher.mdm.appdisplay.readWantedLogosBody
import com.kidslauncher.mdm.appdisplay.uploadCallTimeoutMs
import com.kidslauncher.mdm.appdisplay.uploadGoesOn
import com.kidslauncher.mdm.appdisplay.wantedLogosOf
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest
import java.util.Base64

/** Design 87: the apps' real logos, the shared contract `app_logos.json` (byte-identical with `server/testdata/`, which
 * the server's `tests::app_logos::the_contract` posts and uploads); step 2's reading of the answer and the upload plan
 * (R2-L7). The cache's rules are `AppLogosCacheTest`. */
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

    private fun hash(n: Int) = "%064x".format(n)

    @Test
    fun `the answer is read as the server sent it, else nothing is wanted`() {
        val answer = contract["answer"]!!.toString().toByteArray()
        assertEquals(listOf(contract["png_sha256"]!!.jsonPrimitive.content), wantedLogosOf(200, answer))
        // A 204 (an older server, or no capability), an empty or unreadable body, another code: nothing.
        assertEquals(emptyList<String>(), wantedLogosOf(204, null))
        assertEquals(emptyList<String>(), wantedLogosOf(200, null))
        assertEquals(emptyList<String>(), wantedLogosOf(200, ByteArray(0)))
        assertEquals(emptyList<String>(), wantedLogosOf(200, "<html>".toByteArray()))
        assertEquals(emptyList<String>(), wantedLogosOf(200, "{\"wanted_logos\":7}".toByteArray()))
        assertEquals(emptyList<String>(), wantedLogosOf(500, answer))
        // Past the bound the caller reads (16 KiB + 1): unreadable, nothing.
        val long = "{\"wanted_logos\":[\"${hash(1)}\"],\"pad\":\"${"x".repeat(WANTED_LOGOS_MAX_BODY)}\"}"
        assertEquals(emptyList<String>(), wantedLogosOf(200, long.toByteArray().copyOf(WANTED_LOGOS_MAX_BODY + 1)))
    }

    @Test
    fun `the answer's body is read only up to its bound`() {
        val endless = object : java.io.InputStream() {
            var read = 0L
            override fun read(): Int { read++; return ' '.code }
        }
        assertEquals(WANTED_LOGOS_MAX_BODY + 1, readWantedLogosBody(endless).size)
        assertTrue(endless.read <= WANTED_LOGOS_MAX_BODY + 1)
        val answer = contract["answer"]!!.toString().toByteArray()
        assertEquals(answer.toList(), readWantedLogosBody(answer.inputStream()).toList())
        assertEquals(emptyList<String>(), wantedLogosOf(200, readWantedLogosBody(endless)))
    }

    @Test
    fun `only the phone's own drawn logos are uploaded, at most 40`() {
        val wanted = (1..41).map(::hash)
        assertEquals((1..40).map(::hash), logoUploadPlan(wanted, wanted.toSet()))
        // A hash not in the cache, a repeat or a path in disguise is never sent.
        val own = setOf(hash(1), hash(2))
        assertEquals(listOf(hash(1)), logoUploadPlan(listOf(hash(3), hash(1), hash(1), "../../x", hash(1).uppercase()), own))
        assertEquals(emptyList<String>(), logoUploadPlan(emptyList(), own))
    }

    @Test
    fun `every answer goes on, only no answer ends the step`() {
        for (row in contract["answers"]!!.jsonArray) {
            assertTrue(uploadGoesOn(row.jsonObject["status"]!!.jsonPrimitive.int))
        }
        assertFalse(uploadGoesOn(null))
    }

    @Test
    fun `the uploads keep to 20 s in all and 10 s a call`() {
        assertEquals(LOGO_UPLOAD_CALL_MS, uploadCallTimeoutMs(1_000, 1_000))
        assertEquals(LOGO_UPLOAD_CALL_MS, uploadCallTimeoutMs(1_000, 11_000))
        assertEquals(4_000L, uploadCallTimeoutMs(1_000, 1_000 + LOGO_UPLOAD_BUDGET_MS - 4_000))
        assertNull(uploadCallTimeoutMs(1_000, 1_000 + LOGO_UPLOAD_BUDGET_MS))
    }

    @Test
    fun `the contract's png is a logo the phone would report`() {
        val png = Base64.getDecoder().decode(contract["png_base64"]!!.jsonPrimitive.content)
        assertEquals(contract["png_sha256"]!!.jsonPrimitive.content, logoHashOf(png))
        assertNull("over the upload limit: never reported (R2-L1)", logoHashOf(ByteArray(APP_LOGO_MAX_BYTES + 1)))
    }

    @Test
    fun `the launcher claims the capability`() {
        assertTrue(APP_LOGOS_CAPABILITY in STATUS_CAPABILITIES)
    }

    /** R2-M4: the uploads are cosmetic - after the report, the time-critical steps and the DNS events, before the
     * weather and the self-update commit (which stays last). */
    @Test
    fun `the upload step sits among the cosmetic steps`() {
        val src = listOf("src", "app/src").map { java.io.File(it) }.first { java.io.File(it, "main/java").isDirectory }
        val sync = java.io.File(src, "main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        val body = sync.substringAfter("suspend fun performMdmSync(").substringBefore("\nconst val KIOSK_ESCAPES_CAPABILITY")
        val anchors = listOf(
            "api.sendStatus(", "wantedLogos = try {", "CommandAnswers.flush(context, api)",
            "reportBlockedDnsEvents(context, api)", "AppLogos.upload(context, api, wantedLogos)",
            "WeatherPull.afterSync(", "commitPendingSelfUpdateIfDue(context, api)",
        )
        for (anchor in anchors) {
            assertEquals("$anchor once in performMdmSync", 1, body.split(anchor).size - 1)
        }
        val order = anchors.map { body.indexOf(it) }
        assertTrue("$order", order == order.sorted())
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/app_logos.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
