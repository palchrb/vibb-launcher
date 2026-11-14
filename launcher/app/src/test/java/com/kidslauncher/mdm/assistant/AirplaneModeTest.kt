package com.kidslauncher.mdm.assistant

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 98: the shared contract `airplane_mode.json` (byte-identical with `server/testdata/`, whose
 * `tests::airplane_mode` posts the reports and runs `clean` and `emergency.line`). */
class AirplaneModeTest {
    private val contractText = javaClass.classLoader!!.getResource("airplane_mode.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]!!.let { if (it is JsonNull) null else it.jsonPrimitive.content }

    @Test
    fun `the reports encode exactly the contract's`() {
        val closed = AirplanePeriod(1763150580000, AirplaneBy.KID, 1763183400000, AirplaneBy.KID)
        val report = AirplaneModeReport(
            on = false, goingOn = false, assistant = true, wifiKept = true, lastError = null,
            defaultAppsChanged = emptyList(), periods = listOf(closed),
        )
        assertEquals(contract["report"], ServerJson.encodeToJsonElement(AirplaneModeReport.serializer(), report))
        val open = AirplaneModeReport(
            on = true, goingOn = false, assistant = true, wifiKept = null, lastError = AirplaneError.TIMEOUT,
            defaultAppsChanged = listOf("call_screening", "browser"),
            periods = listOf(
                closed.copy(offBy = AirplaneBy.EMERGENCY),
                AirplanePeriod(1763190000000, AirplaneBy.OTHER, null, null),
            ),
        )
        assertEquals(contract["report_open"], ServerJson.encodeToJsonElement(AirplaneModeReport.serializer(), open))
    }

    @Test
    fun `the capability and the bit`() {
        assertEquals(contract.str("capability"), AIRPLANE_MODE_CAPABILITY)
        assertEquals(contract["bit"]!!.jsonPrimitive.content.toLong(), com.kidslauncher.mdm.server.QuickControlFeature.AIRPLANE)
        assertTrue(contract["rows"]!!.jsonArray.isNotEmpty())
    }

    @Test
    fun `the contract is identical to the server's copy`() {
        // Gradle runs unit tests with the module directory (launcher/app/) as the working directory.
        val serverCopy = java.io.File("../../server/testdata/airplane_mode.json")
        assertTrue("${serverCopy.absolutePath} not found", serverCopy.isFile)
        assertEquals("${serverCopy.path} differs from the launcher's copy", serverCopy.readText(), contractText)
    }
}
